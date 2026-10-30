#!/usr/bin/env python3
"""Контракт dual-stack: IPv6 работает, а IPv4-путь не удалён (часть 1 задачи IPv6).

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

if problems:
    for problem in problems:
        print(f"✗ {problem}")
    sys.exit(1)

print("✓ dual-stack: IPv6 используется, IPv4-путь и откат на месте")
