# APU — готовая инструкция для запуска следующего ИИ-чата

Ниже находится готовый prompt. Пользователь может целиком вставить его в новый Arena Agent Mode
чат. Следующий ИИ не должен заставлять пользователя заново объяснять проект, историю и правила.

> ## ⚠️ ОБНОВЛЕНИЕ СТАТУСА 2026-08-16 — прочитать ПЕРЕД вставкой текста ниже
>
> Prompt ниже частично устарел: он требует «начать с M8-A», но с тех пор уже выполнено
> (ветка `arena/01a00674-apumir`, актуальный tip):
>
> - **M8-A** (`b5408e2`) — durable epoch-ms модель RelayQueue + валидация + 12 тестов;
> - **M8-B/D** (`b881d65`) — durable RelayStore (SQLite) + persist/restore/tombstone wiring;
> - **M8-C slice 1** (текущий tip) — versioned at-rest AEAD envelope `storage/relay_at_rest.rs`
>   (XChaCha20-Poly1305, quarantine-ошибки, Keystore seam; SQL пока не менялся).
>
> **Следующий маленький шаг — НЕ M8-A**, а M8-C slice 2: relay schema v2 + подключение конверта
> к `RelayStore` с quarantine-путём. Windows compile gate `build-rust.ps1 -Features
> mqtt-dual-broker` для всех M8-slices по-прежнему pending. Остальные правила prompt'а ниже
> (телефоны, флешка, релизы, PowerShell-гэтчи, запрет sandbox cargo test) остаются в силе.
> Всегда сначала сверяй этот файл с tip'ом новейшей arena-ветки (`git for-each-ref
> --sort=-committerdate`) — репозиторий может быть новее текста.

---

## Текст для вставки в новый ИИ-чат

Ты продолжаешь разработку Android-мессенджера **APU** в репозитории `vzhem/APUMIR`. Пользователь —
новичок, поэтому говори простым русским языком, но работай как строгий senior engineer. Не задавай
вводных и повторных вопросов: вся необходимая исходная информация ниже. Сразу начинай следующий
безопасный coding-шаг. Вопрос допустим только перед разрушительным действием, изменением телефонов,
новым install, publication/release/tag/PR или если фактический workspace существенно противоречит
этому handoff.

### 1. Как начать — без уточняющих вопросов

В первом же ответе напиши коротко: «Продолжаю с M8-A: сохраняемое время и durable schema для
RelayQueue. Телефоны не трогаю». Затем самостоятельно:

1. проверь текущую branch/HEAD/status, но не переключай branch;
2. полностью прочитай обязательные документы из раздела 2;
3. перечитай целевые Rust-файлы из раздела 7;
4. реализуй первый маленький M8-A slice из раздела 8;
5. выполни только разрешённые статические проверки (`git diff --check`, grep/source review);
6. не запускай sandbox/host `cargo test` и не собирай Android/Rust в sandbox;
7. сделай обычный commit в текущую Arena branch и push этой же branch без отдельного вопроса;
8. отчитайся: изменённые файлы, что доказано, что ещё не доказано, следующий маленький шаг.

Если actual branch имеет другое Arena-generated имя, не переключай её: используй фактическую
текущую branch и сообщи расхождение. Не создавай другую branch. Если commit с этим handoff отсутствует
в новом checkout, выполни read-only `git fetch origin arena/01a000bc-apumir`, проверь remote handoff и
сделай только `git merge --ff-only origin/arena/01a000bc-apumir` в текущую fixed Arena branch — без
checkout/switch. Если fast-forward невозможен или worktree не clean, остановись и сообщи точное
расхождение; не заставляй пользователя заново пересказывать проект.

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
- Канонический Windows workspace: `C:\APUMIR-arena-test`.
- Для дальнейшей APU-работы на Windows использовать только диск C:, кроме уже готовой APU-флешки F:.
- В предыдущем чате Arena branch была `arena/01a000bc-apumir`; version-statistics baseline commit —
  `579ce7a`, а authoritative текущий HEAD — commit, содержащий этот handoff. Сначала проверь actual
  branch/HEAD: в новом Arena session branch может иметь другое generated имя. Никогда самовольно не
  переключай branch.
- Не делать PR/tag/release/publication без нового явного разрешения пользователя.
- Не коммитить generated `.so`, APK, build outputs, caches или signing keystore.

### 4. Что уже завершено

#### Продукт и сеть

- Rust RelayQueue, TTL 7 дней, `MAX_HOPS=8`, per-recipient=200, global=10000.
- Relay/receipt/gossip summary wire formats.
- Bounded gossip и missing-relay forwarding.
- Direct recipient delivery, receipt cleanup и origin delivery path.
- Automatic origin relay при недоступном recipient, Outbox и честный `QUEUED_OFFLINE`.
- Bounded dual MQTT broker transport и production dedup.
- v11.16.16 build/install/launch readiness PASS на Анне, Жене и Стасе.
- Пользователь вручную видел успешную offline UI-доставку через третий телефон.
- Строгий post-capture не содержал exact message/protocol markers, поэтому конкретная message-ID
  chain, полный receipt cleanup и eventual origin `DELIVERED` не доказаны финальным acceptance.

#### Release

- Опубликован **prerelease v11.16.16**:
  `https://github.com/vzhem/APUMIR/releases/tag/v11.16.16`.
- APK: 22,664,712 B.
- APK SHA-256:
  `446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D`.
- Signer certificate SHA-256:
  `F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7`.
- Tag/target: `85aecb0fa9893184e357b6c565869d0f1ebd69b7`.
- GitHub server asset digest совпал; tag workflow success, build job skipped, APK не пересобирался.
- Stable release ещё не заявлен: M8, mixed-version/r4.5 и security gates впереди.

#### Portable backup

- Готова компактная копия `F:\APU_PORTABLE`.
- 278 manifest entries, все hashes PASS.
- previous v11.16.15 + latest v11.16.16.
- Bundle verify, forbidden scan и restore rehearsal PASS.
- Final backup state SHA-256:
  `A96500612DD1AC80D908F1F49ADE9536931E512D387C2FD0EDA8CB82772D2483`.
- **Флешку больше никогда не форматировать.** Только verified rotation: incoming latest → проверить;
  старая latest → previous; удалить можно лишь версии старше previous после final verify.
- Browser release flow признан удобным и теперь preferred: Arena создаёт draft → пользователь
  загружает APK+sha через GitHub browser → Arena проверяет server digests → Arena публикует.
- Не требовать Windows `gh` token/password; Windows keyring token оказался invalid.

#### Документация

- Master plan, mesh design, collaboration notes, flash runbook и recovery guide обновлены.
- `docs/VERSION_STATISTICS.md` — append-only отчёт каждой глобальной версии.
- Baseline v11.16.16: основной код 31,645 physical / 27,685 nonblank; с generated 34,155 /
  29,750; automation 17,117. Для следующей глобальной версии добавить LOC delta тем же методом.

### 5. Главный незакрытый пользовательский сценарий

APU должен гарантировать:

1. Анна отправляет сообщение офлайн-Стасу; Женя получает relay custody.
2. Женя не может доставить, атомарно сохраняет encrypted custody и засыпает.
3. Все телефоны могут быть offline.
4. Через сутки Женя просыпается, восстанавливает старую очередь, запрашивает новые eligible relay
   items и пытается передать старые+новые.
5. Женя кратко пересекается online с relay D и передаёт custody; затем Женя offline/process killed.
6. Позже только D и Стас пересекаются online; D доставляет ровно одно UI message.
7. Анна и Женя в момент доставки не нужны.
8. Receipt/tombstone переживают restart, очищают все custody copies при следующих появлениях и
   eventually ставят origin `DELIVERED`.
9. Process death, app restart и reboot любого relay не теряют сообщение.
10. Если физического разрешённого online-overlap двух соседей не было, APU честно сохраняет Outbox и
    не обещает невозможную доставку.

### 6. Текущий архитектурный разрыв

`rust-core/src/network/relay_queue.rs` сейчас содержит:

- `Mutex<HashMap<String, RelayMessage>>` только в RAM;
- `RelayMessage.created_at: Instant`;
- `RelayMessage.expires_at: Instant`;
- no persistent backing, startup recovery или durable tombstones.

Поэтому текущая multi-hop цепочка возможна только пока custody processes/queues живы. После process
death/reboot relay может потеряться. Это главный blocker M8.

Важная security-оговорка: комментарий типа называет payload E2E encrypted, но M7 strict acceptance
ещё не завершён и notes указывают, что payload path нельзя считать доказанно безопасным. Не записывать
plaintext в persistent store и не заявлять encryption без доказательства. Persistent design должен
хранить recipient ciphertext и/или дополнительно encrypted-at-rest record с ключом, защищённым
Android Keystore. Relay не должен читать plaintext.

### 7. Код, который прочитать перед первым изменением

- `rust-core/src/network/relay_queue.rs` — RAM queue и tests.
- `rust-core/src/engine/core.rs` — RelayQueue ownership, relay/receipt/gsumm paths.
- `rust-core/src/network/offline_send.rs` — origin relay creation.
- `rust-core/src/network/wire.rs` — protocol formats.
- `rust-core/src/storage/db.rs` — существующий rusqlite migration V1.
- `rust-core/src/storage/models.rs` — epoch-ms helper/model convention.
- `rust-core/src/storage/mod.rs` и `rust-core/Cargo.toml`.

`rusqlite` bundled, serde, bincode, chacha20poly1305 и sha2 уже есть. Не добавляй новую тяжёлую
зависимость без необходимости.

### 8. Непосредственный coding-шаг M8-A — выполнить сейчас

Сделай только foundation slice, не пытайся сразу закончить весь M8:

1. Убери process-local `Instant` из persistent-facing `RelayMessage`.
2. Введи serializable absolute UTC epoch milliseconds (`created_at_ms`, `expires_at_ms`) с безопасным
   conversion из `Duration` и без продления TTL после restart.
3. Добавь `Serialize/Deserialize` для durable relay record только если все поля имеют bounded/
   validated representation.
4. Добавь методы с explicit clock для детерминированных tests, например `is_expired_at(now_ms)`, а
   `is_expired()` оставь thin wrapper над system UTC.
5. Добавь validation/loading constructor: reject empty/oversized metadata, invalid timestamps
   (`expires < created`), expired records и `hop_count >= MAX_HOPS`; не panic на clock-before-epoch.
   Не придумывай другие лимиты: вынеси/переиспользуй текущие offline-send bounds — msg ID 128 B,
   recipient/origin 128 B, chat scope 256 B, envelope 64 KiB — без циклического module dependency и
   без несовместимого wire change.
6. Сохрани существующее внешнее поведение `new`, `with_ttl`, dedup, limits, gossip и hop semantics.
7. Обнови все Rust callsites/тесты, которые используют старые поля.
8. Добавь unit tests в `relay_queue.rs` на:
   - fixed timestamp round-trip;
   - TTL не продлевается после serialize/deserialize;
   - expired persisted record rejected/cleaned;
   - invalid timestamp/hop rejected;
   - `next_hop` сохраняет absolute expiry;
   - clock-before-epoch/default handling без panic.
9. Не добавляй SQLite write в этом же slice — сначала добейся чистой durable model boundary.
10. Выполни `git diff --check` и source-level checks. Sandbox `cargo test` запрещён; прямо напиши, что
    compile/runtime ждут Windows `build-rust.ps1` в отдельном следующем gate.

Перед API-изменением grep всех callsites. Не ломай wire compatibility: timestamps persistence model
не должны самовольно менять current relay wire format в этом slice.

### 9. Последовательность после M8-A

#### M8-B — storage abstraction

- Ввести `RelayStore`/durable repository boundary.
- Отдельная SQLite migration для relay records и tombstones.
- Atomic transaction: persist custody before enqueue/ACK/offer.
- Unique primary key `msg_id`, recipient index, absolute expiry index.
- Bounded startup load; expired records delete without UI delivery.
- Не держать `rusqlite::Connection` бездумно через async await; использовать строгий sync boundary/
  mutex или dedicated worker.

#### M8-C — encryption and key lifecycle

- Persist only recipient ciphertext.
- Дополнительное encryption at rest для record metadata/payload.
- Android Keystore-backed key lifecycle, versioned envelope, nonce uniqueness.
- Corrupt/unknown key/version quarantine, не crash и не plaintext fallback.
- App update migration; data clear закономерно теряет local custody, но не маскировать это.

#### M8-D — startup/process/reboot recovery

- Load validated unexpired records при start.
- Restore dedup, hop, receipt tombstones, quotas и cursor без TTL reset.
- Crash-safe insert/remove transactions.
- Idempotent replay: UI exactly once.

#### M8-E — sleep/wake/background cycle

- Explicit relay consent.
- Bounded WorkManager/foreground window после network/app/periodic wake.
- Сначала restore old queue, затем summary/missing-ID request, затем old+new delivery attempt.
- Всё недоставленное снова durable сохраняется; no busy loop.
- Battery/network/traffic budgets и honest restricted status.

#### M8-F — acceptance

- Local tests first, потом controlled 3–4 phone test.
- Non-overlapping windows Anna→Zhenya→D→Stas.
- Delay up to at least one day.
- Process kill/restart и reboot Zhenya/D.
- Exactly-one UI, receipt cleanup, eventual origin delivered.
- Mixed N↔N-1 и compatible schema migration.

### 10. Телефоны и уже использованные сценарии — не повторять

Телефоны: Анна, Женя, Стас. Сейчас их не трогать.

Не повторять:

- reconnect Анны и старые v11.16.12/v11.16.13/v11.16.14/v11.16.15 attempts;
- v11.16.16 Rust/APK build, install, launch/readiness;
- consumed M3(d) offline prepare;
- manual capture;
- format F:;
- compact backup v1/v2/resume v3/v4;
- release upload attempts и publication v11.16.16.

Не удалять их state/evidence. Особенно:

- format state SHA-256 `A75443F8D302B8D856237F63C2122ABA4C676A6456078066125EF1455E1FACFF`;
- compact backup final state SHA-256
  `A96500612DD1AC80D908F1F49ADE9536931E512D387C2FD0EDA8CB82772D2483`;
- phase-A prepare state SHA-256
  `0FCA3B35B5887C3F56C3A5D0BB23EA5F370200EA7CBDB22132693B082469607A`;
- manual capture state SHA-256
  `27D9B17773349E36060D869A866DA73B2EE20DA423B9F3323A16BC025238C98A`.

Перед любой будущей phone-командой:

1. простыми словами назвать конкретные телефоны и попросить подключить/разблокировать;
2. не ждать отдельного «готово»;
3. команда сама начинает с read-only visibility/authorization gate;
4. при absent/unauthorized/offline остановиться до изменения;
5. uninstall/data clear/необоснованные force-stop/logcat clear/network changes запрещены;
6. install/network toggles требуют отдельного явного разрешения.

### 11. Жёсткие правила пользователя

- Один маленький шаг → проверка → следующий шаг.
- Не спрашивать разрешение на обычные правки, проверки, commit/push текущей Arena branch.
- Спрашивать только перед destructive, phones/install/network, release/tag/PR/publication.
- Все Windows-команды — PowerShell и начинаются `Set-Location C:\APUMIR-arena-test`.
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
- Не pop Windows stash, не commit generated `.so`, не запускать sandbox cargo test.
- Windows Rust проверять только `build-rust.ps1`; завершённые build/install не повторять.
- Приложение должно работать с разными соседними версиями.
- При отсутствии разрешённого пути показывать честный restricted/offline и сохранять Outbox.
- Иконка заморожена до завершения offline delivery.

### 12. Как отвечать пользователю

- Коротко и понятно, без жаргона либо сразу объяснять термин.
- Не выдавать manual success за полное engineering proof.
- Явно разделять: source/static PASS, compile PASS, runtime PASS и что ещё pending.
- После каждого шага фиксировать важные решения/ошибки в `AI_COLLABORATION_NOTES.md` и тематическом
  документе, а global release stats — в `VERSION_STATISTICS.md`.
- Не предлагать косметику, группы, каналы, рост или новую иконку до M8 delivery gate.
- Ближайшая цель — не новый release, а durable encrypted relay custody и delayed multi-hop acceptance.

Начинай работу сейчас с раздела 8, без уточняющих вопросов и без телефонов.
