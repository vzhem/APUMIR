//! # SOCKS5-туннель для MQTT-брокеров (срез «любая сеть»)
//!
//! Минимальный асинхронный SOCKS5-клиент (RFC 1928 + аутентификация RFC 1929),
//! предназначенный только для одного: обернуть соединение к MQTT-брокеру в
//! туннель через лучший прокси телефона. Поддерживаются no-auth и
//! username/password; целевой адрес — только доменное имя (брокеры у нас домены).
//!
//! Конфигурация глобальная и меняется с Kotlin (ProxyAutopilot) в любой момент;
//! применяется при следующем подключении брокера — на «дёрганом» канале это
//! происходит постоянно, так что смена прокси подхватывается быстро.
//!
//! При неудаче SOCKS5-подключения вызовующий слой может откатиться на прямое
//! соединение (см. `mqtt_transport::apply_socks5_transport`).

use std::sync::RwLock;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Socks5ProxyConfig {
    pub host: String,
    pub port: u16,
    pub username: String,
    pub password: String,
}

static MQTT_SOCKS5_PROXY: RwLock<Option<Socks5ProxyConfig>> = RwLock::new(None);

/// Установить прокси для MQTT-трафика. Пустой host или нулевой порт игнорируются
/// вызывающими сторонами; здесь конфиг сохраняется как есть.
pub fn set_mqtt_socks5_proxy_config(config: Socks5ProxyConfig) {
    if let Ok(mut guard) = MQTT_SOCKS5_PROXY.write() {
        *guard = Some(config);
    }
}

/// Убрать прокси: MQTT снова ходит напрямую.
pub fn clear_mqtt_socks5_proxy_config() {
    if let Ok(mut guard) = MQTT_SOCKS5_PROXY.write() {
        *guard = None;
    }
}

/// Текущий прокси (нет — MQTT напрямую).
pub fn mqtt_socks5_proxy_config() -> Option<Socks5ProxyConfig> {
    MQTT_SOCKS5_PROXY.read().ok().and_then(|guard| guard.clone())
}

/// Приветствие: VER=5, методы no-auth(0) и/или username/password(2).
pub(crate) fn greeting_bytes(has_auth: bool) -> Vec<u8> {
    if has_auth {
        vec![0x05, 0x02, 0x00, 0x02]
    } else {
        vec![0x05, 0x01, 0x00]
    }
}

/// CONNECT-запрос: VER=5, CMD=1, RSV=0, ATYP=3 (домен), len, host, port BE.
pub(crate) fn connect_request_bytes(target_host: &str, target_port: u16) -> Vec<u8> {
    let host = target_host.as_bytes();
    let mut out = Vec::with_capacity(7 + host.len());
    out.push(0x05);
    out.push(0x01);
    out.push(0x00);
    out.push(0x03);
    out.push(host.len() as u8);
    out.extend_from_slice(host);
    out.push((target_port >> 8) as u8);
    out.push((target_port & 0xff) as u8);
    out
}

/// Разобрать заголовок ответа на CONNECT: [VER, REP, RSV, ATYP].
/// Ok(()) только при REP=0. Остальные байты (адрес+порт) дочитывает вызывающий.
pub(crate) fn parse_connect_reply_head(head: &[u8; 4]) -> Result<(), String> {
    if head[0] != 0x05 {
        return Err(format!("socks5: bad reply version {}", head[0]));
    }
    if head[1] != 0x00 {
        return Err(format!("socks5: connect rejected, rep={}", head[1]));
    }
    Ok(())
}

/// Сколько байт адреса идёт после 4-байтового заголовка ответа (порт входит отдельно).
pub(crate) fn reply_address_len(atyp: u8, domain_len: u8) -> Result<usize, String> {
    match atyp {
        0x01 => Ok(4),
        0x03 => Ok(1 + domain_len as usize),
        0x04 => Ok(16),
        other => Err(format!("socks5: unknown reply ATYP {other}")),
    }
}

/// Аутентификация username/password (RFC 1929): [1, ulen, u, plen, p] → [1, status].
fn auth_bytes(username: &str, password: &str) -> Result<Vec<u8>, String> {
    let u = username.as_bytes();
    let p = password.as_bytes();
    if u.is_empty() || u.len() > 255 || p.len() > 255 {
        return Err("socks5: invalid username/password length".to_string());
    }
    let mut out = Vec::with_capacity(3 + u.len() + p.len());
    out.push(0x01);
    out.push(u.len() as u8);
    out.extend_from_slice(u);
    out.push(p.len() as u8);
    out.extend_from_slice(p);
    Ok(out)
}

/// Установить туннель через прокси до target_host:target_port и вернуть готовый поток.
pub async fn connect_through(
    proxy: &Socks5ProxyConfig,
    target_host: String,
    target_port: u16,
) -> std::io::Result<TcpStream> {
    let mut stream = TcpStream::connect((proxy.host.as_str(), proxy.port)).await?;

    let has_auth = !proxy.username.is_empty();
    stream.write_all(&greeting_bytes(has_auth)).await?;
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(std::io::Error::other(format!(
            "socks5: bad greeting version {}",
            greeting[0]
        )));
    }
    match greeting[1] {
        0x00 => {}
        0x02 if has_auth => {
            stream
                .write_all(&auth_bytes(&proxy.username, &proxy.password).map_err(std::io::Error::other)?)
                .await?;
            let mut auth_reply = [0u8; 2];
            stream.read_exact(&mut auth_reply).await?;
            if auth_reply[1] != 0x00 {
                return Err(std::io::Error::other("socks5: authentication failed"));
            }
        }
        other => {
            return Err(std::io::Error::other(format!(
                "socks5: no acceptable auth method ({other})"
            )));
        }
    }

    stream
        .write_all(&connect_request_bytes(&target_host, target_port))
        .await?;
    let mut head = [0u8; 4];
    stream.read_exact(&mut head).await?;
    parse_connect_reply_head(&head).map_err(std::io::Error::other)?;
    // Дочитываем адрес (переменной длины) и порт — и отбрасываем.
    let mut len_byte = [0u8; 1];
    let addr_len = match head[3] {
        0x03 => {
            stream.read_exact(&mut len_byte).await?;
            reply_address_len(0x03, len_byte[0]).map_err(std::io::Error::other)?
        }
        atyp => reply_address_len(atyp, 0).map_err(std::io::Error::other)?,
    };
    let mut rest = vec![0u8; addr_len + 2];
    stream.read_exact(&mut rest).await?;

    Ok(stream)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn greeting_and_connect_wire_format() {
        assert_eq!(greeting_bytes(false), vec![0x05, 0x01, 0x00]);
        assert_eq!(greeting_bytes(true), vec![0x05, 0x02, 0x00, 0x02]);

        let request = connect_request_bytes("broker.hivemq.com", 1883);
        assert_eq!(
            request,
            vec![
                0x05, 0x01, 0x00, 0x03, 17, b'b', b'r', b'o', b'k', b'e', b'r', b'.', b'h', b'i',
                b'v', b'e', b'm', b'q', b'.', b'c', b'o', b'm', (1883 >> 8) as u8,
                (1883 & 0xff) as u8,
            ]
        );
    }

    #[test]
    fn reply_parsing_rejects_errors() {
        assert!(parse_connect_reply_head(&[0x05, 0x00, 0x00, 0x01]).is_ok());
        assert!(parse_connect_reply_head(&[0x05, 0x01, 0x00, 0x01]).is_err());
        assert!(parse_connect_reply_head(&[0x04, 0x00, 0x00, 0x01]).is_err());
        assert_eq!(reply_address_len(0x01, 0).unwrap(), 4);
        assert_eq!(reply_address_len(0x04, 0).unwrap(), 16);
        assert_eq!(reply_address_len(0x03, 5).unwrap(), 6);
        assert!(reply_address_len(0x09, 0).is_err());
    }

    #[test]
    fn auth_wire_format_and_validation() {
        let bytes = auth_bytes("user", "pass").unwrap();
        assert_eq!(
            bytes,
            vec![0x01, 4, b'u', b's', b'e', b'r', 4, b'p', b'a', b's', b's']
        );
        assert!(auth_bytes("", "pass").is_err());
    }

    #[test]
    fn config_round_trip_is_global() {
        clear_mqtt_socks5_proxy_config();
        assert!(mqtt_socks5_proxy_config().is_none());
        set_mqtt_socks5_proxy_config(Socks5ProxyConfig {
            host: "127.0.0.1".to_string(),
            port: 1080,
            username: "u".to_string(),
            password: "p".to_string(),
        });
        assert_eq!(mqtt_socks5_proxy_config().unwrap().port, 1080);
        clear_mqtt_socks5_proxy_config();
        assert!(mqtt_socks5_proxy_config().is_none());
    }
}
