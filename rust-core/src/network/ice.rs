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
///
/// Резолвятся обе семьи (см. `dual_stack::resolve_ordered`): у
/// `stun.cloudflare.com` есть AAAA, у `stun.l.google.com` — только A. Отдельный
/// «IPv6-STUN сервер» не нужен: в сети только-v6 первый же dual-stack хост
/// ответит по IPv6, а в сети без IPv6 тот же список работает как раньше.
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
        // Резолвим hostname: сначала IPv6, затем IPv4 (порядок предпочтения).
        let targets = resolve_targets(server_addr);
        if targets.is_empty() {
            return Err(IceError::ResolveError(format!(
                "{}: no addresses resolved",
                server_addr
            )));
        }

        // Сокет dual-stack: один и тот же годится и для IPv6, и для IPv4.
        // Телефон без IPv6 получает обычный IPv4-сокет (откат внутри).
        let socket = super::dual_stack::bind_udp_dual(0)?;
        socket.set_read_timeout(Some(STUN_TIMEOUT))?;
        socket.set_write_timeout(Some(STUN_TIMEOUT))?;

        let request_bytes = encode_binding_request()?;
        let mut buf = [0u8; 2048];
        let mut last_error = IceError::AllServersFailed;

        for target in targets {
            // Ответ на предыдущую попытку мог остаться в буфере: иначе он
            // будет принят за ответ этого адреса.
            drain_socket(&socket, &mut buf);

            if let Err(e) = socket.send_to(&request_bytes, target) {
                last_error = IceError::Io(e);
                continue;
            }
            match socket.recv_from(&mut buf) {
                // Отражение приходит с того же адреса, но для IPv4 — в виде
                // v4-mapped: сравниваем и отдаём нормализованно.
                Ok((n, from))
                    if super::dual_stack::canonical(from) == super::dual_stack::canonical(target) =>
                {
                    return decode_binding_response(&buf[..n])
                        .map(super::dual_stack::canonical);
                }
                Ok(_) => {
                    last_error = IceError::Timeout;
                }
                Err(_) => {
                    last_error = IceError::Timeout;
                }
            }
        }

        Err(last_error)
    }

    /// Попробовать все STUN-серверы по очереди, вернуть первый успешный.
    ///
    /// # Аргументы
    /// - `servers` — список серверов (например `DEFAULT_STUN_SERVERS`)
    pub fn get_external_address_from_any(servers: &[&str]) -> IceResult<SocketAddr> {
        try_stun_servers(servers, Self::get_external_address)
    }
}

/// Пробует STUN endpoints по очереди: один внешний отказ не должен отменять
/// последующие адреса. Изолировано для детерминированного теста без интернета.
fn try_stun_servers<F>(servers: &[&str], mut query: F) -> IceResult<SocketAddr>
where
    F: FnMut(&str) -> IceResult<SocketAddr>,
{
    for server in servers {
        match query(server) {
            Ok(addr) => {
                tracing::info!(server = server, addr = %addr, "STUN: получен внешний адрес");
                return Ok(addr);
            }
            Err(error) => {
                tracing::warn!(server = server, error = %error, "STUN сервер недоступен");
            }
        }
    }
    Err(IceError::AllServersFailed)
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

/// Порядок попыток по одному серверу: IPv6 впереди, IPv4 следом.
///
/// Раньше здесь брался `to_socket_addrs()?.next()` — на dual-stack системе это
/// мог быть AAAA при сокете, привязанном к `0.0.0.0`, то есть заведомо
/// нерабочая попытка. Теперь адреса отсортированы и пробуются по очереди.
fn resolve_targets(server_addr: &str) -> Vec<SocketAddr> {
    super::dual_stack::resolve_ordered(server_addr)
}

/// Выбросить всё, что уже лежит в буфере сокета, не блокируясь надолго.
///
/// Перед новой попыткой в буфере может быть ответ (или мусор) от предыдущего
/// адреса; без сброса он был бы принят за ответ текущего.
fn drain_socket(socket: &UdpSocket, buf: &mut [u8]) {
    let previous = socket.read_timeout().ok().flatten();
    if socket
        .set_read_timeout(Some(Duration::from_millis(10)))
        .is_err()
    {
        return;
    }
    for _ in 0..4 {
        if socket.recv_from(buf).is_err() {
            break;
        }
    }
    let _ = socket.set_read_timeout(previous);
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
    fn stun_fallback_uses_next_endpoint_after_a_temporary_refusal() {
        let servers = ["offline.example:3478", "available.example:3478"];
        let expected: SocketAddr = "192.0.2.10:54321".parse().unwrap();
        let mut attempted = Vec::new();
        let result = try_stun_servers(&servers, |server| {
            attempted.push(server.to_string());
            if server.starts_with("offline.") {
                Err(IceError::Timeout)
            } else {
                Ok(expected)
            }
        });

        assert_eq!(result.unwrap(), expected);
        assert_eq!(
            attempted,
            servers.iter().map(|server| server.to_string()).collect::<Vec<_>>(),
        );
    }

    #[test]
    fn all_stun_refusals_return_a_recoverable_error_after_all_attempts() {
        let servers = ["offline-a.example:3478", "offline-b.example:3478"];
        let mut attempts = 0usize;
        let result = try_stun_servers(&servers, |_| {
            attempts += 1;
            Err(IceError::Timeout)
        });

        assert!(matches!(result, Err(IceError::AllServersFailed)));
        assert_eq!(attempts, servers.len());
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
        let addrs = resolve_targets("127.0.0.1:80");
        assert_eq!(addrs.len(), 1);
        assert_eq!(addrs[0].port(), 80);
        println!("✅ Резолв localhost работает");
    }

    #[test]
    fn test_resolve_bad_address() {
        // Невалидное имя: пустой список, и `get_external_address` вернёт
        // ResolveError, а не запаникует.
        assert!(resolve_targets("not-a-real-host.invalid:1234").is_empty());
        assert!(StunClient::get_external_address("not-a-real-host.invalid:1234").is_err());
        println!("✅ Резолв невалидного адреса возвращает ошибку");
    }

    #[test]
    fn test_v4_mapped_resolution_is_normalized() {
        // Резолв никогда не отдаёт наружу `::ffff:`: такой адрес дальше
        // сравнивается с адресом пира и попал бы в каталог как IPv6.
        for addr in resolve_targets("[::ffff:127.0.0.1]:7777") {
            assert!(addr.is_ipv4(), "resolve_targets вернул {addr}");
        }
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
