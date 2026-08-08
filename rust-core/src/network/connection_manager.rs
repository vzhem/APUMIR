//! Connection Manager — Сетевой Оркестратор
//! Связывает: NAT Detection, Fallback Chain,
//! Adaptive Polling, Relay, Presence, Message Queue

use std::collections::HashMap;
use std::time::{Duration, Instant};

use super::adaptive_polling::{AdaptivePolling, AdaptivePollingConfig, PollingMode};
use super::fallback_chain::{FallbackChain, FallbackContext, FallbackStep, StepResult};
use super::nat_types::{ConnectionStrategy, NatDetector, NatDetectorConfig, NatType};

// ============================================================
// Peer Connection State
// ============================================================

/// Состояние соединения с конкретным peer
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConnectionState {
    /// Ещё не пробовали подключиться
    Idle,
    /// Идёт попытка подключения
    Connecting,
    /// Соединение установлено
    Connected,
    /// Соединение потеряно, ждём переподключения
    Reconnecting,
    /// Все попытки исчерпаны, сообщения в очереди
    StoredAndForwarded,
    /// Peer недоступен
    Unreachable,
}

impl std::fmt::Display for ConnectionState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ConnectionState::Idle => write!(f, "Idle"),
            ConnectionState::Connecting => write!(f, "Connecting"),
            ConnectionState::Connected => write!(f, "Connected"),
            ConnectionState::Reconnecting => write!(f, "Reconnecting"),
            ConnectionState::StoredAndForwarded => write!(f, "StoredAndForwarded"),
            ConnectionState::Unreachable => write!(f, "Unreachable"),
        }
    }
}

// ============================================================
// Peer Session
// ============================================================

/// Сессия соединения с одним peer
#[derive(Debug)]
pub struct PeerSession {
    pub peer_id: String,
    pub state: ConnectionState,
    pub current_step: Option<FallbackStep>,
    pub attempts: u32,
    pub last_attempt: Option<Instant>,
    pub connected_at: Option<Instant>,
    pub bytes_sent: u64,
    pub bytes_received: u64,
    pub remote_nat: NatType,
    pub active_strategy: Option<ConnectionStrategy>,
}

impl PeerSession {
    pub fn new(peer_id: String, remote_nat: NatType) -> Self {
        Self {
            peer_id,
            state: ConnectionState::Idle,
            current_step: None,
            attempts: 0,
            last_attempt: None,
            connected_at: None,
            bytes_sent: 0,
            bytes_received: u64::default(),
            remote_nat,
            active_strategy: None,
        }
    }

    pub fn is_connected(&self) -> bool {
        matches!(self.state, ConnectionState::Connected)
    }

    pub fn record_attempt(&mut self, step: FallbackStep) {
        self.attempts += 1;
        self.last_attempt = Some(Instant::now());
        self.current_step = Some(step);
        self.state = ConnectionState::Connecting;
    }

    pub fn on_connected(&mut self, strategy: ConnectionStrategy) {
        self.state = ConnectionState::Connected;
        self.connected_at = Some(Instant::now());
        self.active_strategy = Some(strategy);
    }

    pub fn on_disconnected(&mut self) {
        self.state = ConnectionState::Reconnecting;
        self.connected_at = None;
    }

    pub fn on_stored(&mut self) {
        self.state = ConnectionState::StoredAndForwarded;
    }

    pub fn on_unreachable(&mut self) {
        self.state = ConnectionState::Unreachable;
    }

    pub fn record_sent(&mut self, bytes: u64) {
        self.bytes_sent += bytes;
    }

    pub fn record_received(&mut self, bytes: u64) {
        self.bytes_received += bytes;
    }

    pub fn uptime(&self) -> Option<Duration> {
        self.connected_at.map(|t| t.elapsed())
    }
}

// ============================================================
// Connection Manager Config
// ============================================================

/// Конфигурация Connection Manager
#[derive(Debug, Clone)]
pub struct ConnectionManagerConfig {
    /// Максимум попыток на одного peer
    pub max_attempts_per_peer: u32,
    /// Таймаут одного шага fallback
    pub step_timeout: Duration,
    /// Включить adaptive polling
    pub adaptive_polling: bool,
    /// Конфигурация NAT детектора
    pub nat_config: NatDetectorConfig,
    /// Конфигурация polling
    pub polling_config: AdaptivePollingConfig,
}

impl Default for ConnectionManagerConfig {
    fn default() -> Self {
        Self {
            max_attempts_per_peer: 3,
            step_timeout: Duration::from_secs(5),
            adaptive_polling: true,
            nat_config: NatDetectorConfig::default(),
            polling_config: AdaptivePollingConfig::default(),
        }
    }
}

// ============================================================
// Connection Manager
// ============================================================

/// Главный оркестратор сетевых соединений
pub struct ConnectionManager {
    #[allow(dead_code)]
    config: ConnectionManagerConfig,
    nat_detector: NatDetector,
    polling: AdaptivePolling,
    sessions: HashMap<String, PeerSession>,
    total_connected: u64,
    total_stored: u64,
    total_failed: u64,
}

impl ConnectionManager {
    pub fn new(config: ConnectionManagerConfig) -> Self {
        let nat_detector = NatDetector::new(config.nat_config.clone());
        let polling = AdaptivePolling::new(config.polling_config.clone());

        Self {
            config,
            nat_detector,
            polling,
            sessions: HashMap::new(),
            total_connected: 0,
            total_stored: 0,
            total_failed: 0,
        }
    }

    pub fn with_defaults() -> Self {
        Self::new(ConnectionManagerConfig::default())
    }

    // --- NAT ---

    /// Установить локальный NAT тип (после обнаружения)
    pub fn set_local_nat(&mut self, nat: NatType) {
        self.nat_detector.set_nat_type(nat);
    }

    /// Получить локальный NAT тип
    pub fn local_nat(&self) -> Option<&NatType> {
        self.nat_detector.cached_nat_type()
    }

    // --- Polling ---

    /// Пользователь активен — сбросить polling
    pub fn on_user_active(&mut self) {
        self.polling.set_active();
    }

    /// Приложение ушло в фон
    pub fn on_app_background(&mut self) {
        self.polling.set_background();
    }

    /// Сеть пропала
    pub fn on_network_lost(&mut self) {
        self.polling.set_offline();
        // Все соединения теряем
        for session in self.sessions.values_mut() {
            if session.is_connected() {
                session.on_disconnected();
            }
        }
    }

    /// Сеть появилась
    pub fn on_network_available(&mut self) {
        self.polling.set_network_available();
    }

    /// Получить интервал до следующего poll
    pub fn next_poll_interval(&mut self) -> Duration {
        self.polling.next_interval()
    }

    /// Текущий режим polling
    pub fn polling_mode(&self) -> &PollingMode {
        self.polling.mode()
    }

    // --- Sessions ---

    /// Получить или создать сессию для peer
    pub fn get_or_create_session(
        &mut self,
        peer_id: &str,
        remote_nat: NatType,
    ) -> &mut PeerSession {
        self.sessions
            .entry(peer_id.to_string())
            .or_insert_with(|| PeerSession::new(peer_id.to_string(), remote_nat))
    }

    /// Получить сессию (immutable)
    pub fn session(&self, peer_id: &str) -> Option<&PeerSession> {
        self.sessions.get(peer_id)
    }

    /// Получить сессию (mutable)
    pub fn session_mut(&mut self, peer_id: &str) -> Option<&mut PeerSession> {
        self.sessions.get_mut(peer_id)
    }

    /// Все активные сессии
    pub fn sessions(&self) -> &HashMap<String, PeerSession> {
        &self.sessions
    }

    /// Количество подключённых peers
    pub fn connected_count(&self) -> usize {
        self.sessions.values().filter(|s| s.is_connected()).count()
    }

    // --- Fallback Logic ---

    /// Построить fallback chain для peer
    pub fn build_chain(&self, remote_nat: &NatType) -> FallbackChain {
        let local_nat = self
            .nat_detector
            .cached_nat_type()
            .cloned()
            .unwrap_or(NatType::Unknown);

        let context = FallbackContext::new(local_nat, remote_nat.clone());
        FallbackChain::new(&context)
    }

    /// Симулировать попытку подключения через шаг
    /// Возвращает: (step, result)
    pub fn try_connect_step(&mut self, peer_id: &str, step: FallbackStep, result: StepResult) {
        if let Some(session) = self.sessions.get_mut(peer_id) {
            session.record_attempt(step.clone());

            match result {
                StepResult::Success => {
                    let strategy = step_to_strategy(&step);
                    session.on_connected(strategy);
                    self.total_connected += 1;
                }
                StepResult::Failed => {
                    // Остаёмся в Connecting, следующий шаг попробует chain
                }
                StepResult::RetryLater => {
                    session.on_stored();
                    self.total_stored += 1;
                }
            }
        }
    }

    /// Полный прогон fallback chain для peer (синхронная симуляция)
    /// results — список результатов для каждого шага
    pub fn run_chain(
        &mut self,
        peer_id: &str,
        remote_nat: NatType,
        step_results: Vec<StepResult>,
    ) -> ConnectionState {
        // Создаём сессию
        self.sessions
            .entry(peer_id.to_string())
            .or_insert_with(|| PeerSession::new(peer_id.to_string(), remote_nat.clone()));

        let chain = self.build_chain(&remote_nat);
        let steps: Vec<FallbackStep> = chain.steps.clone();

        let mut result_iter = step_results.into_iter();

        for step in steps {
            let result = result_iter.next().unwrap_or(StepResult::Failed);

            if let Some(session) = self.sessions.get_mut(peer_id) {
                session.record_attempt(step.clone());
            }

            match result {
                StepResult::Success => {
                    let strategy = step_to_strategy(&step);
                    if let Some(session) = self.sessions.get_mut(peer_id) {
                        session.on_connected(strategy);
                    }
                    self.total_connected += 1;
                    return ConnectionState::Connected;
                }
                StepResult::RetryLater => {
                    if let Some(session) = self.sessions.get_mut(peer_id) {
                        session.on_stored();
                    }
                    self.total_stored += 1;
                    return ConnectionState::StoredAndForwarded;
                }
                StepResult::Failed => {
                    continue;
                }
            }
        }

        // Все шаги провалились
        if let Some(session) = self.sessions.get_mut(peer_id) {
            session.on_unreachable();
        }
        self.total_failed += 1;
        ConnectionState::Unreachable
    }

    // --- Statistics ---

    pub fn total_connected(&self) -> u64 {
        self.total_connected
    }

    pub fn total_stored(&self) -> u64 {
        self.total_stored
    }

    pub fn total_failed(&self) -> u64 {
        self.total_failed
    }
}

// ============================================================
// Helpers
// ============================================================

fn step_to_strategy(step: &FallbackStep) -> ConnectionStrategy {
    match step {
        FallbackStep::DirectQuic => ConnectionStrategy::Direct,
        FallbackStep::Ice => ConnectionStrategy::IceDirect,
        FallbackStep::RelayTier1 | FallbackStep::RelayTier2 => ConnectionStrategy::Relay,
        FallbackStep::DhtLookup | FallbackStep::SeedNode => ConnectionStrategy::Relay,
        FallbackStep::StoreAndForward => ConnectionStrategy::Relay,
    }
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_manager() -> ConnectionManager {
        let mut m = ConnectionManager::with_defaults();
        m.set_local_nat(NatType::FullCone);
        m
    }

    // --- PeerSession ---

    #[test]
    fn test_peer_session_initial_state() {
        let s = PeerSession::new("peer1".into(), NatType::FullCone);
        assert_eq!(s.state, ConnectionState::Idle);
        assert_eq!(s.attempts, 0);
        assert!(!s.is_connected());
    }

    #[test]
    fn test_peer_session_record_attempt() {
        let mut s = PeerSession::new("peer1".into(), NatType::FullCone);
        s.record_attempt(FallbackStep::DirectQuic);
        assert_eq!(s.state, ConnectionState::Connecting);
        assert_eq!(s.attempts, 1);
        assert_eq!(s.current_step, Some(FallbackStep::DirectQuic));
    }

    #[test]
    fn test_peer_session_on_connected() {
        let mut s = PeerSession::new("peer1".into(), NatType::FullCone);
        s.on_connected(ConnectionStrategy::Direct);
        assert!(s.is_connected());
        assert_eq!(s.active_strategy, Some(ConnectionStrategy::Direct));
    }

    #[test]
    fn test_peer_session_on_disconnected() {
        let mut s = PeerSession::new("peer1".into(), NatType::FullCone);
        s.on_connected(ConnectionStrategy::Direct);
        s.on_disconnected();
        assert_eq!(s.state, ConnectionState::Reconnecting);
    }

    #[test]
    fn test_peer_session_bytes() {
        let mut s = PeerSession::new("peer1".into(), NatType::FullCone);
        s.record_sent(1024);
        s.record_received(512);
        assert_eq!(s.bytes_sent, 1024);
        assert_eq!(s.bytes_received, 512);
    }

    #[test]
    fn test_peer_session_uptime_none_when_not_connected() {
        let s = PeerSession::new("peer1".into(), NatType::FullCone);
        assert!(s.uptime().is_none());
    }

    #[test]
    fn test_peer_session_uptime_some_when_connected() {
        let mut s = PeerSession::new("peer1".into(), NatType::FullCone);
        s.on_connected(ConnectionStrategy::Direct);
        assert!(s.uptime().is_some());
    }

    // --- ConnectionManager ---

    #[test]
    fn test_manager_initial_state() {
        let m = ConnectionManager::with_defaults();
        assert_eq!(m.connected_count(), 0);
        assert_eq!(m.total_connected(), 0);
        assert_eq!(m.total_stored(), 0);
        assert_eq!(m.total_failed(), 0);
    }

    #[test]
    fn test_manager_set_local_nat() {
        let mut m = ConnectionManager::with_defaults();
        m.set_local_nat(NatType::Symmetric);
        assert_eq!(m.local_nat(), Some(&NatType::Symmetric));
    }

    #[test]
    fn test_manager_get_or_create_session() {
        let mut m = make_manager();
        {
            let s = m.get_or_create_session("alice", NatType::FullCone);
            assert_eq!(s.peer_id, "alice");
        }
        assert!(m.session("alice").is_some());
    }

    #[test]
    fn test_manager_get_or_create_idempotent() {
        let mut m = make_manager();
        m.get_or_create_session("bob", NatType::FullCone);
        m.get_or_create_session("bob", NatType::FullCone);
        assert_eq!(m.sessions().len(), 1);
    }

    // --- Polling integration ---

    #[test]
    fn test_manager_polling_active_interval() {
        let mut m = make_manager();
        m.on_user_active();
        let interval = m.next_poll_interval();
        assert_eq!(interval, Duration::from_secs(5));
    }

    #[test]
    fn test_manager_polling_background_grows() {
        let mut m = make_manager();
        m.on_app_background();
        let i1 = m.next_poll_interval();
        let i2 = m.next_poll_interval();
        assert!(i2 >= i1);
    }

    #[test]
    fn test_manager_polling_offline() {
        let mut m = make_manager();
        m.on_network_lost();
        assert_eq!(m.polling_mode(), &PollingMode::Offline);
    }

    #[test]
    fn test_manager_polling_restored() {
        let mut m = make_manager();
        m.on_network_lost();
        m.on_network_available();
        assert_eq!(m.polling_mode(), &PollingMode::Active);
    }

    // --- Network lost marks sessions ---

    #[test]
    fn test_network_lost_disconnects_sessions() {
        let mut m = make_manager();
        m.get_or_create_session("peer1", NatType::FullCone);
        if let Some(s) = m.session_mut("peer1") {
            s.on_connected(ConnectionStrategy::Direct);
        }
        m.on_network_lost();
        assert_eq!(
            m.session("peer1").unwrap().state,
            ConnectionState::Reconnecting
        );
    }

    // --- run_chain ---

    #[test]
    fn test_run_chain_direct_success() {
        let mut m = make_manager();
        let results = vec![StepResult::Success];
        let state = m.run_chain("peer1", NatType::FullCone, results);
        assert_eq!(state, ConnectionState::Connected);
        assert_eq!(m.total_connected(), 1);
    }

    #[test]
    fn test_run_chain_fallback_to_ice() {
        let mut m = make_manager();
        // Direct fail → ICE success
        let results = vec![StepResult::Failed, StepResult::Success];
        let state = m.run_chain("peer1", NatType::PortRestricted, results);
        assert_eq!(state, ConnectionState::Connected);
    }

    #[test]
    fn test_run_chain_store_and_forward() {
        let mut m = make_manager();
        // Symmetric: нет Direct, нет ICE
        // Шаги: Tier1, Tier2, DHT, Seed, StoreAndForward = 5 шагов
        // Первые 4 — Failed, последний StoreAndForward — RetryLater
        let results = vec![
            StepResult::Failed,     // Tier1
            StepResult::Failed,     // Tier2
            StepResult::Failed,     // DHT
            StepResult::Failed,     // Seed
            StepResult::RetryLater, // StoreAndForward
        ];
        let state = m.run_chain("peer1", NatType::Symmetric, results);
        assert_eq!(state, ConnectionState::StoredAndForwarded);
        assert_eq!(m.total_stored(), 1);
    }

    #[test]
    fn test_run_chain_all_fail_unreachable() {
        let mut m = make_manager();
        // Все шаги fail
        let results = vec![StepResult::Failed; 10];
        let state = m.run_chain("peer1", NatType::Symmetric, results);
        assert_eq!(state, ConnectionState::Unreachable);
        assert_eq!(m.total_failed(), 1);
    }

    #[test]
    fn test_connected_count() {
        let mut m = make_manager();
        m.run_chain("p1", NatType::FullCone, vec![StepResult::Success]);
        m.run_chain("p2", NatType::FullCone, vec![StepResult::Success]);
        m.run_chain("p3", NatType::Symmetric, vec![StepResult::Failed; 10]);
        assert_eq!(m.connected_count(), 2);
    }

    #[test]
    fn test_connection_state_display() {
        assert_eq!(format!("{}", ConnectionState::Connected), "Connected");
        assert_eq!(format!("{}", ConnectionState::Reconnecting), "Reconnecting");
        assert_eq!(
            format!("{}", ConnectionState::StoredAndForwarded),
            "StoredAndForwarded"
        );
    }
}
