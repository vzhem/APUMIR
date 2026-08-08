# -*- coding: utf-8 -*-
import os
import re

BASE = r"C:\APUMIR\p2p-messenger"
RUST_CORE = os.path.join(BASE, "rust-core")

print("=" * 70)
print("1. ВСЕ emit MessageReceived в core.rs (с контекстом 15 строк)")
print("=" * 70)
CORE_FILE = os.path.join(RUST_CORE, "src", "engine", "core.rs")
with open(CORE_FILE, "r", encoding="utf-8") as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if "MessageReceived" in line:
        start = max(0, i - 10)
        end = min(len(lines), i + 15)
        print(f"\n--- строки {start+1}-{end+1} ---")
        for j in range(start, end):
            marker = ">>>" if j == i else "   "
            print(f"{marker} {j+1}: {lines[j].rstrip()}")

print("\n" + "=" * 70)
print("2. lib.rs: FFI mapping MessageReceived (с контекстом)")
print("=" * 70)
LIB_FILE = os.path.join(RUST_CORE, "src", "lib.rs")
with open(LIB_FILE, "r", encoding="utf-8") as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if "MessageReceived" in line:
        start = max(0, i - 5)
        end = min(len(lines), i + 20)
        print(f"\n--- строки {start+1}-{end+1} ---")
        for j in range(start, end):
            marker = ">>>" if j == i else "   "
            print(f"{marker} {j+1}: {lines[j].rstrip()}")

print("\n" + "=" * 70)
print("3. mqtt_transport.rs: обработка входящих сообщений")
print("=" * 70)
MQTT_FILE = os.path.join(RUST_CORE, "src", "network", "mqtt_transport.rs")
with open(MQTT_FILE, "r", encoding="utf-8") as f:
    lines = f.readlines()

# Показать где парсится topic p2pm2/msg
for i, line in enumerate(lines):
    if "p2pm2/msg" in line or "msg/" in line:
        start = max(0, i - 5)
        end = min(len(lines), i + 20)
        print(f"\n--- строки {start+1}-{end+1} ---")
        for j in range(start, end):
            marker = ">>>" if j == i else "   "
            print(f"{marker} {j+1}: {lines[j].rstrip()}")

# Также показать где обрабатывается MqttEvent с msg
for i, line in enumerate(lines):
    if "MqttEvent" in line and ("Message" in line or "msg" in line.lower()):
        start = max(0, i - 5)
        end = min(len(lines), i + 15)
        print(f"\n--- строки {start+1}-{end+1} ---")
        for j in range(start, end):
            marker = ">>>" if j == i else "   "
            print(f"{marker} {j+1}: {lines[j].rstrip()}")

print("\n" + "=" * 70)
print("4. events.rs: ПОЛНЫЙ MessageReceived")
print("=" * 70)
EVENTS_FILE = os.path.join(RUST_CORE, "src", "engine", "events.rs")
with open(EVENTS_FILE, "r", encoding="utf-8") as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if "MessageReceived" in line:
        start = max(0, i - 3)
        end = min(len(lines), i + 12)
        print(f"\n--- строки {start+1}-{end+1} ---")
        for j in range(start, end):
            marker = ">>>" if j == i else "   "
            print(f"{marker} {j+1}: {lines[j].rstrip()}")

print("\nDone.")