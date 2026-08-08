//! # Connection Pool — Пул активных QUIC-соединений
//!
//! Хранит установленные соединения с узлами и переиспользует их.
//! Это критично для производительности: открытие нового QUIC-соединения
//! занимает ~50-200 мс, а переиспользование — микросекунды.
//!
//! ## Что делает:
//!
//! - Кэширует активные `QuicConnection` по `NodeID`
//! - Автоматически удаляет закрытые/устаревшие соединения
//! - Предоставляет thread-safe доступ через `Arc<Mutex<...>>`
//! - Ограничивает максимальное число соединений (защита от DoS)
//!
//! ## Использование:
//!
//! ```ignore
//! let pool = ConnectionPool::new(256); // максимум 256 соединений
//!
//! // Первое обращение — создаёт соединение
//! let conn = pool.get_or_connect(node_id, addr, &quic_client).await?;
//! conn.send_message(&data).await?;
//!
//! // Повторное обращение — переиспользует
//! let conn = pool.get(node_id).unwrap();
//! conn.send_message(&data).await?;
//! ```

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use tokio::sync::Mutex;

use super::quic_client::{QuicClient, QuicClientError, QuicConnection};

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum ConnectionPoolError {
    #[error("Пул переполнен (максимум {max})")]
    PoolFull { max: usize },

    #[error("Ошибка QUIC: {0}")]
    Quic(#[from] QuicClientError),

    #[error("Соединение не найдено: {0}")]
    NotFound(String),
}

pub type PoolResult<T> = Result<T, ConnectionPoolError>;

// ═══════════════════════════════════════════════════════════════════
// ЗАПИСЬ В ПУЛЕ
// ═══════════════════════════════════════════════════════════════════

/// Одна запись в пуле: соединение + метаданные.
struct PoolEntry {
    connection: Arc<QuicConnection>,
    /// Когда было последнее использование (для очистки идла)
    last_used: Instant,
}

// ═══════════════════════════════════════════════════════════════════
// CONNECTION POOL
// ═══════════════════════════════════════════════════════════════════

/// Пул активных QUIC-соединений.
///
/// Ключ — `Vec<u8>` (обычно NodeID = 32 байта, но принимаем любой).
/// В реальном использовании ключ = NodeID из crypto::keys.
pub struct ConnectionPool {
    entries: Mutex<HashMap<Vec<u8>, PoolEntry>>,
    max_connections: usize,
    /// Соединение считается idle после этого времени → закрывается.
    idle_timeout: Duration,
}

impl ConnectionPool {
    /// Создать новый пул.
    ///
    /// # Аргументы
    /// - `max_connections` — максимум одновременных соединений
    ///
    /// Idle timeout по умолчанию 300 секунд (5 минут).
    pub fn new(max_connections: usize) -> Self {
        ConnectionPool {
            entries: Mutex::new(HashMap::new()),
            max_connections,
            idle_timeout: Duration::from_secs(300),
        }
    }

    /// Создать пул с кастомным idle timeout.
    pub fn with_idle_timeout(max_connections: usize, idle_timeout: Duration) -> Self {
        ConnectionPool {
            entries: Mutex::new(HashMap::new()),
            max_connections,
            idle_timeout,
        }
    }

    /// Получить существующее соединение или создать новое.
    ///
    /// # Аргументы
    /// - `node_key` — идентификатор узла (обычно NodeID.0.to_vec())
    /// - `addr` — адрес узла для подключения
    /// - `quic_client` — клиент QUIC для установки нового соединения
    pub async fn get_or_connect(
        &self,
        node_key: Vec<u8>,
        addr: SocketAddr,
        quic_client: &QuicClient,
    ) -> PoolResult<Arc<QuicConnection>> {
        // Быстрая проверка — может уже есть?
        {
            let mut entries = self.entries.lock().await;

            if let Some(entry) = entries.get_mut(&node_key) {
                // Есть — проверяем что не закрыто
                if !entry.connection.is_closed() {
                    entry.last_used = Instant::now();
                    return Ok(Arc::clone(&entry.connection));
                }
                // Закрыто — удаляем и создадим новое ниже
                entries.remove(&node_key);
            }

            // Проверка на переполнение
            if entries.len() >= self.max_connections {
                // Пробуем очистить idle соединения
                self.cleanup_idle_locked(&mut entries);
                if entries.len() >= self.max_connections {
                    return Err(ConnectionPoolError::PoolFull {
                        max: self.max_connections,
                    });
                }
            }
        } // Lock освобождён — можно делать await для connect()

        // Создаём новое соединение
        let connection = quic_client.connect(addr, "p2p-messenger").await?;
        let arc_conn = Arc::new(connection);

        // Вставляем в пул
        let mut entries = self.entries.lock().await;
        entries.insert(
            node_key,
            PoolEntry {
                connection: Arc::clone(&arc_conn),
                last_used: Instant::now(),
            },
        );

        Ok(arc_conn)
    }

    /// Получить соединение если оно уже есть в пуле.
    pub async fn get(&self, node_key: &[u8]) -> Option<Arc<QuicConnection>> {
        let mut entries = self.entries.lock().await;
        if let Some(entry) = entries.get_mut(node_key) {
            if !entry.connection.is_closed() {
                entry.last_used = Instant::now();
                return Some(Arc::clone(&entry.connection));
            }
            // Закрыто — удаляем
            entries.remove(node_key);
        }
        None
    }

    /// Добавить готовое соединение в пул (обычно от accept()).
    ///
    /// Возвращает ошибку `PoolFull` если пул переполнен.
    /// При переполнении вызовите `cleanup_idle()` вручную для освобождения места.
    pub async fn insert(&self, node_key: Vec<u8>, connection: QuicConnection) -> PoolResult<()> {
        let mut entries = self.entries.lock().await;

        // Строгая проверка лимита — БЕЗ автоматической очистки
        if entries.len() >= self.max_connections {
            return Err(ConnectionPoolError::PoolFull {
                max: self.max_connections,
            });
        }

        entries.insert(
            node_key,
            PoolEntry {
                connection: Arc::new(connection),
                last_used: Instant::now(),
            },
        );

        Ok(())
    }

    /// Удалить соединение из пула.
    /// Возвращает true если было удалено.
    pub async fn remove(&self, node_key: &[u8]) -> bool {
        let mut entries = self.entries.lock().await;
        if let Some(entry) = entries.remove(node_key) {
            entry.connection.close(b"removed from pool");
            true
        } else {
            false
        }
    }

    /// Количество активных соединений.
    pub async fn len(&self) -> usize {
        self.entries.lock().await.len()
    }

    /// Проверить пустой ли пул.
    pub async fn is_empty(&self) -> bool {
        self.entries.lock().await.is_empty()
    }

    /// Очистить все idle-соединения (не использовались > idle_timeout).
    /// Возвращает число удалённых соединений.
    pub async fn cleanup_idle(&self) -> usize {
        let mut entries = self.entries.lock().await;
        self.cleanup_idle_locked(&mut entries)
    }

    /// Внутренняя версия очистки — требует уже захваченный lock.
    fn cleanup_idle_locked(&self, entries: &mut HashMap<Vec<u8>, PoolEntry>) -> usize {
        let now = Instant::now();
        let before = entries.len();

        entries.retain(|_key, entry| {
            let alive = !entry.connection.is_closed();
            let recent = now.duration_since(entry.last_used) < self.idle_timeout;
            let keep = alive && recent;
            if !keep {
                entry.connection.close(b"idle timeout");
            }
            keep
        });

        before - entries.len()
    }

    /// Graceful shutdown: закрыть все соединения и очистить пул.
    pub async fn shutdown(&self) {
        let mut entries = self.entries.lock().await;
        for (_key, entry) in entries.drain() {
            entry.connection.close(b"pool shutdown");
        }
    }

    /// Список ключей всех активных соединений (для диагностики).
    pub async fn active_keys(&self) -> Vec<Vec<u8>> {
        let entries = self.entries.lock().await;
        entries.keys().cloned().collect()
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};

    fn any_port() -> SocketAddr {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 0)
    }

    /// Хелпер: создать пару "сервер-клиент" и вернуть готовое соединение
    /// от клиента к серверу. Использует spawn для accept на сервере.
    async fn setup_connection_pair() -> (Arc<QuicClient>, QuicClient, QuicConnection, SocketAddr) {
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();
        let client = QuicClient::new(any_port()).unwrap();

        let server_clone = Arc::clone(&server);
        let server_task = tokio::spawn(async move { server_clone.accept().await.unwrap() });

        let client_conn = client.connect(server_addr, "p2p-messenger").await.unwrap();
        let _server_conn = server_task.await.unwrap();

        (server, client, client_conn, server_addr)
    }

    #[tokio::test]
    async fn test_new_pool_is_empty() {
        let pool = ConnectionPool::new(100);
        assert!(pool.is_empty().await);
        assert_eq!(pool.len().await, 0);
        println!("✅ Новый пул пустой");
    }

    #[tokio::test]
    async fn test_insert_and_get() {
        let pool = ConnectionPool::new(100);
        let (_server, _client, conn, _addr) = setup_connection_pair().await;

        let key = b"node-123".to_vec();
        pool.insert(key.clone(), conn).await.unwrap();

        assert_eq!(pool.len().await, 1);
        assert!(!pool.is_empty().await);

        let retrieved = pool.get(&key).await;
        assert!(retrieved.is_some());
        println!("✅ Insert и Get работают");
    }

    #[tokio::test]
    async fn test_get_missing_returns_none() {
        let pool = ConnectionPool::new(100);
        let missing = pool.get(b"nonexistent").await;
        assert!(missing.is_none());
        println!("✅ Get несуществующего → None");
    }

    #[tokio::test]
    async fn test_get_or_connect_creates_new() {
        let pool = ConnectionPool::new(100);
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();
        let client = QuicClient::new(any_port()).unwrap();

        // Сервер принимает в фоне
        let server_clone = Arc::clone(&server);
        let _server_task = tokio::spawn(async move {
            let _conn = server_clone.accept().await.unwrap();
        });

        let key = b"target-node".to_vec();
        let conn = pool
            .get_or_connect(key.clone(), server_addr, &client)
            .await
            .unwrap();

        assert_eq!(pool.len().await, 1);
        assert_eq!(conn.remote_address(), server_addr);
        println!("✅ get_or_connect() создаёт новое соединение");
    }

    #[tokio::test]
    async fn test_get_or_connect_reuses_existing() {
        let pool = ConnectionPool::new(100);
        let server = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server.local_address();
        let client = QuicClient::new(any_port()).unwrap();

        let server_clone = Arc::clone(&server);
        let _server_task = tokio::spawn(async move {
            let _conn = server_clone.accept().await.unwrap();
        });

        let key = b"reused-node".to_vec();

        // Первый вызов — создаёт
        let conn1 = pool
            .get_or_connect(key.clone(), server_addr, &client)
            .await
            .unwrap();

        // Второй вызов — переиспользует
        let conn2 = pool
            .get_or_connect(key.clone(), server_addr, &client)
            .await
            .unwrap();

        // Оба Arc должны указывать на одно и то же соединение
        assert!(Arc::ptr_eq(&conn1, &conn2));
        assert_eq!(pool.len().await, 1);
        println!("✅ get_or_connect() переиспользует существующее");
    }

    #[tokio::test]
    async fn test_remove_connection() {
        let pool = ConnectionPool::new(100);
        let (_server, _client, conn, _addr) = setup_connection_pair().await;

        let key = b"to-remove".to_vec();
        pool.insert(key.clone(), conn).await.unwrap();
        assert_eq!(pool.len().await, 1);

        assert!(pool.remove(&key).await);
        assert_eq!(pool.len().await, 0);

        // Второе удаление возвращает false
        assert!(!pool.remove(&key).await);
        println!("✅ Remove работает");
    }

    #[tokio::test]
    async fn test_pool_full_returns_error() {
        let pool = ConnectionPool::new(2); // Очень маленький пул

        let (_s1, _c1, conn1, _a1) = setup_connection_pair().await;
        let (_s2, _c2, conn2, _a2) = setup_connection_pair().await;
        let (_s3, _c3, conn3, _a3) = setup_connection_pair().await;

        pool.insert(b"k1".to_vec(), conn1).await.unwrap();
        pool.insert(b"k2".to_vec(), conn2).await.unwrap();

        // Третий insert должен упасть с ошибкой PoolFull
        let result = pool.insert(b"k3".to_vec(), conn3).await;
        assert!(matches!(
            result,
            Err(ConnectionPoolError::PoolFull { max: 2 })
        ));

        println!("✅ Пул отклоняет соединения сверх лимита");
    }

    #[tokio::test]
    async fn test_shutdown_clears_pool() {
        let pool = ConnectionPool::new(100);
        let (_s, _c, conn, _a) = setup_connection_pair().await;

        pool.insert(b"k1".to_vec(), conn).await.unwrap();
        assert_eq!(pool.len().await, 1);

        pool.shutdown().await;
        assert_eq!(pool.len().await, 0);
        println!("✅ Shutdown очищает пул");
    }

    #[tokio::test]
    async fn test_active_keys() {
        let pool = ConnectionPool::new(100);
        let (_s1, _c1, conn1, _a1) = setup_connection_pair().await;
        let (_s2, _c2, conn2, _a2) = setup_connection_pair().await;

        pool.insert(b"node-A".to_vec(), conn1).await.unwrap();
        pool.insert(b"node-B".to_vec(), conn2).await.unwrap();

        let mut keys = pool.active_keys().await;
        keys.sort();
        assert_eq!(keys, vec![b"node-A".to_vec(), b"node-B".to_vec()]);
        println!("✅ active_keys() возвращает список ключей");
    }

    #[tokio::test]
    async fn test_cleanup_idle_removes_old() {
        // Пул с очень маленьким idle timeout
        let pool = ConnectionPool::with_idle_timeout(100, Duration::from_millis(50));
        let (_s, _c, conn, _a) = setup_connection_pair().await;

        pool.insert(b"key".to_vec(), conn).await.unwrap();
        assert_eq!(pool.len().await, 1);

        // Ждём чтобы соединение стало idle
        tokio::time::sleep(Duration::from_millis(100)).await;

        let removed = pool.cleanup_idle().await;
        assert_eq!(removed, 1);
        assert_eq!(pool.len().await, 0);
        println!("✅ cleanup_idle() удаляет старые соединения");
    }
}
