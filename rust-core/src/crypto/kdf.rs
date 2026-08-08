//! # Key Derivation Function (HKDF)
//!
//! HKDF — HMAC-based Key Derivation Function (RFC 5869).
//!
//! Используется для получения нескольких ключей из одного секрета:
//! - X3DH: 4 DH секрета → 1 общий сессионный ключ
//! - Double Ratchet: старый chain key → новый chain key + message key
//!
//! ## Принцип работы:
//!
//! ```text
//! Входы:
//!   IKM  — Input Key Material (общий секрет)
//!   Salt — соль (опционально, повышает энтропию)
//!   Info — контекст (например "handshake" или "ratchet-msg-3")
//!
//! Шаг 1 (extract): PRK = HMAC-SHA256(Salt, IKM)
//! Шаг 2 (expand):  OKM = HKDF-Expand(PRK, Info, output_length)
//!
//! Выход:
//!   OKM  — Output Keying Material (нужное количество ключей)
//! ```

use hkdf::Hkdf;
use sha2::Sha256;

use super::{CryptoError, CryptoResult};

// ═══════════════════════════════════════════════════════════════════
// ФУНКЦИИ ДЕРИВАЦИИ
// ═══════════════════════════════════════════════════════════════════

/// Вывести один ключ из входного материала.
///
/// # Параметры
/// - `input_key_material` — исходный секрет (например DH shared secret)
/// - `salt` — соль (может быть пустой)
/// - `info` — контекстная строка (для разделения доменов использования)
/// - `output_length` — сколько байт нужно на выходе
///
/// # Пример
/// ```ignore
/// let shared_secret = alice.diffie_hellman(&bob_pubkey)?;
/// let session_key = derive_key(&shared_secret, b"", b"handshake-v1", 32)?;
/// ```
pub fn derive_key(
    input_key_material: &[u8],
    salt: &[u8],
    info: &[u8],
    output_length: usize,
) -> CryptoResult<Vec<u8>> {
    let hk = Hkdf::<Sha256>::new(Some(salt), input_key_material);

    let mut output = vec![0u8; output_length];
    hk.expand(info, &mut output)
        .map_err(|e| CryptoError::KeyGeneration(format!("HKDF expand ошибка: {}", e)))?;

    Ok(output)
}

/// Вывести НЕСКОЛЬКО ключей за один вызов.
///
/// Используется в Double Ratchet: из одного секрета получаем
/// одновременно новый chain key и message key.
///
/// # Пример
/// ```ignore
/// let outputs = derive_multiple_keys(
///     &chain_key,
///     b"",
///     b"ratchet",
///     &[32, 32], // Хотим два ключа по 32 байта
/// )?;
/// let new_chain_key = &outputs[0];
/// let message_key = &outputs[1];
/// ```
pub fn derive_multiple_keys(
    input_key_material: &[u8],
    salt: &[u8],
    info: &[u8],
    key_sizes: &[usize],
) -> CryptoResult<Vec<Vec<u8>>> {
    let total_size: usize = key_sizes.iter().sum();

    // Получаем один большой блок и разбиваем на части
    let combined = derive_key(input_key_material, salt, info, total_size)?;

    let mut result = Vec::with_capacity(key_sizes.len());
    let mut offset = 0;

    for &size in key_sizes {
        result.push(combined[offset..offset + size].to_vec());
        offset += size;
    }

    Ok(result)
}

/// Специализированная функция для Double Ratchet.
///
/// Из текущего chain_key выводит:
/// - новый chain_key (для следующего шага)
/// - message_key (для шифрования одного сообщения)
///
/// Каждое из этих значений — 32 байта.
///
/// # Пример
/// ```ignore
/// let (new_chain_key, message_key) = ratchet_kdf(&current_chain_key)?;
/// // Шифруем сообщение с message_key
/// // current_chain_key = new_chain_key (для следующего сообщения)
/// ```
pub fn ratchet_kdf(chain_key: &[u8]) -> CryptoResult<(Vec<u8>, Vec<u8>)> {
    // Разные info для новой chain key и message key — важно для безопасности!
    // Иначе они могут совпасть если input один и тот же.
    let new_chain_key = derive_key(chain_key, b"", b"ratchet-chain", 32)?;
    let message_key = derive_key(chain_key, b"", b"ratchet-message", 32)?;

    Ok((new_chain_key, message_key))
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_derive_key_basic() {
        let ikm = b"input key material";
        let salt = b"salt";
        let info = b"test context";

        let key = derive_key(ikm, salt, info, 32).unwrap();
        assert_eq!(key.len(), 32);
        println!("✅ HKDF: базовая деривация работает");
    }

    #[test]
    fn test_derive_key_deterministic() {
        // Одинаковые входы → одинаковый выход
        let ikm = b"secret";
        let salt = b"salt";
        let info = b"info";

        let k1 = derive_key(ikm, salt, info, 32).unwrap();
        let k2 = derive_key(ikm, salt, info, 32).unwrap();
        assert_eq!(k1, k2);
        println!("✅ HKDF детерминирован");
    }

    #[test]
    fn test_derive_key_different_info_different_output() {
        // Разный info → разный выход (важно для разделения доменов)
        let ikm = b"secret";
        let salt = b"salt";

        let k1 = derive_key(ikm, salt, b"context-A", 32).unwrap();
        let k2 = derive_key(ikm, salt, b"context-B", 32).unwrap();
        assert_ne!(k1, k2);
        println!("✅ HKDF: разный info → разные ключи");
    }

    #[test]
    fn test_derive_key_different_ikm_different_output() {
        let salt = b"salt";
        let info = b"info";

        let k1 = derive_key(b"secret-1", salt, info, 32).unwrap();
        let k2 = derive_key(b"secret-2", salt, info, 32).unwrap();
        assert_ne!(k1, k2);
        println!("✅ HKDF: разный IKM → разные ключи");
    }

    #[test]
    fn test_derive_various_lengths() {
        let ikm = b"secret";
        let salt = b"";
        let info = b"test";

        let k16 = derive_key(ikm, salt, info, 16).unwrap();
        let k32 = derive_key(ikm, salt, info, 32).unwrap();
        let k64 = derive_key(ikm, salt, info, 64).unwrap();

        assert_eq!(k16.len(), 16);
        assert_eq!(k32.len(), 32);
        assert_eq!(k64.len(), 64);

        // Первые 16 байт k32 должны совпадать с первыми 16 байтами k64
        // (свойство HKDF — можно "продлить" вывод)
        // Но это уже детали HKDF, просто убедимся что размеры разные
        println!("✅ HKDF работает с разными размерами вывода");
    }

    #[test]
    fn test_derive_multiple_keys() {
        let ikm = b"shared secret";
        let salt = b"";
        let info = b"double ratchet";

        let keys = derive_multiple_keys(ikm, salt, info, &[32, 32, 16]).unwrap();

        assert_eq!(keys.len(), 3);
        assert_eq!(keys[0].len(), 32);
        assert_eq!(keys[1].len(), 32);
        assert_eq!(keys[2].len(), 16);

        // Ключи должны быть разными
        assert_ne!(keys[0], keys[1]);
        println!("✅ HKDF: множественная деривация работает");
    }

    #[test]
    fn test_ratchet_kdf() {
        let chain_key = vec![42u8; 32];

        let (new_chain, msg_key) = ratchet_kdf(&chain_key).unwrap();

        assert_eq!(new_chain.len(), 32);
        assert_eq!(msg_key.len(), 32);

        // Новая chain key и message key должны быть разными
        assert_ne!(new_chain, msg_key);

        // И оба не должны равняться исходному ключу
        assert_ne!(new_chain, chain_key);
        assert_ne!(msg_key, chain_key);

        println!("✅ Ratchet KDF: новая цепочка и ключ сообщения корректны");
    }

    #[test]
    fn test_ratchet_kdf_deterministic() {
        let chain_key = vec![42u8; 32];

        let (nc1, mk1) = ratchet_kdf(&chain_key).unwrap();
        let (nc2, mk2) = ratchet_kdf(&chain_key).unwrap();

        assert_eq!(nc1, nc2);
        assert_eq!(mk1, mk2);
        println!("✅ Ratchet KDF детерминирован");
    }

    #[test]
    fn test_ratchet_chain_progression() {
        // Имитируем прокрутку рэтчета на несколько сообщений
        let mut chain_key = vec![1u8; 32];
        let mut message_keys = Vec::new();

        for _ in 0..5 {
            let (new_ck, mk) = ratchet_kdf(&chain_key).unwrap();
            chain_key = new_ck;
            message_keys.push(mk);
        }

        // Все message keys должны быть уникальными
        for i in 0..message_keys.len() {
            for j in (i + 1)..message_keys.len() {
                assert_ne!(
                    message_keys[i], message_keys[j],
                    "Message keys {} и {} совпали!",
                    i, j
                );
            }
        }

        println!("✅ Ratchet chain: 5 уникальных message keys подряд");
    }

    #[test]
    fn test_derive_key_empty_salt() {
        // Пустая соль — валидный кейс (соль опциональна в HKDF)
        let key = derive_key(b"secret", b"", b"info", 32).unwrap();
        assert_eq!(key.len(), 32);
        println!("✅ HKDF работает с пустой солью");
    }
}
