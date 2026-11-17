//! # Приоритеты трафика — кто первый, кто потом
//!
//! Владелец (2026-11-17): «разбудить телефон и найти адрес — первостепенное.
//! Свои сообщения тоже важны, особенно личная переписка. Дальше найди все
//! пересылки и распредели их по важности. Кнопка SOS в группе тоже важная, но
//! личные сообщения и пробуждение важнее. И самое главное — чтобы никто никого
//! не мог затормозить».
//!
//! Отсюда лестница из пяти классов. Меньший ранг — важнее:
//!
//! | класс | ранг | что это |
//! |---|---|---|
//! | [`TrafficClass::WakeAddress`] | 0 | пробуждение и поиск адреса: анонс эпохи, NAT/LAN-кандидаты, «где ты» |
//! | [`TrafficClass::Personal`] | 1 | своя личная переписка (1:1) |
//! | [`TrafficClass::Alert`] | 2 | тревога: SOS в группе, звонок |
//! | [`TrafficClass::GroupOther`] | 3 | остальное своё: группы, каналы, истории, файлы, «печатает…» |
//! | [`TrafficClass::Foreign`] | 4 | пересылка чужих сообщений для других узлов |
//!
//! Правила, которые из этого следуют и проверяются тестами внизу файла:
//!
//! 1. **Никто никого не тормозит.** Чужой трафик живёт в отдельном запасе и не
//!    может вытеснить или задержать свой — ни личный, ни групповой.
//! 2. **Важное не вытесняется менее важным.** Групповой поток не может
//!    вытеснить личное сообщение, а личный — анонс эпохи.
//! 3. **Пробуждение не вытесняется ничем, кроме другого пробуждения.** Анонс
//!    эпохи — это адрес: без него собеседника нельзя разбудить вообще.
//! 4. **У каждого класса свой запас**, поэтому один поток не может занять всю
//!    очередь (доли в сумме дают [`OWN_RESERVES_TOTAL`]).
//!
//! Класс считается только по тому, что ядро и так видит: признак «своё»,
//! категория тела ([`RelayPayloadKind`]), начало незапечатанного тела и
//! служебная подсказка в `msg_id` (`push1-`, `sos1-`, `call1-`). Запечатанное
//! тело не читается никогда: для него действует правило «своё запечатанное —
//! это личная переписка», а более точную подсказку даёт Android в id.

use crate::network::relay_queue::RelayPayloadKind;

/// Класс важности записи очереди.
///
/// Порядок объявления — и есть порядок важности: `WakeAddress` меньше всех и
/// поэтому идёт первым, `Foreign` — последним.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum TrafficClass {
    /// Пробуждение и поиск адреса.
    WakeAddress,
    /// Своя личная переписка.
    Personal,
    /// Тревога: SOS в группе, звонок.
    Alert,
    /// Остальное своё: группы, каналы, истории, файлы, служебные сигналы.
    GroupOther,
    /// Пересылка для других узлов.
    Foreign,
}

/// Сколько записей каждого класса помещается в своём резерве.
///
/// Доли нарочно не равны: личная переписка — самый большой запас, потому что
/// её потеря видна человеку; пробуждение и тревога малы, но защищены от
/// вытеснения; остальному своему достаётся столько, сколько останется. Сумма
/// равна [`OWN_RESERVES_TOTAL`], то есть прежнему общему потолку своих записей:
/// общий запас не вырос, он просто поделён.
pub const OWN_RESERVE_WAKE: usize = 64;
pub const OWN_RESERVE_PERSONAL: usize = 608;
pub const OWN_RESERVE_ALERT: usize = 64;
pub const OWN_RESERVE_OTHER: usize = 264;

/// Сумма долей классов в своём резерве.
pub const OWN_RESERVES_TOTAL: usize =
    OWN_RESERVE_WAKE + OWN_RESERVE_PERSONAL + OWN_RESERVE_ALERT + OWN_RESERVE_OTHER;

/// Служебная подсказка Android в `msg_id`: пробуждение и адрес.
pub const ID_HINT_WAKE: &str = "push1-";
/// Служебная подсказка Android в `msg_id`: тревога (SOS).
pub const ID_HINT_SOS: &str = "sos1-";
/// Служебная подсказка Android в `msg_id`: звонок.
pub const ID_HINT_CALL: &str = "call1-";

impl TrafficClass {
    /// Ранг: 0 — самый важный, 4 — самый неважный.
    pub fn rank(self) -> u8 {
        self as u8
    }

    /// Слово для журнала и отчёта «Логи».
    pub fn as_str(self) -> &'static str {
        match self {
            TrafficClass::WakeAddress => "пробуждение",
            TrafficClass::Personal => "личное",
            TrafficClass::Alert => "тревога",
            TrafficClass::GroupOther => "прочее",
            TrafficClass::Foreign => "чужое",
        }
    }

    /// Запас этого класса в своём резерве.
    pub fn own_reserve(self) -> usize {
        match self {
            TrafficClass::WakeAddress => OWN_RESERVE_WAKE,
            TrafficClass::Personal => OWN_RESERVE_PERSONAL,
            TrafficClass::Alert => OWN_RESERVE_ALERT,
            TrafficClass::GroupOther => OWN_RESERVE_OTHER,
            // У чужих свой отдельный запас: MAX_PER_RECIPIENT / MAX_TOTAL.
            TrafficClass::Foreign => 0,
        }
    }

    /// Класс, который не вытесняется ничем, кроме самого себя.
    pub fn protected(self) -> bool {
        matches!(self, TrafficClass::WakeAddress)
    }
}

/// Начало незапечатанного тела: пробуждение и поиск адреса.
///
/// `APUPUSH1|` — анонс эпохи и запросы пробуждения; `APUUDP1|`/`APULAN1|`/
/// `APULANHS1|` — кандидаты адреса для прямого канала. Без них собеседника не
/// разбудить и не найти, поэтому это самый важный класс.
const WAKE_PREFIXES: [&[u8]; 4] = [b"APUPUSH1|", b"APUUDP1|", b"APULAN1|", b"APULANHS1|"];

/// Начало незапечатанного тела: тревога.
const ALERT_PREFIXES: [&[u8]; 2] = [b"APUSOS1|", b"APUCALL1|"];

/// Определить класс СВОЕГО сообщения.
///
/// Запечатанное тело не читается: для него работает только подсказка в id и
/// общее правило «своё запечатанное — личная переписка». Так телефон старой
/// сборки, который подсказок не ставит, получает не худший, а лучший класс:
/// приоритеты не могут ухудшить доставку тому, кто о них ещё не знает.
pub fn class_of_own(
    message_id: &str,
    chat_scope: &str,
    payload: &[u8],
    kind: RelayPayloadKind,
) -> TrafficClass {
    if message_id.starts_with(ID_HINT_WAKE) {
        return TrafficClass::WakeAddress;
    }
    if message_id.starts_with(ID_HINT_SOS) || message_id.starts_with(ID_HINT_CALL) {
        return TrafficClass::Alert;
    }
    if WAKE_PREFIXES.iter().any(|prefix| payload.starts_with(*prefix)) {
        return TrafficClass::WakeAddress;
    }
    // LAN/UDP-кандидаты в служебном scope приходят запечатанными: префикс тела
    // не виден, но ID выдаёт их происхождение (см. `relay_queue::message_kind`).
    if chat_scope == "direct"
        && (message_id.starts_with("lan-")
            || message_id.starts_with("lan-seek-")
            || message_id.starts_with("udp-sig-"))
    {
        return TrafficClass::WakeAddress;
    }
    if ALERT_PREFIXES.iter().any(|prefix| payload.starts_with(*prefix)) {
        return TrafficClass::Alert;
    }
    match kind {
        // Запечатанное тело — это переписка. Ядро не читает его и не пытается
        // угадать группу: личный класс безопаснее, потому что он выше.
        RelayPayloadKind::Correspondence => TrafficClass::Personal,
        // «Печатает…», куски файлов и прочее служебное: нужны, но подождут.
        RelayPayloadKind::ServiceSignal | RelayPayloadKind::FilePacket => TrafficClass::GroupOther,
    }
}

/// Класс записи очереди целиком (с учётом признака «своё»).
pub fn class_of(
    own: bool,
    message_id: &str,
    chat_scope: &str,
    payload: &[u8],
    kind: RelayPayloadKind,
) -> TrafficClass {
    if own {
        class_of_own(message_id, chat_scope, payload, kind)
    } else {
        TrafficClass::Foreign
    }
}

/// Можно ли вытеснить запись класса `candidate` ради входящей `incoming`.
///
/// Правило одно и то же и для записей, и для их объяснения человеку: менее
/// важное уступает более важному, важное не уступает ничему, а пробуждение не
/// уступает вообще ничему, кроме другого пробуждения.
pub fn evictable_for(incoming: TrafficClass, candidate: TrafficClass) -> bool {
    if candidate.protected() {
        return incoming == TrafficClass::WakeAddress;
    }
    if candidate == TrafficClass::Foreign {
        // Чужие записи живут в своём запасе; своя очередь их не вытесняет и не
        // считается с ними (см. `RelayQueue::enqueue_own`).
        return false;
    }
    candidate.rank() >= incoming.rank()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn own(id: &str, scope: &str, payload: &[u8]) -> TrafficClass {
        let kind = crate::network::relay_queue::message_kind(id, scope, payload);
        class_of_own(id, scope, payload, kind)
    }

    #[test]
    fn reserves_do_not_grow_the_own_budget() {
        // Потолок своих остался прежним: классы делят его, а не добавляют.
        assert_eq!(OWN_RESERVES_TOTAL, 1_000);
        let sum: usize = [
            TrafficClass::WakeAddress,
            TrafficClass::Personal,
            TrafficClass::Alert,
            TrafficClass::GroupOther,
            TrafficClass::Foreign,
        ]
        .iter()
        .map(|c| c.own_reserve())
        .sum();
        assert_eq!(sum, OWN_RESERVES_TOTAL);
    }

    #[test]
    fn wake_hint_is_the_top_class() {
        assert_eq!(own("push1-1", "chat-a", b"sealed"), TrafficClass::WakeAddress);
        assert_eq!(own("APUPUSH1|x", "direct", b"APUPUSH1|abc"), TrafficClass::WakeAddress);
        assert_eq!(own("udp-sig-7", "direct", b"sealed"), TrafficClass::WakeAddress);
        assert_eq!(own("lan-seek-1", "direct", b"sealed"), TrafficClass::WakeAddress);
    }

    #[test]
    fn personal_correspondence_is_above_the_rest_of_the_own_traffic() {
        // Запечатанное обычное сообщение — личная переписка.
        assert_eq!(own("3f2a-uuid", "chat-a", b"\x00sealed"), TrafficClass::Personal);
        // «Печатает…» — служебный сигнал: нужен, но подождёт.
        assert_eq!(own("t-1", "X", b"APUTYP1|abc"), TrafficClass::GroupOther);
    }

    #[test]
    fn alert_sits_between_personal_and_the_rest() {
        assert_eq!(own("sos1-1", "group-a", b"sealed"), TrafficClass::Alert);
        assert_eq!(own("call1-1", "chat-a", b"sealed"), TrafficClass::Alert);
        assert_eq!(own("x", "chat-a", b"APUCALL1|ab|cd"), TrafficClass::Alert);
        // Ранг: личное выше тревоги, тревога выше прочего.
        assert!(TrafficClass::Personal < TrafficClass::Alert);
        assert!(TrafficClass::Alert < TrafficClass::GroupOther);
    }

    #[test]
    fn foreign_is_always_the_last_class() {
        assert_eq!(class_of(false, "push1-1", "x", b"sealed", RelayPayloadKind::Correspondence), TrafficClass::Foreign);
        assert_eq!(class_of(false, "APUTYP1|a", "x", b"APUTYP1|a", RelayPayloadKind::ServiceSignal), TrafficClass::Foreign);
        assert!(
            TrafficClass::Foreign > TrafficClass::GroupOther,
            "чужая пересылка не может быть важнее любого своего трафика"
        );
    }

    #[test]
    fn wake_is_never_evicted_by_anything_else() {
        for incoming in [
            TrafficClass::Personal,
            TrafficClass::Alert,
            TrafficClass::GroupOther,
            TrafficClass::Foreign,
        ] {
            assert!(
                !evictable_for(incoming, TrafficClass::WakeAddress),
                "{} не может вытеснить пробуждение",
                incoming.as_str()
            );
        }
        assert!(evictable_for(TrafficClass::WakeAddress, TrafficClass::WakeAddress));
    }

    #[test]
    fn less_important_yields_and_more_important_does_not() {
        // Групповой поток уступает личному — но не наоборот.
        assert!(evictable_for(TrafficClass::Personal, TrafficClass::GroupOther));
        assert!(!evictable_for(TrafficClass::GroupOther, TrafficClass::Personal));
        // Тревога уступает личному и не уступает групповому.
        assert!(evictable_for(TrafficClass::Personal, TrafficClass::Alert));
        assert!(!evictable_for(TrafficClass::GroupOther, TrafficClass::Alert));
        // Свой трафик не вытесняет чужой: у чужих отдельный запас.
        assert!(!evictable_for(TrafficClass::Personal, TrafficClass::Foreign));
    }
}
