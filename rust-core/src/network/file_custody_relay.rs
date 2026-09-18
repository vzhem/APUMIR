//! K5-2: файловая кастодия по прямому каналу - кадры, хранилище и очередь
//! предложений.
//!
//! Что это. Соседние телефоны держат зашифрованные куски файла для того, кто
//! сейчас не в сети, и отдают их, когда получатель появится. Сам склад
//! (`network::file_custody`) уже умеет хранить куски с подписанными
//! квитанциями; здесь - «перевозка»: текстовые кадры прямого канала,
//! ограниченное хранилище того, что нам предложили, и очередь того, что мы
//! сами хотим пристроить соседям.
//!
//! Формат кадров (разделитель `|`, первое поле - `fcust`, поэтому старые
//! сборки молча отбрасывают их тем же стражем, что и любой чужой кадр:
//! правило N ↔ N-1 не нарушено):
//!
//! * `fcust|offer|<origin>|<origin_key_hex>|<recipient>|<expires_ms>|<frame_b64>`
//!   - «подержи этот кусок для получателя». `origin` - обычное имя автора,
//!   под которым его знают в сети (по нему проверяем «свой ли» и отвечаем), а
//!   `origin_key_hex` - ключ кастодии: им же подписывается привязка личности
//!   в приложении, и он же попадает в квитанцию. Два разных поля тут
//!   нарочно: имя в сети и ключ подписи - разные ключи устройства; связывает
//!   их проверенная привязка личности, которую ведёт приложение;
//! * `fcust|ack|<unit_id>|ok|<receipt_b64>` - хранитель подтверждает и
//!   прикладывает подписанную квитанцию; `ok` без квитанции не бывает;
//!   `refused` - не взял (нельзя/не разрешено), `full` - места нет;
//! * `fcust|deliver|<recipient>|<unit_id>|<frame_b64>` - хранитель отдаёт
//!   кусок получателю;
//! * `fcust|drop|<unit_id>` - копия больше не нужна (её отдали получателю).
//!
//! `unit_id` считается из (автор, получатель, transferId, индекс куска,
//! смещение) и одинаков у всех участников: по нему хранитель отвечает, а
//! автор понимает, о каком куске речь, не храня лишнего состояния.
//!
//! Куски - тот же бинарный кадр B1 (`network::file_wire`, магик APUF), что
//! ходит прямым каналом в K3: получателю приходит ровно то же событие
//! (`CoreEvent::FileChunkReceived`), поэтому приложению не нужен новый код
//! приёма файлов. Внутри - только шифртекст: ключей, имён файлов и открытых
//! данных здесь нет.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Mutex;

use base64::Engine;
use sha2::{Digest, Sha256};

use crate::crypto::keys::ED25519_PUBLIC_KEY_SIZE;
use crate::network::file_wire::{FileChunkDataV1, FileFrameV1, MAX_FILE_FRAME_BYTES};

pub const FILE_CUSTODY_OFFER_PREFIX: &str = "fcust|offer";
pub const FILE_CUSTODY_ACK_PREFIX: &str = "fcust|ack";
pub const FILE_CUSTODY_DELIVER_PREFIX: &str = "fcust|deliver";
pub const FILE_CUSTODY_DROP_PREFIX: &str = "fcust|drop";

/// Сколько хранителей целимся получить на один кусок. Два, как у сообщений
/// (K5-1): одного мало (он может исчезнуть), больше - лишний расход чужого
/// места и трафика.
pub const MAX_FILE_CUSTODY_PEERS: usize = 2;
/// Сколько предложений отправляем за один круг потока presence. Куски
/// тяжёлые (до 256 КиБ), поэтому в отличие от сообщений отправляем по одному.
pub const MAX_FILE_CUSTODY_OFFERS_PER_TICK: usize = 1;
/// Сколько кусков отдаём получателю за круг.
pub const MAX_FILE_CUSTODY_DELIVERIES_PER_TICK: usize = 2;
/// Сколько чужих кусков держим и сколько места под них отводим.
pub const MAX_FILE_CUSTODY_HELD_UNITS: usize = 128;
pub const MAX_FILE_CUSTODY_HELD_BYTES: usize = 64 * 1024 * 1024;
/// Сколько писем (ack, drop) держим в очереди на отправку.
pub const MAX_FILE_CUSTODY_QUEUED_OUTBOUND: usize = 64;
/// Сколько своих кусков одновременно ищем, кому пристроить.
pub const MAX_FILE_CUSTODY_TRACKED_UNITS: usize = 256;
/// Сколько идентификаторов отданных кусков помним, чтобы два хранителя не
/// показали получателю один и тот же кусок дважды.
pub const MAX_FILE_CUSTODY_DELIVERED_MEMORY: usize = 512;
/// Размер `unit_id` в байтах (hex-строка вдвое длиннее).
pub const FILE_CUSTODY_UNIT_ID_BYTES: usize = 16;

const UNIT_ID_DOMAIN: &[u8] = b"apu-file-custody-unit-v1\0";
const MIN_NODE_ID_BYTES: usize = 35;
const MAX_NODE_ID_BYTES: usize = 67;

/// Что ответил хранитель.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FileCustodyAck {
    /// Взял и подписал квитанцию.
    Stored,
    /// Не взял: не разрешено, не те нам, или кусок не прошёл проверку.
    Refused,
    /// Не взял из-за места/лимитов.
    Full,
}

impl FileCustodyAck {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Stored => "ok",
            Self::Refused => "refused",
            Self::Full => "full",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "ok" => Some(Self::Stored),
            "refused" => Some(Self::Refused),
            "full" => Some(Self::Full),
            _ => None,
        }
    }
}

/// Один кусок файла, который хранится (или предлагается) у соседа.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCustodyUnit {
    pub unit_id: String,
    pub origin_node_id: String,
    /// Ключ кастодии автора (тот же, что подписывает привязку личности).
    /// Именно он попадает в квитанцию хранителя.
    pub origin_custody_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
    pub recipient_node_id: String,
    pub transfer_id: [u8; 16],
    pub chunk_index: u64,
    pub chunk_offset: u32,
    pub expires_at_ms: i64,
    /// Байты кадра B1 целиком (магик APUF) - то, что уходит получателю.
    pub frame: Vec<u8>,
}

impl FileCustodyUnit {
    /// Собрать единицу хранения из частей кадра и самих байтов кадра B1.
    ///
    /// Здесь же вся проверка формы: идентификаторы должны быть каноническими
    /// (`pk_` + hex), ключ автора - 32 байта hex, кадр - разбираться как
    /// кусок B1 и укладываться в предел кадра. Возвращаем `None`, если
    /// что-то не так: битый кадр хранить нечего.
    pub fn from_parts(
        origin_node_id: &str,
        origin_custody_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
        recipient_node_id: &str,
        expires_at_ms: i64,
        frame: Vec<u8>,
    ) -> Option<Self> {
        if !is_canonical_node_id(origin_node_id)
            || !is_canonical_node_id(recipient_node_id)
            || origin_node_id == recipient_node_id
        {
            return None;
        }
        if frame.is_empty() || frame.len() > MAX_FILE_FRAME_BYTES {
            return None;
        }
        if expires_at_ms <= 0 {
            return None;
        }
        let chunk = match FileFrameV1::decode(&frame) {
            Ok(FileFrameV1::ChunkData(chunk)) => chunk,
            _ => return None,
        };
        let unit_id = custody_unit_id(
            origin_node_id,
            recipient_node_id,
            &chunk.transfer_id,
            chunk.chunk_index,
            chunk.chunk_offset,
        );
        Some(Self {
            unit_id,
            origin_node_id: origin_node_id.to_owned(),
            origin_custody_public_key,
            recipient_node_id: recipient_node_id.to_owned(),
            transfer_id: chunk.transfer_id,
            chunk_index: chunk.chunk_index,
            chunk_offset: chunk.chunk_offset,
            expires_at_ms,
            frame,
        })
    }

    /// Совпадает ли `unit_id` с содержимым кадра. Принимающая сторона
    /// проверяет это перед показом: так подменённый или перепутанный кадр не
    /// проходит дальше.
    pub fn frame_matches_id(&self) -> bool {
        match FileFrameV1::decode(&self.frame) {
            Ok(FileFrameV1::ChunkData(chunk)) => {
                chunk.transfer_id == self.transfer_id
                    && chunk.chunk_index == self.chunk_index
                    && chunk.chunk_offset == self.chunk_offset
                    && custody_unit_id(
                        &self.origin_node_id,
                        &self.recipient_node_id,
                        &chunk.transfer_id,
                        chunk.chunk_index,
                        chunk.chunk_offset,
                    ) == self.unit_id
            }
            _ => false,
        }
    }
}

/// Идентификатор единицы хранения: одинаков у автора, хранителя и получателя.
pub fn custody_unit_id(
    origin_node_id: &str,
    recipient_node_id: &str,
    transfer_id: &[u8; 16],
    chunk_index: u64,
    chunk_offset: u32,
) -> String {
    let mut hasher = Sha256::new();
    hasher.update(UNIT_ID_DOMAIN);
    hasher.update(origin_node_id.as_bytes());
    hasher.update(b"\0");
    hasher.update(recipient_node_id.as_bytes());
    hasher.update(b"\0");
    hasher.update(transfer_id);
    hasher.update(chunk_index.to_be_bytes());
    hasher.update(chunk_offset.to_be_bytes());
    let digest = hasher.finalize();
    bytes_to_hex(&digest[..FILE_CUSTODY_UNIT_ID_BYTES])
}

/// Имя узла для ключа кастодии: `pk_` + sha256 ключа.
///
/// По этому имени квитанция связывается с ключом (проверка сама сверяет
/// пару), поэтому хранитель и автор обязаны называть себя именно так.
pub fn custody_origin_node_id(public_key: &[u8; ED25519_PUBLIC_KEY_SIZE]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(public_key);
    format!("pk_{}", bytes_to_hex(&hasher.finalize()))
}

// ── кадры ──────────────────────────────────────────────────────────────────

pub fn offer_payload(unit: &FileCustodyUnit) -> String {
    format!(
        "{}|{}|{}|{}|{}|{}",
        FILE_CUSTODY_OFFER_PREFIX,
        unit.origin_node_id,
        bytes_to_hex(&unit.origin_custody_public_key),
        unit.recipient_node_id,
        unit.expires_at_ms,
        base64::engine::general_purpose::STANDARD.encode(&unit.frame)
    )
}

pub fn parse_offer_frame(payload: &str) -> Option<FileCustodyUnit> {
    let rest = payload.strip_prefix(FILE_CUSTODY_OFFER_PREFIX)?;
    let rest = rest.strip_prefix('|')?;
    let mut fields = rest.split('|');
    let origin_node_id = fields.next()?;
    let origin_key_hex = fields.next()?;
    let recipient_node_id = fields.next()?;
    let expires_at_ms = fields.next()?.parse::<i64>().ok()?;
    let frame_b64 = fields.next()?;
    if fields.next().is_some() {
        return None;
    }
    let origin_custody_public_key = parse_hex32(origin_key_hex)?;
    let frame = base64::engine::general_purpose::STANDARD
        .decode(frame_b64)
        .ok()?;
    FileCustodyUnit::from_parts(
        origin_node_id,
        origin_custody_public_key,
        recipient_node_id,
        expires_at_ms,
        frame,
    )
}

pub fn ack_payload(unit_id: &str, ack: FileCustodyAck, receipt: Option<&[u8]>) -> String {
    match (ack, receipt) {
        (FileCustodyAck::Stored, Some(receipt)) => format!(
            "{}|{}|ok|{}",
            FILE_CUSTODY_ACK_PREFIX,
            unit_id,
            base64::engine::general_purpose::STANDARD.encode(receipt)
        ),
        (FileCustodyAck::Stored, None) => {
            // Квитанции нет - подтверждать нечего, это отказ.
            format!("{}|{}|refused", FILE_CUSTODY_ACK_PREFIX, unit_id)
        }
        (other, _) => format!("{}|{}|{}", FILE_CUSTODY_ACK_PREFIX, unit_id, other.as_str()),
    }
}

pub fn parse_ack_frame(payload: &str) -> Option<(String, FileCustodyAck, Option<Vec<u8>>)> {
    let rest = payload.strip_prefix(FILE_CUSTODY_ACK_PREFIX)?;
    let rest = rest.strip_prefix('|')?;
    let mut fields = rest.split('|');
    let unit_id = fields.next()?;
    if unit_id.len() != FILE_CUSTODY_UNIT_ID_BYTES * 2 || !is_lower_hex(unit_id) {
        return None;
    }
    let ack = FileCustodyAck::parse(fields.next()?)?;
    let receipt = match fields.next() {
        Some(text) => Some(
            base64::engine::general_purpose::STANDARD
                .decode(text)
                .ok()?,
        ),
        None => None,
    };
    if fields.next().is_some() {
        return None;
    }
    if ack == FileCustodyAck::Stored && receipt.is_none() {
        return None;
    }
    Some((unit_id.to_owned(), ack, receipt))
}

pub fn deliver_payload(
    origin_node_id: &str,
    recipient_node_id: &str,
    unit_id: &str,
    frame: &[u8],
) -> String {
    format!(
        "{}|{}|{}|{}|{}",
        FILE_CUSTODY_DELIVER_PREFIX,
        origin_node_id,
        recipient_node_id,
        unit_id,
        base64::engine::general_purpose::STANDARD.encode(frame)
    )
}

/// Разбор кадра доставки: автор, получатель, идентификатор единицы и сам кусок.
///
/// Идентификатор здесь пересчитывается из полей кадра и сверяется с тем, что
/// пришёл в кадре: перепутанный или подменённый кусок дальше не проходит.
pub fn parse_deliver_frame(payload: &str) -> Option<(String, String, String, FileChunkDataV1)> {
    let rest = payload.strip_prefix(FILE_CUSTODY_DELIVER_PREFIX)?;
    let rest = rest.strip_prefix('|')?;
    let mut fields = rest.split('|');
    let origin_node_id = fields.next()?;
    let recipient_node_id = fields.next()?;
    let unit_id = fields.next()?;
    let frame_b64 = fields.next()?;
    if fields.next().is_some() {
        return None;
    }
    if !is_canonical_node_id(origin_node_id)
        || !is_canonical_node_id(recipient_node_id)
        || unit_id.len() != FILE_CUSTODY_UNIT_ID_BYTES * 2
        || !is_lower_hex(unit_id)
    {
        return None;
    }
    let frame = base64::engine::general_purpose::STANDARD
        .decode(frame_b64)
        .ok()?;
    let chunk = match FileFrameV1::decode(&frame) {
        Ok(FileFrameV1::ChunkData(chunk)) => chunk,
        _ => return None,
    };
    let expected = custody_unit_id(
        origin_node_id,
        recipient_node_id,
        &chunk.transfer_id,
        chunk.chunk_index,
        chunk.chunk_offset,
    );
    if expected != unit_id {
        return None;
    }
    Some((
        origin_node_id.to_owned(),
        recipient_node_id.to_owned(),
        unit_id.to_owned(),
        chunk,
    ))
}

pub fn drop_payload(unit_id: &str) -> String {
    format!("{}|{}", FILE_CUSTODY_DROP_PREFIX, unit_id)
}

pub fn parse_drop_frame(payload: &str) -> Option<String> {
    let rest = payload.strip_prefix(FILE_CUSTODY_DROP_PREFIX)?;
    let unit_id = rest.strip_prefix('|')?;
    if unit_id.len() != FILE_CUSTODY_UNIT_ID_BYTES * 2 || !is_lower_hex(unit_id) {
        return None;
    }
    Some(unit_id.to_owned())
}

// ── хранилище того, что нам предложили ─────────────────────────────────────

/// Ограниченное хранилище чужих кусков в памяти процесса.
///
/// Сами байты лежат на диске в `network::file_custody` (с квитанцией); здесь
/// только то, что нужно потоку presence: кому отдавать, что уже отдано и
/// что очередь на отправку. Куски, которые не удалось отдать, остаются -
/// следующий круг попробует снова.
#[derive(Default)]
pub struct FileCustodyHold {
    inner: Mutex<HoldInner>,
}

#[derive(Default)]
struct HoldInner {
    held: HashMap<String, FileCustodyUnit>,
    order: VecDeque<String>,
    bytes: usize,
    outbound: VecDeque<(String, Vec<u8>)>,
    delivered: HashSet<String>,
    delivered_order: VecDeque<String>,
}

impl FileCustodyHold {
    pub fn new() -> Self {
        Self::default()
    }

    /// Взять кусок на хранение. `false` - не взяли (уже есть, нет места).
    pub fn hold(&self, unit: FileCustodyUnit) -> bool {
        let mut inner = self.inner.lock().unwrap();
        if inner.held.contains_key(&unit.unit_id) {
            return false;
        }
        if inner.held.len() >= MAX_FILE_CUSTODY_HELD_UNITS
            || inner.bytes.saturating_add(unit.frame.len()) > MAX_FILE_CUSTODY_HELD_BYTES
        {
            return false;
        }
        inner.bytes += unit.frame.len();
        inner.order.push_back(unit.unit_id.clone());
        inner.held.insert(unit.unit_id.clone(), unit);
        true
    }

    pub fn contains(&self, unit_id: &str) -> bool {
        self.inner.lock().unwrap().held.contains_key(unit_id)
    }

    pub fn held_len(&self) -> usize {
        self.inner.lock().unwrap().held.len()
    }

    pub fn held_bytes(&self) -> usize {
        self.inner.lock().unwrap().bytes
    }

    pub fn remove(&self, unit_id: &str) -> bool {
        let mut inner = self.inner.lock().unwrap();
        let Some(unit) = inner.held.remove(unit_id) else {
            return false;
        };
        inner.bytes = inner.bytes.saturating_sub(unit.frame.len());
        inner.order.retain(|id| id != unit_id);
        true
    }

    /// Убрать протухшее. Возвращает, сколько убрали.
    pub fn purge_expired(&self, now_ms: i64) -> usize {
        let mut inner = self.inner.lock().unwrap();
        let expired: Vec<String> = inner
            .held
            .values()
            .filter(|unit| unit.expires_at_ms <= now_ms)
            .map(|unit| unit.unit_id.clone())
            .collect();
        let mut removed = 0usize;
        for unit_id in expired {
            if let Some(unit) = inner.held.remove(&unit_id) {
                inner.bytes = inner.bytes.saturating_sub(unit.frame.len());
                inner.order.retain(|id| id != &unit_id);
                removed += 1;
            }
        }
        removed
    }

    /// Получатели, для которых у нас что-то лежит (по одному разу).
    pub fn recipients(&self) -> Vec<String> {
        let inner = self.inner.lock().unwrap();
        let mut seen: Vec<String> = Vec::new();
        for unit_id in inner.order.iter() {
            if let Some(unit) = inner.held.get(unit_id) {
                if !seen.contains(&unit.recipient_node_id) {
                    seen.push(unit.recipient_node_id.clone());
                }
            }
        }
        seen
    }

    /// Что лежит для этого получателя (не больше `limit` штук). НЕ убирает:
    /// убираем только после успешной отправки - иначе обрыв сети стоил бы
    /// куска.
    pub fn pending_for_recipient(&self, recipient: &str, limit: usize) -> Vec<FileCustodyUnit> {
        let inner = self.inner.lock().unwrap();
        let mut out = Vec::new();
        for unit_id in inner.order.iter() {
            if out.len() >= limit {
                break;
            }
            if let Some(unit) = inner.held.get(unit_id) {
                if unit.recipient_node_id == recipient {
                    out.push(unit.clone());
                }
            }
        }
        out
    }

    /// Поставить кадр в очередь на отправку (ack, drop). `false` - очередь полна.
    pub fn queue_outbound(&self, peer_id: &str, payload: String) -> bool {
        let mut inner = self.inner.lock().unwrap();
        if inner.outbound.len() >= MAX_FILE_CUSTODY_QUEUED_OUTBOUND {
            return false;
        }
        inner.outbound.push_back((peer_id.to_owned(), payload.into_bytes()));
        true
    }

    pub fn take_outbound(&self, limit: usize) -> Vec<(String, Vec<u8>)> {
        let mut inner = self.inner.lock().unwrap();
        let mut out = Vec::with_capacity(limit.min(inner.outbound.len()));
        for _ in 0..limit {
            match inner.outbound.pop_front() {
                Some(item) => out.push(item),
                None => break,
            }
        }
        out
    }

    pub fn outbound_len(&self) -> usize {
        self.inner.lock().unwrap().outbound.len()
    }

    /// Отметить, что получателю этот кусок уже отдан. `false` - отдавали
    /// раньше, повторно показывать не нужно.
    pub fn note_delivered(&self, unit_id: &str) -> bool {
        let mut inner = self.inner.lock().unwrap();
        if !inner.delivered.insert(unit_id.to_owned()) {
            return false;
        }
        inner.delivered_order.push_back(unit_id.to_owned());
        while inner.delivered_order.len() > MAX_FILE_CUSTODY_DELIVERED_MEMORY {
            if let Some(old) = inner.delivered_order.pop_front() {
                inner.delivered.remove(&old);
            }
        }
        true
    }
}

// ── очередь того, что мы сами пристраиваем ─────────────────────────────────

/// Куски, для которых мы ищем хранителей (сторона автора).
#[derive(Default)]
pub struct FileCustodyOffers {
    inner: Mutex<OffersInner>,
}

#[derive(Default)]
struct OffersInner {
    pending: HashMap<String, FileCustodyUnit>,
    order: VecDeque<String>,
    asked: HashMap<String, Vec<String>>,
    accepted: HashMap<String, Vec<String>>,
    receipts: HashMap<String, Vec<Vec<u8>>>,
}

impl FileCustodyOffers {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn enqueue(&self, unit: FileCustodyUnit) -> bool {
        let mut inner = self.inner.lock().unwrap();
        if inner.pending.contains_key(&unit.unit_id) {
            return false;
        }
        if inner.pending.len() >= MAX_FILE_CUSTODY_TRACKED_UNITS {
            return false;
        }
        inner.order.push_back(unit.unit_id.clone());
        inner.pending.insert(unit.unit_id.clone(), unit);
        true
    }

    pub fn pending_len(&self) -> usize {
        self.inner.lock().unwrap().pending.len()
    }

    /// Кому и что предложить в этом круге: (unit_id, сосед, кадр).
    ///
    /// Соседа запоминаем - второй раз одному и тому же не предлагаем. Когда
    /// набралось достаточно копий, предложения по этому куску прекращаем.
    pub fn next_offers(&self, candidates: &[String]) -> Vec<(String, String, String)> {
        let mut inner = self.inner.lock().unwrap();
        let mut out = Vec::new();
        if candidates.is_empty() {
            return out;
        }
        let ids: Vec<String> = inner.order.iter().cloned().collect();
        for unit_id in ids {
            if out.len() >= MAX_FILE_CUSTODY_OFFERS_PER_TICK {
                break;
            }
            if inner.accepted.get(&unit_id).map_or(0, |list| list.len())
                >= MAX_FILE_CUSTODY_PEERS
            {
                continue;
            }
            let Some(unit) = inner.pending.get(&unit_id) else {
                continue;
            };
            // Заимствование `unit` заканчивается здесь: дальше меняем
            // `inner.asked`, и одновременная ссылка на `inner.pending` была бы
            // ошибкой компиляции.
            let payload = offer_payload(unit);
            let origin_node_id = unit.origin_node_id.clone();
            let peer = candidates
                .iter()
                .find(|peer| {
                    *peer != &origin_node_id
                        && !inner
                            .asked
                            .get(&unit_id)
                            .map_or(false, |list| list.contains(peer))
                })
                .cloned();
            let Some(peer) = peer else {
                continue;
            };
            inner.asked.entry(unit_id.clone()).or_default().push(peer.clone());
            out.push((unit_id, peer, payload));
        }
        out
    }

    /// Запомнить ответ хранителя. `true` - копий уже достаточно.
    ///
    /// Список подтверждений намеренно не растёт дальше цели: цель - две
    /// копии, а поток подтверждений от чужого узла не должен копить память.
    pub fn note_ack(
        &self,
        unit_id: &str,
        ack: FileCustodyAck,
        receipt: Option<Vec<u8>>,
    ) -> bool {
        let mut inner = self.inner.lock().unwrap();
        if !inner.pending.contains_key(unit_id) {
            return false;
        }
        if ack == FileCustodyAck::Stored {
            // Заимствования идут по очереди: держать два входа в одну и ту же
            // карту разом нельзя (ошибка E0499 - ровно на этом и споткнулись).
            if let Some(receipt) = receipt {
                // Квитанции храним для показа владельцу и для плана
                // репликации; больше двух копий нам не нужно.
                let list = inner.receipts.entry(unit_id.to_owned()).or_default();
                if list.len() < MAX_FILE_CUSTODY_PEERS {
                    list.push(receipt);
                }
            }
            let accepted = inner.accepted.entry(unit_id.to_owned()).or_default();
            if accepted.len() < MAX_FILE_CUSTODY_PEERS {
                accepted.push("stored".to_owned());
            }
        }
        inner
            .accepted
            .get(unit_id)
            .map_or(0, |list| list.len())
            >= MAX_FILE_CUSTODY_PEERS
    }

    pub fn accepted_for(&self, unit_id: &str) -> usize {
        self.inner
            .lock()
            .unwrap()
            .accepted
            .get(unit_id)
            .map_or(0, |list| list.len())
    }

    pub fn receipts_for(&self, unit_id: &str) -> Vec<Vec<u8>> {
        self.inner
            .lock()
            .unwrap()
            .receipts
            .get(unit_id)
            .cloned()
            .unwrap_or_default()
    }

    /// Убрать кусок из очереди (отдали напрямую или пришёл `drop`).
    pub fn remove(&self, unit_id: &str) -> bool {
        let mut inner = self.inner.lock().unwrap();
        let existed = inner.pending.remove(unit_id).is_some();
        inner.order.retain(|id| id != unit_id);
        inner.asked.remove(unit_id);
        inner.accepted.remove(unit_id);
        inner.receipts.remove(unit_id);
        existed
    }

    pub fn purge_expired(&self, now_ms: i64) -> usize {
        let mut inner = self.inner.lock().unwrap();
        let expired: Vec<String> = inner
            .pending
            .values()
            .filter(|unit| unit.expires_at_ms <= now_ms)
            .map(|unit| unit.unit_id.clone())
            .collect();
        let mut removed = 0usize;
        for unit_id in expired {
            if inner.pending.remove(&unit_id).is_some() {
                removed += 1;
            }
            inner.order.retain(|id| id != &unit_id);
            inner.asked.remove(&unit_id);
            inner.accepted.remove(&unit_id);
            inner.receipts.remove(&unit_id);
        }
        removed
    }
}

// ── мелочи ─────────────────────────────────────────────────────────────────

fn is_canonical_node_id(node_id: &str) -> bool {
    (node_id.len() == MIN_NODE_ID_BYTES || node_id.len() == MAX_NODE_ID_BYTES)
        && node_id.starts_with("pk_")
        && is_lower_hex(&node_id[3..])
}

fn is_lower_hex(text: &str) -> bool {
    !text.is_empty()
        && text
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn parse_hex32(text: &str) -> Option<[u8; ED25519_PUBLIC_KEY_SIZE]> {
    if text.len() != ED25519_PUBLIC_KEY_SIZE * 2 || !is_lower_hex(text) {
        return None;
    }
    let mut out = [0u8; ED25519_PUBLIC_KEY_SIZE];
    for (index, byte) in out.iter_mut().enumerate() {
        let high = hex_value(text.as_bytes()[index * 2])?;
        let low = hex_value(text.as_bytes()[index * 2 + 1])?;
        *byte = (high << 4) | low;
    }
    Some(out)
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        _ => None,
    }
}

fn bytes_to_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::file_wire::FileChunkDataV1;

    /// Современное имя узла из ключа (как в жизни: `pk_` + sha256 ключа).
    fn modern(node_id_seed: u8) -> (String, [u8; ED25519_PUBLIC_KEY_SIZE]) {
        let key = [node_id_seed; ED25519_PUBLIC_KEY_SIZE];
        let mut hasher = Sha256::new();
        hasher.update(key);
        let digest = hasher.finalize();
        (format!("pk_{}", bytes_to_hex(&digest)), key)
    }

    fn frame(origin_seed: u8, index: u64, offset: u32) -> Vec<u8> {
        let ciphertext = vec![origin_seed; 64];
        FileFrameV1::ChunkData(FileChunkDataV1 {
            transfer_id: [origin_seed; 16],
            chunk_index: index,
            chunk_offset: offset,
            ciphertext_chunk_len: ciphertext.len() as u32,
            ciphertext,
        })
        .encode()
        .unwrap()
    }

    fn unit(origin_seed: u8, recipient_seed: u8, index: u64) -> FileCustodyUnit {
        let (origin_id, origin_key) = modern(origin_seed);
        let (recipient_id, _) = modern(recipient_seed);
        FileCustodyUnit::from_parts(
            &origin_id,
            origin_key,
            &recipient_id,
            1_700_000_000_000,
            frame(origin_seed, index, 0),
        )
        .unwrap()
    }

    #[test]
    fn offer_frame_round_trip() {
        let unit = unit(1, 2, 7);
        let payload = offer_payload(&unit);
        let parsed = parse_offer_frame(&payload).unwrap();
        assert_eq!(parsed, unit);
        assert_eq!(parsed.unit_id.len(), FILE_CUSTODY_UNIT_ID_BYTES * 2);
        assert!(parsed.frame_matches_id());
    }

    #[test]
    fn offer_frame_rejects_broken_input() {
        let unit = unit(1, 2, 7);
        let payload = offer_payload(&unit);
        // Чужой кадр, лишнее поле, битый base64, несовпадающий ключ.
        assert!(parse_offer_frame("cust|offer|x").is_none());
        assert!(parse_offer_frame(&(payload.clone() + "|tail")).is_none());
        let mut bad = payload.clone();
        bad.push_str("|!!!not-base64!!!");
        assert!(parse_offer_frame(&bad).is_none());
        // Ключ кастодии обязан быть 32 байтами hex: тут он не ключ вовсе.
        let bad_key = format!(
            "{}|{}|{}|{}|{}|{}",
            FILE_CUSTODY_OFFER_PREFIX,
            unit.origin_node_id,
            "not-a-key",
            unit.recipient_node_id,
            unit.expires_at_ms,
            base64::engine::general_purpose::STANDARD.encode(&unit.frame)
        );
        assert!(parse_offer_frame(&bad_key).is_none());
        // Короткий ключ (меньше 32 байт) тоже не проходит.
        let short_key = format!(
            "{}|{}|{}|{}|{}|{}",
            FILE_CUSTODY_OFFER_PREFIX,
            unit.origin_node_id,
            "00ff",
            unit.recipient_node_id,
            unit.expires_at_ms,
            base64::engine::general_purpose::STANDARD.encode(&unit.frame)
        );
        assert!(parse_offer_frame(&short_key).is_none());
        // Имя автора должно быть каноническим (pk_ + строчный hex).
        let bad_name = format!(
            "{}|{}|{}|{}|{}|{}",
            FILE_CUSTODY_OFFER_PREFIX,
            "PK_UPPER",
            bytes_to_hex(&unit.origin_custody_public_key),
            unit.recipient_node_id,
            unit.expires_at_ms,
            base64::engine::general_purpose::STANDARD.encode(&unit.frame)
        );
        assert!(parse_offer_frame(&bad_name).is_none());
    }

    #[test]
    fn ack_and_drop_round_trip() {
        let unit = unit(1, 2, 3);
        let receipt = vec![0xAB; 200];
        let payload = ack_payload(&unit.unit_id, FileCustodyAck::Stored, Some(&receipt));
        let (unit_id, ack, decoded) = parse_ack_frame(&payload).unwrap();
        assert_eq!(unit_id, unit.unit_id);
        assert_eq!(ack, FileCustodyAck::Stored);
        assert_eq!(decoded.unwrap(), receipt);

        let refused = ack_payload(&unit.unit_id, FileCustodyAck::Refused, None);
        let (_, ack, receipt) = parse_ack_frame(&refused).unwrap();
        assert_eq!(ack, FileCustodyAck::Refused);
        assert!(receipt.is_none());

        // «ok» без квитанции - не подтверждение, а мусор.
        assert!(parse_ack_frame(&format!("{}|{}|ok", FILE_CUSTODY_ACK_PREFIX, unit.unit_id)).is_none());
        assert_eq!(
            parse_drop_frame(&drop_payload(&unit.unit_id)).unwrap(),
            unit.unit_id
        );
        assert!(parse_drop_frame("fcust|drop|short").is_none());
    }

    #[test]
    fn deliver_frame_round_trip() {
        let unit = unit(1, 2, 5);
        let payload = deliver_payload(
            &unit.origin_node_id,
            &unit.recipient_node_id,
            &unit.unit_id,
            &unit.frame,
        );
        let (origin, recipient, unit_id, chunk) = parse_deliver_frame(&payload).unwrap();
        assert_eq!(origin, unit.origin_node_id);
        assert_eq!(recipient, unit.recipient_node_id);
        assert_eq!(unit_id, unit.unit_id);
        assert_eq!(bytes_to_hex(&chunk.transfer_id), bytes_to_hex(&unit.transfer_id));
        assert_eq!(chunk.chunk_index, unit.chunk_index);
        assert!(parse_deliver_frame("fcust|deliver|pk_z|pk_z|zz|zz").is_none());

        // Кадр от другого автора с чужим unit_id не проходит: id считается из полей.
        let (other_origin, _) = modern(9);
        let forged = deliver_payload(
            &other_origin,
            &unit.recipient_node_id,
            &unit.unit_id,
            &unit.frame,
        );
        assert!(parse_deliver_frame(&forged).is_none());
    }

    #[test]
    fn hold_keeps_limits_and_gives_back_on_delivery() {
        let hold = FileCustodyHold::new();
        assert!(hold.hold(unit(1, 2, 1)));
        assert!(!hold.hold(unit(1, 2, 1)), "один и тот же кусок дважды не берём");
        assert!(hold.hold(unit(1, 2, 2)));
        assert_eq!(hold.held_len(), 2);
        assert!(hold.held_bytes() > 0);

        let (recipient, _) = modern(2);
        assert_eq!(hold.recipients(), vec![recipient.clone()]);
        let pending = hold.pending_for_recipient(&recipient, 8);
        assert_eq!(pending.len(), 2);
        // pending не убирает: тот же список вернётся и в следующий круг.
        assert_eq!(hold.pending_for_recipient(&recipient, 8).len(), 2);
        assert!(hold.remove(&pending[0].unit_id));
        assert_eq!(hold.held_len(), 1);

        // Отданное помним: два хранителя не покажут один кусок дважды.
        assert!(hold.note_delivered(&pending[0].unit_id));
        assert!(!hold.note_delivered(&pending[0].unit_id));
    }

    #[test]
    fn hold_purges_expired_and_queues_outbound() {
        let hold = FileCustodyHold::new();
        let mut soon = unit(1, 2, 1);
        soon.expires_at_ms = 1_000;
        assert!(hold.hold(soon.clone()));
        assert_eq!(hold.purge_expired(2_000), 1);
        assert_eq!(hold.held_len(), 0);

        assert!(hold.queue_outbound("pk_x", ack_payload(&soon.unit_id, FileCustodyAck::Refused, None)));
        let out = hold.take_outbound(4);
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].0, "pk_x");
        assert!(hold.take_outbound(4).is_empty());
    }

    #[test]
    fn offers_pick_peers_once_and_stop_after_two_receipts() {
        let offers = FileCustodyOffers::new();
        let unit = unit(1, 2, 4);
        assert!(offers.enqueue(unit.clone()));
        assert!(!offers.enqueue(unit.clone()), "повторно не ставим");
        assert_eq!(offers.pending_len(), 1);

        let peer_one = "pk_one".to_owned();
        let peer_two = "pk_two".to_owned();
        let candidates = vec![peer_one.clone(), peer_two.clone()];

        let first = offers.next_offers(&candidates);
        assert_eq!(first.len(), 1, "за круг отправляем одно предложение");
        assert_eq!(first[0].0, unit.unit_id);
        assert_eq!(first[0].1, peer_one);

        let second = offers.next_offers(&candidates);
        assert_eq!(second.len(), 1);
        assert_eq!(second[0].1, peer_two, "второму соседу предлагаем в другой раз");

        // Больше предлагать некому: третий круг молчит.
        assert!(offers.next_offers(&candidates).is_empty());

        assert!(!offers.note_ack(&unit.unit_id, FileCustodyAck::Stored, Some(vec![1; 16])));
        assert!(offers.note_ack(&unit.unit_id, FileCustodyAck::Stored, Some(vec![2; 16])));
        assert_eq!(offers.accepted_for(&unit.unit_id), 2);
        assert_eq!(offers.receipts_for(&unit.unit_id).len(), 2);
        assert!(offers.next_offers(&candidates).is_empty(), "копий достаточно");

        assert!(offers.remove(&unit.unit_id));
        assert_eq!(offers.pending_len(), 0);
        assert_eq!(offers.accepted_for(&unit.unit_id), 0);
    }

    #[test]
    fn offers_ignore_unknown_acks_and_expire() {
        let offers = FileCustodyOffers::new();
        let unit = unit(3, 4, 1);
        offers.enqueue(unit);
        // Ответ про кусок, которого мы не пристраивали, ничего не меняет.
        let unknown = "00".repeat(FILE_CUSTODY_UNIT_ID_BYTES);
        assert!(!offers.note_ack(&unknown, FileCustodyAck::Stored, None));
        assert_eq!(offers.pending_len(), 1);
        assert_eq!(offers.purge_expired(1_800_000_000_000), 1);
        assert_eq!(offers.pending_len(), 0);
    }

    #[test]
    fn unit_id_differs_per_chunk() {
        let first = unit(1, 2, 1);
        let second = unit(1, 2, 2);
        assert_ne!(first.unit_id, second.unit_id);
        let (origin_id, origin_key) = modern(1);
        let (recipient_id, _) = modern(2);
        let same = FileCustodyUnit::from_parts(
            &origin_id,
            origin_key,
            &recipient_id,
            1_700_000_000_000,
            frame(1, 1, 0),
        )
        .unwrap();
        assert_eq!(same.unit_id, first.unit_id, "id считается из полей, а не случайно");
    }
}
