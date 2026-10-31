//! # Единая политика пауз и таймаутов сети
//!
//! До этой правки повторы жили в трёх местах и расходились: прямой канал
//! (`direct_transport.rs`: `CONNECT_TIMEOUT` 4 с, `STREAM_TIMEOUT` 5 с,
//! `DIRECT_SEND_BUDGET` 10 с), MQTT (`mqtt_transport.rs`: старт 20 с, мост
//! 10 с, запрос 5 с) и досылка в приложении (`OutboxCadence`, Kotlin).
//!
//! Правило, которое теперь записано в одном месте:
//!
//! 1. **Пауза между попытками растёт экспоненциально** — удвоение от базы до
//!    потолка ([`BackoffPolicy`]). Линейная пауза «минута всегда» — это и была
//!    жалоба «сообщения идут с задержкой»: недосланное ждало до минуты, потом
//!    ещё минуту.
//! 2. **Потолок маленький.** Для переподключения брокера потолок 30 с: дольше
//!    ждать бессмысленно, сеть обычно возвращается быстрее, а смену сети
//!    отдельно будит `mqtt_transport::wake_reconnect`.
//! 3. **Сеть сменилась — пауза не действует.** Это не «ещё одна попытка», а
//!    новое состояние: и пул прямых соединений, и брокер поднимаются сразу
//!    (`on_network_available`).
//! 4. **Досылка в приложении не унифицируется.** `ChatOutboxPump`/
//!    `OutboxCadence` специально сделаны частыми (шаг секунды, а не минуты) и
//!    покрыты тестом; приводить их к «общей сетевой политике» нельзя — это
//!    вернуло бы минутную задержку.
//!
//! Здесь только арифметика: без таймеров, без сети, поэтому покрыта тестами.

use std::time::Duration;

/// Экспоненциальная пауза между попытками.
#[derive(Debug, Clone, Copy)]
pub struct BackoffPolicy {
    /// Пауза после первой неудачи.
    pub base: Duration,
    /// Потолок: дальше пауза не растёт.
    pub max: Duration,
}

impl BackoffPolicy {
    /// Пауза переподключения брокера: 1 с, удвоение, потолок 30 с.
    ///
    /// Те же числа, что были в `mqtt_transport.rs` в виде
    /// `reconnect_backoff_secs.saturating_mul(2).min(30)`; поведение не
    /// меняется, но число живёт в одном месте и проверяется тестом.
    pub const RECONNECT: Self = Self {
        base: Duration::from_secs(1),
        max: Duration::from_secs(30),
    };

    /// Пауза после `attempt`-й неудачи (`attempt` = 0 — первая).
    ///
    /// Ограничение по числу шагов обязательно: `2^n` при больших `n`
    /// переполнило бы `Duration` и запаниковало.
    pub fn delay(&self, attempt: u32) -> Duration {
        if self.base.is_zero() {
            return Duration::ZERO;
        }
        let shift = attempt.min(16);
        let factor = 1u32.checked_shl(shift).unwrap_or(u32::MAX);
        let scaled = self.base.saturating_mul(factor);
        scaled.min(self.max)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reconnect_backoff_doubles_then_caps() {
        let policy = BackoffPolicy::RECONNECT;
        assert_eq!(policy.delay(0), Duration::from_secs(1));
        assert_eq!(policy.delay(1), Duration::from_secs(2));
        assert_eq!(policy.delay(2), Duration::from_secs(4));
        assert_eq!(policy.delay(3), Duration::from_secs(8));
        assert_eq!(policy.delay(4), Duration::from_secs(16));
        // Потолок: 32 не выдаётся, ждём ровно 30.
        assert_eq!(policy.delay(5), Duration::from_secs(30));
        // И дальше не растёт и не переполняется.
        assert_eq!(policy.delay(64), Duration::from_secs(30));
        assert_eq!(policy.delay(u32::MAX), Duration::from_secs(30));
    }

    #[test]
    fn never_waits_longer_than_a_minute() {
        // Жалоба владельца «залипание на минуту»: политика не должна давать
        // паузу больше 30 с ни при каком числе попыток.
        for attempt in 0..100 {
            assert!(BackoffPolicy::RECONNECT.delay(attempt) <= Duration::from_secs(30));
        }
    }
}
