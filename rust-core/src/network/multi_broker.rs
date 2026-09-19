use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

/// Порт MQTT по умолчанию: если адрес задан без порта.
pub const DEFAULT_MQTT_PORT: u16 = 1883;

/// Сколько ждём ответа порта при выборе брокера.
///
/// Это «строгий таймаут» из правил работы с внешними ресурсами
/// (`docs/CORE_ROADMAP.md`, раздел 3): свой брокер может быть выключен или
/// недоступен из мобильной сети, и приложение не должно ждать его дольше,
/// чем нужно, чтобы перейти на публичный.
pub const BROKER_PROBE_TIMEOUT: Duration = Duration::from_secs(6);

/// Разобрать адрес брокера из настроек.
///
/// Принимаем то, что человек напишет руками: `mqtt://host:1883`,
/// `host:1883`, просто `host` (тогда порт по умолчанию). Пустая строка и
/// чужие схемы (`http://`, `ws://`) не принимаются: молча ходить не туда
/// хуже, чем честно остаться на публичных брокерах.
pub fn parse_broker_endpoint(text: &str) -> Option<(String, u16)> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return None;
    }
    let without_scheme = match trimmed.split_once("://") {
        None => trimmed,
        Some((scheme, rest)) => {
            if !scheme.eq_ignore_ascii_case("mqtt") && !scheme.eq_ignore_ascii_case("tcp") {
                return None;
            }
            rest
        }
    };
    let without_path = without_scheme
        .split(['/', '#', '?'])
        .next()
        .unwrap_or(without_scheme)
        .trim();
    if without_path.is_empty() {
        return None;
    }
    let (host, port) = match without_path.rsplit_once(':') {
        Some((host, port_text)) => {
            let port: u16 = port_text.trim().parse().ok()?;
            (host.trim(), port)
        }
        None => (without_path, DEFAULT_MQTT_PORT),
    };
    if host.is_empty() {
        return None;
    }
    // Квадратные скобки IPv6 оставляем как есть: так адрес понимает и
    // `TcpStream::connect`, и подключение брокера.
    Some((host.to_string(), port))
}

/// Отвечает ли брокер на порт в пределах [`BROKER_PROBE_TIMEOUT`].
///
/// Проба - НАСТОЯЩЕЕ MQTT-рукопожатие: шлём минимальный CONNECT (MQTT 3.1.1,
/// чистая сессия, клиент "probe1") и ждём CONNACK с кодом успеха. Раньше
/// проверяли только установку TCP-соединения - и в сетях, где порт «приоткрыт»,
/// а полезный трафик режется (белые списки оператора, DPI), проба говорила
/// «жив», движок садился на мёртвого адреса и никогда не добирался до
/// WSS-моста. Настоящий брокер всегда отвечает CONNACK сразу; заглушки,
/// чёрные дыры и перехватчики - нет. Сессию не закрываем вежливо (без
/// DISCONNECT): чистая сессия и так сгорит по keep-alive, а соединение
/// рвём сразу. Ошибка разбора адреса - тоже `false`.
pub async fn probe_broker(host: &str, port: u16) -> bool {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // CONNECT: 10 12 | 00 04 "MQTT" | 04 (уровень 3.1.1) | 02 (clean session)
    // | 00 3C (keep-alive 60 c) | 00 06 "probe1".
    const CONNECT: &[u8] = &[
        0x10, 0x12, 0x00, 0x04, b'M', b'Q', b'T', b'T', 0x04, 0x02, 0x00, 0x3C, 0x00, 0x06, b'p',
        b'r', b'o', b'b', b'e', b'1',
    ];
    let handshake = async {
        let io_err = |error: std::io::Error| error.to_string();
        let mut stream =
            tokio::net::TcpStream::connect((host, port)).await.map_err(io_err)?;
        stream.write_all(CONNECT).await.map_err(io_err)?;
        let mut header = [0u8; 2];
        stream.read_exact(&mut header).await.map_err(io_err)?;
        // CONNACK: 0x20, remaining length 2. Всё остальное - не MQTT-брокер.
        if header[0] != 0x20 || header[1] != 0x02 {
            return Err("в ответ на CONNECT не CONNACK".to_string());
        }
        let mut body = [0u8; 2];
        stream.read_exact(&mut body).await.map_err(io_err)?;
        if body[1] != 0x00 {
            return Err("CONNACK с кодом отказа".to_string());
        }
        Ok::<(), String>(())
    };
    match tokio::time::timeout(BROKER_PROBE_TIMEOUT, handshake).await {
        Ok(Ok(())) => true,
        Ok(Err(error)) => {
            tracing::info!(
                "MQTT OWN BROKER: {}:{} не брокер ({}) - дальше по списку",
                host,
                port,
                error
            );
            false
        }
        Err(_) => {
            tracing::info!(
                "MQTT OWN BROKER: {}:{} молчит дольше {} с (нет TCP или нет CONNACK) - дальше по списку",
                host,
                port,
                BROKER_PROBE_TIMEOUT.as_secs()
            );
            false
        }
    }
}

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
    /// Свой брокер из настроек (например, свой Worker/сервер владельца).
    /// Идёт первым, пока отвечает.
    own: Option<(String, u16)>,
    /// Свой брокер не ответил: больше к нему не возвращаемся.
    own_failed: AtomicBool,
    /// Индекс текущего публичного broker
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
            own: None,
            own_failed: AtomicBool::new(false),
            current: AtomicUsize::new(0),
            last_success: Mutex::new(Instant::now()),
            errors: AtomicUsize::new(0),
            max_errors: 3,
        }
    }

    /// Менеджер со своим брокером из настроек: он идёт первым, публичные -
    /// запасной путь. `None` - поведение прежнее, только публичные.
    pub fn with_own(own: Option<(String, u16)>) -> Self {
        let mut broker = Self::new();
        broker.own = own;
        broker
    }

    /// Свой брокер из настроек, если он задан.
    pub fn own(&self) -> Option<(&str, u16)> {
        self.own.as_ref().map(|(host, port)| (host.as_str(), *port))
    }

    /// Отметить, что свой брокер не отвечает: дальше только публичные.
    pub fn mark_own_failed(&self) {
        if !self.own_failed.swap(true, Ordering::Relaxed) {
            if let Some((host, port)) = self.own.as_ref() {
                tracing::warn!(
                    "MQTT: свой брокер {}:{} недоступен, переходим на публичные",
                    host,
                    port
                );
            }
        }
    }

    /// Свой брокер сейчас в игре?
    pub fn own_is_usable(&self) -> bool {
        self.own.is_some() && !self.own_failed.load(Ordering::Relaxed)
    }

    /// Порядок перебора: свой брокер первым, затем публичные.
    pub fn candidates(&self) -> Vec<(String, u16)> {
        let mut list: Vec<(String, u16)> = Vec::with_capacity(BROKERS.len() + 1);
        if self.own_is_usable() {
            if let Some((host, port)) = self.own.as_ref() {
                list.push((host.clone(), *port));
            }
        }
        for (host, port) in BROKERS.iter() {
            list.push(((*host).to_string(), *port));
        }
        list
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn broker_endpoint_forms_are_accepted() {
        assert_eq!(
            parse_broker_endpoint("mqtt://broker.example.com:1883"),
            Some(("broker.example.com".to_string(), 1883))
        );
        assert_eq!(
            parse_broker_endpoint("broker.example.com:8883"),
            Some(("broker.example.com".to_string(), 8883))
        );
        assert_eq!(
            parse_broker_endpoint("  broker.example.com  "),
            Some(("broker.example.com".to_string(), DEFAULT_MQTT_PORT))
        );
        assert_eq!(
            parse_broker_endpoint("tcp://10.0.0.5:1883/"),
            Some(("10.0.0.5".to_string(), 1883))
        );
    }

    #[test]
    fn broken_broker_endpoints_are_rejected() {
        for text in [
            "",
            "   ",
            "http://broker.example.com:1883",
            "ws://broker.example.com",
            ":1883",
            "broker.example.com:not-a-port",
            "broker.example.com:99999",
        ] {
            assert_eq!(parse_broker_endpoint(text), None, "принято: {text:?}");
        }
    }

    #[test]
    fn own_broker_goes_first_and_public_ones_stay_as_bundle() {
        let own = ("my-broker.example.com".to_string(), 1883);
        let broker = MultiBroker::with_own(Some(own.clone()));
        assert_eq!(broker.own(), Some(("my-broker.example.com", 1883)));
        let candidates = broker.candidates();
        assert_eq!(candidates.first().cloned(), Some(own));
        assert_eq!(candidates.len(), BROKERS.len() + 1);
        assert!(broker.own_is_usable());
    }

    #[test]
    fn failed_own_broker_steps_aside_for_the_public_list() {
        let broker = MultiBroker::with_own(Some(("my-broker.example.com".to_string(), 1883)));
        broker.mark_own_failed();
        assert!(!broker.own_is_usable());
        assert_eq!(broker.candidates().len(), BROKERS.len());
        assert_eq!(
            broker.candidates().first().cloned(),
            Some((BROKERS[0].0.to_string(), BROKERS[0].1))
        );
        // Без своего брокера поведение прежнее.
        let plain = MultiBroker::new();
        assert_eq!(plain.candidates().len(), BROKERS.len());
        assert_eq!(plain.own(), None);
    }

    #[test]
    fn probe_timeout_stays_strict() {
        assert!(BROKER_PROBE_TIMEOUT >= Duration::from_secs(3));
        assert!(BROKER_PROBE_TIMEOUT <= Duration::from_secs(10));
    }
}
