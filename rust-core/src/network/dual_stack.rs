//! # Dual-stack адресация: IPv6 и IPv4 на одном сокете
//!
//! Зачем отдельный модуль. Прямой канал был чисто IPv4: endpoint привязан к
//! `0.0.0.0:7777`, mDNS публиковал и выбирал только IPv4, STUN резолвился с
//! фильтром `is_ipv4()`, подсказки в QR тоже были IPv4. При этом вызовы и
//! передача файлов уже предпочитали IPv6 (`CallUdpChannel`, `FileUdpChannel`).
//!
//! При переходе на `[::]` появляется главная ловушка: входящий IPv4-пир
//! приходит на dual-stack сокет как v4-mapped адрес `::ffff:a.b.c.d`. Такой
//! адрес для проверок «публичный / локальный» — это IPv6, и без нормализации
//! он молча не проходит ни `is_public_endpoint`, ни ключи пула соединений.
//! Именно так dual-stack ломает IPv4-путь, поэтому нормализация живёт здесь,
//! в одном месте, и покрыта тестами.
//!
//! Правила:
//!
//! - [`canonical`] — единственная точка нормализации; вызывается на границе
//!   (входящая датаграмма, `remote_address`, резолв DNS, подсказки адресов);
//! - [`rank`] — порядок предпочтения: глобальный IPv6, затем глобальный IPv4,
//!   затем ULA, затем link-local, в конце служебные;
//! - link-local (`fe80::/10`) не публикуется и не выбирается для соединения:
//!   он работает только с zone id (имя интерфейса), а `SocketAddr` его не несёт;
//! - [`bind_udp_dual`] / [`bind_tcp_dual`] — привязка к `[::]` с явным
//!   `only_v6(false)` и откатом на `0.0.0.0`, если IPv6 на устройстве нет.
//!
//! Откат на IPv4 обязателен: телефон без IPv6 (или с выключенным IPv6 в
//! настройках, или под VPN, который держит v6) должен работать как раньше.

use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};

/// Kill-switch IPv6 (настройка «Использовать IPv6», по умолчанию включено).
///
/// Зачем. Бывают сети и телефоны, где IPv6 формально есть, но не работает:
/// оператор отдаёт адрес, а маршрута нет; VPN держит половину стека; роутер
/// объявляет RA и молчит. Приложение в такой сети пробует IPv6, ждёт таймаут
/// и только потом берёт IPv4 — для человека это выглядит как «сообщения
/// доходят с задержкой». Выключенный переключатель убирает v6 из выбора
/// адресов, из публикации (mDNS, QR) и из привязки сокетов: приложение
/// работает ровно как до появления IPv6-поддержки.
static IPV6_ENABLED: AtomicBool = AtomicBool::new(true);

/// Включён ли IPv6 в приложении (kill-switch).
pub fn ipv6_enabled() -> bool {
    IPV6_ENABLED.load(Ordering::Relaxed)
}

/// Переключить IPv6. Действует на следующие привязки сокетов и на выбор
/// адресов; уже открытые соединения не рвутся (их закроет смена сети или
/// перезапуск службы).
pub fn set_ipv6_enabled(enabled: bool) {
    if IPV6_ENABLED.swap(enabled, Ordering::Relaxed) != enabled {
        tracing::info!(ipv6_enabled = enabled, "dual-stack: IPv6 switch changed");
    }
}

/// `[::]` — «любой» адрес для dual-stack сокета.
pub fn any_v6(port: u16) -> SocketAddr {
    SocketAddr::V6(SocketAddrV6::new(Ipv6Addr::UNSPECIFIED, port, 0, 0))
}

/// `0.0.0.0` — запасной «любой» адрес, когда IPv6 недоступен.
pub fn any_v4(port: u16) -> SocketAddr {
    SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, port))
}

/// IPv4-адрес для v4-mapped IPv6 (`::ffff:a.b.c.d`), иначе `None`.
///
/// Так выглядит IPv4-пир, пришедший на dual-stack сокет. Живёт здесь, а не в
/// `peer_exchange`, потому что нужен и транспорту, и адресной книге.
pub fn unmap(ip: IpAddr) -> Option<Ipv4Addr> {
    match ip {
        IpAddr::V6(v6) => v6.to_ipv4_mapped(),
        IpAddr::V4(_) => None,
    }
}

/// Нормализовать адрес: v4-mapped сводится к IPv4, всё остальное не меняется.
pub fn canonical_ip(ip: IpAddr) -> IpAddr {
    match unmap(ip) {
        Some(v4) => IpAddr::V4(v4),
        None => ip,
    }
}

/// Нормализовать `SocketAddr` (порт сохраняется).
pub fn canonical(addr: SocketAddr) -> SocketAddr {
    SocketAddr::new(canonical_ip(addr.ip()), addr.port())
}

/// `fe80::/10` — link-local IPv6 (и `169.254/16` для IPv4).
///
/// Такой адрес достижим только вместе с zone id (имя интерфейса). `SocketAddr`
/// зону не несёт, поэтому наружу (в QR, в mDNS, в каталог адресов) он не идёт.
pub fn is_link_local(ip: IpAddr) -> bool {
    match canonical_ip(ip) {
        IpAddr::V6(v6) => (v6.segments()[0] & 0xffc0) == 0xfe80,
        IpAddr::V4(v4) => {
            let o = v4.octets();
            o[0] == 169 && o[1] == 254
        }
    }
}

/// `fc00::/7` — unique local address: как 192.168.x.x, но в IPv6.
pub fn is_unique_local_v6(ip: IpAddr) -> bool {
    match canonical_ip(ip) {
        IpAddr::V6(v6) => (v6.segments()[0] & 0xfe00) == 0xfc00,
        IpAddr::V4(_) => false,
    }
}

fn is_special(ip: IpAddr) -> bool {
    ip.is_unspecified() || ip.is_loopback() || ip.is_multicast()
}

/// Годится ли адрес как путь до соседа без дополнительной информации.
///
/// Отбрасываются: unspecified, loopback, multicast, link-local (нужна зона) и
/// адреса из `2001:db8::/32` (документация: они не маршрутизируются).
pub fn reachable(ip: IpAddr) -> bool {
    reachable_with(ip, ipv6_enabled())
}

/// `reachable` с явным состоянием kill-switch: чистая функция, чтобы тест не
/// трогал глобальный флаг (тесты идут параллельно в одном процессе).
fn reachable_with(ip: IpAddr, ipv6: bool) -> bool {
    let ip = canonical_ip(ip);
    if !ipv6 && ip.is_ipv6() {
        return false;
    }
    if is_special(ip) || is_link_local(ip) {
        return false;
    }
    match ip {
        IpAddr::V6(v6) => {
            let s = v6.segments();
            !(s[0] == 0x2001 && s[1] == 0x0db8)
        }
        IpAddr::V4(_) => true,
    }
}

/// Порядок предпочтения: меньше — лучше. `None` — адрес непригоден.
///
/// Глобальный IPv6 (0) раньше глобального IPv4 (1): у v6 нет NAT, поэтому
/// прямой канал там чаще заводится с первой попытки. ULA (2) — это LAN, оно
/// хуже глобального v4 только потому, что глобальный адрес с большей
/// вероятностью достижим из чужой сети.
pub fn rank(ip: IpAddr) -> Option<u8> {
    rank_with(ip, ipv6_enabled())
}

/// `rank` с явным состоянием kill-switch (см. `reachable_with`).
fn rank_with(ip: IpAddr, ipv6: bool) -> Option<u8> {
    let ip = canonical_ip(ip);
    if !ipv6 && ip.is_ipv6() {
        return None;
    }
    if is_special(ip) || is_link_local(ip) {
        return None;
    }
    let value = match ip {
        IpAddr::V6(_) => {
            if is_unique_local_v6(ip) {
                2
            } else if reachable(ip) {
                0
            } else {
                return None;
            }
        }
        IpAddr::V4(_) => 1,
    };
    Some(value)
}

/// Выбрать лучший адрес из набора (IPv6 предпочтителен, IPv4 запасной).
pub fn pick<'a, I>(ips: I) -> Option<IpAddr>
where
    I: IntoIterator<Item = &'a IpAddr>,
{
    ips.into_iter()
        .filter_map(|ip| rank(*ip).map(|rank| (rank, canonical_ip(*ip))))
        .min_by_key(|(rank, _)| *rank)
        .map(|(_, ip)| ip)
}

/// То же для адресов с портом: порт берётся у выбранного адреса.
pub fn pick_addr<'a, I>(addrs: I) -> Option<SocketAddr>
where
    I: IntoIterator<Item = &'a SocketAddr>,
{
    addrs
        .into_iter()
        .filter_map(|addr| rank(addr.ip()).map(|rank| (rank, canonical(*addr))))
        .min_by_key(|(rank, _)| *rank)
        .map(|(_, addr)| addr)
}

/// Все адреса, которые имеют смысл отдать соседям: IPv6 впереди IPv4,
/// без loopback, link-local, multicast и unspecified.
pub fn shareable<'a, I>(ips: I) -> Vec<IpAddr>
where
    I: IntoIterator<Item = &'a IpAddr>,
{
    let mut out: Vec<IpAddr> = ips
        .into_iter()
        .filter(|ip| reachable(**ip))
        .map(|ip| canonical_ip(*ip))
        .collect();
    out.sort_by_key(|ip| (rank(*ip).unwrap_or(u8::MAX), *ip));
    out.dedup();
    out
}

/// Локальные адреса интерфейсов в порядке предпочтения (v6 → v4).
///
/// Одна реализация на mDNS и на подсказки в QR: раньше обе собирали список
/// сами, и обе отбрасывали IPv6.
pub fn local_shareable_ips() -> Vec<IpAddr> {
    let interfaces = match if_addrs::get_if_addrs() {
        Ok(list) => list,
        Err(_) => return Vec::new(),
    };
    let present: Vec<IpAddr> = interfaces.iter().map(|iface| iface.ip()).collect();
    shareable(present.iter())
}

/// Подсказки «адрес:порт» для QR и каталога адресов.
///
/// Формат — `SocketAddr::to_string()`, поэтому IPv6 выходит в скобках
/// (`[2001:db8::1]:7777`) и разбирается обратно тем же `parse::<SocketAddr>()`,
/// который уже используется в `device_pairing` и `peer_exchange`.
pub fn contact_hints(port: u16, limit: usize) -> Vec<String> {
    local_shareable_ips()
        .into_iter()
        .take(limit)
        .map(|ip| SocketAddr::new(ip, port).to_string())
        .collect()
}

/// Привязать UDP-сокет к `[::]:port` в режиме dual-stack.
///
/// `only_v6(false)` выставлен явно: по умолчанию в Linux он и так false, но на
/// Android и на разных ядрах полагаться на умолчание нельзя — при `true`
/// IPv4-пиры перестанут подключаться вовсе.
///
/// Если IPv6 на устройстве нет (или адрес занят только под IPv4), — откат на
/// `0.0.0.0:port`, то есть прежнее поведение.
pub fn bind_udp_dual(port: u16) -> io::Result<UdpSocket> {
    if !ipv6_enabled() {
        return UdpSocket::bind(any_v4(port));
    }
    match bind_udp_any_v6(port) {
        Ok(socket) => Ok(socket),
        Err(error) => {
            tracing::warn!(
                port,
                error = %error,
                "dual-stack: [::] bind failed, falling back to IPv4"
            );
            UdpSocket::bind(any_v4(port))
        }
    }
}

fn bind_udp_any_v6(port: u16) -> io::Result<UdpSocket> {
    use socket2::{Domain, Protocol, Socket, Type};

    let socket = Socket::new(Domain::IPV6, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_only_v6(false)?;
    socket.bind(&any_v6(port).into())?;
    Ok(socket.into())
}

/// Привязать TCP-listener к `[::]:port` (dual-stack) с откатом на IPv4.
///
/// Слушатель истории (`run_tcp_listener`, порт 7778) раньше был привязан к
/// `0.0.0.0`; на dual-stack он должен принимать и IPv6, иначе в сети только-v6
/// он бесполезен.
pub async fn bind_tcp_dual(port: u16) -> io::Result<tokio::net::TcpListener> {
    if !ipv6_enabled() {
        let listener = std::net::TcpListener::bind(any_v4(port))?;
        listener.set_nonblocking(true)?;
        return tokio::net::TcpListener::from_std(listener);
    }
    let to_listener = |socket: socket2::Socket| -> io::Result<tokio::net::TcpListener> {
        socket.set_nonblocking(true)?;
        let listener: std::net::TcpListener = socket.into();
        tokio::net::TcpListener::from_std(listener)
    };
    match tcp_socket_v6(port).map(to_listener) {
        Ok(Ok(listener)) => Ok(listener),
        Ok(Err(error)) | Err(error) => {
            tracing::warn!(
                port,
                error = %error,
                "dual-stack: TCP [::] bind failed, falling back to IPv4"
            );
            let listener = std::net::TcpListener::bind(any_v4(port))?;
            listener.set_nonblocking(true)?;
            tokio::net::TcpListener::from_std(listener)
        }
    }
}

fn tcp_socket_v6(port: u16) -> io::Result<socket2::Socket> {
    use socket2::{Domain, Protocol, Socket, Type};

    let socket = Socket::new(Domain::IPV6, Type::STREAM, Some(Protocol::TCP))?;
    socket.set_only_v6(false)?;
    socket.bind(&any_v6(port).into())?;
    socket.listen(1024)?;
    Ok(socket)
}

/// Разрешить имя в адреса в порядке предпочтения (IPv6 → IPv4).
///
/// Блокирующий вызов: вызывать из `spawn_blocking` (DNS в реакторе запрещён).
/// Возвращает все адреса, а не первый: у `stun.l.google.com` AAAA нет вовсе, а
/// у `stun.cloudflare.com` есть оба стека — выбирать должен вызывающий, с
/// возможностью попробовать следующий адрес при неудаче.
pub fn resolve_ordered(host: &str) -> Vec<SocketAddr> {
    use std::net::ToSocketAddrs;

    let mut addrs: Vec<SocketAddr> = match host.to_socket_addrs() {
        Ok(iter) => iter.map(canonical).collect(),
        Err(_) => return Vec::new(),
    };
    // Kill-switch: с выключенным IPv6 не пробуем v6 даже как «второй адрес» —
    // иначе STUN снова начнёт ждать таймаут на неработающем стеке.
    if !ipv6_enabled() {
        addrs.retain(|addr| addr.is_ipv4());
    }
    // Второй ключ — сам адрес: одинаковые адреса обязаны оказаться рядом,
    // иначе `dedup` их не увидит.
    addrs.sort_by_key(|addr| (rank(addr.ip()).unwrap_or(u8::MAX), *addr));
    addrs.dedup();
    addrs
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn ip(text: &str) -> IpAddr {
        text.parse().unwrap()
    }

    fn addr(text: &str) -> SocketAddr {
        text.parse().unwrap()
    }

    #[test]
    fn v4_mapped_addresses_become_plain_ipv4() {
        assert_eq!(canonical(addr("[::ffff:8.8.8.8]:7777")), addr("8.8.8.8:7777"));
        assert_eq!(canonical(addr("[::ffff:192.168.1.5]:7")), addr("192.168.1.5:7"));
        // Настоящий IPv6 не трогаем: иначе сломается весь v6-путь.
        assert_eq!(canonical(addr("[2001:db8::1]:7777")), addr("[2001:db8::1]:7777"));
        assert_eq!(canonical(addr("10.0.0.1:7777")), addr("10.0.0.1:7777"));
    }

    #[test]
    fn mapped_lan_addresses_stay_local_after_normalization() {
        // 192.168 через dual-stack сокет не должен выглядеть «глобальным»:
        // после нормализации это обычный IPv4, и правила LAN к нему применимы.
        let mapped = canonical(addr("[::ffff:192.168.1.5]:7777"));
        assert!(mapped.is_ipv4());
        assert_eq!(mapped.ip(), ip("192.168.1.5"));
        assert!(reachable(mapped.ip()));
    }

    #[test]
    fn ipv6_is_preferred_over_ipv4_and_link_local_is_skipped() {
        // Глобальный v6 (не документационный: 2001:db8::/32 не маршрутизируется).
        let set = vec![ip("192.168.1.5"), ip("fe80::1"), ip("2606:4700:4700::1111")];
        assert_eq!(pick(set.iter()), Some(ip("2606:4700:4700::1111")));
        // Без IPv6 выбирается IPv4, а не link-local.
        let v4_only = vec![ip("fe80::1"), ip("192.168.1.5")];
        assert_eq!(pick(v4_only.iter()), Some(ip("192.168.1.5")));
        // Только link-local — соединение не строим: нужна зона интерфейса.
        let only_ll = vec![ip("fe80::1")];
        assert_eq!(pick(only_ll.iter()), None);
    }

    #[test]
    fn ipv4_only_network_keeps_working() {
        // Устройство без IPv6: единственный адрес — IPv4, и он обязан пройти.
        let v4_only = vec![ip("91.77.1.2")];
        assert_eq!(pick(v4_only.iter()), Some(ip("91.77.1.2")));
        assert_eq!(rank(ip("91.77.1.2")), Some(1));
        // loopback/unspecified/multicast непригодны ни в одном стеке.
        for bad in ["127.0.0.1", "0.0.0.0", "224.0.0.1", "::", "::1", "ff02::1"] {
            assert_eq!(rank(ip(bad)), None, "{bad}");
        }
    }

    #[test]
    fn unique_local_ipv6_ranks_between_global_and_link_local() {
        assert_eq!(rank(ip("2001:db8:1::5")), None); // документация
        assert_eq!(rank(ip("2606:4700:4700::1111")), Some(0));
        assert_eq!(rank(ip("fd00::1")), Some(2));
        assert_eq!(rank(ip("fe80::1")), None);
        // ULA предпочтительнее link-local и не публикуется как интернет-адрес.
        assert!(is_unique_local_v6(ip("fd12::1")));
        assert!(!is_unique_local_v6(ip("2001:db8::1")));
    }

    #[test]
    fn shareable_drops_loopback_link_local_and_sorts_v6_first() {
        let set = vec![
            ip("127.0.0.1"),
            ip("fe80::abcd"),
            ip("192.168.1.5"),
            ip("2a00:1450:4001::1"),
            ip("::1"),
        ];
        let kept = shareable(set.iter());
        assert_eq!(
            kept,
            vec![ip("2a00:1450:4001::1"), ip("192.168.1.5")],
            "v6 впереди, служебные адреса выброшены"
        );
    }

    #[test]
    fn contact_hints_use_brackets_for_ipv6() {
        // Тот же формат, что разбирает device_pairing и peer_exchange.
        let text = SocketAddr::new(ip("2001:db8::1"), 7777).to_string();
        assert_eq!(text, "[2001:db8::1]:7777");
        assert_eq!(text.parse::<SocketAddr>().unwrap(), addr("[2001:db8::1]:7777"));
    }

    #[test]
    fn ipv6_kill_switch_leaves_ipv4_path_intact() {
        // Выключенный IPv6 убирает v6 из выбора и из публикации, но IPv4
        // остаётся полностью рабочим — иначе «выключить IPv6» сломало бы
        // сеть на телефоне без v6 вовсе.
        let v6 = ip("2a00:1450:4001::1");
        let v4 = ip("91.77.1.2");
        assert_eq!(rank_with(v6, false), None);
        assert_eq!(rank_with(v4, false), Some(1));
        assert!(!reachable_with(v6, false));
        assert!(reachable_with(v4, false));
        // Включённый — прежнее поведение (v6 впереди v4).
        assert_eq!(rank_with(v6, true), Some(0));
        assert_eq!(rank_with(v4, true), Some(1));
        // v4-mapped при выключенном v6 — это IPv4, а не «IPv6»: нормализация
        // идёт раньше проверки стека.
        assert_eq!(rank_with(addr("[::ffff:91.77.1.2]:7777").ip(), false), Some(1));
    }

    #[test]
    fn resolve_ordered_prefers_ipv6_for_literal_names() {
        // Локальное имя без DNS: проверяем разбор литерала и нормализацию
        // v4-mapped, не завися от внешней сети.
        assert_eq!(resolve_ordered("127.0.0.1:7777"), vec![addr("127.0.0.1:7777")]);
        assert_eq!(
            resolve_ordered("[::ffff:127.0.0.1]:7777"),
            vec![addr("127.0.0.1:7777")],
            "v4-mapped из резолва приводится к IPv4"
        );
    }

    #[test]
    fn dual_stack_socket_accepts_ipv4_traffic() {
        // Главная проверка правки: сокет на [::] отправляет на IPv4-адрес, а
        // ответ приходит как v4-mapped и нормализуется в IPv4.
        let server = UdpSocket::bind(any_v4(0)).expect("bind v4 server");
        server
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let server_port = server.local_addr().unwrap().port();

        let client = bind_udp_dual(0).expect("dual-stack bind");
        client
            .send_to(b"ping", SocketAddr::new(ip("127.0.0.1"), server_port))
            .expect("send to ipv4 from dual-stack socket");

        let mut buf = [0u8; 16];
        let (n, from) = server.recv_from(&mut buf).expect("v4 server receives");
        assert_eq!(&buf[..n], b"ping");
        // Источник может прийти v4-mapped: канонизация обязана дать IPv4.
        assert!(canonical(from).is_ipv4(), "from = {from}");
    }
}
