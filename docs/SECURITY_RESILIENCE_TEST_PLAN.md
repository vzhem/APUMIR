# APU — план проверок против спама, DoS и других атак

Документ описывает **защитные** испытания APU. Цель — самим находить слабые места на своих
устройствах и улучшать ограничения, дедупликацию, восстановление и приватность до релиза.

## 1. Безопасные границы

- Нагрузочные тесты проводить только на собственных телефонах, ПК, локальном тестовом broker
  и в изолированной тестовой сети.
- На публичном HiveMQ и других чужих сервисах разрешены только короткие функциональные пробы
  с уникальными test ID и малым объёмом. Запрещены flood, исчерпание ресурсов, сканирование,
  обход лимитов и попытки нарушить работу чужого сервиса.
- Высокую нагрузку запускать только после отдельного согласия пользователя и подготовки
  локального Mosquitto/mock transport, мониторинга и аварийной остановки.
- Использовать синтетические сообщения и identities; не включать переписку, ключи, tokens,
  реальные контакты и другие персональные данные.
- Перед тестом фиксировать APK/version/commit, начальные PID, заряд, температуру, свободное место,
  сеть и ожидаемые лимиты. После теста проверять обычную доставку сообщения.
- PowerShell harness выполнять одним atomic `& { ... }` с `ErrorActionPreference=Stop`: state и
  PASS записываются только после всех assertions. Каждая следующая фаза проверяет explicit
  `<phase>Complete=true`; нельзя доверять финальной строке PASS, если выше был `throw`.
- Немедленно остановить тест при перегреве, быстром падении батареи, ANR, циклическом restart,
  потере пользовательских данных, неконтролируемом трафике или влиянии на чужую инфраструктуру.

## 2. Когда повторять проверки

| Частота | Набор |
|---|---|
| Каждый relevant code change | unit/property tests, parser boundaries, dedup/TTL/hop/queue limits |
| Каждая существенно важная APK | короткий 3-phone abuse smoke test и обычное сообщение после него |
| После изменений mesh/MQTT/background | duplicate/replay, retained poison, receipt/presence/gossip limits, reconnect storm |
| После изменений media/files | malformed file, chunk flood, checksum, disk quota, cleanup, zip/decompression bomb protection |
| После изменений invite/username/registry | forged invite, enumeration/rate limit, impersonation, key mismatch, Sybil simulation |
| Release candidate | полный controlled regression + local load + 1–2 hour bounded soak |
| Периодически и перед крупным релизом | dependency/update/signing review, diagnostics redaction, восстановление после нагрузки |

Результат каждой важной проверки записывать: версия, commit, устройства, сценарий, счётчики,
пределы, найденные проблемы, исправление и повторный результат. Провал не скрывать и не называть
релиз защищённым до retest.

## 3. Наборы атак и ожидаемая защита

### 3.1. Спам и повторы сообщений

Проверки:

- много повторов одного `msg_id` от того же origin;
- тот же `msg_id` с другим origin или изменённым payload;
- небольшая серия уникальных `msg_id` одному recipient;
- notification/UI spam при duplicate transports;
- повтор после receipt cleanup, reconnect и restart процесса;
- старые retained relay/receipt/ACK/summary после новой подписки.

Ожидается:

- один пользовательский message/event на `msg_id`;
- conflicting origin отклоняется;
- повтор того же origin может вызвать bounded receipt, но не второй UI event;
- relay после cleanup не возвращается в очередь в пределах bounded tombstone;
- per-origin/per-recipient/global token buckets, cooldown и coalesced notifications;
- нормальный пользователь не блокируется навсегда из-за краткого всплеска.

### 3.2. Flood/DoS на mesh и MQTT

Проверки:

- relay flood, receipt/ACK flood, presence churn и частые `gsumm`;
- summary с 0, 1, limit и limit+1 items;
- envelopes с 0, limit и limit+1 bytes;
- очередь до per-recipient/global cap и попытка ещё одного enqueue;
- reconnect/network-flap storm;
- медленный consumer и заполнение bounded event channel;
- retained-topic poisoning и wildcard cross-topic injection;
- много simulated peers/Sybil identities в локальной лаборатории.

Ожидается:

- ранний drop до дорогого decode/storage;
- жёсткие caps на bytes/items/queue/hops/TTL и bounded channels/caches;
- fair scheduling без вытеснения сообщений друзей одним отправителем;
- backoff+jitter, circuit breaker и ограничение reconnect/presence;
- процесс не падает, не перезапускается и после нагрузки снова принимает обычное сообщение;
- drop reason и rate-limit counters видны локально в diagnostics без plaintext.

### 3.3. Некорректные и враждебные wire payloads

Проверки:

- пустые/пропущенные/лишние поля;
- invalid UTF-8/base64, очень длинные IDs и topic segments;
- числовые overflow/underflow для TTL/hop/timestamp;
- TTL=0, max, max+1; hop=limit и limit+1;
- неизвестный tag/version и смешение legacy/new formats;
- случайные bytes и mutation/fuzz corpus;
- payload, похожий одновременно на ACK/relay/receipt/summary.

Ожидается: deterministic reject без panic/ANR, allocation spike, enqueue, UI event или receipt
для недостоверного payload. Парсер должен иметь unit/property/fuzz tests и максимальные размеры
до split/base64/JSON/decompression.

### 3.4. Replay, spoofing и integrity

Проверки:

- replay старого relay/receipt/invite после TTL;
- receipt для неизвестного `msg_id`/неверного recipient;
- forged sender/origin, topic не соответствует envelope;
- изменение ciphertext/chunk/manifest/checksum/signature;
- key-change и попытка привязать чужой `@username` к другому `pk_…`;
- protocol downgrade на legacy unsigned format;
- clock skew и повтор nonce после появления полноценного E2E.

Ожидается: signed envelope/binding, recipient/topic checks, freshness window, replay cache,
checksums/MAC, key-change warning и безопасная legacy migration. До M7 отсутствие подписи считать
явным известным ограничением, а не доказанной защитой.

### 3.5. Identity, invite, registry и Sybil

Проверки:

- массовые lookup точных `@username`, перебор и case variants;
- регистрация похожих имён, rename churn и squatting;
- forged/expired invite, неправильная подпись/fingerprint;
- сотни synthetic node IDs, лживый friend-of-friend claim;
- смена public key, registry poisoning и недоступность registry.

Ожидается: exact normalized username, signed PK binding, rate limits/privacy, cooldown, uniqueness,
invite expiry/signature, trust tiers и отсутствие автоматического доверия неизвестному Sybil-узлу.
При недоступности registry signed invite/PK продолжает работать.

### 3.6. Android/UI/background abuse

Проверки:

- notification flood и repeated intents/deep links;
- malformed/deeply nested invite URI, oversized clipboard/QR/input;
- многократный start/stop service и смена сети;
- wake-up abuse, WorkManager rescheduling loop, battery/background restrictions;
- exported components/FileProvider permissions и попытки path traversal;
- app upgrade/restart во время очереди и нехватка storage.

Ожидается: bounded/coalesced notifications, strict URI/input limits, no exported internal service,
safe FileProvider paths, bounded receive-only wake, backoff и сохранность данных после upgrade.

### 3.7. Media/files/storage attacks

Проверки после реализации media:

- неверный MIME/extension, path traversal в имени, нулевой/огромный файл;
- слишком много chunks, duplicate/out-of-order/missing chunks;
- checksum mismatch, corrupted encrypted chunk и malicious manifest;
- zip/decompression bomb, oversized image dimensions и parser bombs;
- заполнение cache/disk, истечение TTL и interrupted cleanup;
- один peer пытается занять всю квоту.

Ожидается: имя нормализуется, файл остаётся в sandbox, decode имеет dimension/size limits,
chunks/manifest подписаны и bounded, disk reservation/quota per peer, checksum до открытия,
cleanup освобождает место, а media имеет ниже приоритет, чем короткие сообщения/receipts.

### 3.8. Диагностика и приватность

Проверки:

- crash report с тестовыми secrets, message text, token, username, IP/SSID и node ID;
- preview до отправки, opt-in/opt-out, offline retry/dedup/retention/delete;
- oversized logs и repeated same crash;
- подмена collector/support key и network interception в тестовой среде.

Ожидается: redaction до записи/отправки, salted identifiers, явное согласие, encryption+HTTPS,
bounded report, dedup/backoff, project-controlled endpoint и возможность удалить/export locally.

### 3.9. Update, APK и supply chain

Проверки:

- APK с неверной подписью/versionCode/SHA-256;
- downgrade и подмена update metadata;
- несовпадение tag/commit/APK/provenance;
- повреждённый Gradle dependency/cache и dependency vulnerability review;
- восстановление milestone-копии на чистом ПК.

Ожидается: signature/checksum/provenance verification, monotonic versionCode, rollback plan,
pinned/reviewed dependencies и доказанная clean-PC recovery по
`BACKUP_AND_CLEAN_PC_RECOVERY.md`.

## 4. Что можно безопасно проверить своими силами

### Уровень A — сейчас, на трёх телефонах

Короткие тесты, по одному сценарию и с проверкой после каждого:

1. 2–5 одинаковых relay одного ID → один UI event, bounded receipts, no re-enqueue.
2. Один ID с другим origin → conflict drop, без receipt/UI.
3. TTL/hop boundary и несколько malformed envelopes → drop без crash.
4. Duplicate receipt/unknown receipt → cleanup/origin-delivery максимум один.
5. Fresh subscription после c2 → нет retained relay.
6. 2–3 controlled reconnect cycles → PID стабилен, backoff, одна subscription, без replay.
7. Короткая серия уникальных test IDs значительно ниже лимитов → caps/counters корректны,
   после серии обычное сообщение доставляется.

На публичном broker не увеличивать этот уровень до настоящей нагрузки.

### Уровень B — после подготовки локальной лаборатории

- локальный Mosquitto без доступа извне;
- отдельные synthetic clients на ПК;
- controlled сотни/тысячи сообщений и peers;
- queue saturation, slow consumer, connection churn;
- parser fuzz corpus и mutation tests;
- 1–2 hour bounded soak с CPU/RAM/network/battery/temperature monitoring.

Уровень B нельзя переносить на публичный HiveMQ. Перед ним зафиксировать максимальную скорость,
объём, длительность и аварийную остановку.

### Уровень C — позднее перед production security claim

- независимый code/crypto review;
- Android static/dynamic analysis;
- dependency/SBOM audit;
- external pentest только с письменным scope и на собственной инфраструктуре;
- fuzzing длительного времени в CI/отдельной машине.

## 5. Метрики и критерии прохождения

Для каждого теста собирать только необходимые безопасные данные:

- PID/restart/crash/ANR;
- число входов, accepted, dropped и причина drop;
- UI deliveries, receipts, cleanup, origin-delivery;
- queue/cache/channel sizes и evictions;
- CPU/RSS, network bytes, storage, battery и temperature до/после;
- reconnect attempts/backoff/subscription count;
- время восстановления и результат обычного control message.

Базовый gate:

- 0 crash/ANR/unexpected restart;
- не более одного пользовательского события на `msg_id`;
- queue/cache/storage не превышают hard caps;
- трафик/CPU/батарея прекращают расти после окончания входа;
- приложение восстанавливается без очистки данных;
- control message после теста доставляется;
- в logs/reports нет plaintext/secrets;
- каждое отклонение объясняется конкретным bounded rule.

Численные CPU/RAM/battery thresholds задавать после baseline на трёх моделях телефонов; не
придумывать один процент без измерений.

## 6. Какие улучшения делать по результатам

| Наблюдение | Типичное улучшение |
|---|---|
| Duplicate дошёл в UI | persistent/bounded recipient dedup, signed origin binding |
| Повтор вернулся после cleanup | retained cleanup, tombstone/replay cache, TTL |
| Один peer заполнил очередь | per-origin/per-recipient quota, fair queue, trust priority |
| CPU вырос на malformed input | size check до parse/decode, parser budget, fuzz regression |
| Receipt/presence storm | token bucket, cooldown, aggregation, backoff+jitter |
| Reconnect storm | circuit breaker, capped exponential backoff, single connection owner |
| Notification spam | coalescing, per-chat rate limit, mute/report/block controls |
| Disk/cache exhaustion | reservation, hard quota, LRU/TTL cleanup, media lower priority |
| Sybil вытесняет друзей | signed trust graph, friend priority, unknown-peer global cap |
| Diagnostics раскрывает данные | redaction before persistence, preview, allowlist fields |
| После нагрузки нет восстановления | watchdog state machine, bounded queues, recovery test in gate |

Каждый найденный дефект превращать в минимальный regression test до или вместе с исправлением.
Сначала доказать проблему малым безопасным сценарием, затем исправить, повторить тот же тест и
только после этого повышать нагрузку.

## 7. Журнал коротких security smoke

- **2026-08-14, `v11.16.10`, conflicting-origin preparation:** первая попытка baseline invalid:
  процесс Жени был остановлен, а следующие интерактивные PowerShell-блоки продолжили работу после
  `throw` и напечатали ложный READY. Attack не выполнялся. Harness исправлен на единый atomic
  `& { ... }` + explicit `baselineComplete`.
- Повторный baseline прошёл: security ID `sec-origin-1786707178388`; PID Анна/Женя/Стас =
  `12571/22100/2529`; temperature = `30/28.8/30°C`; PSS = `118986/88012/53898 KiB`;
  battery = `100/100/57%`. Женя cold-start, MQTT subscription подтверждена; все три logcat
  очищены. Identity preflight затем подтвердил origin/recipient из original r3 state и поставил
  `identityValidated=true`; повтор local-only preflight безопасен и не отправляет сеть.
- Первая normal-setup попытка завершилась до сети: helper ожидал `sys.argv[1]`, но PowerShell не
  передал state-path; traceback возник до MQTT client/connect/publish. Atomic recovery затем
  подтвердил `publishConfirmed=false`, test-ID counts `0/0/0`, прежние три PID и записал
  failed-before-network marker; logs очищены.
- Controlled retry опубликовал ровно один normal relay `sec-origin-1786707178388`: QoS1,
  retain=false, 160 bytes. Saved evidence: Анна/Женя input+stored=`1/1`; Стас
  input/delivery/receipt/UI=`0/0/0/0`, `MQTT Network timeout`, retry 16 s. Позднее у всех
  airplane=0/VALIDATED network и стабильные PID, но Стас так и не дал reconnect/re-subscribe.
  ID abandoned: повтор publish и attack запрещены. Это availability/reconnect finding; следующий
  шаг — fresh controlled restart baseline. Нужен будущий watchdog/liveness regression: после
  timeout при VALIDATED network клиент обязан reconnect либо явно перейти в recoverable state.
- Controlled cold start дал Анна/Женя subscription+ConnAck=`1+1`, errors=0; Стас не уложился
  в 20 s gate, но после 5 initial errors поздно получил ConnAck и live presence. Bounded TCP:
  HiveMQ:1883 failed, EMQX:1883 passed. Code review: queued `AsyncClient.publish()` не доказывает
  connection, поэтому доступный fallback не выбирается. Поздний ConnAck затем вытеснился из
  logcat до state adoption; robust equivalent gate = live subscribed peer traffic после последнего
  MQTT error + stable PID/identity. Важные late events сразу сохранять snapshot/state marker.
- Generation-2 baseline прошёл без relay: ID `sec-origin2-1786710017189`, TTL 3600, PID
  `14734/24000/13746`; live traffic gate=true у всех; battery `100/100/58%`, temperature
  `31/28.7/30°C`, PSS `117764/98527/142225 KiB`. Затем один normal relay опубликован QoS1,
  non-retained, 183 B; PID стабильны, immediate snapshot refs=`10/7/10`. После двух harness
  failures ASCII-only finalizer сохранил exact PASS: Анна relay/store/cleanup/origin=`1/1/1/1`,
  Женя=`1/1/1/0`, Стас relay/local/receipt/UI=`1/1/1/1`; duplicate=0, errors/crash=0. Recipient
  origin binding установлен, attack log baseline очищен. Затем одна conflicting-origin injection
  (QoS1/non-retained/179 B) дала exact semantics PASS: Анна/Женя previously-seen=1/1, Стас
  conflict-drop=1; store/delivery/receipt/UI/errors/crash=0. Membership-aware finalizer сохранил
  PASS; post-attack max 35°C, PSS delta `-3043/-15721/+9613 KiB`, battery `0/0/+1`. Первый normal
  control helper прошёл live gate, но PC HiveMQ connect timeout случился до publish. Recovery:
  confirmed=false, control ID refs=`0/0/0`, HiveMQ:1883 TCP reachable=true, no publish persisted;
  разрешён ровно один retry того же ID. Не создавать новый ID и не повторять после send attempt.
