//! Константы и значения по умолчанию для всего ядра.
//! Seed-узлы, таймауты, лимиты сети.

// ═══════════════════════════════════════════════════════════════════
// ВЕРСИИ
// ═══════════════════════════════════════════════════════════════════

/// Версия протокола обмена сообщениями между узлами.
/// Увеличивается при несовместимых изменениях протокола.
pub const PROTOCOL_VERSION: u8 = 1;

/// Версия приложения.
pub const APP_VERSION: &str = "0.1.0";

/// Версия формата presence и списка знакомых узлов.
///
/// Растёт, когда меняется состав полей. Записи от узлов, отставших больше
/// чем на [PRESENCE_VERSION_TOLERANCE], игнорируются: их представление о том,
/// кто в сети, всё равно устарело и только копит мусор.
pub const PRESENCE_VERSION: u32 = 2;

/// На сколько версий разрешено отставать источнику списка узлов.
pub const PRESENCE_VERSION_TOLERANCE: u32 = 3;

/// Presence считается протухшим, если он старше этого срока.
///
/// Брокер отдаёт последнее объявление каждого узла даже спустя месяцы после
/// того, как телефон исчез. Без проверки возраста список «подключённых» рос
/// призраками давно удалённых установок.
pub const PRESENCE_MAX_AGE_MS: i64 = 10 * 60 * 1000;

// ═══════════════════════════════════════════════════════════════════
// СЕТЕВЫЕ КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Порт по умолчанию для QUIC-соединений.
pub const DEFAULT_PORT: u16 = 7777;

/// Максимальное количество одновременных QUIC-соединений.
pub const MAX_CONNECTIONS: usize = 256;

/// Таймаут установки соединения (в секундах).
pub const CONNECTION_TIMEOUT_SECS: u64 = 10;

/// Таймаут неактивного соединения — закрываем если нет активности (сек).
pub const IDLE_CONNECTION_TIMEOUT_SECS: u64 = 300; // 5 минут

/// Максимальный TTL (число хопов) для пересылаемых пакетов.
/// Защита от зацикливания в сети.
pub const MAX_MESSAGE_TTL: u8 = 10;

/// Максимальный размер одного UDP-пакета (байт).
pub const MAX_PACKET_SIZE: usize = 65_507;

// ═══════════════════════════════════════════════════════════════════
// АДАПТИВНЫЙ POLLING
// ═══════════════════════════════════════════════════════════════════

/// Интервал синхронизации в активном режиме (секунды).
pub const POLLING_ACTIVE_SECS: u64 = 5;

/// Минимальный интервал в фоновом режиме (секунды).
pub const POLLING_BACKGROUND_MIN_SECS: u64 = 10;

/// Максимальный интервал в фоновом режиме (секунды).
pub const POLLING_BACKGROUND_MAX_SECS: u64 = 300; // 5 минут

/// Множитель экспоненциального backoff.
pub const POLLING_BACKOFF_FACTOR: f64 = 2.0;

// ═══════════════════════════════════════════════════════════════════
// ХРАНЕНИЕ СООБЩЕНИЙ (store-and-forward)
// ═══════════════════════════════════════════════════════════════════

/// Максимальное количество сообщений в очереди для одного узла.
pub const MAX_QUEUE_PER_NODE: usize = 1_000;

/// Время жизни сообщения в очереди (секунды). 7 дней.
pub const MESSAGE_QUEUE_TTL_SECS: u64 = 7 * 24 * 60 * 60;

/// Максимальное число попыток повторной отправки.
pub const MAX_RETRY_COUNT: u32 = 10;

// ═══════════════════════════════════════════════════════════════════
// РЕЙТИНГ УЗЛОВ
// ═══════════════════════════════════════════════════════════════════

/// Порог рейтинга для Tier 1 (Super Node).
pub const TIER1_RATING_THRESHOLD: f32 = 90.0;

/// Порог рейтинга для Tier 2 (Relay Node).
pub const TIER2_RATING_THRESHOLD: f32 = 50.0;

/// Веса для формулы расчёта рейтинга узла.
pub const RATING_WEIGHT_UPTIME: f32 = 0.30;
pub const RATING_WEIGHT_BANDWIDTH: f32 = 0.25;
pub const RATING_WEIGHT_LATENCY: f32 = 0.20;
pub const RATING_WEIGHT_SUCCESS_RATE: f32 = 0.15;
pub const RATING_WEIGHT_IS_SEED: f32 = 0.10;

/// Начальный рейтинг нового узла (должен заработать доверие).
pub const INITIAL_NODE_RATING: f32 = 0.0;

/// Через сколько дней без активности узел считается устаревшим.
pub const NODE_EXPIRY_DAYS: u64 = 30;

// ═══════════════════════════════════════════════════════════════════
// GOSSIP PROTOCOL
// ═══════════════════════════════════════════════════════════════════

/// TTL для Gossip-сообщений (число хопов пересылки).
pub const GOSSIP_TTL: u8 = 3;

/// Время жизни кэша виденных MessageID (секунды).
/// Защита от зацикливания Gossip-сообщений.
pub const GOSSIP_CACHE_TTL_SECS: u64 = 60;

// ═══════════════════════════════════════════════════════════════════
// STUN СЕРВЕРЫ (для NAT Traversal)
// ═══════════════════════════════════════════════════════════════════

/// Список публичных STUN-серверов для определения внешнего IP.
/// Используются по очереди при недоступности предыдущего.
pub const STUN_SERVERS: &[&str] = &[
    "stun.l.google.com:19302",
    "stun1.l.google.com:19302",
    "stun2.l.google.com:19302",
    "stun.cloudflare.com:3478",
    "stun.nextcloud.com:443",
];

// ═══════════════════════════════════════════════════════════════════
// SEED УЗЛЫ
// ═══════════════════════════════════════════════════════════════════

/// Структура описывающая один seed-узел.
/// Seed-узлы предустановлены в приложении для первичного входа в сеть.
/// Хранятся ТОЛЬКО во внутренней БД — НЕ в телефонной книге Android.
#[derive(Debug, Clone)]
pub struct SeedNode {
    /// Уникальный идентификатор узла (SHA-256 от публичного ключа Ed25519)
    pub node_id: &'static str,
    /// Сетевой адрес в формате "IP:PORT" или "domain:PORT"
    pub address: &'static str,
    /// Публичный ключ Ed25519 в hex-формате (для верификации подписи)
    pub ed25519_pubkey_hex: &'static str,
    /// Географический регион (для информации и балансировки)
    pub region: &'static str,
}

/// Предустановленный список seed-узлов.
///
/// ВАЖНО: Это заглушки для разработки.
/// Перед продакшн-релизом заменить на реальные узлы с реальными ключами.
///
/// Каждый узел верифицируется по подписи Ed25519 при подключении.
/// Новые узлы начинают с рейтинга 0 и не могут быть seed-узлами
/// без подписи создателя (защита от Sybil-атак).
pub const SEED_NODES: &[SeedNode] = &[
    SeedNode {
        node_id: "seed-node-001-placeholder",
        address: "127.0.0.1:7777",
        ed25519_pubkey_hex: "0000000000000000000000000000000000000000000000000000000000000000",
        region: "localhost-dev",
    },
    // TODO: Добавить реальные seed-узлы перед продакшн-релизом:
    // SeedNode {
    //     node_id:            "реальный-node-id",
    //     address:            "node1.example.com:7777",
    //     ed25519_pubkey_hex: "реальный-публичный-ключ-hex",
    //     region:             "EU-West",
    // },
];

/// Количество seed-узлов.
pub const SEED_NODES_COUNT: usize = SEED_NODES.len();

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_protocol_version_is_nonzero() {
        assert!(PROTOCOL_VERSION > 0);
        println!("✅ Версия протокола: {}", PROTOCOL_VERSION);
    }

    #[test]
    fn test_tier_thresholds_are_consistent() {
        // Tier 1 должен быть выше Tier 2
        assert!(TIER1_RATING_THRESHOLD > TIER2_RATING_THRESHOLD);
        println!(
            "✅ Пороги Tier корректны: Tier1={}, Tier2={}",
            TIER1_RATING_THRESHOLD, TIER2_RATING_THRESHOLD
        );
    }

    #[test]
    fn test_rating_weights_sum_to_one() {
        let sum = RATING_WEIGHT_UPTIME
            + RATING_WEIGHT_BANDWIDTH
            + RATING_WEIGHT_LATENCY
            + RATING_WEIGHT_SUCCESS_RATE
            + RATING_WEIGHT_IS_SEED;
        // Допускаем погрешность float
        assert!(
            (sum - 1.0_f32).abs() < 0.001,
            "Сумма весов должна быть 1.0, получено: {}",
            sum
        );
        println!("✅ Сумма весов рейтинга: {:.3}", sum);
    }

    #[test]
    fn test_seed_nodes_not_empty() {
        assert!(SEED_NODES_COUNT > 0);
        println!("✅ Seed-узлов загружено: {}", SEED_NODES_COUNT);
    }

    #[test]
    fn test_polling_intervals_are_consistent() {
        assert!(POLLING_ACTIVE_SECS < POLLING_BACKGROUND_MIN_SECS);
        assert!(POLLING_BACKGROUND_MIN_SECS < POLLING_BACKGROUND_MAX_SECS);
        println!(
            "✅ Интервалы polling корректны: активный={}с, фон={}с-{}с",
            POLLING_ACTIVE_SECS, POLLING_BACKGROUND_MIN_SECS, POLLING_BACKGROUND_MAX_SECS
        );
    }

    /// Правило отбраковки по версии: отставание на три и больше версий -
    /// сведения не берём. Ровно та же арифметика, что и в обработчике presence.
    fn presence_version_accepted(peer_version: u32) -> bool {
        peer_version + PRESENCE_VERSION_TOLERANCE > PRESENCE_VERSION
    }

    #[test]
    fn test_presence_version_filter() {
        assert!(presence_version_accepted(PRESENCE_VERSION));
        assert!(presence_version_accepted(1));
        assert!(!presence_version_accepted(0));
        assert!(!presence_version_accepted(
            PRESENCE_VERSION.saturating_sub(PRESENCE_VERSION_TOLERANCE)
        ));
        println!("✅ Фильтр версий presence работает");
    }

    #[test]
    fn test_presence_max_age_is_a_few_minutes() {
        assert!(PRESENCE_MAX_AGE_MS >= 60_000);
        assert!(PRESENCE_MAX_AGE_MS <= 60 * 60 * 1000);
        println!("✅ Срок годности presence: {} мин", PRESENCE_MAX_AGE_MS / 60_000);
    }
}
