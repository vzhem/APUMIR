//! # mDNS — Обнаружение узлов в локальной сети
//!
//! Использует библиотеку `mdns-sd` для zero-configuration networking.
//!
//! ## Что это даёт:
//!
//! Когда два устройства находятся в одной WiFi сети — они могут
//! обнаружить друг друга БЕЗ интернета, роутеров, DHT и seed-узлов.
//!
//! Просто одно устройство «кричит» в multicast: «я здесь, порт 7777»,
//! а другое «слушает» и получает уведомление.
//!
//! ## Как работает:
//!
//! - **Service Type**: `_p2p-messenger._udp.local.` — наш идентификатор
//! - **Multicast**: `224.0.0.251:5353` — стандарт mDNS
//! - **TXT records**: дополнительные данные (NodeID, версия)
//!
//! ## Использование:
//!
//! ```ignore
//! // Публикуем себя
//! let mdns = MdnsService::new()?;
//! mdns.publish_self(node_id, 7777, "MyNode").await?;
//!
//! // Ищем других
//! let discovered = mdns.discover(Duration::from_secs(5)).await?;
//! for node in discovered {
//!     println!("Найден: {} на {}", node.name, node.address);
//! }
//! ```

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use tokio::sync::Mutex;

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Тип сервиса для mDNS. Все P2P Messenger используют этот идентификатор.
const SERVICE_TYPE: &str = "_p2p-messenger._udp.local.";

/// Ключ TXT-записи для NodeID.
const TXT_KEY_NODE_ID: &str = "node_id";

/// Ключ TXT-записи для версии протокола.
const TXT_KEY_PROTOCOL_VERSION: &str = "proto_ver";
const TXT_KEY_PUBLIC_ADDR: &str = "public_addr";
/// Device key id (P3 multi-device): tells devices of one account apart on the LAN.
const TXT_KEY_DEVICE_ID: &str = "device_id";

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum MdnsError {
    #[error("Ошибка mDNS daemon: {0}")]
    Daemon(String),

    #[error("Ошибка регистрации сервиса: {0}")]
    Registration(String),

    #[error("Ошибка обнаружения: {0}")]
    Discovery(String),

    #[error("Не удалось определить локальный IP")]
    NoLocalIp,
}

pub type MdnsResult<T> = Result<T, MdnsError>;

// ═══════════════════════════════════════════════════════════════════
// DISCOVERED NODE
// ═══════════════════════════════════════════════════════════════════

/// Информация об обнаруженном узле.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredNode {
    /// Полное mDNS-имя (например "MyPhone._p2p-messenger._udp.local.")
    pub full_name: String,

    /// Короткое имя (обычно имя устройства)
    pub name: String,

    /// Адрес и порт для подключения
    pub address: SocketAddr,

    /// NodeID если удалось прочитать из TXT-записи (hex-строка)
    pub node_id_hex: Option<String>,

    /// Версия протокола если удалось прочитать
    pub protocol_version: Option<u8>,
    pub public_addr: Option<String>,
    /// Device key id (hex) when the node advertises one; absent for legacy nodes.
    pub device_id_hex: Option<String>,
}

// ═══════════════════════════════════════════════════════════════════
// MDNS SERVICE
// ═══════════════════════════════════════════════════════════════════

/// Сервис для публикации себя в mDNS и обнаружения других.
pub struct MdnsService {
    daemon: ServiceDaemon,
    /// Полное имя нашего сервиса (для последующего unregister)
    my_service_name: Mutex<Option<String>>,
    /// Кэш обнаруженных узлов
    discovered: Arc<Mutex<HashMap<String, DiscoveredNode>>>,
}

impl MdnsService {
    /// Создать новый mDNS сервис.
    pub fn new() -> MdnsResult<Self> {
        let daemon = ServiceDaemon::new().map_err(|e| MdnsError::Daemon(e.to_string()))?;

        Ok(MdnsService {
            daemon,
            my_service_name: Mutex::new(None),
            discovered: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    /// Опубликовать себя в локальной сети.
    ///
    /// # Аргументы
    /// - `node_id_hex` — hex-строка NodeID (для идентификации)
    /// - `port` — порт на котором мы принимаем QUIC-соединения
    /// - `instance_name` — короткое имя (обычно имя устройства)
    /// - `protocol_version` — версия нашего Wire Protocol
    pub async fn publish_self(
        &self,
        node_id_hex: &str,
        port: u16,
        instance_name: &str,
        protocol_version: u8,
        public_addr: Option<&str>,
    ) -> MdnsResult<()> {
        self.publish_self_device(node_id_hex, port, instance_name, protocol_version, public_addr, None)
            .await
    }

    /// Same as [`publish_self`], plus the device key id so that two devices of one account
    /// (same `node_id`) can be told apart when resolving a device-level address.
    pub async fn publish_self_device(
        &self,
        node_id_hex: &str,
        port: u16,
        instance_name: &str,
        protocol_version: u8,
        public_addr: Option<&str>,
        device_id_hex: Option<&str>,
    ) -> MdnsResult<()> {
        // Публикуем ВСЕ пригодные локальные адреса, IPv6 впереди: раньше уходил
        // один IPv4 (`get_local_ip`), и в сети только-v6 объявление было пустым
        // по адресу — сосед не мог к нам прийти, хотя mDNS работал.
        let local_ips = local_addresses()?;
        let ips_text = local_ips
            .iter()
            .map(|ip| ip.to_string())
            .collect::<Vec<_>>()
            .join(",");

        // TXT-записи с метаданными
        let mut properties: HashMap<String, String> = HashMap::new();
        properties.insert(TXT_KEY_NODE_ID.to_string(), node_id_hex.to_string());
        properties.insert(
            TXT_KEY_PROTOCOL_VERSION.to_string(),
            protocol_version.to_string(),
        );
        if let Some(addr) = public_addr {
            properties.insert(TXT_KEY_PUBLIC_ADDR.to_string(), addr.to_string());
        }
        if let Some(device) = device_id_hex {
            properties.insert(TXT_KEY_DEVICE_ID.to_string(), device.to_string());
        }

        // Создаём ServiceInfo
        let hostname = format!("{}.local.", sanitize_name(instance_name));
        let service_info = ServiceInfo::new(
            SERVICE_TYPE,
            instance_name,
            &hostname,
            ips_text.as_str(),
            port,
            Some(properties),
        )
        .map_err(|e| MdnsError::Registration(e.to_string()))?;

        let full_name = service_info.get_fullname().to_string();

        // Регистрируем в daemon
        self.daemon
            .register(service_info)
            .map_err(|e| MdnsError::Registration(e.to_string()))?;

        // Сохраняем имя для последующего unregister
        *self.my_service_name.lock().await = Some(full_name);

        Ok(())
    }

    /// Обнаружить узлы в локальной сети.
    ///
    /// Слушает объявления в течение `timeout` и возвращает всех обнаруженных.
    /// Игнорирует наш собственный сервис.
    pub async fn discover(&self, timeout: Duration) -> MdnsResult<Vec<DiscoveredNode>> {
        let receiver = self
            .daemon
            .browse(SERVICE_TYPE)
            .map_err(|e| MdnsError::Discovery(e.to_string()))?;

        let my_name = self.my_service_name.lock().await.clone();
        let discovered = Arc::clone(&self.discovered);

        // Обрабатываем события в течение timeout
        let deadline = tokio::time::Instant::now() + timeout;

        loop {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                break;
            }

            // Ждём следующее событие с оставшимся таймаутом
            let event = tokio::task::spawn_blocking({
                let recv = receiver.clone();
                move || recv.recv_timeout(Duration::from_millis(200))
            })
            .await;

            let event = match event {
                Ok(Ok(e)) => e,
                _ => continue, // timeout или ошибка recv — просто продолжаем
            };

            match event {
                ServiceEvent::ServiceResolved(info) => {
                    let full_name = info.get_fullname().to_string();

                    // Игнорируем себя
                    if let Some(ref mine) = my_name {
                        if &full_name == mine {
                            continue;
                        }
                    }

                    // Извлекаем адрес: предпочитаем глобальный IPv6, откат на
                    // IPv4. Раньше брался первый IPv4 — в сети только-v6 сосед
                    // оставался невидимым, хотя его AAAA был в объявлении.
                    // link-local не берём: он достижим только с zone id,
                    // которого в `SocketAddr` нет.
                    let addresses = info.get_addresses();
                    let Some(ip) = crate::network::dual_stack::pick(addresses.iter()) else {
                        continue;
                    };
                    let socket_addr = SocketAddr::new(ip, info.get_port());

                    // TXT-записи
                    let properties = info.get_properties();
                    let node_id_hex = properties
                        .get(TXT_KEY_NODE_ID)
                        .map(|v| v.val_str().to_string());
                    let protocol_version = properties
                        .get(TXT_KEY_PROTOCOL_VERSION)
                        .and_then(|v| v.val_str().parse::<u8>().ok());
                    let public_addr = properties.get(TXT_KEY_PUBLIC_ADDR).map(|v| v.val_str().to_string());
                    let device_id_hex = properties
                        .get(TXT_KEY_DEVICE_ID)
                        .map(|v| v.val_str().to_string());

                    // Извлекаем короткое имя
                    let short_name = full_name
                        .split('.')
                        .next()
                        .unwrap_or(&full_name)
                        .to_string();

                    let node = DiscoveredNode {
                        full_name: full_name.clone(),
                        name: short_name,
                        address: socket_addr,
                        node_id_hex,
                        protocol_version,
                        public_addr,
                        device_id_hex,
                    };

                    discovered.lock().await.insert(full_name, node);
                }
                ServiceEvent::ServiceRemoved(_type, full_name) => {
                    discovered.lock().await.remove(&full_name);
                }
                _ => {} // Игнорируем остальные события
            }
        }

        let result: Vec<DiscoveredNode> = discovered.lock().await.values().cloned().collect();
        Ok(result)
    }

    /// Прекратить публикацию себя.
    pub async fn unpublish(&self) -> MdnsResult<()> {
        let name = self.my_service_name.lock().await.take();
        if let Some(name) = name {
            self.daemon
                .unregister(&name)
                .map_err(|e| MdnsError::Daemon(e.to_string()))?;
        }
        Ok(())
    }

    /// Остановить daemon (graceful shutdown).
    pub async fn shutdown(&self) -> MdnsResult<()> {
        self.unpublish().await?;
        self.daemon
            .shutdown()
            .map_err(|e| MdnsError::Daemon(e.to_string()))?;
        Ok(())
    }
}

// ═══════════════════════════════════════════════════════════════════
// ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
// ═══════════════════════════════════════════════════════════════════

/// Получить локальный IP-адрес (не loopback).
/// Find the LAN endpoint of one specific device. Several devices of one account share a
/// `node_id`, so the device key id is the only way to choose the right one. Matching is exact
/// and case-insensitive on the hex form.
pub fn find_device_endpoint<'a>(
    nodes: &'a [DiscoveredNode],
    device_id_hex: &str,
) -> Option<&'a DiscoveredNode> {
    if device_id_hex.is_empty() {
        return None;
    }
    nodes.iter().find(|node| {
        node.device_id_hex
            .as_deref()
            .is_some_and(|value| value.eq_ignore_ascii_case(device_id_hex))
    })
}

fn local_addresses() -> MdnsResult<Vec<IpAddr>> {
    let addrs = crate::network::dual_stack::local_shareable_ips();
    if addrs.is_empty() {
        return Err(MdnsError::NoLocalIp);
    }
    Ok(addrs)
}

/// Очистить имя от запрещённых mDNS-символов.
fn sanitize_name(name: &str) -> String {
    name.chars()
        .map(|c| {
            if c.is_alphanumeric() || c == '-' {
                c
            } else {
                '-'
            }
        })
        .collect()
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sanitize_name() {
        assert_eq!(sanitize_name("MyDevice"), "MyDevice");
        assert_eq!(sanitize_name("My Device"), "My-Device");
        assert_eq!(sanitize_name("Device@2024"), "Device-2024");
        assert_eq!(sanitize_name("valid-name-123"), "valid-name-123");
        println!("✅ sanitize_name() работает корректно");
    }

    #[tokio::test]
    async fn test_create_mdns_service() {
        let mdns = MdnsService::new();
        assert!(mdns.is_ok());
        println!("✅ MdnsService создаётся успешно");
    }

    #[tokio::test]
    async fn test_local_addresses_are_shareable() {
        // На большинстве систем должен быть хотя бы один non-loopback интерфейс
        match local_addresses() {
            Ok(list) => {
                assert!(!list.is_empty());
                println!("✅ Локальные адреса: {:?}", list);
                for ip in list {
                    assert!(!ip.is_loopback());
                    assert!(!crate::network::dual_stack::is_link_local(ip));
                }
            }
            Err(_) => {
                println!("⚠️  Не удалось определить локальный IP (нет сети?) — это нормально в изолированной среде");
            }
        }
    }

    #[tokio::test]
    async fn test_publish_and_unpublish() {
        let mdns = match MdnsService::new() {
            Ok(m) => m,
            Err(_) => {
                println!("⚠️  mDNS недоступен в тестовой среде");
                return;
            }
        };

        // Пытаемся опубликовать (может не сработать без сети)
        let result = mdns
            .publish_self("abc123deadbeef", 7777, "TestNode", 1, None)
            .await;

        match result {
            Ok(_) => {
                println!("✅ Публикация в mDNS прошла");
                let _ = mdns.unpublish().await;
            }
            Err(MdnsError::NoLocalIp) => {
                println!("⚠️  Нет сети — тест публикации пропущен");
            }
            Err(e) => {
                println!(
                    "⚠️  Публикация не удалась: {} (это может быть нормально в CI)",
                    e
                );
            }
        }
    }

    fn node(name: &str, device: Option<&str>) -> DiscoveredNode {
        DiscoveredNode {
            full_name: name.to_string(),
            name: name.to_string(),
            address: "192.168.1.10:7777".parse().unwrap(),
            node_id_hex: Some("pk_same".to_string()),
            protocol_version: Some(1),
            public_addr: None,
            device_id_hex: device.map(str::to_string),
        }
    }

    #[test]
    fn device_endpoint_is_chosen_by_device_id_not_node_id() {
        let nodes = vec![node("phone", Some("aa11")), node("pc", Some("BB22")), node("old", None)];
        assert_eq!(find_device_endpoint(&nodes, "bb22").map(|n| n.name.as_str()), Some("pc"));
        assert_eq!(find_device_endpoint(&nodes, "cc33").map(|n| n.name.as_str()), None);
        assert!(find_device_endpoint(&nodes, "").is_none());
    }

    #[tokio::test]
    async fn test_shutdown_gracefully() {
        let mdns = match MdnsService::new() {
            Ok(m) => m,
            Err(_) => {
                println!("⚠️  mDNS недоступен");
                return;
            }
        };

        // Shutdown без предварительного publish должен работать
        let result = mdns.shutdown().await;
        assert!(result.is_ok());
        println!("✅ Graceful shutdown работает");
    }
}
