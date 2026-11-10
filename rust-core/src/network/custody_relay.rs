//! # K5-1: кастодия у соседей - сообщение ждёт получателя у двух соседей
//!
//! Задача (этап K5 из `docs/CORE_ROADMAP.md`): доставка не должна зависеть от
//! брокера. Сегодня сообщение офлайн-получателю уходит в общий топик MQTT и
//! ждёт там (M3/M8: relay-очередь и durable custody) - если брокер недоступен
//! или сообщение потерялось на его стороне, доставки нет.
//!
//! Что делает этот модуль: отправитель, который не смог отдать сообщение
//! напрямую, отдаёт копию **соседям по прямому каналу** - тем, кого видит
//! сам (mDNS, presence, найденный адрес). Копию держат до двух соседей; тот
//! из них, кто первым увидит получателя в сети, отдаёт сообщение ему лично.
//! Конверт - тот же самый (`relay|…`, см. `network::wire`), что ходит через
//! брокер: формат не меняется, старые сборки получают то же сообщение.
//!
//! Кадры прямого канала (текст, первое поле не `pk_…`, поэтому старые сборки
//! отбрасывают их тем же стражем, что и любой чужой кадр - правило N ↔ N-1):
//!
//! - `cust|offer|<конверт>` - «подержи это до появления получателя»;
//! - `cust|ack|<msg_id>|ok|full|refused` - ответ хранителя;
//! - `cust|deliver|<конверт>` - хранитель отдаёт сообщение получателю;
//! - `cust|drop|<msg_id>` - копию можно удалить (доставлено).
//!
//! Ограничения - как у всего в ядре: не больше
//! [`MAX_HELD_MESSAGES`] чужих сообщений, срок годности берётся из конверта,
//! на получателя - [`MAX_HOLD_PER_RECIPIENT`], отправляем копию не больше
//! [`MAX_CUSTODY_PEERS`] соседям. Уже отданные сообщения помним
//! ([`MAX_DELIVERED_MEMORY`]), чтобы два хранителя не показали получателю
//! одно и то же сообщение дважды.

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};
use std::sync::Mutex;

use crate::network::wire::{self, MeshEnvelope};

/// Первое поле всех кадров кастодии.
pub const CUSTODY_TAG: &str = "cust";

pub const CUSTODY_OFFER_PREFIX: &str = "cust|offer|";
pub const CUSTODY_ACK_PREFIX: &str = "cust|ack|";
pub const CUSTODY_DELIVER_PREFIX: &str = "cust|deliver|";
pub const CUSTODY_DROP_PREFIX: &str = "cust|drop|";

/// Сколько чужих сообщений держим одновременно.
pub const MAX_HELD_MESSAGES: usize = 64;

/// Сколько чужих сообщений держим для одного получателя.
pub const MAX_HOLD_PER_RECIPIENT: usize = 16;

/// Скольким соседям отдаём копию (план K5: двое).
pub const MAX_CUSTODY_PEERS: usize = 2;

/// Сколько предложений отправляем за один круг (тик потока presence).
pub const MAX_OFFERS_PER_TICK: usize = 4;

/// Сколько сообщений отдаём получателю за один круг.
pub const MAX_DELIVERIES_PER_TICK: usize = 8;

/// Сколько идентификаторов уже отданных сообщений помним.
pub const MAX_DELIVERED_MEMORY: usize = 512;
/// An EventBus admission is not a durable inbox commit: suppress only a burst.
const DELIVERY_RETRY_AFTER: Duration = Duration::from_secs(5);
/// Retry an unconfirmed held copy quickly, then back off to limit battery and
/// traffic. The separate attempt clock does not suppress incoming events.
const HELD_RETRY_INITIAL: Duration = Duration::from_secs(10);
const HELD_RETRY_MAX_SECS: u64 = 5 * 60;

#[derive(Clone, Copy)]
struct HeldAttempt {
    last: Instant,
    attempts: u8,
}

impl HeldAttempt {
    fn due(self, now: Instant) -> bool {
        let shift = self.attempts.saturating_sub(1).min(8) as u32;
        let seconds = HELD_RETRY_INITIAL.as_secs().saturating_mul(1u64 << shift).min(HELD_RETRY_MAX_SECS);
        now.saturating_duration_since(self.last) >= Duration::from_secs(seconds)
    }
}

// ═══════════════════════════════════════════════════════════════════
// КАДРЫ
// ═══════════════════════════════════════════════════════════════════

/// Ответ хранителя на предложение.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CustodyAck {
    /// Взял копию.
    Accepted,
    /// Мест нет (или слишком много копий для этого получателя).
    Full,
    /// Не беру: не контакт, кастодия выключена, конверт не разобрался.
    Refused,
}

impl CustodyAck {
    pub fn as_str(self) -> &'static str {
        match self {
            CustodyAck::Accepted => "ok",
            CustodyAck::Full => "full",
            CustodyAck::Refused => "refused",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        match text.trim() {
            "ok" => Some(CustodyAck::Accepted),
            "full" => Some(CustodyAck::Full),
            "refused" => Some(CustodyAck::Refused),
            _ => None,
        }
    }
}

/// Разобранный конверт, который держит хранитель.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HeldEnvelope {
    pub msg_id: String,
    pub recipient: String,
    pub origin: String,
    /// Конверт целиком, в том виде, в каком его понимает получатель.
    pub envelope: String,
    pub expires_at_ms: i64,
}

/// Разобрать конверт (`relay|…`) в запись хранителя.
///
/// `now_ms` нужен, чтобы сразу отбросить просроченное: держать чужое
/// сообщение, у которого вышел срок, смысла нет.
pub fn held_from_envelope(envelope: &str, now_ms: i64) -> Option<HeldEnvelope> {
    match wire::parse(envelope)? {
        MeshEnvelope::Relay {
            msg_id,
            recipient,
            origin,
            ttl_secs,
            ..
        } => {
            if msg_id.is_empty() || recipient.is_empty() || origin.is_empty() {
                return None;
            }
            let expires_at_ms = now_ms.saturating_add((ttl_secs as i64).saturating_mul(1_000));
            if expires_at_ms <= now_ms {
                return None;
            }
            Some(HeldEnvelope {
                msg_id,
                recipient,
                origin,
                envelope: envelope.to_string(),
                expires_at_ms,
            })
        }
        _ => None,
    }
}

pub fn offer_payload(envelope: &str) -> String {
    format!("{}{}", CUSTODY_OFFER_PREFIX, envelope)
}

pub fn deliver_payload(envelope: &str) -> String {
    format!("{}{}", CUSTODY_DELIVER_PREFIX, envelope)
}

pub fn ack_payload(msg_id: &str, ack: CustodyAck) -> String {
    format!("{}{}|{}", CUSTODY_ACK_PREFIX, msg_id, ack.as_str())
}

pub fn drop_payload(msg_id: &str) -> String {
    format!("{}{}", CUSTODY_DROP_PREFIX, msg_id)
}

/// Конверт из кадра `offer`/`deliver`. `None` - не наш кадр или битый конверт.
pub fn parse_envelope_frame(payload: &str, prefix: &str, now_ms: i64) -> Option<HeldEnvelope> {
    let envelope = payload.strip_prefix(prefix)?;
    if envelope.is_empty() {
        return None;
    }
    held_from_envelope(envelope, now_ms)
}

/// Данные для показа сообщения получателю: `(msg_id, origin, chat_scope, text)`.
///
/// Нужен, когда копию отдаёт хранитель: получатель должен увидеть обычное
/// сообщение, как будто оно пришло через брокер (тот же конверт `relay|…`).
pub fn delivered_message(envelope: &str) -> Option<(String, String, String, String)> {
    match wire::parse(envelope)? {
        MeshEnvelope::Relay {
            msg_id,
            origin,
            chat_scope,
            e2e_payload,
            ..
        } => {
            if msg_id.is_empty() || origin.is_empty() {
                return None;
            }
            let text = String::from_utf8(e2e_payload).ok()?;
            Some((msg_id, origin, chat_scope, text))
        }
        _ => None,
    }
}

/// Разобрать `cust|ack|<msg_id>|<state>`.
pub fn parse_ack(payload: &str) -> Option<(String, CustodyAck)> {
    let rest = payload.strip_prefix(CUSTODY_ACK_PREFIX)?;
    let (msg_id, state) = rest.split_once('|')?;
    if msg_id.is_empty() {
        return None;
    }
    CustodyAck::parse(state).map(|ack| (msg_id.to_string(), ack))
}

/// Разобрать `cust|drop|<msg_id>`.
pub fn parse_drop(payload: &str) -> Option<String> {
    let msg_id = payload.strip_prefix(CUSTODY_DROP_PREFIX)?;
    if msg_id.is_empty() {
        return None;
    }
    Some(msg_id.to_string())
}

// ═══════════════════════════════════════════════════════════════════
// ХРАНИТЕЛЬ: что держим мы
// ═══════════════════════════════════════════════════════════════════

/// Копии чужих сообщений, которые держим мы.
///
/// Замок обычный (не async): секции короткие, внутри нет `await`. Пишет
/// приёмник кадров, читает поток presence (он отдаёт сообщения получателям).
pub struct CustodyHold {
    held: Mutex<Vec<HeldEnvelope>>,
    /// Кадры, которые надо отправить (ответы `ack`, отдача `deliver`):
    /// приёмник кадров только готовит их, отправляет поток presence.
    outbound: Mutex<Vec<(String, Vec<u8>)>>,
    /// Incoming burst suppression, separate from the held-copy retry clock.
    delivered: Mutex<VecDeque<(String, Instant)>>,
    /// Bounded by held entries and removed together with them.
    attempts: Mutex<HashMap<String, HeldAttempt>>,
}

impl CustodyHold {
    pub fn new() -> Self {
        Self {
            held: Mutex::new(Vec::new()),
            outbound: Mutex::new(Vec::new()),
            delivered: Mutex::new(VecDeque::new()),
            attempts: Mutex::new(HashMap::new()),
        }
    }

    pub fn held_len(&self) -> usize {
        self.held.lock().unwrap().len()
    }

    /// Взять копию. `enabled` - владелец телефона разрешил держать чужое.
    ///
    /// Отказываем, если кастодия выключена, если конверт не разобрался или
    /// если для этого получателя уже держим [`MAX_HOLD_PER_RECIPIENT`] штук.
    /// Повторное предложение того же сообщения - принимаем (это не новый объём).
    pub fn hold(&self, item: HeldEnvelope, enabled: bool) -> CustodyAck {
        if !enabled {
            return CustodyAck::Refused;
        }
        let mut held = self.held.lock().unwrap();
        if held.iter().any(|known| known.msg_id == item.msg_id) {
            return CustodyAck::Accepted;
        }
        if held.len() >= MAX_HELD_MESSAGES {
            return CustodyAck::Full;
        }
        let for_recipient = held
            .iter()
            .filter(|known| known.recipient == item.recipient)
            .count();
        if for_recipient >= MAX_HOLD_PER_RECIPIENT {
            return CustodyAck::Full;
        }
        held.push(item);
        CustodyAck::Accepted
    }

    pub fn contains(&self, msg_id: &str) -> bool {
        self.held
            .lock()
            .unwrap()
            .iter()
            .any(|known| known.msg_id == msg_id)
    }

    pub fn remove(&self, msg_id: &str) -> bool {
        let mut held = self.held.lock().unwrap();
        let before = held.len();
        held.retain(|known| known.msg_id != msg_id);
        if held.len() != before { self.attempts.lock().unwrap().remove(msg_id); }
        held.len() != before
    }

    /// Убрать просроченные копии. Возвращает, сколько убрали.
    pub fn purge_expired(&self, now_ms: i64) -> usize {
        let mut held = self.held.lock().unwrap();
        let before = held.len();
        held.retain(|known| known.expires_at_ms > now_ms);
        if held.len() != before {
            self.attempts.lock().unwrap().retain(|id, _| held.iter().any(|item| &item.msg_id == id));
        }
        before - held.len()
    }

    /// Что можно отдать этому получателю прямо сейчас (не больше
    /// [`MAX_DELIVERIES_PER_TICK`] за круг).
    pub fn take_for_recipient(&self, recipient: &str, limit: usize) -> Vec<HeldEnvelope> {
        let held = self.held.lock().unwrap();
        held.iter()
            .filter(|known| known.recipient == recipient)
            .take(limit.min(MAX_DELIVERIES_PER_TICK))
            .cloned()
            .collect()
    }

    /// Reserve due items in least-recent-attempted order. A retrying first
    /// page can never hide later copies for the same recipient.
    pub fn take_due_for_recipient(&self, recipient: &str, limit: usize) -> Vec<HeldEnvelope> {
        self.take_due_at(recipient, limit, Instant::now())
    }

    fn take_due_at(&self, recipient: &str, limit: usize, now: Instant) -> Vec<HeldEnvelope> {
        // All methods that take both locks use this order.
        let held = self.held.lock().unwrap();
        let mut attempts = self.attempts.lock().unwrap();
        let mut due: Vec<&HeldEnvelope> = held.iter().filter(|item| {
            item.recipient == recipient && attempts.get(&item.msg_id).is_none_or(|state| state.due(now))
        }).collect();
        due.sort_by_key(|item| attempts.get(&item.msg_id).map(|state| state.last));
        due.into_iter().take(limit.min(MAX_DELIVERIES_PER_TICK)).map(|item| {
            let count = attempts.get(&item.msg_id).map_or(1, |state| state.attempts.saturating_add(1));
            attempts.insert(item.msg_id.clone(), HeldAttempt { last: now, attempts: count });
            item.clone()
        }).collect()
    }

    /// Список получателей, для которых что-то держим.
    pub fn recipients(&self) -> Vec<String> {
        let held = self.held.lock().unwrap();
        let mut out: Vec<String> = Vec::new();
        for known in held.iter() {
            if !out.iter().any(|seen| seen == &known.recipient) {
                out.push(known.recipient.clone());
            }
        }
        out
    }

    /// Положить кадр в очередь на отправку (кому и что).
    pub fn queue_outbound(&self, peer_id: &str, payload: String) -> bool {
        let mut outbound = self.outbound.lock().unwrap();
        if outbound.len() >= MAX_HELD_MESSAGES {
            return false;
        }
        outbound.push((peer_id.to_string(), payload.into_bytes()));
        true
    }

    /// Забрать не больше `limit` кадров на отправку.
    pub fn take_outbound(&self, limit: usize) -> Vec<(String, Vec<u8>)> {
        let mut outbound = self.outbound.lock().unwrap();
        let take = limit.min(outbound.len());
        outbound.drain(..take).collect()
    }

    /// Сколько кадров ждёт отправки.
    pub fn outbound_len(&self) -> usize {
        self.outbound.lock().unwrap().len()
    }

    /// Suppress a short duplicate burst, not all retries in this process. An
    /// EventBus admission or a failed stream is not a durable Room commit.
    pub fn note_delivered(&self, msg_id: &str) -> bool {
        self.note_delivered_at(msg_id, Instant::now())
    }

    fn note_delivered_at(&self, msg_id: &str, now: Instant) -> bool {
        let mut delivered = self.delivered.lock().unwrap();
        if let Some(index) = delivered.iter().position(|(known, _)| known == msg_id) {
            if now.saturating_duration_since(delivered[index].1) < DELIVERY_RETRY_AFTER {
                return false;
            }
            delivered.remove(index);
        }
        if delivered.len() >= MAX_DELIVERED_MEMORY {
            delivered.pop_front();
        }
        delivered.push_back((msg_id.to_string(), now));
        true
    }
}

impl Default for CustodyHold {
    fn default() -> Self {
        Self::new()
    }
}

// ═══════════════════════════════════════════════════════════════════
// ОТПРАВИТЕЛЬ: кому отдать копию
// ═══════════════════════════════════════════════════════════════════

/// Сообщение, которому ищем хранителей.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OfferTask {
    pub msg_id: String,
    pub recipient: String,
    pub envelope: String,
    /// Кому уже предложили (их не спрашиваем второй раз).
    pub offered_to: Vec<String>,
    /// Number of anonymous legacy acknowledgments, not verified distinct holders.
    pub accepted: usize,
    pub expires_at_ms: i64,
    /// Rotate candidate selection so failed peers cannot monopolize a sweep.
    next_candidate: usize,
}

/// Short bounded history survives completion of an offer task so the origin
/// can later send a drop hint only after the app commits its delivery ACK.
const MAX_OFFER_HISTORY: usize = 512;
struct OfferedPeer {
    msg_id: String,
    peer: String,
    expires_at_ms: i64,
}

/// Очередь предложений на стороне отправителя.
#[derive(Default)]
pub struct CustodyOffers {
    tasks: Mutex<VecDeque<OfferTask>>,
    history: Mutex<VecDeque<OfferedPeer>>,
}

impl CustodyOffers {
    pub fn new() -> Self {
        Self {
            tasks: Mutex::new(VecDeque::new()),
            history: Mutex::new(VecDeque::new()),
        }
    }

    /// Поставить в очередь. Повтор того же сообщения не добавляется.
    /// `false` - очередь полна.
    pub fn enqueue(&self, item: HeldEnvelope) -> bool {
        let mut tasks = self.tasks.lock().unwrap();
        if tasks.iter().any(|known| known.msg_id == item.msg_id) {
            return true;
        }
        if tasks.len() >= MAX_HELD_MESSAGES {
            return false;
        }
        tasks.push_back(OfferTask {
            msg_id: item.msg_id,
            recipient: item.recipient,
            envelope: item.envelope,
            offered_to: Vec::new(),
            accepted: 0,
            expires_at_ms: item.expires_at_ms,
            next_candidate: 0,
        });
        true
    }

    pub fn pending_len(&self) -> usize {
        self.tasks.lock().unwrap().len()
    }

    /// Plan a bounded, round-robin batch `(message id, envelope, peer)`.
    /// A peer is reserved before the network call; callers must report a
    /// failed stream with `note_send_failed` so it can be tried next sweep.
    /// The production sweep runs at most once a minute. One plan per task per
    /// pass and rotating task/peer cursors prevent failing sends from starving
    /// later messages or other candidates.
    pub fn next_offers(&self, candidates: &[String]) -> Vec<(String, String, String)> {
        let mut tasks = self.tasks.lock().unwrap();
        let mut history = self.history.lock().unwrap();
        let mut planned: Vec<(String, String, String)> = Vec::new();
        if tasks.is_empty() || candidates.is_empty() { return planned; }
        let max_visits = tasks.len().saturating_mul(MAX_OFFERS_PER_TICK);
        for _ in 0..max_visits {
            if planned.len() >= MAX_OFFERS_PER_TICK { break; }
            let mut task = tasks.pop_front().expect("nonempty offer queue");
            if task.accepted < MAX_CUSTODY_PEERS {
                let start = task.next_candidate % candidates.len();
                let chosen = (0..candidates.len())
                    .map(|offset| (start + offset) % candidates.len())
                    .find(|&index| !task.offered_to.contains(&candidates[index]));
                if let Some(index) = chosen {
                    let peer = &candidates[index];
                    task.next_candidate = (index + 1) % candidates.len();
                    task.offered_to.push(peer.clone());
                    // Even a failed write might have reached the holder; keep
                    // its bounded drop-hint history until Room confirms delivery.
                    if !history.iter().any(|item| item.msg_id == task.msg_id && item.peer == *peer) {
                        if history.len() >= MAX_OFFER_HISTORY { history.pop_front(); }
                        history.push_back(OfferedPeer {
                            msg_id: task.msg_id.clone(), peer: peer.clone(), expires_at_ms: task.expires_at_ms,
                        });
                    }
                    planned.push((task.msg_id.clone(), task.envelope.clone(), peer.clone()));
                }
            }
            tasks.push_back(task);
        }
        planned
    }

    /// A failed stream can be ambiguous: the holder might still have received
    /// it. Re-arm the same candidate, without discarding its drop-hint history.
    /// An ACK that already finished or removed the task wins over this callback.
    pub fn note_send_failed(&self, msg_id: &str, peer: &str) {
        let mut tasks = self.tasks.lock().unwrap();
        if let Some(task) = tasks.iter_mut().find(|task| task.msg_id == msg_id) {
            task.offered_to.retain(|known| known != peer);
        }
    }

    /// Legacy ACKs have no source identity: this is only a bounded estimate,
    /// never proof of distinct durable copies. Do not count more acknowledgments
    /// than currently planned peers; this prevents a lone peer's duplicate ACK
    /// from pretending to be two peers, but cannot deduplicate across two offers.
    pub fn note_ack(&self, msg_id: &str, ack: CustodyAck) -> bool {
        let mut tasks = self.tasks.lock().unwrap();
        let mut done = false;
        if let Some(task) = tasks.iter_mut().find(|task| task.msg_id == msg_id) {
            if ack == CustodyAck::Accepted && task.accepted < task.offered_to.len().min(MAX_CUSTODY_PEERS) {
                task.accepted += 1;
            }
            if task.accepted >= MAX_CUSTODY_PEERS {
                done = true;
            }
        }
        if done {
            tasks.retain(|task| task.msg_id != msg_id);
        }
        done
    }

    /// Return peers only after the origin's Room status has been committed.
    /// Extracting the list is independent of the normal offer task lifecycle.
    pub fn take_offered_peers(&self, msg_id: &str) -> Vec<String> {
        let mut history = self.history.lock().unwrap();
        let mut peers = Vec::new();
        history.retain(|item| {
            if item.msg_id != msg_id { return true; }
            if !peers.contains(&item.peer) { peers.push(item.peer.clone()); }
            false
        });
        peers
    }

    /// Сообщение доставлено (или протухло) - задачу убираем.
    pub fn remove(&self, msg_id: &str) -> bool {
        let mut tasks = self.tasks.lock().unwrap();
        let before = tasks.len();
        tasks.retain(|task| task.msg_id != msg_id);
        tasks.len() != before
    }

    pub fn purge_expired(&self, now_ms: i64) -> usize {
        let mut tasks = self.tasks.lock().unwrap();
        let before = tasks.len();
        tasks.retain(|task| task.expires_at_ms > now_ms);
        self.history.lock().unwrap().retain(|item| item.expires_at_ms > now_ms);
        before - tasks.len()
    }

    /// Сколько копий уже подтверждено по этому сообщению (для логов).
    pub fn accepted_for(&self, msg_id: &str) -> usize {
        self.tasks
            .lock()
            .unwrap()
            .iter()
            .find(|task| task.msg_id == msg_id)
            .map(|task| task.accepted)
            .unwrap_or(0)
    }

    /// Идентификаторы задач (нужны, чтобы не предлагать копию заново).
    pub fn known_ids(&self) -> Vec<String> {
        self.tasks
            .lock()
            .unwrap()
            .iter()
            .map(|task| task.msg_id.clone())
            .collect()
    }
}

/// Помощник: карта «сколько сообщений уже отдано» для тестов и логов.
#[allow(dead_code)]
pub(crate) fn delivered_map_len(hold: &CustodyHold) -> usize {
    hold.delivered.lock().unwrap().len()
}

/// Помощник: сколько задач с подтверждением (для тестов).
#[allow(dead_code)]
pub(crate) fn accepted_snapshot(offers: &CustodyOffers) -> HashMap<String, usize> {
    offers
        .tasks
        .lock()
        .unwrap()
        .iter()
        .map(|task| (task.msg_id.clone(), task.accepted))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn envelope(msg_id: &str, recipient: &str, origin: &str, ttl_secs: u64) -> String {
        wire::build_relay(
            msg_id,
            recipient,
            origin,
            "chat",
            ttl_secs,
            0,
            "привет".as_bytes(),
        )
    }

    #[test]
    fn enrollment_frames_round_trip() {
        let env = envelope("m1", "pk_recv", "pk_orig", 3600);
        let offer = offer_payload(&env);
        let held = parse_envelope_frame(&offer, CUSTODY_OFFER_PREFIX, 1_000).unwrap();
        assert_eq!(held.msg_id, "m1");
        assert_eq!(held.recipient, "pk_recv");
        assert_eq!(held.origin, "pk_orig");
        assert_eq!(held.envelope, env);
        assert!(held.expires_at_ms > 1_000);

        let ack = ack_payload("m1", CustodyAck::Full);
        assert_eq!(parse_ack(&ack), Some(("m1".to_string(), CustodyAck::Full)));
        assert_eq!(parse_drop(&drop_payload("m1")), Some("m1".to_string()));
        // Чужая строка и битый конверт не разбираются.
        assert!(parse_ack("cust|ack|").is_none());
        assert!(parse_ack("привет|мир").is_none());
        assert!(parse_envelope_frame("cust|offer|не конверт", CUSTODY_OFFER_PREFIX, 0).is_none());
    }

    #[test]
    fn hold_respects_switch_limits_and_duplicates() {
        let hold = CustodyHold::new();
        let env = envelope("m1", "pk_recv", "pk_orig", 3600);
        let item = held_from_envelope(&env, 1_000).unwrap();

        // Кастодия выключена - не берём.
        assert_eq!(hold.hold(item.clone(), false), CustodyAck::Refused);
        assert_eq!(hold.held_len(), 0);

        assert_eq!(hold.hold(item.clone(), true), CustodyAck::Accepted);
        // Повтор того же сообщения - не второй объём.
        assert_eq!(hold.hold(item.clone(), true), CustodyAck::Accepted);
        assert_eq!(hold.held_len(), 1);

        // На одного получателя - не больше MAX_HOLD_PER_RECIPIENT.
        for index in 0..(MAX_HOLD_PER_RECIPIENT + 4) {
            let extra = held_from_envelope(
                &envelope(&format!("extra-{}", index), "pk_recv", "pk_orig", 3600),
                1_000,
            )
            .unwrap();
            let _ = hold.hold(extra, true);
        }
        assert!(hold.held_len() <= MAX_HOLD_PER_RECIPIENT);
    }

    #[test]
    fn expired_and_removed_entries_leave_the_shelf() {
        let hold = CustodyHold::new();
        let short = held_from_envelope(&envelope("m1", "pk_recv", "pk_orig", 10), 1_000).unwrap();
        let long = held_from_envelope(&envelope("m2", "pk_recv", "pk_orig", 3600), 1_000).unwrap();
        assert_eq!(hold.hold(short, true), CustodyAck::Accepted);
        assert_eq!(hold.hold(long, true), CustodyAck::Accepted);

        // Через минуту короткое протухло.
        assert_eq!(hold.purge_expired(1_000 + 20_000), 1);
        assert!(!hold.contains("m1"));
        assert!(hold.contains("m2"));
        assert!(hold.remove("m2"));
        assert_eq!(hold.held_len(), 0);
    }

    #[test]
    fn delivery_is_offered_once_per_message() {
        let hold = CustodyHold::new();
        let item = held_from_envelope(&envelope("m1", "pk_recv", "pk_orig", 3600), 1_000).unwrap();
        assert_eq!(hold.hold(item, true), CustodyAck::Accepted);
        assert_eq!(hold.recipients(), vec!["pk_recv".to_string()]);
        let batch = hold.take_for_recipient("pk_recv", MAX_DELIVERIES_PER_TICK);
        assert_eq!(batch.len(), 1);
        // Первый раз - показываем, второй (копия от второго хранителя) - нет.
        assert!(hold.note_delivered("m1"));
        assert!(!hold.note_delivered("m1"));
    }

    #[test]
    fn received_event_burst_can_retry_after_a_failed_or_lost_room_commit() {
        let hold = CustodyHold::new();
        let now = Instant::now();
        assert!(hold.note_delivered_at("m1", now));
        assert!(!hold.note_delivered_at("m1", now + Duration::from_secs(4)));
        assert!(hold.note_delivered_at("m1", now + Duration::from_secs(5)));
        assert!(CustodyHold::new().note_delivered_at("m1", now), "restart must permit a retry");
    }

    #[test]
    fn held_stream_success_keeps_copy_and_retries_with_bounded_backoff() {
        let hold = CustodyHold::new();
        let item = held_from_envelope(&envelope("m1", "pk_recv", "pk_orig", 3600), 1_000).unwrap();
        hold.hold(item, true);
        let now = Instant::now();
        assert_eq!(hold.take_due_at("pk_recv", 8, now).len(), 1);
        // A successful transport write is deliberately NOT a remove or drop.
        assert!(hold.contains("m1"));
        assert!(hold.take_due_at("pk_recv", 8, now + Duration::from_secs(9)).is_empty());
        assert_eq!(hold.take_due_at("pk_recv", 8, now + Duration::from_secs(10)).len(), 1);
        assert!(hold.take_due_at("pk_recv", 8, now + Duration::from_secs(29)).is_empty());
        assert_eq!(hold.take_due_at("pk_recv", 8, now + Duration::from_secs(30)).len(), 1);
        assert!(hold.remove("m1"));
        assert!(hold.attempts.lock().unwrap().is_empty());
    }

    #[test]
    fn later_held_copies_are_not_starved_when_the_first_page_is_due_again() {
        let hold = CustodyHold::new();
        for n in 0..MAX_HOLD_PER_RECIPIENT {
            let item = held_from_envelope(&envelope(&format!("m{n:02}"), "pk_recv", "pk_orig", 3600), 1_000).unwrap();
            assert_eq!(hold.hold(item, true), CustodyAck::Accepted);
        }
        let now = Instant::now();
        let first = hold.take_due_at("pk_recv", 8, now);
        // Even if a slow first page took ten seconds, never-tried copies win.
        let second = hold.take_due_at("pk_recv", 8, now + Duration::from_secs(10));
        assert_eq!(first.len(), 8);
        assert_eq!(second.len(), 8);
        assert!(first.iter().all(|a| second.iter().all(|b| a.msg_id != b.msg_id)));
        assert_eq!(hold.held_len(), 16);
        assert_eq!(hold.purge_expired(4_000_000), 16);
        assert!(hold.attempts.lock().unwrap().is_empty());
    }

    #[test]
    fn confirmed_origin_can_drop_peers_even_after_offer_task_completes() {
        let offers = CustodyOffers::new();
        let item = held_from_envelope(&envelope("m1", "pk_recv", "pk_orig", 3600), 1_000).unwrap();
        assert!(offers.enqueue(item));
        let peers = vec!["pk_1".into(), "pk_2".into()];
        assert_eq!(offers.next_offers(&peers).len(), 2);
        assert!(!offers.note_ack("m1", CustodyAck::Accepted));
        assert!(offers.note_ack("m1", CustodyAck::Accepted));
        assert_eq!(offers.pending_len(), 0);
        assert_eq!(offers.take_offered_peers("m1"), peers);
        assert!(offers.take_offered_peers("m1").is_empty());
    }

    #[test]
    fn simulated_holder_retries_lost_event_and_drops_only_after_origin_commit() {
        // Protocol-level simulation only: no network, Android or real Room.
        let now = Instant::now();
        let wire = envelope("m1", "pk_recv", "pk_orig", 3600);
        let origin = CustodyOffers::new();
        let holder = CustodyHold::new();
        let recipient = CustodyHold::new();
        assert!(origin.enqueue(held_from_envelope(&wire, 1_000).unwrap()));
        let planned = origin.next_offers(&["pk_holder".into()]);
        assert_eq!(planned.len(), 1);
        let offer = offer_payload(&planned[0].1);
        let held = parse_envelope_frame(&offer, CUSTODY_OFFER_PREFIX, 1_000).unwrap();
        let accepted = holder.hold(held, true);
        assert_eq!(accepted, CustodyAck::Accepted);
        let (message_id, status) = parse_ack(&ack_payload("m1", accepted)).unwrap();
        origin.note_ack(&message_id, status);

        let first = holder.take_due_at("pk_recv", 8, now).remove(0);
        let delivery = deliver_payload(&first.envelope);
        let delivered = parse_envelope_frame(&delivery, CUSTODY_DELIVER_PREFIX, 1_001).unwrap();
        assert_eq!(delivered_message(&delivered.envelope).unwrap().0, "m1");
        assert!(recipient.note_delivered_at("m1", now));
        // Assume the event is lost before Room commits. The holder still has
        // the only copy and a transport write cannot generate a drop hint.
        assert!(holder.contains("m1"));
        assert!(holder.take_due_at("pk_recv", 8, now + Duration::from_secs(9)).is_empty());
        let retry = holder.take_due_at("pk_recv", 8, now + Duration::from_secs(10)).remove(0);
        assert_eq!(retry.envelope, first.envelope);
        assert!(recipient.note_delivered_at("m1", now + Duration::from_secs(10)));
        assert!(holder.contains("m1"));

        // Model an app ACK committed at the origin; only then extract peers
        // and send a bounded, best-effort legacy drop hint.
        let peers = origin.take_offered_peers("m1");
        assert_eq!(peers, vec!["pk_holder".to_string()]);
        let outbound = CustodyHold::new();
        assert!(outbound.queue_outbound(&peers[0], drop_payload("m1")));
        let frames = outbound.take_outbound(8);
        let hint = String::from_utf8(frames[0].1.clone()).unwrap();
        assert!(holder.remove(&parse_drop(&hint).unwrap()));
        assert!(!holder.contains("m1"));
    }

    #[test]
    fn offers_stop_after_two_accepted_copies() {
        let offers = CustodyOffers::new();
        let item = held_from_envelope(&envelope("m1", "pk_recv", "pk_orig", 3600), 1_000).unwrap();
        assert!(offers.enqueue(item));
        assert_eq!(offers.pending_len(), 1);

        let peers = vec!["pk_a".to_string(), "pk_b".to_string(), "pk_c".to_string()];
        let planned = offers.next_offers(&peers);
        assert_eq!(planned.len(), 3, "спрашиваем всех трёх, кто ещё не отвечал");
        // Второй раз тех же не спрашиваем.
        assert!(offers.next_offers(&peers).is_empty());

        assert!(!offers.note_ack("m1", CustodyAck::Accepted));
        assert!(offers.note_ack("m1", CustodyAck::Accepted), "двое взяли - хватит");
        assert_eq!(offers.pending_len(), 0);
    }

    #[test]
    fn failed_offer_is_retried_without_discarding_drop_history() {
        let offers = CustodyOffers::new();
        let item = held_from_envelope(&envelope("m1", "pk_recv", "pk_orig", 3600), 1_000).unwrap();
        assert!(offers.enqueue(item));
        let peers = vec!["pk_a".to_string(), "pk_b".to_string()];
        assert_eq!(offers.next_offers(&peers).len(), 2);
        // Even when the stream reports failure, the holder might have read it.
        offers.note_send_failed("m1", "pk_a");
        let retry = offers.next_offers(&peers);
        assert_eq!(retry.len(), 1);
        assert_eq!(retry[0].2, "pk_a");
        assert!(offers.next_offers(&peers).is_empty());
        assert_eq!(offers.take_offered_peers("m1"), peers);
    }

    #[test]
    fn failed_offers_cannot_starve_other_messages() {
        let offers = CustodyOffers::new();
        for id in 0..10 {
            assert!(offers.enqueue(held_from_envelope(
                &envelope(&format!("m{id}"), "pk_recv", "pk_orig", 3600), 1_000,
            ).unwrap()));
        }
        let peers = vec!["pk_a".to_string()];
        let first = offers.next_offers(&peers);
        assert_eq!(first.iter().map(|item| item.0.as_str()).collect::<Vec<_>>(), vec!["m0", "m1", "m2", "m3"]);
        for (id, _, peer) in &first { offers.note_send_failed(id, peer); }
        let second = offers.next_offers(&peers);
        assert_eq!(second.iter().map(|item| item.0.as_str()).collect::<Vec<_>>(), vec!["m4", "m5", "m6", "m7"]);
        for (id, _, peer) in &second { offers.note_send_failed(id, peer); }
        let third = offers.next_offers(&peers);
        assert_eq!(third.iter().map(|item| item.0.as_str()).collect::<Vec<_>>(), vec!["m8", "m9", "m0", "m1"]);
    }

    #[test]
    fn anonymous_ack_cannot_count_twice_with_only_one_candidate() {
        let offers = CustodyOffers::new();
        let item = held_from_envelope(&envelope("m1", "pk_recv", "pk_orig", 3600), 1_000).unwrap();
        assert!(offers.enqueue(item));
        assert!(!offers.note_ack("m1", CustodyAck::Accepted), "unoffered ACK must not count");
        assert_eq!(offers.accepted_for("m1"), 0);
        assert_eq!(offers.next_offers(&["pk_a".to_string()]).len(), 1);
        assert!(!offers.note_ack("m1", CustodyAck::Accepted));
        assert!(!offers.note_ack("m1", CustodyAck::Accepted));
        assert_eq!(offers.accepted_for("m1"), 1);
        assert_eq!(offers.pending_len(), 1);
        // Two distinct offers still cannot prove two distinct senders without
        // authenticating the legacy ACK; do not infer such a guarantee here.
    }

    #[test]
    fn offers_are_bounded_and_survive_silence() {
        let offers = CustodyOffers::new();
        let item = held_from_envelope(&envelope("m1", "pk_recv", "pk_orig", 10), 1_000).unwrap();
        assert!(offers.enqueue(item.clone()));
        assert!(offers.enqueue(item), "повтор не создаёт вторую задачу");
        assert_eq!(offers.pending_len(), 1);

        // Никто не ответил - через минуту задача протухает.
        assert_eq!(offers.purge_expired(1_000 + 20_000), 1);
        assert_eq!(offers.pending_len(), 0);
        assert_eq!(offers.accepted_for("m1"), 0);
    }
}
