use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

/// Список публичных MQTT broker (без авторизации)
const BROKERS: &[(&str, u16)] = &[
    ("broker.hivemq.com", 1883),
    ("test.mosquitto.org", 1883),
    ("broker.emqx.io", 1883),
    ("mqtt.eclipseprojects.io", 1883),
    ("public.mqtthq.com", 1883),
];

/// Менеджер нескольких MQTT broker.
/// Автоматически переключается на следующий при падении.
pub struct MultiBroker {
    /// Индекс текущего broker
    current: AtomicUsize,
    /// Время последнего успешного соединения
    last_success: Mutex<Instant>,
    /// Количество последовательных ошибок
    errors: AtomicUsize,
    /// Максимум ошибок до переключения
    max_errors: usize,
}

impl MultiBroker {
    pub fn new() -> Self {
        Self {
            current: AtomicUsize::new(0),
            last_success: Mutex::new(Instant::now()),
            errors: AtomicUsize::new(0),
            max_errors: 3,
        }
    }

    /// Текущий broker (host, port)
    pub fn current_broker(&self) -> (&'static str, u16) {
        let idx = self.current.load(Ordering::Relaxed) % BROKERS.len();
        BROKERS[idx]
    }

    /// URL текущего broker
    pub fn current_url(&self) -> String {
        let (host, port) = self.current_broker();
        format!("mqtt://{}:{}", host, port)
    }

    /// Все URL (для перебора)
    pub fn all_urls(&self) -> Vec<String> {
        BROKERS.iter().map(|(h, p)| format!("mqtt://{}:{}", h, p)).collect()
    }

    /// Отметить успешное соединение
    pub async fn mark_success(&self) {
        self.errors.store(0, Ordering::Relaxed);
        let mut last = self.last_success.lock().await;
        *last = Instant::now();
    }

    /// Отметить ошибку. Возвращает true если нужно переключить broker
    pub fn mark_error(&self) -> bool {
        let errs = self.errors.fetch_add(1, Ordering::Relaxed) + 1;
        if errs >= self.max_errors {
            self.switch_next();
            true
        } else {
            false
        }
    }

    /// Переключиться на следующий broker
    pub fn switch_next(&self) {
        let old = self.current.load(Ordering::Relaxed);
        let new = (old + 1) % BROKERS.len();
        self.current.store(new, Ordering::Relaxed);
        self.errors.store(0, Ordering::Relaxed);
        let (host, port) = BROKERS[new];
        tracing::warn!("MQTT broker switch: {} -> {}:{}", old, host, port);
    }

    /// Переключиться на конкретный broker (по индексу)
    pub fn switch_to(&self, idx: usize) {
        self.current.store(idx % BROKERS.len(), Ordering::Relaxed);
        self.errors.store(0, Ordering::Relaxed);
    }

    /// Индекс текущего broker
    pub fn current_index(&self) -> usize {
        self.current.load(Ordering::Relaxed)
    }

    /// Количество broker
    pub fn broker_count(&self) -> usize {
        BROKERS.len()
    }

    /// Проверка: давно ли было успешное соединение
    pub async fn is_stale(&self, timeout: Duration) -> bool {
        let last = self.last_success.lock().await;
        last.elapsed() > timeout
    }
}

impl Default for MultiBroker {
    fn default() -> Self {
        Self::new()
    }
}