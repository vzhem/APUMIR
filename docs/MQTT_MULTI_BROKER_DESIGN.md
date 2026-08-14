# APU — безопасный multi-broker MQTT overlay

Статус: **r4.3 v11.16.14 Anna observe-only runtime PASS; r4.4 next**

Дата: 2026-08-15

Этап: M3(c.2-r4, до M3(d)

r4.1 добавил изолированный bounded duplicate helper; Android Rust release build прошёл за
1m02s. r4.2 оставляет один primary HiveMQ session, но убирает ложный network success: EventLoop
сначала должен получить настоящий ConnAck, и только потом ставятся subscription/presence requests.
r4.2 Android Rust release build прошёл за 1m01s; APK build — за 32s. APK artifact проверен:
22,550,028 B, SHA-256 `DDC836A…3A12`, embedded native совпал с source, apksigner exit=0. Harness
не распарсил certificate label; corrected compare доказал один V2 signer SHA-256 у нового APK и
у установленной v11.16.10. Controlled reconnect Анны сохранил PID `30085`, восстановил исходные
Wi-Fi/data=`1/1`, дал ровно один reconnect subscription request и последующий live peer traffic;
ошибок MQTT, ложных/старых markers и crash/ANR не было. Test message не публиковался. Две
последующие guarded delivery-preflight попытки также остановились **до publish**. Вторая сохранила
динамические PID, но за readiness window и позднюю read-only диагностику все 3 телефона при
`airplane=0`, Wi-Fi=1 и VALIDATED network дали `MQTT IN/presence/peer/error/ConnAck/subscription=0`;
PID membership 3/3, crash/ANR=0. Значит, delivery остаётся pending, а перед r4.3 нужен отдельный
r4.2-r1 observable-liveness/backpressure fix. Второй broker и duplicate filter ещё не подключены.

Source review показал правдоподобный, но ещё не доказанный stack trace риск взаимной блокировки:
EventLoop бесконечно ждёт `event_tx.send(...).await`, а core может одновременно бесконечно ждать
`AsyncClient.publish(...).await` в другом bounded channel. Завершение EventLoop task не
наблюдается, периодические presence publish errors игнорируются. Нельзя называть это точной
причиной без runtime proof, но само тихое состояние уже является release blocker.

## 1. Зачем это нужно

Текущий `MqttTransport::connect()` вызывает `AsyncClient.publish().await` до polling
`EventLoop` и принимает успешную постановку request в локальный bounded channel за настоящее
сетевое соединение. Это не доказывает TCP/MQTT connection и broker `ConnAck`, поэтому фактически
всегда выбирается первый broker.

Простой fix «не дождались ConnAck — переключить этот телефон с HiveMQ на EMQX» **небезопасен**:
публичные brokers независимы и не пересылают topics друг другу. Если Анна останется на HiveMQ,
а Стас подключится только к EMQX, оба будут показывать `connected`, но окажутся в разных сетях.
Сообщения, presence, relay и receipts между ними не дойдут.

Поэтому APU нужен не одиночный fallback, а маленький bounded overlay: телефон одновременно
поддерживает соединения с несколькими одинаково настроенными brokers, получает с каждого и
публикует критичные realtime-конверты в ограниченное число активных sessions.

## 2. Цели и границы r4

Цели:

1. Считать broker session рабочей только после настоящего `ConnAck`.
2. Поддерживать **не больше двух** активных public-broker sessions в первой версии r4.
3. Подписываться на APU topics на каждой session и восстанавливать подписку после каждого
   reconnect при `clean_session=true`.
4. Публиковать один логический realtime-конверт максимум в две активные sessions.
5. Подавлять копии одного конверта, пришедшие с разных brokers, до core/UI.
6. Не создавать безлимитные reconnect, queues, tasks, traffic или log spam.
7. Явно показывать degraded state, если нет общего доступного broker.

Не входит в r4:

- M3(d) автоматический send-path для offline recipient;
- background WorkManager/foreground service;
- большие media/files;
- production-обещание для тысяч пользователей;
- собственный broker cluster/bridge;
- E2E-этап M7;
- удаление текущих mesh hard limits, dedup, TTL или hop limit.

## 3. Главный инвариант

Два телефона могут обмениваться MQTT-трафиком только если множества их активных brokers
пересекаются хотя бы в одной точке.

```text
active_brokers(Anna) ∩ active_brokers(Stas) != ∅
```

Если пересечения нет, APU не должен печатать ложное «доставка работает». Состояние должно быть
`degraded/no-common-broker`, а сообщение остаётся в bounded sender Outbox до другого транспорта
или восстановления общего broker.

## 4. Предлагаемая архитектура

### 4.1. `BrokerSession`

Одна session отвечает только за один `(host, port)`:

- собственные `AsyncClient` и `EventLoop`;
- непрерывный polling;
- status `Connecting | Connected | Backoff | Stopped`;
- broker `ConnAck` как единственный network-ready gate;
- subscription request после каждого `ConnAck`;
- bounded exponential backoff с jitter;
- сообщения в общий bounded channel с обязательным `broker_id`;
- stop/cancellation без оставшихся background tasks.

`AsyncClient.publish/subscribe().await` означает только принятие request клиентским channel.
Это не `ConnAck`, не `SubAck` и не `PubAck`. Логи обязаны различать эти стадии.

### 4.2. `MultiBrokerTransport`

Supervisor владеет максимум двумя `BrokerSession`:

- запускает одинаковый фиксированный ordered broker set;
- объединяет входы sessions в один bounded channel;
- хранит snapshot активных sessions;
- отправляет envelope только в sessions с подтверждённым `ConnAck`;
- возвращает ошибку/degraded, если активных sessions нет;
- fanout одного envelope ограничен двумя broker publications;
- не создаёт новую task на каждое сообщение.

Первая конфигурация r4 должна использовать одинаковый ordered pair на всех телефонах:

1. `broker.hivemq.com:1883`;
2. `broker.emqx.io:1883`.

Оставшиеся публичные brokers не подключать одновременно в r4. Их можно держать только как
будущий резерв конфигурации. Это ограничивает трафик и число reconnect loops.

### 4.3. Подавление cross-broker duplicates

Один publish в две sessions обычно даст две одинаковые входящие копии. До передачи в core нужен
bounded exact-duplicate filter:

- key: SHA-256 от `topic || 0x00 || payload`;
- окно: 30 секунд;
- максимум: 4096 keys;
- структура: `HashMap<digest, Instant>` + `VecDeque<(digest, Instant)>`;
- expired entries удаляются перед lookup/insert;
- при переполнении удаляется oldest;
- никакого plaintext или полного payload в cache;
- metric: `mqtt_cross_broker_duplicate_dropped`;
- filter не заменяет mesh `msg_id` dedup, origin binding, TTL и hop checks.

Короткое окно позволяет одинаковой presence появиться снова через штатные 120 секунд, но
подавляет почти одновременные копии одного realtime publish.

### 4.4. Publish policy

| Envelope | Retain | QoS | r4 fanout |
|---|---:|---:|---:|
| presence | true | 1 | все active sessions, max 2 |
| relay resend | false | 1 | все active sessions, max 2 |
| unique receipt | true | 1 | все active sessions, max 2 |
| receipt clear tombstone | true/empty | 1 | те же sessions, max 2 |
| gossip summary | false | 1 | все active sessions, max 2, существующие budgets |
| ping/probe | false | 0 | только для health, bounded |

Retained receipt, опубликованный в два независимых brokers, должен быть очищен в обоих. Поэтому
state обязан помнить, в какие broker sessions он был поставлен. Нельзя объявлять cleanup полным,
если clear ушёл только в один из двух brokers.

### 4.5. Connection/subscription state

Для каждой session нужны отдельные counters:

- `connack_count`;
- `sub_request_count`;
- `suback_count`, если его можно надёжно сопоставить;
- `disconnect/error_count`;
- `consecutive_errors`;
- `backoff_seconds`;
- `last_connack_at`;
- `last_message_at`;
- bounded `publish_enqueued` / `puback` counters.

После reconnect и `clean_session=true` session обязана повторно подписаться. ConnAck одного
broker не должен ошибочно менять state другой session.

## 5. Безопасность и лимиты

- Sessions: максимум 2.
- Общий входной channel: текущий hard cap 256, не увеличивать без измерений.
- Dedup cache: 4096 entries / 30 секунд.
- Publish fanout: максимум 2 на один логический envelope.
- Backoff: 1 → 2 → 4 → 8 → 16 → 30 секунд, затем 30 секунд + bounded jitter.
- Не делать reconnect tight loop.
- Существующие M3(c.1/c.2) summary/relay byte and count budgets остаются глобальными, а не
  умножаются отдельно на каждый broker.
- Перед каждым `RelayQueue.enqueue` по-прежнему обязательны `contains(msg_id)`, TTL и hop limit.
- Cross-broker filter работает до дорогого parsing/UI, но после envelope-size gate.
- Flood/load test публичных brokers запрещён; high-load только на двух локальных test brokers.

## 6. Поэтапная реализация

### r4.1 — чистая bounded duplicate policy

- Новый небольшой Rust helper без сети.
- Exact key, 30-second expiry, cap 4096, oldest eviction.
- Unit tests: first accepted, immediate duplicate dropped, different topic/payload accepted,
  expiry accepted, cap eviction deterministic.
- Не подключать helper к production path до успешного Windows Android build.

### r4.2 — одна `BrokerSession`, поведение сети не расширять

- Выделить polling/ConnAck/re-subscribe state одной текущей session.
- Убрать ложный `connected` до ConnAck.
- Сохранить один HiveMQ broker и текущую функциональность.
- Проверить cold start и reconnect на трёх телефонах без новой доставки/дублей.

### r4.2-r1 — observable liveness и bounded backpressure

- Не позволять EventLoop→core и core→AsyncClient бесконечно ждать друг друга на заполненных
  bounded channels; timeout/overflow должны быть явными и измеримыми, не тихими.
- Наблюдать завершение/panic EventLoop task и переводить session в `Stopped/Degraded`, а не
  оставлять живой PID с ложной готовностью.
- Не игнорировать periodic presence publish errors; добавить bounded counters/last-progress time.
- Сначала определить topic-aware overflow policy: presence/gossip можно bounded-drop с metric,
  но relay/receipt нельзя молча терять после broker QoS ACK.
- Проверить на локальном broker: idle > keepalive, заполнение channels, consumer stall и recovery.
- До прохождения r4.2-r1 не публиковать новый public delivery probe и не начинать r4.3.

**r4.2-r1a Windows compile PASS:** payload-free `MqttLivenessProbe` использует фазы
`starting/polling/forwarding/backoff/idle/stopped`, monotonic phase/progress age и fixed-memory
atomic counters. Независимый watchdog проверяет состояние каждые 15 с, считает stall после 90 с,
повторяет warning не чаще раза в 60 с и пишет heartbeat раз в 120 с. Completion oneshot отличает
заявленное завершение EventLoop от исчезновения task без сигнала; requested shutdown помечен
отдельно. Initial/periodic presence и relay-registration различают queued request и error.
QoS/retain/brokers/channels/backoff/reconnect не менялись; recovery и overflow policy остаются r1b.
Пять deterministic unit tests добавлены; host `cargo test` запрещён. Windows `build-rust.ps1`
для source `a357520` завершён за 68.45 с: exit=0, `Finished release`, compiler warnings=9,
errors=0. Новая arm64 `.so`: 7,148,552 B, SHA-256
`BC35DE5C760111B20312E189BFF4C7AC99010B4F1CB512E88652EDDD0AA53995`.

**r4.2-r1b1 Windows compile PASS:** transport-agnostic `mqtt_backpressure.rs` ограничивает
ожидание постановки AsyncClient request пятью секундами и различает timeout/client error без
topic/payload. 4 async/pure tests покрывают success, client error, pending future timeout и
безопасный error text. Все 8 persistent publish policies и initial/reconnect subscribe используют
единый wrapper; скрытого retry нет. Liveness heartbeat получил `request_timeouts/request_errors`.
QoS/retain/topics/caps/backoff не менялись. Windows `build-rust.ps1` source `a360833` завершён
за 61.53 с: exit=0, compiler warnings=9, errors=0; arm64 `.so` 7,155,352 B, SHA-256
`A1FE612E339D35CD834234832490B252CFC8AB0C120C843AE876EB6D9CBFD73D`. EventLoop→core stall и
automatic session recovery остаются r1b2; transient `send_message_mqtt` пока вне wrapper.

**r4.2-r1b2 Windows compile PASS:** pure restart decision различает closed notification channel,
finished EventLoop task, stalled phase и stopped probe; deterministic tests фиксируют reason labels
и backoff `1→2→4→8→16→30→30`. Core initial ready gate ждёт настоящий ConnAck и subscription
request максимум 45 с, затем повторяет fresh single-HiveMQ session с backoff. При terminal/stalled
reason старая session полностью Drop до создания новой; generation увеличивается только после
recovery. Уже полученный event сначала обрабатывается. RelayQueue, seen/delivery tombstones, known
peers и traffic budgets объявлены вне restart и сохраняются. После нового ConnAck повторяются
subscription, presence и relay-registration requests. Windows `build-rust.ps1` source `f6130f6`
PASS за 61.65 с: exit=0, compiler warnings=9, errors=0; arm64 `.so` 7,167,112 B, SHA-256
`7C9537E6FA5F93B4F6AC28B2705F97A35E738C36309922A029D06E0886517194`. C-drive JDK17 APK
build3 затем PASS: v11.16.12/11016012, 22,582,796 B, SHA-256
`6765FC7A0ACF10CD9909BC265E729EC1E0BAE47D1314376585CC2123649A3F55`; embedded native exact.
Signer preflight PASS: V2 new/installed=true, certificate SHA-256 `F843CBE7…A4A5F7` exact.
Data-preserving v11.16.12 install PASS 3/3 с appId/UID/firstInstallTime/dataDir preservation.
Controlled launch 3/3 выполнен; первый analyzer пропустил native tag `p2p_core`. Read-only recovery
доказал runtime readiness/liveness PASS 3/3 по unchanged cold-launch PID, active wildcard traffic
до capture end и отсутствию stall/restart/request failures; exact cold-start READY lines вытеснены
и launch не повторяется.

**r4.2-r1b3 source complete, Windows compile pending:** pure `mqtt_overflow.rs` классифицирует
inbound traffic по topic+envelope: presence/gossip/ping/relay-registration/`gsumm`/empty retained
clear — refreshable best-effort; message/relay/receipt/ACK и unknown — loss-intolerant fail-closed.
Из общего cap 256 best-effort события не занимают последние 32 slots; у boundary используется
non-blocking `try_send`, допустимый drop всегда увеличивает `best_effort_drops`, а payload-free log
ограничен первым случаем и powers-of-two. Counter добавлен в heartbeat/stall/exit markers. Четыре
pure tests покрывают taxonomy, unknown safety, exact reserve boundary и bounded logging; static
integration checks PASS, host cargo не запускался. Windows Android Rust build source `a69c1a0`
PASS за 65.99 с, exit=0/errors=0; arm64 `.so` 7,169,720 B, SHA-256
`E36F32E8CB1E6FA641D380550A496523840D174CC70B4888C04D5E2DD6B9B066`. Loss-intolerant path
больше не использует refreshable FIFO.

**r4.2-r1b4 source complete, Windows compile pending:** core создаёт отдельный
`LossIntolerantInbox<MqttEvent>` cap 256 до generation-1 и передаёт тот же `Arc` каждой replacement
session. Message/relay/receipt/ACK/unknown сначала получают owned FIFO slot; `poll_event` всегда
drain'ит их раньше best-effort. EventLoop после initial ConnAck не читает следующий broker packet,
пока inbox full; accepted event уже в shared inbox и переживает Drop old transport. Initial ConnAck
replacement session остаётся pollable даже при inherited full inbox, чтобы recovery не deadlock.
General channel cap 256/reserve 32 теперь держит место для ConnAck/control. Depth/buffered/
backpressure — fixed-memory counters в heartbeat/stall/exit; invariant violation сохраняет event и
пишет payload-free error. 7 sync + 1 async tests покрывают FIFO, core/session Arc ownership,
capacity wait+notify, zero-cap normalization и r1b3 taxonomy. Это session-level in-memory safety,
не process restart persistence (она остаётся M8). Targeted static checks PASS. Windows Android
Rust build source `1a43342` PASS за 66.01 с, exit=0/errors=0; arm64 `.so` 7,180,888 B, SHA-256
`E706A9009F28E842F6A030D0CCC7BABB28D56E20DEFB5FB34117FD87F032E7E5`. Первый v11.16.13 APK
block остановился до Gradle из-за PowerShell `java -version` stderr handling; build не attempted,
APK/phones не менялись. Corrected build2 затем собрал v11.16.13 APK (22,599,180 B,
`5A26728B…D7C03BA`), version/V2/build exits PASS, но exact certificate-line parser остановил state
до cert/native extraction. Read-only recovery PASS без rebuild: signer cert exact, embedded r1b4
native exact; authoritative APK v11.16.13 22,599,180 B / `5A26728B…D7C03BA`. Первый inline
preinstall не парсился и ничего не выполнил; versioned parser-validated preinstall2 затем PASS 3/3:
installed identity/data/PID exact. Guarded data-preserving v11.16.13 install затем PASS 3/3;
UID/firstInstall/dataDir preserved, processes post-install absent. User approved one controlled
launch выполнен once: 2/3 direct startup PASS, Anna missing only READY/ConnAck/subscription lines;
state/evidence immutable, relaunch forbidden. Saved-evidence recovery proved runtime PASS 3/3;
Anna startup lines evicted, direct markers remain 2/3. One-publish QoS1/non-retained functional
smoke PASS with exact relay/receipt/cleanup/UI matrix; budget consumed. User explicitly kept
reliability-first order: bounded r4.3–r4.5 before M3(d). EMQX, dual publish
и cross-broker dedup не подключены;
transient sender остаётся отдельным долгом.

### r4.3 — вторая session за feature gate

- Подключить EMQX параллельно, но сначала только собирать ConnAck/status.
- Не публиковать пользовательские envelopes во вторую session.
- Проверить bounded tasks/channels/backoff/resources.

**Source complete, Windows feature build pending:** `mqtt-secondary-observe` default OFF; новый
observer hard-gated в module/core, использует только EMQX, один EventLoop task и client cap1.
Subscribe/publish отсутствуют; markers всегда сообщают `subscriptions=0 publishes=0`; status
counters + bounded 1→30s backoff + Drop abort. Legacy switching `MultiBroker` не активирован.
`build-rust.ps1 -Features mqtt-secondary-observe` добавлен для exact gated compile. Versioned
Windows feature child build фактически завершён (`Finished release`, generated `.so`), но
`Start-Process -Wait` wrapper завис после child exit и был прерван; parent FAIL state immutable.
Corrected recovery PASS: feature marker/Finished release/errors=1/1/0; `.so` 7,193,912 B /
`D7A2216E…D95FE95`, observe-only binary markers exact. Feature compile gate closed; APK v11.16.14
First artifact harness stopped before Gradle on scalar-zero Wait helper defect. Corrected build2
PASS: v11.16.14 APK 22,615,564 B / `6C4D29DA…E13F5C`, signer/native exact. Minimal Anna-only
First Anna harness stopped pre-ADB on `$Args` automatic-variable conflict; phones unchanged.
Corrected runtime2 выполнен один раз и не повторяется. Parent state SHA-256
`E4AADB47…98425` сохранил `INCOMPLETE_DO_NOT_REPEAT`, хотя install/launch и final device checks
завершились: Anna v11.16.14 PID `21678` stable; Zhenya/Stas остались v11.16.13 с PID
`20562/23149`. Evidence manifest 60 files / SHA-256 `2A3CADDD…3FA0`. EMQX дал connected
ConnAck=1, polls=1/1, poll_errors=0 и exact `subscriptions=0 publishes=0`; backoff=0. Ранние
supervisor/starting breadcrumbs не сохранились, но connected ConnAck — достаточное и более сильное
r4.3 acceptance-событие. Primary остался HiveMQ: один initial network timeout восстановился через
1.432 с в том же generation=1/attempt=1, затем READY, heartbeat и 64 incoming; pending,
backpressure, request failures, stall и restart=0. Поэтому strict `primaryErrors==0` дал ложный
incomplete: одиночная bounded ошибка допустима только при доказанном последующем same-session
ready/progress. Saved-only `r43_anna_runtime2_analyze.ps1` Windows ParseFile/safety и execution
PASS без ADB/logcat/install/launch. Recovery state SHA-256 `9F10B5D1…58E55` связал exact parent
E4AA…8425 и manifest 2A3C…3FA0; outcome `PASS_FROM_IMMUTABLE_RUNTIME2_EVIDENCE`. **r4.3 gate
закрыт PASS**. Parent incomplete и отсутствующие early lifecycle markers остаются immutable;
runtime2/analyzer не повторять. Следующий этап — r4.4 bounded dual publish + production dedup.

### r4.4 — bounded dual publish + cross-broker dedup

- Включить publish max 2 и общий exact-duplicate filter атомарно.
- Сначала локальные brokers; затем один low-volume тест на собственных телефонах.
- Проверить один UI event, bounded receipts/cleanup и отсутствие relay storm.

### r4.5 — failure/recovery matrix

- Broker A down at startup, B up.
- A up, B down.
- A disconnect after startup; B carries delivery.
- B reconnects: one re-subscribe, no second UI event.
- Both down: no fake success, bounded backoff, no queue growth.
- Both recover: subscriptions restored, one normal control delivery.

M3(d) можно начинать только после r4 acceptance либо после отдельного решения пользователя
временно принять single-broker limitation.

## 7. Проверка без нагрузки на публичные сервисы

Основной deterministic harness — два локальных независимых Mosquitto brokers на Windows,
например ports `18883` и `18884`. Test APK/config подключается к ним через доступный телефону
LAN address; browser/phone не должен использовать `localhost` ПК.

Проверки:

1. Оба brokers доступны: один логический publish, один core/UI event.
2. Получатель видит только broker B, отправитель A+B: доставка через B.
3. Убрать A: B продолжает доставку без restart.
4. Вернуть A: нет второго UI event или повторного origin-delivery.
5. Повторить receipt cleanup и retained clear на обоих brokers.
6. Измерить PID, threads, PSS, CPU, battery, temperature и reconnect counters.

На public HiveMQ/EMQX допускается только один короткий functional smoke без flood/stress.

## 8. Acceptance criteria

- Ни один log/state не называет queued request сетевым успехом.
- Хотя бы один реальный ConnAck нужен для ready state.
- При общем broker сообщение доставляется ровно один раз в core/UI.
- Duplicate copies с разных brokers не вызывают второй store/UI/receipt/origin-delivery.
- Retained receipt очищается во всех sessions, куда был опубликован.
- При падении одной session вторая продолжает работу.
- При отсутствии общего broker состояние явно degraded, без ложного `SENT/DELIVERED`.
- Нет crash/ANR/PID restart, tight reconnect loop или превышения hard caps.
- После recovery обычное control message доставляется.
- M3(c.2-r3 regression остаётся PASS.
