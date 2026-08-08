//! # ICE / STUN — NAT Traversal
//!
//! Модуль для определения нашего внешнего IP-адреса через STUN-серверы.
//!
//! ## Что такое NAT и почему это проблема:
//!
//! Большинство домашних роутеров используют NAT (Network Address Translation) —
//! ваш телефон видит себя как `192.168.1.5`, но в интернете он виден как
//! публичный IP роутера (например `217.66.5.10:52341`).
//!
//! Проблема: другие узлы в интернете не могут подключиться к `192.168.1.5`.
//! Они должны подключаться к `217.66.5.10:52341`, но мы не знаем этот адрес!
//!
//! ## Что делает STUN:
//!
//! STUN-сервер — это простой сервис в интернете. Мы отправляем ему запрос,
//! он отвечает: "Я вижу тебя как 217.66.5.10:52341". Готово — теперь мы
//! знаем свой публичный адрес и можем сообщить его другим узлам через DHT.
//!
//! ## Наши STUN-серверы (публичные, бесплатные):
//!
//! - `stun.l.google.com:19302` — Google
//! - `stun.cloudflare.com:3478` — Cloudflare
//! - `stun.nextcloud.com:443` — NextCloud

use std::net::{SocketAddr, UdpSocket};
use std::time::Duration;

use bytecodec::{DecodeExt, EncodeExt};
use stun_codec::rfc5389::attributes::{MappedAddress, XorMappedAddress};
use stun_codec::rfc5389::methods::BINDING;
use stun_codec::rfc5389::Attribute;
use stun_codec::{Message, MessageClass, MessageDecoder, MessageEncoder, TransactionId};

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Таймаут ожидания ответа от STUN-сервера.
pub const STUN_TIMEOUT: Duration = Duration::from_secs(5);

/// Список публичных STUN-серверов (пробуются по очереди).
pub const DEFAULT_STUN_SERVERS: &[&str] = &[
    "stun.cloudflare.com:3478",
    "stun.nextcloud.com:443",
    "stun.l.google.com:19302",
    "stun1.l.google.com:19302",
    "stun2.l.google.com:19302",
];

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum IceError {
    #[error("Ошибка ввода-вывода: {0}")]
    Io(#[from] std::io::Error),

    #[error("Ошибка кодирования STUN: {0}")]
    StunEncoding(String),

    #[error("Ошибка декодирования STUN: {0}")]
    StunDecoding(String),

    #[error("STUN-сервер не вернул адрес")]
    NoAddressInResponse,

    #[error("Таймаут ожидания ответа от STUN")]
    Timeout,

    #[error("Все STUN-серверы недоступны")]
    AllServersFailed,

    #[error("Не удалось разрезолвить адрес: {0}")]
    ResolveError(String),
}

pub type IceResult<T> = Result<T, IceError>;

// ═══════════════════════════════════════════════════════════════════
// STUN CLIENT
// ═══════════════════════════════════════════════════════════════════

/// Клиент для отправки STUN-запросов и получения внешнего адреса.
pub struct StunClient;

impl StunClient {
    /// Запросить наш внешний адрес у STUN-сервера.
    ///
    /// # Аргументы
    /// - `server_addr` — адрес STUN-сервера в формате "host:port"
    ///
    /// # Возвращает
    /// - Наш публичный `SocketAddr` как его видит STUN-сервер
    pub fn get_external_address(server_addr: &str) -> IceResult<SocketAddr> {
        // Резолвим hostname в IP
        let server_socket: SocketAddr = server_addr
            .to_socket_addrs_first()
            .map_err(|e| IceError::ResolveError(format!("{}: {}", server_addr, e)))?;

        // Создаём UDP-сокет
        let socket = UdpSocket::bind("0.0.0.0:0")?;
        socket.set_read_timeout(Some(STUN_TIMEOUT))?;
        socket.set_write_timeout(Some(STUN_TIMEOUT))?;

        // Создаём STUN Binding Request
        let transaction_id = TransactionId::new(rand::random());
        let message: Message<Attribute> =
            Message::new(MessageClass::Request, BINDING, transaction_id);

        // Кодируем в байты
        let mut encoder = MessageEncoder::new();
        let request_bytes = encoder
            .encode_into_bytes(message)
            .map_err(|e| IceError::StunEncoding(e.to_string()))?;

        // Отправляем запрос
        socket.send_to(&request_bytes, server_socket)?;

        // Читаем ответ
        let mut buf = [0u8; 2048];
        let (n, _from) = socket.recv_from(&mut buf).map_err(|_| IceError::Timeout)?;

        // Декодируем STUN-ответ
        let mut decoder = MessageDecoder::<Attribute>::new();
        let response = decoder
            .decode_from_bytes(&buf[..n])
            .map_err(|e| IceError::StunDecoding(e.to_string()))?
            .map_err(|e| IceError::StunDecoding(format!("{:?}", e)))?;

        // Извлекаем адрес — сначала пробуем XOR-Mapped (RFC 5389),
        // потом обычный MappedAddress (RFC 3489, старый)
        let addr = response
            .get_attribute::<XorMappedAddress>()
            .map(|a| a.address())
            .or_else(|| {
                response
                    .get_attribute::<MappedAddress>()
                    .map(|a| a.address())
            })
            .ok_or(IceError::NoAddressInResponse)?;

        Ok(addr)
    }

    /// Попробовать все STUN-серверы по очереди, вернуть первый успешный.
    ///
    /// # Аргументы
    /// - `servers` — список серверов (например `DEFAULT_STUN_SERVERS`)
    pub fn get_external_address_from_any(servers: &[&str]) -> IceResult<SocketAddr> {
        for server in servers {
            match Self::get_external_address(server) {
                Ok(addr) => {
                    tracing::info!(server = server, addr = %addr, "STUN: получен внешний адрес");
                    return Ok(addr);
                }
                Err(e) => {
                    tracing::warn!(server = server, error = %e, "STUN сервер недоступен");
                    continue;
                }
            }
        }
        Err(IceError::AllServersFailed)
    }
}

// ═══════════════════════════════════════════════════════════════════
// ВСПОМОГАТЕЛЬНОЕ
// ═══════════════════════════════════════════════════════════════════

/// Trait для удобного резолва адреса — первый успешный результат.
trait ResolveFirst {
    fn to_socket_addrs_first(&self) -> std::io::Result<SocketAddr>;
}

impl ResolveFirst for &str {
    fn to_socket_addrs_first(&self) -> std::io::Result<SocketAddr> {
        use std::net::ToSocketAddrs;
        self.to_socket_addrs()?.next().ok_or_else(|| {
            std::io::Error::new(
                std::io::ErrorKind::AddrNotAvailable,
                "no addresses resolved",
            )
        })
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_stun_servers_not_empty() {
        assert!(!DEFAULT_STUN_SERVERS.is_empty());
        assert!(DEFAULT_STUN_SERVERS.len() >= 3);
        println!(
            "✅ Список STUN-серверов не пустой ({} серверов)",
            DEFAULT_STUN_SERVERS.len()
        );
    }

    #[test]
    fn test_stun_timeout_reasonable() {
        assert!(STUN_TIMEOUT.as_secs() >= 1);
        assert!(STUN_TIMEOUT.as_secs() <= 30);
        println!("✅ Таймаут STUN разумный: {:?}", STUN_TIMEOUT);
    }

    #[test]
    fn test_resolve_localhost() {
        let addr = "127.0.0.1:80".to_socket_addrs_first();
        assert!(addr.is_ok());
        assert_eq!(addr.unwrap().port(), 80);
        println!("✅ Резолв localhost работает");
    }

    #[test]
    fn test_resolve_bad_address() {
        let addr = "not-a-real-host.invalid:1234".to_socket_addrs_first();
        assert!(addr.is_err());
        println!("✅ Резолв невалидного адреса возвращает ошибку");
    }

    /// Тест реального обращения к Google STUN.
    /// Помечен `#[ignore]` — запускается только вручную если есть интернет.
    /// Запуск: `cargo test test_real_stun_google -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn test_real_stun_google() {
        let result = StunClient::get_external_address("stun.l.google.com:19302");
        match result {
            Ok(addr) => println!("✅ Наш внешний адрес по мнению Google: {}", addr),
            Err(e) => println!("⚠️  STUN не отвечает: {}", e),
        }
    }

    /// Тест fallback через несколько серверов.
    /// Помечен `#[ignore]` — требует интернет.
    #[test]
    #[ignore]
    fn test_real_stun_fallback() {
        let result = StunClient::get_external_address_from_any(DEFAULT_STUN_SERVERS);
        match result {
            Ok(addr) => println!("✅ Внешний адрес получен через fallback: {}", addr),
            Err(e) => println!("⚠️  Все STUN недоступны: {}", e),
        }
    }

    #[test]
    fn test_stun_bad_server_returns_error() {
        // Сервер который точно не отвечает по STUN
        let result = StunClient::get_external_address("127.0.0.1:1");
        assert!(result.is_err());
        println!("✅ STUN на несуществующий сервер → ошибка");
    }
}
