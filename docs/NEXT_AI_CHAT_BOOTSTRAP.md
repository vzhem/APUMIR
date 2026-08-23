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
4. конец `AI_COLLABORATION_NOTES.md`, особенно доп.349–361;
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

`SECURE_FILE_TRANSFER.md` определяет slices F4-A…F4-H. Владелец разрешил последовательно выполнять
все нужные slices. F4-B1 имеет focused Windows PASS 11/11; F4-B2 pure signed control boundary —
focused Windows PASS 12/12 на exact `96dbe28`; combined B1/B2/B3 exact `5ab2517` дал 34 passed,
0 failed, 592 filtered. F4-B закрыт в focused host scope. F4-C1 audit и host-only source/static
готовы на exact `bf4f0c6`; fixes `bedf671`/`ca2edb6` прошли focused Windows 9/9 и combined exact
`ca2edb6` 43/43 (592 filtered). F4-C1 host gate закрыт. F4-C2 read-only ownership review завершён:
legacy pool/engine identity нельзя безопасно подключать к C1. Host-only C2a signer seam exact
`0736d9b` прошёл Windows 46/46. C2b source `fd5d1f3` + test-only `471d099` прошёл focused 7/7 и
combined 53/53; F4-C host gate закрыт. F4-D1 exact `0ef7706` bounded ACK window прошёл Windows
13/13 focused и 56/56 combined. Следующий D2 — parallel streams внутри одного authenticated
connection; benchmarks/engine/Android/path/custody и E…H ещё впереди. Телефоны пока не трогать.

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

В первом ответе коротко скажи: «F4-C1 combined 43/43 PASS на `ca2edb6`; начинаю read-only F4-C2
ownership review; Android и телефоны не трогаю». Затем:

1. проверь текущую branch/HEAD/status, но не переключай branch;
2. полностью прочитай актуальные override-блоки и обязательные документы из раздела 2;
3. сверяй committed C1 audit и source с actual files, не повторяй реализацию;
4. выполни только Windows host gate из раздела 8 и исправляй лишь C1 при реальной ошибке;
5. точно раздели `source/static / host compile/runtime / network runtime / pending`;
6. не подключай Android/FFI/phones и не начинай concurrency F4-D;
7. обычный commit/push текущей Arena branch разрешён; другую branch не создавать и не переключать;
8. отчитайся: что доказано, что ещё не доказано и какой один slice идёт следующим.

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
- F4-B1 source/static и focused Windows host PASS: `network/file_wire.rs` содержит canonical
  bounded binary frame/capability negotiation; exact `4815582`, 11 passed, 0 failed, 592 filtered.
- F4-B2 source/static и focused Windows host PASS: exact `96dbe28`, 12 passed, 0 failed,
  603 filtered; module не wired к sender/QUIC/FFI/Android.
- F4-B3 + combined F4-B focused Windows PASS: exact `5ab2517`, 34 passed, 0 failed, 592 filtered;
  network/FFI/Android/phones всё ещё unwired.
- F4-C1 source `bf4f0c6` + fixes `bedf671`/`ca2edb6`: TLS-exporter-bound mutual B2 auth, bounded
  `APUS`, one ordered QUIC stream, durable-before-ACK/admission и signed resume seam; Windows focused
  9/9 и combined 43/43 PASS, no engine/FFI/Android wiring.
- Канонический Windows clone после последнего gate находится на
  `arena/01a0290d-apumir`/`ca2edb6`, но сохраняет
  две прежние модификации generated artifacts: UniFFI `p2p_core.kt` и
  `arm64-v8a/libp2p_core.so`. Их нельзя
  reset/перезаписывать без отдельной проверки; при B1 gate SHA-256 сохранились без изменений.

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

### 8. Непосредственный следующий шаг — F4-D throughput data plane

F4-C1 exact `ca2edb6` закрыл focused 9/9 и combined 43/43 host gates. C2 read-only audit подтвердил:
production send создаёт endpoint/connection на каждый old send, listener endpoint локален его task,
а unwired `ConnectionPool` unauthenticated, keyed произвольными bytes и допускает concurrent duplicate
connect. Его нельзя расширять как file-session owner.

До owner есть обязательный identity prerequisite. `ffi::CryptoManager` использует fake legacy
подписи и не является Ed25519. Реальный device-bound `Arc<InstalledSigningIdentity>` устанавливается
Android до engine start и не экспортирует private seed/key, но C1 принимает concrete
`&Ed25519KeyPair`.

C2a source/static exact `0736d9b62c64f0cc8daeff29dfef82f85377901c` уже сделал:

1. crate-internal signer abstraction только для exact public key + sign operation;
2. implementations для test `Ed25519KeyPair`, real `InstalledSigningIdentity` и existing `Arc<T>`,
   без seed export и без fallback к fake CryptoManager;
3. обязательную self-verification результата adapter; 2 new control tests + 1 mutual C1 loopback;
4. Jinx `Program`/zero MissingNode, static contract и diff check PASS.

Windows exact `0736d9b`: `adapter` 2/2, `installed_sidecars` 1/1, combined `network::file_` 46/46
(592 filtered) PASS; три warnings только прежние, generated hash guard PASS. C2a закрыт.

C2b source/static exact `fd5d1f3ac2c31e7b52fe123190ee2f560504f89a` уже добавил отдельный
`file_session_owner.rs`: один endpoint, fixed Ed25519-key + address map, per-slot connect/auth mutex,
bounded/idle/failed eviction, fresh reconnect, MissingRanges delegation и explicit shutdown. 7 tests
покрывают reuse, 16-caller race, idle fresh scope, signed resume после reconnect, wrong peer + retry,
capacity и shutdown. Jinx `Program`/zero MissingNode, static contract и diff check PASS.

После test-only `471d0994885a5bf66139b8775bcefcb67734d8ed` Windows focused owner дал
7/7, combined `network::file_` 53/53 и 592 filtered; C2b/F4-C host gate закрыт.

D1 exact `0ef770618292ce1f7ba259cc051a1c390bf7ca62` прошёл Windows 13/13 focused и
56/56 combined. Теперь D2 должен держать base C1 authenticated connection живым, открывать несколько
scope-bound QUIC data streams без нового TLS/identity handshake на chunk, иметь единый hard byte
budget поверх per-stream windows и fail-closed stream ID/scope checks. Host tests: one connection,
реальная concurrent persistence, duplicate/scope/budget negatives и reconnect resume. Затем D3
fast/slow/loss/text benchmarks; после D — E path manager, F custody, G switching, H acceptance.
Engine/FFI/Android/phones не подключать раньше host proof; legacy text listener не использовать.

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
- F4-B combined 34/34 PASS на `5ab2517`; не повторять отдельно.
- F4-C1 exact `ca2edb6`: focused 9/9 и combined 43/43 PASS; не повторять без code change.
- C2a exact `0736d9b` Windows 46/46 PASS; не повторять без code change.
- C2b source `fd5d1f3` + test-only `471d099`: Windows 7/7 focused, 53/53 combined PASS.
- F4-D1 exact `0ef7706`: Windows 13/13 session + 56/56 combined PASS.
- Следующий D2, затем D3; FFI/Android/phones не начинать до host throughput proof.

Сейчас делай D2 из раздела 8. Телефоны не нужны.
