# APU — безопасный multi-broker MQTT overlay

Статус: **cold evidence saved; semantic-equivalent finalizer pending without restart**

Дата: 2026-08-14

Этап: M3(c.2-r4, до M3(d)

r4.1 добавил изолированный bounded duplicate helper; Android Rust release build прошёл за
1m02s. r4.2 оставляет один primary HiveMQ session, но убирает ложный network success: EventLoop
сначала должен получить настоящий ConnAck, и только потом ставятся subscription/presence requests.
r4.2 Android Rust release build прошёл за 1m01s; APK build — за 32s. APK artifact проверен:
22,550,028 B, SHA-256 `DDC836A…3A12`, embedded native совпал с source, apksigner exit=0. Harness
не распарсил certificate label; corrected compare доказал один V2 signer SHA-256 у нового APK и
у установленной v11.16.10. Второй broker и duplicate filter ещё не подключены.

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

### r4.3 — вторая session за feature gate

- Подключить EMQX параллельно, но сначала только собирать ConnAck/status.
- Не публиковать пользовательские envelopes во вторую session.
- Проверить bounded tasks/channels/backoff/resources.

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
