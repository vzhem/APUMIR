//! Bounded waiting for MQTT client-channel requests.
//!
//! This helper is transport-agnostic and stores no topic or payload. It prevents a core task from
//! waiting forever while the MQTT EventLoop is itself blocked on a different bounded channel.

use std::fmt;
use std::future::Future;
use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MqttRequestError {
    Timeout {
        operation: &'static str,
        timeout_ms: u64,
    },
    Client {
        operation: &'static str,
        message: String,
    },
}

impl MqttRequestError {
    pub fn is_timeout(&self) -> bool {
        matches!(self, Self::Timeout { .. })
    }
}

impl fmt::Display for MqttRequestError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Timeout {
                operation,
                timeout_ms,
            } => write!(
                formatter,
                "MQTT {} request channel timed out after {} ms",
                operation, timeout_ms
            ),
            Self::Client { operation, message } => {
                write!(formatter, "MQTT {} request failed: {}", operation, message)
            }
        }
    }
}

pub async fn await_mqtt_request<F, T, E>(
    operation: &'static str,
    timeout: Duration,
    future: F,
) -> Result<T, MqttRequestError>
where
    F: Future<Output = Result<T, E>>,
    E: fmt::Display,
{
    match tokio::time::timeout(timeout, future).await {
        Ok(Ok(value)) => Ok(value),
        Ok(Err(error)) => Err(MqttRequestError::Client {
            operation,
            message: error.to_string(),
        }),
        Err(_) => Err(MqttRequestError::Timeout {
            operation,
            timeout_ms: duration_millis_u64(timeout),
        }),
    }
}

fn duration_millis_u64(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::future;

    #[derive(Debug)]
    struct FakeClientError;

    impl fmt::Display for FakeClientError {
        fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
            formatter.write_str("fake client error")
        }
    }

    #[tokio::test]
    async fn successful_request_returns_value() {
        let result = await_mqtt_request(
            "publish",
            Duration::from_secs(1),
            future::ready(Ok::<_, FakeClientError>(42u8)),
        )
        .await;

        assert_eq!(result, Ok(42));
    }

    #[tokio::test]
    async fn client_error_keeps_operation_without_payload_data() {
        let result = await_mqtt_request(
            "subscribe",
            Duration::from_secs(1),
            future::ready(Err::<(), _>(FakeClientError)),
        )
        .await;

        assert_eq!(
            result,
            Err(MqttRequestError::Client {
                operation: "subscribe",
                message: "fake client error".to_string(),
            })
        );
    }

    #[tokio::test]
    async fn pending_request_times_out_at_zero_without_network() {
        let result = await_mqtt_request(
            "publish",
            Duration::ZERO,
            future::pending::<Result<(), FakeClientError>>(),
        )
        .await;

        assert!(result.as_ref().unwrap_err().is_timeout());
        assert_eq!(
            result,
            Err(MqttRequestError::Timeout {
                operation: "publish",
                timeout_ms: 0,
            })
        );
    }

    #[test]
    fn timeout_display_is_explicit_and_contains_no_message_data() {
        let error = MqttRequestError::Timeout {
            operation: "publish",
            timeout_ms: 5_000,
        };
        assert_eq!(
            error.to_string(),
            "MQTT publish request channel timed out after 5000 ms"
        );
    }
}
