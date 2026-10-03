//! Events — система событий от Rust к Kotlin
//! Все события которые Kotlin получает через callback

use std::collections::VecDeque;
use std::sync::Mutex;

// ============================================================
// Event Types
// ============================================================

/// Все типы событий от Rust-ядра к Kotlin UI
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CoreEvent {
    /// Ядро запущено и готово
    EngineStarted { node_id: String },

    /// Ядро остановлено
    EngineStopped,

    /// Статус сети изменился
    NetworkStatusChanged { status: String },

    /// Новый peer обнаружен
    PeerDiscovered {
        peer_id: String,
        display_name: String,
        is_local: bool,
    },

    /// Peer отключился
    PeerLost { peer_id: String },

    /// Входящее сообщение
    MessageReceived {
        message_id: String,
        chat_id: String,
        sender_id: String,
        text: String,
        timestamp: i64,
    },

    /// K3: бинарный кусок файла из прямого QUIC-потока (кадр APUF,
    /// `network::file_wire`). Это не сообщение переписки: ни чата, ни
    /// отправителя в кадре нет - получатель опознаёт передачу по
    /// `transfer_id`, а подлинность байтов подтверждает AES-GCM тег куска.
    ///
    /// Вариант был потерян при переносе K3: код события слал и разбирал его
    /// на обеих сторонах (движок, мост, Kotlin), а в самом перечислении
    /// объявления не было - `main` не собирался, и первым это увидел
    /// `cargo check` на pull request (CI тега упал бы уже после выпуска).
    FileChunkReceived {
        /// Идентификатор передачи, 32 знака hex.
        transfer_id: String,
        /// Номер куска файла (с нуля).
        chunk_index: u64,
        /// Смещение внутри целого зашифрованного куска (шифртекст + тег AEAD).
        chunk_offset: u32,
        /// Полная длина зашифрованного куска; повторяется в каждом кадре.
        ciphertext_chunk_len: u32,
        /// Диапазон шифртекста этого кадра.
        ciphertext: Vec<u8>,
    },

    /// Статус сообщения изменился
    MessageStatusChanged { message_id: String, status: String },

    /// Сообщение доставлено
    MessageDelivered { message_id: String },

    /// Ошибка
    Error { code: String, message: String },

    /// Генерация ключей завершена
    KeysGenerated { public_key: String },
}

impl CoreEvent {
    /// Тип события как строка (для Kotlin)
    pub fn event_type(&self) -> &'static str {
        match self {
            CoreEvent::EngineStarted { .. } => "engine_started",
            CoreEvent::EngineStopped => "engine_stopped",
            CoreEvent::NetworkStatusChanged { .. } => "network_status_changed",
            CoreEvent::PeerDiscovered { .. } => "peer_discovered",
            CoreEvent::PeerLost { .. } => "peer_lost",
            CoreEvent::MessageReceived { .. } => "message_received",
            CoreEvent::FileChunkReceived { .. } => "file_chunk_received",
            CoreEvent::MessageStatusChanged { .. } => "message_status_changed",
            // "delivery_ack" — Kotlin (CoreServerService) ждёт именно это имя → ставит DELIVERED
            CoreEvent::MessageDelivered { .. } => "delivery_ack",
            CoreEvent::Error { .. } => "error",
            CoreEvent::KeysGenerated { .. } => "keys_generated",
        }
    }

    /// Является ли событие ошибкой?
    pub fn is_error(&self) -> bool {
        matches!(self, CoreEvent::Error { .. })
    }

    /// Является ли событие сетевым?
    pub fn is_network(&self) -> bool {
        matches!(
            self,
            CoreEvent::NetworkStatusChanged { .. }
                | CoreEvent::PeerDiscovered { .. }
                | CoreEvent::PeerLost { .. }
        )
    }

    /// Является ли событие сообщением?
    pub fn is_message(&self) -> bool {
        matches!(
            self,
            CoreEvent::MessageReceived { .. }
                | CoreEvent::MessageStatusChanged { .. }
                | CoreEvent::MessageDelivered { .. }
        )
    }

    /// Приоритет при переполнении шины (чем больше число - тем раньше
    /// выкидываем). Раунд 119: входящее сообщение переписки терять НЕЛЬЗЯ
    /// НИКОГДА. Раньше при переполнении молча выкидывалось самое старое
    /// событие, и им мог оказаться MessageReceived: после обновления
    /// приложения Kotlin начинает пить события с опозданием, ядро в это
    /// время заливает шину retained-presence/gossip/кусками файлов -
    /// входящее сообщение выбрасывалось, а повторная доставка подавлялась
    /// durable tombstone-ом (см. ядро) - сообщение пропадало навсегда при
    /// «доставленном» статусе у отправителя.
    ///
    /// 0 - не выбрасывать никогда (текст переписки, подтверждение доставки).
    /// 1 - потеря чинится протоколом (галочки пересинхронизируются, куски
    ///     файлов пере-запрашиваются приёмником).
    /// 2 - служебная погода сети (presence, gossip) - дёшево потерять.
    pub fn eviction_rank(&self) -> u8 {
        match self {
            CoreEvent::MessageReceived { .. } | CoreEvent::MessageDelivered { .. } => 0,
            CoreEvent::MessageStatusChanged { .. } | CoreEvent::FileChunkReceived { .. } => 1,
            _ => 2,
        }
    }
}

impl std::fmt::Display for CoreEvent {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "CoreEvent::{}", self.event_type())
    }
}

// ============================================================
// Event Bus
// ============================================================

/// Шина событий — очередь событий для Kotlin
pub struct EventBus {
    queue: Mutex<VecDeque<CoreEvent>>,
    max_size: usize,
}

impl EventBus {
    pub fn new(max_size: usize) -> Self {
        Self {
            queue: Mutex::new(VecDeque::new()),
            max_size,
        }
    }

    pub fn with_defaults() -> Self {
        Self::new(1000)
    }

    /// Отправить событие в шину
    ///
    /// Раунд 119: при переполнении выкидываем сначала САМОЕ СВЕЖЕЕ
    /// служебное событие (rank 2: presence/gossip), затем пере-запрашиваемые
    /// (rank 1: куски файлов, статусы). События ранга 0 (входящее сообщение,
    /// подтверждение доставки) не выбрасываются никогда; если очередь состоит
    /// только из них - позволяем ей мягко вырасти: drain Kotlin'а мгновенно
    /// разгрузит её, а потерять переписку хуже, чем ненадолго занять память.
    pub fn emit(&self, event: CoreEvent) {
        let mut queue = self.queue.lock().unwrap();
        if queue.len() >= self.max_size {
            let victim = (1..=2).find_map(|rank| {
                queue.iter().rposition(|e| e.eviction_rank() == rank)
            });
            match victim {
                Some(i) => {
                    queue.remove(i);
                }
                None => {
                    tracing::warn!(
                        "EventBus overflow: only critical message events queued, growing softly"
                    );
                }
            }
        }
        queue.push_back(event);
    }

    /// Получить одно событие (для polling из Kotlin)
    pub fn poll(&self) -> Option<CoreEvent> {
        self.queue.lock().unwrap().pop_front()
    }

    /// Получить все события сразу
    pub fn drain(&self) -> Vec<CoreEvent> {
        let mut queue = self.queue.lock().unwrap();
        queue.drain(..).collect()
    }

    /// Сколько событий в очереди
    pub fn len(&self) -> usize {
        self.queue.lock().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.queue.lock().unwrap().is_empty()
    }

    /// Очистить очередь
    pub fn clear(&self) {
        self.queue.lock().unwrap().clear();
    }

    /// Есть ли события определённого типа?
    pub fn has_event_type(&self, event_type: &str) -> bool {
        self.queue
            .lock()
            .unwrap()
            .iter()
            .any(|e| e.event_type() == event_type)
    }
}

impl Default for EventBus {
    fn default() -> Self {
        Self::with_defaults()
    }
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    // --- CoreEvent ---

    #[test]
    fn test_event_type_strings() {
        assert_eq!(
            CoreEvent::EngineStarted {
                node_id: "n1".into()
            }
            .event_type(),
            "engine_started"
        );
        assert_eq!(CoreEvent::EngineStopped.event_type(), "engine_stopped");
        assert_eq!(
            CoreEvent::NetworkStatusChanged {
                status: "connected".into()
            }
            .event_type(),
            "network_status_changed"
        );
        assert_eq!(
            CoreEvent::PeerDiscovered {
                peer_id: "p1".into(),
                display_name: "Alice".into(),
                is_local: true
            }
            .event_type(),
            "peer_discovered"
        );
        assert_eq!(
            CoreEvent::PeerLost {
                peer_id: "p1".into()
            }
            .event_type(),
            "peer_lost"
        );
        assert_eq!(
            CoreEvent::Error {
                code: "E01".into(),
                message: "err".into()
            }
            .event_type(),
            "error"
        );
    }

    #[test]
    fn test_event_is_error() {
        assert!(CoreEvent::Error {
            code: "E01".into(),
            message: "fail".into()
        }
        .is_error());
        assert!(!CoreEvent::EngineStopped.is_error());
    }

    #[test]
    fn test_event_is_network() {
        assert!(CoreEvent::PeerDiscovered {
            peer_id: "p1".into(),
            display_name: "A".into(),
            is_local: false
        }
        .is_network());
        assert!(CoreEvent::PeerLost {
            peer_id: "p1".into()
        }
        .is_network());
        assert!(CoreEvent::NetworkStatusChanged {
            status: "offline".into()
        }
        .is_network());
        assert!(!CoreEvent::EngineStopped.is_network());
    }

    #[test]
    fn test_event_is_message() {
        assert!(CoreEvent::MessageReceived {
            message_id: "m1".into(),
            chat_id: "c1".into(),
            sender_id: "u1".into(),
            text: "hi".into(),
            timestamp: 0,
        }
        .is_message());
        assert!(CoreEvent::MessageDelivered {
            message_id: "m1".into()
        }
        .is_message());
        assert!(!CoreEvent::EngineStopped.is_message());
    }

    #[test]
    fn test_event_display() {
        let e = CoreEvent::EngineStopped;
        assert_eq!(format!("{}", e), "CoreEvent::engine_stopped");
    }

    // --- EventBus ---

    #[test]
    fn test_bus_initial_empty() {
        let bus = EventBus::with_defaults();
        assert!(bus.is_empty());
        assert_eq!(bus.len(), 0);
    }

    #[test]
    fn test_bus_emit_and_poll() {
        let bus = EventBus::with_defaults();
        bus.emit(CoreEvent::EngineStopped);
        assert_eq!(bus.len(), 1);
        let e = bus.poll();
        assert_eq!(e, Some(CoreEvent::EngineStopped));
        assert!(bus.is_empty());
    }

    #[test]
    fn test_bus_fifo_order() {
        let bus = EventBus::with_defaults();
        bus.emit(CoreEvent::EngineStopped);
        bus.emit(CoreEvent::NetworkStatusChanged {
            status: "connected".into(),
        });
        let e1 = bus.poll().unwrap();
        let e2 = bus.poll().unwrap();
        assert_eq!(e1, CoreEvent::EngineStopped);
        assert!(matches!(e2, CoreEvent::NetworkStatusChanged { .. }));
    }

    #[test]
    fn test_bus_drain() {
        let bus = EventBus::with_defaults();
        bus.emit(CoreEvent::EngineStopped);
        bus.emit(CoreEvent::EngineStopped);
        bus.emit(CoreEvent::EngineStopped);
        let events = bus.drain();
        assert_eq!(events.len(), 3);
        assert!(bus.is_empty());
    }

    #[test]
    fn test_bus_max_size() {
        let bus = EventBus::new(3);
        bus.emit(CoreEvent::EngineStopped);
        bus.emit(CoreEvent::EngineStopped);
        bus.emit(CoreEvent::EngineStopped);
        // 4-е вытесняет 1-е
        bus.emit(CoreEvent::NetworkStatusChanged { status: "x".into() });
        assert_eq!(bus.len(), 3);
    }

    #[test]
    fn test_bus_clear() {
        let bus = EventBus::with_defaults();
        bus.emit(CoreEvent::EngineStopped);
        bus.emit(CoreEvent::EngineStopped);
        bus.clear();
        assert!(bus.is_empty());
    }

    #[test]
    fn test_bus_has_event_type() {
        let bus = EventBus::with_defaults();
        bus.emit(CoreEvent::PeerLost {
            peer_id: "p1".into(),
        });
        assert!(bus.has_event_type("peer_lost"));
        assert!(!bus.has_event_type("engine_stopped"));
    }

    #[test]
    fn test_bus_poll_empty_returns_none() {
        let bus = EventBus::with_defaults();
        assert!(bus.poll().is_none());
    }

    // --- Раунд 119: сообщения не выбрасываются при переполнении ---

    fn message(id: &str) -> CoreEvent {
        CoreEvent::MessageReceived {
            message_id: id.into(),
            chat_id: "chat".into(),
            sender_id: "pk_aaaa".into(),
            text: "привет".into(),
            timestamp: 0,
        }
    }

    fn presence(n: usize) -> CoreEvent {
        CoreEvent::PeerDiscovered {
            peer_id: format!("pk_peer{n:04}"),
            display_name: format!("Peer {n}"),
            is_local: false,
        }
    }

    /// Сценарий владельца: после обновления приложения ядро заливает шину
    /// presence/gossip-событиями, а входящее сообщение приходит посреди
    /// лавины. Раньше переполнение выкидывало самое старое событие - им
    /// бывало сообщение. Теперь сообщение обязано дожить до drain.
    #[test]
    fn message_survives_presence_flood_in_full_bus() {
        let bus = EventBus::new(8);
        for n in 0..8 {
            bus.emit(presence(n));
        }
        assert_eq!(bus.len(), 8);
        // Сообщение приходит посреди продолжающейся лавины.
        bus.emit(message("m1"));
        for n in 8..40 {
            bus.emit(presence(n));
        }
        let drained = bus.drain();
        assert!(
            drained
                .iter()
                .any(|e| matches!(e, CoreEvent::MessageReceived { message_id, .. } if message_id == "m1")),
            "MessageReceived выброшен лавой служебных событий - потеря сообщения"
        );
    }

    /// Куски файлов (ранг 1) тоже переживут presence-лаву, но уступают
    /// сообщениям: при переполнении выкидывается служебное (самое свежее).
    #[test]
    fn eviction_prefers_presence_over_chunks_and_messages() {
        let bus = EventBus::new(4);
        for n in 0..4 {
            bus.emit(presence(n));
        }
        bus.emit(message("m1"));
        // Очередь: [p0, p1, p2, p3] -> p3 (самое свежее служебное)
        // вытеснено, m1 встал в хвост.
        let drained = bus.drain();
        assert!(matches!(drained.first(), Some(CoreEvent::PeerDiscovered { peer_id, .. }) if peer_id == "pk_peer0000"));
        assert_eq!(drained.len(), 4);
        assert!(drained
            .iter()
            .any(|e| matches!(e, CoreEvent::MessageReceived { message_id, .. } if message_id == "m1")));
    }

    /// Очередь из одних критичных событий переписки не роняет их:
    /// мягкий рост вместо молчаливой потери.
    #[test]
    fn full_bus_of_messages_grows_instead_of_dropping() {
        let bus = EventBus::new(4);
        for n in 0..10 {
            bus.emit(message(&format!("m{n}")));
        }
        let drained = bus.drain();
        assert_eq!(drained.len(), 10, "критичные события потеряны");
        for (n, e) in drained.iter().enumerate() {
            match e {
                CoreEvent::MessageReceived { message_id, .. } => {
                    assert_eq!(message_id, &format!("m{n}"), "порядок нарушен");
                }
                other => panic!("лишнее событие в очереди: {other:?}"),
            }
        }
    }

    /// Подтверждение доставки (галочка отправителя) - тоже ранг 0.
    #[test]
    fn delivery_ack_survives_flood() {
        let bus = EventBus::new(4);
        for n in 0..10 {
            bus.emit(presence(n));
        }
        bus.emit(CoreEvent::MessageDelivered {
            message_id: "m1".into(),
        });
        for n in 10..30 {
            bus.emit(presence(n));
        }
        assert!(bus
            .drain()
            .iter()
            .any(|e| matches!(e, CoreEvent::MessageDelivered { message_id } if message_id == "m1")));
    }
}
