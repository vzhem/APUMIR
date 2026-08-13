# 📋 МАСТЕР-ПЛАН v2.0
# APUMIR / P2P Messenger — Telegram-like UX без зависимости от центральных серверов

---

## 🤖 ДЛЯ СЛЕДУЮЩЕЙ СЕССИИ ИИ — ПРОЧТИ ПЕРВЫМ

> Полный живой документ (его поддерживает сам ИИ): [`AI_COLLABORATION_NOTES.md`](AI_COLLABORATION_NOTES.md).
> Ниже — только критичные правила. **ИИ обязан читать и поддерживать оба места без напоминания пользователя**, дописывая новое после каждой сессии.

- Пошагово, маленькими шагами: шаг → проверка → следующий шаг. Без подтверждения — никаких больших пачек задач.
- Команды для Windows-машины — Windows PowerShell, каждый блок начинается с `Set-Location`. Python — `py -3`.
- Путь к локальному клону репозитория заранее НЕ известен — подтверждать у пользователя, не предполагать `C:\APUMIR\android-app`.
- Перед публикацией/тегами/релизом — всегда отдельно спрашивать подтверждение. PR не мержить без явного разрешения.
- Правки одного файла — строго по одной с проверкой (параллельные правки одного файла ломают файл).
- Android/Rust в sandbox агента не собираются — код пишется диффами, сборка/тест на машине пользователя.

---

## 0. Назначение документа

Этот документ фиксирует новый практический roadmap APUMIR после публикации `v11.16.3`.

Старый мастер-план v1.0 остаётся архитектурной базой: Rust core, Kotlin/Compose, QUIC, DHT, mDNS, ICE/STUN, relay tiers, E2E encryption, store-and-forward, CRDT.

v2.0 добавляет практический путь развития приложения до уровня мессенджера, близкого по удобству к Telegram, но с главным отличием:

> **APUMIR должен уметь работать без внешних серверов и ресурсов.**
>
> Внешние ресурсы допускаются только как вспомогательные bootstrap/fallback-каналы: помочь найти абонента, передать invite, узнать адрес, ускорить доставку или обновление. Они не должны быть единственной точкой отказа и не должны иметь доступа к содержимому сообщений.

---

## 1. Текущая точка проекта

### Последний опубликованный релиз

- Version: `v11.16.4`
- Source commit: `991da05ec13eab11867f2b201b5c063966a61bbf`
- Release: `https://github.com/vzhem/APUMIR/releases/tag/v11.16.4`
- Проверенный APK: `P2P-Messenger-v11.16.4.apk`
- APK SHA-256: `444C255A14DEC2DACF3701BE624465BC358ABE231076B21E8E9A1EE26F3F4F55`
- Checksum (`.apk.sha256`) SHA-256: `6D62E247F8A133B152B1A4E5FB1A3796131D50420EE0D0FF5D9044202FAAE70F`
- Provenance JSON SHA-256: `0D90A8DA5D93B4F7C50D7CDDF9C17F54F4ED46747403D2013437AF3FB8C7A567`
- Установлен поверх `v11.16.3` через `adb install -r` на всех трёх тестовых устройствах.

### Проверенные устройства

- `11567254BK001192`
- `3B665800EES00000`
- `AUYF6R5923006121`

---

## 2. Главная цель продукта

Создать Android-мессенджер мирового уровня со структурой и удобством, близкими к Telegram:

- личные чаты;
- контакты;
- группы;
- темы внутри групп;
- каналы;
- голосовые сообщения;
- звонки;
- видеозвонки;
- файлы и медиа;
- реакции;
- ответы;
- редактирование;
- закрепы;
- поиск;
- уведомления;
- простая отправка приглашения другу;
- простая установка приложения по ссылке;
- простое добавление контакта после установки.

Но архитектурно APUMIR должен оставаться независимым:

- сообщения и ключи хранятся на устройствах пользователей;
- содержимое сообщений E2E-зашифровано;
- relay/bootstrap-сервисы не читают сообщения;
- приложение должно иметь fallback-сценарии без Cloudflare, Telegram, GitHub, MQTT и других внешних ресурсов;
- внешний ресурс — помощник, а не хозяин сети.

---

## 3. Принципы независимости

### 3.1. Основной режим

Основной режим должен работать через собственную P2P-сеть:

1. mDNS / LAN discovery.
2. Прямое QUIC-соединение.
3. ICE/STUN NAT traversal.
4. DHT Kademlia для поиска узлов.
5. Seed nodes для bootstrap.
6. Tier 1 / Tier 2 relay nodes.
7. Store-and-forward через доверенные P2P relay nodes.

### 3.2. Вспомогательные внешние ресурсы

Допускаются как временные или резервные каналы:

- Cloudflare Worker registry/relay;
- MQTT public brokers;
- Telegram bot/deep links;
- GitHub Releases для обновлений;
- web landing page для установки;
- STUN-серверы;
- в будущем: Nostr/Matrix/ActivityPub relays как encrypted envelope transport.

Ограничения:

- не хранить plaintext сообщений;
- не хранить приватные ключи;
- не становиться единственным способом работы;
- иметь P2P-замену в roadmap;
- быть отключаемыми.

---

# 🔴 ПРИОРИТЕТ 0 — Release, документация, безопасность процесса

## Фаза 0.1 — Release process hardening

Проблема v11.16.3: создание GitHub Release создало tag и запустило workflow, который пересобрал APK и временно добавил лишний asset `app-release.apk`.

Задачи:

- [ ] Переделать `.github/workflows/build-release.yml`, чтобы он не перезаписывал уже созданный Release.
- [ ] Добавить manual release mode.
- [ ] Добавить публикацию checksum и provenance.
- [ ] Привести имя APK к формату `P2P-Messenger-vX.Y.Z.apk`.
- [ ] Создать `docs/RELEASE_PROCESS.md`.
- [ ] Создать checklist ручной проверки перед релизом.
- [ ] Создать rollback plan.

Критерий:

- Следующий релиз публикуется без cleanup и без риска подмены проверенного APK.

## Фаза 0.2 — Документация текущей архитектуры

Задачи:

- [ ] `docs/CURRENT_ARCHITECTURE.md`
- [ ] `docs/TESTING_MATRIX.md`
- [ ] `docs/TELEGRAM_AND_REGISTRY.md`
- [ ] `docs/THREAT_MODEL.md`
- [ ] `docs/NETWORK_CASCADE.md`

---

# 🔴 ПРИОРИТЕТ 1 — Лёгкое приглашение друга, установка и добавление контакта

Это ключевой пользовательский сценарий.

## Цель

Пользователь APUMIR должен нажать одну кнопку:

> **Пригласить друга**

И отправить другу ссылку любым способом: Telegram, WhatsApp, SMS, email, QR, Nearby Share, Bluetooth, файл, локальная сеть.

Друг должен:

1. открыть ссылку;
2. если приложение не установлено — легко установить APK;
3. после установки — автоматически открыть invite;
4. увидеть профиль пригласившего;
5. нажать “Добавить контакт”;
6. сразу начать переписку.

## Фаза 1.1 — Invite Kit v1

Создать единый формат invite-пакета.

Invite должен содержать:

- `node_id`;
- `public_key` или fingerprint;
- `display_name`;
- `avatar_hash` или placeholder;
- `created_at`;
- `expires_at` опционально;
- `app_min_version`;
- `recommended_apk_version`;
- `apk_download_urls`;
- `bootstrap_hints`;
- подпись invite владельцем ключа.

Пример логической структуры:

```json
{
  "type": "apumir_invite",
  "version": 1,
  "node_id": "pk_...",
  "display_name": "Vladimir",
  "app_min_version": "v11.16.4",
  "apk": {
    "version": "v11.16.4",
    "sha256": "...",
    "urls": [
      "https://github.com/vzhem/APUMIR/releases/latest",
      "https://p2p-relay.example/install/latest.apk"
    ]
  },
  "bootstrap": {
    "dht": true,
    "mdns": true,
    "registry_url": "https://p2p-relay.1985vzhem.workers.dev"
  },
  "signature": "..."
}
```

## Фаза 1.2 — Invite links

Поддержать несколько типов ссылок:

### 1. Внутренняя ссылка приложения

```text
p2pmessenger://add?node_id=pk_...
```

Назначение: если приложение уже установлено, сразу открыть экран добавления контакта.

### 2. HTTPS app/install link

```text
https://apumir.app/i/<invite_id>
```

или временно:

```text
https://p2p-relay.1985vzhem.workers.dev/i/<invite_id>
```

Назначение:

- если приложение установлено — открыть APUMIR;
- если не установлено — показать страницу установки APK;
- после установки — дать пользователю открыть invite снова.

### 3. Telegram link

```text
https://t.me/p2p_messenger_relay_bot?start=<invite_id_or_node_id>
```

Назначение:

- удобная пересылка через Telegram;
- не основной транспорт;
- не обязательный внешний ресурс;
- fallback для пользователей Telegram.

### 4. QR invite

QR должен кодировать либо короткую ссылку, либо компактный invite bundle.

Назначение:

- добавление контакта без интернета;
- демонстрация QR на одном телефоне и сканирование другим;
- offline bootstrap.

### 5. Offline invite file

Файл:

```text
APUMIR-invite-<name>.apumir
```

Назначение:

- отправить через Bluetooth;
- отправить через Nearby Share;
- отправить через флешку;
- приложить к email;
- передать без серверов.

## Фаза 1.3 — Экран “Пригласить друга”

Добавить полноценный экран:

```text
InviteFriendScreen
```

Функции:

- [ ] Показать имя пользователя и fingerprint.
- [ ] Показать QR-код.
- [ ] Кнопка “Поделиться ссылкой”.
- [ ] Кнопка “Поделиться APK + приглашение”.
- [ ] Кнопка “Скопировать ссылку”.
- [ ] Кнопка “Скопировать Telegram-ссылку”.
- [ ] Кнопка “Сохранить invite-файл”.
- [ ] Кнопка “Показать APK для передачи рядом”.
- [ ] Показать SHA-256 APK.
- [ ] Пояснить другу: “Установи APK, затем открой эту ссылку”.

## Фаза 1.4 — Установка приложения другом

Сценарии:

### Если APUMIR установлен

- Android открывает deep link.
- APUMIR показывает карточку контакта.
- Пользователь нажимает “Добавить”.

### Если APUMIR не установлен и есть интернет

- Открывается install landing page.
- Пользователь скачивает APK.
- APK проверяется по SHA-256.
- После установки пользователь возвращается к invite.

### Если APUMIR не установлен и интернета нет

- Приглашающий отправляет APK напрямую:
  - Nearby Share;
  - Bluetooth;
  - Wi-Fi Direct;
  - USB;
  - локальная сеть;
  - QR-серия для маленьких bootstrap данных.
- После установки друг импортирует invite file или сканирует QR.

## Фаза 1.5 — Исправить текущие Telegram/deep link баги

Текущее несоответствие:

- генератор использует `p2p_messenger_relay_bot`;
- обработчик ждёт `P2PMessengerBot`;
- генератор даёт `start=<nodeId>`;
- обработчик ждёт `start=add_<nodeId>`.

Задачи:

- [ ] Вынести парсер в `InviteLinkParser.kt`.
- [ ] Поддержать `p2p_messenger_relay_bot`.
- [ ] Поддержать legacy `P2PMessengerBot`.
- [ ] Поддержать `start=<nodeId>`.
- [ ] Поддержать `start=add_<nodeId>`.
- [ ] Поддержать `p2pmessenger://add?node_id=...`.
- [ ] Поддержать `p2pmessenger://add?nodeId=...`.
- [ ] Добавить Kotlin unit tests.

## Фаза 1.6 — Registry lookup для красивого добавления контакта

При открытии invite:

- [ ] Сначала читать данные из signed invite bundle.
- [ ] Если данных не хватает — делать lookup в registry.
- [ ] Если registry недоступен — добавлять по nodeId/fingerprint.
- [ ] Показывать имя пригласившего.
- [ ] Проверять подпись invite, если она есть.

## Критерий завершения Приоритета 1

На чистом телефоне без APUMIR пользователь может получить приглашение, установить APK, открыть invite, добавить пригласившего и начать чат.

---

# 🔴 ПРИОРИТЕТ 2 — Надёжная доставка сообщений и сетевой каскад

## Фаза 2.1 — Зафиксировать фактический каскад v11.16.x

Текущий практический каскад примерно такой:

1. Rust core send.
2. MQTT fallback.
3. Cloudflare relay fallback.
4. Delayed Cloudflare duplicate, если MQTT не подтверждён.
5. TelegramRelay существует, но не интегрирован end-to-end.

Задачи:

- [ ] Описать текущий state machine доставки.
- [ ] Ввести явные каналы:
  - `DIRECT_QUIC`
  - `LAN_MDNS`
  - `MQTT`
  - `CLOUDFLARE`
  - `TELEGRAM_EXPERIMENTAL`
  - `P2P_RELAY`
  - `STORE_FORWARD`
  - `UNKNOWN`
- [ ] Ввести статусы:
  - `PENDING`
  - `SENT_TO_TRANSPORT`
  - `DELIVERED`
  - `READ`
  - `FAILED`
  - `QUEUED_OFFLINE`
- [ ] Сделать единый envelope сообщений.
- [ ] Сделать единый deduplication по `message_id`.
- [ ] Сделать единый ACK/receipt.
- [ ] Показывать канал доставки в debug mode.

## Фаза 2.2 — Cloudflare relay как временный store-and-forward

- [ ] Worker хранит encrypted payload для offline node.
- [ ] Polling забирает пачку сообщений.
- [ ] Client отправляет ACK.
- [ ] Worker удаляет доставленное.
- [ ] TTL сообщений.
- [ ] Ограничение размера inbox.
- [ ] Rate limit.
- [ ] Abuse protection.

## Фаза 2.3 — P2P store-and-forward

- [ ] Перенести store-and-forward на Tier 1/Tier 2 nodes.
- [ ] Relay nodes хранят только E2E encrypted payload.
- [ ] Пользователь может разрешить устройству быть relay.
- [ ] Ограничения по батарее, Wi-Fi, зарядке, трафику.

## Фаза 2.4 — Network diagnostics

Экран диагностики должен показывать:

- node id;
- fingerprint;
- current transport;
- connected peers;
- MQTT broker;
- Cloudflare availability;
- Telegram bot/link status;
- DHT status;
- mDNS status;
- NAT type;
- relay candidates;
- последние network events;
- export logs.

---

# 🟡 ПРИОРИТЕТ 3 — Группы как в Telegram

Пользователь хочет увидеть группы уже достаточно скоро. Поэтому группы ставятся в ранний roadmap после invite/delivery hardening.

## Фаза 3.1 — Groups MVP: простая группа без тем

Цель: пользователь может создать группу, добавить участников и писать туда сообщения.

### Функции

- [ ] Создать группу.
- [ ] Название группы.
- [ ] Аватар группы или initials.
- [ ] Добавить участников из контактов.
- [ ] Список участников.
- [ ] Отправка сообщения в группу.
- [ ] Доставка group message через fan-out: отправитель отправляет каждому участнику отдельное E2E-сообщение.
- [ ] Локальное хранение group metadata.
- [ ] System messages:
  - создана группа;
  - добавлен участник;
  - удалён участник;
  - изменено название.

### Ограничения MVP

- Нет сложной server-side группы.
- Нет единого сервера группы.
- Отправитель сам рассылает участникам.
- Для offline участников используется store-and-forward.

### Критерий

Три телефона могут состоять в одной группе и обмениваться сообщениями.

## Фаза 3.2 — Group roles

Роли как в Telegram, но P2P:

- Owner.
- Admin.
- Moderator.
- Member.
- Read-only member.

Задачи:

- [ ] Права на добавление участников.
- [ ] Права на удаление участников.
- [ ] Права на изменение названия/аватара.
- [ ] Подписанные group events.
- [ ] Проверка group event signatures.

## Фаза 3.3 — Темы внутри групп

Аналог Telegram topics.

Функции:

- [ ] Включить темы в группе.
- [ ] Создать тему.
- [ ] Название темы.
- [ ] Иконка/emoji темы.
- [ ] Закрыть тему.
- [ ] Закрепить тему.
- [ ] Сообщения внутри темы.
- [ ] Уведомления по темам.
- [ ] Mute отдельной темы.

## Фаза 3.4 — Group CRDT

Чтобы группа работала без центрального сервера:

- [ ] CRDT OR-Set для состава участников.
- [ ] CRDT для topics.
- [ ] CRDT для pinned messages.
- [ ] Vector clocks для group events.
- [ ] Conflict resolution.

## Фаза 3.5 — Большие группы

- [ ] Оптимизация fan-out.
- [ ] Relay-assisted group delivery.
- [ ] Tier 1 group relay.
- [ ] Partial sync.
- [ ] Message history sync.
- [ ] Join by invite link.
- [ ] Invite approval.
- [ ] Public/private groups.

---

# 🟡 ПРИОРИТЕТ 4 — Каналы как в Telegram

## Фаза 4.1 — Channels MVP

Канал — один или несколько админов публикуют, подписчики читают.

Функции:

- [ ] Создать канал.
- [ ] Public/private channel.
- [ ] Название, описание, аватар.
- [ ] Owner/Admin.
- [ ] Публикация постов.
- [ ] Подписка по invite link.
- [ ] Read-only для подписчиков.
- [ ] Forward/share post.

## Фаза 4.2 — Децентрализованная доставка каналов

- [ ] Подписчики получают encrypted channel updates.
- [ ] DHT record для public channel discovery.
- [ ] Signed channel manifest.
- [ ] Relay-assisted distribution.
- [ ] Store-and-forward для offline subscribers.

## Фаза 4.3 — Telegram-like channel features

- [ ] Reactions.
- [ ] Comments через linked group.
- [ ] Scheduled posts.
- [ ] Pinned posts.
- [ ] Search внутри канала.
- [ ] Channel statistics local/approximate.

---

# 🟡 ПРИОРИТЕТ 5 — Чаты как в Telegram

## Фаза 5.1 — Базовые удобства

- [ ] Ответ на сообщение.
- [ ] Пересылка сообщения.
- [ ] Копирование сообщения.
- [ ] Удаление у себя.
- [ ] Удаление у всех, если поддержано протоколом.
- [ ] Редактирование сообщения.
- [ ] Статус редактирования.
- [ ] Закреплённые сообщения.
- [ ] Черновики.
- [ ] Поиск по чату.

## Фаза 5.2 — Реакции и статусы

- [ ] Emoji reactions.
- [ ] Read receipts.
- [ ] Delivered receipts.
- [ ] Typing indicator.
- [ ] Online/last seen, если пользователь разрешил.
- [ ] Mute chat.
- [ ] Archive chat.

## Фаза 5.3 — Медиа

- [ ] Фото.
- [ ] Видео.
- [ ] Документы.
- [ ] Voice messages.
- [ ] Audio player.
- [ ] Image preview.
- [ ] File transfer progress.
- [ ] Resume interrupted transfer.
- [ ] Chunk encryption.
- [ ] Chunk checksums.

---

# 🟢 ПРИОРИТЕТ 6 — Звонки и голосовые чаты

## Фаза 6.1 — Voice calls MVP

- [ ] 1-to-1 voice call.
- [ ] Signaling через P2P/relay/bootstrap.
- [ ] WebRTC или QUIC media path.
- [ ] NAT traversal.
- [ ] Fallback через relay node.
- [ ] Mute microphone.
- [ ] Speaker toggle.
- [ ] Call notification.

## Фаза 6.2 — Video calls

- [ ] 1-to-1 video.
- [ ] Camera toggle.
- [ ] Picture-in-picture.
- [ ] Adaptive bitrate.
- [ ] Relay fallback.

## Фаза 6.3 — Group voice chats

Аналог Telegram voice chats:

- [ ] Голосовой чат внутри группы.
- [ ] Speaker/listener roles.
- [ ] Raise hand.
- [ ] Admin mute.
- [ ] Mesh mode до малого числа участников.
- [ ] Tier 1 SFU-like relay для больших комнат.

---

# 🟢 ПРИОРИТЕТ 7 — Реальная независимая сеть

## Фаза 7.1 — mDNS/LAN без интернета

- [ ] Полная проверка LAN discovery.
- [ ] Обмен сообщениями в одной Wi-Fi сети без интернета.
- [ ] Invite через QR без интернета.
- [ ] Передача APK рядом без интернета.
- [ ] LAN diagnostics.

## Фаза 7.2 — Wi-Fi Direct / Nearby / Bluetooth

- [ ] Nearby Connections API.
- [ ] Wi-Fi Direct transport.
- [ ] Bluetooth transport для коротких сообщений/invites.
- [ ] Offline emergency mode.
- [ ] Автоматическая ретрансляция между рядом находящимися телефонами.

## Фаза 7.3 — DHT и seed nodes

- [ ] Production DHT flow.
- [ ] Signed node records.
- [ ] Bootstrap через seed nodes.
- [ ] User-defined seed nodes.
- [ ] Signed seed manifest.
- [ ] DHT poisoning protection.

## Фаза 7.4 — Tier relay network

- [ ] Node rating.
- [ ] Tier classification.
- [ ] Пользовательское разрешение быть relay.
- [ ] Relay only on charging/Wi-Fi option.
- [ ] Traffic limits.
- [ ] Relay statistics.
- [ ] Local trust graph.

---

# 🟢 ПРИОРИТЕТ 7+ — Автоматический обход ограничений и самовосстановление связи

> **Цель:** приложение само находит рабочий транспорт в условиях блокировок и ограничений и автоматически восстанавливает связь. Внешние ресурсы (прокси, relay, bootstrap) используются только как вспомогательный канал и **никогда не видят plaintext** — только E2E-зашифрованный payload.
>
> Логически продолжает P7 «Реальная независимая сеть» и жёстко связан с P8 «Криптография и приватность»: всё, что проходит через любой транспорт, уже зашифровано end-to-end.

## Фаза 7+.1 — TransportManager (единый транспортный слой)

Единая точка выбора и управления всеми транспортами приложения.

- [ ] `TransportManager` как единственный вход для отправки/приёма.
- [ ] Реестр доступных транспортов с приоритетом и весами.
- [ ] Текущий активный транспорт и журнал переключений.
- [ ] Автоматический выбор лучшего транспорта по health score.
- [ ] Ручное переключение транспорта в debug-режиме.
- [ ] Единый callback статуса транспорта (online / offline / degraded).
- [ ] Метрики каждого транспорта (latency, loss, throughput).
- [ ] Graceful переключение без потери сообщений в полёте (queue + resend).

## Фаза 7+.2 — Автоматический поиск и проверка прокси

- [ ] Обнаружение доступных прокси (SOCKS5, HTTP, MTProto).
- [ ] Поддержка пользовательских прокси-списков.
- [ ] Поддержка подписок / public proxy pools.
- [ ] Автоматическая проверка reachability каждого прокси.
- [ ] Тестовое соединение через прокси (handshake + round-trip).
- [ ] Отбраковка мёртвых / медленных / подозрительных прокси.
- [ ] Кеширование последних рабочих прокси.

## Фаза 7+.3 — Proxy fallback: SOCKS5 / HTTP / MTProto

- [ ] SOCKS5 proxy transport.
- [ ] HTTP CONNECT proxy transport.
- [ ] MTProto proxy transport.
- [ ] Каскадный fallback: при сбое одного типа пробуется следующий.
- [ ] Туннелирование QUIC / WebSocket через прокси.
- [ ] Параллельные попытки на нескольких прокси (happy-eyeballs).
- [ ] Цепочки прокси (chain) и multi-hop как будущая идея.

## Фаза 7+.4 — Proxy health score и автоматическая ротация серверов

- [ ] Health score для каждого прокси / relay / bootstrap-сервера.
- [ ] Метрики: latency, success rate, throughput, stability, censorship-resistance.
- [ ] Деградация score при сбоях и таймаутах.
- [ ] Автоматический выбор top-N лучших эндпоинтов.
- [ ] Фоновый периодический health-check (probing).
- [ ] Circuit breaker: временная изоляция плохих эндпоинтов.
- [ ] Автоматическая ротация без участия пользователя.
- [ ] Уведомление пользователя при падении качества связи.

## Фаза 7+.5 — Anti-censorship modes

Режимы работы сети, выбираемые вручную или автоматически:

| Режим | Назначение |
| --- | --- |
| **Normal** | Прямые соединения, минимум прокси. Базовый режим. |
| **Restricted** | Предпочитать прокси, обход базовых блокировок DNS/IP. |
| **Heavy Censorship** | Агрессивный fallback, pluggable transports, обфускация, домино. |
| **Offline Mesh** | Только локальные P2P: LAN/mDNS, Wi-Fi Direct, Bluetooth. Без интернета. |
| **Emergency** | Минимальный bootstrap: QR, invite-файл, передача APK рядом, Bluetooth. |

Задачи:

- [ ] Переключение режимов вручную из настроек / диагностики.
- [ ] Автоопределение режима по доступности сети и блокировкам.
- [ ] Плавная деградация: Normal → Restricted → Heavy → Offline Mesh → Emergency.
- [ ] Индикатор активного режима в UI.
- [ ] Логирование причин переключения режима.

## Фаза 7+.6 — Fallback без серверов

Связь и добавление контактов без интернета и без внешних ресурсов:

- [ ] QR invite (генерация + сканирование).
- [ ] Offline invite-файл (`.apumir`).
- [ ] Передача APK рядом (Nearby Share / Quick Share).
- [ ] Bluetooth transport для коротких сообщений и invites.
- [ ] Wi-Fi Direct transport.
- [ ] LAN / mDNS transport.
- [ ] QR-серия для передачи больших bootstrap-данных.
- [ ] NFC bundle transfer (будущее).

## Фаза 7+.7 — Android VpnService / app-scoped tunnel (будущая идея)

> Идея, требующая аккуратной реализации и явного согласия пользователя (Android VPN consent dialog). Туннель **app-scoped** — только для трафика APUMIR, а не всей системы.

- [ ] App-scoped VPN-туннель только для трафика приложения.
- [ ] Маршрутизация всего трафика APUMIR через прокси / relay.
- [ ] Изоляция от системного VPN и системного DNS.
- [ ] Подмена DNS на DoH / DoT внутри туннеля.
- [ ] Split tunneling.
- [ ] Согласование с пользователем через системный диалог VPN.
- [ ] Постоянное уведомление об активном VPN-подключении.
- [ ] Тест: туннель не должен нарушать приватность или обходить user consent.

## Фаза 7+.8 — Pluggable transports и обфускация (будущее)

- [ ] obfs4-like transport.
- [ ] Domain fronting.
- [ ] Meek / новые типы pluggable transports.
- [ ] Обфускация формы трафика (padding, dummy traffic).
- [ ] Маскировка под обычный HTTPS / WebSocket.

## Фаза 7+.9 — Безопасность: прокси / relay не видят plaintext

> Жёсткое архитектурное правило: **любой внешний транспорт получает только E2E-зашифрованный envelope.** Прокси / relay / bootstrap — это почтальоны, а не читатели.

- [ ] Весь payload через proxy / relay / bootstrap — E2E encrypted.
- [ ] Прокси видит только encrypted envelope (минимальные метаданные маршрутизации + ciphertext).
- [ ] Отсутствие приватных ключей на стороне любого транспорта.
- [ ] Audit: ни один внешний транспорт не получает plaintext.
- [ ] Минимизация метаданных, передаваемых через прокси.
- [ ] Тест red-team: relay / proxy не может расшифровать даже при полном логировании.
- [ ] Sealed sender / скрытие отправителя там, где это возможно.

## Критерий завершения приоритета 7+

- [ ] В условиях блокировок / ограничений приложение автоматически переходит на рабочий транспорт без ручных действий.
- [ ] При полном отсутствии интернета работают Offline Mesh / Emergency (QR, Bluetooth, LAN, APK рядом).
- [ ] Ни один прокси / relay / bootstrap не может прочитать содержимое сообщений.
- [ ] Пользователь видит активный режим и качество связи.

---

# 🔵 ПРИОРИТЕТ 8 — Криптография и приватность

## Фаза 8.1 — Проверка текущей защиты

- [ ] Audit: отправляется ли plaintext через MQTT/CF/TG.
- [ ] Тест: relay не может прочитать content.
- [ ] Проверка сохранения ключей.
- [ ] Проверка identity после `adb install -r`.

## Фаза 8.2 — Signal-like protocol

- [ ] Полный X3DH.
- [ ] Signed prekeys.
- [ ] One-time prekeys.
- [ ] Full Double Ratchet with DH-ratchet.
- [ ] Safety numbers.
- [ ] QR verification.
- [ ] Key change warning.
- [ ] Sealed sender.

## Фаза 8.3 — Metadata protection

- [ ] Минимизация раскрытия nodeId.
- [ ] Onion routing через relay nodes.
- [ ] Traffic padding.
- [ ] Dummy traffic.
- [ ] Delayed delivery.
- [ ] Контактное обнаружение без раскрытия адресной книги.

---

# 🟣 ПРИОРИТЕТ 9 — Большие идеи и резерв будущего

## 9.1 — Offline disaster mesh

- [ ] Режим связи без SIM и интернета.
- [ ] Wi-Fi Direct mesh.
- [ ] Bluetooth mesh.
- [ ] Автоматическая локальная ретрансляция.
- [ ] Emergency broadcast.

## 9.2 — LoRa / radio bridge

- [ ] LoRa-модуль через USB/Bluetooth.
- [ ] Очень короткие encrypted messages.
- [ ] Long-distance emergency text.
- [ ] Mesh через LoRa nodes.

## 9.3 — Sneakernet transport

- [ ] Encrypted message bundle в файл.
- [ ] QR-серия для передачи сообщения.
- [ ] NFC bundle transfer.
- [ ] USB/flash drive transfer.
- [ ] Courier mode.

## 9.4 — Alternative encrypted transports

- [ ] Nostr relay как encrypted envelope transport.
- [ ] Matrix bridge как encrypted envelope transport.
- [ ] ActivityPub relay compatibility.
- [ ] DNS-over-HTTPS fallback.
- [ ] WebSocket public fallback.
- [ ] Steganographic payload in images/text.

## 9.5 — Post-quantum hybrid

- [ ] Hybrid X25519 + ML-KEM/Kyber.
- [ ] PQ prekey bundle.
- [ ] PQ session upgrade.
- [ ] Migration strategy.

## 9.6 — Multi-device and desktop

- [ ] Несколько устройств одного пользователя.
- [ ] Device linking через QR.
- [ ] Device revocation.
- [ ] Desktop app на Tauri.
- [ ] CLI/headless relay node.
- [ ] Docker image для seed/relay.

---

# 10. Ближайший практический roadmap

## v11.16.4 — Invite/deep link/registry stabilization

Цель: легко пригласить друга и корректно добавить контакт.

- [ ] `InviteLinkParser.kt`.
- [ ] Unit tests для parser.
- [ ] Исправить Telegram bot username mismatch.
- [ ] Исправить `start=<nodeId>` / `start=add_<nodeId>` mismatch.
- [ ] Registry lookup при открытии invite.
- [ ] Улучшить экран “Поделиться профилем”.
- [ ] Добавить текст “если приложение не установлено”.
- [ ] Проверить на 3 телефонах.

## v11.16.5 — Offline delivery + Install/share friend flow

> Подробный дизайн и план офлайн-доставки: [`OFFLINE_DELIVERY.md`](OFFLINE_DELIVERY.md).
> В этой версии офлайн-доставка (store-and-forward + ACK через relay) — **основной фокус**;
> install/share — второй трек.

### Трек A — Offline delivery (основной)

- [ ] D1. Расширить `MessageStatus` (`QUEUED_OFFLINE`, `READ`) + миграция БД.
- [ ] D2. ACK round-trip через CF relay → `DELIVERED` у отправителя (+ unit-тест).
- [ ] D3. OutboxProcessor: гарантированный постинг `PENDING/QUEUED_OFFLINE` в CF relay с retry.
- [ ] D4. Дедуп CF poll по `messageId`.
- [ ] D5. Запуск OutboxProcessor на старте / `onNetworkConnected` / `peer_discovered` / таймер.
- [ ] D6. Smoke-тест на 3 телефонах: доставка офлайн + `DELIVERED`.

### Трек B — Install/share friend flow

Цель: друг без приложения может легко установить APUMIR.

- [ ] Invite text содержит ссылку на APK release.
- [ ] Показывать SHA-256 APK.
- [ ] Кнопка “Поделиться APK”.
- [ ] Кнопка “Поделиться APK + invite”.
- [ ] QR invite.
- [ ] Offline invite file.

## v11.17.0 — Groups MVP

Цель: первые группы в приложении.

- [ ] Group data model.
- [ ] Create group UI.
- [ ] Add members.
- [ ] Group chat screen.
- [ ] Fan-out delivery каждому участнику.
- [ ] Group messages на 3 телефонах.

## v11.18.0 — Delivery diagnostics, channel transparency and data cleanup

- [ ] Показывать канал доставки в debug mode.
- [ ] Network diagnostics screen.
- [ ] Unified message envelope.
- [ ] Deduplication hardening.
- [ ] ACK hardening.
- [ ] Исправить дублирующие чаты, оставшиеся после старых версий.
- [ ] Добавить миграцию/утилиту слияния дублей чатов по реальному `contactId` / fingerprint.
- [ ] При слиянии сохранять историю сообщений и не терять статусы доставки.
- [ ] Добавить безопасный dry-run режим диагностики дублей перед изменением БД.

## v11.19.0 — Group topics MVP

- [ ] Включение тем в группе.
- [ ] Создание темы.
- [ ] Сообщения внутри темы.
- [ ] Mute topic.
- [ ] Pin topic.

## v11.20.0 — Channels MVP

- [ ] Create channel.
- [ ] Subscribe by invite.
- [ ] Publish posts.
- [ ] Read-only subscribers.

---

# 11. Главный пользовательский сценарий, который должен быть доведён до идеала

```text
Я хочу пригласить друга.

1. Открываю APUMIR.
2. Нажимаю “Пригласить друга”.
3. Выбираю способ отправки:
   - Telegram;
   - SMS;
   - WhatsApp;
   - email;
   - QR;
   - Bluetooth;
   - Nearby Share;
   - APK + invite file.
4. Друг открывает ссылку.
5. Если APUMIR не установлен — он легко ставит APK.
6. После установки открывается invite.
7. Друг видит мой профиль.
8. Нажимает “Добавить”.
9. Я получаю/вижу его контакт.
10. Мы сразу можем писать друг другу.
```

Этот сценарий имеет максимальный приоритет, потому что без него рост сети невозможен.

---

# 12. Главная стратегическая линия

1. Сначала — лёгкое приглашение, установка, добавление контакта.
2. Затем — стабильная доставка сообщений.
3. Затем — Groups MVP.
4. Затем — темы, каналы, медиа.
5. Затем — звонки.
6. Параллельно — уход от внешних ресурсов к собственной P2P/DHT/relay сети.

APUMIR должен стать удобным как Telegram, но архитектурно независимым как настоящая P2P-сеть.
