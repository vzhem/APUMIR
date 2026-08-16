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
//! На Android реализация будет обёрткой над ключом в Android Keystore
//! (не-извлекаемый ключ; app update сохраняет Keystore-ключ, data clear — теряет;
//! это честно документируется пользователю). Текущий slice содержит только trait
//! и тестовый источник ключей.

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
#[derive(Clone)]
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

/// Источник ключей конверта. На Android будет реализован мостом к Android
/// Keystore (не-извлекаемый device-bound ключ). Важно для app update migration:
/// Keystore переживает обновление APK, поэтому старые записи остаются
/// расшифровываемыми тем же `key_id`; data clear ключ стирает — записи после
/// этого законно не расшифровываются (честная потеря local custody, без
/// маскировки).
pub trait RelayAtRestKeySource {
    /// Ключ для НОВЫХ шифрований (текущий).
    fn current_key(&self) -> Result<RelayAtRestKey, AtRestError>;

    /// Ключ для расшифровки существующего конверта по `key_id` из заголовка.
    fn key_by_id(&self, key_id: u16) -> Result<RelayAtRestKey, AtRestError>;
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
}
