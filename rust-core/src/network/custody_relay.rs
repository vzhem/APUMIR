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
#[derive(Default)]
pub struct CustodyHold {
    held: Mutex<Vec<HeldEnvelope>>,
    /// Кадры, которые надо отправить (ответы `ack`, отдача `deliver`):
    /// приёмник кадров только готовит их, отправляет поток presence.
    outbound: Mutex<Vec<(String, Vec<u8>)>>,
    /// Что уже отдали получателю: защита от второго показа того же сообщения,
    /// когда его держал не только этот узел.
    delivered: Mutex<VecDeque<String>>,
}

impl CustodyHold {
    pub fn new() -> Self {
        Self {
            held: Mutex::new(Vec::new()),
            outbound: Mutex::new(Vec::new()),
            delivered: Mutex::new(VecDeque::new()),
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
        held.len() != before
    }

    /// Убрать просроченные копии. Возвращает, сколько убрали.
    pub fn purge_expired(&self, now_ms: i64) -> usize {
        let mut held = self.held.lock().unwrap();
        let before = held.len();
        held.retain(|known| known.expires_at_ms > now_ms);
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

    /// Отметить, что сообщение отдано. `false` - оно уже отдавалось (дубль от
    /// второго хранителя): получателю его показывать не нужно.
    pub fn note_delivered(&self, msg_id: &str) -> bool {
        let mut delivered = self.delivered.lock().unwrap();
        if delivered.iter().any(|known| known == msg_id) {
            return false;
        }
        if delivered.len() >= MAX_DELIVERED_MEMORY {
            delivered.pop_front();
        }
        delivered.push_back(msg_id.to_string());
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
    /// Сколько хранителей подтвердили.
    pub accepted: usize,
    pub expires_at_ms: i64,
}

/// Очередь предложений на стороне отправителя.
#[derive(Default)]
pub struct CustodyOffers {
    tasks: Mutex<VecDeque<OfferTask>>,
}

impl CustodyOffers {
    pub fn new() -> Self {
        Self {
            tasks: Mutex::new(VecDeque::new()),
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
        });
        true
    }

    pub fn pending_len(&self) -> usize {
        self.tasks.lock().unwrap().len()
    }

    /// Кому предложить копию в этом круге: список `(msg_id, envelope, сосед)`.
    ///
    /// Соседей даёт вызывающий (ядро знает, кто сейчас в сети и кто «свой»);
    /// модуль только решает, кого ещё не спрашивали и хватает ли копий.
    pub fn next_offers(&self, candidates: &[String]) -> Vec<(String, String, String)> {
        let mut tasks = self.tasks.lock().unwrap();
        let mut planned: Vec<(String, String, String)> = Vec::new();
        for task in tasks.iter_mut() {
            if task.accepted >= MAX_CUSTODY_PEERS || planned.len() >= MAX_OFFERS_PER_TICK {
                continue;
            }
            for peer in candidates.iter() {
                if planned.len() >= MAX_OFFERS_PER_TICK {
                    break;
                }
                if task.accepted >= MAX_CUSTODY_PEERS {
                    break;
                }
                if task.offered_to.iter().any(|known| known == peer) {
                    continue;
                }
                task.offered_to.push(peer.clone());
                planned.push((task.msg_id.clone(), task.envelope.clone(), peer.clone()));
            }
        }
        planned
    }

    /// Хранитель ответил. `true` - копий достаточно, задачу можно закрыть.
    pub fn note_ack(&self, msg_id: &str, ack: CustodyAck) -> bool {
        let mut tasks = self.tasks.lock().unwrap();
        let mut done = false;
        if let Some(task) = tasks.iter_mut().find(|task| task.msg_id == msg_id) {
            if ack == CustodyAck::Accepted {
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
