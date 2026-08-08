//! Adaptive Polling with Exponential Backoff
//! Управляет интервалами синхронизации DHT и Presence
//! в зависимости от активности пользователя и состояния сети

use std::time::Duration;

// ============================================================
// Polling Mode
// ============================================================

/// Текущий режим polling
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PollingMode {
    /// Пользователь активно использует приложение (5 сек)
    Active,
    /// Приложение в фоне — экспоненциальный backoff
    Background,
    /// Сеть отсутствует — polling остановлен
    Offline,
}

impl std::fmt::Display for PollingMode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PollingMode::Active => write!(f, "Active (5s)"),
            PollingMode::Background => write!(f, "Background (backoff)"),
            PollingMode::Offline => write!(f, "Offline (stopped)"),
        }
    }
}

// ============================================================
// Adaptive Polling Config
// ============================================================

/// Конфигурация adaptive polling
#[derive(Debug, Clone)]
pub struct AdaptivePollingConfig {
    /// Интервал в активном режиме
    pub active_interval: Duration,
    /// Начальный интервал в фоне
    pub initial_backoff: Duration,
    /// Максимальный интервал в фоне
    pub max_backoff: Duration,
    /// Множитель экспоненциального роста
    pub multiplier: f64,
}

impl Default for AdaptivePollingConfig {
    fn default() -> Self {
        Self {
            active_interval: Duration::from_secs(5),
            initial_backoff: Duration::from_secs(10),
            max_backoff: Duration::from_secs(300), // 5 минут
            multiplier: 2.0,
        }
    }
}

impl AdaptivePollingConfig {
    pub fn new() -> Self {
        Self::default()
    }
}

// ============================================================
// Adaptive Polling Engine
// ============================================================

/// Движок adaptive polling
#[derive(Debug)]
pub struct AdaptivePolling {
    config: AdaptivePollingConfig,
    mode: PollingMode,
    /// Текущий интервал (для background — растёт экспоненциально)
    current_interval: Duration,
    /// Сколько шагов backoff прошло
    backoff_steps: u32,
}

impl AdaptivePolling {
    pub fn new(config: AdaptivePollingConfig) -> Self {
        let active_interval = config.active_interval;
        Self {
            config,
            mode: PollingMode::Active,
            current_interval: active_interval,
            backoff_steps: 0,
        }
    }

    pub fn with_defaults() -> Self {
        Self::new(AdaptivePollingConfig::default())
    }

    // --- Getters ---

    pub fn mode(&self) -> &PollingMode {
        &self.mode
    }

    pub fn current_interval(&self) -> Duration {
        self.current_interval
    }

    pub fn backoff_steps(&self) -> u32 {
        self.backoff_steps
    }

    // --- Mode transitions ---

    /// Пользователь стал активным — сброс на минимальный интервал
    pub fn set_active(&mut self) {
        self.mode = PollingMode::Active;
        self.current_interval = self.config.active_interval;
        self.backoff_steps = 0;
    }

    /// Приложение ушло в фон — начинаем backoff
    pub fn set_background(&mut self) {
        if self.mode == PollingMode::Active {
            self.mode = PollingMode::Background;
            self.current_interval = self.config.initial_backoff;
            self.backoff_steps = 1;
        }
    }

    /// Сеть пропала — polling остановлен
    pub fn set_offline(&mut self) {
        self.mode = PollingMode::Offline;
    }

    /// Сеть восстановлена — сразу в активный режим
    pub fn set_network_available(&mut self) {
        self.set_active();
    }

    /// Пользователь interacted (открыл чат, начал писать)
    /// — немедленный poll + сброс на active
    pub fn on_user_interaction(&mut self) {
        self.set_active();
    }

    // --- Interval calculation ---

    /// Получить интервал до следующего poll
    /// В фоне: после каждого poll интервал растёт
    pub fn next_interval(&mut self) -> Duration {
        let interval = self.current_interval;

        if self.mode == PollingMode::Background {
            self.advance_backoff();
        }

        interval
    }

    /// Продвинуть backoff на следующий шаг
    fn advance_backoff(&mut self) {
        self.backoff_steps += 1;

        let new_secs = self.config.initial_backoff.as_secs_f64()
            * self.config.multiplier.powi(self.backoff_steps as i32 - 1);

        let new_interval = Duration::from_secs_f64(new_secs);

        self.current_interval = if new_interval > self.config.max_backoff {
            self.config.max_backoff
        } else {
            new_interval
        };
    }

    /// Сколько шагов backoff до достижения максимума?
    pub fn steps_to_max(&self) -> u32 {
        if self.config.multiplier <= 1.0 {
            return 0;
        }
        let initial = self.config.initial_backoff.as_secs_f64();
        let max = self.config.max_backoff.as_secs_f64();

        if initial >= max {
            return 0;
        }

        let ratio = max / initial;
        (ratio.log(self.config.multiplier)).ceil() as u32
    }
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    // --- PollingMode display ---

    #[test]
    fn test_polling_mode_display() {
        assert_eq!(format!("{}", PollingMode::Active), "Active (5s)");
        assert_eq!(
            format!("{}", PollingMode::Background),
            "Background (backoff)"
        );
        assert_eq!(format!("{}", PollingMode::Offline), "Offline (stopped)");
    }

    // --- Initial state ---

    #[test]
    fn test_initial_state_is_active() {
        let p = AdaptivePolling::with_defaults();
        assert_eq!(p.mode(), &PollingMode::Active);
        assert_eq!(p.current_interval(), Duration::from_secs(5));
        assert_eq!(p.backoff_steps(), 0);
    }

    // --- Active mode stays constant ---

    #[test]
    fn test_active_mode_constant_interval() {
        let mut p = AdaptivePolling::with_defaults();
        // В active режиме интервал не растёт
        let i1 = p.next_interval();
        let i2 = p.next_interval();
        let i3 = p.next_interval();
        assert_eq!(i1, Duration::from_secs(5));
        assert_eq!(i2, Duration::from_secs(5));
        assert_eq!(i3, Duration::from_secs(5));
    }

    // --- Transition to background ---

    #[test]
    fn test_transition_to_background() {
        let mut p = AdaptivePolling::with_defaults();
        p.set_background();
        assert_eq!(p.mode(), &PollingMode::Background);
        assert_eq!(p.current_interval(), Duration::from_secs(10));
        assert_eq!(p.backoff_steps(), 1);
    }

    // --- Exponential backoff growth ---

    #[test]
    fn test_backoff_exponential_growth() {
        let mut p = AdaptivePolling::with_defaults();
        p.set_background();

        // Step 1: 10s (initial)
        let i1 = p.next_interval();
        assert_eq!(i1, Duration::from_secs(10));

        // Step 2: 20s
        let i2 = p.next_interval();
        assert_eq!(i2, Duration::from_secs(20));

        // Step 3: 40s
        let i3 = p.next_interval();
        assert_eq!(i3, Duration::from_secs(40));

        // Step 4: 80s
        let i4 = p.next_interval();
        assert_eq!(i4, Duration::from_secs(80));

        // Step 5: 160s
        let i5 = p.next_interval();
        assert_eq!(i5, Duration::from_secs(160));
    }

    // --- Max backoff cap ---

    #[test]
    fn test_backoff_max_cap() {
        let mut p = AdaptivePolling::with_defaults();
        p.set_background();

        // 10 -> 20 -> 40 -> 80 -> 160 -> 300 (capped at 300)
        for _ in 0..5 {
            p.next_interval();
        }

        let i6 = p.next_interval();
        assert_eq!(i6, Duration::from_secs(300));

        // После cap — остаётся на максимуме
        let i7 = p.next_interval();
        assert_eq!(i7, Duration::from_secs(300));
    }

    // --- Reset to active from background ---

    #[test]
    fn test_reset_to_active_from_background() {
        let mut p = AdaptivePolling::with_defaults();
        p.set_background();

        // Несколько шагов backoff
        p.next_interval(); // 10
        p.next_interval(); // 20
        p.next_interval(); // 40

        // Пользователь interacted — сброс
        p.on_user_interaction();
        assert_eq!(p.mode(), &PollingMode::Active);
        assert_eq!(p.current_interval(), Duration::from_secs(5));
        assert_eq!(p.backoff_steps(), 0);
    }

    // --- Set active directly ---

    #[test]
    fn test_set_active_directly() {
        let mut p = AdaptivePolling::with_defaults();
        p.set_background();
        p.next_interval(); // 10
        p.set_active();
        assert_eq!(p.current_interval(), Duration::from_secs(5));
        assert_eq!(p.backoff_steps(), 0);
    }

    // --- Network events ---

    #[test]
    fn test_network_lost_goes_offline() {
        let mut p = AdaptivePolling::with_defaults();
        p.set_offline();
        assert_eq!(p.mode(), &PollingMode::Offline);
    }

    #[test]
    fn test_network_restored_resets_to_active() {
        let mut p = AdaptivePolling::with_defaults();
        p.set_background();
        p.next_interval(); // 10
        p.set_offline();
        p.set_network_available();
        assert_eq!(p.mode(), &PollingMode::Active);
        assert_eq!(p.current_interval(), Duration::from_secs(5));
    }

    // --- Steps to max calculation ---

    #[test]
    fn test_steps_to_max_default_config() {
        let p = AdaptivePolling::with_defaults();
        // 10 * 2^n >= 300
        // 2^n >= 30
        // n >= log2(30) ≈ 4.9 → 5 steps
        assert_eq!(p.steps_to_max(), 5);
    }

    #[test]
    fn test_steps_to_max_equal_initial_and_max() {
        let config = AdaptivePollingConfig {
            initial_backoff: Duration::from_secs(300),
            max_backoff: Duration::from_secs(300),
            ..Default::default()
        };
        let p = AdaptivePolling::new(config);
        assert_eq!(p.steps_to_max(), 0);
    }

    // --- Background doesn't double-set ---

    #[test]
    fn test_set_background_twice_no_reset() {
        let mut p = AdaptivePolling::with_defaults();
        p.set_background();
        let i1 = p.current_interval();
        p.set_background(); // Повторный вызов — не сбрасывает backoff
        let i2 = p.current_interval();
        assert_eq!(i1, i2); // Интервал не изменился
    }

    // --- Custom config ---

    #[test]
    fn test_custom_multiplier() {
        let config = AdaptivePollingConfig {
            active_interval: Duration::from_secs(3),
            initial_backoff: Duration::from_secs(5),
            max_backoff: Duration::from_secs(50),
            multiplier: 3.0,
        };
        let mut p = AdaptivePolling::new(config);
        assert_eq!(p.current_interval(), Duration::from_secs(3));

        p.set_background();
        let i1 = p.next_interval(); // 5
        let i2 = p.next_interval(); // 15
        let i3 = p.next_interval(); // 45
        let i4 = p.next_interval(); // 50 (capped)

        assert_eq!(i1, Duration::from_secs(5));
        assert_eq!(i2, Duration::from_secs(15));
        assert_eq!(i3, Duration::from_secs(45));
        assert_eq!(i4, Duration::from_secs(50));
    }
}
