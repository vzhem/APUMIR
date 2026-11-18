//! Events — система событий от Rust к Kotlin
//! Все события которые Kotlin получает через callback

use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

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
        /// Original observation time, so queued/retained events cannot renew themselves.
        observed_at_ms: i64,
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
    ///     файлов пере-запрашиваются приёмником), а присутствие видно
    ///     пользователю прямо: выкинутое наблюдение = «друг не в сети».
    /// 2 - служебная погода сети (статус канала, движение, жизнь движка) -
    ///     дёшево потерять: она пересоздаётся следующим событием.
    pub fn eviction_rank(&self) -> u8 {
        match self {
            CoreEvent::MessageReceived { .. } | CoreEvent::MessageDelivered { .. } => 0,
            // Владелец 2026-11-18: «статусы кто онлайн пропадают». Присутствие
            // раньше стояло в ранге 2, а очередь в тот момент держала 873
            // события из 1000 - то есть шина уже была на грани, и вытеснялось
            // именно присутствие как «дешёвое». Наблюдение, выброшенное здесь,
            // до Kotlin не доходит вовсе: контакт молча считается «не в сети»,
            // хотя телефон рядом и присылает heartbeat. Ставим присутствие в
            // ранг 1: под нож идут статус канала и движение, которые следующее
            // событие пересоздаст.
            CoreEvent::PeerDiscovered { .. } | CoreEvent::PeerLost { .. } => 1,
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

/// Binary file ranges have an independent consumer. All other events share one
/// consumer so contact/presence and message handlers cannot race each other.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EventLane {
    Main,
    File,
}

impl EventLane {
    fn matches(self, event: &CoreEvent) -> bool {
        let is_file = matches!(event, CoreEvent::FileChunkReceived { .. });
        (self == Self::File) == is_file
    }
}

/// Encrypted chat and encrypted service packets are indistinguishable here.
/// Both are prioritised, without inspecting or altering the sealed payload.
///
/// Владелец 2026-11-18 (скрин «Болталки»): «статусы кто онлайн пропадают,
/// хотя должны быть в сети». Присутствие — тоже срочное событие, и вот
/// почему. `PeerDiscovered` несёт МОМЕНТ наблюдения (`observed_at_ms`), а
/// Kotlin отбрасывает наблюдение старше 200 с как «устаревшее». Главная
/// полоса снимает по одному событию за итерацию, а служебные
/// (`network_status_changed`, `message_status_changed`) валятся пачками во
/// время переподключения брокера. Пока они разгребаются, свежий
/// `PeerDiscovered` стоит в очереди, стареет и приходит уже просроченным —
/// и контакт мигает «не в сети», хотя телефон рядом. Присутствие ставим в
/// приоритетную группу: она снимается в первую очередь, поэтому
/// наблюдение уходит к Kotlin свежим.
fn is_interactive(event: &CoreEvent) -> bool {
    matches!(
        event,
        CoreEvent::MessageReceived { .. }
            | CoreEvent::MessageDelivered { .. }
            | CoreEvent::PeerDiscovered { .. }
            | CoreEvent::PeerLost { .. }
    )
}

/// Шина событий — очередь событий для Kotlin
pub struct EventBus {
    queue: Mutex<VecDeque<CoreEvent>>,
    ready: Condvar,
    wake_generation: AtomicU64,
    max_size: usize,
    /// Сколько событий вытеснено переполнением (всего и по каждому рангу).
    ///
    /// Зачем: в отчёте владельца 2026-11-18 было «очередь событий 873» из
    /// 1000, и по одному числу нельзя было понять, теряются ли наблюдения
    /// присутствия по дороге. Именно потеря наблюдения выглядит на экране как
    /// «друг вышел из сети», хотя телефон рядом. Счётчик отвечает на этот
    /// вопрос числом, а не догадкой.
    evicted_total: AtomicU64,
    evicted_service: AtomicU64,
    evicted_presence: AtomicU64,
    evicted_retryable: AtomicU64,
}

impl EventBus {
    pub fn new(max_size: usize) -> Self {
        Self {
            queue: Mutex::new(VecDeque::new()),
            ready: Condvar::new(),
            wake_generation: AtomicU64::new(0),
            max_size,
            evicted_total: AtomicU64::new(0),
            evicted_service: AtomicU64::new(0),
            evicted_presence: AtomicU64::new(0),
            evicted_retryable: AtomicU64::new(0),
        }
    }

    /// (всего, служебных, присутствия, пере-запрашиваемых) — для диагностики.
    pub fn eviction_stats(&self) -> (u64, u64, u64, u64) {
        (
            self.evicted_total.load(Ordering::Relaxed),
            self.evicted_service.load(Ordering::Relaxed),
            self.evicted_presence.load(Ordering::Relaxed),
            self.evicted_retryable.load(Ordering::Relaxed),
        )
    }

    fn note_eviction(&self, event: &CoreEvent) {
        let total = self.evicted_total.fetch_add(1, Ordering::Relaxed) + 1;
        let presence = match event {
            CoreEvent::PeerDiscovered { .. } | CoreEvent::PeerLost { .. } => {
                self.evicted_presence.fetch_add(1, Ordering::Relaxed) + 1
            }
            CoreEvent::MessageStatusChanged { .. } | CoreEvent::FileChunkReceived { .. } => {
                self.evicted_retryable.fetch_add(1, Ordering::Relaxed);
                // Не присутствие: молчим, чтобы не заливать logcat.
                0
            }
            _ => {
                self.evicted_service.fetch_add(1, Ordering::Relaxed);
                0
            }
        };
        // Терять наблюдение присутствия дорого: на экране это «друг вышел из
        // сети». Поэтому каждый такой случай виден в logcat, с накопленным
        // числом — по отчёту владельца можно отличить «наблюдений не было» от
        // «были и потерялись в переполненной шине».
        if presence > 0 {
            tracing::warn!(
                "EventBus overflow: evicted presence observation (presence_evicted={} of total={})",
                presence,
                total
            );
        }
    }

    pub fn with_defaults() -> Self {
        Self::new(1000)
    }

    /// Отправить событие в шину
    ///
    /// Раунд 119: при переполнении выкидываем сначала САМОЕ СВЕЖЕЕ
    /// служебное событие (rank 2: статус канала, движение, жизнь движка),
    /// затем пере-запрашиваемые (rank 1: куски файлов, статусы сообщений и
    /// присутствие — 2026-11-18). События ранга 0 (входящее сообщение,
    /// подтверждение доставки) не выбрасываются никогда; если очередь состоит
    /// только из них - позволяем ей мягко вырасти: drain Kotlin'а мгновенно
    /// разгрузит её, а потерять переписку хуже, чем ненадолго занять память.
    pub fn emit(&self, event: CoreEvent) {
        let mut queue = self.queue.lock().unwrap();
        if queue.len() >= self.max_size {
            let victim = (1..=2).rev().find_map(|rank| {
                queue.iter().rposition(|e| e.eviction_rank() == rank)
            });
            match victim {
                Some(i) => {
                    if let Some(dropped) = queue.remove(i) {
                        self.note_eviction(&dropped);
                    }
                }
                None => {
                    tracing::warn!(
                        "EventBus overflow: only critical message events queued, growing softly"
                    );
                }
            }
        }
        queue.push_back(event);
        // Both lane waiters share this condvar. Waking only one can wake the
        // wrong lane and leave a message sleeping until the timeout.
        self.ready.notify_all();
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

    /// Remove one event, leaving the others in the native queue. On the main
    /// lane, prefer message events while preserving FIFO within each class;
    /// callers periodically request the opposite class to avoid starvation.
    pub fn poll_lane(&self, lane: EventLane, prefer_interactive: bool) -> Option<CoreEvent> {
        let mut queue = self.queue.lock().unwrap();
        let index = if lane == EventLane::Main {
            queue.iter().position(|event| lane.matches(event) && is_interactive(event) == prefer_interactive)
                .or_else(|| queue.iter().position(|event| lane.matches(event)))?
        } else {
            queue.iter().position(|event| lane.matches(event))?
        };
        queue.remove(index)
    }

    /// Wait for the requested lane, not merely for any event. The predicate is
    /// checked while holding the queue mutex so an emit cannot be missed.
    pub fn wait_for_lane(&self, lane: EventLane, timeout: Duration) -> bool {
        self.wait_for_lane_with(lane, timeout, || {})
    }

    fn wait_for_lane_with(&self, lane: EventLane, timeout: Duration, locked: impl FnOnce()) -> bool {
        let queue = self.queue.lock().unwrap();
        let generation = self.wake_generation.load(Ordering::Acquire);
        // Tests can announce registration while holding the same mutex as the
        // waker. In production this callback is a no-op.
        locked();
        let (queue, _) = self.ready.wait_timeout_while(queue, timeout, |q| {
            !q.iter().any(|event| lane.matches(event)) &&
                self.wake_generation.load(Ordering::Acquire) == generation
        }).unwrap();
        queue.iter().any(|event| lane.matches(event))
    }

    /// Блокирующее ожидание события для FFI: поток спит на Condvar и не
    /// тратит CPU, пока очередь пуста. Возвращает число событий после
    /// пробуждения (0 - истёк таймаут). Замок движка при этом НЕ держится.
    pub fn wait_for_events(&self, timeout: Duration) -> usize {
        let queue = self.queue.lock().unwrap();
        if !queue.is_empty() {
            return queue.len();
        }
        let generation = self.wake_generation.load(Ordering::Acquire);
        let (queue, _result) = self
            .ready
            .wait_timeout_while(queue, timeout, |q| {
                q.is_empty() && self.wake_generation.load(Ordering::Acquire) == generation
            })
            .unwrap();
        queue.len()
    }

    /// Разбудить ожидающих без события (остановка движка).
    pub fn wake_waiters(&self) {
        // Hold the same mutex used by waiters while changing the predicate and
        // notifying, preventing a wake between the predicate check and sleep.
        let _queue = self.queue.lock().unwrap();
        self.wake_generation.fetch_add(1, Ordering::Release);
        self.ready.notify_all();
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
                is_local: true,
                observed_at_ms: 1,
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
            is_local: false,
            observed_at_ms: 1,
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

    #[test]
    fn wait_returns_immediately_when_events_are_queued() {
        let bus = EventBus::with_defaults();
        bus.emit(CoreEvent::EngineStopped);
        assert_eq!(bus.wait_for_events(Duration::from_secs(5)), 1);
    }

    #[test]
    fn wait_times_out_on_empty_bus_without_busy_loop() {
        let bus = EventBus::with_defaults();
        let start = std::time::Instant::now();
        assert_eq!(bus.wait_for_events(Duration::from_millis(30)), 0);
        assert!(start.elapsed() >= Duration::from_millis(25));
    }

    #[test]
    fn emit_wakes_a_waiting_reader() {
        let bus = std::sync::Arc::new(EventBus::with_defaults());
        let writer = std::sync::Arc::clone(&bus);
        let handle = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(20));
            writer.emit(CoreEvent::EngineStopped);
        });
        let start = std::time::Instant::now();
        assert_eq!(bus.wait_for_events(Duration::from_secs(5)), 1);
        assert!(start.elapsed() < Duration::from_secs(2));
        handle.join().unwrap();
    }

    #[test]
    fn wake_waiters_releases_empty_bus() {
        let bus = std::sync::Arc::new(EventBus::with_defaults());
        let waker = std::sync::Arc::clone(&bus);
        let handle = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(20));
            waker.wake_waiters();
        });
        assert_eq!(bus.wait_for_events(Duration::from_secs(5)), 0);
        handle.join().unwrap();
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

    fn file_chunk(index: u64) -> CoreEvent {
        CoreEvent::FileChunkReceived {
            transfer_id: "transfer".into(),
            chunk_index: index,
            chunk_offset: 0,
            ciphertext_chunk_len: 1,
            ciphertext: vec![1],
        }
    }

    #[test]
    fn main_lane_prioritizes_messages_and_presence_with_fifo_in_each_class() {
        let bus = EventBus::with_defaults();
        bus.emit(presence(1));
        bus.emit(file_chunk(1));
        bus.emit(message("m1"));
        bus.emit(presence(2));
        bus.emit(CoreEvent::MessageDelivered { message_id: "ack1".into() });
        bus.emit(file_chunk(2));
        bus.emit(message("m2"));
        bus.emit(CoreEvent::NetworkStatusChanged { status: "relayed".into() });

        assert!(matches!(bus.poll_lane(EventLane::Main, true), Some(CoreEvent::PeerDiscovered { peer_id, .. }) if peer_id == "pk_peer0001"));
        assert!(matches!(bus.poll_lane(EventLane::Main, true), Some(CoreEvent::MessageReceived { message_id, .. }) if message_id == "m1"));
        assert!(matches!(bus.poll_lane(EventLane::Main, true), Some(CoreEvent::PeerDiscovered { peer_id, .. }) if peer_id == "pk_peer0002"));
        assert!(matches!(bus.poll_lane(EventLane::Main, true), Some(CoreEvent::MessageDelivered { message_id }) if message_id == "ack1"));
        assert!(matches!(bus.poll_lane(EventLane::Main, true), Some(CoreEvent::MessageReceived { message_id, .. }) if message_id == "m2"));
        // A fairness turn processes the oldest service event without taking a file range.
        assert!(matches!(bus.poll_lane(EventLane::Main, false), Some(CoreEvent::NetworkStatusChanged { status }) if status == "relayed"));
        assert!(bus.poll_lane(EventLane::Main, true).is_none());
        assert!(bus.poll_lane(EventLane::Main, false).is_none());
        assert!(matches!(bus.poll_lane(EventLane::File, false), Some(CoreEvent::FileChunkReceived { chunk_index: 1, .. })));
        assert!(matches!(bus.poll_lane(EventLane::File, false), Some(CoreEvent::FileChunkReceived { chunk_index: 2, .. })));
        assert!(bus.is_empty());
    }

    #[test]
    fn lanes_wait_for_their_own_event_and_both_wake_on_shutdown() {
        let bus = std::sync::Arc::new(EventBus::with_defaults());
        bus.emit(file_chunk(1));
        assert!(bus.wait_for_lane(EventLane::File, Duration::from_secs(1)));
        assert!(!bus.wait_for_lane(EventLane::Main, Duration::from_millis(20)));
        assert!(bus.poll_lane(EventLane::File, false).is_some());

        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let (result_tx, result_rx) = std::sync::mpsc::channel();
        let mut workers = Vec::new();
        for lane in [EventLane::Main, EventLane::File] {
            let bus = std::sync::Arc::clone(&bus);
            let ready = ready_tx.clone();
            let result = result_tx.clone();
            workers.push(std::thread::spawn(move || {
                let observed = bus.wait_for_lane_with(lane, Duration::from_secs(2), || ready.send(()).unwrap());
                result.send((lane, observed)).unwrap();
            }));
        }
        ready_rx.recv().unwrap();
        ready_rx.recv().unwrap();
        // The waker takes the same queue mutex, so neither registration can be missed.
        bus.wake_waiters();
        let first = result_rx.recv_timeout(Duration::from_millis(500)).unwrap();
        let second = result_rx.recv_timeout(Duration::from_millis(500)).unwrap();
        assert!(!first.1 && !second.1 && first.0 != second.0);
        for worker in workers { worker.join().unwrap(); }
    }

    #[test]
    fn emitting_for_one_lane_does_not_make_the_other_busy_poll() {
        let bus = std::sync::Arc::new(EventBus::with_defaults());
        let producer = std::sync::Arc::clone(&bus);
        let worker = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(20));
            producer.emit(file_chunk(1));
            std::thread::sleep(Duration::from_millis(20));
            producer.emit(message("m1"));
        });
        assert!(bus.wait_for_lane(EventLane::Main, Duration::from_secs(1)));
        assert!(matches!(bus.poll_lane(EventLane::Main, true), Some(CoreEvent::MessageReceived { message_id, .. }) if message_id == "m1"));
        assert!(matches!(bus.poll_lane(EventLane::File, false), Some(CoreEvent::FileChunkReceived { chunk_index: 1, .. })));
        worker.join().unwrap();
    }

    fn presence(n: usize) -> CoreEvent {
        CoreEvent::PeerDiscovered {
            peer_id: format!("pk_peer{n:04}"),
            display_name: format!("Peer {n}"),
            is_local: false,
            observed_at_ms: 1,
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

    /// Присутствие (ранг 1) переживает лаву служебных событий: вытесняется
    /// погода сети, а не наблюдение. Владелец 2026-11-18: «статусы кто онлайн
    /// пропадают» — выброшенное отсюда наблюдение не доходит до Kotlin, и
    /// контакт молча показывается «не в сети».
    #[test]
    fn eviction_prefers_service_events_over_presence_chunks_and_messages() {
        let bus = EventBus::new(5);
        bus.emit(file_chunk(1));
        for n in 0..3 {
            bus.emit(presence(n));
        }
        // В очереди уже лежит служебный статус сети - именно он и должен
        // уйти под нож, а не наблюдение присутствия.
        bus.emit(CoreEvent::NetworkStatusChanged { status: "connecting".into() });
        assert_eq!(bus.len(), 5);
        bus.emit(CoreEvent::NetworkStatusChanged { status: "relayed".into() });
        let drained = bus.drain();
        assert_eq!(drained.len(), 5);
        assert!(
            matches!(drained.first(), Some(CoreEvent::FileChunkReceived { chunk_index: 1, .. })),
            "кусок файла потерян при переполнении"
        );
        for n in 0..3 {
            assert!(
                drained.iter().any(|e| matches!(e, CoreEvent::PeerDiscovered { peer_id, .. } if peer_id == &format!("pk_peer{n:04}"))),
                "наблюдение присутствия {n} выброшено при переполнении"
            );
        }
        // Под нож ушёл служебный статус, а не наблюдение: из двух статусов в
        // очереди остался один. Какой именно - дело политики (rposition берёт
        // последний служебный), и на смысл «присутствие не теряем» это не
        // влияет, поэтому проверяем класс, а не строку.
        let statuses = drained
            .iter()
            .filter(|e| matches!(e, CoreEvent::NetworkStatusChanged { .. }))
            .count();
        assert_eq!(
            statuses, 1,
            "вытеснено присутствие вместо служебного статуса сети"
        );
        let (_, service, presence_evicted, _) = bus.eviction_stats();
        assert_eq!(service, 1, "вытеснение служебного не посчитано");
        assert_eq!(presence_evicted, 0, "вытеснено присутствие");
    }

    /// Счётчик вытеснений отвечает на вопрос из отчёта владельца: «очередь
    /// 873» само по себе не говорит, терялись ли наблюдения присутствия.
    #[test]
    fn eviction_stats_count_what_was_dropped() {
        let bus = EventBus::new(4);
        // В очереди и присутствие, и служебное событие. Вытесняется только
        // служебное - присутствие трогать нельзя, поэтому за 4 лишних
        // служебных вставки вытеснений будет ровно 4.
        for n in 0..3 {
            bus.emit(presence(n));
        }
        for _ in 0..5 {
            bus.emit(CoreEvent::NetworkStatusChanged { status: "relayed".into() });
        }
        let (total, service, presence_evicted, retryable) = bus.eviction_stats();
        assert_eq!(total, 4, "вытеснения не посчитаны: {total}");
        assert_eq!(service, 4, "служебные вытеснения не посчитаны: {service}");
        assert_eq!(presence_evicted, 0, "присутствие вытеснено при служебной лавине");
        assert_eq!(retryable, 0);
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

    /// Владелец 2026-11-18: «статусы кто онлайн пропадают». Служебная лавина
    /// переподключения брокера не должна надолго задерживать наблюдение
    /// присутствия: оно несёт исходный момент времени, и если доедет до Kotlin
    /// позже 200 с, `PresenceRecovery` вправе отбросить его как «устаревшее».
    ///
    /// Вместе с присутствием в очереди лежит и служебный статус сети — так и
    /// бывает на телефоне. Служебное вытесняется первым, присутствие остаётся,
    /// а приоритетный бёрст забирает его раньше оставшейся службы.
    #[test]
    fn presence_is_not_starved_by_service_flood() {
        let bus = EventBus::with_defaults();
        for n in 0..40 {
            bus.emit(CoreEvent::NetworkStatusChanged {
                status: if n % 2 == 0 { "connecting".into() } else { "relayed".into() },
            });
        }
        bus.emit(presence(1));
        for _ in 40..80 {
            bus.emit(CoreEvent::NetworkStatusChanged { status: "relayed".into() });
        }
        // Присутствие обязано дожить до потребителя, а не быть вытесненным.
        let drained = bus.drain();
        assert!(
            drained
                .iter()
                .any(|e| matches!(e, CoreEvent::PeerDiscovered { peer_id, .. } if peer_id == "pk_peer0001")),
            "наблюдение присутствия вытеснено служебной лавиной - статус «в сети» потеряется"
        );
    }

    /// Наблюдение уходит к потребителю в приоритетном бёрсте, не дожидаясь
    /// разбора всей накопившейся службы переподключения.
    #[test]
    fn interactive_burst_takes_presence_before_service_backlog() {
        let bus = EventBus::new(64);
        for _ in 0..20 {
            bus.emit(CoreEvent::NetworkStatusChanged { status: "connecting".into() });
        }
        bus.emit(presence(1));
        assert!(
            matches!(bus.poll_lane(EventLane::Main, true), Some(CoreEvent::PeerDiscovered { peer_id, .. }) if peer_id == "pk_peer0001"),
            "присутствие ждёт в хвосте за 20 служебными событиями"
        );
        // Служба не теряется: без приоритета она идёт первой.
        assert!(matches!(
            bus.poll_lane(EventLane::Main, false),
            Some(CoreEvent::NetworkStatusChanged { .. })
        ));
    }

    /// The legacy MQTT event has no peer field; Kotlin must receive null, not an empty sender.
    #[test]
    fn delivery_ack_ffi_preserves_absent_sender() {
        let event = crate::event_to_ffi(CoreEvent::MessageDelivered { message_id: "m1".into() });
        assert_eq!(event.event_type, "delivery_ack");
        assert_eq!(event.message_id.as_deref(), Some("m1"));
        assert_eq!(event.sender_id, None);
    }

    /// Подтверждение доставки (галочка отправителя) - тоже ранг 0.
    #[test]
    fn delivery_ack_survives_flood() {
        let bus = EventBus::new(4);
        for n in 0..10 {
            // Лавина смешанная: служба и присутствие. Оба класса ниже ранга 0,
            // поэтому подтверждение не должно пострадать ни от одного из них.
            bus.emit(CoreEvent::NetworkStatusChanged { status: "relayed".into() });
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
