#!/usr/bin/env python3
"""Контракт dual-stack и устойчивости сети (части 1–2 задачи IPv6).

Почему это проверка, а не комментарий в документации. Переход на `[::]` — это
правка, которую легко «доделать» обратно по частям, не заметив:

  * кто-то вернёт `find(|a| a.is_ipv4())` при резолве STUN или в mDNS — IPv6
    снова перестанет использоваться, при этом ничего не упадёт;
  * кто-то уберёт откат на `0.0.0.0` — телефон без IPv6 перестанет запускать
    прямой канал, и это выяснится только в бою на конкретном устройстве;
  * кто-то забудет нормализацию `::ffff:a.b.c.d` — входящий IPv4-пир на
    dual-stack сокете не пройдёт `is_public_endpoint`, и «IPv4 работает как
    раньше» перестанет быть правдой.

Проверяются ровно эти инварианты:

  1. модуль `network::dual_stack` существует, объявлен и содержит
     нормализацию v4-mapped;
  2. ворота адресов (`is_public_endpoint`, `usable_local_endpoint`) и
     подпись аренды адреса нормализуют адрес;
  3. ни в одном месте не осталось фильтра «только IPv4» для адресов пира или
     для резолва STUN-сервера;
  4. откат на IPv4 на месте: `bind_udp_dual` и `bind_tcp_dual` умеют
     `0.0.0.0`, а QUIC-endpoint создаётся через `endpoint_socket`;
  5. `only_v6(false)` выставлен явно;
  6. тесты на IPv4-only сеть, на v4-mapped адрес и на приём IPv4 dual-stack
     сокетом не удалены.

Запуск: python3 scripts/ci/check-dual-stack.py
Код возврата: 0 — в порядке, 1 — нарушение, 2 — не нашёл файлы.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NETWORK = ROOT / "rust-core/src/network"

DUAL_STACK = NETWORK / "dual_stack.rs"
MOD_RS = NETWORK / "mod.rs"
QUIC = NETWORK / "quic_client.rs"
DIRECT = NETWORK / "direct_transport.rs"
ICE = NETWORK / "ice.rs"
MDNS = NETWORK / "mdns.rs"
PEER_EXCHANGE = NETWORK / "peer_exchange.rs"
CORE = ROOT / "rust-core/src/engine/core.rs"

TIMING = NETWORK / "timing.rs"
NETWORK_PATH = (
    ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/swarm/NetworkPathSettings.kt"
)
NETWORK_DIALOG = (
    ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui/screens/settings/MessagesNetworkDialog.kt"
)
SETTINGS_SCREEN = (
    ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui/screens/settings/SettingsScreen.kt"
)

problems = []


def read(path):
    if not path.exists():
        problems.append(f"{path.relative_to(ROOT)}: файл не найден")
        return ""
    return path.read_text(encoding="utf-8")


def need(path, text, marker, why):
    if marker not in text:
        problems.append(f"{path.relative_to(ROOT)}: нет «{marker}» — {why}")


def forbid(path, text, pattern, why):
    found = re.search(pattern, text, re.MULTILINE)
    if found:
        problems.append(
            f"{path.relative_to(ROOT)}: осталось «{found.group(0).strip()}» — {why}"
        )


dual = read(DUAL_STACK)
mod_rs = read(MOD_RS)
quic = read(QUIC)
direct = read(DIRECT)
ice = read(ICE)
mdns = read(MDNS)
peer = read(PEER_EXCHANGE)
core = read(CORE)

if problems:
    for problem in problems:
        print(f"✗ {problem}")
    sys.exit(2)

# ── 1. Модуль нормализации адресов ──────────────────────────────────────────
need(MOD_RS, mod_rs, "pub mod dual_stack;", "модуль не объявлен в network/mod.rs")
need(DUAL_STACK, dual, "pub fn canonical(", "нет нормализации v4-mapped адреса")
need(DUAL_STACK, dual, "to_ipv4_mapped", "v4-mapped не сводится к IPv4")
need(DUAL_STACK, dual, "set_only_v6(false)", "dual-stack сокет обязан принимать IPv4")

# ── 2. Нормализация на воротах адресов ──────────────────────────────────────
for function in ("is_public_endpoint", "usable_local_endpoint"):
    block = re.search(
        rf"pub fn {function}\(addr: SocketAddr\) -> bool \{{(.*?)\n\}}", peer, re.S
    )
    if not block:
        problems.append(f"peer_exchange.rs: не нашёл функцию {function}")
    elif "dual_stack::canonical(addr)" not in block.group(1):
        problems.append(
            f"peer_exchange.rs: {function} не нормализует адрес — "
            "v4-mapped IPv4-пир будет отброшен"
        )

# ── 3. Никаких «только IPv4» для адресов пира и STUN ────────────────────────
forbid(
    DIRECT,
    direct,
    r"\.find\(\|a\| a\.is_ipv4\(\)\)",
    "резолв STUN-сервера снова отбрасывает AAAA",
)
forbid(
    MDNS,
    mdns,
    r"\.find\(\|a\| a\.is_ipv4\(\)\)",
    "mDNS снова выбирает только IPv4-адрес пира",
)
forbid(
    CORE,
    core,
    r"filter\(\|ip\| ip\.is_ipv4\(\) && !ip\.is_loopback\(\)\)",
    "подсказки адресов в QR снова только IPv4",
)
forbid(
    ICE,
    ice,
    r'UdpSocket::bind\("0\.0\.0\.0:0"\)',
    "STUN-клиент снова привязан к IPv4-сокету",
)
forbid(
    DUAL_STACK,
    dual,
    r"\.filter\(\|ip\| ip\.is_ipv4\(\)\)",
    "сборщик локальных адресов не должен отбрасывать IPv6",
)

# ── 4. Откат на IPv4 сохранён ───────────────────────────────────────────────
need(DUAL_STACK, dual, "UdpSocket::bind(any_v4(port))", "нет отката UDP на IPv4")
need(DUAL_STACK, dual, "bind_tcp_dual", "нет dual-stack TCP-listener")
need(DUAL_STACK, dual, "TcpListener::bind(any_v4(port))", "нет отката TCP на IPv4")
need(QUIC, quic, "fn endpoint_socket(", "QUIC-endpoint должен создаваться через общий сокет")
need(QUIC, quic, "dual_stack::bind_udp_dual(", "QUIC-сокет не dual-stack")
need(CORE, core, "crate::network::dual_stack::any_v6(port)", "endpoint движка снова на 0.0.0.0")
need(CORE, core, "crate::network::dual_stack::bind_tcp_dual(port)", "TCP-listener движка снова на 0.0.0.0")
need(MDNS, mdns, "dual_stack::pick(addresses.iter())", "mDNS не предпочитает IPv6")
need(ICE, ice, "dual_stack::bind_udp_dual(0)", "STUN-клиент не dual-stack")
need(DIRECT, direct, "dual_stack::canonical(conn.remote_address())", "адрес входящего соединения не нормализуется")

# ── 5. Тесты, которые нельзя удалять ────────────────────────────────────────
for test in (
    "fn ipv4_only_network_keeps_working",
    "fn v4_mapped_addresses_become_plain_ipv4",
    "fn dual_stack_socket_accepts_ipv4_traffic",
    "fn ipv6_is_preferred_over_ipv4_and_link_local_is_skipped",
):
    need(DUAL_STACK, dual, test, "пропал тест dual-stack (IPv4-путь не защищён)")
need(
    PEER_EXCHANGE,
    peer,
    "[::ffff:1.1.1.1]:7777",
    "пропал тест: v4-mapped глобальный IPv4 считается публичным",
)

# ── 6. Часть 2: устойчивость сети (не откатить молча) ───────────────────────
timing = read(TIMING)
network_path = read(NETWORK_PATH)
network_dialog = read(NETWORK_DIALOG)
settings_screen = read(SETTINGS_SCREEN)

if problems:
    for problem in problems:
        print(f"✗ {problem}")
    sys.exit(2)

need(
    TIMING,
    timing,
    "pub struct BackoffPolicy",
    "пропала единая политика пауз (network/timing.rs)",
)
need(
    TIMING,
    timing,
    "pub const RECONNECT",
    "нет политики переподключения брокера",
)
need(
    QUIC,
    quic,
    "pub const QUIC_INITIAL_MTU: u16 = 1200",
    "QUIC снова может уйти выше 1200 байт и развалиться на IPv6-туннеле",
)
need(QUIC, quic, "transport.min_mtu(QUIC_MIN_MTU)", "нет пола MTU")
need(
    DIRECT,
    direct,
    "pub fn network_changed(",
    "смена сети больше не сбрасывает пул прямых соединений",
)
need(
    CORE,
    core,
    "crate::network::mqtt_transport::wake_reconnect()",
    "смена сети больше не будит переподключение брокера",
)
need(
    DIRECT,
    direct,
    "pub fn set_relay_only(",
    "пропала настройка «только через relay»",
)
if direct.count("if self.is_relay_only()") < 4:
    problems.append(
        "direct_transport.rs: «только через relay» проверяется не во всех путях отправки"
    )
need(
    NETWORK_PATH,
    network_path,
    'getBoolean(KEY_RELAY_ONLY, false)',
    "«только через relay» должно быть выключено по умолчанию",
)
need(
    NETWORK_PATH,
    network_path,
    'getBoolean(KEY_SAVE_TRAFFIC, false)',
    "«экономия трафика» должна быть выключена по умолчанию",
)
need(
    NETWORK_DIALOG,
    network_dialog,
    "NetworkPathSettings.setRelayOnly",
    "переключатель «только через relay» пропал из интерфейса",
)
need(
    SETTINGS_SCREEN,
    settings_screen,
    "MessagesNetworkDialog(",
    "диалог «Сеть сообщений» должен оставаться вынесенным (потолок god-файла)",
)

# ── 7. Часть 3: kill-switch IPv6 (не откатить молча) ────────────────────────
# Сеть бывает с IPv6 «на бумаге»: адрес объявлен, маршрута нет. Переключатель
# обязан убирать v6 из выбора адресов, из публикации и из привязки сокетов, а
# IPv4-путь при этом оставаться полностью рабочим.
need(DUAL_STACK, dual, "pub fn set_ipv6_enabled(", "пропал kill-switch IPv6 в ядре")
need(DUAL_STACK, dual, "pub fn ipv6_enabled(", "пропало чтение kill-switch IPv6")
if dual.count("if !ipv6_enabled()") < 3:
    problems.append(
        "dual_stack.rs: kill-switch не учитывается в bind_udp_dual/bind_tcp_dual/resolve_ordered"
    )
need(
    DUAL_STACK,
    dual,
    "if !ipv6 && ip.is_ipv6()",
    "kill-switch не влияет на выбор адресов (rank/reachable)",
)
need(DUAL_STACK, dual, "fn ipv6_kill_switch_leaves_ipv4_path_intact", "пропал тест kill-switch")
need(
    ROOT / "rust-core/src/lib.udl",
    read(ROOT / "rust-core/src/lib.udl"),
    "void set_ipv6_enabled(boolean enabled);",
    "kill-switch IPv6 не доходит до приложения (нет в lib.udl)",
)
need(
    NETWORK_PATH,
    network_path,
    "setIpv6Enabled(",
    "настройка «Использовать IPv6» пропала из приложения",
)
need(
    NETWORK_PATH,
    network_path,
    "getBoolean(KEY_IPV6, true)",
    "kill-switch IPv6 должен быть включён по умолчанию",
)
need(
    NETWORK_DIALOG,
    network_dialog,
    "NetworkPathSettings.setIpv6Enabled",
    "переключатель «Использовать IPv6» пропал из интерфейса",
)

# ── 8. Часть 4: оба стека в каталоге адресов и в DHT ────────────────────────
# Формат `AddressClaims` не менялся: два стека — это две обычные подписанные
# аренды одного узла. Проверяем, что вторая аренда не потерялась и что
# DHT-ответ умеет нести второй адрес: первое поле прежнее, новый адрес — в
# начале кольца, поэтому старый телефон просто не пробует его, а не ломается.
lookup_src = read(NETWORK / "address_lookup.rs")
need(NETWORK / "address_lookup.rs", lookup_src, "pub fn reply_payload_multi(", "DHT-ответ снова умеет только один адрес")
need(NETWORK / "address_lookup.rs", lookup_src, "pub const MAX_ADDRS_PER_NODE", "пропал предел адресов на узел")
need(NETWORK / "address_lookup.rs", lookup_src, "pub fn merge_addrs(", "пропало слияние адресов двух стеков")
need(NETWORK / "address_lookup.rs", lookup_src,
     "fn reply_carries_both_stacks_and_stays_readable_by_the_old_shape",
     "пропал тест: DHT-ответ с двумя стеками читается старым разбором")
need(PEER_EXCHANGE, peer, "self_leases: Vec<AddressLease>", "свои аренды снова только по одному стеку")
need(PEER_EXCHANGE, peer, "pub fn plan_multi(", "движок не может объявить оба стека")
need(PEER_EXCHANGE, peer, "fn both_own_stacks_are_published_as_separate_leases",
     "пропал тест: оба стека уезжают в каталог двумя арендами")
need(CORE, core, "public_addr_alt", "движок не помнит внешний адрес второго стека")
need(CORE, core, "directory.plan_multi(", "оба стека не передаются в каталог")
need(CORE, core, "stun_via_side_channel_family(", "нет запроса отражения второго стека")
need(DIRECT, direct, "pub async fn stun_via_side_channel_family(", "пропал STUN по выбранному стеку")
# Карточка «Сеть сообщений» обязана отличать «в сети нет IPv6» от «IPv6
# выключен в настройках» — иначе совет человеку неверный.
DIRECT_TEXT = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/diagnostics/DirectChannelText.kt"
direct_text = read(DIRECT_TEXT)
need(CORE, core, "ipv6={}", "ядро не сообщает, включён ли IPv6 в настройках")
need(DIRECT_TEXT, direct_text, "ipv6Enabled", "приложение не разбирает kill-switch из строки ядра")
need(DIRECT_TEXT, direct_text, "IPv6 выключен в настройках", "приложение не объясняет выключенный IPv6")

if problems:
    for problem in problems:
        print(f"✗ {problem}")
    sys.exit(1)

print("✓ dual-stack: IPv6 используется, IPv4-путь и откат на месте")
print("✓ устойчивость сети: смена сети, MTU, паузы, настройки пути на месте")
print("✓ kill-switch IPv6: выключение убирает v6, IPv4-путь не меняется")
print("✓ оба стека в каталоге адресов: две подписанные аренды и DHT-ответ с двумя адресами")
