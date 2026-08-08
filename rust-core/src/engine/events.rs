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
            CoreEvent::MessageStatusChanged { .. } => "message_status_changed",
            CoreEvent::MessageDelivered { .. } => "message_delivered",
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
    pub fn emit(&self, event: CoreEvent) {
        let mut queue = self.queue.lock().unwrap();
        if queue.len() >= self.max_size {
            // Удаляем самое старое если очередь полна
            queue.pop_front();
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
}
