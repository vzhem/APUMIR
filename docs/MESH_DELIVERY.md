# Mesh-доставка (эпидемический store-and-forward)

> Связан с: `MASTER_PLAN_v2.md` (Фазы 2.3, 3.4, 7.4), `OFFLINE_DELIVERY.md`,
> `AI_COLLABORATION_NOTES.md` (раздел «🌐 Mesh store-and-forward»).
>
> Это **фундамент**, который строится заранее, чтобы 1:1-чаты, группы и каналы
> получили надёжную доставку офлайн-получателям через одну и ту же механику.

---

## 0. Назначение и сценарий

Доставить сообщение получателю, который **офлайн**, даже если:

- отправитель и получатель **никогда не бывают в сети одновременно**;
- отправитель **ушёл офлайн** сразу после отправки.

**Сценарий (CORE-мысль пользователя):**

1. Анна хочет написать Стасу. Стаса нет в сети.
2. Анна рассылает E2E-сообщение (адресованное Стасу) **всем телефонам, что сейчас онлайн**.
3. Анна может **сама уйти офлайн**.
4. Каждый телефон-узел **хранит** это сообщение и **пересылает новым** появившимся телефонам
   (эпидемически), пока Стас не появится.
5. Когда Стас появляется — **любой узел доставляет** ему сообщение.
6. Стас рассылает **«я получил msg_id»** → все узлы **удаляют** это сообщение у себя.

Внешние ресурсы (MQTT/STUN/registry) — **только discovery**: помочь телефонам найти друг
друга. Содержимое живёт **только на телефонах** (E2E-зашифрованное).

---

## 1. Принципы

- **Телефон = мини-сервер и relay.** Любой телефон может хранить и пересылать чужие
  зашифрованные сообщения (не имея возможности их прочитать).
- **E2E везде.** Relay-узел видит только `msg_id`, `recipient`, `TTL`, ciphertext.
  Расшифровать может только получатель.
- **Дедупликация по `msg_id`** на каждом узле и у получателя.
- **Cleanup по receipt.** Доставленное сообщение удаляется из всех relay-узлов после того,
  как получатель подтвердил получение.
- **Self-healing.** Потерянный receipt → сообщение живёт до TTL, потом удаляется.
- **Ограничения ресурсов.** Per-recipient и global лимиты на relay-очередь; relay можно
  разрешить/запретить пользователем (батарея/трафик — Фаза 7.4).

---

## 2. Что уже есть (строим на этом)

- `rust-core/src/network/message_queue.rs` — `MessageQueue` (HashMap<recipient, Vec<QueuedMessage>>),
  TTL 7 дней, retry max 10, лимиты per-recipient/global. **Это заготовка relay-очереди.**
- В `engine/core.rs` (`run_mqtt_transport`, `run_mdns_discovery`) — **при появлении peer'а**
  ядро выдаёт ему накопленные сообщения (`dequeue_for`). Базовый store-and-forward работает
  (подтверждено тестом: Анна → офлайн-Стас → доставка при появлении + ✓✓).
- ACK/DELIVERED по транспорту телефон↔телефон уже работает (фикс `0c992b9`).
- `RelayEnvelope` (`data/relay/RelayEnvelope.kt`) — чистый Kotlin build/parse для message/ack.

**Чего нет (разрыв до полного mesh):**

| Нужно | Статус |
|---|---|
| Relay **для чужих** сообщений (не только своих) | ❌ |
| **Эпидемический gossip**-обмен при peer-discovered | ⚠️ частично (только своим) |
| **E2E payload** в канале (сейчас открытый текст) | ❌ (Фаза 8.1) |
| **Receipt** расходится по mesh → cleanup | ❌ (ACK только отправителю) |

---

## 3. Модель данных

### 3.1. RelayMessage (единица хранения в mesh)

```
RelayMessage {
  msg_id:        String      // глобальный UUID — ключ дедупликации
  recipient:     NodeId      // кому адресовано (только он расшифрует)
  origin_sender: NodeId      // кто изначально отправил (для ACK/статуса)
  chat_scope:    String      // chat_id (1:1) ИЛИ group_id/topic (группы/каналы)
  e2e_payload:   Bytes       // ciphertext — relay НЕ читает
  created_at:    Instant
  expires_at:    Instant     // TTL (по умолчанию 7 дней)
  hop_count:     u8          // защита от петель (max hops)
}
```

### 3.2. RelayQueue (на каждом телефоне)

- Хранит `RelayMessage` **для других** (узел = relay), с TTL и лимитами.
- Отличается от **Outbox** (свои исходящие, ждущие доставки) — но может быть одной
  структурой с разными ролями (своя / relay).
- API: `enqueue`, `for_recipient(B)` (выдать всё для B, когда B появился), `remove(msg_id)`
  (cleanup), `digest()` (сводка `{msg_id, recipient}` для gossip), `cleanup_expired()`.
- **Персистентность:** пока RAM (как MessageQueue); SQLite-персистентность — отдельный шаг (M8),
  чтобы переживать рестарт.

### 3.3. Wire-формат (расширение envelope)

Добавить типы в конверт (и в Rust, и в Kotlin `RelayEnvelope`):

- `relay` — переносимое сообщение: `{type, msg_id, recipient, origin, chat_scope, ttl, hop, e2e_payload}`.
- `receipt` — подтверждение получения: `{type, msg_id, recipient, ts, sig}` (подпись получателя).
- `gossip_summary` — сводка relay-очереди узла: `{type, items:[{msg_id, recipient}, ...]}`.

---

## 4. Протокол

### 4.1. Отправка (origin)

1. Анна создаёт сообщение → `msg_id`.
2. E2E-шифрует payload **для Стаса** (ключ/сессия Стаса).
3. Если Стас онлайн → прямая доставка (как сейчас).
4. Если Стас офлайн → Анна:
   - кладёт в свой Outbox (своё сообщение, ждёт Стаса);
   - формирует `RelayMessage{recipient:Стас}` и **флудит** её всем онлайн-узлам (C, D, …);
   - может уйти офлайн.

### 4.2. Распространение (gossip)

- При **peer-discovered** (X встретил Y): X и Y обмениваются `gossip_summary`
  (список `{msg_id, recipient}`).
- Для каждого `msg_id`, которого нет у собеседника, но есть у меня и **не доставлено** →
  отправить ему полный `relay`-конверт.
- Hop-лимит и TTL защищают от петель и бесконечного распространения.
- Дедуп: узел не кладёт в RelayQueue уже известный `msg_id`.

### 4.3. Доставка

- Когда локальный узел видит `relay`-конверт, где **`recipient == я`**:
  - расшифровывает `e2e_payload` → текст;
  - эмитит `MessageReceived` (существующий путь);
  - отправляет `receipt{msg_id}` (см. 4.4).
- Когда узел видит `relay`-конверт, где `recipient != я`:
  - кладёт в свою RelayQueue (если ещё не знаю `msg_id`);
  - будет распространять дальше (gossip).

### 4.4. Receipt и cleanup

- Получатель, приняв сообщение, рассылает `receipt{msg_id, recipient:я, sig}` всем онлайн +
  кладёт в свой Outbox-receipt (чтобы разослать появляющимся узлам).
- Любой узел, видя `receipt{msg_id}` для сообщения, которое он **хранит в RelayQueue**, →
  `remove(msg_id)` (cleanup).
- Origin-отправитель (Анна), видя `receipt{msg_id}` → ставит статус `DELIVERED` (✓✓).
- Receipt тоже дедуплицируется и TTL-ится.

### 4.5. Дедупликация и TTL

- `msg_id` уникален глобально (UUID origin). Получатель игнорирует дубли. Узлы не хранят дубли.
- `cleanup_expired()` удаляет просроченные (по `expires_at`) из RelayQueue и Outbox.

---

## 5. Безопасность

- **E2E payload:** `e2e_payload` зашифрован **получателю** (X25519 + AEAD; сессия/ratchet —
  Фаза 8.2). Relay-узлы и транспорт видят только ciphertext.
- **Подписанные receipts:** `receipt` подписан ключом получателя → нельзя подделать
  подтверждение и преждевременно удалить сообщение.
- **Метаданные:** `msg_id`, `recipient`, `chat_scope` видны relay-узлам (нужно для маршрутизации).
  Минимизация/сокрытие — Фаза 8.3 (onion/padding).
- **relay-узел НЕ имеет приватных ключей** других узлов.

---

## 6. Группы и каналы (переиспользование фундамента)

- Сообщение группы/канала = **N отдельных E2E-envelope**, по одному на участника
  (каждый зашифрован своему участнику). Все N копий идут через **тот же mesh**.
- `chat_scope = group_id` (или `group_id/topic` для тем). Fan-out отличается только
  шагом «создать N копий»; relay/gossip/receipt/cleanup — **идентичны** 1:1.
- Будущее: **Sender Key** (Signal group ratchet) — один ciphertext на группу + N headers
  (экономия). Mesh-механика та же.
- Поэтому **фундамент (RelayQueue + gossip + receipt) строим сейчас** — группы получат
  надёжную офлайн-доставку бесплатно.

---

## 7. План реализации (маленькими шагами, каждый с тестом)

- **M0** — этот дизайн-документ (фундамент-спецификация). ✅
- **M1** — `RelayQueue` в Rust (хранение **чужих** сообщений, TTL, дедуп, лимиты) + unit-тесты.
  (RAM; персистентность — M8.)
- **M2** — wire-форматы `relay` / `receipt` / `gossip_summary` (Rust + Kotlin `RelayEnvelope`).
- **M3** — gossip-обмен при peer-discovered (сводки + пересылка недоставленных).
- **M4** — доставка: `recipient == я` → расшифровать → `MessageReceived`.
- **M5** — receipt → cleanup RelayQueue + `DELIVERED` у origin.
- **M6** — интеграция в отправку: recipient офлайн → флуд онлайн-узлам + Outbox.
- **M7** — E2E-шифрование payload получателю (Фаза 8.1; пока payload «как есть» — небезопасно).
- **M8** — персистентность RelayQueue (SQLite) — переживать рестарт.
- **M9** — группы: fan-out N копий через mesh (Фаза 3.x).

Каждый шаг: код → сборка (Rust + APK) → установка на 2–3 телефона → тест → запись в памятку.

---

## 8. Открытые вопросы

- **Gossip-обмен:** полные сводки vs Bloom-filter/хэши (полоса трафика).
- **TTL:** 7 дней (как MessageQueue) или больше для «доставки через неделю»?
- **Hop-лимит** и анти-петля (кто кому уже пересылал).
- **Relay-политика — направление решено 2026-08-14:** приоритет 1 = друзья/прямые
  контакты; 2 = друзья друзей по проверяемой/signed цепочке; 3 = любые узлы только opt-in,
  когда телефон свободен, не перегрет и имеет достаточные батарею/сеть/место. Нужны weighted
  fair queues и отдельные квоты. Пользователь выбирает один из трёх режимов: «Лёгкий»,
  «Средний», «Без ограничений». Текущий M3(c.2) backend = средний (16/256 KiB round,
  32/512 KiB за 30 с); UI/config позже. «Без ограничений» снимает только soft traffic budget,
  но не hard safety (dedup/TTL/hops/queue/storage/thermal/OS).
- **Background receive-only — решение 2026-08-14:** Android должен периодически просыпаться
  при наличии сети для получения только собственных сообщений, не становясь relay. Отдельный
  будущий шаг: WorkManager → receive-only mode → own-topic/signed bounded pull → короткое окно
  → stop; никакого enqueue/forward чужого. Exact wake Android не гарантирует; real-time только
  через отдельный opt-in foreground service. Нужны sender/recipient quotas против offline-load.
- **Надёжность receipt:** r1 reconnect и r2 unique retained receipt+cleanup проверены на
  Android 2026-08-14. R3 `v11.16.10` полностью проверен: два relay дали одно local
  `MessageReceived`, второй подавлен с повторным receipt; обе очереди cleanup, origin delivery
  один раз, seen tombstones исключили re-enqueue. Fresh subscriber: retained relay=0. После
  reconnect origin: re-subscribe/probe=1, все повторные mesh/UI counts=0, PID не изменились.
  Security smoke затем подтвердил recipient origin binding: один relay того же ID с attacker
  origin дал у Стаса ровно 1 conflict-drop, у Анны/Жени seen-tombstone `1/1`, при нулевых
  receipt/UI/re-enqueue/cleanup/crash. Отдельно найден availability edge: Стас после timeout не
  получил первый setup relay; новый generation-2 setup после cold start прошёл.
  Cold start подтвердил ConnAck у Анны/Жени; Стас после 5 initial errors подключился позже
  20-секундного gate и снова видел live presence. TCP probe Стаса: HiveMQ failed, EMQX passed.
  Причина broker-fallback в текущем коде: `AsyncClient.publish().await` до polling подтверждает
  enqueue, не TCP/ConnAck, поэтому доступный второй broker не выбирается. Наивный bounded
  ConnAck→next-broker fix отклонён review до build: независимые public brokers не bridge topics,
  и телефоны на HiveMQ/EMQX окажутся в разных mesh-сегментах. Правильный fallback должен сохранять
  пересечение: bounded parallel subscriptions/publish с дедупом либо coordinated primary
  migration; затем circuit breaker/re-subscribe без flood. Queued `subscribed` не ConnAck.
  Принятый staged design: `docs/MQTT_MULTI_BROKER_DESIGN.md` (2 sessions, bounded duplicate
  window/cap, global budgets, local two-broker failure matrix до public smoke). r4.1 helper
  скомпилирован; r4.2 single-HiveMQ truthful ConnAck gate собран/установлен как v11.16.11 и дал
  effective cold-start readiness 3/3 без false success/crash. Controlled reconnect Анны также
  PASS на том же PID: один subscription request после reconnect, затем live peer traffic, без
  MQTT error/ложных markers/crash. Test message не публиковался; отдельный safe low-volume
  delivery check остановлен до publish: при живых PID, VALIDATED network и crash=0 все три
  телефона позднее дали ноль MQTT input/presence/peer/error/ConnAck/subscription markers. Это
  silent-liveness release blocker, а не delivery FAIL. Перед r4.3 обязателен r4.2-r1: наблюдаемое
  завершение EventLoop, bounded channel waits, явные periodic publish errors и local stall/recovery
  tests. r4.2-r1a добавляет payload-free phase/counter probe, independent watchdog,
  completion/shutdown markers и rate-limited stall/heartbeat logs без изменения network policy.
  Windows Android Rust build source `a357520` PASS за 68.45 с, exit=0/errors=0; arm64 `.so`
  7,148,552 B, SHA-256 `BC35DE5C…AA53995`. r1b1 добавил единый 5-секундный bounded
  AsyncClient enqueue wrapper для 8 persistent publish и 2 subscribe policies, 4 tests и heartbeat
  counters без изменения QoS/retain/topics. Windows build source `a360833` PASS за 61.53 с,
  exit=0/errors=0; arm64 `.so` 7,155,352 B, SHA-256 `A1FE612E…CBFD73D`. r1b2 добавил deterministic
  restart reasons/backoff, 45-секундный real-ConnAck ready gate и bounded full single-session
  recreation. Old transport Drop до new; уже полученный event не теряется; RelayQueue/tombstones/
  known peers/budgets сохраняются. Windows build source `f6130f6` PASS за 61.65 с, exit=0/errors=0;
  arm64 `.so` 7,167,112 B, SHA-256 `7C9537E6…86517194`. C-drive JDK17 APK build3 PASS:
  v11.16.12/11016012, 22,582,796 B, SHA-256 `6765FC7A…649A3F55`, embedded native exact.
  Signer preflight PASS: V2 new/installed=true, cert SHA-256 `F843CBE7…A4A5F7`. Data-preserving
  v11.16.12 install PASS 3/3; appId/UID/firstInstallTime/dataDir preserved. Controlled launch 3/3;
  runtime readiness/liveness recovered PASS 3/3 from `p2p_core` snapshots: same launch PID, active
  subscribed traffic through capture end, no stall/restart/request failures. Exact cold-start READY
  lines were evicted; launch is not repeated. r1b3 source добавил topic-aware overflow: последние
  32/256 slots защищены от refreshable presence/gossip/ping/summary; relay/receipt/message/ACK/
  unknown fail-closed loss-intolerant и не drop. Best-effort drop observable/rate-limited. Windows
  Rust build `a69c1a0` PASS; `.so` 7,169,720 B / `E36F32E8…6B9B066`. r1b4 source вынес
  message/relay/receipt/ACK/unknown в отдельный core-owned FIFO cap 256, shared всеми replacement
  sessions и drained раньше best-effort; full inbox останавливает дальнейший broker poll после
  initial ConnAck, не удаляя accepted event. Это in-memory session safety, не M8 persistence.
  Windows Rust build `1a43342` PASS; `.so` 7,180,888 B / `E706A900…032E7E5`. Первый v11.16.13
  APK block остановился harness-only до Gradle на PowerShell handling штатного Java stderr;
  read-only no-effect/native-hash preflight PASS. Build2 собрал v11.16.13 APK, version/V2 PASS,
  но certificate output regex остановил first state. Read-only recovery затем PASS без rebuild:
  signer/embedded r1b4 exact; authoritative APK 22,599,180 B / `5A26728B…D7C03BA`. Первый inline
  preinstall не парсился/не выполнялся; versioned parser-validated preinstall2 затем PASS 3/3 без
  phone changes. Guarded data-preserving v11.16.13 install затем PASS 3/3; identity/data preserved,
  processes stopped. Controlled launch executed once: direct startup markers PASS 2/3; Anna missing
  only READY/ConnAck/subscription lines. Saved-evidence recovery proved runtime PASS 3/3 without
  ADB/relaunch; direct startup remains 2/3. One explicit-approved functional smoke is next.
  EMQX/fanout не
  подключены; public delivery probe до новой APK и её liveness gate не повторять.
  После fresh connection generation-2 normal setup прошёл exact: relay/store/cleanup/origin у
  Анны `1/1/1/1`, у Жени `1/1/1/0`, recipient Стас local/receipt/UI=`1/1/1`, без duplicate/
  error/crash. После successful conflict rejection отдельный normal control также прошёл exact:
  intermediates store+cleanup, recipient одна local/UI delivery+receipt, origin delivery, все
  duplicate/conflict/error/crash=0 и PID stable 3/3. Первый PC publish attempt остановился до
  publish на HiveMQ ConnAck timeout; 0 refs и TCP recovery доказали безопасный единственный retry.
- **Объём relay-очереди** на телефоне (лимиты per-recipient/global — есть в MessageQueue).
