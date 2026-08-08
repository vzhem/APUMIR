//! # Kademlia DHT — Distributed Hash Table
//!
//! Реализация Kademlia для поиска узлов в P2P-сети.
//!
//! ## Ключевые концепции:
//!
//! - **NodeID** — 256-битный (32 байта) идентификатор узла
//! - **XOR-метрика** — расстояние между двумя NodeID = xor их байтов
//!   (например: 0x00 XOR 0x01 = 0x01 → расстояние 1)
//! - **k-bucket** — список узлов, чей ID отличается от нашего на N бит
//!   Всего 256 бакетов (по одному на каждый бит), в каждом до K узлов
//! - **K = 20** — стандартный параметр Kademlia
//!
//! ## Что делает этот модуль:
//!
//! - Хранит routing table (256 k-buckets)
//! - Добавляет/удаляет узлы с учётом XOR-расстояния
//! - Ищет K ближайших узлов к любому NodeID
//! - Автоматически ограничивает размер каждого бакета
//!
//! ## Что НЕ делает (пока):
//!
//! - **Не отправляет** сетевые запросы (это будет в `router.rs`)
//! - **Не хранит** данные (это pure discovery, не хранилище)
//! - **Не проверяет** живость узлов (это будет в `presence.rs`)

use std::collections::VecDeque;

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Размер NodeID в байтах (256 бит).
pub const NODE_ID_LEN: usize = 32;

/// Количество бит в NodeID.
pub const ID_BITS: usize = NODE_ID_LEN * 8;

/// K — максимальное число узлов в одном k-bucket.
/// Стандарт Kademlia: K = 20.
pub const K: usize = 20;

// ═══════════════════════════════════════════════════════════════════
// ТИПЫ
// ═══════════════════════════════════════════════════════════════════

/// NodeID = 32 байта.
///
/// Для использования в DHT принимает любой массив [u8; 32].
/// В реальности это будет SHA-256 от Ed25519 publickey узла.
pub type NodeIdBytes = [u8; NODE_ID_LEN];

/// Информация об узле в DHT.
///
/// Минимальный набор данных для routing table.
/// Расширенная информация (Tier, рейтинг, ключи) в `protocol::messages::NodeInfo`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DhtNodeInfo {
    pub node_id: NodeIdBytes,
    pub address: String, // "IP:PORT" или пустая строка если только через relay
}

// ═══════════════════════════════════════════════════════════════════
// XOR РАССТОЯНИЕ
// ═══════════════════════════════════════════════════════════════════

/// XOR-расстояние между двумя NodeID.
/// Результат — 32 байта, представляющие "расстояние" в метрике Kademlia.
pub fn xor_distance(a: &NodeIdBytes, b: &NodeIdBytes) -> NodeIdBytes {
    let mut result = [0u8; NODE_ID_LEN];
    for i in 0..NODE_ID_LEN {
        result[i] = a[i] ^ b[i];
    }
    result
}

/// Индекс k-bucket для узла с указанным NodeID.
///
/// Возвращает позицию первого отличающегося бита между `our_id` и `their_id`.
/// Например если ID совпадают в первых 100 битах — bucket_index = 100.
///
/// Возвращает `None` если ID одинаковые (это мы сами).
pub fn bucket_index(our_id: &NodeIdBytes, their_id: &NodeIdBytes) -> Option<usize> {
    let distance = xor_distance(our_id, their_id);

    // Находим первый ненулевой байт
    for (byte_idx, &byte) in distance.iter().enumerate() {
        if byte != 0 {
            // Находим позицию старшего установленного бита в этом байте
            let leading_zeros = byte.leading_zeros() as usize;
            // Общий индекс bucket = позиция первого бита где они различаются
            let bit_position = byte_idx * 8 + leading_zeros;
            // Bucket = ID_BITS - 1 - bit_position (наиболее удалённые в bucket 255)
            return Some(ID_BITS - 1 - bit_position);
        }
    }

    // Все байты нулевые → ID одинаковые
    None
}

/// Сравнение XOR-расстояний.
/// Возвращает `Ordering`: Less если a ближе к target чем b.
pub fn compare_distance(
    target: &NodeIdBytes,
    a: &NodeIdBytes,
    b: &NodeIdBytes,
) -> std::cmp::Ordering {
    let dist_a = xor_distance(target, a);
    let dist_b = xor_distance(target, b);
    dist_a.cmp(&dist_b)
}

// ═══════════════════════════════════════════════════════════════════
// K-BUCKET
// ═══════════════════════════════════════════════════════════════════

/// Один k-bucket — список до K узлов с одинаковым расстоянием от нашего ID
/// (в терминах старшего различающегося бита).
///
/// LRU-политика: недавно виденные узлы в конце, давние — в начале.
/// При переполнении удаляется самый давний.
#[derive(Debug, Clone)]
pub struct Bucket {
    nodes: VecDeque<DhtNodeInfo>,
    capacity: usize,
}

impl Bucket {
    pub fn new(capacity: usize) -> Self {
        Bucket {
            nodes: VecDeque::with_capacity(capacity),
            capacity,
        }
    }

    /// Добавить или обновить узел в bucket.
    ///
    /// - Если узел уже есть → перемещаем в конец (недавно виден)
    /// - Если новый и есть место → добавляем в конец
    /// - Если новый и bucket полный → возвращаем false (нужно проверить старейший)
    pub fn add_or_update(&mut self, node: DhtNodeInfo) -> bool {
        // Ищем существующий по node_id
        if let Some(pos) = self.nodes.iter().position(|n| n.node_id == node.node_id) {
            // Уже есть — удаляем из старой позиции и добавляем в конец
            self.nodes.remove(pos);
            self.nodes.push_back(node);
            return true;
        }

        // Нового узла в bucket нет
        if self.nodes.len() < self.capacity {
            self.nodes.push_back(node);
            true
        } else {
            // Bucket полный — не добавляем
            // В реальном Kademlia здесь пингуют старейший узел
            // и если он жив — новый отбрасывается, иначе заменяется.
            // Для MVP: просто отказываем в добавлении.
            false
        }
    }

    /// Удалить узел по ID.
    pub fn remove(&mut self, node_id: &NodeIdBytes) -> bool {
        if let Some(pos) = self.nodes.iter().position(|n| &n.node_id == node_id) {
            self.nodes.remove(pos);
            true
        } else {
            false
        }
    }

    /// Все узлы bucket.
    pub fn nodes(&self) -> Vec<DhtNodeInfo> {
        self.nodes.iter().cloned().collect()
    }

    /// Число узлов в bucket.
    pub fn len(&self) -> usize {
        self.nodes.len()
    }

    /// Пустой ли bucket.
    pub fn is_empty(&self) -> bool {
        self.nodes.is_empty()
    }

    /// Заполнен ли до предела.
    pub fn is_full(&self) -> bool {
        self.nodes.len() >= self.capacity
    }
}

// ═══════════════════════════════════════════════════════════════════
// ROUTING TABLE
// ═══════════════════════════════════════════════════════════════════

/// Routing table — 256 k-buckets, по одному на каждый возможный
/// индекс различающегося бита.
pub struct RoutingTable {
    our_id: NodeIdBytes,
    buckets: Vec<Bucket>,
}

impl RoutingTable {
    /// Создать пустую routing table.
    pub fn new(our_id: NodeIdBytes) -> Self {
        let buckets = (0..ID_BITS).map(|_| Bucket::new(K)).collect();
        RoutingTable { our_id, buckets }
    }

    /// Наш собственный NodeID.
    pub fn our_id(&self) -> &NodeIdBytes {
        &self.our_id
    }

    /// Добавить или обновить узел в таблице.
    /// Возвращает `false` если это мы сами или bucket переполнен.
    pub fn add_or_update(&mut self, node: DhtNodeInfo) -> bool {
        let Some(bucket_idx) = bucket_index(&self.our_id, &node.node_id) else {
            return false; // Это мы сами
        };
        self.buckets[bucket_idx].add_or_update(node)
    }

    /// Удалить узел из таблицы.
    pub fn remove(&mut self, node_id: &NodeIdBytes) -> bool {
        let Some(bucket_idx) = bucket_index(&self.our_id, node_id) else {
            return false;
        };
        self.buckets[bucket_idx].remove(node_id)
    }

    /// Найти K ближайших к `target` узлов из всех бакетов.
    ///
    /// Возвращает не более `max_results` узлов, отсортированных по
    /// возрастанию XOR-расстояния до `target`.
    pub fn find_closest(&self, target: &NodeIdBytes, max_results: usize) -> Vec<DhtNodeInfo> {
        // Собираем ВСЕ узлы из всех бакетов
        let mut all_nodes: Vec<DhtNodeInfo> = self.buckets.iter().flat_map(|b| b.nodes()).collect();

        // Сортируем по расстоянию к target
        all_nodes.sort_by(|a, b| compare_distance(target, &a.node_id, &b.node_id));

        // Обрезаем до max_results
        all_nodes.truncate(max_results);
        all_nodes
    }

    /// Общее число узлов во всех бакетах.
    pub fn total_nodes(&self) -> usize {
        self.buckets.iter().map(|b| b.len()).sum()
    }

    /// Содержит ли узел с указанным ID.
    pub fn contains(&self, node_id: &NodeIdBytes) -> bool {
        let Some(bucket_idx) = bucket_index(&self.our_id, node_id) else {
            return false;
        };
        self.buckets[bucket_idx]
            .nodes()
            .iter()
            .any(|n| &n.node_id == node_id)
    }

    /// Получить bucket по индексу (для диагностики).
    pub fn get_bucket(&self, index: usize) -> Option<&Bucket> {
        self.buckets.get(index)
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    /// Хелпер: создать NodeID где все байты одинаковые.
    fn id_all(byte: u8) -> NodeIdBytes {
        [byte; NODE_ID_LEN]
    }

    /// Хелпер: создать NodeID с указанным первым байтом, остальные нули.
    fn id_first_byte(byte: u8) -> NodeIdBytes {
        let mut id = [0u8; NODE_ID_LEN];
        id[0] = byte;
        id
    }

    // ── XOR метрика ────────────────────────────────────────────────

    #[test]
    fn test_xor_distance_same_id() {
        let a = id_all(0x42);
        let dist = xor_distance(&a, &a);
        assert_eq!(dist, [0u8; NODE_ID_LEN]);
        println!("✅ XOR одинаковых ID = 0");
    }

    #[test]
    fn test_xor_distance_different_ids() {
        let a = id_first_byte(0x00);
        let b = id_first_byte(0xFF);
        let dist = xor_distance(&a, &b);
        assert_eq!(dist[0], 0xFF);
        for i in 1..NODE_ID_LEN {
            assert_eq!(dist[i], 0);
        }
        println!("✅ XOR разных ID даёт правильный результат");
    }

    #[test]
    fn test_xor_distance_symmetric() {
        let a = id_first_byte(0xA5);
        let b = id_first_byte(0x5A);
        assert_eq!(xor_distance(&a, &b), xor_distance(&b, &a));
        println!("✅ XOR симметричен");
    }

    // ── Bucket index ───────────────────────────────────────────────

    #[test]
    fn test_bucket_index_same_id_returns_none() {
        let a = id_all(0x42);
        assert_eq!(bucket_index(&a, &a), None);
        println!("✅ bucket_index для одинаковых ID = None");
    }

    #[test]
    fn test_bucket_index_different_first_bit() {
        // 0x80 = 10000000 → отличается в самом старшем бите → bucket 255
        let a = id_first_byte(0x00);
        let b = id_first_byte(0x80);
        assert_eq!(bucket_index(&a, &b), Some(255));
        println!("✅ Разница в старшем бите → bucket 255");
    }

    #[test]
    fn test_bucket_index_different_last_bit_of_first_byte() {
        // 0x01 = 00000001 → отличается в младшем бите первого байта → bucket 248
        let a = id_first_byte(0x00);
        let b = id_first_byte(0x01);
        assert_eq!(bucket_index(&a, &b), Some(248));
        println!("✅ Разница в младшем бите первого байта → bucket 248");
    }

    #[test]
    fn test_compare_distance() {
        let target = id_first_byte(0x00);
        let a = id_first_byte(0x01); // Расстояние 1
        let b = id_first_byte(0x08); // Расстояние 8

        assert_eq!(compare_distance(&target, &a, &b), std::cmp::Ordering::Less);
        assert_eq!(
            compare_distance(&target, &b, &a),
            std::cmp::Ordering::Greater
        );
        println!("✅ compare_distance работает корректно");
    }

    // ── Bucket ─────────────────────────────────────────────────────

    #[test]
    fn test_bucket_empty() {
        let bucket = Bucket::new(K);
        assert!(bucket.is_empty());
        assert_eq!(bucket.len(), 0);
        println!("✅ Новый bucket пустой");
    }

    #[test]
    fn test_bucket_add_and_update() {
        let mut bucket = Bucket::new(K);

        let node = DhtNodeInfo {
            node_id: id_all(0x01),
            address: "1.1.1.1:7777".to_string(),
        };

        assert!(bucket.add_or_update(node.clone()));
        assert_eq!(bucket.len(), 1);

        // Обновление того же узла — не добавляет новый
        assert!(bucket.add_or_update(node.clone()));
        assert_eq!(bucket.len(), 1);
        println!("✅ Bucket: add и update работают");
    }

    #[test]
    fn test_bucket_full() {
        let mut bucket = Bucket::new(2);

        let node1 = DhtNodeInfo {
            node_id: id_all(0x01),
            address: "1.1.1.1:7777".to_string(),
        };
        let node2 = DhtNodeInfo {
            node_id: id_all(0x02),
            address: "2.2.2.2:7777".to_string(),
        };
        let node3 = DhtNodeInfo {
            node_id: id_all(0x03),
            address: "3.3.3.3:7777".to_string(),
        };

        assert!(bucket.add_or_update(node1));
        assert!(bucket.add_or_update(node2));

        // Третий не помещается
        assert!(!bucket.add_or_update(node3));
        assert_eq!(bucket.len(), 2);
        assert!(bucket.is_full());
        println!("✅ Bucket отказывает при переполнении");
    }

    #[test]
    fn test_bucket_remove() {
        let mut bucket = Bucket::new(K);
        let node_id = id_all(0x42);
        let node = DhtNodeInfo {
            node_id,
            address: "1.1.1.1:7777".to_string(),
        };

        bucket.add_or_update(node);
        assert_eq!(bucket.len(), 1);

        assert!(bucket.remove(&node_id));
        assert_eq!(bucket.len(), 0);

        // Повторное удаление — false
        assert!(!bucket.remove(&node_id));
        println!("✅ Bucket: remove работает");
    }

    // ── Routing Table ──────────────────────────────────────────────

    #[test]
    fn test_routing_table_empty() {
        let table = RoutingTable::new(id_all(0x00));
        assert_eq!(table.total_nodes(), 0);
        println!("✅ Новая routing table пустая");
    }

    #[test]
    fn test_routing_table_add_and_find() {
        let our_id = id_all(0x00);
        let mut table = RoutingTable::new(our_id);

        // Добавляем 5 узлов с разными ID
        for i in 1..=5u8 {
            let node = DhtNodeInfo {
                node_id: id_first_byte(i),
                address: format!("1.1.1.{}:7777", i),
            };
            assert!(table.add_or_update(node));
        }

        assert_eq!(table.total_nodes(), 5);

        // Ищем ближайшие к нам
        let closest = table.find_closest(&our_id, 3);
        assert_eq!(closest.len(), 3);

        // Первый должен быть с наименьшим расстоянием (0x01)
        assert_eq!(closest[0].node_id[0], 0x01);
        println!("✅ Routing table: add и find_closest работают");
    }

    #[test]
    fn test_routing_table_ignores_self() {
        let our_id = id_all(0x42);
        let mut table = RoutingTable::new(our_id);

        let self_node = DhtNodeInfo {
            node_id: our_id,
            address: "127.0.0.1:7777".to_string(),
        };

        // Попытка добавить самого себя → false
        assert!(!table.add_or_update(self_node));
        assert_eq!(table.total_nodes(), 0);
        println!("✅ Routing table игнорирует самого себя");
    }

    #[test]
    fn test_routing_table_contains() {
        let our_id = id_all(0x00);
        let mut table = RoutingTable::new(our_id);

        let node_id = id_first_byte(0x42);
        let node = DhtNodeInfo {
            node_id,
            address: "1.1.1.1:7777".to_string(),
        };

        assert!(!table.contains(&node_id));
        table.add_or_update(node);
        assert!(table.contains(&node_id));
        println!("✅ Routing table: contains работает");
    }

    #[test]
    fn test_routing_table_remove() {
        let our_id = id_all(0x00);
        let mut table = RoutingTable::new(our_id);

        let node_id = id_first_byte(0x42);
        let node = DhtNodeInfo {
            node_id,
            address: "1.1.1.1:7777".to_string(),
        };

        table.add_or_update(node);
        assert_eq!(table.total_nodes(), 1);

        assert!(table.remove(&node_id));
        assert_eq!(table.total_nodes(), 0);
        println!("✅ Routing table: remove работает");
    }

    #[test]
    fn test_find_closest_returns_sorted() {
        let our_id = id_all(0x00);
        let mut table = RoutingTable::new(our_id);

        // Добавляем узлы в случайном порядке
        let ids = vec![0x10, 0x03, 0x50, 0x01, 0x20];
        for &b in &ids {
            table.add_or_update(DhtNodeInfo {
                node_id: id_first_byte(b),
                address: format!("addr-{}", b),
            });
        }

        let target = id_first_byte(0x00);
        let closest = table.find_closest(&target, 5);

        // Проверяем что они отсортированы по возрастанию первого байта
        // (для нашего простого случая это соответствует XOR расстоянию)
        let first_bytes: Vec<u8> = closest.iter().map(|n| n.node_id[0]).collect();
        let mut expected = ids.clone();
        expected.sort();

        assert_eq!(first_bytes, expected);
        println!("✅ find_closest возвращает отсортированный по расстоянию список");
    }

    #[test]
    fn test_find_closest_respects_max_results() {
        let our_id = id_all(0x00);
        let mut table = RoutingTable::new(our_id);

        // Добавляем 10 узлов
        for i in 1..=10u8 {
            table.add_or_update(DhtNodeInfo {
                node_id: id_first_byte(i),
                address: format!("addr-{}", i),
            });
        }

        // Запрашиваем только 3
        let closest = table.find_closest(&our_id, 3);
        assert_eq!(closest.len(), 3);

        // Запрашиваем 100 — но узлов только 10
        let all = table.find_closest(&our_id, 100);
        assert_eq!(all.len(), 10);
        println!("✅ find_closest уважает max_results");
    }
}
