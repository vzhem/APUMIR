# M8-E / M8-F — подготовительный план (2026-08-16)

Документ написан ПОСЛЕ M8-C slice 3 (source-complete) и ДО Windows compile gate.
Это план, а не код: код M8-E пишется только после compile PASS M8 (A→C3).
Правило из журнала: не накапливать нескомпилированные слои.

## Что уже готово под эти шаги

- Датчики acceptance уже вшиты в движок (M8-C slice 3):
  - UniFFI `relay_custody_mode()` → `"durable-encrypted"` / `"ram-only"` / `"disabled"`;
  - UniFFI `relay_quarantine_count()` → число записей в карантине;
  - `RustBridge.relayCustodyMode()` / `.relayQuarantineCount()` для Kotlin;
  - logcat-маркеры: `MESH durable: encrypted relay store opened ... (key_id=...)`,
    `quarantined at startup`, `purged ... (+N quarantined)`.
- Честный degrade: без Keystore-ключа — RAM-only ephemeral, durable-файл не создаётся.

## M8-E — bounded sleep/wake (после compile PASS)

Цель: relay-custody работает, когда ОС усыпляет приложение, без 24/7 foreground и
без превышения бюджетов. По ранее принятому решению пользователя: режим 🅱️ +
допустимый fallback 🅰️; receive-only в фоне; рост без спама; диагностика opt-in.

Планируемые маленькие шаги (каждый = отдельный slice с тестом):

1. **WorkManager wake (bounded):** периодический `WorkManager` worker
   (`PeriodicWorkRequest`, минимальный интервал ОС, без exact-alarms),
   который: поднимет Rust одним bounded вызовом → drain pending relay →
   flush custody → завершается. Новые разрешения — только если неизбежно
   (никаких REQUEST_IGNORE_BATTERY_OPTIMIZATIONS по умолчанию).
2. **Sleep flush hook:** при `onTaskRemoved`/stop сервиса — явный flush:
   всё недоставленное уже durable (M8-B/D/C), поэтому hook = верификация и
   лог, а не новая persistence-логика.
3. **Явное согласие на relay-режим:** UI-переключатель устройства-relay
   («Лёгкий / Средний / Без ограничений», backend default = Средний),
   приоритет друзьям. Pending-список пользователя согласия/тома — честный
   UI, без скрытой фон-работы сверх выбранного режима.
4. **Budgets для sleep/wake:** существующие medium-limity (сообщений/байт
   за round/window) применяются и к wake-циклам; счётчики логируются.
5. Риски: OEM-агрессия к фон-процессам (Xiaomi/Huawei/Samsung) — только
   документируем + честный статус, без борьбы с системой.

## M8-F — телефонный acceptance (строгий gate)

Телефоны: Анна `AUYF6R5923006121`, Женя `3B665800EES00000`, Стас `11567254BK001192`
(+ позже устройство D). Перед ЛЮБОЙ командой: назвать конкретные телефоны,
предупредить подключить/разблокировать, начать с read-only visibility gate.
Запрещены: uninstall, data clear, force-stop, logcat clear.

### Предусловия

- Windows compile gate M8 (A→C3) PASS; signed test APK собран с M8-кодом;
  data-preservation gates из прошлых harness'ов не нарушены.
- На каждом телефоне после установки: первый запуск → в logcat есть
  `Relay custody mode: durable-encrypted` (иначе честно фиксируем ram-only и
  причину; acceptance продолжается только при durable-encrypted).

### Сценарий 1 — базовый durable restart (Анна → Женя)

1. Женя online. Анна шлёт Жене сообщение, Женя НЕ читает; Анна offline.
2. На Жене/релеях: `pkill`-эквивалент только через штатный kill приложения
   (swipe) — НЕ force-stop через adb (запрещено правилами) → повторный запуск.
3. Ожидания: стартап-лог `restored N/M relay record(s)`; сообщение ровно
   ОДИН раз в UI; receipt cleanup лог `durable custody removed` на релеях;
   origin (Анна при возврате online) получает `MessageDelivered`.
4. `relay_quarantine_count` == 0 на всех после сценария.

### Сценарий 2 — delayed chain с перезагрузкой (Anna → Zhenya → D → Stas)

1. Анна шлёт Стасу (Стас offline). Женя хранит custody.
2. Через ~сутки: Женя → новый relay D в коротком overlap; Женя offline/killed.
3. D перезагружается (reboot телефона — реальный, не симуляция).
4. Стас online: получает ровно одно UI-сообщение; custody cleanup на D;
   Анна eventually DELIVERED.
5. TTL не продлевается: `expires_at_ms` в записях неизменен (spot-check через
   troubleshooting-лог quarantine/purge маркеров; чтение БД напрямую — только
   read-only и только если правила сессии это разрешат явно).

### Сценарий 3 — честные края

1. Data clear на одном устройстве (только если пользователь явно разрешит
   именно этот шаг): старые записи → quarantine (`quarantined at startup` > 0,
   `relay_custody_mode` = durable-encrypted с новым key_id), никаких phantom
   UI-сообщений, никакого crash.
2. Отключение Keystore (симулировать нельзя — пропустить или документировать
   тестовый вариант отдельно).

### Сценарий 4 — mixed N↔N-1 (оставшийся релизный gate)

- v11.16.16 (без M8) ↔ M8-сборка: сообщения ходят в обе стороны; у N-версии
  RAM-only поведение не деградирует; relay между версиями без шторма
  (счётчики логов сравниваются, как в M3(a)-тесте).

## Что НЕ входит (запреты из журнала)

- Никаких cosmetic/групп/каналов/роста/новой иконки до M8 delivery gate.
- Иконка заморожена.
- Релиз — только после M8-F PASS + mixed gate + security audit; через
  draft-flow (Arena draft → пользователь грузит APK+sha256 → verify digests →
  publish), никогда не требовать Windows gh token.
