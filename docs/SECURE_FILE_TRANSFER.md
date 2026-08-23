# Secure File Delivery — current architecture and historical MVP

## CURRENT OVERRIDE 2026-08-22 — файлы с максимальной доступной скоростью в любой доступной сети

Этот раздел является текущим источником истины для файловой передачи. Старые status-блоки и
первоначальные лимиты ниже сохранены как история F0–F3, но **не определяют следующий шаг**, если
противоречат этому override, последним записям `AI_COLLABORATION_NOTES.md` или фактическому коду.

### Подтверждённые владельцем инварианты

1. **Телефон — сервер.** Исходящие файлы и durable relay-custody хранятся на телефонах, а не во
   внешнем inbox/blob-store.
2. Внешний ресурс допустим только как **временный realtime-провод** для уже E2E-зашифрованных
   байтов. Он не хранит файл, chunks или message inbox и не получает file key/plaintext.
3. Нужны одновременно два режима:
   - быстрый online: максимально использовать доступный прямой/транзитный канал;
   - delayed mesh: если отправитель и получатель не совпали online, другие телефоны с разрешённым
     relay-режимом durable хранят зашифрованные chunks и позже передают их дальше.
4. Владелец relay-телефона выбирает режим и квоты (`Лёгкий / Средний / Без ограничений` или их
   будущий эквивалент): место, трафик, Wi-Fi/mobile/roaming, зарядку и thermal policy. Даже режим
   «Без ограничений» не отключает hard safety, резерв места для самого телефона и приоритет текста.
5. Текст, receipts, presence и управляющие сообщения всегда выше bulk-файла. Файл не может
   заполнить общую message RelayQueue или задержать обычный чат.
6. Если физически нет ни одного разрешённого общего канала и ни одна пара custody-телефонов не
   пересеклась, передача не может продвигаться. APU честно показывает ожидание, сохраняет bytes и
   продолжает bounded retry; ложный `SENT/DELIVERED` запрещён.
7. **Нет произвольного продуктового лимита размера.** «Любой объём» означает streaming/paging без
   загрузки целого файла в RAM и без старого барьера 4 GiB. Реальный предел задают `u64`, файловая
   система, свободное место и выбранные владельцами квоты; бесконечный файл физически не обещается.
   До production wiring B3 обязан убрать текущую 4-GiB validation coupling из manifest/B1 geometry.

### Что действительно доказано, а что нет

**Runtime PASS на телефонах:** существующий F3-конвейер передал небольшие фото (3 KiB и 26 KiB),
получатель проверил/сохранил файл, финальный file-ACK вернулся; экспорт через SAF открыл фото в
галерее. Durable preparation, manifest/chunk AEAD, device-bound key vault, Room progress и базовые
restart/idempotence границы имеют отдельные source/build/device gates из журнала.

**Новый direct API:** `send_direct_payload` прошёл Rust/UniFFI/Kotlin compile и был установлен на
тестовые телефоны, но завершённая файловая передача именно через этот новый путь ещё не имеет
строгого runtime PASS. Direct chat-scope hardening commit `fc157cd` имеет только source/static PASS;
JVM compile и phone runtime pending.

**Важно: текущий код ещё НЕ является параллельным файловым транспортом.** Source-аудит показал:

- sender последовательно кодирует каждый fragment как `apu-file1|<Base64>`, вызывает синхронный
  `sendDirectPayload` и ждёт фиксированные 120 ms;
- каждый вызов Rust создаёт новый QUIC endpoint и новое соединение, отправляет один generic text
  frame и закрывается; persistent connection и одновременных streams нет;
- packet проходит через общий `MessageReceived`/Kotlin polling path, а не через отдельный binary
  file data plane;
- production при первом direct-failure переводит transfer в `WAITING_RECIPIENT`; заявленного в
  комментариях fallback к phone relay для file bytes фактически нет;
- обычная message RelayQueue ограничена 500 envelopes/recipient и 64 KiB/envelope; использовать её
  для тысяч file fragments архитектурно нельзя;
- source создаёт manifest всегда с chunk 128 KiB, хотя объявляет до 4 MiB; app-private store
  принимает только 640 chunks. Поэтому заявленный 4 GiB technical limit сейчас недостижим:
  practical preparation boundary при 128 KiB около 80 MiB;
- 4 MiB plaintext + 16-byte AEAD tag требует 1025 fragments по 4 KiB, но packet cap = 1024;
- internet presence публикуется, mDNS refresh идёт раз в 60 s, но согласованной signed
  contact-scoped модели `beacon 60 s / offline after 90 s` и endpoint expiry ещё нет;
- OFFER manifest metadata (имя/размер/MIME) пока не имеет доказанной confidentiality от relay;
  strict packet/log/DB privacy audit pending.

Эти пункты — не повод латать очередной коэффициент. Они означают, что file bytes нужно отделить от
text transport до дальнейшего наращивания размера/скорости.

### Целевая архитектура: control plane отдельно, file data plane отдельно

#### A. Control plane — маленькие durable сообщения

Через существующий совместимый message/mesh путь идут только bounded подписанные команды:

- HELLO/capabilities и signed endpoint candidates;
- encrypted offer/key envelope без открытого filename/size для relay;
- transfer inventory: chunk ranges/bitmap/Bloom summary;
- custody offer/accept/receipt/lease;
- missing-range request, progress ACK, final receipt, cancel/expiry/cleanup.

Control plane имеет deterministic IDs, durable tombstones и приоритет выше file bytes. Он не несёт
Base64-копию большого chunk. Его durability находится только на телефонах: при проходе через
внешний conduit запрещены retained payload, persistent offline session/inbox и server-side retry
после разрыва; недоступный peer оставляет команду в phone Outbox.

#### B. Direct binary data plane — когда peer достижим сейчас

- Один долгоживущий authenticated QUIC endpoint/connection на peer/path, а не handshake на fragment.
- Binary frames/streams с `transfer_id`, chunk/range, length и version; no Base64, generic chat text,
  Room text row или EventBus string для file bytes.
- Несколько QUIC streams внутри одного соединения. Начальная concurrency остаётся bounded и затем
  адаптируется по RTT/loss/throughput/backpressure; точное число задаётся benchmark, не догадкой.
- AEAD/custody chunk geometry выбирается один раз до encryption по размеру файла и negotiated hard
  bounds; она не меняется при смене пути. Binary wire может дробить chunk на bounded frames, а
  frame/window/concurrency адаптируются по RTT/loss/throughput. Фиксированные 120 ms не являются
  регулятором production speed.
- Получатель durable пишет каждый authenticated encrypted chunk до ACK; после reconnect запрашивает
  только missing ranges. Смена transport/path не меняет transfer/chunk identity и не создаёт дубль.
- Text scheduler сохраняет отдельную полосу/приоритет независимо от file throughput.

#### C. Any-network path manager

Единый `TransportManager` выбирает и при необходимости параллельно пробует bounded candidates:

1. LAN/mDNS QUIC;
2. direct IPv6/ICE-STUN hole punching/QUIC;
3. TCP/TLS 443, WebSocket/HTTP-compatible encapsulation, если UDP заблокирован;
4. пользовательский SOCKS5/HTTP CONNECT/VPN или внешний realtime conduit — только transient,
   E2E bytes, no storage;
5. live phone relay;
6. delayed phone custody; рядом без интернета — Wi-Fi Direct/Nearby/Bluetooth в будущих slices.

Presence — signed/contact-scoped: beacon примерно каждые 60 s, peer считается недоступным после
90 s без подтверждения; local/public candidates имеют expiry и не публикуются без необходимости
всей глобальной wildcard-сети. Любой ready-status требует реальный handshake/progress, не enqueue.

#### D. Отдельная phone-owned FileCustody

Большие encrypted chunks не попадают в `RelayQueue`. Нужен отдельный bounded disk store:

- relay принимает весь файл или его часть только по своей policy/quota и после reserve-space gate;
- signed manifest/control содержит ciphertext chunk identities или Merkle root с binding к index,
  чтобы relay/recipient могли dedup/reject подмену без file key; whole plaintext hash проверяет
  только конечный recipient;
- custody receipt подписан relay и связывает точные chunk identities/expiry; он означает, что bytes
  fsync/atomic сохранены. Origin не удаляет свою копию только из-за transport enqueue;
- несколько подходящих телефонов могут держать разные/повторные bounded subsets, чтобы один relay
  не был единственной точкой потери; replication/fairness ограничены глобальными бюджетами;
- recipient узнаёт holders/inventory и вытягивает только missing chunks; relay не расшифровывает;
- final signed receipt распространяет cleanup, но потерянный receipt оставляет данные только до TTL;
- app-private E2E ciphertext и custody metadata дополнительно защищены device-bound at-rest key;
  process death/reboot/app update восстанавливают custody без продления absolute expiry;
- friend/direct-contact priority, per-origin/per-recipient/global quota, low-storage/thermal stop и
  защита от Sybil/chunk flood обязательны до включения strangers relay.

#### E. Честные состояния и совместимость

`PREPARED/OUTBOX` означает только local durability; `CUSTODIED(n)` — подтверждённые copies на
n телефонах; `TRANSFERRING` — измеренный progress; только signed recipient receipt после exact
size + chunk AEAD + whole hash даёт `DELIVERED`. Transport enqueue/FIN и relay receipt не дают
двух галочек. Capability/version negotiation подписана и fail-closed: новый binary protocol нельзя
отправлять старому text parser; N↔N-1 либо выбирают общий доказанный формат, либо явно показывают
`upgrade required` и сохраняют Outbox.

### Маленькие implementation slices — обязательный порядок

1. **F4-A — architecture/docs (завершён и подтверждён владельцем):** синхронизированы этот документ,
   MASTER plan, new-chat bootstrap и collaboration notes; зафиксированы source audit/proof levels.
   Production code/телефоны не менялись.
2. **F4-B — pure binary protocol boundary:** без network wiring и телефонов, тремя отдельными
   reviewable slices: B1 canonical frame + capability codec; B2 signed bounded control records;
   B3 ciphertext chunk/Merkle identity плюс uncapped `u64` manifest/geometry без старой 4-GiB
   coupling. Каждый slice имеет negative/boundary/replay/downgrade tests.
3. **F4-C — persistent single-peer QUIC:** один connection, binary offer/chunk/ACK, один stream;
   доказать resume/missing chunk и отсутствие Base64/message queue. Сначала local host tests.
4. **F4-D — bounded parallel/adaptive streams:** concurrency/window/backpressure, benchmark на
   loopback/LAN и сохранение text latency. Никаких обещаний скорости до измерений.
5. **F4-E — signed presence + path manager:** 60/90 liveness, expiring candidates, LAN +
   different-network direct/tunnel fallback, honest unavailable state.
6. **F4-F — FileCustody on phones:** encrypted chunk store, consent modes/quotas, custody receipts,
   inventory/missing pull, TTL/cleanup/reboot tests; отдельный scheduler ниже текста.
7. **F4-G — seamless path switch:** direct ↔ transient conduit ↔ live phone relay ↔ delayed custody
   без смены transfer ID, потери progress или второго файла.
8. **F4-H — acceptance:** small/large boundaries; fast LAN; slow/lossy link; different NAT/network;
   UDP blocked/TCP-only; sender/recipient non-overlap through relay phones; all phones offline then
   resume; process death/reboot; disk quota; mixed N↔N-1; integrity/privacy and normal-text control.

### Status 2026-08-23 — F4-B1 focused Windows host gate PASS

Добавлен изолированный `rust-core/src/network/file_wire.rs`, подключённый только как Rust module:

- canonical binary header `APUF | version | type | flags | payload_len`, big-endian;
- bounded 16-byte capability record и pure negotiation по общим version/features/min limits;
- binary ciphertext range с transfer ID/chunk index/offset/whole encrypted length, без Base64/text;
- hard frame ceiling 256 KiB; это memory bound, не chunk size и не обещанная скорость;
- fail-closed unknown version/type/flags/mandatory feature, exact length/trailing-byte checks;
- 11 unit-test функций покрывают canonical round-trip, min/max, truncation, oversize/overflow,
  invalid ranges, downgrade/features и reject legacy `apu-file1` text packet.

Source contract, `git diff --check` и отдельный Rust syntax parser (AST root `Program`) PASS. На
Windows в каноническом `C:\APU-M8\rust-core` для exact commit `4815582091bba50c72c7a4a49b9d26de6aa643db`
команда `cargo test network::file_wire::tests --lib -- --nocapture` скомпилировала lib-test target и
дала `11 passed; 0 failed; 0 ignored; 592 filtered out`. Три warning относятся к прежнему коду
`multi_broker.rs`, `engine/core.rs` и `mqtt_transport.rs`, не к F4-B1. Это focused B1 host
compile/runtime PASS, но не полный Rust suite, Android build или phone runtime. Module не вызывается
sender/QUIC/FFI; Android production source и телефоны в B1 не менялись.

### Status 2026-08-23 — F4-B2 focused Windows host gate PASS

Добавлен отдельный pure `rust-core/src/network/file_control.rs`, пока подключённый только module
declaration:

- canonical `APUC` v1 envelope с hard ceiling 64 KiB и Ed25519 domain separation;
- пять bounded типов: signed capabilities, opaque encrypted offer, paged missing ranges, custody
  offer/accept/stored receipt и final recipient receipt;
- signer/recipient, current session-or-transfer scope ID и modern/legacy pinned-key boundary;
- absolute expiry/clock skew и strictly increasing sequence для durable replay scope;
- range pages максимум 1024 normalized intervals на record, но `u32::MAX` total chunks и `u64` final
  byte count — без нового 4-GiB control-plane cap;
- encrypted offer максимум 32 KiB, подписан вместе с SHA-256 ciphertext digest; filename/size fields
  в открытом B2 record отсутствуют;
- 12 unit-test функций покрывают все типы, canonical round-trip, full truncation/tamper, unknown
  version/type/flags, signature/peer/scope/replay/time failures, offer/range/custody/final boundaries.

`git diff --check`, source contract и отдельный Rust syntax parser (AST root `Program`) PASS. На
Windows в `C:\APU-M8\rust-core` для exact `96dbe28207e5023ffac6f56401b20d5a15776c77`
`cargo test network::file_control::tests --lib -- --nocapture` скомпилировал lib-test target и дал
`12 passed; 0 failed; 0 ignored; 603 filtered out` (compile 12.37 s; tests 1.23 s). Три warning —
прежний код `multi_broker.rs`, `engine/core.rs`, `mqtt_transport.rs`, не B2. Это focused B2 host
compile/runtime PASS, не full suite/Android/phone test. Generated Kotlin/`.so` сохранили exact hashes;
телефоны не подключались.

### Status 2026-08-23 — F4-B1/B2/B3 combined Windows host gate PASS

Добавлен isolated pure `rust-core/src/network/file_identity.rs` и до production wiring окончательно
расширены только pre-production B1/B2 binary поля chunk/range/count с `u32` до `u64`:

- F4 geometry принимает `file_size:u64`, exact `chunk_count:u64`, bounded 16-KiB…4-MiB chunks и
  final remainder без whole-file allocation или 4-GiB validation cap;
- canonical ciphertext identity связывает transfer ID, `u64` index, exact plaintext/ciphertext
  lengths и SHA-256 фактических ciphertext bytes; leaf hash domain-separated;
- streaming Merkle accumulator хранит O(log chunks) frontier, требует exact ordered leaf count и
  deterministic odd-node promotion;
- manifest/root/commitment и single-chunk proof имеют canonical exact-length codecs; proof максимум
  64 siblings, missing/extra/wrong-order/index/hash/root fail closed;
- empty-file root привязан к transfer ID; geometry вплоть до `u64::MAX` bytes проверяется без
  выделения памяти по размеру файла;
- legacy F3 `crypto/file_transfer.rs`, sender, DB/UI, QUIC, FFI, Android и phones не изменены.

B1/B2 draft bytes ранее не были wired ни к одному consumer, поэтому их `u64` finalization не ломает
production/mixed versions. На Windows в `C:\APU-M8\rust-core` для exact
`5ab25178d09793595a44b5f12f7e311ca75651e3` команда
`cargo test network::file_ --lib -- --nocapture` скомпилировала combined lib-test target и дала
`34 passed; 0 failed; 0 ignored; 592 filtered out` (compile 11.09 s; tests 1.33 s). Три warning —
прежние `multi_broker.rs`, `engine/core.rs`, `mqtt_transport.rs`, не F4-B. Generated Kotlin/`.so`
сохранили hashes; телефоны не подключались. Source/static + focused host compile/runtime PASS;
full suite/Android/phone runtime не запускались. Следующий этап F4-C начинается с отдельного аудита и
малого persistent single-peer QUIC host slice, не с Android/phones.

### Status 2026-08-23 — F4-C1 QUIC audit + source/static PASS; Windows host gate pending

Read-only audit перед кодом подтвердил, что старый QUIC нельзя считать authenticated file channel:

- TLS server использует `with_no_client_auth`, client — `SkipServerVerification`; комментарий обещает
  Ed25519/X3DH выше, но фактический listener принимает поле sender из unsigned text payload;
- каждый `engine/core.rs::send_via_quic` создаёт новый endpoint, handshake и connection; имеющийся
  `connection_pool` в `P2PCore` не используется ни одним send/accept callsite;
- old receive boundary выделяет whole message `Vec` до 16 MiB и передаёт UTF-8 в generic EventBus;
  transport FIN/stopped подтверждает доставку QUIC bytes, но не durable file write.

На exact source commit `bf4f0c6c5bce9a2c531d9da39c2c61ad7b99594d` добавлен host-only C1:

- один ordered bidirectional stream держит много binary records внутри одной QUIC connection;
- mutual B2 signed capabilities проверяют pinned peer node ID/key; signed scope связан с exact QUIC
  connection через TLS exporter, поэтому active TLS terminator не может перенести transcript между
  двумя своими TLS legs;
- injected admission boundary обязан durable/atomic принять session replay scope до `AUTH_OK`;
- bounded `APUS` record reader проверяет type/length до allocation и декодирует exact B1 frame;
- negotiated frame ceiling применяется на send и receive; Base64/chat/EventBus/RelayQueue отсутствуют;
- chunk ACK связывает transfer/index/offset/range и пишется только после async durable sink success;
  timeout/failure/invalid frame сбрасывает stream без ACK;
- signed B2 control record остаётся отдельным resume seam; reconnect test передаёт MissingRanges и
  затем только отсутствующий binary chunk;
- 9 tests: many frames/one connection, durable ordering/failure, reconnect/missing range, replay,
  wrong peer/signature/scope, truncation, oversize-before-allocation и negotiated ceiling.

Jinx Rust AST (`Program`, zero `MissingNode`), source contracts и `git diff --check` PASS. Arena не
имеет cargo/rustc, поэтому первоначально compile/runtime не были объявлены. Первый Windows attempt
на `05fa038` корректно поднял MSVC через direct `vcvars64.bat` (сломанная регистрация `vswhere`
известна) и дошёл до crate `p2p-core`, где выявил один C1 type error E0599: Quinn
`ExportKeyingMaterialError` не реализует `Display`. Exact fix `bedf671` заменяет недопустимый
`to_string()` на fail-closed static `TlsConfig`. Combined attempt после этого скомпилировал crate и
дал 41/43: два success-path теста теряли последний queued ACK, потому что test server сразу dropping
all connection handles. Exact test-only fix `ca2edb6` добавил protocol `CLOSE` после final ACK и
receiver wait; production protocol/state не менялись. Focused Windows gate на exact `ca2edb6` PASS:
`9 passed; 0 failed; 626 filtered` (compile 50.56 s; tests 0.20 s). Финальный combined command на том
же exact code дал `43 passed; 0 failed; 0 ignored; 592 filtered out` (compile 1.61 s; tests 1.25 s).
Три warning только прежние `multi_broker.rs`, `engine/core.rs`, `mqtt_transport.rs`; generated-file
guard прошёл, телефоны не подключались. F4-C1 host compile/runtime gate закрыт. Engine/FFI/Android/F3
всё ещё unwired; F4-D не начат. Следующий F4-C slice сначала должен определить persistent engine
ownership/reconnect seam без подключения UI/phones.

После каждого slice отдельно отмечаются source/static, host tests, Windows Android compile и phone
runtime. Следующий slice не объявляется готовым по комментарию или ручному наблюдению.

## Status 2026-08-20 (late evening) — F3 Windows gate PASS; File-HELLO handshake added

JVM gate 88/88 PASS and assembleDebug PASS on the new PC (see доп.308). The first-file
key-pin deadlock (pin existed only from an incoming offer) is closed by the File-HELLO
handshake: a tiny signed durable message, throttled, deterministic per-pair IDs, auto-reply
on first pin (доп.309). Phone acceptance is the next step.

## Status 2026-08-20 (evening) — F3 transport source complete

Sender owner, receiver ingest, deterministic packet IDs and chat picker/progress UI are wired
(source-only; see доп.307 in `AI_COLLABORATION_NOTES.md`). Transports order below is now:
direct QUIC first, then the phone-owned encrypted M8 relay custody (bounded window of ≤120
in-flight chunk-fragment messages per recipient, 7-day TTL, per-chunk file-ACKs, restart
resume). Honest deviation from §2 (to fix next): the OFFER item carries the canonical manifest
integrity-protected but NOT encrypted, so relay nodes can see filename/size/media type; chunk
plaintext, chunk keys and the per-transfer key envelope remain protected. A Rust offer-AEAD
slice is queued to close this.

## 1. Scope and ordering

This is the nearest product milestone after the current signed-referral R1 slice. The first release is
intentionally limited to one file in a direct chat, 10 MiB maximum, explicit user selection/download,
and streaming bounded-memory processing. Photo/video/voice UX later reuses the same transport.

The MVP must not place a whole file in a text MQTT envelope, Room row, Compose state, log, analytics
event, or byte array. A relay may carry only bounded encrypted manifests/chunks under quotas and TTL.

## 2. Security model

- File bytes are untrusted until final AEAD, exact size and whole-file SHA-256 checks pass.
- Android Storage Access Framework is the only picker/export boundary; no broad storage permission.
- Sender creates a random 32-byte file key and random 16-byte transfer ID.
- Each device has a separate device-bound static X25519 file-exchange key. Its public key is signed by
  the real Ed25519 sidecar and carries the verified legacy identity binding; it is not the routing key.
- Manifest is bound to sender, recipient, transfer ID, metadata, size/chunk geometry, whole hash and TTL.
- Every chunk uses XChaCha20-Poly1305 and authenticated canonical manifest + chunk index as AAD.
- The file key/manifest delivery must be protected by the established E2E session. Relay/MQTT never
  sees plaintext metadata, original filename, file bytes or key.
- Deterministic nonce per `(random transfer ID, chunk index)` makes retries byte-stable; a transfer ID
  must never be reused with the same key for another file.
- A downloaded file becomes visible to the user only after atomic final verification.

The recipient file-exchange binding must be pinned to the contact before wrapping a file key. Signed
referral contacts can pin it from the authenticated invite/contact exchange; legacy contacts require
an explicit TOFU/QR confirmation. A self-signed first binding is not misrepresented as proof that the
old legacy ID was historically cryptographic.

This MVP does not claim antivirus scanning or safety of file contents. APK/executable content is never
auto-opened; MIME and filename are display hints, not authority.

## 3. Canonical manifest v1

Domain: `apu-file-manifest-v1\0`.

Fields in exact order:

```text
version u8
transfer_id [16]
sender_node_id length u16 + UTF-8
recipient_node_id length u16 + UTF-8
display_name length u16 + UTF-8
media_type length u16 + ASCII
file_size u64
chunk_size u32
chunk_count u32
file_sha256 [32]
created_at_ms i64
expires_at_ms i64
```

Rules:

- canonical legacy node IDs are `pk_` + 32 or 64 lowercase hex characters;
- sender and recipient differ;
- filename is 1–255 UTF-8 bytes, has no control characters, `/`, `\`, `.`/`..` path names;
- media type is bounded printable ASCII and contains `/`;
- file size is 0–10 MiB;
- chunk size is a power of two from 16–256 KiB; default 128 KiB;
- `chunk_count == ceil(file_size/chunk_size)`, with zero chunks only for an empty file;
- TTL is positive and at most 30 days;
- unknown version or trailing bytes fail closed.

## 4. Chunk v1

Nonce is exactly `transfer_id[16] || chunk_index_be_u64`, 24 bytes for XChaCha20-Poly1305.

AAD is domain `apu-file-chunk-v1\0`, SHA-256 of canonical manifest, and chunk index. Plaintext length
must exactly match manifest geometry: full chunk except the final remainder. Ciphertext is plaintext
plus the 16-byte Poly1305 tag.

## 5. Durable state

Room stores transfer state and bounded metadata only:

- transfer ID/message/chat/direction/state;
- expected size/chunk geometry/hash;
- received chunk bitmap or normalized chunk table;
- retry timestamps/errors without paths or key material.

Encrypted partial chunks live in app-private files under a per-transfer directory. Final plaintext is
streamed through SAF to a user-selected destination only after verification, or atomically retained in
an app-private received-file area. File keys use the existing device-bound/E2E key lifecycle and must
not be logged or placed in ordinary preferences.

States are monotonic and idempotent: `OFFERED → TRANSFERRING → VERIFYING → COMPLETE`, plus
`PAUSED/CANCELLED/FAILED/EXPIRED`. Duplicate manifests/chunks/receipts never create duplicate files.

## 6. Transport order

1. Direct QUIC/P2P when both peers are reachable.
2. Bounded encrypted relay chunks only after quotas/backpressure are implemented.
3. Offline custody uses TTL, per-peer/global disk quotas and cleanup receipts.

File traffic is lower priority than text/receipts and cannot starve durable messaging.

### Direct QUIC chat-scope rule (2026-08-22)

Direct file frames use the reserved transport scope `direct`; they do not pretend that a sender's
local chat UUID is valid on the recipient. Before any incoming transfer row is written, Android maps
the authenticated sender node ID to the recipient phone's existing local chat UUID. If no local chat
exists, the direct file packet is consumed and dropped rather than persisting a hidden row under the
sentinel. A non-direct legacy transport scope remains the compatibility fallback. Source/static checks
and four pure JVM test cases are present; Windows JVM compile and phone runtime remain separate gates.

## 7. Acceptance gates

- canonical/negative crypto tests and Android production compilation;
- streaming memory ceiling test with 10 MiB input;
- 0 B, 1 B, chunk−1, chunk, chunk+1 and 10 MiB boundaries;
- wrong key/index/manifest/recipient, corrupted/truncated/reordered/duplicate chunk rejection;
- malicious filename/MIME, oversize, disk full, cancel, process death and reboot recovery;
- two-phone online SHA-256 equality and interruption/resume exactly once;
- three-phone offline custody with plaintext/log/DB/packet audit;
- text delivery latency and reliability remain within baseline while a file transfers.
