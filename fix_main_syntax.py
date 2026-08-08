# -*- coding: utf-8 -*-
import os

BASE = r"C:\APUMIR\p2p-messenger"
MAIN_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                         "com", "vladimir", "messenger", "MainActivity.kt")

with open(MAIN_FILE, "r", encoding="utf-8") as f:
    lines = f.readlines()

print(f"Всего строк: {len(lines)}")
print("\n=== Строки 310-330 ===")
for i in range(309, min(330, len(lines))):
    print(f"{i+1}: {lines[i].rstrip()}")

print("\n=== Последние 15 строк файла ===")
for i in range(max(0, len(lines)-15), len(lines)):
    print(f"{i+1}: {lines[i].rstrip()}")

# Найти все функции в файле
print("\n=== Найденные функции ===")
for i, line in enumerate(lines, 1):
    stripped = line.strip()
    if stripped.startswith("fun ") or stripped.startswith("private fun ") or stripped.startswith("override fun "):
        print(f"{i}: {stripped[:80]}")

# Подсчитать фигурные скобки
opens = sum(l.count("{") for l in lines)
closes = sum(l.count("}") for l in lines)
print(f"\n{{ = {opens}, }} = {closes}, diff = {opens - closes}")