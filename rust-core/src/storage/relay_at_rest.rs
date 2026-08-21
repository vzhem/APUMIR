//! # Relay At-Rest — шифрование хранимых relay-записей (M8-C, slice 1)
//!
//! Граница модели: **версионируемый AEAD-конверт** для байт, которые durable-слой
//! ([`crate::storage::relay_store`]) собирается хранить на диске. Этот модуль — чистая
//! криптографическая модель без I/O: он НЕ пишет в SQLite и НЕ управляет ключами на
//! устройстве. Подключение к `RelayStore` (миграция схемы v2) и Android Keystore
//! (жизненный цикл ключа) — отдельные следующие slice-ы M8-C.
//!
//! ## Правила безопасности (обязательные)
//!
//! - **Никакого plaintext-fallback.** Если расшифровка невозможна — типизированная
//!   ошибка, а не «вернуть как есть». Caller обязан отправлять такую запись в
//!   quarantine (не загружать, не показывать в UI, не panic).
//! - **Ни один путь не panic.** Вся разборка длин — только проверенными срезами.
//! - **Уникальный случайный nonce** на каждое шифрование. Используется
//!   XChaCha20-Poly1305: 192-битный nonce делает случайный выбор безопасным для
//!   долгоживущего storage-ключа (в отличие от 96-битных nonce, которых в
//!   `crypto/cipher.rs` достаточно для короткоживущих E2E-сессий).
//! - **AAD привязывает конверт к записи**: `msg_id`, `recipient` и абсолютный
//!   `expires_at_ms` входят в AAD, поэтому подмена открытых колонок SQLite
//!   (получателя или дедлайна) ломает аутентификацию. Поля `msg_id`/`recipient`
//!   по wire-инварианту не содержат `|` (см. `valid_metadata_atom`), поэтому
//!   разбор AAD однозначен.
//!
//! ## Планировка конверта (binary, versioned)
//!
//! ```text
//! [0]      version: u8          (= AT_REST_ENVELOPE_VERSION)
//! [1..3]   key_id: u16 LE       (идентификатор ключа из RelayAtRestKeySource)
//! [3..27]  nonce: 24 байта      (XChaCha20, случайный на каждое шифрование)
//! [27..]   ciphertext || 16-байтовый Poly1305 tag
//! ```
//!
//! Заголовок открыт (он и есть метаданные конверта); его целостность защищена
//! тегом AEAD. Неизвестная версия или неизвестный `key_id` — это **quarantine**,
//! а не попытка «угадать» (см. [`AtRestError`]).
//!
//! Ключевая иерархия: symmetric key материал поставляет [`RelayAtRestKeySource`].
//! На Android обёрткой служит мост к Android Keystore (M8-C slice 3): Keystore
//! хранит не-извлекаемый wrap-ключ; случайный 32-байтный master secret генерируется
//! один раз (Kotlin, SecureRandom), wrap-ится Keystore-ключом, wrapped blob хранится
//! в app-private SharedPreferences, при старте unwrap-ится и передаётся в Rust через
//! UniFFI (`install_device_key_source`). App update переживает Keystore-ключ →
//! `key_id` стабилен; data clear теряет ключ → старые записи честно уходят в
//! quarantine (`UnknownKeyId`), без маскировки потери local custody.
//!
//! ## Slice 3 добавления
//!
//! - [`MasterSecretKeySource`] — боевой источник из предоставленного хостом
//!   материала (master secret). Хранит ОДИН текущий ключ; ротация — через смену
//!   `key_id` при повторной установке (действует на следующий запуск движка).
//! - Глобальный реестр [`install_device_key_source`]/[`installed_key_source`]/
//!   [`clear_device_key_source`]: точка, куда UniFFI-слой передаёт unwrap-нутый
//!   материал ДО старта движка. Движок читает снимок в момент открытия хранилища.
//! - [`ephemeral_key_source`] — эфемерный RAM-only источник (случайный ключ,
//!   живёт до конца процесса): честный degrade при недоступном Keystore.
//! - [`wipe_bytes`]: гарантированное зануление (volatile-запись + black_box
//!   барьер против оптимизатора). `Drop` ключа и источника зануляет материал.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use rand::rngs::OsRng;
use rand::RngCore;

use crate::network::relay_queue::MAX_MESH_RELAY_ENVELOPE_BYTES;

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Текущая (и единственная) версия формата конверта.
pub const AT_REST_ENVELOPE_VERSION: u8 = 1;

/// Размер симметричного ключа конверта (256 бит).
pub const AT_REST_KEY_BYTES: usize = 32;

/// Размер nonce XChaCha20-Poly1305 (192 бита — безопасен для случайного выбора).
pub const AT_REST_NONCE_BYTES: usize = 24;

/// Размер тега аутентификации Poly1305.
pub const AT_REST_TAG_BYTES: usize = 16;

/// Размер открытого заголовка конверта: version(1) + key_id(2) + nonce(24).
pub const AT_REST_HEADER_BYTES: usize = 1 + 2 + AT_REST_NONCE_BYTES;

/// Максимальный размер plaintext одной записи. Сериализованная чувствительная
/// часть relay-записи (origin/chat_scope/created/hop + e2e payload) ограничена
/// тем же mesh-envelope bound плюс bounded запас на кадрирование.
pub const MAX_AT_REST_RECORD_BYTES: usize = MAX_MESH_RELAY_ENVELOPE_BYTES + 4 * 1024;

/// Префикс домена для AAD (domain separation): чтобы байты AAD этого модуля
/// невозможно было перепутать с любым другим AEAD-контекстом приложения.
const AAD_DOMAIN: &str = "apu-relay-at-rest-v1";

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ (→ quarantine у caller; никогда не panic и не fallback)
// ═══════════════════════════════════════════════════════════════════

/// Ошибки конверта at-rest. Любая из них означает: запись НЕ загружать и НЕ
/// показывать в UI (quarantine). Никогда не возвращать «как есть» plaintext.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum AtRestError {
    /// Версия конверта не поддерживается этой сборкой (например, запись создана
    /// более новой версией приложения). Quarantine, не расшифровывать угадыванием.
    #[error("at-rest envelope: неподдерживаемая версия {found}")]
    UnsupportedVersion { found: u8 },

    /// `key_id` из заголовка неизвестен источнику ключей (ключ сменён/удалён,
    /// data clear, другой Keystore). Quarantine.
    #[error("at-rest envelope: неизвестный key_id {key_id}")]
    UnknownKeyId { key_id: u16 },

    /// Источник ключей недоступен (Keystore ошибка). Честный сбой, не fallback.
    #[error("at-rest envelope: источник ключей недоступен")]
    KeySourceUnavailable,

    /// Конверт битый структурно: короче минимального размера или длиннее
    /// максимально допустимого. Quarantine.
    #[error("at-rest envelope: битая структура ({reason})")]
    MalformedEnvelope { reason: &'static str },

    /// Слишком большой plaintext для одной записи.
    #[error("at-rest envelope: plaintext больше {max} байт")]
    PlaintextTooLarge { max: usize },

    /// AEAD-аутентификация не прошла: битый ciphertext/tag, неверный ключ или
    /// неверный AAD (криптографически неразличимы — так и задумано). Quarantine.
    #[error("at-rest envelope: расшифровка/аутентификация не удалась")]
    DecryptionFailed,

    /// Хост передал материал ключа неверной длины (не 32 байта). Ключ в этом
    /// случае НЕ устанавливается; caller обязан честно деградировать (RAM-only),
    /// а не «подрезать»/дополнять чужие байты.
    #[error("at-rest key material: ожидалось {expected} байт, получено {found}")]
    InvalidKeyMaterial { expected: usize, found: usize },
}

// ═══════════════════════════════════════════════════════════════════
// КЛЮЧ
// ═══════════════════════════════════════════════════════════════════

/// Симметричный ключ at-rest конверта + его идентификатор.
///
/// `key_id` открыто пишется в заголовок конверта, чтобы после смены ключа
/// (rotation / app update) старые записи могли найти свой ключ, а записи с
/// неизвестным ключом детерминированно уходили в quarantine.
///
/// Debug намеренно НЕ печатает материал ключа.
#[derive(Clone, PartialEq, Eq)]
pub struct RelayAtRestKey {
    pub key_id: u16,
    pub material: [u8; AT_REST_KEY_BYTES],
}

impl std::fmt::Debug for RelayAtRestKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RelayAtRestKey")
            .field("key_id", &self.key_id)
            .field("material", &"[redacted]")
            .finish()
    }
}

/// Зануление буфера, которое оптимизатор не выбросит: volatile-запись каждого
/// байта + `black_box` барьер. Используется для ключевого материала в `Drop`.
/// Без новых зависимостей (zeroize не входит в дерево crate по умолчанию).
pub fn wipe_bytes(buf: &mut [u8]) {
    for b in buf.iter_mut() {
        // SAFETY: `b` — валидная ссылка на байт живого буфера; volatile-запись
        // нужна только чтобы компилятор не удалил зануление как dead store.
        unsafe { std::ptr::write_volatile(b, 0) };
    }
    std::hint::black_box(buf);
}

impl Drop for RelayAtRestKey {
    fn drop(&mut self) {
        wipe_bytes(&mut self.material);
    }
}

/// Источник ключей конверта. На Android реализуется [`MasterSecretKeySource`],
/// материал которому поставляет Keystore-мост (не-извлекаемый device-bound
/// wrap-ключ в Keystore + master secret в RAM движка). Важно для app update
/// migration: Keystore переживает обновление APK, поэтому старые записи остаются
/// расшифровываемыми тем же `key_id`; data clear ключ стирает — записи после
/// этого законно не расшифровываются (честная потеря local custody, без
/// маскировки).
///
/// `Send + Sync`: источник разделяется между потоками движка (MQTT transport
/// живёт в отдельном потоке) через `Arc<dyn RelayAtRestKeySource>`.
pub trait RelayAtRestKeySource: Send + Sync {
    /// Ключ для НОВЫХ шифрований (текущий).
    fn current_key(&self) -> Result<RelayAtRestKey, AtRestError>;

    /// Ключ для расшифровки существующего конверта по `key_id` из заголовка.
    fn key_by_id(&self, key_id: u16) -> Result<RelayAtRestKey, AtRestError>;
}

// ═══════════════════════════════════════════════════════════════════
// MASTER SECRET KEY SOURCE (M8-C slice 3: Android Keystore bridge)
// ═══════════════════════════════════════════════════════════════════

/// Боевой источник ключей из master secret, предоставленного хост-приложением.
///
/// Жизненный цикл (Android): Keystore держит не-извлекаемый AES/GCM wrap-ключ →
/// Kotlin unwrap-ит persisted master secret при старте сервиса → передаёт байты
/// сюда через UniFFI ДО `engine.start()`. После передачи Kotlin зануляет свою
/// копию; эта сторона зануляет свою в `Drop`. Материал никогда не пишется на
/// диск в открытом виде и не логируется (`Debug` redacted).
pub struct MasterSecretKeySource {
    key: RelayAtRestKey,
}

impl PartialEq for MasterSecretKeySource {
    fn eq(&self, other: &Self) -> bool {
        self.key == other.key
    }
}

impl Eq for MasterSecretKeySource {}

impl MasterSecretKeySource {
    pub fn new(key_id: u16, material: [u8; AT_REST_KEY_BYTES]) -> Self {
        Self {
            key: RelayAtRestKey { key_id, material },
        }
    }

    /// Из среза байт (граница UniFFI: Kotlin передаёт `ByteArray`). Длина
    /// строго 32 байта — иначе отказ без установки (никакого «подрезания»).
    pub fn from_shared_slice(key_id: u16, material: &[u8]) -> Result<Self, AtRestError> {
        if material.len() != AT_REST_KEY_BYTES {
            return Err(AtRestError::InvalidKeyMaterial {
                expected: AT_REST_KEY_BYTES,
                found: material.len(),
            });
        }
        let mut bytes = [0u8; AT_REST_KEY_BYTES];
        bytes.copy_from_slice(material);
        Ok(Self::new(key_id, bytes))
    }

    pub fn key_id(&self) -> u16 {
        self.key.key_id
    }
}

impl std::fmt::Debug for MasterSecretKeySource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MasterSecretKeySource")
            .field("key_id", &self.key.key_id)
            .field("material", &"[redacted]")
            .finish()
    }
}

impl RelayAtRestKeySource for MasterSecretKeySource {
    fn current_key(&self) -> Result<RelayAtRestKey, AtRestError> {
        Ok(self.key.clone())
    }

    fn key_by_id(&self, key_id: u16) -> Result<RelayAtRestKey, AtRestError> {
        if key_id == self.key.key_id {
            Ok(self.key.clone())
        } else {
            Err(AtRestError::UnknownKeyId { key_id })
        }
    }
}

impl Drop for MasterSecretKeySource {
    fn drop(&mut self) {
        wipe_bytes(&mut self.key.material);
    }
}

/// `key_id` эфемерного RAM-only источника. Записи, зашифрованные эфемерным
/// ключом, не переживают процесс, поэтому конкретное значение не имеет смысла
/// persistence-семантики и выбрано явно помеченным.
pub const EPHEMERAL_AT_REST_KEY_ID: u16 = 0;

/// Эфемерный источник для честного RAM-only degrade (Keystore недоступен):
/// случайный ключ на время процесса. Durable-файл БЕЗ установленного ключа
/// сознательно НЕ создаётся — M8-C запрещает незашифрованную durable-запись,
/// а «заявить durable, но зашифровать одноразовым ключом» было бы нечестной
/// дурниной (записи на диске, расшифровать которые нельзя уже после выхода).
pub fn ephemeral_key_source() -> std::sync::Arc<MasterSecretKeySource> {
    let mut material = [0u8; AT_REST_KEY_BYTES];
    OsRng.fill_bytes(&mut material);
    std::sync::Arc::new(MasterSecretKeySource::new(EPHEMERAL_AT_REST_KEY_ID, material))
}

// ═══════════════════════════════════════════════════════════════════
// ГЛОБАЛЬНЫЙ РЕЕСТР УСТАНОВЛЕННОГО КЛЮЧА (точка входа UniFFI)
// ═══════════════════════════════════════════════════════════════════

/// Установленный хостом источник. Движок делает `Arc`-снимок в момент открытия
/// relay-хранилища; повторная установка влияет только на СЛЕДУЮЩИЙ запуск
/// движка (уже открытое хранилище ключ не меняет — иначе записи одного файла
/// смешали бы ключи молча).
static INSTALLED_DEVICE_KEY_SOURCE: std::sync::RwLock<Option<std::sync::Arc<MasterSecretKeySource>>> =
    std::sync::RwLock::new(None);

/// Установить источник ключа из материала, unwrap-нутого Keystore-мостом.
/// Вызывается из UniFFI ДО `engine.start()`. Неверная длина — отказ
/// (`InvalidKeyMaterial`), прежняя установка при этом сохраняется.
pub fn install_device_key_source(key_id: u16, material: &[u8]) -> Result<(), AtRestError> {
    let source = MasterSecretKeySource::from_shared_slice(key_id, material)?;
    let mut guard = INSTALLED_DEVICE_KEY_SOURCE
        .write()
        .map_err(|_| AtRestError::KeySourceUnavailable)?;
    *guard = Some(std::sync::Arc::new(source));
    Ok(())
}

/// Снимок установленного источника для движка (None → честный RAM-only путь).
pub fn installed_key_source() -> Option<std::sync::Arc<MasterSecretKeySource>> {
    INSTALLED_DEVICE_KEY_SOURCE
        .read()
        .ok()
        .and_then(|guard| guard.clone())
}

/// `key_id` установленного источника (диагностика/acceptance; None = не
/// установлен). Материал не раскрывается.
pub fn installed_key_id() -> Option<u16> {
    INSTALLED_DEVICE_KEY_SOURCE
        .read()
        .ok()
        .and_then(|guard| guard.as_ref().map(|s| s.key_id()))
}

/// Убрать установленный источник (drop зануляет материал). Для будущего
/// logout/wipe пути; уже работающий движок держит свой Arc-снимок.
pub fn clear_device_key_source() {
    if let Ok(mut guard) = INSTALLED_DEVICE_KEY_SOURCE.write() {
        *guard = None;
    }
}

// ═══════════════════════════════════════════════════════════════════
// AAD
// ═══════════════════════════════════════════════════════════════════

/// AAD записи: привязывает конверт к открытым колонкам SQLite
/// (`msg_id`, `recipient`, `expires_at_ms`). `msg_id`/`recipient` не могут
/// содержать `|` (wire-инвариант `valid_metadata_atom`), поэтому границы полей
/// однозначны. Подмена любой из этих колонок делает расшифровку невозможной —
/// запись уходит в quarantine вместо молчаливого принятия чужого дедлайна/
/// получателя.
pub fn build_record_aad(msg_id: &str, recipient: &str, expires_at_ms: i64) -> Vec<u8> {
    format!("{}|{}|{}|{}", AAD_DOMAIN, msg_id, recipient, expires_at_ms).into_bytes()
}

// ═══════════════════════════════════════════════════════════════════
// ENCRYPT / DECRYPT
// ═══════════════════════════════════════════════════════════════════

/// Зашифровать plaintext записи со случайным уникальным nonce.
pub fn encrypt_record(
    key: &RelayAtRestKey,
    aad: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, AtRestError> {
    let mut nonce = [0u8; AT_REST_NONCE_BYTES];
    OsRng.fill_bytes(&mut nonce);
    encrypt_record_with_nonce(key, aad, plaintext, &nonce)
}

/// Зашифровать с явным nonce (детерминированные тесты и контролируемое
/// re-wrap при миграциях). НЕ использовать в боевых путях со повторным nonce:
/// повтор (key, nonce) для разных plaintext разрушает AEAD.
pub fn encrypt_record_with_nonce(
    key: &RelayAtRestKey,
    aad: &[u8],
    plaintext: &[u8],
    nonce: &[u8; AT_REST_NONCE_BYTES],
) -> Result<Vec<u8>, AtRestError> {
    if plaintext.len() > MAX_AT_REST_RECORD_BYTES {
        return Err(AtRestError::PlaintextTooLarge {
            max: MAX_AT_REST_RECORD_BYTES,
        });
    }

    let cipher = XChaCha20Poly1305::new_from_slice(&key.material)
        .expect("AT_REST_KEY_BYTES is 32, always a valid XChaCha20 key size");
    let ciphertext = cipher
        .encrypt(XNonce::from_slice(nonce), Payload { msg: plaintext, aad })
        .map_err(|_| AtRestError::DecryptionFailed)?;

    let mut out = Vec::with_capacity(AT_REST_HEADER_BYTES + ciphertext.len());
    out.push(AT_REST_ENVELOPE_VERSION);
    out.extend_from_slice(&key.key_id.to_le_bytes());
    out.extend_from_slice(nonce);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Прочитать открытый заголовок конверта (version, key_id, nonce). Проверка
/// версии выполняется здесь же: неподдерживаемая версия — quarantine, а не
/// попытка разобрать дальше.
pub fn parse_envelope_header(
    envelope: &[u8],
) -> Result<(u8, u16, [u8; AT_REST_NONCE_BYTES]), AtRestError> {
    if envelope.len() < AT_REST_HEADER_BYTES + AT_REST_TAG_BYTES {
        return Err(AtRestError::MalformedEnvelope {
            reason: "короче header+tag",
        });
    }
    let version = envelope[0];
    if version != AT_REST_ENVELOPE_VERSION {
        return Err(AtRestError::UnsupportedVersion { found: version });
    }
    let key_id = u16::from_le_bytes([envelope[1], envelope[2]]);
    let mut nonce = [0u8; AT_REST_NONCE_BYTES];
    nonce.copy_from_slice(&envelope[3..AT_REST_HEADER_BYTES]);
    Ok((version, key_id, nonce))
}

/// Расшифровать конверт. Никогда не panic, никогда не возвращает plaintext
/// при ошибке. Ошибки — типизированные для quarantine-политики caller'а.
pub fn decrypt_record(
    key_source: &dyn RelayAtRestKeySource,
    aad: &[u8],
    envelope: &[u8],
) -> Result<Vec<u8>, AtRestError> {
    let (_version, key_id, nonce) = parse_envelope_header(envelope)?;

    if envelope.len() > AT_REST_HEADER_BYTES + MAX_AT_REST_RECORD_BYTES + AT_REST_TAG_BYTES {
        return Err(AtRestError::MalformedEnvelope {
            reason: "длиннее максимального размера записи",
        });
    }

    let key = key_source.key_by_id(key_id)?;
    let cipher = XChaCha20Poly1305::new_from_slice(&key.material)
        .expect("AT_REST_KEY_BYTES is 32, always a valid XChaCha20 key size");
    cipher
        .decrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: &envelope[AT_REST_HEADER_BYTES..],
                aad,
            },
        )
        .map_err(|_| AtRestError::DecryptionFailed)
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ (детерминированные, явные ключи и nonce)
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    const KEY_ID: u16 = 0x0102;

    fn key() -> RelayAtRestKey {
        RelayAtRestKey {
            key_id: KEY_ID,
            material: [0x42u8; AT_REST_KEY_BYTES],
        }
    }

    fn nonce() -> [u8; AT_REST_NONCE_BYTES] {
        [0x07u8; AT_REST_NONCE_BYTES]
    }

    /// Простой источник ключей для тестов.
    struct MapKeySource {
        keys: HashMap<u16, [u8; AT_REST_KEY_BYTES]>,
    }

    impl MapKeySource {
        fn single(k: &RelayAtRestKey) -> Self {
            let mut keys = HashMap::new();
            keys.insert(k.key_id, k.material);
            MapKeySource { keys }
        }
    }

    impl RelayAtRestKeySource for MapKeySource {
        fn current_key(&self) -> Result<RelayAtRestKey, AtRestError> {
            let (&key_id, &material) = self.keys.iter().next().ok_or(AtRestError::KeySourceUnavailable)?;
            Ok(RelayAtRestKey { key_id, material })
        }
        fn key_by_id(&self, key_id: u16) -> Result<RelayAtRestKey, AtRestError> {
            self.keys
                .get(&key_id)
                .map(|&material| RelayAtRestKey { key_id, material })
                .ok_or(AtRestError::UnknownKeyId { key_id })
        }
    }

    // ── Round trip / layout ─────────────────────────────────────────

    /// Детерминированный round-trip: заголовок точно по планировке,
    /// ciphertext отличается от plaintext, расшифровка восстанавливает байты.
    #[test]
    fn test_round_trip_and_exact_layout() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1_700_000_000_000);
        let plaintext = b"sensitive relay record bytes";

        let envelope = encrypt_record_with_nonce(&k, &aad, plaintext, &nonce()).unwrap();

        // Планировка: version | key_id LE | nonce | ciphertext||tag.
        assert_eq!(envelope.len(), AT_REST_HEADER_BYTES + plaintext.len() + AT_REST_TAG_BYTES);
        assert_eq!(envelope[0], AT_REST_ENVELOPE_VERSION);
        assert_eq!(&envelope[1..3], &KEY_ID.to_le_bytes());
        assert_eq!(&envelope[3..AT_REST_HEADER_BYTES], &nonce());
        // Тело не содержит plaintext.
        assert!(!envelope.windows(plaintext.len()).any(|w| w == plaintext));

        let (v, kid, n) = parse_envelope_header(&envelope).unwrap();
        assert_eq!(v, AT_REST_ENVELOPE_VERSION);
        assert_eq!(kid, KEY_ID);
        assert_eq!(n, nonce());

        let source = MapKeySource::single(&k);
        let out = decrypt_record(&source, &aad, &envelope).unwrap();
        assert_eq!(out, plaintext);
    }

    /// Случайный nonce: повторное шифрование тех же байт даёт другой конверт.
    #[test]
    fn test_random_nonce_makes_distinct_envelopes() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);
        let plaintext = b"same bytes";
        let first = encrypt_record(&k, &aad, plaintext).unwrap();
        let second = encrypt_record(&k, &aad, plaintext).unwrap();
        assert_ne!(first, second);
        // nonce-части заголовков различны.
        assert_ne!(
            &first[3..AT_REST_HEADER_BYTES],
            &second[3..AT_REST_HEADER_BYTES]
        );

        let source = MapKeySource::single(&k);
        assert_eq!(decrypt_record(&source, &aad, &first).unwrap(), plaintext);
        assert_eq!(decrypt_record(&source, &aad, &second).unwrap(), plaintext);
    }

    // ── Отказы: неправильный ключ / AAD / битые байты ───────────────

    /// Чужой ключ: расшифровка невозможна, plaintext не возвращается.
    #[test]
    fn test_wrong_key_rejected_without_plaintext() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);
        let envelope = encrypt_record_with_nonce(&k, &aad, b"secret", &nonce()).unwrap();

        let mut other = key();
        other.material = [0x24u8; AT_REST_KEY_BYTES];
        let source = MapKeySource::single(&other);
        assert_eq!(
            decrypt_record(&source, &aad, &envelope),
            Err(AtRestError::DecryptionFailed)
        );
    }

    /// Подмена AAD (например, другой expires_at_ms в открытой колонке) ломает
    /// аутентификацию: quarantine, а не принятие подменённых метаданных.
    #[test]
    fn test_aad_mismatch_rejected() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);
        let envelope = encrypt_record_with_nonce(&k, &aad, b"secret", &nonce()).unwrap();
        let source = MapKeySource::single(&k);

        let tampered_aad = build_record_aad("m1", "pk_b", 2);
        assert_eq!(
            decrypt_record(&source, &tampered_aad, &envelope),
            Err(AtRestError::DecryptionFailed)
        );
        let other_msg_aad = build_record_aad("m2", "pk_b", 1);
        assert_eq!(
            decrypt_record(&source, &other_msg_aad, &envelope),
            Err(AtRestError::DecryptionFailed)
        );
    }

    /// Перевёрнутый байт ciphertext или тега → DecryptionFailed.
    #[test]
    fn test_flipped_ciphertext_and_tag_rejected() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);
        let plaintext = b"flipping bits must fail";
        let envelope = encrypt_record_with_nonce(&k, &aad, plaintext, &nonce()).unwrap();
        let source = MapKeySource::single(&k);

        // Байт в теле ciphertext.
        let mut tampered = envelope.clone();
        tampered[AT_REST_HEADER_BYTES] ^= 0x01;
        assert_eq!(
            decrypt_record(&source, &aad, &tampered),
            Err(AtRestError::DecryptionFailed)
        );

        // Байт в теге (последние 16 байт).
        let mut tampered = envelope.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 0x80;
        assert_eq!(
            decrypt_record(&source, &aad, &tampered),
            Err(AtRestError::DecryptionFailed)
        );

        // Байт в nonce (части заголовка, тоже покрытой тегом через AEAD).
        let mut tampered = envelope;
        tampered[5] ^= 0x01;
        assert_eq!(
            decrypt_record(&source, &aad, &tampered),
            Err(AtRestError::DecryptionFailed)
        );
    }

    // ── Quarantine-семантика: версия / ключ / структура ─────────────

    /// Неподдерживаемая версия отклоняется явно (и НЕ угадывается).
    #[test]
    fn test_unknown_version_goes_to_quarantine() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);
        let mut envelope = encrypt_record_with_nonce(&k, &aad, b"data", &nonce()).unwrap();
        envelope[0] = 9;
        let source = MapKeySource::single(&k);
        assert_eq!(
            decrypt_record(&source, &aad, &envelope),
            Err(AtRestError::UnsupportedVersion { found: 9 })
        );
    }

    /// Неизвестный key_id (data clear / другой Keystore) → UnknownKeyId.
    #[test]
    fn test_unknown_key_id_goes_to_quarantine() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);
        let envelope = encrypt_record_with_nonce(&k, &aad, b"data", &nonce()).unwrap();
        // Пустой источник: ключа нет вовсе.
        let empty = MapKeySource {
            keys: HashMap::new(),
        };
        assert_eq!(
            decrypt_record(&empty, &aad, &envelope),
            Err(AtRestError::UnknownKeyId { key_id: KEY_ID })
        );
        // Ключ есть, но под другим id.
        let mut other = key();
        other.key_id = 0x9999;
        let source = MapKeySource::single(&other);
        assert_eq!(
            decrypt_record(&source, &aad, &envelope),
            Err(AtRestError::UnknownKeyId { key_id: KEY_ID })
        );
    }

    /// Любое усечение конверта — ошибка без panic (все длины 0..header+tag).
    #[test]
    fn test_truncated_envelope_never_panics() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);
        let envelope = encrypt_record_with_nonce(&k, &aad, b"record", &nonce()).unwrap();
        let source = MapKeySource::single(&k);

        for cut in 0..(AT_REST_HEADER_BYTES + AT_REST_TAG_BYTES) {
            let truncated = &envelope[..cut];
            assert!(
                decrypt_record(&source, &aad, truncated).is_err(),
                "length {} must be rejected",
                cut
            );
        }
        // Пустой вход.
        assert!(decrypt_record(&source, &aad, &[]).is_err());
    }

    /// Oversized plaintext отклоняется; ровно на границе — принимается.
    #[test]
    fn test_plaintext_size_bound() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);

        let too_big = vec![0xAAu8; MAX_AT_REST_RECORD_BYTES + 1];
        assert_eq!(
            encrypt_record_with_nonce(&k, &aad, &too_big, &nonce()),
            Err(AtRestError::PlaintextTooLarge {
                max: MAX_AT_REST_RECORD_BYTES
            })
        );

        let at_max = vec![0xAAu8; MAX_AT_REST_RECORD_BYTES];
        let envelope = encrypt_record_with_nonce(&k, &aad, &at_max, &nonce()).unwrap();
        let source = MapKeySource::single(&k);
        assert_eq!(decrypt_record(&source, &aad, &envelope).unwrap(), at_max);

        // И конверт, чья длина превышает максимум, отклоняется на входе.
        let mut oversized = envelope;
        oversized.extend_from_slice(&[0u8; 8]);
        assert!(matches!(
            decrypt_record(&source, &aad, &oversized),
            Err(AtRestError::MalformedEnvelope { .. })
                | Err(AtRestError::DecryptionFailed)
        ));
    }

    /// Никакого plaintext-fallback: «голые» байты (без конверта) не
    /// расшифровываются и не возвращаются как есть.
    #[test]
    fn test_no_plaintext_fallback() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);
        let source = MapKeySource::single(&k);

        // Похоже на bincode/plaintext: нет валидного заголовка конверта.
        let fake_plaintext = b"\x01\x02\x03short fake record";
        assert!(decrypt_record(&source, &aad, fake_plaintext).is_err());

        // Достаточно длинный, с валидной версией и key_id, но мусорным телом:
        // именно AEAD-отказ (не структурный и не unknown-key).
        let mut long_fake = vec![0x11u8; AT_REST_HEADER_BYTES + 64];
        long_fake[0] = AT_REST_ENVELOPE_VERSION;
        long_fake[1..3].copy_from_slice(&KEY_ID.to_le_bytes());
        assert_eq!(
            decrypt_record(&source, &aad, &long_fake),
            Err(AtRestError::DecryptionFailed)
        );
    }

    // ── AAD helper ──────────────────────────────────────────────────

    /// AAD детерминирован, включает домен и все три поля; разные поля → разный AAD.
    #[test]
    fn test_aad_is_domain_separated_and_field_bound() {
        let a = build_record_aad("m1", "pk_b", 1);
        assert!(a.starts_with(AAD_DOMAIN.as_bytes()));
        assert_ne!(a, build_record_aad("m2", "pk_b", 1));
        assert_ne!(a, build_record_aad("m1", "pk_c", 1));
        assert_ne!(a, build_record_aad("m1", "pk_b", 2));
        assert_eq!(a, build_record_aad("m1", "pk_b", 1));
    }

    /// Пустой plaintext допустим на уровне конверта (семантика «запись
    /// обязана быть непустой» остаётся на `RelayMessage::validate_durable`).
    #[test]
    fn test_empty_plaintext_round_trip() {
        let k = key();
        let aad = build_record_aad("m1", "pk_b", 1);
        let envelope = encrypt_record_with_nonce(&k, &aad, b"", &nonce()).unwrap();
        assert_eq!(envelope.len(), AT_REST_HEADER_BYTES + AT_REST_TAG_BYTES);
        let source = MapKeySource::single(&k);
        assert_eq!(decrypt_record(&source, &aad, &envelope).unwrap(), Vec::<u8>::new());
    }

    /// Debug ключа не раскрывает материал.
    #[test]
    fn test_key_debug_redacts_material() {
        let dbg = format!("{:?}", key());
        assert!(dbg.contains("[redacted]"));
        assert!(!dbg.contains("66")); // 0x42 = 66 десятичное
    }

    // ── M8-C slice 3: MasterSecretKeySource / wipe / реестр ────────

    /// Зануление: все байты нули, вызов на пустом срезе не panic.
    #[test]
    fn test_wipe_bytes_zeroes() {
        let mut buf = vec![0xABu8; 64];
        wipe_bytes(&mut buf);
        assert!(buf.iter().all(|&b| b == 0));
        wipe_bytes(&mut []); // не panic
    }

    /// Из среза: ровно 32 байта принимаются, любая другая длина — отказ
    /// без установки (никакого «подрезания» чужих байт).
    #[test]
    fn test_master_secret_from_slice_length() {
        assert!(MasterSecretKeySource::from_shared_slice(7, &[0x11u8; 32]).is_ok());
        for bad in [0usize, 16, 31, 33, 64] {
            assert_eq!(
                MasterSecretKeySource::from_shared_slice(7, &vec![0x11u8; bad]),
                Err(AtRestError::InvalidKeyMaterial {
                    expected: AT_REST_KEY_BYTES,
                    found: bad,
                })
            );
        }
    }

    /// Источник отдаёт текущий ключ и находит его по id; чужой id — явный
    /// UnknownKeyId (quarantine-семантика, не угадывание).
    #[test]
    fn test_master_secret_key_lookup() {
        let source = MasterSecretKeySource::from_shared_slice(7, &[0x33u8; 32]).unwrap();
        assert_eq!(source.key_id(), 7);
        let current = source.current_key().unwrap();
        assert_eq!(current.key_id, 7);
        assert_eq!(current.material, [0x33u8; 32]);
        assert!(source.key_by_id(7).is_ok());
        assert_eq!(
            source.key_by_id(8),
            Err(AtRestError::UnknownKeyId { key_id: 8 })
        );
    }

    /// Сквозной цикл: шифрование текущим ключом источника и расшифровка
    /// тем же источником; Debug не раскрывает материал.
    #[test]
    fn test_master_secret_seal_open_and_redaction() {
        let source = MasterSecretKeySource::from_shared_slice(7, &[0x5Au8; 32]).unwrap();
        let aad = build_record_aad("m1", "pk_b", 1);
        let plaintext = b"custody bytes";
        let envelope = encrypt_record(&source.current_key().unwrap(), &aad, plaintext).unwrap();
        assert_eq!(decrypt_record(&source, &aad, &envelope).unwrap(), plaintext);
        let dbg = format!("{:?}", source);
        assert!(dbg.contains("[redacted]"));
        assert!(dbg.contains("7")); // key_id может логироваться, материал — нет
    }

    /// Глобальный реестр: установка → снимок → key_id → очистка. Единственный
    /// тест, трогающий глобальное состояние (cargo test гоняет тесты
    /// параллельно — двух таких тестов быть не должно).
    #[test]
    fn test_install_read_clear_roundtrip() {
        clear_device_key_source();
        assert!(installed_key_source().is_none());
        assert_eq!(installed_key_id(), None);

        install_device_key_source(4242, &[0x77u8; AT_REST_KEY_BYTES]).unwrap();
        let snap = installed_key_source().expect("источник установлен");
        assert_eq!(snap.key_id(), 4242);
        assert_eq!(installed_key_id(), Some(4242));
        // Снимок usable как источник: encrypt → decrypt.
        let aad = build_record_aad("m", "r", 1);
        let env = encrypt_record(&snap.current_key().unwrap(), &aad, b"x").unwrap();
        assert_eq!(decrypt_record(&*snap, &aad, &env).unwrap(), b"x");

        clear_device_key_source();
        assert!(installed_key_source().is_none());
        assert_eq!(installed_key_id(), None);
        // Ранее снятый Arc продолжает работать (движок держит свой снимок).
        assert_eq!(decrypt_record(&*snap, &aad, &env).unwrap(), b"x");

        // Неверная длина — отказ и состояние не меняется.
        assert!(install_device_key_source(1, &[0u8; 10]).is_err());
        assert_eq!(installed_key_id(), None);
    }

    /// Эфемерный источник: валиден для шифрования/расшифровки в пределах
    /// процесса; два экземпляра независимы (случайный материал).
    #[test]
    fn test_ephemeral_source_is_ram_only_and_random() {
        let a = ephemeral_key_source();
        let b = ephemeral_key_source();
        assert_eq!(a.key_id(), EPHEMERAL_AT_REST_KEY_ID);
        let aad = build_record_aad("m", "r", 1);
        let env = encrypt_record(&a.current_key().unwrap(), &aad, b"tmp").unwrap();
        assert_eq!(decrypt_record(&*a, &aad, &env).unwrap(), b"tmp");
        // Другой эфемерный ключ тот же envelope расшифровать не может
        // (крайне маловероятное совпадение 256-бит материала исключено дизайном).
        assert_eq!(
            decrypt_record(&*b, &aad, &env),
            Err(AtRestError::DecryptionFailed)
        );
    }
}
