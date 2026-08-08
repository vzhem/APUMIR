# -*- coding: utf-8 -*-
import os
import subprocess

BASE = r"C:\APUMIR\p2p-messenger"

print("=" * 70)
print("1. MessageBubble: код обработки клика по ссылке")
print("=" * 70)
BUBBLE_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                           "com", "vladimir", "messenger", "ui", "components", "MessageBubble.kt")
with open(BUBBLE_FILE, "r", encoding="utf-8") as f:
    content = f.read()

# Найти блок onTap
import re
match = re.search(r'onTap = \{ offset ->[\s\S]*?\}', content)
if match:
    print("=== onTap блок ===")
    print(match.group(0))
else:
    print("onTap блок не найден")

# Показать полный Box с pointerInput
match2 = re.search(r'Box\([\s\S]*?pointerInput[\s\S]*?\)\s*\{[\s\S]*?\}', content)
if match2:
    print("\n=== Полный Box с pointerInput ===")
    print(match2.group(0))

print("\n" + "=" * 70)
print("2. Rust core: структура MQTT topics")
print("=" * 70)
# Найти Rust файлы с MQTT
rust_mqtt_files = []
for root, dirs, files in os.walk(os.path.join(BASE, "p2p-core", "src")):
    for file in files:
        if file.endswith(".rs") and "mqtt" in file.lower():
            rust_mqtt_files.append(os.path.join(root, file))

if rust_mqtt_files:
    for fpath in rust_mqtt_files[:3]:
        print(f"\n=== {os.path.basename(fpath)} ===")
        with open(fpath, "r", encoding="utf-8") as f:
            content = f.read()
        # Найти функции publish/send
        for match in re.finditer(r'(pub fn|fn) (send|publish|mqtt)[\s\S]{0,300}', content):
            print(match.group(0)[:200])
else:
    print("Rust MQTT файлы не найдены")
    # Проверить p2p-core/src
    p2p_src = os.path.join(BASE, "p2p-core", "src")
    if os.path.exists(p2p_src):
        print(f"Файлы в {p2p_src}:")
        for f in os.listdir(p2p_src):
            print(f"  - {f}")

print("\n" + "=" * 70)
print("3. Очистка логов + инструкция для теста")
print("=" * 70)
# Очистить logcat
subprocess.run(["adb", "-s", "AUYF6R5923006121", "logcat", "-c"], shell=True)
print("✓ Логи очищены на AUYF6R5923006121")

print("\n" + "=" * 70)
print("ИНСТРУКЦИЯ ДЛЯ ТЕСТА")
print("=" * 70)
print("""
1. Откройте приложение на Анне (AUYF6R5923006121)
2. Откройте чат 'Владимир'
3. Отправьте сообщение: "Привет, это тест маршрутизации"
4. Подождите 5 секунд
5. Выполните команду ниже для сбора логов
""")

print("\nDone. Готов к сбору логов после отправки сообщения.")