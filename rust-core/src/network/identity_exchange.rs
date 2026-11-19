//! # Обмен подписанной identity через брокер
//!
//! Проблема (владелец 2026-11-19, «научи ядро получать»). Помощная доставка
//! (`ONLINE_HELPER`) требует ОДНОГО: [`HelperIdentity`] получателя, у которой
//! не истёк срок. Сама по себе identity — это подписанные claims
//! (node_id, signing_key, exchange_key, issued_at_ms, expires_at_ms) плюс
//! Ed25519-подпись. Ядро проверяет подпись и хранит их в каталоге
//! (`PeerExchange::identities`), после чего `helper_identity(&recipient)`
//! отвечает «да», и `prepare_relay` может собрать запечатанный пакет.
//!
//! До сих пор identity попадала в каталог ТОЛЬКО двумя путями, и оба требуют
//! прямого канала: подписанный ответ обмена адресами (QUIC) или ретранслированный
//! `Introduction` от посредника. Под VPN (STUN/UDP/direct мертвы, MQTT жив)
//! ни один из них не срабатывает — отсюда `route_unavailable
//! reason=recipient_identity` и застрявшая своя очередь.
//!
//! Что делает этот модуль: переносит ту же подписанную identity тем
//! единственным каналом, который под VPN работает, — топиком брокера
//! `p2pm2/msg/<id>`.
//!
//! ```text
//!   pidreq|<requester_node_id>|<nonce_hex>
//!   pidres|<nonce_hex>|<base64(claims_json)>.<base64(signature)>
//! ```
//!
//! ## Почему это не подделка
//!
//! Ответ несёт [`HelperIdentity`] ЦЕЛИКОМ, с настоящей подписью: принимающая
//! сторона прогоняет `HelperIdentity::verify` (срок, окно аренды, подпись) и
//! только потом кладёт в каталог. Подделать чужой `node_id` без чужого ключа
//! подписи нельзя.
//!
//! ## Что остаётся слабым местом и что мы с этим делаем
//!
//! `node_id` в этом приложении — СЛУЧАЙНЫЙ (`pk_` + hex, см.
//! `crypto_ffi::generate_keys`), он не выводится из ключа подписи. Поэтому
//! «узел назвался X» само по себе ничего не доказывает: подпись подтверждает
//! владение КЛЮЧОМ, но не то, что этот ключ принадлежит именно X. Связать
//! `node_id` с ключом можно было бы только прямым каналом (TOFU), которого
//! под VPN нет.
//!
//! Отсюда две осознанные меры, а не молчаливая дыра:
//!
//! 1. **Ответ — только ответ.** В запросе едет `nonce_hex`, и ответ обязан
//!    его повторить. Принимается лишь тот кадр, чей nonce МЫ выдавали и чей
//!    `node_id` совпадает с тем, у кого просили. Слепая инъекция в нашу тему
//!    без знания свежего nonce не проходит.
//! 2. **Подсказка не становится доверием.** Принятое таким путём хранится как
//!    hint (`accept_identity_hint`), без пина. Настоящий пинимый ключ
//!    (`accept_end_to_end_identity`) по-прежнему требует прямого
//!    криптографического доказательства и всегда перекрывает подсказку.
//!
//! Плюс содержимое сообщений и так запечатано end-to-end на уровне Android
//! (`APUSEAL1`), поэтому знание чужой identity не даёт прочитать переписку.
//!
//! ## Граница: что этот модуль НЕ решает
//!
//! Полученная identity снимает `route_unavailable reason=recipient_identity`
//! и позволяет `prepare_relay` собрать запечатанный `Relay`-пакет. Но сам
//! `Relay` принимает только тот помощник, у которого уже есть
//! `authenticated_peer(получателя)` — прямая проверенная связь
//! (`peer_assistance.rs`, ветка `AssistOperation::Relay`). Под VPN, где
//! STUN/UDP мертвы, такой связи может не быть ни у кого, и тогда пакет
//! собирается, но нога возвращается отказом или молчанием.
//!
//! Поэтому считать этот шаг «доставка заработала» нельзя. Он убирает первую
//! из двух причин `not_prepared` и делает отказ диагностируемым: после него
//! в журнале видно либо `recipient_signed_receipts_issued` (маршрут есть),
//! либо отказ помощника (маршрута нет ни у кого). Следующий шаг — путь
//! доставки, не требующий прямой связи у посредника.
//!
//! ## N-1
//!
//! Старые сборки такие кадры молча пропускают: `pidreq`/`pidres` не являются
//! ни `ack|`, ни `relay|`, ни `ppres|`, а обычная ветка требует ровно четырёх
//! полей с `pk_` первым — у наших кадров после `|` идёт hex/base64, и первое
//! поле равно целиком `pidres|…` (то есть не `pk_…`). Ничего не падает.

use crate::network::peer_assistance::HelperIdentity;
use base64::Engine;

/// Печатный ASCII без `|`: кадр остаётся однострочным и не рвётся нашим же
/// разделителем полей. Отдельная функция, а не константа: `GeneralPurpose`
/// не обязан быть `const-конструируемым` в этой версии `base64`.
fn standard() -> base64::engine::general_purpose::GeneralPurpose {
    base64::engine::general_purpose::STANDARD
}

/// Просьба прислать свою подписанную identity.
pub const IDENTITY_REQUEST_TAG: &str = "pidreq";

/// Ответ: подписанная identity адресата.
pub const IDENTITY_RESPONSE_TAG: &str = "pidres";

/// Размер nonce в байтах. 16 байт достаточно, чтобы угадывание было бесполезно.
pub const NONCE_BYTES: usize = 16;

/// Максимальная длина кадра, который мы готовы разбирать.
///
/// `pidres` — это JSON claims (порядка 300 байт) плюс подпись и base64-обёртка;
/// 4 КиБ с большим запасом. Ограничение нужно, чтобы кадр из чужой темы не
/// заставил нас декодировать мегабайтную строку.
pub const MAX_FRAME_BYTES: usize = 4096;

/// Новый случайный nonce в hex.
///
/// Nonce не секрет — он защищает от слепой инъекции: принять ответ можно только
/// на тот запрос, который мы действительно слали.
pub fn new_nonce() -> String {
    use rand::RngCore;
    let mut raw = [0u8; NONCE_BYTES];
    rand::rngs::OsRng.fill_bytes(&mut raw);
    raw.iter().map(|byte| format!("{:02x}", byte)).collect()
}

fn valid_nonce(value: &str) -> bool {
    value.len() == NONCE_BYTES * 2
        && value
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

/// Собрать запрос: `pidreq|<свой node_id>|<nonce>`.
///
/// Свой id здесь — подсказка для получателя (кому отвечать). Доверять ему
/// нельзя и не нужно: ответ всё равно подписан, а адрес доставки берётся из
/// темы, а не из тела кадра.
pub fn request_payload(node_id: &str, nonce: &str) -> String {
    format!("{}|{}|{}", IDENTITY_REQUEST_TAG, node_id, nonce)
}

/// Разобрать запрос. Возвращает `(node_id просившего, nonce)`.
///
/// `None` — не наш кадр или он битый. Ошибку не логируем: в чужой теме может
/// лежать что угодно.
pub fn parse_request(payload: &str) -> Option<(String, String)> {
    if payload.len() > MAX_FRAME_BYTES {
        return None;
    }
    let body = payload.strip_prefix(IDENTITY_REQUEST_TAG)?.strip_prefix('|')?;
    let (node_id, nonce) = body.split_once('|')?;
    // Ровно два поля: лишние `|` означают испорченный кадр.
    if !crate::network::peer_exchange::valid_node_id(node_id) || !valid_nonce(nonce) {
        return None;
    }
    Some((node_id.to_string(), nonce.to_string()))
}

/// Собрать ответ: `pidres|<nonce>|<base64(claims_json)>.<base64(signature)>`.
///
/// Точкой разделены две независимые base64-строки. Формат не полагается на
/// JSON целиком, потому что подпись — это `Vec<u8>`, и в JSON она была бы
/// массивом чисел.
pub fn response_payload(nonce: &str, identity: &HelperIdentity) -> Option<String> {
    if !valid_nonce(nonce) {
        return None;
    }
    let claims = serde_json::to_vec(&identity.claims).ok()?;
    let mut payload = String::with_capacity(512);
    payload.push_str(IDENTITY_RESPONSE_TAG);
    payload.push('|');
    payload.push_str(nonce);
    payload.push('|');
    payload.push_str(&standard().encode(&claims));
    payload.push('.');
    payload.push_str(&standard().encode(&identity.signature));
    Some(payload)
}

/// Разобрать ответ. Возвращает `(nonce, identity)`.
///
/// Здесь проверяется ТОЛЬКО форма. Подпись проверяет вызывающий
/// (`HelperIdentity::verify`), потому что для неё нужны текущее время и
/// политика аренды, которые модулю кадров знать незачем.
pub fn parse_response(payload: &str) -> Option<(String, HelperIdentity)> {
    if payload.len() > MAX_FRAME_BYTES {
        return None;
    }
    let body = payload.strip_prefix(IDENTITY_RESPONSE_TAG)?.strip_prefix('|')?;
    let (nonce, rest) = body.split_once('|')?;
    if !valid_nonce(nonce) {
        return None;
    }
    let (claims_b64, signature_b64) = rest.split_once('.')?;
    let claims = standard().decode(claims_b64).ok()?;
    let signature = standard().decode(signature_b64).ok()?;
    let claims = serde_json::from_slice(&claims).ok()?;
    Some((nonce.to_string(), HelperIdentity { claims, signature }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::signing_identity::InstalledSigningIdentity;

    fn now() -> i64 {
        crate::storage::models::now_ms()
    }

    fn identity(legacy: &str, seed: u8) -> InstalledSigningIdentity {
        InstalledSigningIdentity::from_seed(1, legacy.into(), &[seed; 32]).unwrap()
    }

    fn legacy(tail: char) -> String {
        format!("pk_{}", tail.to_string().repeat(32))
    }

    #[test]
    fn request_round_trip_keeps_nonce_and_id() {
        let nonce = new_nonce();
        let payload = request_payload(&legacy('a'), &nonce);
        let (id, back) = parse_request(&payload).expect("свой кадр должен разбираться");
        assert_eq!(id, legacy('a'));
        assert_eq!(back, nonce);
    }

    #[test]
    fn nonces_are_random_hex_of_fixed_size() {
        let first = new_nonce();
        let second = new_nonce();
        assert_eq!(first.len(), NONCE_BYTES * 2);
        assert!(valid_nonce(&first));
        assert_ne!(first, second, "два nonce подряд не должны совпасть");
    }

    #[test]
    fn request_rejects_foreign_and_malformed_frames() {
        let nonce = new_nonce();
        // Кадры других подсистем брокера не должны приниматься за запрос.
        assert!(parse_request(&format!("ppres|{}|Имя||client|2|1", legacy('a'))).is_none());
        assert!(parse_request("ack|abc").is_none());
        assert!(parse_request("relay|abc").is_none());
        assert!(parse_request(&format!("pidreq|{}", legacy('a'))).is_none(), "нет nonce");
        assert!(parse_request(&format!("pidreq|{0}|{1}|хвост", legacy('a'), nonce)).is_none(), "лишнее поле");
        assert!(parse_request(&format!("pidreq|{}|{}", "x", nonce)).is_none(), "не node id");
        assert!(parse_request(&format!("pidreq|{}|{}", legacy('a'), "00")).is_none(), "короткий nonce");
        assert!(parse_request(&format!("pidreq|{}|{}", legacy('a'), "A".repeat(32))).is_none(), "верхний регистр");
        assert!(
            parse_request(&format!("pidreq|{}|{}", legacy('a'), "0".repeat(MAX_FRAME_BYTES))).is_none(),
            "слишком длинный кадр"
        );
    }

    #[test]
    fn response_round_trip_keeps_nonce_and_valid_signature() {
        let id = identity(&legacy('b'), 7);
        let signed = HelperIdentity::create(&id, now()).unwrap();
        let nonce = new_nonce();
        let payload = response_payload(&nonce, &signed).unwrap();

        let (back, parsed) = parse_response(&payload).expect("кадр должен разбираться");
        assert_eq!(back, nonce);
        assert_eq!(parsed, signed, "claims и подпись должны пережить обёртку");
        assert!(parsed.verify(now()), "подпись остаётся настоящей");
    }

    #[test]
    fn response_rejects_other_frames_and_corruption() {
        let id = identity(&legacy('c'), 9);
        let signed = HelperIdentity::create(&id, now()).unwrap();
        let nonce = new_nonce();
        let payload = response_payload(&nonce, &signed).unwrap();

        assert!(parse_response(&format!("pidreq|{}|{}", legacy('c'), nonce)).is_none(), "запрос — не ответ");
        assert!(parse_response("ack|1").is_none());
        assert!(parse_response(&format!("pidres|{}", nonce)).is_none(), "нет тела");
        assert!(parse_response(&format!("pidres|{}|bm90LWFqc29u.сгс", nonce)).is_none(), "мусор");
        // Нет точки-разделителя: подпись потеряна.
        assert!(parse_response(&payload.replace('.', "")).is_none());
        assert!(parse_response(&format!("{}!", payload)).is_none(), "мусор в конце");
        assert!(
            parse_response(&"y".repeat(MAX_FRAME_BYTES)).is_none(),
            "слишком длинный кадр не разбираем"
        );
    }

    #[test]
    fn response_without_valid_nonce_is_ignored() {
        let id = identity(&legacy('d'), 10);
        let signed = HelperIdentity::create(&id, now()).unwrap();
        // Ответ без нашего nonce (или с испорченным) не должен приниматься.
        assert!(response_payload("", &signed).is_none());
        assert!(response_payload("zz", &signed).is_none());
        let forged = response_payload(&new_nonce(), &signed).unwrap();
        let (nonce, _) = parse_response(&forged).unwrap();
        assert!(valid_nonce(&nonce));
    }

    #[test]
    fn tampered_claims_fail_verification_even_when_frame_parses() {
        // Подмена node_id внутри кадра: форма цела, подпись — нет.
        let id = identity(&legacy('e'), 11);
        let signed = HelperIdentity::create(&id, now()).unwrap();
        let mut damaged = signed.clone();
        damaged.claims.node_id = legacy('f');

        let payload = response_payload(&new_nonce(), &damaged).unwrap();
        let (_, parsed) = parse_response(&payload).expect("форма цела");
        assert!(!parsed.verify(now()), "чужая подпись не должна проходить проверку");
    }

    #[test]
    fn parse_is_pure_and_repeatable() {
        let id = identity(&legacy('a'), 13);
        let signed = HelperIdentity::create(&id, now()).unwrap();
        let nonce = new_nonce();
        let payload = response_payload(&nonce, &signed).unwrap();
        for _ in 0..3 {
            let (back, parsed) = parse_response(&payload).unwrap();
            assert_eq!(back, nonce);
            assert_eq!(parsed, signed);
        }
    }
}
