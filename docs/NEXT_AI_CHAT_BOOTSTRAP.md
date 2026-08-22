# APU — готовая инструкция для запуска следующего ИИ-чата

Ниже находится готовый prompt. Пользователь может целиком вставить его в новый Arena Agent Mode
чат. Следующий ИИ не должен заставлять пользователя заново объяснять проект, историю и правила.

## CURRENT OVERRIDE 2026-08-22 — сначала единая file-архитектура, не старый M8

Этот блок заменяет старые указания ниже при любом противоречии. Не начинай с M8-A/M8-E, нового
телефонного gate, release или «быстрого увеличения QUIC». Владелец подтвердил F4 architecture;
текущий продуктовый приоритет — надёжная и максимально быстрая файловая доставка через все
доступные сети, маленькими доказуемыми slices.

### Сначала прочитать и сверить

1. этот CURRENT OVERRIDE целиком;
2. верхний `CURRENT OVERRIDE 2026-08-22` в `SECURE_FILE_TRANSFER.md` — authoritative file design;
3. актуальный file-раздел в `MASTER_PLAN_v2.md`;
4. конец `AI_COLLABORATION_NOTES.md`, особенно доп.349–351;
5. только затем соответствующий production source. Нельзя говорить «разобрался во всём проекте»,
   если обязательный контекст и фактические limits не проверены.

### Неподвижные решения владельца

- Серверов хранения у проекта нет: **каждый телефон — сервер**.
- Внешний ресурс допустим только как transient realtime-провод для E2E ciphertext. External
  message/file inbox, blob-store и store-and-forward запрещены.
- Нужны оба режима: быстрый online direct/transit между любыми доступными сетями и delayed delivery
  полного файла через relay-телефоны, когда sender/recipient не совпали online.
- Relay-владелец сам выбирает режим/квоты; hard safety, reserve disk и приоритет текста обязательны.
- Нет физического overlap/path — файл честно остаётся в Outbox. Enqueue/custody не равны delivered.
- Работать маленькими срезами: один срез → отдельный доказанный gate → следующий.
- Телефоны не трогать без предварительного сообщения с точным списком устройств. Release/tag/PR/
  публикацию не делать без отдельного явного разрешения.
- Единственный канонический Windows-клон: `C:\APU-M8`.

### Фактическое состояние, которое нельзя переименовывать в «готово»

- Published stable остаётся v11.17.1, но это не доказательство нового direct file path.
- Малые файлы прошли старый F3 phone runtime; новый `send_direct_payload` compile/install PASS, но
  законченной transfer через него ещё не доказано.
- Commit `fc157cd` укрепляет direct chat routing; только source/static PASS, phones untouched.
- Текущий sender идёт последовательно: 4-KiB Base64 fragment → новый QUIC connection → ACK →
  120-ms pause. Persistent binary connection, concurrent streams и adaptive window отсутствуют.
- Direct failure ставит `WAITING_RECIPIENT`; отдельной file custody через телефоны пока нет.
- Generic RelayQueue (64 KiB envelope, 500/recipient) не является file/blob store.
- Manifest всегда создаётся с 128-KiB chunk, а local store допускает лишь 640 chunks: объявленные
  4 GiB сейчас фактически не достижимы (около 80 MiB при текущей geometry).

### Текущий порядок работы

`SECURE_FILE_TRANSFER.md` определяет slices F4-A…F4-H. F4-A подтверждён. F4-B1 canonical binary
frame + capability codec уже имеет source/static PASS: изолированный Rust module, 11 тестов, no
network/FFI/Android wiring. В Arena нет cargo/rustc, поэтому текущее действие — focused host
compile/test B1. До PASS нельзя начинать B2 signed control, QUIC concurrency, proxy или phone custody.

> ## Исторический M8 handoff
>
> M8-A…F и старые branch/release gates больше не являются bootstrap-инструкцией. Их evidence
> сохранён в `AI_COLLABORATION_NOTES.md`; повторять или продолжать их как current task нельзя.

---

## Текст для вставки в новый ИИ-чат

Ты продолжаешь разработку Android-мессенджера **APU** в репозитории `vzhem/APUMIR`. Пользователь —
новичок, поэтому говори простым русским языком, но работай как строгий senior engineer. Не начинай
код по одному историческому next-step: сначала сверяй CURRENT OVERRIDE, актуальный план и source.
Спроси пользователя, если архитектурное/продуктовое решение существенно неясно. Перед телефонами,
install, publication/release/tag/PR всегда нужно отдельное предупреждение или разрешение по правилам
выше.

### 1. Как начать сейчас

В первом ответе коротко скажи: «Продолжаю с focused F4-B1 host gate; Android и телефоны не трогаю».
Затем:

1. проверь текущую branch/HEAD/status, но не переключай branch;
2. полностью прочитай актуальные override-блоки и обязательные документы из раздела 2;
3. проверь изоляцию `network/file_wire.rs` от sender/QUIC/FFI/Android;
4. выполни только focused B1 host compile/tests из раздела 8;
5. точно раздели `source/static PASS / compile PASS / runtime PASS / pending`;
6. при ошибке исправляй только B1, не начинай B2/network wiring;
7. обычный commit/push текущей Arena branch разрешён; другую branch не создавать и не переключать;
8. отчитайся: изменённые файлы, что доказано, что ещё не доказано, следующий маленький шаг.

При расхождении workspace с handoff источником истины являются фактический checkout, текущая fixed
Arena branch, newest committed override и production source. Не подтягивай старую Arena-ветку по
историческому hash без отдельного доказательства, что это действительно нужный линейный ancestor.

### 2. Обязательные документы — прочитать полностью до изменения кода

1. `docs/MASTER_PLAN_v2.md`
2. `docs/AI_COLLABORATION_NOTES.md`
3. `docs/MESH_DELIVERY.md`
4. `docs/NEXT_AI_CHAT_BOOTSTRAP.md` (этот файл)
5. `docs/FLASH_BACKUP_RUNBOOK.md`
6. `docs/VERSION_STATISTICS.md`

Исторические «следующий шаг» внутри длинных заметок не переопределяют `CURRENT OVERRIDE` в начале
`AI_COLLABORATION_NOTES.md` и этот handoff.

### 3. Название, платформа и репозиторий

- Единственное пользовательское название: **APU**.
- Технический repository/package пока могут называться APUMIR / `com.vladimir.messenger`; package не
  переименовывать.
- Канонический Windows workspace: `C:\APU-M8`; другие APU-клоны/пути не использовать.
- Для дальнейшей APU-работы на Windows использовать только диск C:, кроме уже готовой APU-флешки F:.
- В этой Arena-сессии branch fixed = `arena/01a0290d-apumir`. В новом session имя может быть другим:
  всегда проверь actual branch/HEAD и используй только назначенную Arena branch; не переключай её.
- Не делать PR/tag/release/publication без нового явного разрешения пользователя.
- Не коммитить generated `.so`, APK, build outputs, caches или signing keystore.

### 4. Текущее доказанное состояние

#### Product/release

- Published stable/latest = **v11.17.1**, release asset `app-release.apk` = 35,285,255 B; tag target
  commit `6deb532`. Это не является proof нового direct file transport.
- Тестовые телефоны debug-signed; public release APK поверх них не ставится.
- Message mesh/durable custody, receipts и bounded RelayQueue существуют для малых message/control
  envelopes. Их file/blob store не расширять.
- File F0–F3 foundation: SAF streaming, canonical manifest/chunk AEAD, Room progress, device-bound
  key vault, app-private encrypted chunks, small direct-chat UI. Малые 3-KiB/26-KiB phone transfers
  имеют runtime PASS в старом transport scope.
- `send_direct_payload` и binding compile/install PASS, но завершённой file transfer именно через
  новый direct route не доказано. `fc157cd` имеет source/static PASS; JVM/phone gate pending.
- F4-A docs/architecture синхронизирован и подтверждён владельцем.
- F4-B1 source/static PASS: `network/file_wire.rs` содержит canonical bounded binary frame,
  capability negotiation и 11 unit-test функций; module ещё никуда не wired. Host compile/tests
  pending, потому что Arena environment не содержит cargo/rustc.

#### Backup и публикация

- На F: существует принятый portable milestone backup; **флешку больше никогда не форматировать**.
- Release/tag/PR/publication и backup rotation только после отдельного разрешения владельца.
- Старые v11.16.x hashes/attempts — исторический evidence в collaboration notes, не current gate.

### 5. Обязательный пользовательский сценарий

1. Если sender/recipient достижимы сейчас, APU выбирает самый быстрый разрешённый LAN/direct/NAT/
   TCP-TLS/WS/proxy/VPN/transient conduit path.
2. Внешний conduit только live-forward E2E ciphertext: no retained payload, offline inbox, blob
   storage или server retry после disconnect.
3. Если endpoints не совпали online, другие consented телефоны durable хранят encrypted chunks и
   доставляют их по будущим пересечениям; sender после handoff может уйти offline.
4. Relay owner выбирает место, трафик, Wi-Fi/mobile/roaming, charging/thermal mode; hard safety и
   text-first scheduling остаются всегда.
5. Disconnect/network change/process death/reboot продолжают missing chunks с тем же transfer ID.
6. Только verified recipient receipt означает DELIVERED; phone custody и transport FIN — не доставка.
7. Если ни одного физического path/overlap нет, файл честно ждёт в Outbox.

### 6. Текущий source-gap

- Sender последователен: 4-KiB Base64 fragment → generic text frame → новый QUIC connection → FIN
  → 120-ms pause. Persistent binary connection/concurrent streams отсутствуют.
- Direct failure даёт `WAITING_RECIPIENT`; separate phone FileCustody отсутствует.
- Generic RelayQueue = 64-KiB envelope, 500/recipient, 10,000 total; file fragments туда не класть.
- Manifest default 128 KiB и chunk-store cap 640 дают practical preparation boundary около 80 MiB,
  несмотря на source constant 4 GiB. 4-MiB plaintext+tag также не помещается в 1024 packet fragments.
- OFFER filename/size confidentiality, signed 60/90 presence, path manager, adaptive scheduler,
  file custody quotas и mixed-version binary negotiation не доказаны.

### 7. Код, который прочитать перед F4-B

- `rust-core/src/crypto/file_transfer.rs` и file-transfer FFI в `rust-core/src/lib.rs`;
- `rust-core/src/network/wire.rs`, `quic_client.rs`, `quic_server.rs`, `relay_queue.rs`;
- `rust-core/src/engine/core.rs`, особенно `send_direct_payload` и peer endpoint ownership;
- Android `FileTransferPacketCodec.kt`, `FileTransferSender.kt`, `FileTransferReceiver.kt`,
  `FileTransferChunkStore.kt`, `OutgoingFilePreparationService.kt`;
- существующие Rust/Kotlin file tests и generated-binding boundary.

Перед изменением grep всех callsites и фактических constants. Комментарий/старый journal claim не
считать реализацией.

### 8. Непосредственный следующий шаг — focused F4-B1 host gate

1. Сверить, что B1 меняет только `rust-core/src/network/file_wire.rs`, module declaration и docs;
   sender/QUIC/FFI/Android остаются unwired.
2. В подготовленном Windows MSVC environment выполнить только:
   `cargo test network::file_wire::tests --lib` из `C:\APU-M8\rust-core`.
3. Если compile/test падает, исправлять только B1 codec/tests; не расширять scope.
4. После PASS повторить source contract/`git diff --check`, записать exact test count и proof level.
5. Не запускать Android build и не подключать телефоны для pure B1.

Только после отдельного B1 PASS/review идут следующие slices F4-B:

- **F4-B2:** signed control records (capability/offer/missing/custody/final receipt) и replay/expiry.
- **F4-B3:** ciphertext chunk identities или Merkle root с index binding, inventory proof и
  tamper/duplicate/downgrade tests.

### 9. Порядок после F4-B

- **F4-C:** persistent authenticated single-peer QUIC, один binary stream, durable-before-ACK resume.
- **F4-D:** bounded adaptive parallel streams/window/backpressure + LAN/slow-link benchmarks и
  text-latency guard.
- **F4-E:** signed contact-scoped 60/90 presence и any-network path manager.
- **F4-F:** separate device-bound encrypted phone FileCustody, owner policy/quotas, custody receipts,
  inventory/missing pull, TTL/tombstone/cleanup/reboot/flood defense.
- **F4-G:** seamless direct/transient/live-phone/delayed-custody path switching.
- **F4-H:** full boundary/NAT/UDP-blocked/loss/reboot/quota/non-overlap/mixed-version/privacy gates.

Не начинать несколько пунктов сразу и не обещать throughput/file limit до benchmark.

### 10. Телефоны и evidence

Телефоны: Стас `11567254BK001192`, Женя `3B665800EES00000`, Анна `AUYF6R5923006121`. Сейчас их не
трогать. Не повторять старые v11.16.x install/reconnect/capture/release/format-F attempts; evidence
сохранён в `AI_COLLABORATION_NOTES.md`.

Перед любой будущей phone-командой:

1. непосредственно назвать конкретные телефоны и попросить подключить/разблокировать;
2. команда начинает с read-only visibility/authorization gate;
3. absent/unauthorized/offline = stop до изменения;
4. uninstall/data clear/необоснованные force-stop/logcat clear/network changes запрещены;
5. install/network toggles требуют отдельного явного разрешения.

### 11. Жёсткие правила пользователя

- Один маленький шаг → проверка → следующий шаг.
- Не спрашивать разрешение на обычные правки, проверки, commit/push текущей Arena branch.
- Спрашивать только перед destructive, phones/install/network, release/tag/PR/publication.
- Все Windows-команды — PowerShell и начинаются `Set-Location C:\APU-M8`.
- Python на Windows только `py -3`.
- Не использовать Arena Downloads; большие versioned scripts — authenticated inline Base64 с
  per-chunk/full gzip hash, raw hash и Parser::ParseFile.
- Не называть повреждение transfer или Windows environment ошибкой пользователя.
- Не использовать `$Pid`/`$PID`; только `$ProcessId`/`$AndroidPid`.
- Не использовать короткие helper names: PowerShell case-insensitive alias `H` вызвал `Get-History`.
  Только уникальные Verb-Noun names (`Get-ApuBytesSha256Exact`) + `Get-Command <name> -All` conflict gate.
- PS5 `$ErrorActionPreference=Stop` + native `2>&1` может дать terminating NativeCommandError.
- `Start-Process` иногда даёт null ExitCode; для critical native commands использовать tested
  `Diagnostics.ProcessStartInfo` async stdout/stderr helper.
- `ConvertFrom-Json` array не оборачивать лишним `@(...)`, иначе Count может стать 1.
- После Windows `git apply` нормализовать CRLF→LF в памяти, доказать expected hash, затем Parser.
- Empty tree size считать явным `[int64]$Total=0`, не читать отсутствующее `$Measure.Sum`.
- Не запускать public high-load/security traffic; только локальная лаборатория.
- Не pop Windows stash и не commit generated `.so`/APK/build outputs.
- Relevant local host tests запускать только после проверки toolchain; отсутствующий prerequisite
  фиксировать как pending, а не обходить ослаблением теста. Windows Android/native gate выполняется
  отдельным `build-rust.ps1`/Gradle шагом; завершённые install не повторять без причины.
- Приложение должно работать с разными соседними версиями.
- При отсутствии разрешённого пути показывать честный restricted/offline и сохранять Outbox.
- Иконка/косметика не являются приоритетом до F4 acceptance.

### 12. Как отвечать пользователю

- Коротко и понятно, без жаргона либо сразу объяснять термин.
- Не выдавать manual success за полное engineering proof.
- Явно разделять: source/static PASS, compile PASS, runtime PASS и что ещё pending.
- После каждого шага фиксировать важные решения/ошибки в `AI_COLLABORATION_NOTES.md` и тематическом
  документе, а global release stats — в `VERSION_STATISTICS.md`.
- Не предлагать косметику, группы, каналы, рост или новую иконку до F4 file-delivery acceptance.
- Ближайшая цель — не release и не phone gate, а focused host compile/test уже написанного F4-B1.

Сейчас выполни B1 gate из раздела 8. К B2 не переходи без отдельного PASS; телефоны не нужны.
