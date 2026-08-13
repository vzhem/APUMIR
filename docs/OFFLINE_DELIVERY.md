# Офлайн-доставка сообщений и файлов (Store-and-Forward)

> Связан с `MASTER_PLAN_v2.md`: Приоритет 2 (доставка), Фаза 2.1/2.2/2.3; Приоритет 3 (группы), Приоритет 5 (медиа/файлы), Приоритет 7+ (обход ограничений).

---

## 0. Назначение

Фиксирует дизайн и план надёжной доставки сообщений и файлов получателю, который
офлайн, с прицелом на группы и каналы.

**Требование:**

> Сообщение или файл, отправленные пока получатель не в сети, должны быть
> доставлены, когда он появится — даже через неделю. Это работает в обе стороны
> (отправитель тоже может уйти офлайн после отправки). Дизайн должен быть готов к
> группам и каналам: fan-out на многих офлайн-получателей.

---

## 1. Принципы

- Внешний транспорт (relay / прокси / bootstrap) видит **только E2E-зашифрованный payload**.
- Store-and-forward — это «почтовый ящик», а не «читатель».
- Устойчивость к: получатель офлайн; отправитель офлайн; relay временно недоступен;
  доставка с задержкой в часы/дни; дубли по любому пути.
- Единая дедупликация по `message_id` на всех уровнях.
- Группы/каналы = fan-out: отправитель формирует N отдельных E2E-envelope (по одному
  на участника) и кладёт в inbox каждого; relay не знает состава группы.

---

## 2. Текущее состояние (v11.16.4)

### 2.1. Что уже есть

- **Room DB** (`MessageEntity`/`MessageDao`): сообщения персистентны; статусы
  `PENDING/SENT/DELIVERED/FAILED`. Сообщения в `PENDING` переживают рестарт приложения.
  Retry: `retryAllPendingMessages()` (на `network connected`) и
  `retryPendingMessagesForPeer()` (на `peer_discovered`, + FULL SYNC последних 50).
- **Rust `MessageQueue`** (`rust-core/src/network/message_queue.rs`): store-and-forward
  очередь, TTL 7 дней, retry max 10, per-recipient 1000, global 100k.
  ⚠️ **только RAM — теряется при рестарте** (персистентность заявлена на «Фазу 1.6»).
- **CloudflareRelay** (`service/CloudflareRelay.kt`): `/send` → KV inbox получателя;
  `/poll?node=<myNodeId>` каждые 10–60 c (backoff) → `onMessageReceived`.
  Дедуп: in-memory кеш (100, по `from:payload.hashCode()`) + `messageExists(messageId)` в БД.
- **Каскад отправки** (`ChatRepository.sendMessage`):
  1. вставить `PENDING`;
  2. `RustBridge.sendMessage` (P2P + MQTT fallback для `pk_`);
  3. при сбое → CF relay `/send`;
  4. если MQTT ok, через 5 c при отсутствии `DELIVERED` → повторный постинг в CF relay (дубликат-страховка).
  → Итог: каждое отправленное текстовое сообщение в итоге попадает в CF inbox получателя.
- **Приём**: Rust-event `message_received` → `saveIncomingMessage` + ACK через MQTT;
  CF poll → разбор JSON → `saveIncomingMessage`.
- **ACK → DELIVERED**: только по MQTT (`ack|<messageId>`) → Rust-event `delivery_ack`
  → `updateMessageStatus(DELIVERED)`.

### 2.2. Ключевые пробелы

| # | Пробел | Влияние |
|---|--------|---------|
| **G1** | **ACK через relay отсутствует.** CF-delivered сообщение не порождает ACK обратно. | У отправителя статус зависает на `SENT` навсегда при доставке через офлайн-путь. |
| **G2** | **TTL/retention CF Worker неизвестен.** Исходник Worker'а не в репо. | «Доставка через неделю» не гарантирована, если Worker хранит inbox коротко / удаляет до подтверждения. |
| **G3** | **Нет гарантированного постинга в relay.** Сейчас best-effort (fallback + 5 c duplicate). Retry `PENDING` идёт через Rust/MQTT, не через CF. | Если в момент отправки MQTT и CF оба недоступны — сообщение может не попасть в relay. |
| **G4** | Rust `MessageQueue` RAM-only. | Теряется при рестарте (важно для будущего Tier1 relay). |
| **G5** | Нет явного статуса `QUEUED_OFFLINE`. | Жизненный цикл сообщения неявный. |
| **G6** | Файлы: store-and-forward отсутствует. | Файл офлайн-получателю не доставить. |
| **G7** | Группы/каналы: fan-out не реализован. | Групповое сообщение многим офлайн-участникам невозможно. |

---

## 3. Целевая модель доставки

### 3.1. Статусы (расширить `MessageStatus`)

```
PENDING        — создано, ещё не передано транспорту
QUEUED_OFFLINE — принято в локальную S&F-очередь (получатель/relay недоступны), будет retry
SENT           — успешно передано: relay принял в inbox ИЛИ P2P/MQTT доставил до узла
DELIVERED      — получатель подтвердил приём (ACK)
READ           — получатель прочитал (read receipt)
FAILED         — исчерпаны retry / истёк TTL
```

### 3.2. Каналы (`MessageChannel`)

`DIRECT_QUIC`, `LAN_MDNS`, `MQTT`, `CLOUDFLARE`, `TELEGRAM_EXPERIMENTAL`,
`P2P_RELAY`, `STORE_FORWARD`, `UNKNOWN`.

### 3.3. Жизненный цикл отправки (целевой)

1. Создать сообщение (`PENDING`), сохранить в Room (персистентно).
2. Поставить в исходящую S&F-очередь (`QUEUED_OFFLINE`).
3. Фоновый **OutboxProcessor** доставляет каскадом: P2P/QUIC → LAN/mDNS → MQTT → CF relay (store) → …
4. Relay подтвердил приём в inbox (HTTP 200) ИЛИ получатель онлайн и подтвердил → `SENT`.
5. Получатель, получив сообщение любым путём, отправляет **ACK обратно** через доступный relay.
6. Отправитель получает ACK → `DELIVERED`.
7. При чтении → `READ`.
8. TTL истёк / retry исчерпаны → `FAILED`.

### 3.4. ACK через relay (закрывает G1)

- Получатель при сохранении входящего шлёт `ack` envelope в inbox **отправителя** через CF relay
  (и/или любой доступный транспорт).
- Отправитель в `onMessageReceived` обрабатывает `type = ack` → `DELIVERED`.
- ACK подписан ключом получателя (нельзя подделать подтверждение).

### 3.5. Гарантированный постинг в relay (закрывает G3)

- OutboxProcessor постоянно крутит сообщения в `PENDING/QUEUED_OFFLINE` и периодически
  повторяет постинг в CF relay, пока не получит подтверждение хранения (HTTP 200).
- Триггеры запуска/пробуждения: старт сервиса, `network connected`, `peer_discovered`, таймер.
- Гарантия: даже если в момент отправки relay/сеть были недоступны — сообщение уйдёт позже.

### 3.6. Retention relay (закрывает G2)

- Worker должен хранить inbox с TTL **≥ 30 дней** и не удалять до успешного `poll` + подтверждения.
- Задача: обновить CF Worker, исходник вынести в репо (`worker/`) — отдельный шаг.

### 3.7. Дедупликация (закрывает хрупкость)

- Единый `message_id` (UUID) на уровне envelope.
- DB `UNIQUE(id)` + `messageExists`.
- CF poll: дедуп **по `messageId`** (заменить хрупкий `from:hashCode`).
- Relay inbox: игнорировать дубли по `messageId`.

### 3.8. Группы и каналы (готовность)

- Group message = N персональных E2E-envelope (по одному на участника), каждый с
  `group_id` + `member_sequence`.
- Отправитель кладёт N копий в relay (по inbox каждого участника). Relay не знает, что это группа.
- При появлении Groups MVP (Приоритет 3) отправка просто итерирует участников и
  использует тот же OutboxProcessor + S&F.
- Будущее: Sender Key (Signal group ratchet) — один ciphertext на группу, N headers.

### 3.9. Файлы

- Файл режется на чанки, каждый E2E-зашифрован; чанки заливаются в relay (blob store) или P2P.
- Envelope сообщения ссылается на `file_id` + manifest (список чанков + checksums).
- Получатель тянет чанки, собирает, проверяет. Blob в relay хранится с TTL.
- Store-and-forward для файлов = тот же механизм inbox + blob.

### 3.10. Безопасность

- Relay видит только ciphertext + минимальные метаданные маршрутизации (`to`/`from node_id`, `timestamp`).
- ACK подписан ключом получателя.
- Никаких приватных ключей на relay/прокси/bootstrap.

---

## 4. План реализации (маленькими шагами)

### v11.16.5 — Offline delivery core (текущий фокус)

- [ ] **D1.** Расширить `MessageStatus` (`QUEUED_OFFLINE`, `READ`) + миграция БД (версия схемы).
- [ ] **D2.** ACK round-trip через CF relay (получатель → `ack` в inbox отправителя; отправитель обрабатывает `type=ack` → `DELIVERED`). + unit-тест парсинга envelope.
- [ ] **D3.** OutboxProcessor: гарантированный постинг `PENDING/QUEUED_OFFLINE` в CF relay с retry.
- [ ] **D4.** Дедуп CF poll по `messageId` (вместо `hashCode`).
- [ ] **D5.** Запуск OutboxProcessor на старте / `onNetworkConnected` / `peer_discovered` / таймер.
- [ ] **D6.** Smoke-тест на 3 телефонах: отправить офлайн → включить через час/день → доставка + `DELIVERED` у отправителя.

### v11.16.6 — Worker retention + durability

- [ ] **W1.** Вынести исходник CF Worker в репо (`worker/`).
- [ ] **W2.** TTL inbox ≥ 30 дней, хранение до `poll` + ACK.
- [ ] **W3.** Персистентность Rust `MessageQueue` (SQLite) для Tier1 (закрывает G4).

### v11.17.0 — Groups MVP (переиспользует D1–D5)

- Fan-out через OutboxProcessor; group data model.

### v11.x — Files store-and-forward

- Чанки, manifest, blob relay (закрывает G6).

---

## 5. Открытые вопросы

- CF Worker retention сейчас — сколько? (проверить / обновить — W1/W2).
- Inbox-ключ = `node_id` напрямую? (privacy: relay связывает `node_id` ↔ inbox; рассмотреть анонимизацию/rotation в рамках Приоритета 8).
- Отдельный ACK-канал или хватает inbox-ACK? (рабочая гипотеза — inbox-ACK, см. D2).
