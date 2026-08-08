//! NAT Type Classification
//! Определяет тип NAT и стратегию обхода для ICE

use std::net::SocketAddr;
use std::time::{Duration, Instant};

// ============================================================
// NAT Type Detection
// ============================================================

/// Тип NAT по классификации RFC 3489
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum NatType {
    /// Нет NAT — прямой публичный IP
    OpenInternet,

    /// Full Cone NAT:
    /// Любой внешний хост может слать пакеты на mapped адрес
    FullCone,

    /// Address-Restricted Cone:
    /// Внешний хост может слать только если мы ранее слали ему
    AddressRestricted,

    /// Port-Restricted Cone:
    /// Внешний хост:порт может слать только если мы ранее слали туда
    PortRestricted,

    /// Symmetric NAT:
    /// Для каждого destination — разный mapped адрес
    /// Самый сложный для traversal
    Symmetric,

    /// Тип ещё не определён
    Unknown,

    /// Определение завершилось ошибкой
    Failed(String),
}

impl NatType {
    /// Может ли этот тип NAT работать с прямым P2P через ICE?
    pub fn supports_direct_p2p(&self) -> bool {
        matches!(
            self,
            NatType::OpenInternet
                | NatType::FullCone
                | NatType::AddressRestricted
                | NatType::PortRestricted
        )
    }

    /// Нужен ли ретранслятор?
    pub fn requires_relay(&self) -> bool {
        matches!(self, NatType::Symmetric | NatType::Failed(_))
    }

    /// Приоритет ICE-стратегии (ниже = лучше)
    pub fn ice_priority(&self) -> u8 {
        match self {
            NatType::OpenInternet => 0,
            NatType::FullCone => 1,
            NatType::AddressRestricted => 2,
            NatType::PortRestricted => 3,
            NatType::Symmetric => 4,
            NatType::Unknown => 5,
            NatType::Failed(_) => 6,
        }
    }

    /// Человекочитаемое описание
    pub fn description(&self) -> &'static str {
        match self {
            NatType::OpenInternet => "Open Internet (no NAT)",
            NatType::FullCone => "Full Cone NAT (best)",
            NatType::AddressRestricted => "Address-Restricted Cone NAT",
            NatType::PortRestricted => "Port-Restricted Cone NAT",
            NatType::Symmetric => "Symmetric NAT (relay required)",
            NatType::Unknown => "Unknown (not yet detected)",
            NatType::Failed(_) => "Detection Failed",
        }
    }
}

impl std::fmt::Display for NatType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.description())
    }
}

// ============================================================
// STUN Probe Result
// ============================================================

/// Результат одного STUN-зонда
#[derive(Debug, Clone)]
pub struct StunProbeResult {
    /// Публичный адрес, который увидел STUN-сервер
    pub mapped_addr: SocketAddr,
    /// Время RTT до STUN-сервера
    pub rtt: Duration,
    /// STUN-сервер, который ответил
    pub server: SocketAddr,
}

/// Результаты нескольких STUN-зондов с разных серверов
#[derive(Debug, Clone)]
pub struct MultiProbeResult {
    pub probes: Vec<StunProbeResult>,
    pub detected_at: Instant,
}

impl MultiProbeResult {
    pub fn new(probes: Vec<StunProbeResult>) -> Self {
        Self {
            probes,
            detected_at: Instant::now(),
        }
    }

    /// Все mapped адреса одинаковые? → Full Cone или Restricted
    pub fn all_mapped_same(&self) -> bool {
        if self.probes.is_empty() {
            return false;
        }
        let first = self.probes[0].mapped_addr;
        self.probes.iter().all(|p| p.mapped_addr == first)
    }

    /// Публичный IP (берём из первого probe)
    pub fn public_addr(&self) -> Option<SocketAddr> {
        self.probes.first().map(|p| p.mapped_addr)
    }

    /// Минимальный RTT среди всех проб
    pub fn min_rtt(&self) -> Option<Duration> {
        self.probes.iter().map(|p| p.rtt).min()
    }
}

// ============================================================
// NAT Detector
// ============================================================

/// Конфигурация детектора NAT
#[derive(Debug, Clone)]
pub struct NatDetectorConfig {
    /// STUN-серверы для зондирования
    pub stun_servers: Vec<SocketAddr>,
    /// Таймаут одного зонда
    pub probe_timeout: Duration,
    /// Количество попыток
    pub max_retries: u8,
    /// TTL кэша результата
    pub cache_ttl: Duration,
}

impl Default for NatDetectorConfig {
    fn default() -> Self {
        Self {
            stun_servers: vec![
                // Заглушки — в реальности парсим из строки
                "8.8.8.8:3478".parse().unwrap(),
                "8.8.4.4:3478".parse().unwrap(),
            ],
            probe_timeout: Duration::from_secs(3),
            max_retries: 3,
            cache_ttl: Duration::from_secs(300), // 5 минут
        }
    }
}

/// Детектор типа NAT
#[derive(Debug)]
pub struct NatDetector {
    config: NatDetectorConfig,
    /// Последний обнаруженный тип NAT
    cached_type: Option<(NatType, Instant)>,
    /// Последний известный публичный адрес
    cached_public_addr: Option<SocketAddr>,
}

impl NatDetector {
    pub fn new(config: NatDetectorConfig) -> Self {
        Self {
            config,
            cached_type: None,
            cached_public_addr: None,
        }
    }

    pub fn with_defaults() -> Self {
        Self::new(NatDetectorConfig::default())
    }

    /// Проверить — не устарел ли кэш?
    pub fn cache_valid(&self) -> bool {
        self.cached_type
            .as_ref()
            .map(|(_, ts)| ts.elapsed() < self.config.cache_ttl)
            .unwrap_or(false)
    }

    /// Получить тип NAT из кэша (если актуален)
    pub fn cached_nat_type(&self) -> Option<&NatType> {
        if self.cache_valid() {
            self.cached_type.as_ref().map(|(t, _)| t)
        } else {
            None
        }
    }

    /// Получить последний публичный адрес
    pub fn public_addr(&self) -> Option<SocketAddr> {
        self.cached_public_addr
    }

    /// Определить тип NAT по результатам зондирования
    /// (В реальности вызывается async, здесь — pure logic)
    pub fn classify(&mut self, probe: MultiProbeResult) -> NatType {
        let nat_type = self.classify_inner(&probe);

        // Обновляем кэш
        self.cached_public_addr = probe.public_addr();
        self.cached_type = Some((nat_type.clone(), Instant::now()));

        nat_type
    }

    fn classify_inner(&self, probe: &MultiProbeResult) -> NatType {
        if probe.probes.is_empty() {
            return NatType::Failed("No STUN responses received".into());
        }

        if probe.probes.len() == 1 {
            // Недостаточно данных для полной классификации
            return NatType::Unknown;
        }

        if probe.all_mapped_same() {
            // Все STUN-серверы видят один и тот же mapped адрес
            // → Cone NAT (Full / Address-Restricted / Port-Restricted)
            // Различие между ними определяется дополнительными тестами
            // (change-request), здесь упрощённо:
            NatType::FullCone
        } else {
            // Разные STUN-серверы видят разные mapped адреса
            // → Symmetric NAT
            NatType::Symmetric
        }
    }

    /// Принудительно инвалидировать кэш
    pub fn invalidate_cache(&mut self) {
        self.cached_type = None;
    }

    /// Установить тип NAT вручную (для тестов и inject)
    pub fn set_nat_type(&mut self, nat_type: NatType) {
        self.cached_type = Some((nat_type, Instant::now()));
    }

    /// Рекомендуемая стратегия подключения для пары NAT-типов
    pub fn recommend_strategy(local: &NatType, remote: &NatType) -> ConnectionStrategy {
        use NatType::*;
        match (local, remote) {
            // Оба открытые — прямое соединение
            (OpenInternet, _) | (_, OpenInternet) => ConnectionStrategy::Direct,

            // Full Cone + любой Cone → прямое ICE
            (FullCone, FullCone)
            | (FullCone, AddressRestricted)
            | (AddressRestricted, FullCone)
            | (FullCone, PortRestricted)
            | (PortRestricted, FullCone) => ConnectionStrategy::IceDirect,

            // Restricted + Restricted → ICE с дырами
            (AddressRestricted, AddressRestricted)
            | (AddressRestricted, PortRestricted)
            | (PortRestricted, AddressRestricted)
            | (PortRestricted, PortRestricted) => ConnectionStrategy::IceHolePunch,

            // Symmetric → нужен relay
            (Symmetric, _) | (_, Symmetric) => ConnectionStrategy::Relay,

            // Unknown/Failed → relay как fallback
            _ => ConnectionStrategy::Relay,
        }
    }
}

// ============================================================
// Connection Strategy
// ============================================================

/// Рекомендуемая стратегия подключения
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConnectionStrategy {
    /// Прямое соединение (нет NAT)
    Direct,
    /// ICE с прямым обменом кандидатами
    IceDirect,
    /// ICE с активным hole punching
    IceHolePunch,
    /// Через ретранслятор (Tier 1/2)
    Relay,
}

impl ConnectionStrategy {
    pub fn needs_relay(&self) -> bool {
        matches!(self, ConnectionStrategy::Relay)
    }

    pub fn needs_ice(&self) -> bool {
        matches!(
            self,
            ConnectionStrategy::IceDirect | ConnectionStrategy::IceHolePunch
        )
    }
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn make_probe(addrs: &[&str]) -> MultiProbeResult {
        let stun_server: SocketAddr = "1.2.3.4:3478".parse().unwrap();
        let probes = addrs
            .iter()
            .enumerate()
            .map(|(i, addr)| StunProbeResult {
                mapped_addr: addr.parse().unwrap(),
                rtt: Duration::from_millis(10 + i as u64 * 5),
                server: stun_server,
            })
            .collect();
        MultiProbeResult::new(probes)
    }

    // --- NatType methods ---

    #[test]
    fn test_nat_type_supports_direct_p2p() {
        assert!(NatType::OpenInternet.supports_direct_p2p());
        assert!(NatType::FullCone.supports_direct_p2p());
        assert!(NatType::AddressRestricted.supports_direct_p2p());
        assert!(NatType::PortRestricted.supports_direct_p2p());
        assert!(!NatType::Symmetric.supports_direct_p2p());
        assert!(!NatType::Unknown.supports_direct_p2p());
        assert!(!NatType::Failed("err".into()).supports_direct_p2p());
    }

    #[test]
    fn test_nat_type_requires_relay() {
        assert!(!NatType::OpenInternet.requires_relay());
        assert!(!NatType::FullCone.requires_relay());
        assert!(NatType::Symmetric.requires_relay());
        assert!(NatType::Failed("x".into()).requires_relay());
    }

    #[test]
    fn test_nat_type_ice_priority_ordering() {
        assert!(NatType::OpenInternet.ice_priority() < NatType::FullCone.ice_priority());
        assert!(NatType::FullCone.ice_priority() < NatType::AddressRestricted.ice_priority());
        assert!(NatType::AddressRestricted.ice_priority() < NatType::PortRestricted.ice_priority());
        assert!(NatType::PortRestricted.ice_priority() < NatType::Symmetric.ice_priority());
    }

    #[test]
    fn test_nat_type_display() {
        let s = format!("{}", NatType::FullCone);
        assert!(s.contains("Full Cone"));
        let s2 = format!("{}", NatType::Symmetric);
        assert!(s2.contains("relay"));
    }

    // --- MultiProbeResult ---

    #[test]
    fn test_multi_probe_all_same() {
        let probe = make_probe(&["1.2.3.4:5000", "1.2.3.4:5000", "1.2.3.4:5000"]);
        assert!(probe.all_mapped_same());
    }

    #[test]
    fn test_multi_probe_different_addrs() {
        let probe = make_probe(&["1.2.3.4:5000", "5.6.7.8:6000"]);
        assert!(!probe.all_mapped_same());
    }

    #[test]
    fn test_multi_probe_public_addr() {
        let probe = make_probe(&["9.10.11.12:4444"]);
        let addr = probe.public_addr().unwrap();
        assert_eq!(addr.port(), 4444);
    }

    #[test]
    fn test_multi_probe_min_rtt() {
        let probe = make_probe(&["1.1.1.1:1111", "2.2.2.2:2222", "3.3.3.3:3333"]);
        let rtt = probe.min_rtt().unwrap();
        assert_eq!(rtt, Duration::from_millis(10));
    }

    #[test]
    fn test_multi_probe_empty() {
        let probe = MultiProbeResult::new(vec![]);
        assert!(!probe.all_mapped_same());
        assert!(probe.public_addr().is_none());
        assert!(probe.min_rtt().is_none());
    }

    // --- NatDetector classify ---

    #[test]
    fn test_classify_symmetric() {
        let mut detector = NatDetector::with_defaults();
        let probe = make_probe(&["1.1.1.1:5000", "2.2.2.2:6000"]);
        let nat = detector.classify(probe);
        assert_eq!(nat, NatType::Symmetric);
    }

    #[test]
    fn test_classify_full_cone() {
        let mut detector = NatDetector::with_defaults();
        let probe = make_probe(&["1.1.1.1:5000", "1.1.1.1:5000"]);
        let nat = detector.classify(probe);
        assert_eq!(nat, NatType::FullCone);
    }

    #[test]
    fn test_classify_unknown_single_probe() {
        let mut detector = NatDetector::with_defaults();
        let probe = make_probe(&["1.1.1.1:5000"]);
        let nat = detector.classify(probe);
        assert_eq!(nat, NatType::Unknown);
    }

    #[test]
    fn test_classify_failed_empty() {
        let mut detector = NatDetector::with_defaults();
        let probe = MultiProbeResult::new(vec![]);
        let nat = detector.classify(probe);
        assert!(matches!(nat, NatType::Failed(_)));
    }

    #[test]
    fn test_cache_valid_after_classify() {
        let mut detector = NatDetector::with_defaults();
        let probe = make_probe(&["1.1.1.1:5000", "1.1.1.1:5000"]);
        detector.classify(probe);
        assert!(detector.cache_valid());
        assert!(detector.cached_nat_type().is_some());
    }

    #[test]
    fn test_invalidate_cache() {
        let mut detector = NatDetector::with_defaults();
        let probe = make_probe(&["1.1.1.1:5000", "1.1.1.1:5000"]);
        detector.classify(probe);
        detector.invalidate_cache();
        assert!(!detector.cache_valid());
        assert!(detector.cached_nat_type().is_none());
    }

    #[test]
    fn test_set_nat_type_manual() {
        let mut detector = NatDetector::with_defaults();
        detector.set_nat_type(NatType::Symmetric);
        assert_eq!(detector.cached_nat_type(), Some(&NatType::Symmetric));
    }

    // --- ConnectionStrategy ---

    #[test]
    fn test_strategy_open_internet() {
        let s = NatDetector::recommend_strategy(&NatType::OpenInternet, &NatType::Symmetric);
        assert_eq!(s, ConnectionStrategy::Direct);
    }

    #[test]
    fn test_strategy_full_cone_pair() {
        let s = NatDetector::recommend_strategy(&NatType::FullCone, &NatType::FullCone);
        assert_eq!(s, ConnectionStrategy::IceDirect);
    }

    #[test]
    fn test_strategy_port_restricted_pair() {
        let s = NatDetector::recommend_strategy(&NatType::PortRestricted, &NatType::PortRestricted);
        assert_eq!(s, ConnectionStrategy::IceHolePunch);
    }

    #[test]
    fn test_strategy_symmetric_requires_relay() {
        let s = NatDetector::recommend_strategy(&NatType::Symmetric, &NatType::FullCone);
        assert_eq!(s, ConnectionStrategy::Relay);
        assert!(s.needs_relay());
    }

    #[test]
    fn test_strategy_ice_variants_need_ice() {
        assert!(ConnectionStrategy::IceDirect.needs_ice());
        assert!(ConnectionStrategy::IceHolePunch.needs_ice());
        assert!(!ConnectionStrategy::Direct.needs_ice());
        assert!(!ConnectionStrategy::Relay.needs_ice());
    }
}
