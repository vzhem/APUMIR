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

/// Magic cookie STUN (RFC 5389 §6) - байты 4..8 каждого сообщения.
pub const STUN_MAGIC_COOKIE: [u8; 4] = [0x21, 0x12, 0xA4, 0x42];

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

        // Отправляем запрос
        let request_bytes = encode_binding_request()?;
        socket.send_to(&request_bytes, server_socket)?;

        // Читаем ответ
        let mut buf = [0u8; 2048];
        let (n, _from) = socket.recv_from(&mut buf).map_err(|_| IceError::Timeout)?;

        decode_binding_response(&buf[..n])
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
// STUN ПОВЕРХ ЧУЖОГО СОКЕТА
// ═══════════════════════════════════════════════════════════════════
//
// Раньше внешний адрес узнавали через отдельный временный сокет. Такой
// адрес бесполезен: NAT заводит отображение для ТОГО сокета, а слушает
// телефон на другом (QUIC, порт 7777). Чтобы адрес в presence был
// настоящим, запрос должен уходить с самого QUIC-сокета - для этого
// кодирование и разбор вынесены в отдельные функции (см.
// `network::direct_transport`).

/// Собрать STUN Binding Request (RFC 5389). Возвращает байты датаграммы.
pub fn encode_binding_request() -> IceResult<Vec<u8>> {
    let transaction_id = TransactionId::new(rand::random());
    let message: Message<Attribute> = Message::new(MessageClass::Request, BINDING, transaction_id);
    let mut encoder = MessageEncoder::new();
    encoder
        .encode_into_bytes(message)
        .map_err(|e| IceError::StunEncoding(e.to_string()))
}

/// Разобрать ответ STUN-сервера и вынуть отражённый (внешний) адрес.
///
/// Сначала XOR-MAPPED-ADDRESS (RFC 5389), потом MAPPED-ADDRESS (RFC 3489).
pub fn decode_binding_response(bytes: &[u8]) -> IceResult<SocketAddr> {
    let mut decoder = MessageDecoder::<Attribute>::new();
    let response = decoder
        .decode_from_bytes(bytes)
        .map_err(|e| IceError::StunDecoding(e.to_string()))?
        .map_err(|e| IceError::StunDecoding(format!("{:?}", e)))?;

    response
        .get_attribute::<XorMappedAddress>()
        .map(|a| a.address())
        .or_else(|| {
            response
                .get_attribute::<MappedAddress>()
                .map(|a| a.address())
        })
        .ok_or(IceError::NoAddressInResponse)
}

/// Похожа ли датаграмма на STUN (RFC 7983 / RFC 9443): первые два бита
/// нулевые (у QUIC всегда выставлен fixed bit 0x40) и на месте magic cookie.
pub fn looks_like_stun(datagram: &[u8]) -> bool {
    datagram.len() >= 20
        && (datagram[0] & 0xC0) == 0
        && datagram[4..8] == STUN_MAGIC_COOKIE
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
    fn test_binding_request_looks_like_stun() {
        let request = encode_binding_request().unwrap();
        assert!(request.len() >= 20, "заголовок STUN - 20 байт");
        assert!(looks_like_stun(&request));
        // Первый байт QUIC всегда несёт fixed bit 0x40 - за STUN не сойдёт.
        let mut quic_like = request.clone();
        quic_like[0] |= 0x40;
        assert!(!looks_like_stun(&quic_like));
        assert!(!looks_like_stun(&request[..19]));
    }

    #[test]
    fn test_decode_rejects_garbage() {
        assert!(decode_binding_response(&[0u8; 8]).is_err());
    }

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
