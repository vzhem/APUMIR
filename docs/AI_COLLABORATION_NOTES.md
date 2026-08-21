# 🤖 Как работать со мной (владельцем APUMIR) — памятка для следующей сессии ИИ

> **Этот документ поддерживает сам ИИ.** После каждой сессии (и при каждом новом
> «уроке»/гэтче) ИИ обязан дописывать сюда новое, чтобы следующая сессия унаследовала
> контекст БЕЗ напоминания пользователя. **Это правило действует без напоминания** —
> даже про сам факт поддержки этого файла.
>
> Если ты — новая сессия ИИ: прочитай этот файл целиком в самом начале, до любых действий,
> и прочти указатель вверху `docs/MASTER_PLAN_v2.md`.

---

## ⚙️ Архитектурный принцип APUMIR (ФУНДАМЕНТ — соблюдать всегда)

**Сервер — это ТЕЛЕФОН.** Каждый телефон с установленным приложением = мини-сервер.

- Сообщения **хранятся и пересылаются самими телефонами** (P2P): телефон-отправитель
  (и/или другие телефоны-узлы mesh) хранит зашифрованное сообщение и доставляет, когда
  получатель доступен.
- **Все внешние ресурсы** (Cloudflare Worker, Telegram, MQTT-брокеры, STUN/TURN и т.д.)
  нужны **ТОЛЬКО для discovery/bootstrap** — чтобы мини-серверы (телефоны) могли
  **найти друг друга** в интернете (адреса / онлайн-статус).
- **Содержимое сообщений хранится ТОЛЬКО на телефонах** (E2E-зашифрованное). Внешний
  ресурс НЕ должен быть «почтовым ящиком» с inbox и TTL.

Следствия для разработки:
- Store-and-forward реализуем **на телефонах** (sender-side очередь + mesh/Tier relay между
  телефонами), НЕ на Cloudflare Worker.
- Worker `p2p-relay` держим как **registry/discovery** (`/health`, `/register`, `/lookup`,
  `/version`). Релейные `/send`+`/poll` (KV inbox) — ОТКЛОНЕНИЕ от архитектуры; не полагаться
  на них как на хранилище офлайн-сообщений.
- Значит: фикс 404 на Worker'е НЕ решает офлайн-доставку по-настоящему — доставка идёт
  через телефоны.
- Открытый вопрос (уточнить у пользователя): могут ли внешние ресурсы ТРАНЗИТНО передавать
  зашифрованные байты между телефонами в реальном времени (TURN/MQTT как «провод»), или
  внешние ресурсы строго discovery-only, а байты — только телефон↔телефон напрямую/mesh?
- **РЕШЕНО пользователем (2026-08-13):** основной режим — **🅱️** (внешние ресурсы = строго
  discovery; байты идут телефон↔телефон напрямую или через mesh телефонов). Режим **🅰️**
  (внешний ресурс как транзитный «провод» для зашифрованных байтов в реальном времени,
  **БЕЗ хранения**) — **допустим как дополнение/fallback** (когда телефоны за NAT не могут
  соединиться напрямую). Хранилище сообщений — всегда только на телефонах.

---

## 🌐 Mesh store-and-forward — CORE-мысль пользователя (ОСНОВНОЙ принцип, соблюдать)

Если получатель офлайн — отправитель **НЕ просто ждёт его**, а действует по mesh-сценарию:

1. Отправитель видит, что получателя нет в сети → рассылает E2E-сообщение **всем телефонам,
   которые сейчас онлайн** (relay-узлы).
2. Отправитель может **сам уйти офлайн**.
3. Каждый телефон-узел **хранит** сообщение и **пересылает его новым появившимся телефонам**
   (эпидемическое распространение / gossip), пока получатель не появится в сети.
4. Когда получатель появляется — **какой-то узел доставляет** ему сообщение.
5. Получатель рассылает **«я получил msg_id»** → все узлы **УДАЛЯЮТ** это сообщение у себя
   (cleanup по доставке).

Это **эпидемический/gossip store-and-forward с cleanup по receipt**. Содержимое —
**E2E-зашифровано**, relay-узлы его не читают (видят только `msg_id`, `recipient`, `TTL`,
зашифрованный payload). Дедупликация — по `msg_id` на всех узлах.

**Тот же принцип — для групп и каналов:** сообщение группы/канала fan-out по участникам
через этот же mesh. **Фундамент строим ЗАРАНЕЕ**, чтобы при создании групп/каналов этой
проблемы (доставки офлайн-участникам) не было. См. MASTER_PLAN Фазы 2.3 / 3.4 / 7.4 и
`docs/OFFLINE_DELIVERY.md`.

Реализация — инкрементально: relay-queue на телефоне → gossip на peer-discovered →
receipt-cleanup → интеграция в отправку → переиспользование группами.

---

## 0. Главное правило

Пользователь работает маленькими шагами:

> один шаг → проверка → следующий шаг.

Никаких больших пачек задач без подтверждения. Каждое действие — отдельный маленький шаг
с проверкой результата, потом согласование следующего.

### 0.1. Обязательное напоминание о milestone-backup

Только после действительно крупной **проверенной** точки (новая существенно изменённая APK,
release candidate/стабильный release, завершённый большой этап или точка перед рискованной
миграцией) ИИ предлагает сохранить APU на внешний диск/флешку. Не дёргать флешку для docs-only,
повторной проверки той же APK, небольшого security checkpoint или промежуточного коммита:
remote Git и существующего milestone-backup достаточно. Не копировать без согласия: сначала
спросить букву диска и проверить место. Полная копия включает Git bundle+history, точный source
commit, проверенный APK и `libp2p_core.so`, SHA-256, environment/milestone manifests, docs и
инструкцию восстановления/сборки/запуска на чистом Windows-ПК. Проверить bundle и все hashes.
Учитывать signing material: рекомендовать зашифрованный носитель, не копировать tokens/`.env`/
переписку/private identity keys. Каноническая процедура:
`docs/BACKUP_AND_CLEAN_PC_RECOVERY.md`. Обычную неудачную/промежуточную сборку milestone не считать.

### 0.2. Обязательный журнал ошибок и обходов

Каждую обнаруженную ошибку, ловушку инструмента и безопасный workaround ИИ обязан сразу
записывать в `AI_COLLABORATION_NOTES.md` и в тематическую инструкцию/план. Не оставлять решение
только в чате. Фиксировать: симптом, причину, точный безопасный обход, как проверить результат
и что нельзя делать. Если обход снижает безопасность или является временным — явно отметить
риск и будущий правильный fix. Перед похожим шагом новая ИИ-сессия сначала ищет существующий
гэтч и не заставляет пользователя повторно проходить уже известную ошибку.

### 0.3. Обязательное предупреждение перед подключением телефонов

Перед **любой** командой или тестом, которому нужен хотя бы один телефон, ИИ обязан непосредственно
перед блоком простыми словами назвать конкретные телефоны, которые нужно подключить по USB: Анна,
Женя и/или Стас. **Ждать отдельного подтверждения подключения не нужно:** после ясного предупреждения
можно сразу дать команду. Команда обязана безопасно остановиться на read-only visibility gate, если
нужного телефона нет/он unauthorized/offline, до install/launch или другого изменения. Нельзя
прятать phone action внутри большого PC-блока без предупреждения. Если телефоны не нужны, прямо
отметить, что шаг только для ПК и подключать ничего не требуется.

### 🐣 Пользователь — новичок. Это важно.

- Объясняй **простым языком, без жаргона**. Если нужен технический термин — сразу
  расшифруй его одним предложением.
- Давай **готовые команды**, которые можно скопировать и вставить целиком (копипаст).
- Не предполагай, что пользователь сам додумает промежуточные шаги — пиши их явно.
- Лучше больше простых шагов, чем один сложный.

---

## 1. Общение и стиль

- Язык общения — **русский**.
- Будь конкретен: что изменил, где, как проверить — но простыми словами.
- Не делай вид, что что-то работает, если не проверил. Честно говори, что не можешь
  проверить (например, сборку Android в sandbox).

---

## 2. Команды для моей Windows-машины

- Все команды для моего компьютера — **Windows PowerShell**.
- **Каждый блок команд начинается с `Set-Location <путь>`.**
- Python запускать как **`py -3`**, не `python`.
- ⚠️ **На машине несколько клонов репозитория** — это путает. Договорились: arena-ветки
  тестируем в **одном каноническом клоне `C:\APUMIR-arena-test`**. Версионные снапшоты
  (`C:\APUMIR-v11.16.4-final`, `C:\APUMIR\main-validation-v11.16.3`) — НЕ мутировать.
  `C:\APUMIR` содержит `release-staging\`, но это НЕ рабочий git-клон.
- ⚠️ **Код из Arena-sandbox сам по себе не виден на моей Windows-машине.** Нормальный путь — ИИ
  коммитит+пушит только текущую session branch, а Windows clone получает её через Git. Для этой
  сессии branch fixed: `arena/01a000bc-apumir`; не checkout старые branch names из журнала:
  ```powershell
  Set-Location C:\APUMIR-arena-test
  git fetch origin arena/01a000bc-apumir
  git checkout arena/01a000bc-apumir
  ```
  Если GitHub TCP 443 недоступен или Arena не отдаёт скачиваемый файл, не повторять Download/
  Downloads: использовать authenticated inline gzip/Base64 процедуру из раздела 2.1.
- Если путь к клону вдруг неизвестен — найди `gradlew.bat`:
  ```powershell
  Get-ChildItem -Path C:\ -Filter gradlew.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 5 FullName
  ```
- ⚠️ Чат/терминал иногда превращает точки в пакете (`com.vladimir.messenger.data`) в
  гиперссылку. В командах вида `gradlew --tests "..."` следи, чтобы FQN не исказился —
  лучше скажи мне вставить строку вручную.

### 2.1. Передача файлов из Arena на Windows: обязательный рабочий порядок

- **Проверенный факт 2026-08-15:** карточка Arena file viewer / `Download`, повторный поиск
  файла в браузерных `Downloads` и просьба пользователю «найти скачанный файл» не передали bundle
  на Windows. Это ограничение интерфейса передачи, а не ошибка пользователя. Новая ИИ-сессия не
  должна снова отправлять пользователя к карточке, кнопке Download или папке Downloads.
- Если GitHub недоступен и файл нельзя скачать из Arena, передавать нужные байты **непосредственно
  внутри готового PowerShell-блока**: создать минимальный deterministic patch от точно
  проверенного Windows base, gzip-сжать его, закодировать Base64 и встроить payload в here-string.
  Если full overlay велик, сначала разделить его на независимые минимальные части (например Rust,
  затем Kotlin), а не заставлять пользователя вручную копировать исходники.
- Блок всегда начинается с `Set-Location C:\APUMIR-arena-test`, затем единый atomic
  `& { $ErrorActionPreference = "Stop"; ... }`. До записи/apply он проверяет exact branch/HEAD,
  ожидаемый `git status` и hashes сохраняемых generated/untracked artifacts. В текущем кейсе нельзя
  потерять generated `.so` и untracked original icon; `git clean`, broad stash/pop и hard reset
  запрещены.
- Base64 нельзя считать аутентифицированным сам по себе. Обязательны два независимых identity
  gate: размер+SHA-256 сжатых байтов **сразу после decode** и размер+SHA-256 распакованного patch.
  Только затем `git apply --check --whitespace=error-all`, сам `git apply`, проверка точного списка
  worktree paths и filter-aware Git blob каждого target source file. Любое несовпадение = STOP до
  build; не применять частично и не делать automatic retry.
- После offline patch Windows `HEAD` намеренно остаётся на base commit, а source становится dirty.
  Это доказывает exact содержимое и compile, но **не** переносит Git history/commit identity. Нельзя
  писать, что ветка синхронизирована с target commit. Историю позже согласовать отдельно без commit
  generated `.so` и без потери untracked icon.
- Incremental Git bundle остаётся хорошим автономным форматом и должен проходить `git bundle
  verify`, size/SHA-256 и prerequisite checks, но в этой среде его передача через Arena download
  фактически не сработала. Не выбирать bundle как пользовательский путь, пока нет реально
  доступного канала доставки байтов; рабочий fallback здесь — inline gzip/Base64 с hashes.
- Critical inline payload обязан быть **полным**: не давать отдельно короткую строку build, которую
  новичок может запустить из `C:\Users\User`. Блок сам создаёт `C:\APUMIR-transfer`, сохраняет
  immutable state/logs, имеет bounded child wait и печатает PASS только после source/artifact/hash
  gates. Телефоны для source transfer/Rust/APK compile не нужны — это нужно прямо сказать.

---

## 3. Релизы, теги, PR

- Перед **любой** публикацией / тегом / релизом — **всегда отдельно спрашивай подтверждение**.
- **PR не мержить**, пока я явно не скажу, что этап готов к завершению.
- Все коммиты/пуши — только в текущую сессионную ветку.

---

## 4. Репозиторий и окружение

- Репо: **github.com/vzhem/APUMIR**.
- Архитектура: Rust core (`rust-core/`) + Android-приложение Kotlin/Compose (`android-app/`).
- Тестовые телефоны (установка `adb install -r`):
  - `11567254BK001192` — Стас
  - `3B665800EES00000` — Женя
  - `AUYF6R5923006121` — Анна
- Релизные артефакты лежат в `C:\APUMIR\release-staging\`
  (`P2P-Messenger-vX.Y.Z.apk` + `.sha256` + `-provenance.json`).
- Текущий опубликованный релиз — **v11.16.5** (tag `v11.16.5` → commit `b83d9b3`; APK
  `P2P-Messenger-v11.16.5.apk`, SHA-256 `993ae5eb33197f4f998a6db84ef757c16a5a4fdc08beda54d6f9e95c6d12000d`;
  сборка arm64-v8a; отмечен «Latest»). Предыдущий: v11.16.4 (`991da05…`). ⚠️ Workflow
  `build-release.yml` сейчас **отключён через UI** (чтобы v11.16.5 не испортился) — правильный
  фикс с guard pending; пользователь должен запушить его и重新 включить workflow.
- Известный долг: дублирующие чаты на телефоне Женя от старых версий — будущий cleanup/миграция.

---

## 5. Гэтчи проекта (уроки прошлых сессий — не наступать снова)

- **Нельзя делать две правки одного файла параллельно** в одном шаге — однажды это
  сломало `MASTER_PLAN_v2.md` (вставка не туда + дублирование). Правки одного файла —
  строго последовательно, по одной, с проверкой между.
- **`AppDatabase` использует `fallbackToDestructiveMigration()`** → повышение версии Room
  **СТИРАЕТ** все данные пользователя (чаты/контакты/сообщения). Менять схему — только с
  настоящей миграцией, либо избегать. Статусы/каналы хранятся как String-колонки →
  добавление значений в enum миграции НЕ требует.
- **В sandbox агента нельзя собрать Android/Rust** (нет Android SDK / кросс-компилятора /
  `kotlinc`). Код пишется диффами, компиляция и тест — на моей Windows-машине.
- **Чистый Kotlin (без Android-deps) тестируется в JVM** — предпочитай чистые хелперы +
  unit-тесты (пример: `util/InviteLinkParser`, `data/relay/RelayEnvelope`). `org.json` в
  unit-тестах НЕ работает (Android-stub «not mocked») — не используй его в тестируемых
  хелперах.
- **Исходник Cloudflare Worker'а НЕ в репо** (развёрнут на Cloudflare) — его поведение
  (endpoints `/send`, `/poll`, TTL/retention inbox) известно лишь по клиентскому коду
  (`service/CloudflareRelay.kt`). Это открытый вопрос для офлайн-доставки «через неделю».
- **Sandbox может пере-клонироваться между ходами**: локальная ветка `arena/019ffc32-apumir`
  сбрасывается к базе (`991da05`), рабочие файлы сохраняются, а **remote остаётся целым**
  (все коммиты на месте). Симптом: `git log` показывает мало коммитов, а `git status` — кучу
  «modified/untracked» для уже закоммиченных файлов; `origin/arena/...` ref может отсутствовать.
  **Лечение**: `git fetch origin arena/019ffc32-apumir`; узнать tip через `git ls-remote origin
  arena/019ffc32-apumir`; `git reset --mixed <tip-hash>` (сохраняет рабочее дерево); проверить
  `git status` (остаться должны только новые изменения); закоммитить + `git push origin
  arena/019ffc32-apumir` (fast-forward). Если после reset среды уже случайно создан локальный
  commit от base и push отклонён как `fetch first`, не merge и не force-push: fetch remote tip,
  filter-aware сравнить каждый рабочий файл с remote tree, отдельно сохранить только реальные
  отличия, вернуть branch на remote tip и заново сделать малый commit. `reset --hard` допустим
  только после полного hash-сравнения и временной копии отличий; обычно безопаснее `--mixed`.
  **НИКОГДА не делать `--force` push** — затрёт remote.
- **Перенос в новую сессионную ветку выполнен 2026-08-13:** `arena/019ff7c3-apumir`
  fast-forward-слита в `arena/019ffc32-apumir` (`991da05` → `e5b171c`) и новая ветка запушена.
  Команды переноса были: `git fetch origin`; `git merge origin/arena/019ff7c3-apumir`;
  проверка `git log --oneline -15` и наличия `network/relay_queue.rs` + `network/wire.rs`;
  `git push origin arena/019ffc32-apumir`. **Для продолжения источник истины — только новая
  ветка `arena/019ffc32-apumir`; старую не checkout и не пушить.** Новый ИИ сразу проверяет
  текущую ветку и подтягивает её только fast-forward, без `force-push`.
- **MQTT broker fallback сейчас фактически не проверяет соединение:** `MqttTransport::connect`
  считает `AsyncClient.publish(...).await` успехом, но это лишь постановка request в bounded
  channel до polling `EventLoop`, а не TCP/ConnAck. Поэтому цикл всегда выбирает первый broker;
  строка `subscribed` тоже означает queued subscribe, а не доказанное соединение. Реальный gate —
  `ConnAck`. Будущий fix: bounded await ConnAck/timeout и rotation broker (либо state machine после
  bounded poll errors), затем subscribe; не создавать flood reconnect. Security smoke: после
  timeout Стас не дал ConnAck даже после cold start, тогда как Анна/Женя дали 1/1.
- **Масштабирование mesh:** текущие MQTT `p2pm2/#` и all-to-all presence — функциональный
  прототип, НЕ готовая схема для тысяч телефонов. Уже есть: dedup `msg_id`, TTL 7 дней,
  `hop_count` до 8, queue 200/recipient и 10 000 total; M3(c.1) добавляет 256 items/64 KiB,
  cooldown 60 с/peer и 8 summaries/30 с global. До M3(c.2) обязательны batch/byte budgets
  на relay-пересылку (начать с ≤16 relay за round) и честный fair fanout. Для production на
  тысячи узлов нужны bounded neighbours (несколько peers за round), DHT/sharding и подписка
  на own/neighbour topics вместо глобального wildcard. Не обещать масштаб до нагрузочного теста.
- **Relay priority — решение пользователя (2026-08-14):** 1) друзья/прямые контакты;
  2) друзья друзей (только по проверяемой/signed цепочке знакомства); 3) любые узлы — лишь
  как opt-in, когда телефон не занят, не перегрет, с достаточной батареей/сетью/местом.
  Реализовывать weighted fair queues с квотами, чтобы strangers не вытесняли друзей и при
  этом не голодали навсегда.
- **Настройка relay — решение пользователя (2026-08-14):** три режима в UI: «Лёгкий»,
  «Средний», «Без ограничений». Текущий M3(c.2) реализует backend-бюджеты «Среднего» режима
  (16/256 KiB round, 32/512 KiB за 30 с). UI/config plumbing — отдельный шаг. Даже в режиме
  «Без ограничений» НИКОГДА не отключать hard safety: dedup, TTL, max hops, queue/storage,
  thermal/OS safety; это означает без мягкого лимита трафика, а не буквально без защиты.
- **Background receive-only — решение пользователя (2026-08-14):** приложение должно
  периодически просыпаться в фоне хотя бы для получения СВОИХ сообщений, не становясь relay-
  сервером. Это отдельный Android-шаг после текущих r1/r2: bounded WorkManager wake при наличии
  сети → receive-only Rust mode → подписка только own topics / signed bounded pull → короткое
  окно приёма → stop. В этом режиме запрещены enqueue/хранение/forward чужих relay. Android
  не гарантирует точный момент wake; для real-time нужен отдельный opt-in foreground service.
  Телефон без сети/выключенный не разбудить — сообщение ждёт у relay до следующего окна.
- **Уникальный `@username` — решение пользователя (2026-08-14):** глобально искать не по
  display name (имена могут совпадать), а по единственному латинскому `@username`, связанному
  с конкретным `pk_…`/public key. Display name остаётся неуникальным. Binding должен быть
  подписан и проверен клиентом; нужны case normalization, uniqueness, rate limits/privacy,
  rename/recovery и защита от squatting. Подробный backlog — `MASTER_PLAN_v2.md`, фаза 1.7.
- **Медиа/файлы — подтверждено пользователем (2026-08-14):** план обязан включать фото,
  видео, аудио, voice notes, видеокружочки, документы/любые файлы, GIF/stickers, previews,
  progress/resume, encrypted chunks/checksums, offline quotas и storage cleanup. Также нужны
  личные Stories: фото/видео/текст, audience privacy, signed manifest, E2E chunks, TTL 24 ч,
  views/reactions/replies и friend-only bounded relay без flood. Большие media не помещать в
  MQTT text envelope. Backlog — `MASTER_PLAN_v2.md`, фазы 5.3–5.4.
- **Бренд — решение пользователя (2026-08-14):** пользовательское название везде только
  **APU**. APUMIR/P2P Messenger остаются лишь техническими/историческими именами repo, папок,
  package/classes и legacy links; не делать рискованный массовый rename. Нужны аудит всех UI
  strings, новый оригинальный modern logo (adaptive/monochrome/notification/splash/vector),
  design system и последовательное light/dark/accessibility оформление. Регистрация: убрать
  `Например: Владимир`, использовать `Имя или имя и фамилия` / `Как к вам обращаться`, пояснить
  отличие display name от уникального `@username`. План — фаза 0.3.
- **Диагностические отчёты — решение пользователя (2026-08-14):** телефоны должны уметь
  отправлять ошибки в выбранное проектом место для удобного исправления. Только manual send или
  явный opt-in auto-crash; redaction plaintext/keys/tokens/PII, preview, encryption, size/rate
  limits, dedup/backoff/retention и локальный export. Не отправлять напрямую «ИИ»: ИИ получает
  только явно приложенный export или доступный project issue/report. План — фаза 2.4.
- **Проверки атак — решение пользователя (2026-08-14):** периодически на разных важных APK
  проверять spam, duplicate/replay, DoS/resource exhaustion, malformed/fuzz inputs, Sybil/
  spoofing, Android/deep-link/background, media/storage, diagnostics/privacy и update/signing.
  Короткие low-volume пробы можно делать на 3 своих телефонах; flood/load/soak — только на
  локальном broker/mock, никогда не на публичном HiveMQ/чужих сервисах. После атаки обязательно
  проверить recovery обычным сообщением. Полный план: `docs/SECURITY_RESILIENCE_TEST_PLAN.md`.

---

## 6. Текущий фокус работы (resume here — следующая сессия)

> **CURRENT OVERRIDE 2026-08-15:** активная ветка этой сессии — только
> `arena/01a00674-apumir` (новая Arena-сессия; handoff-история подтянута в неё ff-only из
> `arena/01a000bc-apumir`, tip `99ac840`). Не переключаться на старые session branches из
> исторического журнала ниже и не создавать новую ветку. Sandbox source M3(d) —
> commit `61e1580ff85aa1cfaed1f9e7a7522f1cd8e5d602`; versioned Kotlin/APK harness — commit
> `dfd36d9`. Канонический Windows clone: `C:\APUMIR-arena-test`, его HEAD намеренно пока
> `8cea566e50f439810e29fb1dc4ac14dc69b5fbc6`.
>
> **Последний доказанный результат:** Rust+Kotlin M3(d) exact overlays применены на Windows и
> signed test APK v11.16.16/11016016 собран PASS: 22,664,712 B, SHA-256
> `446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D`; V2 cert
> F843…A5F7, embedded `.so` 7,263,416 B / `27B9D4DC…D1FD26C`. APK state
> `%TEMP%\apu-m3d-v11.16.16-apk-build.json` SHA-256
> `917077E82C25DFF9A020713BA4A391DD49D7AFD81446367F87A23B17216AFABC`; transfer state
> A4D8…5154; chunk-recovery state 545D…CA98. Kotlin patch target blobs=5/5, parser errors=0,
> BUILD SUCCESSFUL=1, build exit=0. Rust build и APK build не повторять; evidence/APK не удалять.
> Windows HEAD всё ещё base `8cea566…`, worktree содержит exact Rust+Kotlin overlays, generated
> native, untracked build harness и icon; history reconciliation отложен.
>
> **CURRENT product state:** approved M3(d) prepare уже выполнен один раз и завершён
> `INCOMPLETE_DO_NOT_REPEAT`: Wi-Fi Стаса стал 0, mobile data остался 1, поэтому строгий harness
> правильно остановился до message/install/launch/force-stop/log clear/data clear. Пользователь
> затем вручную выполнил сценарий и сообщает, что UI delivery сработала. Последующий single-use
> read-only capture сохранил stable PIDs/no crash, но exact text и protocol markers=0, поэтому это
> полезное functional observation, не финальное engineering proof relay chain/receipt cleanup.
> Phone steps не повторять и телефоны сейчас не менять.
>
> **Следующий product priority — M8 durable delayed multi-hop custody:** encrypted persistent
> RelayQueue должен пережить process death/reboot/sleep. Перед sleep custody flush; после wake APU
> сначала восстанавливает старые items, bounded summary/missing-ID exchange запрашивает ещё доступные
> relay items, пытается передать их и снова durable хранит всё недоставленное без продления TTL.
> Обязательный gate: Anna→offline Stas, Zhenya stores; через день Zhenya→new relay D в коротком
> overlap; Zhenya offline/killed; позже D→Stas без Anna/Zhenya online, ровно одно UI message,
> durable receipt cleanup и eventually origin DELIVERED. До M8 это best-effort в живых RAM queues.
>
> **Release checkpoint:** пользователь выбрал GitHub prerelease v11.16.16 и backup на Windows F:.
> Draft создан, assets пока empty/unpublished. v1/v2/v3 не повторять. v4 patch применился с CRLF,
> read-only normalization доказала expected LF hash, затем normalization/write/parser PASS и full
> workspace robocopy начался. Пользователь правильно остановил его: full build/cache/evidence copy не
> соответствует portable backup. One-time approved format уже удалил partial tree; не повторять.
> Read-only gate доказал F:=Disk2 `General UDisk`, USB/MBR, FAT32 `SMARTBUY`, 14.62 GiB, healthy,
> DriveType2, IsSystem/IsBoot=false. Explicitly authorized guarded format PASS: F: теперь exFAT,
> label `APU_BACKUP`, empty; immutable format state SHA-256 `A75443F8…FACFF`. **Никогда больше не
> форматировать эту флешку:** future updates replace/rotate files only; retain previous+latest and
> delete older only after new pair verifies. Compact v1 stopped before F: copy; do not repeat.
> Compact portable backup PASS: `F:\APU_PORTABLE`, 278 hashes, previous v11.16.15 + latest
> v11.16.16, bundle/forbidden/restore rehearsal PASS, state SHA-256 `A9650061…2D2483`.
> Флешку никогда больше не форматировать; rotate files only. v1/v2/resume-v3/full-v4 transfer и
> alias-H wrappers не повторять. **v11.16.16 prerelease published**: exact APK 22,664,712 B /
> SHA-256 `446A1EE9…429DC0D`, checksum asset exact; GitHub server digests PASS, tag target `85aecb0`,
> workflow release-exists PASS/build skipped. Preferred next-time flow: Arena creates draft → user
> uploads two flash files in browser → Arena verifies server digests and publishes. Version baseline
> lives in `docs/VERSION_STATISTICS.md` and must get a short immutable entry+LOC delta for every global
> version. Full no-questions handoff prompt: `docs/NEXT_AI_CHAT_BOOTSTRAP.md` (⚠️ может отставать —
> сначала сверить с tip новейшей arena-ветки, см. доп.195). **M8-A и M8-B/D source slices выполнены
> 2026-08-15** (durable epoch-ms model + RelayStore/migration + engine persist/restore/tombstone
> wiring + tests; sqlite3-прототип и delayed-сценарий симуляция PASS, tree-sitter syntax PASS — см.
> доп.193/194); **M8-C slice 1 source выполнен 2026-08-16** (pure at-rest AEAD envelope граница
> `storage/relay_at_rest.rs`: versioned XChaCha20-Poly1305 конверт, AAD-привязка к колонкам,
> Keystore seam через `key_id`, quarantine-ошибки, 12 tests, без SQL-изменений — см. доп.195) и
> **M8-C slice 2 выполнен 2026-08-16** (relay schema v2: `relay_records_enc` + `relay_quarantine`,
> encrypted API в RelayStore, 13 tests, sqlite3-прототип 24/24 PASS — см. доп.196); **M8-C slice 3
> source выполнен 2026-08-16** (Android Keystore-мост `RelayAtRestMasterKey.kt` + Rust
> `MasterSecretKeySource`/глобальный install-реестр + engine полностью на encrypted API через
> `RelayCustody`; custody всегда encrypted, без ключа — честный RAM-only ephemeral, durable-файл
> без ключа не создаётся; UniFFI: `install_relay_at_rest_key`/`create_engine_durable`/
> `relay_custody_mode`/`relay_quarantine_count`; backup-исключения; 6 новых Rust tests —
> см. доп.197). **Sandbox push заблокирован** (нет GitHub credentials): локальные commit'ы ждут
> `git push origin arena/01a00674-apumir` при доступной среде либо переносятся mbox-патчем.
> Next product priority: **Windows compile gate M8 (A→C3): `build-rust.ps1 -Features
> mqtt-dual-broker` + uniffi-bindgen регенерация Kotlin bindings + `gradlew assembleDebug`**,
> затем M8-E (sleep/wake), M8-F (телефонный acceptance). Mixed N↔N-1/r4.5 remain
> stable-release gates. Иконка пока заморожена. Новых релизов нет и не будет до закрытия гейтов —
> см. доп.196/197.
>
> Исторический summary ниже нужен для evidence/запретов, но его старые «следующий шаг» и branch
> labels не переопределяют CURRENT OVERRIDE.

Канонический клон для сборки/теста: `C:\APUMIR-arena-test` (arm64-v8a). Сборки:
`build-rust.ps1` (Rust) + gradlew assembleRelease (см. раздел 8.1). Хостовый `cargo test` НЕ
работает (ring/aws-lc MSVC) — см. раздел 8.

**Сделано (v11.16.5 — РЕЛИЗ ОПУБЛИКОВАН; M0–M2, M3.1, M3(a/b/b.1/c.1) + M3(c.2/r1/r2/r3)):**
- D1 (статусы) + D2 (ACK round-trip) — **✓✓ DELIVERED работает** (Rust-фикс `0c992b9`).
- Базовая офлайн-доставка (отправитель онлайн, получатель офлайн→онлайн) — работает.
- Recipient-aware routing + отключён старый relay-шторм (Rust-фиксы `7e8586b`, `b83d9b3`).
- Архитектурный принцип (телефоны = серверы; mesh) + `docs/MESH_DELIVERY.md`.
- **M1 `RelayQueue`** + **M2 `network/wire.rs`** (mesh-конверты relay/receipt/gsumm) — компилируются.
- **M3.1:** `RelayQueue` добавлена в `P2PCore`, создаётся в `start()` и передаётся параметром
  в `run_mqtt_transport`. Windows `build-rust.ps1` успешно завершён 2026-08-13.
- **M3(a) ВЕРИФИЦИРОВАН на 3 телефонах:** `relay|…` разбирается через `wire::parse`;
  локальный получатель получает `MessageReceived`, чужое сообщение хранится с TTL/hop.
  Перед единственным `enqueue` явно вызывается `contains(msg_id)`. Windows Rust + APK
  `v11.16.6` собраны; ручной `m3a-1786687291239` дал: Анна/Женя `stored … hop 1` без сообщения
  в UI, Стас получил ровно одно сообщение, счётчики логов 5→5 / 5→5 / 7→7 — шторма нет.
  Gossip и send-path ещё НЕ добавлены.

- **M3(b) ВЕРИФИЦИРОВАН на 3 телефонах:** `receipt|…` проверяет `msg_id` + `recipient`,
  удаляет сообщение из `RelayQueue`, а на origin эмитит `MessageDelivered`. Ручной тест
  `m3b-1786688346629`: Анна `stored=1 removed=1 originDelivery=1`, Женя `1/1/0`, Стас
  `localDelivery=1 removed=0`; повторный receipt ничего повторно не удалил, чужого UI нет.
  Тихий замер стабилен (Анна 0→0, Женя 0→0, Стас 9→9); шторма нет.

- **M3(b.1) ВЕРИФИЦИРОВАН на 3 телефонах:** Python опубликовал только relay
  `m3b1-1786689507840`; Стас автоматически отправил mesh receipt, Анна/Женя удалили запись,
  только Анна получила origin-delivery. Итог точно ожидаемый: Анна `1/0/0/1/1`, Женя
  `1/0/0/1/0`, Стас `0/1/1/0/0`; тихий замер 9→9 / 7→7 / 9→9, чужого UI и шторма нет.
  Старый ACK оставлен для совместимости; gossip/send-path не тронуты.

- **M3(c.1) ВЕРИФИЦИРОВАН на 3 телефонах:** каждый узел отправляет/принимает адресные
  `gsumm`, relay не пересылаются (`relay actions=0`). Повтор к одному peer идёт через
  60–66 секунд, то есть cooldown 60 с соблюдён. Логи 8/8/10 включали старый буфер logcat,
  но timestamps доказали интервалы; item/byte/global limits активны.

- **M3(c.2), relay-path ВЕРИФИЦИРОВАН на 3 телефонах:** Windows Rust + APK `v11.16.7`
  собраны. Для `m3c2-1786692960596` Анна/Женя сохранили hop 1; Анна ушла offline;
  Женя получил пустой `gsumm`, переслал ровно 1 relay/166 bytes; Стас получил 1 раз,
  отправил 1 receipt, Женя сделал 1 cleanup. Budgets 16/256 KiB round, 32/512 KiB/30 с.
  Финальный origin-delivery не прошёл: после возврата сети MQTT Анны не reconnect-нулся,
  хотя Android Wi-Fi был VALIDATED и TCP broker:1883 доступен. Также retained receipt может
  быть перезаписан ACK/summary на общем topic — это отдельный M3(c.2-r2).
- **M3(c.2-r1) ВЕРИФИЦИРОВАН:** `EventLoop::poll()` работает в непрерывной background task;
  основной цикл читает bounded channel (256), reconnect имеет backoff 1→30 с, а после
  повторного `ConnAck` заново подписывается на `p2pm2/#`. Windows Rust 1m01s, APK `v11.16.8`
  установлен на 3 телефона. На Анне Wi-Fi+data off/on: PID остался `23407`, offline errors=4
  с backoff 8/16/30/30 с, reconnect+re-subscribe=1, live probe=1.

- **M3(c.2-r2), unique receipt ВЕРИФИЦИРОВАН на `v11.16.9`:** Стас отправил receipt в
  exact SHA-256 topic; Женя cleanup=1; при offline Анне независимый subscriber получил retained
  payload с `retain=true`; после reconnect Анна сделала cleanup+origin-delivery и topic очищен
  (`RETAINED RECEIPT CLEARED`). Но тест выявил следующий blocker: c2 relay resend всё ещё
  retained на base recipient topic. После reconnect Анна получила старый relay, снова сохранила
  его и вызвала вторую доставку/receipt (`originDelivery=2`). Это не норма.

- **M3(c.2-r3) ПОЛНОСТЬЮ ВЕРИФИЦИРОВАН на `v11.16.10`:** code commit `e055471`,
  APK SHA-256 `E68F6EC976428AF5A2910A652653C41DFA9E4A84F4D1A149C8A0E3CF5F16D9DB` установлен
  на 3 телефона. Для `m3c2r3-1786702254585` Анна/Женя отдельно подтвердили store=1;
  Стас получил ровно 1 `MessageReceived`, второй live relay подавлен, receipts=2; обе очереди
  cleanup=1, origin-delivery только Анна=1, seen tombstones исключили re-enqueue. Fresh
  subscription exact base-topic Стаса получила только retained `gsumm`, retained relay=0.
  После Wi-Fi+data off/on Анны: PID всех трёх неизменны, reconnect+re-subscribe=1, live probe=1,
  повторные store/remove/origin/delivery/receipt/UI и relay payload = 0. `MQTT error` в logcat
  не появился, но exact второй ConnAck/re-subscribe и live probe напрямую доказывают reconnect;
  старый guard `errors>=1` был излишне строгим, это не ошибка продукта.

**M3(c.2-r3), milestone-backup и первый security smoke завершены. Пользователь выбрал следующим
этапом надёжный multi-broker MQTT, не M3(d). Design r4 сохранён в
`docs/MQTT_MULTI_BROKER_DESIGN.md`: максимум 2 sessions, реальный ConnAck, bounded 30 s/4096 exact
cross-broker dedup, global traffic budgets и сначала два локальных brokers. r4.1/r4.2 Rust и APK
`v11.16.11` install+cold-start PASS 3/3: expected PID membership, effective readiness, no false
success/crash. Следующий шаг — controlled reconnect Анны на том же PID и subscription recovery.
Второго broker/fanout пока нет; M3(d), UI/background не начинать.**
**Критично: дедуп `RelayQueue.contains(msg_id)` перед любым relay** — иначе петля/шторм
(как со старым relay). Подшаги M3 (каждый — телефонный тест):
- (a) handle `relay`-конверт → получатель я → доставить; иначе → `RelayQueue` (с дедупом) + hop/TTL;
- (b) handle `receipt` → cleanup `RelayQueue` + DELIVERED у origin;
- (c) gossip: на peer-discovered — обмен сводками `gsumm` + пересылка недоставленных;
- (d) send-path: recipient офлайн → flood `relay`-конверта онлайн-узлам.
Дальше: M4 доставка → M5 receipt-cleanup → M6 интеграция → M7 E2E → M8 персистентность →
M9 группы. Полный план: `docs/MESH_DELIVERY.md`.
⚠️ M3 — большой и рискованный (как старый relay, что устроил шторм). Лучше делать свежей,
сфокусированной сессией.

**Pending (НЕ в ветке / в очереди):**
- Фикс `.github/workflows/build-release.yml` (guard от порчи Release) — **НЕ закоммичен**:
  бот Arena не может пушить GitHub Actions файлы (`workflows` permission). Лежит готовым в
  sandbox; пользователь коммитит+пушит сам, либо ИИ даёт содержимое файла.
- Шаг **B (скорость)**: переиспользовать постоянное MQTT-соединение вместо нового на каждую
  отправку/ACK (`send_message_mqtt` создаёт новое соединение каждый раз) — умеренный рефакторинг.
- D4: дедуп CF poll по `messageId` (мелочь).
- CF Worker `/send`,`/poll` = 404 — НЕ критично (доставка идёт через телефоны/MQTT, не через
  Worker-ящик; Worker = только registry/discovery по архитектуре).

---

## 7. Журнал изменений этой памятки

- **2026-08-13** — создано. Зафиксированы: главное правило (маленькие шаги), **пользователь
  — новичок (простой язык)**, стиль общения, формат Windows-команд (`Set-Location`, `py -3`),
  правила релизов/PR, гэтчи (правки одного файла, `fallbackToDestructiveMigration`, sandbox
  без сборки, org.json в тестах, CF Worker вне репо), текущий фокус v11.16.5 (офлайн-доставка).
- **2026-08-13 (доп.)** — выяснилось: на Windows несколько клонов; путь `C:\APUMIR\android-app`
  не существует. Договорились: arena-ветки тестировать в одном каноническом клоне
  `C:\APUMIR-arena-test`, снапшоты не мутировать. Код ИИ доходит до машины только через
  commit + push в arena-ветку + `git fetch`/`checkout` пользователем.
- **2026-08-13 (доп.9)** — состояние fast-forward перенесено из `arena/019ff7c3-apumir` в
  `arena/019ffc32-apumir`. Выполнен M3.1: `RelayQueue` проброшена в `run_mqtt_transport` без
  обработки mesh-конвертов; Windows-сборка прошла успешно.
- **2026-08-13 (доп.10)** — выполнен код M3(a): приём `relay`-конверта, доставка локальному
  получателю либо сохранение для другого узла. Шторм-защита: явный `contains(msg_id)` перед
  `enqueue`, плюс TTL и hop. Receipt/gossip/send-path не тронуты.
- **2026-08-14** — M3(a) успешно собран на Windows (`build-rust.ps1`, arm64-v8a), APK
  `v11.16.6` установлен на Анну/Женю/Стаса. Ручной relay-тест прошёл: Анна и Женя сохранили
  конверт в очереди и не показали его, Стас получил одно сообщение, счётчики не росли —
  шторма нет. Наблюдение: из-за общей подписки `p2pm2/#` старый `ack|…` видят все узлы и
  эмитят локальный `DELIVERY_ACK`, но чужого сообщения/статуса в UI нет; это не шторм.
- **2026-08-14 (доп.)** — M3(b) собран и проверен на 3 телефонах: relay→ручной receipt
  очистил очереди Анны/Жени, только Анна получила origin-delivery, дубликат receipt не вызвал
  повторного cleanup, Стас получил одно сообщение. Тихий замер подтвердил отсутствие шторма.
- **2026-08-14 (доп.2)** — M3(b.1) собран и проверен на 3 телефонах: при публикации только
  relay Стас сам отправил mesh receipt, Анна/Женя автоматически очистили очереди, origin
  получил delivery. Счётчики стабильны, утечки UI/шторма нет.
- **2026-08-14 (доп.3)** — M3(c.1) собран и проверен на 3 телефонах: адресные `gsumm`
  отправляются/принимаются, cooldown 60 с подтверждён timestamps (интервалы 60–66 с), relay
  actions=0.
- **2026-08-14 (доп.4)** — добавлен M3(c.2): bounded resend отсутствующих relay с budgets
  16/256 KiB round и 32/512 KiB window, max envelope 64 KiB, fair cursor. Пользователь хочет
  три UI-режима relay: лёгкий/средний/без ограничений; сейчас backend = средний, UI позже.
- **2026-08-14 (доп.5)** — M3(c.2) relay-path проверен на `v11.16.7`: Женя по пустому
  summary переслал 1 relay/166 B, Стас доставил один раз + auto-receipt, Женя cleanup один раз.
  Обнаружены два blocker финального origin: MQTT не reconnect после Wi-Fi off/on при доступном
  TCP 1883; retained receipt перезаписывается более поздним ACK/summary на общем topic.
- **2026-08-14 (доп.6)** — M3(c.2-r1) проверен на APK `v11.16.8`: при Wi-Fi+data off
  процесс Анны не перезапустился, MQTT дал 4 bounded errors (8/16/30/30 с); после сети —
  ровно 1 reconnect+re-subscribe и 1/1 live probe при том же PID `23407`.
- **2026-08-14 (доп.7)** — пользователь требует отдельный background receive-only режим:
  периодически просыпаться и получать только свои сообщения, не хранить/не пересылать чужие.
  План: WorkManager + network constraint + own topics/signed pull + короткое bounded окно;
  real-time foreground — отдельный opt-in. Реализация только после M3(c.2-r1/r2).
- **2026-08-14 (доп.8)** — код M3(c.2-r2): unique retained receipt topic использует
  SHA-256 msg_id, safe origin segment и limits; ACK/summary остаются на base topic и не могут
  перезаписать receipt. Только локальный origin очищает exact retained topic после обработки.
  Старый receipt совместим. Windows Android Rust build успешен за 1m01s; дальше APK/test.
- **2026-08-14 (доп.9)** — записана продуктовая идея пользователя: глобальный поиск только
  по уникальному латинскому `@username`, привязанному подписанной записью к одному `pk_…`;
  display names могут совпадать. Добавлена фаза 1.7 в `MASTER_PLAN_v2.md`.
- **2026-08-14 (доп.10)** — расширены media-фазы: 5.3 включает любые files и видеокружочки;
  новая 5.4 — Stories (photo/video/text, privacy audience, signed/E2E, TTL 24 ч, reactions,
  bounded friend relay). Большие media не идут внутри MQTT text envelope.
- **2026-08-14 (доп.11)** — зафиксирован единый пользовательский бренд **APU**; APUMIR и
  P2P Messenger — только внутренние/legacy имена. В MASTER_PLAN добавлена фаза 0.3: аудит
  strings, оригинальный adaptive logo, design system и современное light/dark оформление.
- **2026-08-14 (доп.12)** — r2 unique retained receipt доказан и корректно очищен, но stale
  retained relay после reconnect породил вторые store/delivery/receipt/origin-delivery.
  Нужен отдельный r3: c2 resend non-retained + bounded recipient delivery dedup.
- **2026-08-14 (доп.13)** — регистрация должна использовать нейтральное `Имя или имя и
  фамилия`, без примера «Владимир». В фазу 2.4 добавлены безопасные opt-in remote error reports:
  redaction/preview/encryption/limits/export и project-controlled collector/issue tracker.
- **2026-08-14 (доп.14)** — код r3: c2 relay resend non-retained; bounded seen tombstones
  10k предотвращают re-enqueue после cleanup; local delivery cache 4096 подавляет duplicate UI
  и conflicting origin, но повторяет receipt для того же origin.
- **2026-08-14 (доп.15)** — пользователь требует после каждой существенно важной проверенной
  сборки автоматически предлагать milestone-backup APU на внешний носитель с полной инструкцией
  восстановления на чистом ПК. Добавлен `docs/BACKUP_AND_CLEAN_PC_RECOVERY.md`; копировать только
  после согласия, проверить Git bundle/hashes, учитывать signing secrets и шифрование носителя.
- **2026-08-14 (доп.16)** — r3 Rust/APK `v11.16.10` собраны; 3-phone delivery для
  `m3c2r3-1786702254585`: store Анна/Женя=1, Стас delivery=1 + duplicate suppressed=1 +
  receipts=2, cleanup Анна/Женя=1, origin-delivery только Анна=1, seen tombstone сработал.
- **2026-08-14 (доп.17)** — independent fresh subscription exact base-topic Стаса получила
  retained `gsumm`, но `retainedRelayCount=0`; PIDs 12571/27336/2529 не изменились. Остался
  финальный reconnect Анны без повторной store/delivery/origin-delivery.
- **2026-08-14 (доп.18)** — добавлен периодический defensive security plan: spam/DoS/replay,
  malformed/fuzz, Sybil/identity, Android/background, media/storage, diagnostics/privacy и
  supply-chain. High load только локально; важная APK получает short 3-phone smoke + recovery
  control message. См. `docs/SECURITY_RESILIENCE_TEST_PLAN.md` и MASTER_PLAN фазы 0.4/8.4.
- **2026-08-14 (доп.19)** — r3 reconnect финально пройден: Wi-Fi+data Анны off/on, PID
  12571/27336/2529 сохранились, re-subscribe=1, live probe=1; повторные store/remove/origin,
  recipient delivery/receipt/UI и relay payload все 0. Отсутствие строки `MQTT error` не делает
  тест неполным: второй ConnAck/re-subscribe и probe доказаны. `v11.16.10` — важная точка.
- **2026-08-14 (доп.20)** — пользователь требует записывать все ошибки/обходы для новой ИИ-
  сессии. Milestone backup на незашифрованный FAT32 `F:` создан (22 files, 50,920,869 bytes,
  21 hash entries). Гэтчи: отдельный `else` в интерактивном PowerShell 5; `java -version`
  stderr + ErrorAction Stop; безопасное resume по `INCOMPLETE.tmp`.
- **2026-08-14 (доп.21)** — independent verify: 21 hashes, known APK/native hashes, bundle,
  offline clone/reset на docs commit и Git connectivity прошли. Raw source-ZIP blob check дал
  ложный fail из-за CRLF; fresh archive SHA-256 совпал с USB и filter-aware `hash-object --path`
  совпал с commit для 4/4 файлов. `dangling commit` при fsck — stash refs и exit=0.
- **2026-08-14 (доп.22)** — milestone-backup `v11.16.10` на незашифрованном FAT32 `F:`
  ПОЛНОСТЬЮ ПРОВЕРЕН: `F:\APU-backups\APU-v11.16.10-2026-08-14-e055471`, 24 files,
  50,958,011 bytes, 23 manifest entries; final manifest SHA-256
  `8FD9428BDF6B938FFA4DF25752F64ED67FD21FB092299F9B6C31983F38C6AF49`. Bundle/offline
  restore/source ZIP/artifacts/hashes passed. Носитель содержит signing material без шифрования:
  хранить физически безопасно и не публиковать.
- **2026-08-14 (доп.23)** — `F:` успешно безопасно извлечён через Shell `Eject`; проверка
  `Test-Path F:\` дала false, пользователь физически вынул флешку. Milestone-copy завершена.
- **2026-08-14 (доп.24)** — выбран первый low-volume security smoke. Старый-cache baseline
  НЕ готов: Анна PID 12571/100%/31°C/118238 KiB, но процесс Жени уже остановлен; `throw` прервал
  только текущий интерактивный foreach, а последующие отдельно выполненные команды ошибочно
  записали partial state, очистили logcat и напечатали READY. Не использовать этот state/PASS.
  Перезапустить Женю и создать новый security msg_id в одном atomic `& { ... }` scriptblock.
- **2026-08-14 (доп.25)** — atomic baseline security smoke прошёл; Женя cold-start и MQTT
  subscription confirmed. Новый ID `sec-origin-1786707178388`. PID Анна/Женя/Стас =
  `12571/22100/2529`; battery = `100/100/57%`; temperature = `30/28.8/30°C`; PSS =
  `118986/88012/53898 KiB`. State имеет `baselineComplete=true` и все 3 телефона; logcat очищен.
- **2026-08-14 (доп.26)** — identity preflight взял origin/recipient из проверенного original r3
  state, а не доверился ручному вводу: `pk_591a…4ecc` / `pk_7dc6…bd0c`, оба совпали. State имеет
  `identityValidated=true`. Один и тот же local-only validation block случайно выполнен дважды;
  он идемпотентен, сеть/logcat не затрагивал.
- **2026-08-14 (доп.27)** — первая normal-setup попытка НЕ отправила relay: PowerShell создал
  marker, но вызвал Python без обязательного state-path; `IndexError: sys.argv[1]` возник до MQTT
  client/connect/publish. Atomic recovery доказал test-ID log count `0/0/0`, PID остались
  `12571/22100/2529`, записал no-network outcome, очистил logs и разрешил ровно один controlled
  retry. Исправленный вызов обязан быть `py -3 $PythonPath $SecurityStatePath`.
- **2026-08-14 (доп.28)** — Arena sandbox внезапно пере-клонировался к `991da05` перед docs
  commit; рабочие файлы сохранились, но commit получил неверного base и push был rejected.
  Remote `84e5104` не пострадал. После fetch filter-aware hash всех remote-tracked файлов доказал:
  отличаются только 2 новых docs-правки, missing=0. Они сохранены, branch возвращён на remote,
  правки повторно закоммичены как fast-forward `1b15270`; remote sync и clean tree проверены.
- **2026-08-14 (доп.29)** — corrected normal relay действительно опубликован ровно один раз:
  ID `sec-origin-1786707178388`, QoS1, retain=false, 160 bytes; все PID до publish стабильны.
  Через 15 с Анна/Женя сохранили snapshots и имели по 3 references. Snapshot Стаса не создался,
  потому что filtered `adb logcat` дал пустой stdout и pipeline `Set-Content` не был вызван;
  publish уже confirmed, повтор запрещён.
- **2026-08-14 (доп.30)** — full-log diagnostic: PID всех трёх стабильны, Женя принял original
  relay один раз и stored=1; Стас test ID/input/delivery/receipt/UI=`0/0/0/0/0`, а log показал
  `MQTT error: Network timeout; retrying in 16s`. Поэтому setup incomplete, conflicting-origin
  запрещён. Текущий full log Анны уже вытеснил ранние relay строки (остались queue summaries),
  поэтому её immediate saved snapshot нужно читать отдельно; original не публиковать повторно.
- **2026-08-14 (доп.31)** — saved evidence подтвердил store Анна/Женя=1/1; late diagnostic при
  airplane=0 и VALIDATED network у всех не нашёл у Стаса ни reconnect/re-subscribe, ни relay/UI.
  Первый security ID окончательно непригоден/abandoned. Это availability/reconnect наблюдение,
  не crash и не доказанный dedup defect; старый relay не переиздавать.
- **2026-08-14 (доп.32)** — controlled force-stop/cold-start всех трёх очистил RAM state. Анна
  PID `14734`, Женя `24000`: subscription=1, ConnAck=1, MQTT errors=0. На Стасе cold start прошёл,
  но 20-секундный gate не дождался ConnAck; block остановился до state/new ID/log clear.
- **2026-08-14 (доп.33)** — Стас PID `13746` поздно получил initial ConnAck после 5 MQTT errors
  и затем стабильно видел presence Анны/Жени; это late initial connection, не re-subscribe.
  Bounded TCP probe: `broker.hivemq.com:1883` exit=1, `broker.emqx.io:1883` exit=0. Значит,
  fallback-дефект практически значим: доступный второй broker не выбирается, пока first retry
  когда-нибудь не сработает.
- **2026-08-14 (доп.34)** — fresh-state adoption прошёл Анну/Женю, но сначала остановился на
  вытесненной ConnAck-строке Стаса. Safe equivalent gate: текущий PID + startup identity/
  subscription + live peer traffic ПОСЛЕ последнего MQTT error.
- **2026-08-14 (доп.35)** — generation-2 baseline READY без relay: старый ID abandoned;
  новый `sec-origin2-1786710017189`, TTL 3600, logs cleared. PID Анна/Женя/Стас =
  `14734/24000/13746`; live-peer-after-error=true у всех; battery `100/100/58%`, temperature
  `31/28.7/30°C`, PSS `117764/98527/142225 KiB`.
- **2026-08-14 (доп.36)** — после immediate live gate `8/16/28` peer lines generation-2 normal
  relay опубликован ровно один раз: QoS1, retain=false, 183 bytes, TTL 3600. PID неизменны;
  immediate snapshots сохранены, test-ID refs Анна/Женя/Стас=`10/7/10`. Не повторять.
- **2026-08-14 (доп.37)** — первый exact-analysis блок вообще не выполнился: PowerShell parser
  отверг не заключённые в скобки вызовы `Count-Literal ... +`. Остаток интерактивного paste дал
  пустые метрики, отдельный `else` и ложный текст INCOMPLETE. MQTT/state/snapshots не изменены.
- **2026-08-14 (доп.38)** — compile-first Python helper точно посчитал setup, но raw Unicode
  log line не кодировалась в cp1251 и helper упал до save/clear. Attack не выполнялся.
- **2026-08-14 (доп.39)** — ASCII-only finalizer повторно доказал и сохранил generation-2
  normal setup PASS: Анна relay/store/cleanup/origin=`1/1/1/1`, Женя=`1/1/1/0`, Стас
  relay/local/receipt/UI=`1/1/1/1`; duplicate=0, PID stable, errors/crash=0. Recipient origin
  binding установлен, attack logs очищены и `attackLogBaselineReady=true`.
- **2026-08-14 (доп.40)** — после live gate peer lines `2/9/16` опубликован ровно один
  conflicting-origin relay того же ID: attacker origin, QoS1, retain=false, 179 B. PID остались
  `14734/24000/13746`; attack snapshots готовы, test-ID refs=`2/2/2`. Не повторять.
- **2026-08-14 (доп.41)** — attack semantics полностью ожидаемые: Анна/Женя forged input=1 и
  previously-seen=1, Стас forged input=1/conflict-drop=1; на всех store/local/receipt/cleanup/
  origin/UI/errors/crash=0. Resources safe (max 35°C). Analyzer поставил incomplete только потому,
  что поздний `pidof` Жени вернул `8350 24000`, хотя expected `24000` жив.
- **2026-08-14 (доп.42)** — process diagnostic позже дал только PID `24000`; `ps`/cmdline/UID
  подтверждают единственный основной `com.vladimir.messenger`, 52 threads, sleeping, MQTT live,
  errors=0. PID `8350` был transient/already exited и не содержал attack snapshot.
- **2026-08-14 (доп.43)** — membership-aware finalizer сохранил conflicting-origin PASS:
  expected PID present на 3/3; Анна/Женя forged-input+previously-seen=`1+1`; Стас forged-input+
  conflict-drop=`1+1`; все receipt/store/local/UI/cleanup/origin/errors/crash=`0`. Post-attack:
  max 35°C, PSS delta `-3043/-15721/+9613 KiB`, battery delta `0/0/+1`. Logs очищены.
- **2026-08-14 (доп.44)** — control ID создан и pre-live gate `6/15/15` прошёл, но PC Paho не
  подключился к HiveMQ за 10 s и упал ДО `client.publish`; control не отправлен. Recovery доказал
  control ID refs=`0/0/0`, confirmed=false и TCP HiveMQ:1883 reachable=true. State сохранил
  no-network-publish и разрешил ровно один retry того же ID; attack остаётся завершённым.
- **2026-08-14 (доп.45)** — единственный authorized retry того же ранее unpublished control ID
  `sec-control-1786712273127` подтверждён QoS1/non-retained, 176 B. Pre-gate PID membership 3/3,
  peer lines `3/12/29`, ID refs=0; через 15 s snapshots готовы с refs `10/7/10`. Не повторять.
- **2026-08-14 (доп.46)** — local analyzer сохранил окончательный normal-control PASS. Анна:
  input/store/receipt-input/remove/origin=`1/1/1/1/1`; Женя input/store/receipt-input/remove=
  `1/1/1/1`; Стас input/local/receipt-input/receipt-sent/UI=`1/1/1/1/1`. Все unexpected store/
  local/origin/UI, duplicate, previously-seen, conflict, MQTT error, crash/ANR=`0`; snapshots
  `10/7/10`, expected PID membership 3/3. Post-control battery `100/100/60%`, temperature
  `38/29.6/31°C`, PSS `117511/90608/126208 KiB`, CPU `0.7/0.5/0.7%`, threads `59/52/66`.
  Control recovery и весь первый low-volume security smoke завершены; logs оставлены, state PASS.
- **2026-08-14 (доп.47)** — пользователь уточнил: флешку не подключать для мелких checkpoints;
  backup предлагать только на крупных новых APK/release/этапах, а не после docs или повторного
  smoke той же сборки. Попытка исправить initial fallback через bounded ConnAck и переход к
  следующему broker остановлена code review ДО Windows build: public HiveMQ/EMQX/Mosquitto не
  bridge topics, поэтому разные выбранные brokers разделят APU-узлы и сообщения не дойдут.
  Код возвращён к проверенному r3; commit `8544b6e` superseded следующим revert-коммитом. Нужен
  design: parallel/overlapping broker subscriptions + bounded publish/dedup либо coordinated
  primary migration; затем local tests. Sandbox не имеет `cargo`/`rustfmt`.
- **2026-08-14 (доп.48)** — пользователь выбрал multi-broker reliability перед M3(d). Принят
  staged design `docs/MQTT_MULTI_BROKER_DESIGN.md`: две concurrent sessions, ConnAck-ready gate,
  subscriptions/reconnect state per broker, publish fanout max 2, exact SHA-256 topic+payload
  duplicate window 30 s/cap 4096, global (не per-broker) mesh budgets, retained cleanup на всех
  published sessions и deterministic failure tests сначала на двух local Mosquitto brokers.
- **2026-08-14 (доп.49)** — r4.1 code добавил изолированный `network/mqtt_dedup.rs`: хранит
  только SHA-256(topic+separator+payload), window 30 s, cap 4096, expiry и deterministic oldest
  eviction. Четыре unit cases покрывают duplicate/different/expiry/cap. Модуль объявлен, но не
  подключён к MQTT receive path. Windows Android Rust release build PASS за 1m02s; ожидаемые
  dead-code warnings подтверждают изоляцию helper, host `cargo test` не запускался.
- **2026-08-14 (доп.50)** — r4.2 code убирает ложные startup markers без смены broker: создаёт
  только primary HiveMQ session, запускает непрерывный EventLoop и ждёт первый настоящий ConnAck
  через oneshot. Только после него queue-ятся wildcard subscription, retained-presence clear и
  current presence; reconnect по-прежнему делает subscription request при clean session. Логи
  различают initialized/ConnAck/subscription-request, не называют enqueue SubAck. Windows Android
  Rust release build PASS за 1m01s, 9 warnings без errors; EMQX, fanout и r4.1 filter production
  path пока не подключены.
- **2026-08-14 (доп.51)** — release APK build фактически PASS за 32 s (52 tasks), metadata
  versionCode=`11016011`; harness затем дал ложный fail, потому что ожидал versionName `11.16.11`,
  а канонический `$env:GITHUB_REF_NAME` сохраняется как `v11.16.11`. Ошибка произошла ПОСЛЕ
  BUILD SUCCESSFUL и создания APK, поэтому rebuild не требовался. Nonfatal warnings: strip `.so`,
  Kapt 2.0→1.9, processor options и Gradle deprecations.
- **2026-08-14 (доп.52)** — existing APK artifact verify PASS (read-only block был безопасно
  повторён дважды): 22,550,028 B, SHA-256 `DDC836A142D899B0A70EA805313B416744A5C68DA8562E7CF93F9D5605003A12`;
  source/embedded native оба `B0905083B734886B46BA2EB1B7AE9CBC76E77FAABC6C3359CD091352EE105C65`;
  apksigner exit=0. Regex не распарсил certificate (`NOT_PARSED`), поэтому до install нужно
  read-only сравнить signer SHA-256 нового APK и установленной v11.16.10, не полагаться лишь на exit.
- **2026-08-14 (доп.53)** — signer preflight подтвердил все 3 телефона online на v11.16.10,
  затем остановился ДО pull/install: PowerShell `-f` применился только к второй части строки после
  `+`, и `cmd` получил literal `"{0}"`. Это harness-only fail, APK/phones/data не изменены.
  Workaround: не сочетать `+` и `-f` без скобок; для apksigner вызвать `.bat` напрямую, временно
  `ErrorActionPreference=Continue`, сохранить exit/output и парсить SHA-256 из полного текста.
- **2026-08-14 (доп.54)** — corrected direct apksigner compare PASS: новый v11.16.11 и
  установленный на Анне v11.16.10 имеют один V2 certificate SHA-256
  `F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7`. Pulled base.apk удалён,
  APK ещё не установлен, user data не менялись; evidence разрешил только `adb install -r`.
- **2026-08-14 (доп.55)** — первый install helper остановился на Анне во время read-only
  pre-state, ДО создания install state и ДО любого `adb install`: strict parser ожидал `userId`,
  а все телефоны дают `appId`. Diagnostic подтвердил v11.16.10 на 3/3, install state absent,
  appId и independent `cmd package -U` UID совпали: Анна `10425`, Женя `10395`, Стас `10387`;
  firstInstallTime уникально присутствует `1/1/1`.
- **2026-08-14 (доп.56)** — corrected `adb install -r` v11.16.11 PASS на Анне/Жене/Стасе.
  На 3/3 version=`v11.16.11/11016011`; appId, independent UID и firstInstallTime совпали до/после,
  значит uninstall/data reset не было. Install state/evidence complete PASS.
- **2026-08-14 (доп.57)** — controlled force-stop/log-clear/launch выполнился на 3/3 и прошёл
  35 s wait, но analyzer упал ДО первого log read/snapshot/state: PowerShell variables
  case-insensitive, поэтому `$Pid` попытался перезаписать read-only automatic `$PID`. Продукт не
  падал, message не публиковался; read-only recovery затем сохранил snapshots/state.
- **2026-08-14 (доп.58)** — delayed recovery: PIDs Анна/Женя/Стас=`30085/15597/26923`, single
  process 3/3, crash=0, old false connected/subscribed=0. Стас сохранил direct r4.2 markers
  init/session/loop=`1/1/1`, subscription/ready/ack=`1/1/1`, MQTT errors=1 и live peers=24.
  У Анны/Жени early markers вытеснены, но current-process live peers=`7/7`, errors=0.
- **2026-08-14 (доп.59)** — semantic finalizer сохранил cold-start PASS: direct markers 1/3
  (Стас), effective readiness 3/3 через code-valid live-peer-after-error equivalent у Анны/Жени;
  expected PID membership=true, false-success gate=true, crash=0. Phones не restart, logs не
  очищены, test message не публиковался. Дальше controlled reconnect Анны на PID `30085`.
- **2026-08-14 (доп.60)** — controlled reconnect Анны PASS на сохранённом PID `30085`: Wi-Fi и
  mobile data восстановлены `1/1`, MQTT errors=0, новый truthful reconnect subscription marker
  ровно 1, live peer lines=4 и peer-after-reconnect=true. Initial/false/old markers=`0/0/0/0`,
  crash/ANR=0. Evidence сохранён в `%TEMP%\apu-r4.2-v11.16.11-reconnect-Anna.json` и
  `%TEMP%\apu-r4.2-reconnect-Anna.log`; network interruption, restart и log clear не повторять.
  Test message не публиковался. Следующий отдельный шаг — спроектировать один безопасный
  low-volume delivery check этой APK без перехода к r4.3, UI/background или M3(d).
- **2026-08-14 (доп.61)** — первая delivery-preflight попытка остановилась до readiness window,
  envelope и Python publisher: ожидаемый cold-start PID Жени `15597` уже сменился на `2513`.
  One-shot state `%TEMP%\apu-r4.2-v11.16.11-delivery.json` сохранён и не удаляется; read-only
  проверка (SHA-256 `8E528E76BEC261BE79BDFD0DBFA020ACF6DEE824C5CF52D5644B7758698465F9`)
  доказала `topic/envelope` пустые, `publisherStarted` пустой, `publishCalled=false` и
  `publishConfirmed=false`. PID Анна/Женя/Стас=`30085/2513/26923` затем были стабильны 15 с,
  версия 3/3=`v11.16.11/11016011`. Это не delivery FAIL и не разрешает повтор старого блока.
  При paste также потерялись первые кириллические буквы в двух внутренних labels; corrected
  one-shot harness `scripts/r42_delivery2_check.ps1` использует только ASCII labels, отдельные
  `delivery2` state/snapshots и динамически фиксирует текущий single PID каждого телефона перед
  readiness gate (не вшивает уже устаревший PID). Сначала он проверяет старое no-publish evidence,
  а при любом неполном исходе не повторяет публикацию автоматически.
- **2026-08-14 (доп.62)** — delivery2 также безопасно остановлен ДО envelope/publisher: за 45 с
  Анна не дала fresh peer marker. State SHA-256
  `39ED438B7EF19910D41700C01965466B1BEFAA8B63C06DBB9DB45DE0936099EC` сохранил пустые
  topic/envelope и `publishCalled/publishConfirmed=false`. Первый read-only analyzer упал на
  PowerShell/.NET typed-list binding после записи только Anna diagnostic; телефоны не менял.
  Отдельный Python diagnostic2 завершён: PID membership Анна/Женя/Стас=`30085/20292/26923`,
  airplane=`0/0/0`, Wi-Fi=`1/1/1`, mobile data=`1/0/1`, VALIDATED lines=`21/20/21`, crash=0.
  При этом после baseline на всех 3: MQTT input/presence/peer/error/ConnAck/subscription=`0`.
  Это не delivery FAIL (ни одного test publish не было), а silent MQTT liveness blocker.
  Source review нашёл правдоподобный mutual-backpressure риск: EventLoop ждёт bounded
  `event_tx.send().await`, core может ждать bounded `AsyncClient.publish().await`; task completion
  не проверяется, periodic presence errors игнорируются. Причина ещё не доказана runtime stack,
  но перед r4.3 обязателен r4.2-r1 observable-liveness/backpressure fix и local stall tests.
- **2026-08-14 (доп.63)** — r4.2-r1a source добавил чистый payload-free
  `network/mqtt_liveness.rs`: фазы EventLoop, monotonic phase/progress age и fixed-memory atomic
  counters; 5 deterministic tests для healthy/stalled boundary/stopped/counters/saturation.
  `MqttTransport` запускает независимый watchdog (15 с), stall threshold 90 с, warning repeat
  60 с, heartbeat 120 с; completion oneshot сообщает normal task exit или missing signal, а
  `shutdown_requested` исключает ложный panic-marker при штатном Drop. EventLoop помечает poll,
  publish input, ConnAck, forwarding и backoff. Initial/periodic presence, retained clear и relay
  registration больше не скрывают enqueue errors и не называют queued request broker success.
  Brokers/QoS/retain/channel caps/backoff/reconnect не изменены; timeout/drop/recovery — r1b.
  Sandbox без cargo/rustfmt, поэтому host tests не запускались; следующий gate — только Windows
  `build-rust.ps1`, затем отдельный idle/stall log test новой APK без public user publish.
- **2026-08-14 (доп.64)** — r4.2-r1a Windows Android Rust compile PASS для source `a357520`:
  `build-rust.ps1` arm64-v8a завершён за 68.45 с, exit=0, Cargo `Finished release`, compiler
  warnings=9, errors=0. Harness count=10 включает отдельную итоговую строку `generated 9 warnings`.
  Новая `libp2p_core.so`: 7,148,552 B, SHA-256
  `BC35DE5C760111B20312E189BFF4C7AC99010B4F1CB512E88652EDDD0AA53995`; Git modified только
  arm64 `.so`, её не commit. Предыдущая проверенная v11.16.11 `.so` B090… сохранена отдельным
  новым stash; старые stashes не pop. Строка PowerShell `NativeCommandError` была только stderr
  progress Cargo при фактическом exit=0. Build state/log: `%TEMP%\apu-r4.2-r1a-rust-build.json`
  и `%TEMP%\apu-r4.2-r1a-rust-build.log`. APK/phones не менялись; следующий код — r1b.
- **2026-08-14 (доп.65)** — r4.2-r1b1 source добавил чистый `mqtt_backpressure.rs`: generic
  bounded await максимум 5 с, отдельные timeout/client errors и 4 deterministic tests без сети.
  Все 8 publish операций persistent `MqttTransport` (presence/clear/receipt/receipt-clear/relay/
  message/gossip/registration) и initial+reconnect subscribe переведены на wrapper. Timeout/error
  увеличивают fixed-memory liveness counters и пишут `MQTT REQUEST TIMEOUT/ERROR` без topic/payload;
  hidden retry нет. QoS/retain/topics, AsyncClient cap 100, event cap 256 и backoff 30 с прежние.
  Это должно разорвать вероятный mutual deadlock: core через 5 с перестаёт ждать outgoing channel
  и снова освобождает EventLoop→core queue. Но forwarding `.send().await`, другие core stalls,
  task restart и transient `send_message_mqtt` пока не исправлены — r1b2.
- **2026-08-14 (доп.66)** — r4.2-r1b1 Windows Android Rust compile PASS для source `a360833`:
  61.53 с, exit=0, Cargo `Finished release`, compiler warnings=9, errors=0. Новая arm64
  `libp2p_core.so`: 7,155,352 B, SHA-256
  `A1FE612E339D35CD834234832490B252CFC8AB0C120C843AE876EB6D9CBFD73D`; Git modified только
  generated `.so`, не commit. r1a `.so` BC35… сохранена новым stash, старые stashes не pop.
  State/log: `%TEMP%\apu-r4.2-r1b1-rust-build.json` и `%TEMP%\apu-r4.2-r1b1-rust-build.log`.
  PowerShell `NativeCommandError` снова был только stderr progress при exit=0. APK/phones не
  менялись. Следующий код r1b2: closed channel outcome + bounded whole-session restart.
- **2026-08-14 (доп.67)** — r4.2-r1b2 source добавил pure restart decision для closed core
  notification channel, finished EventLoop task, stalled phase и stopped probe; liveness tests
  теперь 11, включая стабильные reason labels и backoff `1→2→4→8→16→30→30`. Transport ставит
  one-time channel-closed marker и проверяет task handle/probe. Core ready helper ждёт настоящий
  ConnAck+subscription request максимум 45 с. Initial failure и terminal/stalled session создают
  fresh single-HiveMQ transport с bounded backoff; old transport Drop раньше new, generation растёт
  только после success. Полученный event не выбрасывается: restart проверяется только при None.
  RelayQueue, seen/local-delivery tombstones, known peers и global budgets живут вне replacement.
  После recovery повторяются subscription/presence/relay-registration requests. EMQX/dual publish/
  production cross-broker dedup и transient sender не подключены.
- **2026-08-14 (доп.68)** — r4.2-r1b2 Windows Android Rust compile PASS source `f6130f6`:
  61.65 с, exit=0, Cargo `Finished release`, compiler warnings=9, errors=0. Новая arm64 `.so`
  7,167,112 B, SHA-256 `7C9537E6FA5F93B4F6AC28B2705F97A35E738C36309922A029D06E0886517194`;
  Git modified только generated `.so`, не commit. r1b1 `.so` A1FE… сохранена новым stash;
  старые stashes не pop. State/log: `%TEMP%\apu-r4.2-r1b2-rust-build.json` и
  `%TEMP%\apu-r4.2-r1b2-rust-build.log`. PowerShell `NativeCommandError` — только stderr progress
  при exit=0. APK/phones ещё не менялись. Следующий artifact gate — test APK v11.16.12 из exact
  application source `f6130f6` с embedded native hash 7C95…; install только после artifact verify.
- **2026-08-14 (доп.69)** — первая v11.16.12 APK-build попытка остановилась **до**
  `assembleRelease`: уже `gradlew --stop` запустил JBR 21.0.8 и JVM упала в `jimage.dll` с
  `EXCEPTION_IN_PAGE_ERROR (0xc0000006)`, создав `android-app/hs_err_pid8788.log`. Harness сохранил
  `gradleStopExitCode=1`, `gradleBuildExitCode=-1`, без `BUILD SUCCESSFUL`; stable TEMP APK не
  создавалась, install/phones не затрагивались. Это известный Windows Defender/JVM transient, не
  compile defect APU. Старый one-shot state/log не удалять и блок не повторять. Перед одним
  controlled retry сохранить `hs_err` в TEMP с hash, вернуть worktree к одной generated `.so`,
  использовать новый build2 state/log и не вызывать аварийный `gradlew --stop` повторно.
- **2026-08-14 (доп.70)** — controlled APK build2 снова упал через ~11 с в том же JBR
  `jimage.dll`/`0xc0000006`, до Android tasks/готовой APK; новый `hs_err_pid7760.log`, phones не
  менялись, дальнейшие build retry запрещены до environment fix. Первый read-only environment
  analyzer сам остановился до диагностики: PowerShell `Test-Path` разрешил relative path от
  `C:\APUMIR-arena-test`, но .NET `[IO.File]::ReadAllText` разрешил его от process cwd
  `C:\Users\User`. Ничего не менялось, diagnostic JSON не создан. Workaround: каждый path для
  .NET IO сначала превращать в absolute через `(Resolve-Path -LiteralPath ...).Path`.
- **2026-08-14 (доп.71)** — corrected jimage diagnostic подтвердил build2 exit=1/no APK и
  exact loaded DLL `D:\Android Studio\jbr\bin\jimage.dll` (JBR 21.0.8). Файл 33,432 B,
  SHA-256 `92910BA8868890D814107FDFBB2605C99E86C7CB7F7B3EFEE1B8F6262A043C20`, три чтения
  стабильны, Authenticode Valid. `JAVA_HOME=D:\Android Studio\jbr`; одновременно на `C:` есть
  signed Adoptium JDK 17.0.17. Defender available, но real-time=false; project/.gradle/.cargo
  exclusions=true, JBR exclusion=false. PhysicalDisk API показывает оба диска Healthy/OK, но
  System log за 2 часа содержит одно disk event — нужно отдельно вывести ID/provider и mapping
  C:/D: до нового Java process. Дальше предпочтителен process-local JDK17 override на `C:`, без
  изменения system JAVA_HOME и без build, затем только один guarded `gradlew --version` preflight.
- **2026-08-14 (доп.72)** — disk mapping доказал environment root cause: `C:` = Netac SSD
  Healthy/Online, volume Healthy; `D:` = HDD ST1000DM010, physical API Healthy/Online, но volume
  Health=`Warning`. System журнал содержит NTFS Error Event ID 55 в 15:57:45Z: повреждена
  структура файловой системы тома D, нужна online check. Оба JBR crash читали
  `D:\Android Studio\jbr\bin\jimage.dll`. Пользователь выбрал APU работать только с C.
  Не запускать Java/Gradle/JBR с D и не делать chkdsk repair до отдельного согласования/backup
  важных данных. Для сборки использовать только process-local
  `C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot`, `--no-daemon`, с восстановлением
  env после команды; system JAVA_HOME не менять. Сначала guarded `java -version` +
  `gradlew --version`, без assemble, и проверка отсутствия нового hs_err.
- **2026-08-14 (доп.73)** — первый C-drive JDK17 preflight остановился **до Java/Gradle**:
  он успешно проверил signed/stable JDK17 `jimage.dll`, скопировал `hs_err_pid7760.log` в
  `%TEMP%\apu-v11.16.12-hs_err_pid7760.log` с hash-check и удалил только исходную уже сохранённую
  копию из worktree. Затем strict `hs_err_pid*.log count == 0` обнаружил другой исторический
  crash-report и остановил блок до создания preflight state/log. Нельзя удалять неизвестный файл
  или повторять блок. Workaround: сначала read-only inventory exact path/size/SHA/time всех repo и
  TEMP hs_err, отделить известные 8788/7760 от historical, затем архивировать каждый отдельно.
  Новый Java preflight сравнивает baseline set с post-run set, а не требует абсолютный zero.
- **2026-08-14 (доп.74)** — hs_err inventory нашёл 9 historical reports в `android-app/` от
  2026-08-12 (до r1b2), каждый с отдельным SHA-256; Git их игнорирует, status содержит только
  generated arm64 `.so`. Новые 8788/7760 уже отдельно сохранены в TEMP с hashes
  `FD9EE6A3…F6DEE5F` / `8BD399F5…17F4BEC`; repo originals удалены после verified copies.
  Historical reports не удалять и не архивировать повторно. Correct JDK17 preflight записывает
  sorted baseline `full path|size|SHA`, запускает только `java -version` и
  `gradlew --no-daemon --version` с process-local C-drive JDK17, затем требует exact same set/hash.
  Любой new/changed hs_err = STOP; system JAVA_HOME после блока обязательно восстановить.
- **2026-08-14 (доп.75)** — C-drive JDK17 preflight2 PASS: signed Adoptium
  `C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot`, jimage SHA-256
  `F01C057DA0222BC70A7F904C3104DEDFC6F268446EE797C6DD70F9651A52265F`; `java -version` и
  `gradlew --no-daemon -Dorg.gradle.java.home=<C JDK17> --version` exit=0 и Gradle подтвердил
  JVM 17.0.17. Baseline/final hs_err=`9/9`, set/hash unchanged, new crash=0; Git только generated
  `.so`; JAVA_HOME/GRADLE_JAVA_HOME/PATH восстановлены. State/log:
  `%TEMP%\apu-v11.16.12-jdk17-preflight2.json/.log`. Следующий единственный build3 использует
  process-local C JDK17 + `--no-daemon`, без `gradlew --stop`; до task обязан проверить, что
  Android SDK/NDK path тоже не на D. Любой new hs_err или D JBR reference = STOP.
- **2026-08-14 (доп.76)** — C-drive APK build3 PASS: process-local signed Adoptium JDK17 и
  Android SDK `C:\Users\User\AppData\Local\Android\Sdk`, `--no-daemon`, без `gradlew --stop` и
  без D JBR. Gradle 52 tasks, `BUILD SUCCESSFUL` за 41.74 с; historical hs_err set 9/9 unchanged,
  new crash=0, env restored. Test APK v11.16.12/11016012 из exact application source `f6130f6`:
  22,582,796 B, SHA-256 `6765FC7A0ACF10CD9909BC265E729EC1E0BAE47D1314376585CC2123649A3F55`.
  Embedded arm64 `.so` 7,167,112 B, SHA-256 `7C9537E6FA5F93B4F6AC28B2705F97A35E738C36309922A029D06E0886517194`,
  exact source match. Saved APK/state/log: `%TEMP%\apu-r4.2-r1b2-v11.16.12-build3.apk`,
  `...apk-build3.json/.log`. APK ещё не установлена. Следующий gate: apksigner V2/cert SHA-256
  compare с установленной v11.16.11 + devices/version/current PID preflight; install только PASS.
- **2026-08-14 (доп.77)** — первый v11.16.12 signer/device preflight остановился до state,
  apksigner и APK pull: `adb shell pidof` Жени вернул exit=1/empty, потому что процесс APU не
  запущен; generic ADB wrapper ошибочно объявил это ADB failure. Package/version уже читались,
  install/data/phones не менялись, Java env не переключался. Для signer/install preflight процесс
  не обязателен: corrected wrapper принимает только exact `pidof exit=1 + empty` как
  `processRunning=false/processIds=[]`; exit=0 требует PID tokens, другие exit/output = STOP.
  После install нужен отдельный controlled launch и новые dynamic PID/liveness gates; старые PID
  не использовать как обязательные.
- **2026-08-14 (доп.78)** — v11.16.12 signer/device preflight2 PASS: APK 22,582,796 B/hash
  `6765FC7A…649A3F55`; new и установленная v11.16.11 имеют V2=true и один certificate SHA-256
  `F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7`. Anna base.apk была
  read-only pulled, hashed и удалена; Java env restored, hs_err set unchanged, install/data не
  менялись. Все 3 adb devices/version v11.16.11; preinstall process: Anna PID 30085, Zhenya
  stopped, Stas PID 26923. Stopped process допустим до install. Следующий gate — один atomic
  `adb install -r` на 3 телефона с appId + independent UID + firstInstallTime preservation;
  при partial fail не rollback/uninstall/retry, после install приложение пока не launch.
- **2026-08-14 (доп.79)** — v11.16.12 data-preserving `adb install -r` PASS 3/3. Version
  v11.16.11/11016011→v11.16.12/11016012; appId/independent UID preserved: Anna 10425,
  Zhenya 10395, Stas 10387; firstInstallTime и dataDir preserved 3/3. Uninstall/data clear/
  force-stop/launch не выполнялись; после install processRunning=false 3/3. State:
  `%TEMP%\apu-r4.2-r1b2-v11.16.12-install.json`. Следующий gate — controlled launch без
  force-stop: precheck stopped/version, log clear, launch 3/3, 150 с wait для real ConnAck,
  peer traffic и первого 120-секундного `MQTT LIVENESS HEARTBEAT`; no user test publish.
- **2026-08-14 (доп.80)** — v11.16.12 controlled launch реально выполнился 3/3 и ждал 150 с,
  processes/crash gate не упал, но analyzer дал ложные native counts=0: snapshots фильтровали
  `RustBridge:V`, тогда как `rust-core/src/logging/logger.rs` создаёт tracing-android layer с
  exact tag **`p2p_core`**. `RustBridge` — только Kotlin bridge tag. Это harness-only false
  incomplete, не MQTT FAIL; no user publish/network toggle. State и старые snapshots не удалять,
  launch/log clear не повторять. Recovery только read-only current logcat с `p2p_core:V`, exact
  original PID membership и отдельными diagnostic snapshots/state. Все будущие native harnesses
  обязаны включать `p2p_core:V`; Kotlin/UI дополнительно `RustBridge`/`CoreServerService`.
- **2026-08-14 (доп.81)** — первый native-recovery block остановился на non-destructive ADB
  preflight: Anna `RFCWC16RDYL` отсутствовала в `adb devices` как exact `device`. Остановка была
  до loop capture/state writes: `%TEMP%\apu-r4.2-r1b2-v11.16.12-native-recovery.json` и три
  `...-native-recovery-<Name>.log` не созданы, logcat/app/network не менялись, capture budget не
  израсходован. Сначала только read-only `adb devices -l`; capture разрешён лишь после exact 3/3.
  Repo hygiene audit: correct branch clean, 222 tracked files, no untracked/ignored files и no
  merge markers. Historical tracked baggage есть: phone logs (~13 MB), 9 JVM crash logs + replay,
  Kotlin daemon/build logs, unused UTF-16 `temp_core.txt`, duplicated legacy native libraries.
  Сейчас не удалять: cleanup отложен. Важный release blocker: tracked Android arm64 `.so`
  SHA-256 `3C5E0D645B5FE80367AAFDB8590DDC21B9E56B081DCC639E99A1A59A412B2832`
  не содержит r1a/r1b markers, а tag workflow Rust не пересобирает и может упаковать stale native
  code. Установленный build3 не затронут: он собран свежим `build-rust.ps1`, его `.so` hash
  `7C9537E6FA5F93B4F6AC28B2705F97A35E738C36309922A029D06E0886517194`. До любого tag/release
  добавить reproducible Rust build/hash gate. Tracked signing keystore/config — security debt, но
  сейчас нужен для data-preserving signer continuity; не удалять без отдельного migration plan.
  Во время аудита Arena снова локально вернула HEAD на `991da05`; безопасно исправлено exact fetch
  `arena/01a000bc-apumir` + mixed reset до `44f1aa3`, без branch switch/hard reset/потери файлов.
- **2026-08-14 (доп.82)** — read-only package identity mapping после ошибочного serial в первом
  recovery block PASS 3/3; serials совпали с original launch state. Независимые identity values:
  Anna `AUYF6R5923006121` / MTN-NX1 / UID 10425 / PID 24022 / firstInstallTime
  `2026-08-08 11:40:39`; Zhenya `3B665800EES00000` / PLR110 / UID 10395 / PID 7439 /
  `2026-08-08 17:31:18`; Stas `11567254BK001192` / TECNO LI6 / UID 10387 / PID 20786 /
  `2026-08-10 12:41:10`. Все versionCode 11016012, processRunning=true. Это те же три
  data-preserved APU installations; capture guard'ил exact serial/UID/version/firstInstallTime.
- **2026-08-14 (доп.83)** — one-shot native recovery capture завершён и не повторяется. State:
  `%TEMP%\apu-r4.2-r1b2-v11.16.12-native-recovery.json`, outcome
  `CAPTURED_READ_ONLY_ANALYSIS_PENDING`; immutable snapshots `...-native-recovery-{Anna,Zhenya,Stas}.log`.
  Snapshot hash/parse PASS, all lines exact unchanged cold-launch PID: Anna 24022 (122 lines),
  Zhenya 7439 (78), Stas 20786 (286). Original launch 18:21:48Z–18:24:20Z; native buffers start
  only 18:34:11Z–18:39:35Z, поэтому exact startup `MQTT SESSION READY` lines вытеснены и direct
  cold-start-marker evidence остаётся incomplete; launch/log clear не повторять. Runtime
  readiness/liveness при этом PASS 3/3: до последних секунд capture пришли wildcard events
  Anna/Zhenya/Stas 48/33/137 (`presence`, `gossip`, `message-or-receipt`), core consumer обработал
  peer-online 12/8/34, warnings/errors/stall/EventLoop end/channel close/session start failure/
  restart/recovery/request timeout/request error = 0 на всех. Anna heartbeat: polls 963/964,
  incoming 349, connacks 1, forwarded 350, poll_errors 0. Stas: три heartbeat через ~135 с,
  counters 694/695→938/939 и incoming 249→337, connacks 1, forwarded 250→338; historical
  poll_errors=3 не растут и current `MQTT error`=0. Zhenya buffer span лишь 83.5 с (<120-секундного
  heartbeat interval), но 33 incoming + 8 consumer peer events до capture end доказывают progress;
  по source order `MQTT IN` невозможен до initial ConnAck + wildcard subscribe readiness gate.
  Старые analyzer markers `MQTT INITIAL READY`/`MQTT PEER TRAFFIC` также были неверны; exact source
  literals: `MQTT SESSION READY`, `MQTT: connection acknowledged by broker`,
  `MQTT: subscription requested after ConnAck`, `MQTT IN`, `MQTT LIVENESS HEARTBEAT`.
  User payload не публиковался; увиденные public-broker 5-byte message/receipt events чужие/служебные.
- **2026-08-14 (доп.84)** — r4.2-r1b3 topic-aware overflow source complete, Windows compile
  pending. Новый pure `network/mqtt_overflow.rs` классифицирует по topic+payload envelope:
  presence/gossip/ping/relay-registration/`gsumm`/empty retained clear — refreshable best-effort;
  обычное message, `relay|`, `receipt|`, ACK и unknown — loss-intolerant fail-closed. Общий
  EventLoop→core cap остаётся 256; best-effort не может занять последние 32 slots и идёт через
  non-blocking `try_send`. Допустимый overflow drop увеличивает fixed-memory
  `best_effort_drops`; marker `MQTT OVERFLOW DROP` не содержит topic/payload и пишется только на
  total 1/powers-of-two, exact counter входит в heartbeat/stall/task-exit. Четыре pure tests:
  taxonomy, delivery+unknown safety, reserve boundary (loss-intolerant enqueue даже capacity=0),
  bounded log schedule. Static field/marker/integration checks PASS; первый вспомогательный
  Python exact-count check имел слишком широкий substring assertion, исправленный line-regex PASS;
  это не Rust defect. Host cargo/test не запускались. Broker/QoS/retain/topics/caps не изменены.
  Loss-intolerant всё ещё ждёт `.send().await` и никогда не drop; durable pending handoff перед
  r1b2 session abort/replacement — следующий отдельный r1b4, без него overflow/recovery gate не
  закрыт. Телефоны/APK/public traffic не менялись.
- **2026-08-14 (доп.85)** — r4.2-r1b3 Windows Android Rust build PASS exact source
  `a69c1a0bc44e653758523965ca6c46e48deb0bd8`: `build-rust.ps1` exit=0,
  `Finished release`=1, compiler errors=0, duration 65.99 с. Harness warning count=10 включает
  прежние 9 warning lines + Cargo summary `generated 9 warnings`, не новый десятый defect.
  arm64 `libp2p_core.so`: 7,169,720 B, SHA-256
  `E36F32E8CB1E6FA641D380550A496523840D174CC70B4888C04D5E2DD6B9B066`; hash отличается от
  r1b2. Windows HEAD/remote exact, Git modified только generated arm64 `.so`, не commit.
  State/log: `%TEMP%\apu-r4.2-r1b3-rust-build.json` и `.log`; APK/phones/public traffic не
  менялись.
- **2026-08-14 (доп.86)** — r4.2-r1b4 loss-intolerant owned handoff source complete, Windows
  compile pending. `LossIntolerantInbox<MqttEvent>` — core-owned `Arc` FIFO cap 256, созданный до
  generation-1 и передаваемый initial/replacement sessions. Message/relay/receipt/ACK/unknown
  больше не идут в refreshable `mpsc.send().await`: EventLoop сначала кладёт их в shared inbox,
  core `poll_event` drain'ит inbox раньше best-effort. После initial ConnAck producer перед каждым
  следующим broker poll ждёт capacity через `Notify`; accepted packet уже owned и переживает Drop
  old transport. Initial ConnAck новой session разрешён даже при inherited full inbox, иначе
  recovery loop не смог бы вернуть transport core для drain. `push_owned` намеренно не drop даже
  при invariant violation: depth>cap + payload-free error, затем producer backpressure. General
  mpsc cap 256/reserve 32 теперь защищает ConnAck/control от refreshable flood. Liveness получил
  phase `loss_intolerant_backpressure` и counters buffered/pending/backpressure во всех heartbeat/
  stall/task-exit markers; новая session инициализирует inherited pending gauge. 7 sync + 1 async
  tests покрывают FIFO, Arc owner survival после session drop, full wait+drain notify, zero cap и
  taxonomy/reserve/log schedule; targeted ownership/route/field/delimiter checks PASS. Один общий
  самодельный delimiter scan дал false result на legacy large `core.rs` strings/comments и был
  отброшен; это не Rust defect. Host cargo/test не запускались. Broker/QoS/retain/topics не
  изменены. Safety только между sessions внутри живого process; process persistence остаётся M8.
  APK/phones/public traffic не менялись.
- **2026-08-14 (доп.87)** — r4.2-r1b4 Windows Android Rust build PASS exact source
  `1a433424d9b340b392b00aef2c093cdd54edb128`: `build-rust.ps1` exit=0,
  `Finished release`=1, compiler errors=0, duration 66.01 с. Warning count=10 снова равен 9
  existing warning lines + Cargo summary. Previous r1b3 `.so` hash E36F…B066 exact before build.
  Новая arm64 `libp2p_core.so`: 7,180,888 B, SHA-256
  `E706A9009F28E842F6A030D0CCC7BABB28D56E20DEFB5FB34117FD87F032E7E5`. Git modified только
  generated arm64 `.so`, не commit. State/log: `%TEMP%\apu-r4.2-r1b4-rust-build.json` и `.log`.
  APK/phones/public traffic не менялись. r1b3 topic-aware admission + r1b4 owned cross-session
  handoff source/build gates закрыты.
- **2026-08-14 (доп.88)** — первый v11.16.13 APK artifact block остановился до Gradle: штатный
  `java -version` JDK 17.0.17 написал version в stderr, а Windows PowerShell при
  `$ErrorActionPreference=Stop` превратил её в `NativeCommandError`. Outcome
  `FAIL_DO_NOT_RETRY_AUTOMATICALLY`, build exit/BUILD SUCCESSFUL/APK/version/signature/native
  fields empty, phones=false. State `%TEMP%\apu-r4.2-r1b4-v11.16.13-apk-build.json` и logs не
  удалять, старый блок не повторять. Это harness-only preflight defect, не Java/APU compile FAIL.
  Сначала read-only подтвердить `BuildAttempted=false`, stable APK absent, exact r1b4 `.so` и
  generated-only Git status. Затем один corrected build2 с distinct state/log/APK; Java version
  получать через child `Start-Process`/redirect, чтобы штатный stderr не был terminating error.
- **2026-08-14 (доп.89)** — read-only APK failure preflight PASS: old outcome
  `FAIL_DO_NOT_RETRY_AUTOMATICALLY`, `BuildAttempted=false`, build exit empty, stable APK absent,
  phones/ADB/install/launch=false; Windows HEAD `5dfa53c`; r1b4 native 7,180,888 B / E706…E7E5
  exact; old state SHA-256 `A2C91AAF40F1241B6255C6B302573656C614FA63E0FD03CA6C119D17730FC039`.
  Ничего не изменено. Общий Windows PowerShell 5 rule расширен в этом документе и
  `BACKUP_AND_CLEAN_PC_RECOVERY.md`: redirected native stderr + outer EAP Stop может terminate до
  `$LASTEXITCODE`; касается Java, Cargo/cargo-ndk, Gradle/JVM, Git progress, ADB diagnostics.
  Critical harness обязан использовать `Start-Process` с separate stdout/stderr, ExitCode +
  command-specific allowed outcomes + positive marker/artifact/hash. Нормальные nonzero отдельно:
  `pidof exit=1+empty` = process absent, `git diff --quiet exit=1` = differences, `git grep exit=1`
  = no match; никакого global allowance. После harness-only stop — immutable old evidence,
  read-only no-effect proof и distinct build2/recovery2 paths, без удаления/повтора old block.
- **2026-08-14 (доп.90)** — corrected v11.16.13 APK build2 реально собрал artifact, но state
  `FAIL_DO_NOT_RETRY_AUTOMATICALLY` из-за слишком узкого certificate-output parser после signer
  success. Java/Gradle/AAPT/apksigner exit=`0/0/0/0`, `BUILD SUCCESSFUL`=1, version
  v11.16.13/11016013, V2=true. APK `%TEMP%\apu-r4.2-r1b4-v11.16.13-build2.apk`:
  22,599,180 B, SHA-256 `5A26728BE78941C7D0CA6FE8FBE24AD81679A57F60FE87B35999014F3D7C03BA`.
  Cert field и embedded-native fields пусты только потому, что exact-English regex остановил block
  до ZIP inspection; phones/ADB/install/launch/public traffic=false. State и separate signer
  stdout/stderr immutable; build2 не повторять и artifact не перезаписывать. Recovery только
  read-only: показать safe signer certificate lines, извлечь substring после digest label,
  удалить `[^0-9A-Fa-f]`, потребовать ровно один 64-hex digest и exact expected cert; отдельно
  открыть existing APK ZIP и проверить embedded `.so` size/hash. Общее parser rule: raw output
  сохранять до parsing; tool version/localization/separators не считать build failure. Если
  exit+positive marker+artifact уже PASS, parser failure исправлять read-only analyzer, не rebuild.
- **2026-08-14 (доп.91)** — v11.16.13 APK read-only validation recovery PASS без rebuild.
  Recovery state `%TEMP%\apu-r4.2-r1b4-v11.16.13-apk-validation-recovery.json`; parent build2
  state SHA-256 `73D33593BCFB5FF82718716A1929AE7CD89028A215E2E7389A1AE41DE3A570AD`.
  Authoritative APK `%TEMP%\apu-r4.2-r1b4-v11.16.13-build2.apk`: v11.16.13/11016013,
  22,599,180 B, SHA-256 `5A26728BE78941C7D0CA6FE8FBE24AD81679A57F60FE87B35999014F3D7C03BA`.
  Signer exit=0, V2=true, exactly one signer; certificate SHA-256
  `F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7` exact installed signer.
  Embedded arm64 `.so` 7,180,888 B / `E706A9009F28E842F6A030D0CCC7BABB28D56E20DEFB5FB34117FD87F032E7E5`
  exact r1b4. Причина old parser stop: actual label `V2 Signer: certificate SHA-256 digest`, не
  ожидавшийся `Signer #1 ...`; normalized semantic parser PASS. BuildRepeated/phones/ADB/install/
  launch/public traffic=false. APK не перезаписывать.
- **2026-08-14 (доп.92)** — первый interactive v11.16.13 preinstall block имел parser defect:
  binary operator был разорван как `@(...) -` newline `join`, вместо atomic `$Lines -join ""`.
  PowerShell полностью не распарсил outer `& {}` и поэтому не выполнил его ни частично: `$Adb`,
  `$Device`, `$Results`, `$StatePath` не создавались. Остаток большого paste затем попал в prompt
  отдельными командами; `& $Adb` явно упал на NULL, state write имел NULL path. Напечатанный позже
  `PREINSTALL READ-ONLY PASS` недействителен (state/hash пустые); ADB/phones/files не менялись.
  После любого `ParserError` немедленно STOP: не выполнять/не доверять auto-pasted remainder и
  любой PASS без non-empty persisted state+hash. Long critical harness больше не давать inline:
  versioned `scripts/v111613_preinstall.ps1` сначала sync exact branch, проверяется встроенным
  `[System.Management.Automation.Language.Parser]::ParseFile`, затем выполняется `-File`/call.
  Операторы `-join`, `-f`, `-replace`, `+` держать с обоими operands в одном expression либо
  сначала присваивать raw value. Новый preinstall2 использует `Start-Process` stdout/stderr evidence
  для каждого ADB call, command-specific pidof exit 0/1, final state write+hash до PASS; install/
  force-stop/launch/log clear/network отсутствуют.
- **2026-08-14 (доп.93)** — versioned parser-validated `scripts/v111613_preinstall.ps1` PASS.
  Windows parser=0 errors; worktree script SHA-256 (CRLF checkout)
  `3B5504C15F8173FCE9B0FD6729E71D7C53673553D9D747B1EBD63A9289916956`. State
  `%TEMP%\apu-r4.2-r1b4-v11.16.13-preinstall2.json`, SHA-256
  `F57C880884CE7AFDD177393DF5BA879372CD2BF3C0E04998B89795D33B90C575`, outcome PASS 3/3.
  APK hash 5A26…03BA exact. Installed v11.16.12/11016012 identities exact: Anna current PID 24022 /
  UID 10425 / firstInstallTime `2026-08-08 11:40:39`; Zhenya PID 7439 / UID 10395 /
  `2026-08-08 17:31:18`; Stas PID 20786 / UID 10387 / `2026-08-10 12:41:10`; dataDir
  `/data/user/0/com.vladimir.messenger` 3/3. Это те же unchanged cold-launch PID. All native
  calls captured separate stdout/stderr; install/force-stop/launch/logcat/network/public traffic
  false. Следующий phone-changing gate требует explicit approval: versioned guarded `adb install -r`
  v11.16.13; package replacement штатно остановит process, но block не делает force-stop/launch.
- **2026-08-14 (доп.94)** — пользователь явно разрешил data-preserving install v11.16.13 на
  Anna/Zhenya/Stas. Добавлен versioned `scripts/v111613_install.ps1`: exact preinstall state/APK/
  signer guards; все 3 real-time prechecks завершаются до первого phone-changing command; затем
  ровно один sequential `adb install -r` call site, separate stdout/stderr, exact `Success`, post
  version/UID/firstInstallTime/dataDir/process-absent. State пишется в `finally` даже при partial
  install, automatic retry запрещён. Script не содержит actionable uninstall/data clear/force-stop/
  launch/logcat/network; final PASS только после state write+hash и verified 3/3. Known parser-trap,
  ordering/state и forbidden-command scans PASS. Первый sandbox checker дал false assertion на
  слово `uninstall` внутри safety output label `Data clear/uninstall: False`; corrected scan
  отличает declarations от actionable commands и PASS. Phones пока не менялись; следующий gate —
  sync exact script, Windows `ParseFile` zero errors, затем approved install once.
- **2026-08-14 (доп.95)** — approved versioned `scripts/v111613_install.ps1` Windows ParseFile
  PASS; worktree script SHA-256 `B13E9127856B01FB2898AC46B4599CA6C72C404BC8A36F4BBD0605DE9C001374`.
  Guarded data-preserving `adb install -r` v11.16.13 PASS 3/3. State
  `%TEMP%\apu-r4.2-r1b4-v11.16.13-install.json`, SHA-256
  `2B7C9B74AEA366AA5530351223E956F0087DE70FC71461E282192DBA8D2CC4B7`; verified count=3.
  Anna UID 10425, Zhenya 10395, Stas 10387 preserved; version v11.16.13/11016013,
  firstInstallTime и dataDir exact preserved 3/3. Uninstall/data clear/force-stop/launch/logcat/
  network/public traffic=false. Post-install processRunning=false 3/3. Install/state/evidence не
  повторять/не удалять. Следующий gate требует separate approval: one controlled launch новой
  версии без force-stop и без `logcat -c`; early 45s + late additional 120s snapshots с exact new
  PID и native `p2p_core`, чтобы одновременно сохранить startup READY и heartbeat evidence.
- **2026-08-14 (доп.96)** — пользователь отдельно разрешил one controlled launch v11.16.13.
  Добавлен versioned `scripts/v111613_launch.ps1`: exact install-state hash/version/UID/data/
  process-absent preflight 3/3 до первого launch; ровно один `am start -W` call site; no force-stop,
  no `logcat -c`, no network/user payload. Early wait 45 с сохраняет startup markers; late ещё
  120 с (total 165) — heartbeat. `p2p_core` snapshots фильтруются exact new PID **и** per-device
  pre-launch epoch, исключая старый buffer/PID reuse. Process PID обязан остаться один и прежний.
  Analyzer использует exact literals (`MQTT SESSION READY`, ConnAck/subscription, heartbeat),
  требует latest connacks>=1, loss_intolerant_pending=0, request counters=0 и ноль best-effort
  drop/backpressure/invariant/stall/channel close/session start/restart/recovery/request failure/
  crash-ANR. Poll errors только измеряются: transient stable errors сами по себе не означают stall.
  Separate early/late/crash evidence, state в finally, no automatic retry. Known parser/action,
  ordering/evidence/state и forbidden-command scans PASS. Phones остаются stopped после install;
  следующий gate — sync exact script, Windows ParseFile zero errors, затем approved launch once.
- **2026-08-14 (доп.97)** — approved one-shot v11.16.13 launch выполнен; не повторять. Versioned
  script Windows ParseFile PASS, worktree SHA-256
  `6C7869F73FA8505F8CF7B279F9648B9AA6F8B58419DC51CC6D997F76D07D8DB1`. Early 45 с + late
  additional 120 с завершены; force-stop/logcat-clear/network/user payload=false. State
  `%TEMP%\apu-r4.2-r1b4-v11.16.13-launch.json`, SHA-256
  `3AA96B1AE79C9EEB5CBEF99DCC4FAC13E7A14A23DD95F530989C138140737E47`, outcome
  `INCOMPLETE_DO_NOT_REPEAT`, passedDeviceCount=2. Единственные reported failures: Anna direct
  `MQTT SESSION READY`, connection-ack и subscription-request lines missing. Значит её process/
  heartbeat connacks/pending/request counters и все error/stall/restart/crash gates прошли; иначе
  они были бы в aggregated failure list. Вероятная причина — high public traffic вытеснил startup
  lines даже до 45s early snapshot. Никаких новых ADB capture/relaunch/log clear. Recovery только
  read-only из immutable state + early/late evidence: exact PID/epoch-filtered span, heartbeat
  counters и incoming traffic. Direct cold-start markers могут остаться incomplete; runtime
  readiness допускается только при connacks>=1 + heartbeat + incoming subscribed traffic + stable
  PID + zero errors.
- **2026-08-14 (доп.98)** — добавлен saved-evidence-only versioned analyzer
  `scripts/v111613_launch_analyze.ps1`: exact parent hash, no `$Adb`/Start-Process, читает только
  existing early/late logs, filters PID+launch epoch, verifies process stability and parses latest
  heartbeat polls/incoming/connacks/forwarded/r1b3-r1b4/request counters. Runtime PASS требует
  incoming>=1, connacks>=1, pending=0, requests=0 и zero original error metrics; direct READY
  остаётся отдельным 2/3 fact. Anna разрешена как единственный inferred runtime-ready phone;
  earliest offset>10 с помечает likely eviction. Manifest хэширует весь evidence; новый recovery
  state не меняет parent. Static saved-only/ordering/state scans PASS. До commit source review
  обнаружил три собственных risky line continuations (member `.` и два `-f` at EOL); переписаны
  atomic expressions в соответствии с доп.89/92 до Windows execution. Следующий gate — sync,
  Windows ParseFile zero errors и один read-only analyzer run; relaunch/logcat/ADB запрещены.
- **2026-08-14 (доп.99)** — saved-evidence analyzer Windows ParseFile PASS, worktree SHA-256
  `ECAFECEF82B301A351CB776DEE05BABC8F4412A8D97C661E1581AEC4A71E9EA2`; no ADB/logcat/relaunch.
  Runtime recovery PASS 3/3. State `%TEMP%\apu-r4.2-r1b4-v11.16.13-launch-analysis-recovery.json`,
  SHA-256 `7FDAF9BD2634A322186AECB704A393F132D9B66D9A891C568A9DB3E041C3A89F`; parent launch hash
  3AA9…7E47 exact. Direct startup markers 2/3, runtime 3/3. Anna PID 10945 stable, earliest
  preserved offset 20.501 с (startup definitively evicted), incoming 58; heartbeat incoming56/
  connacks1/forwarded57, loss buffered/pending/backpressure=0/0/0. Zhenya PID20562 stable,
  incoming73; heartbeat 58/1/59, loss buffered/pending/backpressure=2/0/0. Stas PID23149 stable,
  incoming70; heartbeat 57/1/58, loss=2/0/0. Best-effort drops/poll errors/request timeout/error=0
  на всех; all original stall/channel/session restart/recovery/crash gates zero. r1b4 handoff runtime
  доказан: accepted critical events drain to pending=0. Relaunch/ADB/logcat/user payload=false.
  Readiness/liveness gate закрыт; следующий разрешённый network action — только один explicit-user-
  approved synthetic QoS1 non-retained relay smoke, без retry/high load.
- **2026-08-14 (доп.100)** — пользователь разрешил functional smoke и последующие необходимые
  проверки («сколько раз надо»), но public-broker safety остаётся: сначала ровно один publish;
  повтор только отдельным решением при persisted `publishCalled=false`, никогда automatic/high-load.
  Добавлены versioned `scripts/v111613_publish_once.py` и `v111613_delivery_smoke.ps1`. Python
  имеет один `client.publish`, QoS1/retain=false, atomic result перед/после publish/PUBACK и
  `--check`. PowerShell exact runtime-state/PID/version guards, node IDs выводит из immutable
  launch peer-set intersections, GUID test ID absent proof, final PID check, затем запускает
  publisher once и ждёт 30 с только если publishCalled. Captures correct `p2p_core` + Kotlin tags
  without clear/relaunch. Matrix: Anna relay/store/receipt/remove/origin/clear=1; Zhenya relay/
  store/receipt/remove=1; Stas relay/local/receipt-sent/UI=1; все unrelated roles 0, PID stable,
  overflow/backpressure/invariant/stall/restart/request/crash=0. State в finally, automatic retry
  false. Python py_compile, one-call, parser-trap, action/acceptance, ordering/state scans PASS.
  Следующий gate — sync both scripts, Windows ParseFile + `py -3 ... --check`, затем one shot.
- **2026-08-14 (доп.101)** — v11.16.13 one-shot functional delivery smoke PASS; не повторять.
  Scripts Windows parser PASS; PS SHA `F114F0B0D85F0FEAD1714A69673B4E1802E04649D07C82750B9E345C048DDAF7`,
  Python SHA `53942722673005DCCB77593A352C89A51277DDC4AD641EF7568A1B7EBE211F3A`.
  State `%TEMP%\apu-r4.2-r1b4-v11.16.13-delivery-smoke.json`, SHA-256
  `BA04C060B1CCEE5272A4113332032225A3029BAD8CF10F27DAD215BD4790F08F`; test ID
  `v111613-smoke-1786737622095-3deda0ae`. Exactly one public HiveMQ publish: QoS1,
  retain=false, publishCalled/confirmed=true, automaticRetry=false. Exact matrix: Anna
  relay/store/local/receipt/remove/origin/UI=`1/1/0/1/1/1/0`; Zhenya=`1/1/0/1/1/0/0`;
  Stas=`1/0/1/1/0/0/1`. PASS также означает PID stable 3/3 и unexpected relay/MQTT error/
  overflow/backpressure/invariant/stall/restart/request/crash=0. Functional smoke budget consumed.
  Transport/relay/receipt foundation доказан; главная user-facing offline goal теперь блокируется
  конкретно M3(d) automatic send-path + M8 persistence + final 3-phone offline acceptance. Старый
  agreed order ставит bounded multi-broker r4 перед M3(d), но пользователь спросил, когда будет
  главная цель; не менять порядок молча — запросить explicit reprioritization choice.
- **2026-08-14 (доп.102)** — пользователь явно выбрал сохранить старый reliability-first порядок:
  `r4.3 status-only feature-gated second session → r4.4 bounded dual publish+production dedup →
  r4.5 local two-broker failure matrix → M3(d) automatic offline relay send-path → persistence →
  final three-phone offline acceptance`. Не переставлять M3(d) раньше r4 молча. Functional public
  smoke уже consumed; r4.3 не публикует user envelopes во вторую session, дальнейшие public tests
  только если отдельно необходимы/разрешены. Следующий шаг source-only: audit current
  `multi_broker.rs`, `mqtt_dedup.rs`, session ownership/config и минимальный compile-tested r4.3.
- **2026-08-14 (доп.103)** — r4.3 status-only secondary source complete, Windows feature build
  pending. Legacy `multi_broker.rs` (5-broker switching fallback, может split mesh) не подключён.
  Cargo feature `mqtt-secondary-observe`, `default=[]`; module declaration и core spawn hard-gated.
  Новый `mqtt_secondary_observer.rs`: ровно EMQX `broker.emqx.io:1883`, one EventLoop task,
  AsyncClient channel cap1 retained only for session lifetime, clean_session=true/keepalive60;
  **нет `.subscribe()`/`.publish()`**. Payload-free `MQTT SECONDARY STATUS/SUPERVISOR` markers
  фиксируют starting/connecting/connected/backoff/stopped, polls/ConnAck/errors и неизменно
  `subscriptions=0 publishes=0`; backoff 1→2→4→8→16→30, Drop abort. 4 pure tests: initial
  counters, ConnAck/error transitions, stable labels, capped backoff. Primary HiveMQ transport,
  topics/QoS/retain/channels/relay/dedup untouched. `build-rust.ps1` получил safe optional
  `-Features mqtt-secondary-observe`, default behavior unchanged. Observe-only/feature integration/
  Windows feature-build/static policy scans PASS; host cargo/test не запускались. APK/phones/
  public traffic не менялись. Следующий gate — exact Windows `build-rust.ps1 -Features
  mqtt-secondary-observe`, generated `.so` only.
- **2026-08-14 (доп.104)** — добавлен versioned `scripts/r43_observe_build.ps1`: exact r1b4
  previous `.so` E706…E7E5, application diff guard от source `2689dbb`, parser check для
  `build-rust.ps1`, child PowerShell с exact `-Features mqtt-secondary-observe`, separate
  stdout/stderr, required feature marker + one Finished release + errors=0 + changed `.so`, final
  Git generated-only. State в finally, APK/phones/public traffic=false, no retry. Parser-trap,
  feature invocation/marker, ordering и no-phone scans PASS. Первый ordering checker ошибочно взял
  initialization `$GeneratedSoHash=$null` вместо post-build assignment; corrected targeted search
  PASS, harness defect отсутствует. Следующий gate — sync exact versioned harness и Windows run.
- **2026-08-14 (доп.105)** — r4.3 feature child Cargo build фактически завершён, но parent
  `Start-Process -Wait` не вернулся после child PID 9160 exit и был безопасно прерван через 689.25с
  после process-tree proof. Parent state `%TEMP%\apu-r4.3-observe-rust-build.json`, SHA-256
  `1A81D7F0ABE883146C06E9641A2C43AD49F94C80CD35C94A633143964BD42D03`, outcome FAIL only because
  wrapper не получил exit/не прочитал logs. Evidence: stdout exact feature marker и completion/
  native-copy lines; stderr `Finished release ... in 1m 12s`, `generated 9 warnings`, no compile
  error shown. cargo/rustc/clang/lld/child PowerShell отсутствовали; only original console+conhost.
  Generated `.so` 7,193,912 B, SHA-256
  `D7A2216EF0210CBCD59685A6E76CF4D8284B87A8052C799280CBEEC4DD95FE95`; Git generated-only;
  APK/phones untouched. Build не повторять. Added `r43_observe_build_recover.ps1` проверяет exact
  parent/log/source/binary markers read-only и пишет distinct recovery state. Future nested builds:
  no unbounded `Start-Process -Wait`; exact child `.WaitForExit(timeout)` + timeout evidence.
- **2026-08-14 (доп.106)** — первый read-only r4.3 build recovery safely stopped before analysis:
  Windows parser PASS, но script hardcoded previous documentation HEAD `c6164ba`, тогда как script
  сам добавлен в `a056369`; build/ADB/phones не запускались, recovery state не создан. Это
  self-referential harness precondition defect. Исправление: launcher отвечает за exact current
  branch commit; versioned recovery внутри сохраняет фактический docs HEAD и строго требует
  `git diff --quiet 2689dbb <HEAD> -- rust-core android-app build-rust.ps1`. Не hardcode commit,
  который ещё изменится при добавлении самого script/docs. Parent build evidence неизменно, build
  не повторять; distinct corrected script commit, затем read-only recovery.
- **2026-08-14 (доп.107)** — corrected r4.3 saved-artifact recovery Windows ParseFile PASS;
  state `%TEMP%\apu-r4.3-observe-rust-build-recovery.json`, SHA-256
  `99599AA6072DDF15DE8F4AAB76D427D17B21C8E8CD7EBA9D7D41313ED4ACD085`, outcome
  `PASS_FROM_COMPLETED_CHILD_EVIDENCE`. Parent state 1A81…2D03 exact; feature marker=1,
  Finished release=1, compiler errors=0. Generated feature `.so` 7,193,912 B, SHA-256
  `D7A2216EF0210CBCD59685A6E76CF4D8284B87A8052C799280CBEEC4DD95FE95`; binary ASCII exact
  `MQTT SECONDARY STATUS`, `MQTT SECONDARY SUPERVISOR`, `subscriptions=0 publishes=0` all present.
  BuildRepeated/phones=false. r4.3 feature compile gate закрыт; parent wrapper FAIL остаётся
  immutable harness evidence. Следующий gate — C-drive JDK17 APK v11.16.14 artifact с embedded
  D7A2…FE95, signer/version checks, no install. Child process ожидать bounded exact PID через
  `.WaitForExit(timeout)`, не unbounded `Start-Process -Wait`.
- **2026-08-14 (доп.108)** — добавлен versioned `scripts/r43_v111614_apk_build.ps1`: application
  diff guard 2689dbb, exact feature `.so` D7A2…FE95/7,193,912 B, JDK17 C-drive, version через
  GITHUB_REF_NAME v11.16.14/11016014. Java/Gradle/AAPT/apksigner используют exact child Process и
  bounded `.WaitForExit(30s/900s/30s/60s)`, no unbounded `Start-Process -Wait`; separate logs.
  Gates: BUILD SUCCESSFUL, AAPT version, semantic normalized V2 signer cert, embedded native exact,
  generated-only Git. State в finally; APK/phones/ADB/install/launch/public traffic false. Parser-
  trap, bounded-wait/identity и no-phone scans PASS. Следующий gate — sync parser-validated harness
  и artifact-only Windows run.
- **2026-08-14 (доп.109)** — first v11.16.14 APK harness stopped safely before Gradle after 0.13s.
  State `%TEMP%\apu-r4.3-observe-v11.16.14-apk-build.json`, SHA-256
  `828C2F47976F7E9E081A491236324318CF1483EE9259AAE8AFEB5B8A0708CF0B`; outcome FAIL,
  buildAttempted=false, build PID/exit empty, APK absent, phones=false. Java process started, but
  `Wait-ExactProcess` returned scalar exit code 0 which was lost as `$null` in assignment; guard
  stopped before Gradle. Это harness return-shape defect, не Java/APU failure. Old block/state/logs
  не повторять/не удалять. Added distinct `r43_v111614_apk_build2.ps1`: verifies old state hash and
  pre-Gradle stop; Wait helper returns non-null object `{Exited,ExitCode,ProcessId}` and callers read
  `.ExitCode`; calls parameterless WaitForExit after bounded wait for redirected-stream flush.
  Separate build2 paths; same exact artifact/signature/native gates. Следующий gate parser/static
  checks then one corrected artifact build2, no phones.
- **2026-08-14 (доп.110)** — corrected r4.3 v11.16.14 APK build2 PASS. State
  `%TEMP%\apu-r4.3-observe-v11.16.14-apk-build2.json`, SHA-256
  `01A649477F975B312D1CAB4797B217ACA5E6C68071E0AAC8736426FD9B492FBF`; exact child Gradle PID
  11116/exit0, BUILD SUCCESSFUL=1, duration49.48s. APK v11.16.14/11016014:
  22,615,564 B, SHA-256 `6C4D29DA78EB914C172376955B4A802473C26EB7595E25BEB51373D0CCE13F5C`;
  V2 signer cert F843…A4A5F7 exact; embedded feature `.so` 7,193,912 B / D7A2…FE95 exact.
  Phones/ADB/install/launch/public traffic=false. Artifact gate closed. Minimal r4.3 runtime plan:
  data-preserving install+one launch только Anna, while Zhenya/Stas remain v11.16.13 primary peers;
  this does not switch Anna away from HiveMQ because EMQX observer has subscriptions/publishes=0.
  Require explicit phone-change approval before versioned harness.
- **2026-08-14 (доп.111)** — пользователь разрешил Anna-only r4.3 runtime install+launch.
  Добавлен `scripts/r43_anna_runtime.ps1`: exact APK/runtime states and identities, all-three
  precheck before one `adb install -r` Anna, data-preserving postcheck/process absent, one launch;
  Zhenya/Stas remain v11.16.13 with exact PID. No force-stop/logcat clear/user payload. Early
  snapshot shortened to 15s (Anna old 45s buffer already evicted startup); late additional 135s,
  total150 for heartbeat. Gates: secondary supervisor+starting exactly1, EMQX connected>=1,
  `subscriptions=0 publishes=0`, no secondary backoff; primary READY/heartbeat/incoming and zero
  error/stall/restart/request failures. State finally/no retry. Parser-contract and exact one-install/
  one-launch/forbidden-action scans PASS. Next sync, Windows ParseFile, approved execution once.
- **2026-08-14 (доп.112)** — first Anna r4.3 runtime harness Windows parser PASS but stopped before
  first ADB process: function parameter `$Args` conflicts case-insensitively with automatic `$args`,
  so Start-Process ArgumentList was NULL. State
  `%TEMP%\apu-r4.3-observe-v11.16.14-anna-runtime.json`, SHA-256
  `D33273ACC3A728ED2DB883CC6D096D8D9BB1200B4D803E5177A6B81193137EDD`, outcome incomplete,
  installStarted/launchStarted=false, PID empty; phones unchanged. Source review also found future
  `$Pid` parameter conflict with read-only `$PID`. Old harness/state не повторять/не удалять.
  Added distinct `r43_anna_runtime2.ps1`: `$ArgumentList`/`$AndroidProcessId`, exact old-state hash
  and pre-ADB proof, separate state/evidence; other gates unchanged. Automatic-variable scan PASS.
  General rule: PowerShell variables case-insensitive applies to **all** automatic names, not only
  assignment sites; never use `$Args`/`$Pid` as function parameters. Next parser-validated runtime2.
- **2026-08-14 (доп.113)** — approved corrected Anna runtime2 выполнен один раз; не повторять.
  Parent state `%TEMP%\apu-r4.3-observe-v11.16.14-anna-runtime2.json`, SHA-256
  `E4AADB476E794C8F2DD12223A4BD555FCE55D159A5051BD002F104D579B98425`, outcome
  `INCOMPLETE_DO_NOT_REPEAT` из-за слишком строгой status matrix после полностью завершённых
  install/launch/final-device gates. Anna data-preserving v11.16.14 install+one launch состоялись;
  PID `21678` stable. Zhenya/Stas final evidence сохранило v11.16.13 и PID `20562/23149`.
  Immutable evidence: 60 files, manifest SHA-256
  `2A3CADDD0908E5FA566E0432DC4A68460AF1620C664BAC8CAA7C3F8262CE3FA0`; early/late p2p SHA-256
  `A0A4063…D2830` / `5E32A7E…509E6`. EMQX status доказал настоящий ConnAck: connected,
  connacks=1, polls=1/1, poll_errors=0, subscriptions=0, publishes=0, backoff=0. Более ранние
  supervisor/starting breadcrumbs не сохранились; connected — более сильное acceptance-событие,
  поэтому их отсутствие не означает connection failure и не разрешает relaunch. Primary HiveMQ
  имел один initial `Network timeout; retrying in 1s`, затем через 1.432 с READY в том же
  generation=1/attempt=1, 64 incoming и heartbeat polling с connacks=1, pending/backpressure/
  request errors=0; no stall/restart. Это bounded transient recovery, не runtime failure. Добавлен
  saved-evidence-only `scripts/r43_anna_runtime2_analyze.ps1`: exact parent/manifest/device/event
  gates, без ADB/logcat/install/launch; Windows ParseFile+one analyzer run pending. До его PASS
  r4.3 формально не закрывать. Общее правило: не требовать `MQTT error=0` как самоцель — норма
  только bounded error с последующим same-session ready/progress; отсутствие последующего ready,
  repeated/tight errors, stall/restart, pending growth или PID change остаются failure.
- **2026-08-15 (доп.114)** — saved-evidence-only r4.3 runtime2 analyzer Windows ParseFile/safety
  PASS; worktree script SHA-256 `F53742D765F86FCB705ED3C79548E4862E4FEF67392DF022223FB36A714E9D51`.
  Recovery state `%TEMP%\apu-r4.3-observe-v11.16.14-anna-runtime2-analysis-recovery.json`,
  SHA-256 `9F10B5D13BC00033991AB27CF3EABAADC4D0BE34E242FBF47AA180B766258E55`, outcome
  `PASS_FROM_IMMUTABLE_RUNTIME2_EVIDENCE`; parent E4AA…8425 и evidence manifest 2A3C…3FA0 exact.
  Anna v11.16.14 PID `21678` stable; EMQX connected ConnAck=1, subscriptions/publishes=0/0,
  errors=0. Primary HiveMQ READY+heartbeat+64 incoming после единственного initial timeout,
  recovered за 1.432 с в generation=1/attempt=1. Zhenya/Stas v11.16.13 PID `20562/23149`
  unchanged. Analyzer ADB/logcat/install/launch=false. **r4.3 runtime gate закрыт PASS**; parent
  incomplete и missing early lifecycle markers остаются immutable facts, ничего не повторять.
  Следующий выбранный этап — r4.4 bounded dual publish max2 + production cross-broker dedup;
  сначала source/local compile gates, public high-load запрещён.
- **2026-08-15 (доп.115)** — r4.4 source audit и первый isolated policy step complete; Windows
  compile pending. Все восемь persistent publish policies сходятся в private
  `MqttTransport::enqueue_publish`, а весь primary inbound `Packet::Publish` — в одном admission
  path перед core; это безопасные будущие точки atomic fanout+dedup. r4.3 observer нельзя просто
  «научить publish»: он не связан с shared event channel/loss-intolerant inbox/restart ownership.
  Legacy `multi_broker.rs` по-прежнему запрещён (sequential switch split mesh). Добавлен default-off
  feature `mqtt-dual-broker` и pure `mqtt_fanout.rs`: fixed broker bitset HiveMQ/EMQX, stable
  primary-first fanout max2 без per-message allocation, partial/complete queued outcome и bounded
  retained-target ledger cap4096. Ledger хранит только SHA-256 logical ID + 2-bit target mask,
  unions повторные targets, deterministic oldest eviction и очищает order при remove/re-record.
  Пять tests покрывают 0/1/2 sessions, partial outcome, target union/remove, cap eviction и stale-
  order regression. Модуль не содержит AsyncClient/EventLoop/publish/subscribe/spawn; current
  default и r4.3 network behavior не изменены. Host cargo test запрещён/недоступен; следующий gate
  — exact Windows `build-rust.ps1 -Features mqtt-dual-broker`, generated `.so` only. Только после
  compile PASS atomic integration включает secondary subscribe/publish и shared production dedup.
- **2026-08-15 (доп.116)** — добавлен versioned `scripts/r44_fanout_policy_build.ps1` для exact
  source commit `e3b806f`: требует прежний проверенный r4.3 feature `.so` 7,193,912 B /
  D7A2…FE95, C-drive repo/environment, generated-only worktree и hard-gated feature/policy
  constants. Child PowerShell запускает ровно один `build-rust.ps1 -Features mqtt-dual-broker`,
  ждёт exact process максимум 900 с через structured `{Exited,ExitCode,ProcessId}`, затем требует
  exit0, one feature marker, one Finished release, compiler errors=0, changed nonempty `.so` и
  generated-only final status. Separate stdout/stderr/log/state; APK/ADB/phones/public traffic и
  automatic retry=false. Parser-trap, automatic-variable, one-build, bounded-wait, C-drive,
  state/order и forbidden-action static scans PASS. Следующий gate — sync exact harness commit,
  Windows ParseFile/automatic-variable scan и один build run; при ambiguous/incomplete не retry.
- **2026-08-15 (доп.117) — обязательное решение пользователя:** APU должен работать в одной mesh-
  сети при разных поддерживаемых версиях приложения; нельзя рассчитывать на одновременное
  обновление всех телефонов. Для protocol changes обязательны explicit wire/schema version,
  capabilities или backward-compatible encoding, безопасный reject неизвестного обязательного
  формата и mixed-version matrix минимум N↔N-1 в обоих направлениях плюс relay третьим телефоном
  другой версии. Проверять message/receipt/cleanup/dedup/reconnect и отсутствие split/storm. Старые
  небезопасные версии не поддерживать бесконечно: minimum supported version и `upgrade required`
  должны быть явными, без ложного SENT/DELIVERED/тихой потери. Android upgrade только с сохранением
  identity/data/keys; destructive migration/uninstall запрещены. План: MASTER_PLAN v2 фаза 0.5;
  тематические MQTT/mesh acceptance criteria также должны учитывать rolling update.
- **2026-08-15 (доп.118)** — первый r4.4 fanout-policy Android Rust build фактически compile PASS,
  но parent harness сохранил `FAIL_DO_NOT_RETRY_AUTOMATICALLY` после build из-за combined-stream
  feature-marker count=2. State `%TEMP%\apu-r4.4-fanout-policy-rust-build.json`, SHA-256
  `311C29BFE2402EBEEBA853087C133EF23D05D67590B0AE6A38BF940A1BE57495`; child PID8048 exit0,
  Finished release=1, compiler errors=0, duration67.85s, policy source SHA F411…F7E. Generated `.so`
  fields пусты только потому, что marker assertion сработал до artifact inspection; APK/ADB/phones/
  public traffic=false. Build не повторять. Это повтор известного CLIXML/host-stream эффекта:
  feature marker считать exact по stdout, stderr duplicate хранить как evidence, combined count не
  использовать как uniqueness gate. Нужен distinct saved-build recovery: exact parent/log hashes,
  stdout marker=1, completed child evidence, generated `.so` nonempty/changed и generated-only Git.
- **2026-08-15 (доп.119)** — добавлен saved-only
  `scripts/r44_fanout_policy_build_recover.ps1`: exact parent SHA 311C…7495/source e3b806f/policy
  hash F411…F7E; требует parent child exit0/Finished1/errors0 и null generated fields после
  marker-only stop. Authoritative stdout feature marker должен быть 1, stderr duplicate=1,
  combined=2; raw streams/log остаются immutable и хэшируются. Recovery без Start-Process/build/
  ADB проверяет generated `.so` nonempty, changed от D7A2…FE95 и generated-only Git, затем пишет
  distinct PASS state. Static saved-only, ordering/state, parser-trap, automatic-variable и
  no-phone scans PASS. Следующий gate — sync/parser и один recovery run; build не повторять.
- **2026-08-15 (доп.120)** — r4.4 fanout-policy saved-build recovery Windows ParseFile/safety и
  execution PASS; recovery script SHA-256 `385C833037881576F8974DBD3EBCEED3B8173CFD36D69D140C4D1A58E2616C49`.
  Recovery state `%TEMP%\apu-r4.4-fanout-policy-rust-build-recovery.json`, SHA-256
  `55590C7AB6E431EE301A933267E742DDCFA13277A421318D9B513C971A4CAF03`, outcome
  `PASS_FROM_COMPLETED_BUILD_EVIDENCE`. Parent 311C…7495 exact; stdout/stderr feature markers=1/1,
  child exit0, Finished release=1, warnings/errors=25/0. Generated arm64 `.so` 7,180,888 B,
  SHA-256 `C8665B5DD723D6853A7D2AA0D88B9E3775B6D3AD6AD2A01E1BC8AC13807E4FCC`; policy source
  F411…F7E exact. BuildRepeated/runtime/APK/ADB/phones/public traffic=false. r4.4 isolated fanout/
  retained-target policy compile gate закрыт; parent false incomplete и logs immutable, ничего не
  повторять. Следующий source step — atomic secondary subscribe/publish + shared production dedup;
  mixed-version invariant сохраняет HiveMQ common path для N-1.
- **2026-08-15 (доп.121)** — r4.4 atomic dual-session integration source complete; Windows compile
  pending. Под default-off `mqtt-dual-broker` persistent transport создаёт exact pair HiveMQ+EMQX,
  максимум 2 AsyncClient/EventLoop sessions; r4.3 observer одновременно не spawn. Secondary имеет
  clean_session/keepalive60, bounded request cap100 и independent poll/backoff 1→30; active только
  после real ConnAck + queued wildcard subscription, повторяемой после reconnect. Primary также
  исключается из fanout между disconnect и re-subscribe. Все persistent presence/message/relay/
  receipt/clear/gossip/registration проходят единый max2 fanout; partial outcome explicit degraded,
  zero queued = error. Existing SHA-256 topic+payload filter теперь shared до core/UI для обоих
  brokers, cap4096/window30s, payload-free duplicate counter log 1/powers-of-two; core-owned state
  переживает primary generation replacement. Retained target ledger shared/limited 4096; cleanup
  удаляет obligation только после всех attempted targets queued. Dual producers больше не могут
  race past critical inbox cap256: каждый EventLoop до post-ConnAck poll резервирует owned
  semaphore slot; loss-intolerant event переносит permit в core queue, control/best-effort его
  освобождает. Поэтому accepted delivery уже имеет bounded core capacity до обработки и переживает
  session abort; добавлен two-producer regression test. Retained receipt envelope ради N-1 не
  меняется и не несёт remote broker mask, поэтому origin ставит empty tombstone в exact configured
  pair как safe bounded superset (в inactive client queue тоже bounded); obligation удаляется лишь
  после enqueue обоих. Exact configured broker list сокращён до HiveMQ/EMQX; N-1 остаётся совместим
  через общий HiveMQ. Legacy synchronous unused `send_message_mqtt` и secondary-startup-with-primary-down
  остаются отдельными долгами/r4.5, не скрывать. Static feature/fanout/dedup/ownership/cap/mixed-
  version scans PASS; host cargo/test не запускался. Следующий gate — Windows Android Rust compile
  feature `mqtt-dual-broker`, до PASS никакой APK/phone/public runtime.
- **2026-08-15 (доп.122)** — добавлен versioned `scripts/r44_dual_integration_build.ps1` для exact
  integration source `0181b34`; baseline — policy recovery SHA 5559…AF03 и generated `.so`
  7,180,888 B / C866…E4FCC. Preflight требует C-drive, generated-only Git, feature gate, exact
  two persistent constructors, secondary status/subscription, fanout/dedup/retained markers,
  semaphore reservation/two-producer test и shared core state. Ровно один child
  `build-rust.ps1 -Features mqtt-dual-broker`, exact bounded wait900s, separate streams; feature
  uniqueness считается только по stdout (CLIXML stderr count лишь записывается). PASS требует
  exit0/Finished1/errors0, changed nonempty `.so`, generated-only final status. State фиксирует
  exact pair/max sessions/fanout=2, dedup30s/4096, critical cap256, retained cap4096, wireChanged=false,
  runtimeIntegration=true; APK/ADB/phones/public traffic/retry=false. Parser-trap, one-build,
  bounded-wait, auto-variable, C-drive, ordering/state и forbidden-action scans PASS. Следующий gate
  — sync exact harness commit и один Windows build; incomplete не повторять автоматически.
- **2026-08-15 (доп.123)** — r4.4 atomic dual integration Windows Android Rust build PASS.
  Harness ParseFile/safety PASS, worktree SHA-256
  `9277AD99125946EF58F7BD1E7C99355EF13A8E508F3043BD64439CEE602FC089`. State
  `%TEMP%\apu-r4.4-dual-integration-rust-build.json`, SHA-256
  `A3E247756AC92DC77B836DDF19DC8B509B6A6DE9E6CB493AD22287A36DB5B3E4`; child PID10948,
  exit0, Finished release=1, stdout/stderr feature markers=1/1, warnings/errors=7/0, duration65.22s.
  Generated arm64 `.so` 7,248,576 B, SHA-256
  `E6C34E86F18D9F63B9A641E3FD9FAFD67D5F1B7101729B2CB3DF25163380095B`, changed from policy C866.
  State exact: runtimeIntegration=true, wireFormatChanged=false, configured pair HiveMQ/EMQX,
  sessions/fanout max2, dedup30s/4096, critical cap256, retained targets4096. APK/ADB/phones/public
  traffic=false. Source compile gate закрыт; build/state/native не повторять/не удалять. Следующий
  safe gate — C-drive signed v11.16.15 APK artifact with embedded E6C3…095B, no phones; после artifact
  PASS mixed-version install/runtime требует отдельного явного разрешения.
- **2026-08-15 (доп.124)** — добавлен versioned `scripts/r44_v111615_apk_build.ps1`: exact source
  `0181b34`, integration state A3E2…B3E4 и native 7,248,576 B/E6C3…095B; C-drive Adoptium JDK17,
  SDK C, `--no-daemon`, no Gradle stop. Один assembleRelease строит v11.16.15/11016015, затем
  stable TEMP APK проверяется AAPT package/version, V2 exact cert F843…A5F7 и embedded native
  size/hash. Все Java/Gradle/AAPT/signer child waits exact bounded со structured outcome/separate
  streams. Historical hs_err path/size/hash manifest до/после обязан совпасть; process-local env
  восстанавливается и проверяется. Final Git generated-only; state в finally, automatic retry,
  APK install/launch/ADB/phones/public traffic=false. Parser-trap, one-Gradle, bounded waits,
  automatic-variable, ordering/state, signer/native/version, C-drive и no-phone static scans PASS.
  Следующий gate — sync exact harness commit и один artifact-only Windows run; failure не retry.
- **2026-08-15 (доп.125)** — первый outer launcher для v11.16.15 остановился **до вызова APK
  harness**: проверка literal Gradle invocation была записана в double-quoted launcher string, и
  отсутствующая там переменная `$Gradlew` интерполировалась в empty; count=0 вместо1. Windows HEAD
  успел sync к `4419172`, parser/safety harness PASS, но строка `& $HarnessPath` ниже throw не
  выполнялась. Поэтому Gradle/Java/AAPT/signer не запускались, v11.16.15 state/log/APK paths не
  создавались, native E6C3…095B и phones неизменны. Старый launcher не повторять. Общее правило:
  при cross-script literal scan текст с `$variable` задавать single-quoted строкой с doubled inner
  quotes либо анализировать AST; double quotes без escape проверяют уже искажённый текст.
- **2026-08-15 (доп.126)** — добавлен versioned `scripts/r44_v111615_apk_run.ps1`: exact source/
  integration-state/native/worktree guards, требует отсутствие всех v11.16.15 output paths как
  доказательство pre-harness stop, ParseFile harness и safe single-quoted literal Gradle count=1;
  затем имеет ровно один call approved harness. Runner сам не содержит Gradle/Java/build/ADB/phone
  action. Static parser-shape, literal-interpolation, one-call, automatic-variable и no-ADB scans
  PASS. Следующий gate — sync exact runner commit, ParseFile и один runner execution.
- **2026-08-15 (доп.127)** — runner sync launcher сделал успешный `git fetch`: FETCH_HEAD и local
  tracking ref получили exact `d654569`, затем отдельный `git ls-remote` не смог соединиться с
  GitHub:443 и вернул empty remote value. Guard остановился **до `git reset` и до runner/harness**;
  Java/Gradle/APK/phones не затронуты. Старый network launcher не повторять: безопасное продолжение
  использует уже проверенные локальные FETCH_HEAD + `refs/remotes/origin/...`, без нового network
  call, затем mixed reset/restore с сохранением E6C3…095B и versioned runner. Общее правило: если
  fetch уже exit0 и exact ref сохранён, поздний redundant `ls-remote` outage не отменяет fetched
  object; сначала проверить оба local refs, не делать blind refetch/rebuild.
- **Операционный контекст 2026-08-15:** пользователь подключил по USB Анну. Женю и Стаса не
  считать подключёнными; если следующий mixed-version/3-phone test потребует их, заранее простыми
  словами предупредить пользователя подключить конкретные телефоны. Текущий APK artifact gate не
  использует ADB и не требует ни одного телефона.
- **2026-08-15 (доп.128)** — local-only FETCH_HEAD/tracking adoption exact `d654569` PASS после
  GitHub outage; runner ParseFile PASS, SHA-256
  `E476404507CBBB2E290D4201179BE332F13B64E0F7BE4A446B6241AB284F52CA`. Versioned runner доказал
  все v11.16.15 output paths absent и один раз вызвал harness SHA-256
  `9DB7639E1170BB600619870C86432D3673700E97AA70BDE34751B2E2D73A16F5`. r4.4 dual APK artifact
  PASS: state `%TEMP%\apu-r4.4-dual-v11.16.15-apk-build.json`, SHA-256
  `3D893F5A546008C3166C68026203EF2B395144EA9C072D6E9583E9FE84644078`; Gradle PID12220/exit0,
  BUILD SUCCESSFUL=1, duration91.53s. APK `%TEMP%\apu-r4.4-dual-v11.16.15.apk`:
  v11.16.15/11016015, 22,664,716 B, SHA-256
  `B675770D043E4ABA5D6D099275F489DB9666A9B16792DD45000A9EC2D243E9B2`; package exact, V2 cert
  F843…A5F7, embedded native 7,248,576 B/E6C3…095B exact. hs_err set/env restore gates входят в
  PASS. APK/ADB/install/launch/phones/public traffic=false. Artifact/state/runner/build не
  повторять/не удалять. Следующий phone-changing gate только после explicit approval: data-
  preserving v11.16.15 install на Анну + one launch для secondary ready/fanout/dedup и mixed-version
  HiveMQ common-path observation. Женя/Стас для первого Anna-only gate не нужны и не обновляются.
- **2026-08-15 (доп.129)** — пользователь явно разрешил data-preserving v11.16.15 install только
  на Анну и один controlled launch. Добавлен versioned `scripts/r44_anna_runtime.ps1`: exact APK/
  integration/native/source/signer guards, требует по USB только Anna `AUYF6R5923006121`; precheck
  v11.16.14 UID10425/firstInstall/dataDir, затем один `adb install -r`, postinstall v11.16.15 с
  сохранёнными identity/data и stopped process, один `am start -W`. No force-stop/logcat clear/
  network toggle/user payload; Zhenya/Stas serials отсутствуют и touched=false. Early15s + late135s
  saved PID/epoch-filtered `p2p_core` evidence, final stable PID. Runtime matrix требует secondary
  READY after any backoff, fanout brokers=2, cross-broker duplicate drop, evidence обоих broker,
  primary heartbeat pending/request=0, no stopped/stall/restart/request/inbox invariant; bounded
  transient errors допустимы только с более поздним ready/reconnect. State в finally/no retry.
  Parser-shape, exact one-install/one-launch, automatic-variable, forbidden action, ordering/state,
  dual markers и Anna-only static scans PASS. Следующий gate — sync exact harness, ParseFile и once;
  Женю/Стаса сейчас подключать не требуется.
- **2026-08-15 (доп.130)** — approved first r4.4 Anna runtime harness ParseFile/safety PASS, но
  остановился на первом read-only `adb devices` gate: Anna не была представлена ровно одной строкой
  `<serial> device`. State `%TEMP%\apu-r4.4-dual-v11.16.15-anna-runtime.json`, SHA-256
  `FF4F61A3320A5A78B344C9E344A04E69FF34CCC3275CC37E519E73E64BEB7968`, outcome
  `INCOMPLETE_DO_NOT_REPEAT`, installStarted/launchStarted=false, process/metrics empty;
  Zhenya/Stas touched=false, user payload=false. Значит APK не устанавливался и APU не запускался;
  это ADB visibility/authorization precheck, не product/runtime failure. Old harness/state/evidence
  не повторять/не удалять. Следующий шаг только saved evidence read: exact adb-devices stdout/stderr
  + hashes. После классификации можно создать distinct runtime2, но только при доказанном pre-action
  stop и exact current Anna `device`; никакого automatic retry.
- **2026-08-15 (доп.131)** — immutable saved ADB evidence объяснило pre-action stop: stdout 28 B,
  SHA-256 `BBFCC7239A6F1BE3544814EBDC6BCC9B66BF5B1F0B03BAA3340808F19265A630`, содержит только
  `List of devices attached` и пустую строку; stderr empty SHA E3B0…B855. Значит Anna не была даже
  `unauthorized/offline`: ADB вообще не видел USB device. Install/launch false подтверждены повторно,
  phones unchanged. Физическое подключение кабеля не равно ADB visibility. Пользователю нужно
  разблокировать Анну, выбрать USB mode «Передача файлов», убедиться, что USB debugging включён,
  принять prompt RSA (если появится), при необходимости переподключить data-capable cable/USB port;
  не force-stop/relaunch APU и не подключать Женю/Стаса. Затем только live read-only `adb devices -l`.
  Runtime2 разрешён после exact `AUYF6R5923006121 device` и old-state hash/pre-action proof.
- **2026-08-15 (доп.132)** — live read-only `adb devices -l` сохранил exact Anna line
  `AUYF6R5923006121 device product:MTN-NX1 model:MTN_NX1 ...`; stdout SHA-256
  `0E481BBCDDABC8556ACEA4E8720014BA2289B656678230E5E9EDCD8296A35E05`, stderr empty E3B0…B855.
  Значит ADB visibility READY. Inline wrapper затем дал false fail: после WaitForExit direct
  `$Process.ExitCode` отобразился blank/$null и условие `$null -ne 0` сработало, хотя exact output и
  empty stderr доказали успешный read-only command. Это wrapper defect, не ADB/device failure;
  visibility command не повторять. Versioned wrappers cast exit only after bounded+parameterless
  WaitForExit в structured object, как существующий `Invoke-Captured`.
- **2026-08-15 (доп.133)** — добавлен corrected `scripts/r44_anna_runtime2.ps1`: distinct state/
  evidence, exact old runtime SHA FF4F…7968 с install/launch=false и exact visibility hashes/Anna
  ready line. Остальной approved one-install/one-launch contract неизменён; only Anna serial,
  no uninstall/data clear/force-stop/log clear/network/user payload, no automatic retry. State
  связывает old failure и visibility evidence. Parser/action/automatic-variable, one-install/
  one-launch, ordering/state, dual marker и forbidden-action static scans PASS. Следующий gate —
  sync exact commit, ParseFile и once; Женя/Стас не нужны.
- **2026-08-15 (доп.134)** — первый sync launcher к corrected runtime2 остановился на `git fetch`:
  GitHub:443 недоступен ~21 с, fetch exit nonzero. Stop произошёл до FETCH_HEAD checks/reset,
  ParseFile/runtime2/ADB/install/launch; Windows HEAD/worktree/native и все phones неизменны, Anna
  остаётся v11.16.14. Старый большой launcher не повторять целиком при неизвестной сети. Следующий
  отдельный шаг только PC network: `Test-NetConnection github.com -Port 443`; при PASS один fetch
  exact current session branch и hash check, без reset/ADB. Если TCP false — STOP и подождать,
  никаких source/phone обходов или inline копирования большого harness.
- **2026-08-15 (доп.135)** — corrected r4.4 Anna runtime2 synced/parser PASS и выполнил approved
  install, затем stopped до launch в postinstall snapshot. State
  `%TEMP%\apu-r4.4-dual-v11.16.15-anna-runtime2.json`, SHA-256
  `78AF1BFC1AC9A3869973EAE5C2E1CCE8C6A08E6B081F8540C0CF3F74B34DA0B5`, outcome
  `INCOMPLETE_DO_NOT_REPEAT`, installStarted=true/launchStarted=false, new PID/metrics empty,
  Zhenya/Stas/user payload=false. Install command уже прошёл exit/single `Success` gate, иначе code
  не дошёл бы до postinstall snapshot. Stop вызван wrapper semantics: postinstall `pidof` stdout
  empty (ожидаемый stopped process), но `[int]$Process.ExitCode` превратил unavailable `$null` в
  ложный 0; strict rule `0 requires PID` дал false failure. Это не runtime/product failure, но Anna
  уже, вероятно, v11.16.15; не reinstall/relaunch и не повторять runtime2. Сначала saved-only exact
  install/postinstall UID/package/process evidence + hashes; только затем distinct launch-only
  runtime3, если data-preservation/v15/stopped доказаны. General rule: `[int]$null == 0`, поэтому
  cast не доказывает native success; обязательны HasExited/real exit availability и command-specific
  positive output/semantic markers.
- **2026-08-15 (доп.136)** — saved-only runtime2 evidence analysis PASS без ADB: parent state exact
  `78AF1BFC…4DA0B5`; install stdout 38 B / `C4AA470A…1306D3` содержит single `Success`, stderr
  empty; postinstall UID=10425, v11.16.15/code11016015, firstInstall=`2026-08-08 11:40:39`,
  dataDir unchanged, process stdout/stderr empty. Package evidence 16,387 B /
  `E353F7F6…41C6199`; UID `B6D5357A…4E34FD7`; все empty files имеют standard E3B0…B855.
  Data-preserving install окончательно PASS, app stopped, launch не выполнялся. Добавлен distinct
  `scripts/r44_anna_runtime3.ps1`: exact parent state + все 8 evidence hashes/semantics, no install,
  exact installed/stopped live prelaunch gate, один `am start`, 150 s PID/log runtime matrix и
  immutable runtime3 state/evidence. Wrapper хранит raw ExitCode и availability до cast; при null
  опирается только на command-specific positive output, stderr и identity markers. Static delimiter,
  ordering, 0-install/1-launch, forbidden-action, state и marker checks PASS. Первый internal hash
  counter дал false FAIL из-за regex, требовавшего лишний hyphen после `install`; scoped corrected
  parser доказал 8/8 distinct entries, harness не менялся. Следующий gate — Windows sync exact
  commit, ParseFile/static safety и один launch-only runtime3; no automatic retry.
- **2026-08-15 (доп.137)** — runtime3 Windows sync/adoption дошёл до exact commit `ed221bd`,
  сохранил E6C3 native и stopped на raw harness SHA до ParseFile/ADB/launch: Windows checkout
  CRLF hash `9E3BA002…505AB3` отличается от sandbox LF `26052F49…DB6289`. Это не изменение Git
  content: raw working-tree SHA нельзя делать cross-platform identity gate для text files при
  `core.autocrlf`. Проверять exact committed text через `git diff --quiet HEAD -- <path>` плюс
  filter-aware `git hash-object --path=<path> <working-file>` == `git rev-parse HEAD:<path>`;
  затем ParseFile/static checks. Старый sync block не повторять, fetch/reset не нужны. State/
  evidence runtime3 absent, ADB/phones/actions=0. Пользователь отдельно закрепил обязательное
  правило: до выдачи любой phone-dependent команды заранее назвать конкретные нужные телефоны и
  предупредить подключить; уточнение о подтверждении см. доп.139. PC-only шаги явно так и называть.
- **2026-08-15 (доп.138)** — local-only runtime3 filter-aware/parser gate PASS на Windows HEAD
  `ed221bd`: committed и filtered working blob оба exact
  `c7cbc2bf7d372db52bc56fc9d88059c68edeffed`; raw CRLF SHA ожидаемо
  `9E3BA002…505AB3`. ParseFile zero errors, install calls=0, launch calls=1, forbidden actions=0;
  runtime3 state/evidence absent, ADB commands/phone actions=0. Старые sync/hash blocks не
  повторять. Следующий шаг требует **только Анну**: непосредственно перед one-shot командой ясно
  предупредить подключить Анну по USB/разблокировать/разрешить debugging; Женю и Стаса не
  подключать. Уточнение о подтверждении см. доп.139.
- **2026-08-15 (доп.139, correction)** — пользователь уточнил правило предупреждения: **не ждать
  его отдельного подтверждения**, что телефон подключён. Норма: непосредственно перед блоком
  назвать конкретный телефон/телефоны и предупредить подключить, затем сразу дать команду. Сам
  блок начинает с read-only exact visibility gate и не доходит до изменения, если device отсутствует,
  unauthorized или offline. Для текущего runtime3 предупредить: нужна только Анна; затем дать
  one-shot wrapper, который сначала read-only проверяет exact Anna `device` вне immutable attempt
  и лишь при PASS один раз вызывает harness. Женя/Стас не нужны.
- **2026-08-15 (доп.140)** — Anna-only r4.4 launch-only runtime3 окончательно **PASS**, не
  повторять. Read-only wrapper увидел exact `AUYF6R5923006121 device`, затем harness один раз
  запустил уже установленную v11.16.15 без install. State
  `%TEMP%\apu-r4.4-dual-v11.16.15-anna-runtime3.json`, SHA-256
  `3375CA6B351D82CA4B5B4B2C6E2961E7CC79A9085437A8042486599710CCDC3B`; install=false,
  launch=true, stable new PID=12943. Metrics: secondary connected/ready=1/1,
  backoff/stopped=0/0; primary ready/heartbeat=1/1, reconnect/errors=0/0; fanoutTwo=6,
  duplicateDrops=3, Hive ingress/duplicate=21/2, EMQX ingress/duplicate=0/1; stalls/restarts/
  request errors/timeouts/secondary errors/inbox invariant — все 0. EMQX duplicate=1 при ingress=0
  означает ожидаемый shared admission: копия дошла через EMQX и была подавлена до core после уже
  принятой HiveMQ copy. Zhenya/Stas touched=false, user payload=false. Это закрывает single-phone
  public low-volume r4.4 runtime gate: оба sessions ready, bounded max2 fanout, cross-broker dedup,
  stable heartbeat/PID и common HiveMQ path без wire change. Следующий отдельный gate —
  mixed-version 3-phone N↔N-1/relay acceptance; перед его phone-командой предупредить подключить
  Анну, Женю и Стаса, отдельного подтверждения не ждать.
- **2026-08-15 (доп.141)** — пользователь сообщил, что Анна, Женя и Стас подключены. Перед
  дальнейшей phone-командой всё равно явно предупредить, что нужны все три. Добавлен versioned
  read-only `scripts/r44_mixed_preflight1.ps1`: exact runtime3 PASS state/hash, branch/application/
  E6C3 native guards, exact three serials, live UID/version/firstInstall/dataDir/one-PID snapshot.
  Expected matrix: Anna v11.16.15/code11016015 UID10425; Zhenya v11.16.13/code11016013 UID10395;
  Stas v11.16.13/code11016013 UID10387. Distinct immutable state/evidence; no install/launch/
  force-stop/log clear/network/user payload. Correct raw ExitCode availability semantics. Static
  delimiter, exact serial/version, parent, 0-install/0-launch, forbidden-action, automatic-variable,
  one-shot/state checks PASS; script LF SHA-256 `C4BAF05E…81935F`. Следующий gate — Windows sync,
  ParseFile/static exact and execute preflight1 once. Это только чтение телефонов, не acceptance
  traffic; при incomplete не повторять автоматически.
- **2026-08-15 (доп.142)** — mixed preflight1 sync/ParseFile PASS и read-only attempt завершился
  `INCOMPLETE_DO_NOT_REPEAT`, state `%TEMP%\apu-r4.4-mixed-v15-v13-preflight1.json`, SHA-256
  `FBD7A04551572762E2FD1FC95A39E10723DE21F7D06ED8F43C1BE357A78E44CE`. Все 3 serial имели exact
  `device`; Anna exact v11.16.15/code11016015 PID12943 PASS. Stop на Zhenya identity/process gate;
  state не сохранил failing snapshot, потому что harness добавлял snapshot только после expectation
  checks; Stas snapshot ещё не выполнялся. Phone changes/user payload=false, install/launch calls=0.
  Preflight1 не повторять и evidence не менять. Следующий шаг только PC saved-only analyzer exact
  parent state/hash + raw Zhenya UID/package/process evidence, без ADB. General harness rule: сначала
  записать observed snapshot в state, затем применять expected gate, чтобы incomplete state не терял
  причину; preflight2 после анализа обязан это исправить.
- **2026-08-15 (доп.143)** — добавлен saved-only
  `scripts/r44_mixed_preflight1_analyze.ps1`: exact parent FBD7…E44CE, 14 required ADB/Anna/Zhenya
  raw evidence files + hashes, empty stderr/semantic package/process parsing, exact Anna recheck,
  explicit Zhenya mismatch list и proof, что Stas snapshot не начинался. Analysis state distinct;
  ADB/phone changes/user payload/retry=false. Static delimiters, parent/evidence count, no-ADB,
  no-action, no-auto-variable и marker checks PASS; LF SHA-256 `9D7CBFAD…98B337`. Следующий шаг
  PC-only: sync/ParseFile и analyzer once; телефоны не нужны. Только после exact mismatch решать,
  нужен ли corrected read-only preflight2 или разрешённый запуск stopped old-version app.
- **2026-08-15 (доп.144)** — preflight1 saved analyzer Windows ParseFile/execution **PASS**, state
  `%TEMP%\apu-r4.4-mixed-v15-v13-preflight1-analysis.json`, SHA-256
  `ED0A3850AE2F5A381F3D73FD32476CBFCADDB47006583760CBE0A51F8C74D2C0`. ADB/phone changes=0.
  Exact причина: Zhenya identity полностью правильная v11.16.13/code11016013, но saved process
  output empty, `ProcessCount expected=1 actual=0`; Anna повторно exact v11.16.15 PID12943; Stas
  snapshot не начинался. Это readiness condition, не compatibility/product failure. Не запускать
  Женю вслепую до corrected read-only preflight2: он должен сохранить observed snapshot **до**
  expected checks, проверить identities всех трёх, дочитать Стаса и допускает dynamic process count
  0 или 1, чтобы точно определить минимальный список необходимых controlled launches.
- **2026-08-15 (доп.145)** — добавлен corrected read-only `scripts/r44_mixed_preflight2.ps1`:
  exact runtime3/preflight1/analysis hash+contract guards, all 3 serial/identity snapshots, но
  process count принимается dynamic 0 или 1. Критическое исправление: каждый parsed observed
  snapshot записывается в `$Snapshots` **до** identity/process-shape gate, поэтому incomplete не
  потеряет failing values. State показывает connected/running counts и минимальный список будущих
  launches можно вывести без догадок. Install/launch/network/log clear/user payload отсутствуют;
  raw ExitCode availability preserved. Static parent/path/serial, ordering, dynamic-shape,
  0-install/0-launch, forbidden, automatic-variable и marker checks PASS; LF SHA-256
  `6522B5B4…896A3C`. Следующий gate — sync/ParseFile и один preflight2; перед командой предупредить,
  что нужны подключённые Анна, Женя и Стас. Это read-only, preflight1 не повторяется.
- **2026-08-15 (доп.146)** — corrected mixed preflight2 Windows ParseFile/execution **PASS**,
  state `%TEMP%\apu-r4.4-mixed-v15-v13-preflight2.json`, SHA-256
  `3E02E3A438D9D79F804FA4A94970618E56D5608507275AC6B5A2FA6D46D0AD90`. Connected=3/3,
  running=2/3: Anna v11.16.15 PID12943, Zhenya v11.16.13 stopped (PID empty), Stas v11.16.13
  PID23149. All identities/data exact; phone changes/user payload=false. Значит минимальное
  необходимое действие перед mixed acceptance — один controlled launch только Zhenya, без install;
  Anna/Stas не relaunch.
- **Cleanup rule (уточнение пользователя, 2026-08-15):** не оставлять бесконтрольно временный
  мусор, но и не удалять active immutable evidence. Во время незакрытого gate все referenced
  `%TEMP%` state/evidence и authoritative APK/native сохранять: следующие harness сверяют hashes,
  а удаление уничтожит audit/recovery. Versioned `scripts/*.ps1` — часть репозитория, не temp.
  Generated E6C3 `.so` сохранять вне commit до завершения этапа. После закрытия mixed acceptance
  сделать отдельную read-only inventory временных файлов и предложить bounded cleanup только
  действительно ненужных intermediate logs/states; итоговые PASS manifests/artifacts сначала
  сохранить по принятой evidence/milestone процедуре. Не удалять evidence автоматически или без
  явного согласования состава.
- **2026-08-15 (доп.147)** — подготовлен versioned
  `scripts/r44_mixed_zhenya_launch1.ps1`: exact preflight2 state/hash, source/E6C3/worktree guards,
  exact three-device/identity/PID pre-gate; Zhenya обязана быть stopped, Anna PID12943 и Stas
  PID23149 не меняются. Ровно один `am start -W` только serial Zhenya, install=0; затем 15+135 s
  current-PID/launch-epoch filtered HiveMQ readiness (ConnAck/subscription/ready/incoming/heartbeat),
  recovered-after-last-error rule, stable all-three final PIDs, zero overflow/backpressure/invariant/
  stalls/restart/request failure. No force-stop/log clear/network/user payload; distinct immutable
  state/evidence, no automatic retry. Static delimiter/parent/serial, 0-install/1-launch exact target,
  no Anna/Stas launch, 150 s, raw-exit, forbidden/auto-var/payload/state/marker checks PASS; LF
  SHA-256 `942523F0…1DD38A`. Следующий gate — sync/ParseFile/once. Непосредственно перед командой
  предупредить: подключены и разблокированы должны быть Анна, Женя и Стас; изменяется только запуск
  APU на Жене.
- **2026-08-15 (доп.148)** — Zhenya launch1 sync/ParseFile PASS, но live pre-action guard увидел,
  что Zhenya уже не stopped, и корректно остановил attempt **до launch**. State
  `%TEMP%\apu-r4.4-mixed-v15-v13-zhenya-launch1.json`, SHA-256
  `DCC2C15E8FF7BD03656851417B3E85F52B1F36ABCD07B78FD0D15EDA9216D0A4`, outcome
  `INCOMPLETE_DO_NOT_REPEAT`, launchStarted=false, new PID/metrics empty, install/user payload=false,
  Anna/Stas launched=false. Это precondition race/desired state reached между snapshots, не product
  failure; guard предотвратил лишний relaunch. Launch1 не повторять. Сначала PC-only exact state/raw
  evidence analyzer должен извлечь Zhenya pre PID, проверить Anna12943/Stas23149 и доказать отсутствие
  launch epoch/command evidence; затем observe-only current-PID HiveMQ readiness без launch.
- **2026-08-15 (доп.149)** — добавлен saved-only
  `scripts/r44_mixed_zhenya_launch1_analyze.ps1`: exact parent DCC2…6D0A4, expected evidence set
  ровно 20 pre-action files (adb devices + UID/package/process для 3 phones), hashes/empty stderr,
  exact identities и Anna/Stas PIDs, one numeric observed Zhenya PID. Любой launch/start/final/epoch
  evidence делает analysis fail. Distinct state; analyzer ADB/actions=false. Static delimiter,
  parent/evidence, no-ADB/no-action, launch=false, all identities/auto-var/marker checks PASS; LF
  SHA-256 `7965BD79…2A0E58`. Следующий шаг PC-only sync/ParseFile/analyzer once; телефоны не нужны.
- **2026-08-15 (доп.150)** — Zhenya launch1 saved analyzer Windows ParseFile/execution **PASS**,
  state `%TEMP%\apu-r4.4-mixed-v15-v13-zhenya-launch1-analysis.json`, SHA-256
  `5325CDB85CC6A3D88019270BFCE1E7ACDDD8E56A18FF17CEE89F3F1FBB21CAF4`. Evidence exact 20
  pre-action files; launch command=false, analyzer ADB/actions=0. PIDs: Anna12943, Zhenya14811,
  Stas23149. Значит все 3 apps уже running, никакой controlled launch больше не нужен. Следующий
  gate — distinct observe-only current-PID window для Zhenya14811: baseline device epoch, 15+135 s,
  new heartbeat/incoming HiveMQ evidence и all-three PID stability, без startup-marker требования,
  launch/install/payload=0. Launch1 и analyzer не повторять.
- **2026-08-15 (доп.151)** — добавлен `scripts/r44_mixed_zhenya_observe1.ps1`: exact analyzer
  5325…1CAF4, source/E6C3/worktree and exact three PID/identity guards; no install/launch. После
  Zhenya device epoch наблюдает PID14811 15+135 s, требует новый healthy polling heartbeat с
  connacks>0/pending=0/request counters=0, incoming HiveMQ publish>=1, recovered-after-last-error,
  zero overflow/backpressure/invariant/stall/event-loop-end/restart/request failure и финальную
  стабильность Anna12943/Zhenya14811/Stas23149. Startup ready marker необязателен, потому что PID
  начался до окна. Distinct state/evidence; no network/log clear/payload/retry. Static parent/PIDs,
  0-install/0-launch, 150 s/epoch/heartbeat/incoming, raw-exit/forbidden/auto-var/state/marker PASS;
  LF SHA-256 `309A7511…83C1DC`. Следующий gate sync/ParseFile/once; перед командой предупредить, что
  нужны подключённые Анна, Женя и Стас, но команда только читает и ждёт.
- **2026-08-15 (доп.152)** — Zhenya observe1 Windows ParseFile/execution **PASS**, state
  `%TEMP%\apu-r4.4-mixed-v15-v13-zhenya-observe1.json`, SHA-256
  `90BC69ABFB5E5BE0D1A37623C13324F0D276402290AF5E6C4B385BDB78886B5F`. PID14811 stable over
  150 s; Anna12943/Stas23149 also stable. New-window metrics: incomingPublish=48, heartbeat=1,
  pollErrors/overflow/backpressure/invariant/stalls/eventLoopEnded/channelClosed/restart required/
  scheduled/failed/request timeouts/errors=0. sessionReady/reconnect=0 ожидаемо для процесса,
  начавшегося до observation epoch; healthy polling heartbeat с connacks>0 подтверждён harness.
  Install/launch/phone changes/user payload=false. Common HiveMQ N↔N-1 readiness gate закрыт;
  observe1 не повторять. Следующий отдельный gate — controlled mixed-version delivery N→N-1 и
  N-1→N, затем third-phone relay другого version; сначала audit existing delivery/relay harness и
  определить минимальный test payload/identity evidence без ложных UI/ACK.
- **2026-08-15 (доп.153)** — перед controlled delivery добавлен read-only
  `scripts/r44_mixed_identity1.ps1`: exact observe1 90BC…86B5F, source/E6C3/worktree, three
  serial/version/PID guards; baseline epoch на каждом и 135 s presence window. Для каждого current
  PID сохраняет full/fresh p2p logs, получает own NodeId из startup marker либо безопасным
  three-peer set-difference fallback, требует 3 unique safe `pk_…` и чтобы каждый телефон свежо
  увидел NodeId двух остальных. Это одновременно доказывает common-HiveMQ full mesh и даёт точные
  адреса для двух one-shot relay deliveries. No install/launch/log clear/network/payload/injected
  traffic. Static parent/PIDs/wait, 0-install/0-launch, peer/node parsing, raw-exit/forbidden/auto-var/
  payload/marker PASS; LF SHA-256 `BFFF7EDC…D17AAD`. Следующий gate sync/ParseFile/once; перед
  командой предупредить, что нужны подключённые Анна, Женя и Стас, но шаг read-only.
- **2026-08-15 (доп.154)** — identity1 Windows ParseFile/execution **PASS**, state
  `%TEMP%\apu-r4.4-mixed-v15-v13-identity1.json`, SHA-256
  `ED16F594A36E388B7ABED0172FAD5AE43839F72B8167BBED5BEB55FF94EF5435`. Exact NodeIds:
  Anna=`pk_591a15c0f5d659ebbb407bd377214ecc`, Zhenya=`pk_9f43c5971a820d4f6bc5dc4f4dca4f8b`,
  Stas=`pk_7dc6b7c52ae086094e7b367b4df5bd0c`; каждый fresh peer set содержит ровно два остальных.
  Phone changes/injected traffic=false. На основе этого добавлен первый controlled delivery
  `scripts/r44_mixed_delivery_n_to_n1.ps1`: один QoS1 non-retained HiveMQ relay, Anna N origin →
  Zhenya N-1 recipient, Stas N-1 third relay. Ожидается exact relay/store/local/receipt/remove/
  origin/UI matrix: Anna 1/1/0/1/1/1/0, Zhenya 1/0/1/1/0/0/1, Stas 1/1/0/1/1/0/0; no errors,
  all PIDs stable, receipt cleanup. Это **один видимый controlled test message** на Жене и один
  public publish; не user-generated content. Install/launch/network/log clear отсутствуют.
  Correct raw-exit + filter-aware exact publisher helper blob (не raw SHA, чтобы не повторить
  CRLF/LF false fail); static parent/IDs/direction, 0-install/0-launch, one external publish,
  expected matrix, forbidden/auto-var/state/marker PASS; final LF SHA-256 `E27C4A72…72493C`.
  Перед командой явно предупредить о 3 phones и одном тестовом сообщении;
  automatic retry запрещён. Reverse direction только отдельным следующим gate после PASS.
- **2026-08-15 (доп.155)** — первый Windows launcher к N→N-1 delivery stopped на `git fetch`:
  GitHub:443 недоступен ~21 s. Stop до reset/ParseFile/harness/ADB/Python/publication; delivery state/
  evidence отсутствуют, phones unchanged. Windows HEAD остаётся `8cea566`; старый большой launcher
  целиком не повторять. Следующий network step PC-only: TCP443 + exact fetch `d650499`, затем local
  adoption/parser/delivery отдельно. Пользователь также передал новое neon speech-bubble/P2P-mesh
  artwork для APU icon. Решение: сохранить immutable original в
  `design/branding/app-icon/source/apu-icon-original.png`, затем square master/adaptive background+
  foreground/round/monochrome/legacy exports по `design/branding/app-icon/README.md`. Не менять
  Android resources посреди r4.4: это создаст новую APK и потребует artifact/upgrade/launcher
  regression. Attachment виден в чате, но injected `/home/user/uploads/1786786331.png` фактически
  отсутствовал в filesystem; не генерировать неточную копию, попросить повторно прикрепить original.
- **2026-08-15 (доп.156)** — пользователь положил exact icon original в Windows clone:
  `C:\APUMIR-arena-test\design\branding\app-icon\source\apu-icon-original.png`. Read-only PNG
  check PASS: 1,980,451 B, 1664×928, `Format24bppRgb`, SHA-256
  `F2638C88A3EAB243766B8F4755183C89A3E1FFCB72B45A0BBC5F3D398C83ACA9`; Windows status = E6C3
  `.so` modified + `?? design/`. Это landscape/no-alpha source: сохранить byte-exact, derived square
  assets отдельно. Добавлен `design/branding/app-icon/SOURCE_PROVENANCE.md`; rights confirmation
  остаётся release gate. Чтобы перенести binary агенту, сначала PC-only network fetch latest branch
  без reset; затем отдельный local adoption, допускающий exact untracked icon, и commit/push только
  original. Не делать `git clean`, stash/pop или broad add. Android mipmaps пока не менять.
- **2026-08-15 (доп.158)** — isolated Windows network diagnosis окончательно локализовал outage:
  DNS PASS (`github.com` A=`140.82.121.4`), но `Test-NetConnection :443=False`; независимый
  `curl.exe -I` дал zero bytes и timeout `(28)` через 15.011 s. Git fetch/reset/add/commit/ADB/
  publication=false; icon F263…ACA9 и native E6C3…095B unchanged; status ровно `.so` modified +
  exact untracked icon. Это external TCP path, не repository/config failure. Не менять DNS/proxy/
  firewall вслепую и не повторять fetch немедленно; подождать восстановления. Телефоны пока можно
  отключить, следующий phone step снова потребует явного предупреждения о трёх устройствах.
- **2026-08-15 (доп.157)** — Windows icon network block подтвердил exact `.so` + original icon,
  `Test-NetConnection github.com:443=True`, но немедленный `git fetch` снова timed out через ~21 s.
  Stop до reset/add/commit/ADB/publication; Windows HEAD остаётся `8cea566`, icon остаётся exact
  untracked F263…ACA9, `.so` E6C3…095B. Это intermittent GitHub path (ранее fetch на том же clone
  работал), не повод менять proxy/DNS/Git config или повторять большой block. Не делать `git clean`:
  untracked icon безопасно переживёт fetch/mixed reset. Следующий шаг PC-only diagnostic без fetch:
  DNS/TCP/curl + read-only proxy/config report; затем подождать и сделать ровно один isolated fetch.
- **2026-08-15 (доп.159, новый приоритет пользователя)** — пользователь потребовал прекратить
  отвлечения/многоступенчатые разрешения и немедленно перейти к реальной офлайн-доставке в app.
  Это явное решение начать M3(d) до незавершённого r4.5 local matrix; mixed/r4.5 остаются release
  acceptance, но больше не блокируют source implementation. Icon заморожен после сохранения source.
  Обычные code/static/commit шаги выполнять без дополнительных вопросов; спрашивать только перед
  destructive phone action/install/release/tag.
- **M3(d) automatic offline send-path source complete, build pending:** добавлен pure bounded
  `network/offline_send.rs`: delimiter/length/64 KiB gates, origin `RelayMessage` hop0/TTL7d и exact
  старый `relay|...` encoding для N-1, 3 tests. `P2PCore` теперь владеет bounded mpsc cap256 к
  persistent MQTT task; offline/direct-failed send сначала dedup+enqueue origin RelayQueue, один раз
  кладёт legacy LAN retry, затем `try_send` command. MQTT loop bounded-drain max32 и вызывает
  existing `send_mesh_relay` (r4.4 fanout max2/non-retained); full/closed/session error сохраняет
  relay локально и не заявляет SENT. Rust offline result=false + `queued_offline`; fake
  `NetworkManager.send_message().is_ok()` удалён. Kotlin убрал transient per-message MQTT и CF inbox
  content fallback; Room ставит `QUEUED_OFFLINE`/`STORE_FORWARD`, retry queries включают queued,
  direct QUIC only → SENT. Wire/Room schema unchanged; Cloudflare receive compatibility пока есть,
  legacy transient ACK остаётся отдельным debt. Static source checks PASS. Первый internal delimiter
  scanner false-failed на Rust lifetime `'static`; corrected scanner treats lifetimes correctly,
  source не менялся из-за false check. Следующий обязательный gate — Windows
  `build-rust.ps1 -Features mqtt-dual-broker`, затем Kotlin/APK compile и один combined 3-phone
  offline acceptance; host cargo test запрещён.
- **2026-08-15 (доп.160)** — добавлен versioned `scripts/m3d_offline_send_build.ps1` для exact app
  source `61e1580`: C-drive/r4.4 integration/E6C3 baseline, source static contract, one bounded child
  `build-rust.ps1 -Features mqtt-dual-broker`, 900 s timeout, Finished/feature/error/native/status
  gates, distinct state/logs. APK/ADB/phones/public traffic=false. Static Parse-shape, source/baseline/
  feature, one build, no Gradle/ADB/phone action, bounded wait/state/marker/auto-var PASS; final LF
  SHA-256 `4870BDB2…CECD3E`. Harness теперь явно допускает только exact untracked icon
  F263…ACA9 рядом с modified native, проверяет её до/после и не требует icon commit для сборки.
  Этот compile gate выполнить сразу после восстановления Windows GitHub; дополнительных разрешений
  не спрашивать, перенос icon в Git можно завершить после основного offline gate.
- **2026-08-15 (доп.161)** — пользователь запустил bare `./build-rust.ps1 -Features
  mqtt-dual-broker` из `C:\Users\User`; PowerShell дал CommandNotFound до script/build. Причина —
  отсутствовал обязательный `Set-Location C:\APUMIR-arena-test`; source/native/phones unchanged.
  Не считать compile attempt и не создавать recovery state. Больше не показывать короткую build
  строку отдельно так, чтобы новичок мог принять её за готовую команду: давать только полный
  guarded block с Set-Location после exact fetch/adoption.
- **Глобальный connectivity-инвариант пользователя (2026-08-15):** APU обязан при прямом
  соединении автоматически использовать все доступные законные пути при NAT/блокировках/strict
  mobile whitelist: QUIC/TCP/TLS443/WSS/H2/H3/WebRTC ICE-TURN, user proxy, consented app VPN/
  WireGuard/MASQUE, signed own bridge, phone mesh, local radios и pluggable transports. Подробный
  обязательный Phase 0.6 добавлен в MASTER_PLAN. При нулевом разрешённом общем endpoint обещать
  bypass нельзя: truthful restricted status + phone Outbox/retry, без ложного SENT. Random public
  proxies, hidden VPN и unsupported domain fronting запрещены.
- **2026-08-15 (доп.162, superseded transfer route)** — создан и проверен incremental Git bundle
  `/home/user/APU-M3d-offline-send.bundle`, 38,526 B, SHA-256
  `D95CF88AD76FCE34FE271F0CA7E15DA5D395E285BCCEF5A09140F76BE846AF1F`; prerequisite Windows
  HEAD=`8cea566…`, `git bundle verify` PASS. Но Arena file viewer/Download фактически не передал
  файл пользователю: повторный поиск в browser Downloads тоже не помог. Это сбой доступного канала
  передачи, не действие пользователя. Bundle как формат остаётся valid, но этот пользовательский
  маршрут abandoned: не отправлять снова к карточке/Download/Downloads и не утверждать, что файл
  уже находится на Windows.
- **2026-08-15 (доп.163) — проверенный обход передачи без скачивания:** из exact Windows base
  `8cea566` к M3(d) app source `61e1580` создан минимальный Rust-only patch: 23,530 B, SHA-256
  `BCB546D61C01852790FB0EAF7B2BD1BD85A20B3D6DCB5570F1B1CD42DE45F7F9`; deterministic gzip:
  5,862 B, SHA-256 `9087F7C58CD0983FAC07F2B9ECCC89A3B4A1EB26BB9AD0F4CDEE25E06DC84655`, Base64 7,816 chars.
  Payload встроен прямо в один PC-only PowerShell block. Он проверил branch/HEAD, exact исходный
  E6C3 `.so` и icon F263…, восстановил оба файла в `C:\APUMIR-transfer`, проверил их size/SHA,
  выполнил `git apply --check` + apply и проверил blobs четырёх Rust targets. Это канонический
  fallback при недоступных GitHub и Arena download: minimal gzip/Base64 + hashes, не ручная вставка
  исходников. Rust-only overlay намеренно не переносит Kotlin и Git history; Windows HEAD остаётся
  `8cea566`, worktree dirty, Kotlin overlay нужен отдельным следующим шагом до APK.
- **2026-08-15 (доп.164) — M3(d) Android Rust compile PASS:** exact Rust overlay собран через
  `build-rust.ps1 -Features mqtt-dual-broker`. Marker Finished/feature=`1/1`, compiler errors=0;
  новый arm64 `.so` 7,263,416 B, SHA-256
  `27B9D4DC87CA7046D9F862F9ED153FDDD48C26E4053B620FE46986D25D1FD26C`, отличается от baseline
  E6C3…. State `%TEMP%\apu-m3d-rust-direct-build.json`, SHA-256
  `7589A0349386640443039B2C09EB311F269C342473715CB22E23E5314A1716A1`; child PID 8996,
  raw exit недоступен/blank, но exact completion marker, zero compiler errors и regenerated native
  identity дали bounded evidence PASS. GitHub/ADB/phones=false; icon unchanged. Build и state не
  повторять/не удалять. Следующий PC-only шаг: authenticated inline Kotlin-only overlay, затем
  exact source/native/icon gates и APK compile; телефоны только после готового APK и отдельного
  трёхтелефонного visibility gate.
- **2026-08-15 (доп.165) — Kotlin/APK gate подготовлен:** versioned
  `scripts/m3d_kotlin_apk_build.ps1` добавлен commit `dfd36d9`; он не вызывает Rust build/ADB и
  собирает v11.16.16 только после exact Rust state/native/icon и 8 Rust+Kotlin blob gates. C-drive
  JDK17/SDK, one bounded Gradle child, BUILD SUCCESSFUL, package/version, V2 cert F843…, embedded
  native 27B9…, unchanged hs_err/icon/status и env restore входят в PASS. Для Windows создан patch
  из base `8cea566` только по четырём Kotlin files + harness: 34,081 B, SHA-256
  `B84E9F88156C8D4BB6D72B1D42CE7BFE9C00B2F776C77395510F28783FD7BC0A`; deterministic gzip
  9,081 B, SHA-256 `FF688DF96AD270E96B34886E29953E56F265D5C17033FC8696AF13C82FA2138D`, Base64 12,108 chars.
  Disposable exact-base apply/check и 5/5 target blobs PASS; harness blob
  `715483c8a758a967df6e9a17cf2834cfcaaf4572`.
- **2026-08-15 (доп.166) — первый Kotlin inline transfer остановлен до распаковки/apply:**
  wrapper ожидал gzip runner 13,794 B / SHA-256 `E081A906…65332`, но после decode exact gate дал
  `Embedded M3(d) runner gzip failed verification`. Значит сохранённые из чата Base64-байты не
  совпали с подготовленным payload; это transfer-integrity failure, не ошибка пользователя и не
  Kotlin/APU compile failure. По порядку кода stop произошёл до GzipStream, runner ParseFile,
  inner patch, Git apply, Gradle, APK, ADB/phones. Большой block не повторять и созданный transfer
  file не удалять: следующий PC-only шаг только read-only выводит его actual size/SHA-256, наличие
  runner/patch/state paths, exact branch/HEAD/status/native/icon. Диагностика PASS: gzip существует
  и имеет ожидаемый размер 13,794 B, но другой SHA-256
  `6070E0882C7EA87E398F35A2683014001CF9067259400B501CFC892D13CC37A5`; runner/inner patch/transfer
  state/APK state/APK все absent. Branch/HEAD/status, native 27B9… и icon F263… exact. Значит
  изменились байты внутри Base64 при сохранённой длине; source/build/phones не затронуты. Recovery:
  old gzip оставить evidence. Read-only сравнение 14 chunks по 1024 B нашло ровно один mismatch:
  index=2, offset=2048, length=1024, actual SHA-256
  `14794B07FC0D41677B4DB5E26413364AAE5B5337F3D0917892DC8D5D9705BE96`, expected
  `A0F8453468F60AAEC22B081EB3926AB279CAD341AD725BF23FB4E2B06DEBD8A1`; остальные 13 exact.
  Recovery создаёт distinct copy, заменяет только этот authenticated chunk и требует полный
  expected gzip SHA `E081A906…65332` до распаковки/ParseFile/run. Не повторять full payload и не
  ослаблять hash gate.
- **2026-08-15 (доп.167) — single-chunk recovery + M3(d) APK artifact PASS:** authenticated chunk
  A0F8…D8A1 восстановил distinct gzip к exact E081…65332; runner 27,992 B / SHA-256
  CB21…8762 и ParseFile=0. Kotlin patch B84E…BC0A applied, target blobs=5/5. C-drive Gradle build
  PID15684/exit0, BUILD SUCCESSFUL=1 за 70.28s. APK
  `%TEMP%\apu-m3d-v11.16.16.apk`: v11.16.16/11016016, 22,664,712 B, SHA-256
  `446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D`; V2 cert
  F843…A5F7, embedded native 7,263,416 B / 27B9…D26C exact. APK state SHA
  `917077E82C25DFF9A020713BA4A391DD49D7AFD81446367F87A23B17216AFABC`; transfer state SHA
  `A4D8DB1165B1427CB3C23BDB612B183769CE1746D3175C966C17AC880F205154`; recovery state SHA
  `545DC670A643EE017A71E4CC46374764FA891EA810D3AFE099C2E875B685CA98`. Old corrupted gzip kept;
  GitHub/ADB/phones=false. Build/transfer/recovery не повторять. Runtime acceptance pending.
- **2026-08-15 (доп.168) — глобальная growth-цель пользователя:** после launch-readiness APU
  должен быстро и честно распространяться тысячам и десяткам тысяч людей, чтобы они хотели им
  пользоваться и добровольно приглашать друзей. В MASTER_PLAN добавлен Global Stage 2.5 после core
  invite/delivery и до optional feature-completeness: polished brand/onboarding, useful core/media/
  social loop, security/data/update и scale-safe network hard gates; затем waves 50–100 → 300–1k →
  1k–5k → 10k–50k+, privacy-preserving activation/retention/referral/reliability metrics, landing/
  stores/offline share/community channels, support/rollback и stop conditions. Advanced topics,
  channels, Stories, calls, desktop и часть media допустимы после launch; data/key/E2E/update/
  truthful delivery/bounded traffic/basic accessibility/capacity откладывать нельзя. Spam, bots,
  scraped contacts, fake reviews/installs и deceptive ads запрещены.
- **2026-08-15 (доп.169) — пользователь разрешил M3(d) v11.16.16 install 3/3; execution pending:**
  versioned `scripts/m3d_v111616_install.ps1` commit `db5821f` проверяет exact APK/build/transfer/
  recovery states, branch/base HEAD, 8 source blobs, native/icon и expected dirty status. Outer и
  harness требуют exact `device` Anna/Zhenya/Stas; все live UID/version/firstInstall/data checks
  завершаются до первого phone-changing call. Expected preversions: Anna v11.16.15, Zhenya/Stas
  v11.16.13; UID 10425/10395/10387. Ровно один code callsite `adb install -r`, sequential max3;
  post gate требует v11.16.16, UID/firstInstall/data preserved и process stopped. No uninstall/data
  clear/force-stop/launch/logcat/network/public traffic; state finally/no retry. Harness LF raw
  24,537 B / SHA-256 `A3AA5ACA8AE6AA13D7C8B127F3B97225B57B3CA31B41D3746CA0034F0E20CB8F`,
  Git blob `ec05c31127849fa042e59e49696c2b52889ec95c`; deterministic gzip 6,403 B / SHA-256
  `2E4E1F4B40BA72DC3808DA76255153FDFACC1AE1A66D47CD6F5A9D5835CDB3F7`, Base64 8,540 chars.
  Static action/order/state/automatic-variable/delimiter checks PASS; Windows ParseFile/runtime
  pending. Перед командой предупредить подключить/разблокировать все три; outer visibility gate
  выполняется до transfer/apply/install.
- **2026-08-15 (доп.170) — M3(d) v11.16.16 data-preserving install PASS 3/3:** outer read-only
  visibility и versioned harness ParseFile/hash/blob PASS. Pre: Anna v11.16.15 PID12943,
  Zhenya v11.16.13 PID5714, Stas v11.16.13 PID23149; UID=10425/10395/10387 exact. Ровно три
  approved sequential `adb install -r`; post v11.16.16/11016016 3/3, UID, firstInstallTime и
  dataDir preserved, process stopped 3/3. State `%TEMP%\apu-m3d-v11.16.16-install.json`, SHA-256
  `59D5F8EDDBBEF2865CFFF5C48E349514502D6635C6F794CD0DBFDD6908846295`; install calls/verified=3/3.
  Uninstall/data clear/force-stop/launch/logcat/network/public traffic=false. Install/state/evidence
  не повторять/не удалять. Следующий phone-changing gate — отдельно разрешённый one controlled
  launch 3/3, затем readiness evidence; automatic offline scenario только после него.
- **2026-08-15 (доп.171) — пользователь разрешил controlled launch v11.16.16 3/3; execution
  pending:** versioned `scripts/m3d_v111616_launch.ps1` commit `06fa3f1` требует install state
  59D5…6295, exact branch/base HEAD/source/native/icon/status и stopped identity 3/3 до first launch.
  Ровно один launch callsite в loop/count=3; no install/force-stop/logcat clear/network/payload.
  Early15s + late135s captures `p2p_core` by new PID+device epoch; PASS требует stable PID,
  primary ConnAck/subscription/READY, secondary EMQX READY, fanout brokers=2, cross-broker dedup and
  both broker evidence, healthy heartbeat pending/request=0, bounded recovery after any transient
  error/backoff, zero overflow/backpressure/invariant/stall/restart/request failure/crash. State
  finally/no retry. Harness 35,816 B / SHA-256
  `A4AAE5A5EFEFB608C75AD4B5F3CDAF616B78C8B54AF8444635A308D1201A58B9`, blob
  `18cefa2f651f84eaae5eff1b4473046e68ae779d`; gzip 8,111 B / SHA-256
  `50FDE4A1DD301A49D2706D07877F2D312EC7505A229D26B5CCD30889B683EC59`, Base64 10,816 chars.
  Static delimiter/action/order/identity checks PASS; Windows ParseFile/runtime pending. Перед
  command предупредить подключить/разблокировать Anna/Zhenya/Stas; outer visibility is first phone
  interaction.
- **2026-08-15 (доп.172) — M3(d) v11.16.16 controlled launch/readiness PASS 3/3:** state
  `%TEMP%\apu-m3d-v11.16.16-launch.json`, SHA-256
  `6561A0636034B4782605D008C649F20A7D0A72DB014568B84CEF84AB380F84E2`; launch calls=3, wait150s,
  PIDs Anna/Zhenya/Stas=`22055/11575/11449` stable. На 3/3 primary init/EventLoop/ConnAck/
  subscription/READY/heartbeat=1; secondary connected/READY=1; fanoutTwo=8/18/14, duplicateDrops=
  5/7/6, both HiveMQ+EMQX evidence present. Heartbeat pending/backpressure/request timeout/error=0;
  crash/stall/restart/overflow/invariant/channel close=0. Stas имел bounded primary poll_errors=4 и
  secondary backoff=1, но later READY и healthy polling heartbeat доказали recovery; no restart.
  Force-stop/logcat clear/network/user payload=false. Launch/state/evidence не повторять/не удалять.
  Следующий gate — separately approved automatic offline UI scenario Anna origin → Stas offline
  recipient, Zhenya online relay; one unique message, then Anna offline, Stas online delivery,
  receipt cleanup and eventual origin DELIVERED.
- **2026-08-15 (доп.173) — пользователь разрешил automatic offline acceptance; prepare pending:**
  `scripts/m3d_offline_prepare.ps1` commit `5c63d1b` требует launch state 6561…84E2, exact
  branch/base/status, versions/UID/firstInstall/data and PIDs Anna/Zhenya/Stas=22055/11575/11449.
  До network action завершает read-only 3-device preflight и device epochs; затем exact two callsites
  disable Wi-Fi+mobile data only Stas, wait35s, require Stas toggles=0, all PIDs/identity stable and
  Anna/Zhenya fresh MQTT input with no stall/restart. Generates short unique manual UI text in state
  but does not send it. State finally records original network settings for later exact restore.
  No install/launch/force-stop/log clear/data clear/synthetic publish/retry. Harness 18,936 B /
  SHA-256 `6C405BEAC52642D8CF3400AF4EBCEE5A3F54F7455C16820913A27FB9B0029693`, blob
  `b5d47d55df3d7c27ea85dbb8d0c8d8ab3563e51b`; gzip 5,170 B / SHA-256
  `945A9776DBF32887D49B4138C8B1CF4DFB17062F3825776EC2019D2F30D1712C`, Base64 6,896 chars.
  Static action/order/state/delimiter checks PASS; Windows ParseFile/execution pending.
- **2026-08-15 (доп.174) — M3(d) prepare consumed/incomplete; manual success report + read-only
  capture:** approved prepare executed once; outer 3/3 visibility, payload/blob/parser/preflight PASS,
  then exact Stas Wi-Fi/data disable callsites ran. State
  `%TEMP%\apu-m3d-offline-acceptance-prepare.json`, SHA-256
  `0FCA3B35B5887C3F56C3A5D0BB23EA5F370200EA7CBDB22132693B082469607A`, outcome
  `INCOMPLETE_DO_NOT_REPEAT`, text `M3D-OFFLINE-1786800490`: post-toggle predicate found Stas not
  fully offline, so no user message/install/launch/force-stop/log clear/data clear. Пользователь
  вручную закончил сценарий и сообщает successful delivery. Separate read-only capture state
  `%TEMP%\apu-m3d-manual-offline-capture.json`, SHA-256
  `27D9B17773349E36060D869A866DA73B2EE20DA423B9F3323A16BC025238C98A`: PIDs 22055/11575/11449
  stable; settings Anna 1/1/0, Zhenya 1/0/0, Stas 0/1/0 (Wi-Fi/data/airplane); no crash/ANR.
  Exact text и searched M3(d) origin/relay/receipt/delivery markers all zero, поэтому capture не
  доказывает exact message-ID chain. Оба state/evidence immutable; не повторять, телефоны не менять.
- **2026-08-15 (доп.175) — delayed multi-carrier custody is hard requirement / current gap:**
  Anna→offline Stas may be stored by Zhenya; after a day Zhenya must hand custody to relay D during
  a separate overlap, then D later delivers to Stas exactly once while Anna/Zhenya remain offline.
  Source inspection confirms `gsumm`/bounded missing-relay forwarding, TTL=7 days, max hops=8,
  per-recipient=200/global=10000, but `RelayQueue` is only process-RAM `Mutex<HashMap<...>>` with
  `Instant`. Поэтому chain сейчас возможна лишь пока custody processes survive; process death/reboot
  теряет relay. Before claiming scenario: M8 encrypted persistent storage, absolute timestamps,
  startup recovery/expiry, tombstones/receipts, bounded background participation and controlled
  non-overlapping-window + restart/reboot acceptance. No common transport overlap means no handoff;
  UI must honestly retain Outbox/restricted state, never promise physically impossible transfer.
- **2026-08-15 (доп.176) — relay sleep/wake requirement + authorized checkpoint release:** если
  custody не удалось передать, relay атомарно сохраняет encrypted envelope перед sleep. На каждом
  network/app/periodic wake сначала restores old queue, затем bounded summary/missing-ID exchange
  запрашивает eligible новые relay items, attempts old+new delivery и снова durable сохраняет всё
  недоставленное без TTL reset/busy-loop; transient error never deletes custody. Пользователь выбрал
  GitHub **prerelease v11.16.16** и backup на Windows **F:**. Stable release не выбран. Exact verified
  APK: 22,664,712 B, SHA-256
  `446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D`. Tag-triggered Actions
  auto-build запрещён для этого checkpoint: tracked arm64 `.so` 6,935,552 B / `3C5E0D64…B2832`
  отличается от embedded verified `.so` 7,263,416 B / `27B9D4DC…D1FD26C`. Создать draft prerelease
  first, upload exact Windows APK+sha256, verify remote asset hash, then publish; release notes must
  state manual functional success but missing exact runtime chain proof and M8 not implemented.
- **2026-08-15 (доп.177) — v11.16.16 draft prerelease created; exact asset/flash backup pending:**
  release prep commit `85aecb0`, notes `docs/RELEASE_NOTES_v11.16.16.md`, backup/upload harness
  `scripts/v111616_prerelease_backup_upload.ps1` 16,645 B / SHA-256
  `DE5C3303EDBA3CFE02806959BB14F4E0D64B7EC65BB067356B228B2DDC8C3014`; deterministic gzip
  4,679 B / SHA-256 `63C607040CE986340D54A3D87B94101565079C74FE8398C9DB7916C5CD6CBEC2`, Base64 6,240 chars.
  Draft `v11.16.16` isDraft/isPrerelease=true, target full commit
  `85aecb0fa9893184e357b6c565869d0f1ebd69b7`, assets empty, not published and no tag/Actions build.
  First `gh release create --target 85aecb0` failed safely HTTP 422 because API rejected abbreviated
  target; no release/tag created. Retry with full 40-char commit created draft successfully. Next:
  authenticated inline harness on Windows (no phones/build/install) verifies exact APK, copies full
  C: workspace + all `%TEMP%\apu-*` evidence + git bundle/patches/APK to F:, then if Windows `gh` is
  authenticated uploads APK+sha256 to draft and re-downloads/hash-verifies. Publish only after remote
  asset exact hash verification; if gh/fetch unavailable, backup remains complete and upload pending.
- **2026-08-15 (доп.178) — v1 backup stopped before F: copy; offline-only v2 prepared:** authenticated
  transfer gzip/raw/parser PASS, then first network operation `git fetch` failed after ~21s:
  `Failed to connect to github.com:443`. Because PS5 EAP=Stop + `2>&1` surfaced native stderr as a
  terminating `NativeCommandError`, intended nonfatal branch did not run. No phones/build/install;
  script order proves no F: directories/copy/upload before fetch. v1 state, if written at
  `%TEMP%\apu-v11.16.16-prerelease-backup.json`, must be preserved; do not rerun v1. Draft remains
  empty/unpublished. Corrected `scripts/v111616_prerelease_backup_upload_v2.ps1` is offline-only:
  deliberately no fetch/gh retry, uses Start-Process stdout/stderr capture for local git diff/bundle,
  verifies APK, copies full exact workspace + all prior `%TEMP%\apu-*` (including v1 state/evidence)
  + APK/hash/patches/bundle/manifest to same F: backup root, and records v2 state separately. Harness
  16,436 B / SHA-256 `2E5CCAD547B23A91B973188FFED6EA73DB85FE11B61316B3094B95B1E260F82E`;
  deterministic gzip 4,727 B / SHA-256
  `1BBF74DDD18E31144937C7BD10474D8B5C5B93DD09B6DA72C33217317195BA95`, Base64 6,304 chars.
  Expected end: `BACKUP COMPLETE ON F:; NETWORK RETRY SKIPPED; DRAFT UPLOAD PENDING`. Only after
  local backup PASS may a separate bounded upload be attempted when GitHub connectivity is available.
- **2026-08-15 (доп.179) — v2 transfer rejected before write; shorter v3 patch prepared:** copied v2
  Base64 was malformed; authenticated wrapper failed at `FromBase64String` with invalid length before
  decompression, `WriteAllBytes`, ParseFile or v2 execution. Therefore no v2 state/F: mutation; this
  is transfer-channel corruption, not user error. Do not repeat long v2. Existing v1 script remains
  exact 16,645 B / `DE5C3303…C3014`. New authenticated patcher
  `scripts/v111616_offline_v3_from_v1.ps1` verifies that v1 hash, applies 8 unique exact replacements,
  verifies target hash and ParseFile, then executes `scripts/v111616_prerelease_backup_offline_v3.ps1`.
  Patcher 6,867 B / SHA-256 `763F8DAC979493450BEB3CEFCB419F50AFBFF910F450554C1ED67BF1A3077330`;
  deterministic gzip 2,874 B / SHA-256
  `1C4E80631F24C17704A416F238FE7DBF740D1CB23FD51DE9B2DCBBE4C7D0171B`, Base64 3,832 chars.
  Target v3 16,653 B / SHA-256
  `74F098F1BC76871B916C30EA32722BA914C42C5C68E2FAE449099505ADFF10A8`, separate v3 state; fetch is
  replaced by fixed offline warning before execution, and backup exits before unreachable legacy gh
  branch. Local reconstruction test proves 8 replacements produce exact target bytes/hash. Expected
  terminal marker remains `BACKUP COMPLETE ON F:; NETWORK RETRY SKIPPED; DRAFT UPLOAD PENDING`.
- **2026-08-15 (доп.180) — v3 stopped before F: copy; mandatory two-version v4 prepared:** v3
  transfer/source/target/parser all PASS, then `Get-TreeBytes` failed under StrictMode reading `.Sum`
  for an empty `%TEMP%\apu-*` directory. Order proves no F: directory/copy yet; v3 state at
  `%TEMP%\apu-v11.16.16-prerelease-backup-v3.json` must be preserved and v3 not repeated. User added
  permanent flash policy: minimum old+new APK, with an explicit label identifying latest. v4 uses an
  explicit file-length loop safe for empty trees and requires exact signed previous v11.16.15
  22,664,716 B / `B675770D…3E9B2` plus latest v11.16.16 22,664,712 B / `446A1EE9…DC0D`.
  Both build states/hash/content must prove V2 signer cert `F843CBE7…A5F7`. F: layout adds
  `versions/previous-v11.16.15`, `versions/latest-v11.16.16`, per-APK `.sha256`, `LATEST.txt` and
  `LATEST.json`; critical manifest includes all. Target
  `scripts/v111616_prerelease_backup_offline_v4.ps1` 23,553 B / SHA-256
  `70BDDC913F52048312CA749331505D208054582903D2D98BA785EB308A565648`. Authenticated zero-context
  patch `scripts/v111616_offline_v4_from_v3.patch` 8,811 B / SHA-256
  `007CDD0ECC86A6F9FA20ADFDAFD4E80F2B453BDE55E908BD6F6D0A31D838C096`; deterministic gzip
  2,364 B / SHA-256 `193D5D7A242A0B4F2AB7129304F3844E036055B3086AD9340F07E4CC1727A7E8`, Base64 3,152 chars.
  Local `git apply --check --unidiff-zero` + apply reconstruction produced exact v4 target hash.
  Expected terminal marker: `PREVIOUS v11.16.15 + LATEST v11.16.16 VERIFIED; DRAFT UPLOAD PENDING`.
- **2026-08-15 (доп.181) — v4 full copy stopped by user; F: format authorized and gated:** zero-context
  patch source/compressed/patch/apply PASS but Windows `git apply` rewrote target as 502 CRLF lines:
  24,055 B / `87FC4D99…9954`; read-only normalization gave exact LF 23,553 B /
  `70BDDC91…5648`. Normalization/write/ParseFile PASS; v4 then started workspace robocopy and created
  partial `F:\APU-backup-2026-08-15-v11.16.16\metadata\robocopy-workspace.log`. Пользователь
  остановил command (`Ctrl+C`), prompt returned; v4 не повторять. Correction: portable clean backup,
  not full caches/evidence. User explicitly authorized complete F: format. Read-only exact gate PASS:
  label SMARTBUY, FAT32, healthy, 14.62 GiB/free13.34, DiskNumber2, FriendlyName `General UDisk`,
  BusType USB, MBR, DriveType2, IsSystem=false, IsBoot=false, SafeToFormat=true. Versioned destructive
  harness `scripts/v111616_flash_format_v1.ps1` binds all those identities and size 14–16 GiB,
  rejects other mounted partitions, has exactly one `Format-Volume` call to exFAT/`APU_BACKUP`, then
  verifies physical identity, filesystem/label and empty root. Harness 6,231 B / SHA-256
  `A0FBC37C960F24D08A596DBDE7DCC5BA172E707BC709089BFA9C883BBF1B86F7`; execution pending. It writes
  `%TEMP%\apu-v11.16.16-flash-format-v1.json`; after PASS compact copy is separate. Compact scope:
  source/Git bundle + exact working overlays/signing material + restore/bootstrap instructions + two
  signed APKs/hashes/latest marker; exclude `.git` duplication, build/.gradle/target/caches/logs and
  bulk runtime evidence; new PC downloads Java/Android/Rust dependencies from internet.
- **2026-08-15 (доп.182) — F: format PASS; authoritative compact flash runbook added:** elevated
  authenticated harness transfer/raw/ParseFile PASS; exact pre-format identity remained Disk2
  `General UDisk`/USB/MBR/FAT32 `SMARTBUY`, non-system/non-boot, no additional mounted partition.
  Exactly one approved `Format-Volume` call completed. Post: F: exFAT, label `APU_BACKUP`, same
  physical identity, empty root. State `%TEMP%\apu-v11.16.16-flash-format-v1.json`, SHA-256
  `A75443F8D302B8D856237F63C2122ABA4C676A6456078066125EF1455E1FACFF`, outcome
  `PASS_FORMATTED_EMPTY`; do not repeat/delete. No phones/build/APK changes. User requires future AI
  never repeat full recursive workspace/cache/evidence copy. New authoritative
  `docs/FLASH_BACKUP_RUNBOOK.md` must be read before any flash command and defines: portable recovery
  default vs separately authorized forensic archive; separate identity/format/copy/verify steps;
  source allowlist and explicit exclusions; exact Windows overlays + bundle/patch/untracked recovery;
  private signing material; mandatory previous+latest signed APK rotation and LATEST markers;
  internet-restored toolchains; size/largest-file preflight; hash/bundle/forbidden/restore rehearsal;
  known PS5 stderr, empty-tree, CRLF and Base64 failures. `BACKUP_AND_CLEAN_PC_RECOVERY.md` now points
  to it and labels its old generic section non-executable for portable flash backup. Immediate next:
  build/run a new compact-copy harness against already-empty F:, never rerun v1-v4 or format.
- **2026-08-15 (доп.183) — format permanently forbidden; compact-copy v1 prepared:** user corrected
  the permanent policy: never format accepted APU flash again. Future rotation writes/verifies
  incoming latest first, old latest→previous, then may delete only versions older than previous.
  Runbook/master/recovery/current override updated in commit `1fdb73a`; authoritative full commit
  `1fdb73a512db97ccecca8990a79410b095290acd`. New
  `scripts/v111616_compact_flash_backup_v1.ps1` is one-use/no-network/no-phone and contains no format
  call. It requires immutable format state A754…ACFF and exact F: identity/exFAT label, empty root for
  this first compact copy, signed previous/latest APK+build states, native 27B9…D1FD26C and private
  keystore. Source is selected by `git ls-files --cached --others --exclude-standard` plus explicit
  forbidden segment/file filter; tracked logs/hs_err/temp_core, caches/build/target/.git, local props,
  JDK/SDK/Rust and bulk `%TEMP%` evidence are excluded. Hard plan gates: 100–2000 source files,
  source≤512 MiB, free-space reserve and top-10 listing. Output includes two APKs/LATEST markers,
  exact portable tree, bundle+verify, working/index patches, essential untracked list, source/all-file
  manifests, exact native, private keystore warning, only three bounded state JSONs, restore/verify
  scripts, forbidden scan, full flash hash verify and temporary bundle clone/commit rehearsal.
  Harness 28,811 B / SHA-256 `5121A8B9FBCCCDAA31EF722A1C679DC2C4F6FF295A746A6A97DDAE81DCCBCA34`;
  deterministic gzip 8,006 B / SHA-256
  `8B2233DF6462E252E06E520162F84043694685A7CAB7C921D7411F811A44391C`, Base64 10,676 chars.
  Transfer must use smaller authenticated chunks, raw+gzip+Parser gates; execution pending. After
  compact PASS, upload exact v11.16.16 asset to draft, remote re-download/hash verify, then publish
  prerelease; tag-triggered auto-build remains forbidden.
- **2026-08-15 (доп.184) — compact v1 stopped before F: copy; .NET Process v2 prepared:** 4/4 chunk,
  combined gzip/raw and ParseFile PASS. v1 verified format/APKs/build states/native/keystore, then its
  first Git command (`git branch`) completed but PowerShell `Start-Process` exposed null ExitCode;
  state outcome `INCOMPLETE_DO_NOT_REPEAT`, `copyStarted=false`, so no `F:\APU_PORTABLE` creation.
  Do not repeat v1. v2 uses `Diagnostics.ProcessStartInfo` with redirected async stdout/stderr,
  bounded wait and direct non-null `.ExitCode`; arguments containing whitespace/quotes are rejected
  (all current exact paths/args have none). Separate v2 state/native/restore paths preserve v1 state;
  preflight requires v1 outcome/copyStarted/copyComplete/formatRepeated proof and copies that small
  state into bounded provenance. Embedded new-PC restore clone also avoids Start-Process ExitCode.
  `scripts/v111616_compact_flash_backup_v2.ps1`: 30,337 B / SHA-256
  `466D05A84DD2E4E915ED3F045ACDB29BC20EB060EFA5B6F07DCB1F3E79774565`; deterministic gzip
  8,294 B / SHA-256 `8B840BE487DBEA7D9327923C50A018984832B71956F808D559B3D9D1402E8FE7`.
  Prefer compact exact patch from already verified v1:
  `scripts/v111616_compact_v2_from_v1.patch` 3,963 B / SHA-256
  `6CDDEA227CFD47141004024C8C3CBE9E2DE3091E120C257CFCACD406E3B901B1`; gzip 1,318 B /
  `3FE55BF579197FD8856DD37F0F670BED37033C8892B7355050B75AA296B62819`, Base64 1,760 chars.
  Normalize CRLF→LF after Windows git apply before expected target hash/Parser. Execution pending;
  never format.
- **2026-08-15 (доп.185) — compact v2 copy/hash PASS; final marker resume v3 prepared:** v1→v2
  source/gzip/patch/normalized target/ParseFile PASS. Plan PASS sourceFiles=252, source=14.36 MiB,
  reserved minimum=320.52 MiB; top files were exact native 7,263,416 B, armv7 native 2,076,080 B
  and icon 1,980,451 B, confirming no cache explosion. Portable tree/two APKs/bundle/patches/native/
  private signing/restore docs/manifests copied; forbidden scan passed and
  `ALL PORTABLE BACKUP HASHES PASSED: 278 files`. Bundle restore clone also succeeded. v2 then failed
  before commit check call because PowerShell `("{0}^{commit}" -f HEAD)` parsed `{commit}` as invalid
  format placeholder. State is `INCOMPLETE_DO_NOT_REPEAT`, copyStarted=true/copyComplete=false;
  `F:\APU_PORTABLE\INCOMPLETE.json` remains and BACKUP_COMPLETE absent. Do not repeat copy/v2.
  Resume `scripts/v111616_compact_flash_backup_resume_v3.ps1` revalidates v2 state, F: identity,
  both APK hashes, Parser+all 278 manifest hashes and bundle; requires/reuses existing successful
  v2 clone, builds commit spec by concatenation `HEAD + '^{commit}'`, verifies commit, deletes only
  that exact temp clone, writes BACKUP_COMPLETE, removes only exact INCOMPLETE, recopies 0 files and
  writes separate v3 state. Harness 9,565 B / SHA-256
  `25FE08F4573FB2885A67814C4EFB62CF6A34A00D5E13748744E80626ADDAB6DE`; execution pending; no
  format/network/phone/copy call.
- **2026-08-15 (доп.186) — resume v3 hashes PASS, PS5 JSON count bug; resume v4 prepared:** v3
  transfer/raw/ParseFile PASS and flash verifier again printed `ALL PORTABLE BACKUP HASHES PASSED:
  278 files`, then `@(<ConvertFrom-Json array>).Count` returned 1 due outer-array wrapping. It stopped
  before bundle/commit/final-marker changes; existing restore clone and INCOMPLETE remain, complete
  absent. v3 state `INCOMPLETE_DO_NOT_REPEAT`, reusedCopiedFiles=true/filesRecopied=0/format=false;
  do not repeat. v4 requires v2+v3 states, uses the same direct assignment as successful verifier
  (`$ManifestEntries = ... | ConvertFrom-Json`, then `.Count`), expects 278, and otherwise retains the
  zero-copy finalization sequence. `scripts/v111616_compact_flash_backup_resume_v4.ps1` 10,262 B /
  SHA-256 `8015CD2C9433D684C68ED57807137207D59BCCD05665E9BD7ECF8A34B7C13F29`;
  execution pending; no format/copy/network/phone action.
- **2026-08-15 (доп.187) — full v4 transfer rejected before write; tiny v3→v4 patch prepared:**
  copied Base64 had invalid length; wrapper failed at `FromBase64String` before decompression/write/
  ParseFile/execute, so no v4 state or F: change. Do not repeat full payload; not user error.
  Authenticated zero-context `scripts/v111616_resume_v4_from_v3.patch` is 1,846 B / SHA-256
  `41CEE317C63790EC7A5E2A1E05C55D7A4BD710400326F82509297BB20258F767`; deterministic gzip 641 B /
  `63410DFCAB8F1DE94CD3BE5311EC62D98B1AF09D879E23B7285751F4F9509693`, Base64 856 chars.
  Local apply reconstructs exact v4 hash 8015…13F29. Transfer patch only from verified v3, normalize
  CRLF→LF, verify target/Parser, then execute zero-copy finalizer.
- **2026-08-15 (доп.188) — alias collision recorded; compact portable backup final PASS:** first two
  tiny-wrapper attempts stopped before patch write because PowerShell is case-insensitive and helper
  `H` resolved to built-in alias `h`=`Get-History`; `H $bytes` became invalid `Get-History -Id 0`.
  No F: change. Corrected unique `Get-BytesSha256Exact` wrapper passed source/gzip/patch/target/Parser.
  Resume v4 then printed `ALL PORTABLE BACKUP HASHES PASSED: 278 files` and final
  `APU COMPACT FLASH BACKUP PASS; 278 HASHES; PREVIOUS+LATEST; RESTORE REHEARSAL PASS; NEVER FORMAT`.
  Backup `F:\APU_PORTABLE`; state
  `%TEMP%\apu-v11.16.16-compact-flash-backup-resume-v4.json`, SHA-256
  `A96500612DD1AC80D908F1F49ADE9536931E512D387C2FD0EDA8CB82772D2483`. INCOMPLETE removed,
  BACKUP_COMPLETE written, files recopied by resumes=0. Runbook now hard-bans one-letter/common helper
  names, requires unique Verb-Noun names, named parameters and `Get-Command <name> -All` alias conflict
  gate. Never format flash again. Immediate next: draft asset upload → remote exact hash → publish
  prerelease; draft currently empty/unpublished.
- **2026-08-15 (доп.189) — exact draft upload harness prepared:**
  `scripts/v111616_draft_upload_v1.ps1` requires compact backup marker+state A965…2D2483, exact flash
  APK 22,664,712 B / 446A…DC0D and checksum text. It requires Windows `gh.exe` already installed and
  authenticated, verifies target is empty draft+prerelease, uploads exactly APK+sha256 once, downloads
  APK to distinct C: temp, rechecks bytes/hash and remote asset names, and proves release remains
  unpublished draft. State is separate/finally/no auto-retry; no format/phone/build. Harness 7,133 B /
  SHA-256 `19CE187B2B9D4D488D9D23403F114DBF1C6143C9055087B01524AC79F6147F6C`;
  deterministic gzip 2,367 B / `A42109FDB2734B34B90DF6428FB11724FED9C9D428995A97F9FBBBFFB67E8C5E`,
  Base64 3,156 chars. Outer read-only gh/auth/TCP gate must pass before transfer/execution. After PASS,
  sandbox independently checks assets/download hash and publishes existing prerelease without build.
- **2026-08-15 (доп.190) — browser asset upload + v11.16.16 prerelease publication PASS:** Windows
  outer gate found `gh.exe` keyring token invalid and stopped before transfer/upload/state; no release
  change and do not repeat. User confirmed browser upload is convenient/preferred. User uploaded exact
  `APU-v11.16.16.apk` and `.apk.sha256` to draft and saved draft. Arena API verified asset IDs
  515862302/515862281, states uploaded, sizes 22,664,712/84 B. First independent asset download hit
  transient release-assets EOF; no blind retry. GitHub server digests independently proved APK
  `sha256:446a1ee9…429dc0d` and checksum `sha256:cc4f947f…8cf78d`; expected checksum bytes/hash were
  locally reproduced exactly. Existing draft published as **prerelease**, URL
  `https://github.com/vzhem/APUMIR/releases/tag/v11.16.16`, publishedAt 2026-08-15T16:35:19Z,
  target/tag `85aecb0fa9893184e357b6c565869d0f1ebd69b7`. Final assets/URLs/digests unchanged. Tag workflow
  run 31895919402 success: release-exists check success, build job skipped — no auto-build or asset
  replacement. Future default: Arena draft → browser upload exact previous/latest-selected files
  (release uploads latest APK+sha) → Arena server-digest verification → Arena publish. Never request
  Windows GitHub token/password and never treat invalid Windows gh keyring as user error.
- **2026-08-15 (доп.191) — global version statistics ledger created:**
  `docs/VERSION_STATISTICS.md` is append-only per published stable/prerelease milestone. Required
  fields: code/docs/tag commits, Rust/handwritten Kotlin/UDL/XML/generated/tests files+physical/nonblank
  LOC, delta using same method, APK bytes/hash/signer, key changes, acceptance, limitations, release
  and previous/latest backup. Initial v11.16.16 baseline at count commit `03c9768`: core 149 files /
  31,645 physical / 27,685 nonblank; with generated 34,155 / 29,750; Android tests 241; automation
  17,117; all tracked code/config/automation excluding docs/logs 53,173. Future AI must add a new
  section rather than rewrite history. Next product work is M8 durable encrypted relay custody.
- **2026-08-15 (доп.192) — complete next-chat bootstrap prompt added:**
  `docs/NEXT_AI_CHAT_BOOTSTRAP.md` is the authoritative paste-ready handoff. It captures current
  release/backup/stats, consumed phone/Windows steps, all user workflow constraints and reusable
  PowerShell hazards, exact delayed multi-carrier requirement, current RAM/Instant gap, required
  reading/source files, and a no-questions first task. Immediate coding slice M8-A: replace
  persistent-facing `Instant` with validated serializable epoch-ms timestamps, preserve current wire/
  queue semantics, add deterministic clock/round-trip/TTL/hop tests, static checks+commit only; no
  SQLite write in the same slice and no sandbox cargo test. Then M8-B store, M8-C encryption/key
  lifecycle, M8-D restart/reboot recovery, M8-E bounded sleep/wake and M8-F phone acceptance.
- **2026-08-15 (доп.193) — M8-A durable timestamp/model boundary source slice complete; Windows
  compile pending:** новая Arena сессия стартовала на сгенерированной ветке
  `arena/01a00674-apumir` (база `991da05`); handoff-коммит подтянут предписанным read-only
  `git fetch origin arena/01a000bc-apumir` + `git merge --ff-only` в текущую ветку (tip `99ac840`,
  worktree был clean, переключения веток не было). Изменения source: `relay_queue.rs` переводит
  durable-facing `RelayMessage` с process-local `Instant` на абсолютные UTC epoch-ms
  (`created_at_ms`/`expires_at_ms`, `i64`); добавлены serde derive, безопасные `utc_now_ms()`
  (clock-before-epoch → 0, без panic) и `ttl_to_ms` (saturating), explicit-clock методы
  `new_at_ms`/`with_ttl_at_ms`/`is_expired_at`/`remaining_ttl_secs_at` и `cleanup_expired_at`
  (`is_expired`/`cleanup_expired`/`remaining_ttl_secs` — thin wrappers над системным UTC),
  validation/loading constructor `from_persisted` + `validate_durable` (reject: пустые/oversized
  msg_id/recipient/origin/chat_scope, wire-разделитель `|`, пустой/oversized payload,
  `expires < created`, already-expired, `hop >= MAX_HOPS`) с отдельным `RelayValidationError`;
  TTL абсолютный и НЕ продлевается serialize/deserialize/next_hop/restart. Общие bounded-лимиты
  метаданных (msg_id/node ID 128 B, chat scope 256 B, envelope 64 KiB) вынесены в `relay_queue.rs`
  и переиспользованы `offline_send.rs` (публичный реэкспорт сохранён) и `engine/core.rs`
  (локальный дубль константы удалён; gossip resend считает остаток TTL через
  `remaining_ttl_secs()` вместо `Instant`). 12 новых unit tests: round-trip, TTL-not-extended,
  expired rejected/cleaned, invalid timestamps/hop rejected, delimiter/empty/oversized reject,
  next_hop keeps absolute expiry, clock-before-epoch/saturation no panic, deterministic cleanup.
  Существующие API/поведение (`new`, `with_ttl`, dedup, limits, gossip, hop, receipt path) и wire
  format `relay|...` не менялись. `git diff --check` PASS; grep всех callsites выполнен.
  Sandbox cargo test запрещён и не запускался: **compile/runtime ждут отдельного Windows gate
  `build-rust.ps1 -Features mqtt-dual-broker`**. В этом slice нет SQLite write; следующий шаг —
  M8-B RelayStore boundary + отдельная SQLite migration (relay records + tombstones, atomic
  persist до enqueue/ACK/offer).
- **2026-08-15 (доп.194) — M8-B/D durable custody source slice complete; максимум тестирования
  в sandbox выполнен; Windows compile pending:** пользователь дал полную волю на процесс и
  maximum testing. Sandbox не имеет cargo/rustc, crates.io/rustup недоступны (проверено),
  поэтому Rust-компиляция по-прежнему невозможна локально — вместо неё выполнен расширенный
  статический/семантический контроль. Добавлен `storage/relay_store.rs`: `RelayStore`
  (Mutex<Connection>, sync-only, никогда не удерживается через await) с отдельной миграцией
  `MIGRATION_RELAY_V1` — таблицы `relay_messages` (PK msg_id, индексы recipient/expires_at_ms)
  и `relay_tombstones` (PK msg_id, индекс removed_at_ms), своя `relay_schema_version`.
  API: store (validate_durable до записи, INSERT OR IGNORE), load_unexpired/load_for_recipient
  (bounded, битые строки удаляются без UI), remove/remove_for_recipient/purge_expired,
  remove_and_tombstone (одна транзакция), record_tombstone/has_tombstone/prune_tombstones
  (возраст + top-N), load_tombstone_ids, счётчики; 14 unit tests. Engine integration
  (`engine/core.rs`): P2PCore.relay_store создаётся в start() (файл `<db_path>.relay.sqlite`
  либо in-memory; при ошибке открытия честный RAM-only fallback), `restore_relay_custody`
  восстанавливает RAM RelayQueue bounded-load без продления TTL и purges expired; relay_store
  прокинут в run_mqtt_transport: MESH-relay путь persist-ит custody ДО enqueue (tombstone/
  validation reject → не enqueue), receipt атомарно remove+tombstone независимо от RAM-копии,
  локальная доставка ставит durable tombstone И подавляет повторную UI-доставку после restart
  (UI exactly-once), gossip чистит expired и в durable-слое, seen-set стартует с durable
  tombstones; origin offline send persist-ит ДО enqueue. Поведение при relay_store=None —
  legacy RAM-only. Проведённые проверки: (1) sqlite3-прототип SQL-семантики 25/25 PASS;
  (2) end-to-end симуляция отложенного сценария Anna→Zhenya→D→Stas на реальной SQLite 17/17
  PASS (custody переживает kill+сутки, TTL не продлевается, hop/payload сохраняются, ровно
  одна UI-доставка, replay после restart блокируется durable tombstone, истёкшие не
  доставляются); (3) tree-sitter Rust parse всех изменённых файлов 6/6 PASS (синтаксис, не
  типы); (4) git diff --check PASS. Артефакты прототипов: `.arena/m8b/` (вне Git-снапшота).
  **Compile/runtime по-прежнему ждут Windows `build-rust.ps1 -Features mqtt-dual-broker`.**
  Остаток M8: M8-C encryption at rest + Android Keystore key lifecycle (Kotlin/JNI seam),
  M8-E bounded WorkManager sleep/wake, M8-F телефонный acceptance (требует телефонов и APK).
- **2026-08-16 (доп.195) — урок устаревшего bootstrap + аудит M8-A/B/D + M8-C envelope slice 1:**
  новая Arena-сессия стартовала с ПУСТЫМ sandbox (checkout отсутствовал вовсе) и старым текстом
  bootstrap, требовавшим «сделать M8-A». Read-only clone показал, что M8-A (`b5408e2`), M8-B/D
  (`b881d65`) и compile-gate harness (`8b376cc`) уже запушены в `arena/01a00674-apumir` —
  линейном продолжении handoff-коммита `99ac840`. **Правило: прежде чем следовать вставленному
  handoff, сравнить его с tip'ом НОВЕЙШЕЙ arena-ветки** (`git for-each-ref --sort=-committerdate`);
  bootstrap может отставать от репозитория, и это разрешённый handoff-ом случай остановиться и
  спросить. Пользователь выбрал продолжение на `arena/01a00674-apumir` (сознательное одобренное
  переключение; новых веток не создавалось). Аудит M8-A/B/D проведён полным чтением
  `relay_queue.rs` (1189 строк), `relay_store.rs` (698 строк), всех diff'ов `core.rs`/
  `offline_send.rs` и полного `wire.rs`: epoch-ms модель, валидация, persist-before-enqueue,
  receipt remove+tombstone, exactly-once UI tombstone, bounded startup restore выполнены по
  спецификации; wire format `relay|.../` не тронут; блокирующих находок нет. Мелкие наблюдения
  (не блокеры, не править без необходимости): `RelayStore.store()` валидирует caller-provided
  `now_ms` (вызывающий обязан передавать UTC now); при IO-ошибке file-backed store сообщение
  честно не попадает в RAM (by design «custody не подтверждена durable»). **M8-C slice 1
  source complete:** новый pure модуль `storage/relay_at_rest.rs` — версионируемый AEAD-конверт
  для байт relay-записи: XChaCha20-Poly1305 (192-битный случайный nonce, безопасен для
  долгоживущего storage-ключа; новых зависимостей нет), заголовок `[version:u8][key_id:u16 LE]
  [nonce:24]`, AAD доменно отделён и связывает `msg_id|recipient|expires_at_ms` (инвариант
  «нет `|`» из `valid_metadata_atom` делает поля однозначными), уникальный nonce через `OsRng`,
  явные quarantine-ошибки `UnsupportedVersion`/`UnknownKeyId`/`KeySourceUnavailable`/
  `MalformedEnvelope`/`DecryptionFailed` (криптографически неразличимые случаи не различаются
  нарочно), Никакого plaintext-fallback и ни одного panic-пути (оба `.expect` — на константном
  32-байтном размере ключа). Trait `RelayAtRestKeySource` (`current_key`/`key_by_id`) — seam для
  будущего Android Keystore моста; ротация ключа решается через `key_id` в заголовке.
  12 детерминированных unit tests (явные nonce/ключи): exact layout, round-trip, различие
  случайных nonce, wrong key/AAD, flipped ciphertext/tag/nonce, unknown version/key_id, все
  усечения 0..header+tag без panic, size bound на границе, отсутствие plaintext fallback,
  domain separation AAD, redacted Debug ключа. SQL/`RelayStore`/core НЕ менялись — граница
  модели сначала, как в M8-A. Проверки ТОЛЬКО разрешённые статические: `git diff --check` PASS,
  grep name-collision PASS; **в этом sandbox нет cargo/rustc/rustfmt/дерева-sitter** — синтаксис
  подтверждён только source review, compile/runtime ждут Windows `build-rust.ps1 -Features
  mqtt-dual-broker` (тот же pending gate, что и у M8-A/B/D). Следующий маленький slice — M8-C
  slice 2: relay schema v2 (зашифрованная чувствительная часть записи как одна BLOB-колонка +
  открытые msg_id/recipient/expires_at_ms для индексов) и проводка конверта в `RelayStore` с
  quarantine-путём; Keystore-мост (Kotlin/JNI) — отдельно позже.
- **2026-08-16 (доп.196) — M8-C slice 2: encrypted schema v2 + quarantine в RelayStore; SQL
  проверен; пользователь просил готовый релиз — честно объяснено, что релиз без гейтов невозможен:**
  пользователь дал полную волю («всё по максимуму, все разрешаю, давай готовый релиз»).
  Релизного нового артефакта НЕТ и не могло появиться из sandbox: весь M8-код (A/B/D/C) ещё ни
  разу не компилировался (Windows-only gate), принятый политикой порядок релиза (M8 + mixed +
  security gates) не закрыт; публиковать нескомпилированный код как «релиз» недопустимо — это
  прямо объяснено пользователю. Push из этого sandbox по-прежнему невозможен (нет GitHub
  credentials; не считать ошибкой пользователя — commit готов локально и пушится одной командой
  при доступной среде). Выполненный slice: `relay_store.rs` получил `MIGRATION_RELAY_V2` —
  таблицу `relay_records_enc` (открыты только индексные `msg_id`/`recipient`/`expires_at_ms`;
  чувствительные origin/chat_scope/payload/created/hop уходят одним bincode→AEAD конвертом из
  `relay_at_rest` в колонку `envelope`) и таблицу `relay_quarantine` (msg_id, открытые колонки,
  envelope, reason-код, failed_at_ms). Миграция данных V1→V2 отсутствует намеренно: схема V1
  никогда не доезжала до устройств (нет ни одного .so с M8), переносить нечего; V1-таблицы и
  поведение сохранены для совместимости wiring из M8-B/D. API: store_encrypted
  (validate→encode→encrypt→INSERT OR IGNORE дедуп), load_unexpired_encrypted/
  load_for_recipient_encrypted (decrypt→deserialize→validate_durable; любой отказ — атомарный
  move в карантин с причиной-кодом: запись не возвращается и не молча удаляется),
  remove_encrypted(+_and_tombstone через общий tombstones-тейбл), remove_for_recipient_encrypted,
  purge_expired_encrypted (чистит и карантин), quarantine_count/list_quarantined (диагностика
  без payload байтов), count_encrypted. Подмена открытых колонок детектируется AAD как
  `atrest-auth-failed`. 13 новых unit tests (round-trip, отсутствие sensitive-cleartext в строке,
  dedup, reject-до-encrypt, expiry, per-recipient/bounded load, tampered recipient → quarantine,
  unknown key → quarantine, unsupported version → quarantine, atomic remove+tombstone, purge
  quarantine, V1+V2 coexistence). Проверки: sqlite3-прототип `.arena/m8c2/verify_v2_sql.py`
  извлекает SQL-тексты прямо из `relay_store.rs` (не ручные копии) — **24/24 PASS** на SQLite
  3.46.1 (тот же движок, что rusqlite bundled); git diff --check PASS; grep коллизий PASS.
  Синтаксис — source review (в sandbox нет cargo/rustc/rustfmt/tree-sitter, как и раньше). Wire
  format снова не менялся. Engine пока работает как раньше: encrypted-путь станет активным
  вместе с Android Keystore-мостом (M8-C slice 3, следующий маленький шаг: Kotlin/JNI key
  source + перевод persist/load в core.rs на encrypted API + честный RAM-only degrade при
  недоступном Keystore).

- **2026-08-16 (доп.197) — M8-C slice 3: Android Keystore-мост + engine переведён на encrypted
  API; custody всегда encrypted, durable — только с ключом:** пользователь разрешил «делать всё
  по максимуму» и попросил присылать исполняемые блоки для Windows. Выполнено, source+
  static-only (compile по-прежнему ждёт Windows gate). Решения: (1) **plaintext-fallback
  запрещён и удалён как возможность** — V1-методы хранилища остались для тестов, но engine их
  больше не вызывает; (2) **без ключа durable-файл не создаётся вообще** — честный RAM-only
  degrade с эфемерным ключом (`ephemeral_key_source`, `key_id=0`), а не «durable с
  одноразовым ключом» (ложная семантика); (3) **relay custody получил собственный путь**
  `EngineConfig.relay_db_path` (`with_relay_db`) — main storage поведение не изменено
  (in-memory, как в v11.16.16); (4) **trait `RelayAtRestKeySource` стал `Send + Sync`**
  (Arc-снимок разделяется с MQTT-потоком). Rust: `relay_at_rest.rs` += `MasterSecretKeySource`
  (боевой источник из 32-байт master secret; `InvalidKeyMaterial` на неверную длину; `Drop`
  зануляет через `wipe_bytes` — volatile write + black_box, без новых зависимостей),
  глобальный реестр `install_device_key_source`/`installed_key_source`/`installed_key_id`/
  `clear_device_key_source` (install ДО start; повторный install действует на следующий engine),
  6 новых tests (wipe, длина среза, lookup, seal/open+redaction, install/clear roundtrip,
  эфемерный RAM-only и его изоляция). `core.rs`: `RelayCustody { store, keys, durable }`
  заменил поле `relay_store`; `open_relay_custody` реализует правила (ключ+путь →
  durable-encrypted; без пути → in-memory encrypted; без ключа → in-memory ephemeral + warn);
  ВСЕ сайты переведены: startup restore (`purge_expired_encrypted` tuple +
  `load_unexpired_encrypted` с quarantine-warn), persist-BEFORE-enqueue (inbound MESH relay и
  origin offline) на `store_encrypted`, receipt → `remove_encrypted_and_tombstone`, gossip →
  `purge_expired_encrypted`, tombstones без изменений (общая открытая таблица msg_id+время).
  UniFFI (`lib.udl`+`lib.rs`): `install_relay_at_rest_key(u16, bytes)` [Throws=CoreError],
  `clear_relay_at_rest_key()`, `relay_at_rest_key_id()` → i64 (-1 = нет), `create_engine_durable(
  display_name, public_key, private_key, relay_db_path)` (пустые ключи = сгенерировать), методы
  `P2PCoreHandle.relay_custody_mode()` ("durable-encrypted"/"ram-only"/"disabled") и
  `relay_quarantine_count()` — это acceptance-датчики для M8-F. Kotlin: новый
  `data/security/RelayAtRestMasterKey.kt` — Keystore AES/GCM/NoPadding 256 не-извлекаемый
  wrap-ключ `apu_relay_at_rest_wrap_v1`; 32-байт master secret (SecureRandom) wrap-ится и
  хранится Base64 в `apu_relay_at_rest` prefs ([keyId:2 BE][IV:12][ct+tag]); unwrap при старте;
  провал unwrap (data clear/чужой Keystore) = честно новый secret с НОВЫМ keyId (старые записи
  → quarantine), Kotlin-копия `fill(0)` после передачи; любая ошибка → false = движок RAM-only.
  Wiring: `CoreServerService.onStartCommand` и `CreateIdentityUseCase` вызывают
  `installIntoCore()` СТРОГО до `RustBridge.initialize(..., relayDbPath = filesDir/apu_relay.sqlite)`;
  `RustBridge.initialize` получил 4-й параметр и логирует custody mode+quarantine после старта.
  Backup-исключения: `data_extraction_rules.xml` + `backup_rules.xml` исключают
  `apu_relay.sqlite{,-wal,-shm}` и `apu_relay_at_rest.xml` (байты привязаны к Keystore
  устройства — на другом устройстве это мусор; свежий secret генерируется на месте). Небольшой
  housekeeping в той же сессии: снят UTF-8 BOM с 6 Kotlin-файлов (data entities + domain models)
  — отдельный мелкий commit; остальные 11 BOM-файлов сознательно не тронуты (v11.16.16
  компилировался с ними — не чиним работающее). Проверки в sandbox: git diff --check PASS;
  brace/string-скан всех 7 затронутых файлов BALANCED; полный diff review PASS; cross-grep
  (нет лишних ссылок `EngineConfig{}`/`open_relay_store`/`relay_store`) PASS. НЕ доказано:
  compile (нет toolchain в sandbox) — единый pending gate Windows: `build-rust.ps1 -Features
  mqtt-dual-broker` + регенерация UniFFI Kotlin bindings (`cargo run --bin uniffi-bindgen
  generate src/lib.udl --language kotlin --config uniffi.toml --out-dir
  ..\android-app\app\src\main\java`), затем `gradlew assembleDebug` для Kotlin-части.
  Wire format не менялся; N↔N-1 безопасно: старый APK новые UniFFI-функции не вызывает; на
  устройствах V1-файлов relay никогда не было (Kotlin до сих пор не задавал db_path), конфликта
  файлов нет. Передача кода на Windows — `git format-patch` mbox (push из sandbox по-прежнему
  заблокирован средой: нет GitHub credentials; не считать ошибкой пользователя).

- **2026-08-18 (доп.198) — новая Arena-сессия продолжена без потери точки остановки; compile-gate
  harness доведён до полного M8-C3:** старая ветка `arena/01a013d0-apumir` на GitHub и новая
  фиксированная ветка сессии обе стартуют с exact tip `24303b4`; незакоммиченных изменений старой
  сессии нет. Исторический `scripts/m8_rust_build_gate.ps1` проверял только M8-A/B/D commit и
  ожидал специальный dirty Windows overlay, поэтому он не мог честно закрыть актуальный C3 gate.
  Harness обновлён: требует чистый worktree на C:, проверяет неизменность application source против
  exact C3 commit `204fb9f`, выполняет `build-rust.ps1 -Features mqtt-dual-broker`, затем UniFFI
  Kotlin bindgen, проверяет наличие четырёх новых API, выполняет `:app:assembleDebug`, хеширует
  `.so`/bindings/debug APK и сохраняет одноразовые stdout/stderr + JSON state в
  `%TEMP%\\apu-m8-c3-*`. ADB/установка/телефоны/traffic отсутствуют. В sandbox доступны только
  source review, balanced-delimiter check и `git diff --check`; реальный PASS/FAIL по-прежнему
  получается одним запуском harness на каноническом Windows PC. До compile PASS код M8-E не писать.

- **2026-08-18 (доп.199) — первый C3 compile attempt честно остановился на stale MSVC PDB,
  source error ещё не достигнут:** Windows clean worktree `C:\\APU-M8` fast-forwarded до `746e241`;
  raw script SHA между Linux/Windows различался из-за LF→CRLF, поэтому дальнейшая верификация —
  Git blob, не filesystem hash. Gate state `2ABC3ACF…6236FBF6`, Rust stderr: host proc-macro link
  `LNK1207 incompatible PDB` для `target\\release\\deps\\uniffi_macros-….pdb`, до компиляции
  project source/bindgen/Gradle не дошло; `.so`/bindings/APK не произведены, ADB/phones/traffic=false.
  Пустой Process.ExitCode — отдельная ошибка harness diagnostics, не причина build failure; реальная
  причина доказана stderr. Исходное evidence не удалять и основной gate не повторять. Добавлен
  single-use `scripts/m8_c3_compile_recovery.ps1`: требует exact prior-state hash+LNK1207 evidence,
  атомарно переносит весь stale Cargo `target` в `%TEMP%\\apu-m8-c3-stale-target-lnk1207` вместо
  удаления, делает один fresh Android Rust build прямым `cargo ndk`, затем bindgen+assembleDebug,
  использует явные exit-marker файлы вместо ненадёжного `Process.ExitCode` и сохраняет отдельный
  recovery state/logs. До recovery PASS M8-E не начинать.

- **2026-08-18 (доп.200) — fresh compile дошёл до project source и нашёл один точный C3 defect:**
  preserved-target recovery state `D37E5372…61402D8`, exit 101, ADB/phones/traffic=false. После
  чистой перекомпиляции MSVC/PDB проблема исчезла; единственная Rust error — E0004 в
  `relay_store.rs::at_rest_reason`: добавленный в C3 `AtRestError::InvalidKeyMaterial` не был
  покрыт exhaustive match. Исправление bounded: стабильный diagnostics/quarantine reason
  `atrest-invalid-key-material` + unit test mapping; wire/schema/crypto behavior не меняются.
  Добавлен single-use `scripts/m8_c3_source_fix_gate.ps1`: требует exact prior recovery hash и
  E0004/InvalidKeyMaterial evidence, продолжает из fresh incremental target, собирает Rust,
  bindgen и debug APK, проверяет четыре C3 binding API и сохраняет отдельное evidence. Старые
  gate/recovery/stale-target evidence не удалять; этот continuation запускать один раз. До PASS
  M8-E не начинать.

- **2026-08-18 (доп.201) — Android Rust C3 PASS; старый bindgen invocation ошибочно тянул все
  host-native зависимости p2p-core:** source-fix state `92693E06…59F9559`; Android Rust process
  PASS, новый arm64 `.so` 7,369,056 B / SHA-256 `F4D2FABF…DAF824`. Затем `cargo run --bin
  uniffi-bindgen` внутри основного package попытался собрать на Windows весь dependency graph,
  включая bundled sqlite/ring/aws-lc; MSVC `cl.exe` дал массовые C errors/`0xc0000006`. Это не
  UDL/bindgen и не Android Rust defect: CLI binary использует только UniFFI, но Cargo package-level
  unconditional deps заставляли строить ненужные host-native библиотеки. Исправление build boundary:
  minimal pinned tool `tools/uniffi-bindgen` (`uniffi = =0.28.3`, CLI only), отдельный target в
  `%TEMP%`; все M8 harness-команды переведены на его manifest. Добавлен single-use
  `scripts/m8_c3_isolated_bindgen_gate.ps1`: требует exact prior-state hash, exact successful `.so`
  hash и только его dirty status, запускает isolated bindgen, проверяет четыре C3 Kotlin API и затем
  `assembleDebug`. Успешный `.so` не пересобирать/не восстанавливать; old evidence сохранить.

- **2026-08-18 (доп.202) — PC storage incident изолирован, recovery backup PASS, D: выведен из
  эксплуатации:** после bindgen PASS Gradle сначала остановился на отсутствующем `local.properties`;
  при следующем действии PC получил BSOD. Диагностика доказала два crash (`0x1A PAGE_HASH_ERRORS`,
  `0x154 UNEXPECTED_STORE_EXCEPTION`), `cl.exe/c1.dll` с `0xc0000006`, NTFS corruption и реальные
  `c0000483 disk read errors` на HDD D: `ST1000DM010-2EP102` serial `Z9AM7JYH`, где находились
  Visual Studio и pagefile. Это hardware/storage boundary, не APU defect. USB Disk 2 serial
  `2240331293279315997` (старый corrupt exFAT, пользователь разрешил стереть) guarded-format NTFS
  `APU_RECOVERY`, затем backup `E:\\APU_RECOVERY_20260818_M8C3` PASS: repo, 25 evidence items,
  verified Git bundle SHA `83CE8009…B871F9`, 3 crash dumps, events; backup-state SHA
  `C8DC7311…D4BEAAA`, USB Healthy/clean. USB безопасно offline/отключён. Pagefile перенесён на C:
  (active 16 GiB), failed Disk 1 переведён offline; D: больше не использовать/не форматировать для
  APU. Сохранённые outputs: arm64 `.so` 7,369,056 B `F4D2FABF…DAF824`; binding
  `31BD5505…B610F2`. Добавлен final single-use `scripts/m8_c3_gradle_only_gate.ps1`: требует exact
  artifacts/evidence, failed disk offline/physically absent, pagefile C-only и valid SDK
  local.properties; запускает только `assembleDebug`, Rust/bindgen/ADB/phones не повторяет.
  После decommission Windows перестала перечислять Disk 1 через `Get-Disk`; это допустимое ещё
  более безопасное состояние (HDD физически отсутствует/не виден). Gradle gate исправлен: exact
  serial может быть либо present+offline, либо отсутствовать; present+online по-прежнему STOP.
- **2026-08-18 (доп.203) — final Gradle attempt не стартовал из-за stale JAVA_HOME:** state
  `185E997A…FF1AB8E`, duration 0.4 s, stderr exact `JAVA_HOME ... D:\\Android Studio\\jbr`;
  Gradle/Kotlin compilation не начиналась, artifacts прежних Rust+bindgen PASS неизменны. На C:
  найдены Android Studio JBR 21.0.10 (требование daemon toolchain=21), Temurin 17/25 и JBR17.
  Добавлен single-use `scripts/m8_c3_java_home_recovery.ps1`: требует exact prior evidence/hashes,
  D absent/offline + pagefile C, проверяет JDK21/javac/SDK и запускает только Gradle с process-local
  `JAVA_HOME=C:\\Program Files\\Android\\Android Studio\\jbr`; системную environment не меняет,
  Rust/bindgen/ADB/phones не запускает.
- **2026-08-18 (доп.204) — M8 A→C3 Windows compile gate PASS и source reconciliation PASS:**
  process-local JBR21 recovery завершила `assembleDebug`; state SHA `6D69585B…DC94DD`, debug APK
  29,283,225 B / SHA `87BE9E22…F2FD76`, Rust `.so` `F4D2FABF…DAF824`, normalized binding
  `982308D9…2596D3`. Rust/bindgen/ADB/phones на финальном шаге не запускались. Generated binding
  commit `9bf45b9` содержит только `p2p_core.kt` и запушен в fixed branch; `.so` не commit.
  Compile gate закрыт, поэтому разрешён M8-E. Системный `JAVA_HOME` всё ещё может ссылаться на D:;
  APU build должен явно использовать JBR21 на C:, пока environment отдельно не исправлена.
- **2026-08-18 (доп.205) — M8-E slice 1 source: bounded WorkManager wake:** добавлен unique
  `RelayWakeWorker`: periodic 6h/flex1h/initial30m, constraints connected+battery-not-low, без
  exact alarm/новых permissions/foreground service/быстрого retry. Worker после identity gate
  устанавливает Keystore at-rest key, поднимает durable Rust на максимум 25 s, делает один gossip
  discovery, логирует custody/quarantine и shutdown-ит engine в `finally`. `RustBridge.initialize`
  и `shutdown` синхронизированы; `runBoundedRelayWake` удерживает lifecycle monitor на всём окне,
  поэтому service не создаст второй engine. Если engine уже принадлежит foreground service, worker
  только trigger-ит gossip и не останавливает его. Следующий gate — Windows compile только этого
  Kotlin slice; до PASS не начинать sleep hook/UI budgets.
- **2026-08-18 (доп.206) — M8-E slice1 compile + signed APK PASS; первый phone preflight invalid:**
  slice1 state `36B20E5C…266C3`, debug APK 29,420,535 B / `7D48EF02…9779C`.
  Signed test v11.16.17 release build (lintVital excluded после известного aggregate-task defect)
  PASS: 22,779,828 B / SHA `246ED135…FCE44`, signer `F843CBE7…A4A5F7`, unpublished.
  Первый read-only phone paste не выполнился как block из-за PowerShell parser error на chained
  `.Trim().ToUpperInvariant()`; пользователь затем вставил остаток построчно с null variables и
  получил ложный печатный `PASS`/state `F878…`, где phones пусты. Этот state НЕ evidence; ADB/phones
  фактически не затронуты. Чтобы исключить повтор paste hazard, добавлен проверяемый файл
  `scripts/m8e_phone_readonly_preflight.ps1` (v3 state/capture names): exact 3-phone visibility,
  pull installed base APK read-only, signer/version/install-time/UID/PID capture; никаких install,
  launch, force-stop, log/data clear. V3 реально стартовал и безопасно остановился на Анне:
  installed signer `40448024…3A186` != target `F843CBE7…A4A5F7`; install запрещён, phone changes=false.
  Повтор v3 правильно заблокирован immutable evidence. Добавлен read-only v4 signer inventory всех
  трёх телефонов без требования совпадения: группирует cert digests и версии, чтобы найти общий
  upgrade path/правильный historical keystore; по-прежнему только `adb pull` installed APK на PC.
  V4 PASS state `15DD85A…5E718B`: все 3 уже v11.16.17, один signer group
  `40448024…3A186`, target release signer F843 несовместим. M8-E debug APK signer 4044 совпадает,
  но его default version был v11.16. Пользователь затем явно разрешил удалить приложения и все
  тестовые данные/cache на всех телефонах и поставить начисто. Добавлен guarded destructive script
  `scripts/m8e_clean_phone_install.ps1`: сначала строит/проверяет distinct debug v11.16.18 с signer
  4044, затем uninstall+clean install+controlled launch Anna/Zhenya/Stas, сохраняет partial state
  после каждого телефона. Uninstall здесь является явным удалением data/cache; отдельный clear не
  нужен. Force-stop/log clear не используются. Clean install v11.16.18 PASS 3/3: state
  `20FDAC7F…05F110`, APK 29,299,613 B / `3A06F85A…2CCCD`; data/cache deleted, controlled launch
  PIDs Anna/Zhenya/Stas 15427/31191/27276. На Анне Android restore оставил stale identity flag
  при пустых чатах; пользователь подтвердил targeted `pm clear` только Анны, reset state
  `C6CBBC56…7A94CB`, затем вручную создал профиль/permissions; Женя/Стас не менялись. Все три
  профиля теперь готовы. Добавлен read-only `scripts/m8e_phone_readiness_gate.ps1`: проверяет
  current PID/version, current-process log markers schedule+engine+durable-encrypted/quarantine0,
  WorkManager JobScheduler visibility и отсутствие fatal; phone writes отсутствуют. Первый readiness
  остановился на Anna из-за выпавших ранних log markers, но доказал v11.16.18/PID/appId/Job/fatal=false.
  Follow-up names/sizes-only `run-as` PASS state `8655827E…E8ACA`: все 3 имеют profile prefs,
  wrapped-key prefs, SQLite+WAL+SHM и WorkManager jobs; phone changes=false. При этом обнаружен
  blocker: фактический файл назывался `apu_relay.sqlite.relay.sqlite` — Rust ошибочно добавлял
  `.relay.sqlite` к exact host path `apu_relay.sqlite`, а Android backup rules исключали только
  exact host filename. Это могло включить device-bound encrypted custody в backup. Исправление:
  `open_relay_custody` теперь открывает переданный path без suffix. M8 ещё не release, test data
  disposable; до нового Rust build/clean install/runtime gate acceptance не продолжать. Path-fix
  build PASS: state `A6DDD4ED…450D5F6`, new `.so` `08E5153E…710C4`, debug v11.16.19 APK
  29,299,613 B / `811E2D3F…48BF02`, ADB/phones=false. Добавлен guarded clean-install v11.16.19
  script; он требует exact build evidence/hashes, удаляет v11.16.18 data/cache на 3 тестовых
  телефонах и сохраняет partial state после каждого. Clean v11.16.19 install PASS state
  `8985031B…C1DAF1`, 3/3. На Анне профиль+чаты снова автоматически восстановились, тогда как
  Женя/Стас получили onboarding: это доказало второй blocker — manifest имел `allowBackup=true`
  и вообще не ссылался на подготовленные XML rules. Device-bound Keystore identity нельзя
  восстанавливать отдельно от keys; Room chats + stale p2p_prefs приводят к skip onboarding.
  Исправление: `allowBackup=false`, `fullBackupContent=false`, подключён `dataExtractionRules`,
  а cloud/device/legacy rules исключают все eligible domains. Android compile PASS: state
  `5D886A5C…754E08`, debug v11.16.20 APK 29,299,817 B / `8FA37AB3…FC3E3A`, phones=false.
  Добавлен guarded no-restore clean-install test: uninstall/install/launch 3 phones и до onboarding
  names-only assert, что `p2p_prefs.xml`, exact relay DB и old double-suffix DB отсутствуют.
  Первый run остановился после clean install+launch Анны, потому что пользователь успел пройти
  onboarding до 6-second assertion; state `64BA6573…1E566` пуст по phones и immutable. Read-only
  inventory доказал: Anna v11.16.20/new firstInstall, exact relay files 3/wrong0; Zhenya/Stas ещё
  v11.16.19 exact3/wrong0. Визуально Anna действительно показала onboarding, значит no-restore
  сработал; её не трогать. Recovery только Zhenya/Stas остановился после Android install-confirm
  на Жене: пользователь нажал только системную кнопку установки, затем через 6 s profile=1/relay=3,
  значит старый backup dataset всё ещё восстановился вопреки новой policy (старый backup создан
  предыдущей allowBackup=true версией/OEM restore semantics). Стас не затронут. Добавлена runtime
  защита `DeviceIdentityMarker` в `noBackupFilesDir`: создаётся только при успешном onboarding;
  `MessengerApplication` до Room/services удаляет restored p2p prefs, Room DB, relay/key files,
  если identity_created есть, а device marker отсутствует. `CheckIdentityUseCase` требует оба
  доказательства. v11.16.21 compile PASS (`001E12F3…74513`, APK `840B8CCF…38616`) до phone install.
  Pre-install review выявил migration hazard: marker отсутствует и у легитимных pre-marker users,
  поэтому unconditional discard стёр бы их при обычном update. Исправлено до установки v11.16.21:
  `RelayAtRestMasterKey.hasUsablePersistedKey()` делает read-only unwrap только уже существующим
  Keystore key. Если usable — создаётся marker и identity сохраняется; если blob restored, а key
  после uninstall отсутствует — stale state удаляется. Нужен новый compile v11.16.22; v11.16.21
  на телефоны НЕ ставился. Safe migration compile v11.16.22 PASS: state `32AA6AED…C90FA9`,
  APK 29,299,813 B / `E89935E8…113ADC`, phones=false. Differential phone gate prepared:
  replace-install all 3 (no uninstall); Anna v20 + Stas v19 legitimate usable Keystore identities
  должны получить marker/preserve profile, Zhenya v20 restored-unusable identity должна быть
  discarded/show onboarding. Gate partial: Anna v22 preserved profile/marker/exact3; Zhenya v22
  визуально получил onboarding, boolean inventory доказал identity=false/marker=false/wrong0,
  хотя пустая relay DB успела создаться — harness ошибочно требовал relay=0. Стас затем отдельно
  safely migrated v19→v22 PASS state `1CD027A8…4DDDD4`: identity/marker true, exact3/wrong0,
  data clear=false; Anna/Zhenya unchanged. Пользователь зарегистрировал Женю. Добавлен final
  read-only readiness gate всех 3: v22, identity+marker, key prefs, exact DB, custody durable/
  quarantine0 log, WorkManager job, no fatal. Старые startup markers отсутствовали; `am kill`
  не смог завершить foreground process. Controlled `run-as kill -9` доказал на Anna реальную
  process death/restart: PID 28371→12023, engine/durable-encrypted/quarantine0/exact path PASS,
  wrong0/fatal0. Gate остановился до Zhenya, потому что её процесс уже не работал — это допустимый
  cold-start case, а не failure. Добавлен resume: immutable Anna PASS + Zhenya/Stas, где absent
  process запускается cold, running process получает own-UID SIGKILL; force-stop/clear не используются.
- **2026-08-18 (доп.207) — strict durable chain выявил relay cleanup defect после delivery:** exact
  message `710a724f-a560-434d-b084-9bb907cae65e` / `M8-DURABLE-20260818-01`: Anna retained+
  outbound PASS, Zhenya stored PASS, после SIGKILL PID 28956→10265 restored durable PASS, Anna
  offline, Stas вернулся online и получил/saved ровно один durable message + sent receipt PASS,
  UI подтверждает два разных сообщения (online+durable), дубля нет. Но delayed DB capture
  `EA473A34…7417E2` доказал Zhenya `targetMessageCount=1`, тогда как Stas=0+tombstone1:
  receipt публиковался только в retained unique-origin topic, поэтому online intermediate relay
  не видел его и не чистил custody. Исправление: recipient после authoritative retained origin
  receipt дополнительно публикует non-retained hashed cleanup fanout `p2pm2/receipt/cleanup/<sha256>`;
  wildcard-subscribed online relays применяют существующий receipt removal+tombstone path. Offline
  relay сохраняет TTL fallback. Добавлен topic-bound test; Rust/Gradle v11.16.23 build PASS:
  state `6F2E47D2…6A51CB`, `.so` `6C3C12EA…B36F78`, APK 29,316,197 B /
  `39111403…A0B30`, phones=false. Anna вернулась online и визуально получила eventual two ticks
  для старого exact message. Guarded replace-update v11.16.23 PASS 3/3 state
  `AC98D29F…62879C`, identity/marker preserved.
- **2026-08-19 (доп.208) — M8 durable three-phone chain + intermediate cleanup PASS:** новый exact
  message `64a5829d-e6a6-44da-92a7-6fda14338a35` / `M8-CLEANUP-20260818-02`: offline Stas,
  Anna retained+accepted, Zhenya stored; Stas online получил/saved exactly one, sent retained
  origin receipt + cleanup fanout; Zhenya получил `p2pm2/receipt/cleanup/<sha256>`, RAM removed=true,
  durable removed=true, failures/fatal=false. Phase-B state `8AB2CE34…57DD17`; UI exactly one.
  Anna вернулась online, eventual two ticks. Final read-only SQLite state
  `8CD48812…B2526A`: Anna/Zhenya/Stas targetMessageCount=0, tombstone=1, quarantine=0 на всех.
  Это закрывает основной M8 durable 3-phone delivery/process-death/exactly-once/cleanup gate.
  Не закрыты: фактический WorkManager wake при stopped foreground service, delayed relay D+reboot,
  mixed N↔N-1 и security/release gates. Старый message у Zhenya останется до TTL (до fanout fix),
  это зафиксированный pre-fix evidence, не новый cleanup failure. Пользователь после PASS явно
  решил, что проверок достаточно, и выбрал публичный **stable/Latest v11.16.23**, затем verified
  external backup. Добавлен PC-only signed stable release build gate: exact tested source/native/
  binding, release signer F843…, lintVital aggregate excluded, staging `C:\\APU-RELEASE-v11.16.23`;
  публикация/ADB/phones отсутствуют до отдельной digest verification. Signed stable build PASS:
  state `8981A23C…ABBF759`, APK 22,796,416 B / `85480D5C…EA9A68`, signer
  `F843CBE7…A4A5F7`, staging `C:\\APU-RELEASE-v11.16.23`, published=false. Release notes/statistics
  подготовлены; следующий шаг — docs commit → GitHub draft → browser upload APK+sha256 → server
  digest verify → publish stable/Latest → verified external backup. **v11.16.23 опубликован stable/
  Latest:** <https://github.com/vzhem/APUMIR/releases/tag/v11.16.23>, tag target `09f7f52`,
  draft=false/prerelease=false; APK server digest `85480D5C…EA9A68` совпал, checksum asset 86 B /
  server digest `c83d9d38…a2800c` соответствует deterministic checksum bytes. Следующий шаг — только
  verified external backup/rotation; release/tag/assets не заменять. USB preflight PASS: Disk 2
  serial `2240331293279315997`, E: `APU_RECOVERY` NTFS Healthy/clean, previous backup state
  `C8DC7311…D4BEAAA` unchanged. Добавлен compact latest backup harness: release assets, source zip,
  all-refs bundle+restore rehearsal, tested native, new M8/release evidence (старые huge compiler
  caches остаются в previous), full SHA-256 manifest; previous backup не удаляется и диск не
  форматируется. **External backup PASS:** `E:\\APU_BACKUP_v11.16.23_20260819`, 95 files /
  76 evidence items, APK hash exact, bundle verify+restore rehearsal PASS, previous retained;
  backup-state `73633F88…965F5F`, manifest `6AC2CEBD…9C17B5`, E: NTFS Healthy/OK clean.
  Release+backup milestone завершён; эти каталоги/manifest/state не удалять и не перезаписывать.
- **2026-08-19 (доп.209) — идея direct referral growth/status добавлена в roadmap, code не менялся:**
  пользователь хочет персональную реферальную ссылку и награды на порогах
  `1/3/10/20/30/50/100/200/300/500/700/1000`. В `MASTER_PLAN_v2` добавлена Фаза 2.5.2A:
  signed opaque direct token в Invite Kit, qualification только после identity+контакт+handshake+
  DELIVERED (не за install), D7 confirmation high tiers, exactly-once signed receipts, local
  encrypted count/optional blinded registry, offline sync, token rotate/revoke и anti-replay.
  Статусы от «Первый связной» до «Создатель сети», косметические/opt-in награды без transport/
  security/pay-to-win преимуществ. Только direct referrals, без multi-level/денег/contact scraping;
  privacy, anti-fraud, accessibility, boundary tests и pilot 50–100 — обязательные gates.
- **2026-08-19 (доп.210) — следующий serious direction объединяет Invite Kit и referrals; crypto
  сначала:** пользователь подтвердил публичный release APK на четвёртом телефоне Vladimir и не
  хочет тратить день на мелкие post-release checks/старые версии. Existing-flow audit: parser уже
  поддерживает custom/legacy/Telegram, но нет official HTTPS App Link/pending invite; share UI
  отправляет три раздельные ссылки и ещё использует APUMIR text. Критический blocker: текущий
  `ffi::CryptoManager.sign/verify` — prototype `sig_+hash`, verify не связывает public key; его
  запрещено использовать для referral/security claims. В roadmap добавлен R0.5 real Ed25519
  identity signing + device-bound lifecycle/migration до signed referral R1. Legacy unsigned invite
  остаётся contact-only и никогда не даёт reward.
- **2026-08-19 (доп.211) — referral R0.5 slice 1 source:** добавлен pure Rust
  `crypto/referral.rs`, пока без engine/UniFFI/UI/wire. `ReferralInviteClaimsV1` получает exact
  domain-separated canonical binary payload (`apu-referral-invite-v1`), scope direct-friend,
  canonical `pk_<sha256(ed25519_pub)>`, 16-byte nonce, created/expiry и max lifetime 30 дней.
  `sign_referral_invite_v1` использует настоящий `Ed25519KeyPair`; verify проверяет node↔pubkey,
  clock skew/expiry/lifetime/nonce и 64-byte signature. 7 tests: deterministic/domain, real roundtrip,
  modified claim, wrong key/binding, malformed key/signature, time/nonce bounds, wrong signer.
  Private key не сериализуется/не логируется. Slice намеренно не подключён к prototype engine.
  Windows Android Rust release compile PASS (`Finished release`, errors=0), но `.so` закономерно
  unchanged: linker удалил неэкспортируемый foundation module; parent gate state `243DFB68…9482C`
  честно INCOMPLETE до Gradle. Добавлен recovery: не повторяет release build, выполняет Android
  `cargo ndk check --tests` для typecheck всех 7 `#[cfg(test)]` tests, затем Gradle debug; unchanged
  native теперь explicit expected. Recovery state `DB3A6269…C71C25` остановился до Gradle:
  Android test check потребовал host proc-macro/build-script linking, но после вывода неисправного D:
  на C: нет `link.exe` (`error: linker link.exe not found`). Production Android release compile
  остаётся PASS; test blocks ещё не typechecked/executed. Перед identity migration нужен новый
  MSVC Build Tools workload на C: (не D:) и guarded resume; не маскировать environment failure.
  Winget и direct signed Microsoft bootstrapper оба timeout/TLS fail до download; установка не
  началась. Arena GitHub App также не имеет `workflows` permission, поэтому новый CI workflow нельзя
  безопасно push-нуть. Выбран bounded обход без ослабления теста: feature-gated Android executable
  `referral-selftest` (не входит в normal build) должен исполнить те же 7 real Ed25519 scenarios на
  Android. Первый package build снова потребовал p2p-core build.rs host linking после Cargo.toml
  change и остановился до adb (`link.exe` missing); app/data не менялись. Добавлен standalone tool
  crate без build.rs/UniFFI/unrelated native deps, который path-компилирует **exact production**
  `keys.rs` + `referral.rs` и использует общий release target cache; Android v2 gate строит/запускает
  его на Стасе и удаляет binary. V2 state `47213A1C…61BA8` остановился до build/adb: cargo-ndk
  запускает metadata из current directory и не увидел nested manifest. V3 из правильного tool dir
  всё равно потребовал host proc-macro linker; state `3C97A378…B80CAC`, до adb не дошёл. WSL audit:
  `WSL_E_WSL_OPTIONAL_COMPONENT_REQUIRED`; новую ОС ради одного test gate не устанавливаем. После
  трёх bounded workaround attempts остановились: production Android compile PASS, runtime tests
  честно pending до supported linker environment; app/phone data untouched.
  Identity lifecycle audit оформлен в `IDENTITY_SIGNING_MIGRATION.md`: текущий CryptoManager и
  pk/sk persistence — prototype, Kotlin сохраняет node_id как private; legacy routing ID менять
  нельзя. Принят sidecar migration: stable legacy routing ID + device-bound wrapped Ed25519 seed,
  self-signed TOFU binding, pin/dual-signed rotation, install-before-engine, domain-specific UniFFI
  и slices S2–S5 с data-preservation/security gates. Local Build Tools — отдельный maintenance.
- **2026-08-19 (доп.212) — identity signing S2 Kotlin storage source (ещё не active):** pure strict
  envelope v1 `[version][IV12][ciphertext32+GCMtag16]`; `IdentitySigningKeyStore` использует отдельный
  Android Keystore AES-256-GCM alias `apu_identity_signing_wrap_v1`, domain AAD, random 32-byte
  Ed25519 seed, commit-before-return и `withSeed` zeroization. Existing blob без usable Keystore key
  = `UNAVAILABLE`/exception, без silent overwrite/rotation; `mode()` read-only. Добавлены 5 JVM
  framing tests (layout, lengths, every truncation/trailing, version, aliasing). Нет вызова из app/
  Rust/engine: текущая версия не создаёт seed и не меняет profiles. Kotlin gate PASS state
  `E6AEDB6C…4BD825`, 5 JVM tests + Gradle, APK `BC703942…67570D`, Keystore/ADB/phones=false.
  Android test APK build PASS state `0172D6E5…8F2A8`; instrumented test on Stas PASS (`OK (1)`,
  log `BFA8FF7E…4FFC21`): stable real Keystore roundtrip, lambda-array zeroization, tamper/no overwrite,
  missing alias/no rotation. Harness ожидал физическое отсутствие prefs после `clear`, но Android
  оставил empty XML; wrapped seed отсутствовал. Test cleanup исправлен на `deleteSharedPreferences`.
  Recovery state `93060511…4F4C8`: Stas profile SHA unchanged `66F65581…9C4245`, identity/marker
  preserved, test package removed, wrapped seed false, v11.16.24 PID 17574, no clear/force-stop.
  **S2 закрыт.** S3 source начат: Rust `InstalledSigningIdentity` v1 хранит real keypair + stable
  legacy routing ID + public/key-id; strict lower-hex pk_32/64 validation, redacted Debug, validate-
  before-replace global Arc registry/snapshot/clear. UniFFI seam install/clear/mode/public-key/key-id;
  incoming seed Vec wiped, private bytes не возвращаются. Rust/bindgen/Gradle PASS state
  `7BE02D31…37E34B`: native `73FC4D6D…7AE97B`, binding `80C5AF87…47E629`, APK
  `2577AD31…6FF6C7`; generated binding commit `fc4199e`, `.so` not commit.
  Startup wiring source: service и bounded worker устанавливают sidecar до engine; onboarding —
  сразу после legacy node generation. Mode/public/key-id SHA invariant checked; failure честно
  legacy-only, node_id unchanged. Pre-wiring instrumented test помечен `@Ignore`, потому что default
  alias теперь production state; rerun без isolated namespace запрещён. Первый startup build gate
  остановился до Gradle/evidence: binding уже tracked/clean; precheck исправлен. Startup wiring build
  PASS state `44F038EF…B46DE2`: app `25DB5434…40DBF1`, test `52EBE71F…4D2287`, tests/Gradle PASS,
  phones=false. Read-only startup instrumentation: READY sidecar, stable double install,
  public/key-id SHA invariant и unchanged legacy node_id. Добавлен one-phone Stas gate: replace
  update v11.16.24→v11.16.26, exact profile/node hashes, sidecar blob stability через SIGKILL,
  instrumentation/logs; no uninstall/data clear/force-stop. Stas migration PASS state
  `28CDCE95…D7FD4B`: v11.16.26, profile/node/sidecar stable, instrumentation+SIGKILL restart PASS,
  no clear/force-stop. **S3 install-before-engine закрыт.** Next S3B — canonical persisted self-signed
  TOFU binding до signed invite output. Пользователь также заметил, что update notification мог
  перестать появляться; regression в MASTER_PLAN Phase 0.5, проверить отдельно на published N-1
  release (debug v11.16.24+ не является валидным кейсом).
- **2026-08-19 (доп.213) — S3B TOFU binding Rust source:** canonical domain
  `apu-identity-binding-v1`, exact `[version,len,legacy,public32,created_i64,signature64]`, real
  self-sign/verify, strict no trailing/truncation/tamper parser, installed legacy/public/key-id match.
  UniFFI create/verify/matches APIs; Rust/direct-bindgen/Gradle PASS state `834FDAF8…1A7F14`, native
  `3A0B876A…DE2172`, binding `A4A718A5…6AAE7A`, APK `8BB7DFC7…41935B`; generated binding commit
  `f751474`, `.so` not commit. Kotlin persistence source: create-once Base64 binding beside seed,
  verify signature+installed match every startup, malformed/mismatch never overwritten, diagnostics
  only binding SHA. S3B phone PASS state `2E6BD9D3…413AA4`: Stas v11.16.28, profile/node/seed/
  binding stable, instrumentation+SIGKILL restart, no clear/force-stop. Первый phone gate paste
  parser-error до ADB (`\"` вместо PowerShell quoting) был исправлен. Cross-device test source:
  Anna verifies Stas binding as valid foreign/non-local, tampered signature rejected; binding bounded,
  not persisted. Первый persistence build precheck дважды
  безопасно остановился до Gradle/evidence: harness ожидал binding dirty, но `f751474` уже tracked;
  исправлено на exact one dirty native file. One-phone restart/tamper gate pending.
- **2026-08-19 (доп.214) — S3B cross-device TOFU gate PASS, этап закрыт:** Anna
  `AUYF6R5923006121` обновлена in-place v11.16.23→v11.16.28, Stas `11567254BK001192` сохранил
  прежний binding. State `349D523C…DFE2F`: foreign valid/local/tamper `True/False/Rejected`, Anna
  profile/node preserved `True/True`, Stas binding unchanged; uninstall/data clear/force-stop
  `False/False`. Это закрывает create-once persistence + local match + cross-device verification;
  dual-signed rotation вынесен в отдельный S3C и PASS не приписывается ему.
- **2026-08-19 (доп.215) — R1 identity-bound wire source начат:** исправлено важное различие
  migration-модели: referral теперь использует стабильный legacy routing `node_id`, а не требует
  ошибочного `node_id == SHA-256(sidecar public key)`. Добавлен bounded binary envelope
  `[v1,binding_len,binding,nonce16,created_i64,expires_i64,signature64]`: сначала проверяется
  self-signed S3B binding, затем exact legacy-ID/public-key/timestamp binding и Ed25519 claims.
  Добавлены roundtrip, foreign-binding, signature tamper/trailing-byte tests. Host Rust toolchain в
  Arena sandbox отсутствует; production Android Rust compile gate ещё обязателен.

---

---

## 8. Сборка и тест APK на Windows (гэтчи)

- **PowerShell variables case-insensitive; `$PID` read-only:** локальная `$Pid` — это та же
  automatic variable `$PID`, поэтому assignment упал после cold-start wait. Для Android process
  всегда использовать `$ProcessId`/`$AndroidPid`; после trap не restart/clear, а дочитать logs.
- **Windows PowerShell 5: отдельный `else` после уже выполненного блока `if` не парсится** →
  `else is not recognized`. Давать `if { ... } else { ... }` одним цельным paste-блоком либо
  вообще не использовать отдельный `else`. Если ветка `if` уже успешно выполнилась, эта ошибка
  сама по себе не отменяет её результат.
- **`throw` в одном интерактивно выполненном блоке не отменяет следующие отдельно вставленные
  блоки:** пользователь может после failed precheck невольно выполнить save/clear/`PASSED`.
  Критические тестовые сценарии оборачивать целиком в один `& { ... }`, ставить
  `$ErrorActionPreference = "Stop"`, сохранять state и печатать PASS только внутри него после всех
  assertions. Следующая фаза обязана проверять explicit `baselineComplete=true`, а не текст PASS.
- **Python helper с `sys.argv[1]` требует сам аргумент:** вызов `py -3 $PythonPath` без
  `$SecurityStatePath` дал `IndexError` до создания MQTT client/connect/publish. Не повторять
  вслепую после pre-publish marker: сначала доказать `publishConfirmed=false` и ноль test ID в
  phone logs, записать failed-before-network recovery, затем исправить вызов на
  `py -3 $PythonPath $SecurityStatePath`.
- **`dumpsys package` identity key зависит от Android:** install preflight ожидал только exact
  `userId=...` + `firstInstallTime` и остановился до install; некоторые версии печатают `appId`
  либо time в другой секции. Сначала read-only вывести только safe matching keys и
  `cmd package list packages -U`; parser должен принимать `userId|appId`, проверять ambiguity и
  всё равно сравнивать UID + firstInstallTime до/после. Не ослаблять data-preservation gate.
- **Пустой native stdout через pipeline может не создать snapshot-файл:** конструкция
  `adb ... | Set-Content $Path` на Стасе вернула no output, поэтому `Set-Content` не вызвался и
  последующий `Get-Content` упал, хотя `$LASTEXITCODE=0`. Сначала получать `Out-String`, затем
  явно писать через `[IO.File]::WriteAllText(...)`; отдельно проверять `Test-Path`. Failed
  snapshot не означает failed publish: доверять persisted `setupPublishConfirmed` и не повторять.
- **ConnAck может вытесниться из logcat до следующего интерактивного шага:** поздний ConnAck
  Стаса был доказан, но ранний startup snapshot снят до него, а live ring позже вытеснил строку;
  повторный gate дал ложный missing ConnAck. После важного позднего события сразу сохранять
  snapshot/state marker. Если строка уже потеряна, live `MQTT: peer online` после последнего
  `MQTT error` логически доказывает действующее subscribed connection; не требовать недоступную
  старую строку и не перезапускать лишь ради лога.
- **PowerShell parser/precedence вокруг `+` требует скобок:** function calls у `+` без скобок
  дали parser error; позже `'<template-a>' + '<template-b>' -f args` применил `-f` только ко второй
  строке и оставил literal `{0}` для `cmd`. Использовать `(Call ...) + (Call ...)`, а format сначала
  собирать целиком: `$Template = 'a{0}' + 'b{1}'; $Command = $Template -f $x,$y`. Для длинной
  диагностики безопаснее записать Python/`.ps1`, сначала compile/parse, затем выполнить;
  PASS/state/clear остаются только внутри валидного файла.
- **Python stdout в Windows PowerShell cp1251 не печатает любой Unicode из logcat:** raw line с
  box-drawing glyph дала runtime `UnicodeEncodeError` после верного подсчёта, но до state save и
  log clear; `py_compile` такое не ловит. Automation должна выводить только ASCII metrics/
  `json.dumps(..., ensure_ascii=True)`, либо использовать `backslashreplace`; raw logs уже лежат
  в snapshot и не должны печататься в консоль.
- **`pidof <package>` может вернуть несколько PID через пробел:** exact string equality дала
  ложный restart на Жене (`8350 24000`), хотя ожидаемый cache-owning PID `24000` оставался в
  списке и attack snapshot был снят именно с него. Сначала разбирать PID как tokens и требовать
  membership expected PID; при новом PID отдельно смотреть `/proc/<pid>/cmdline/status` и `ps`,
  потому что auxiliary/duplicate process может влиять на MQTT. Не повторять уже sent attack.
- **Paho connect timeout до строки `client.publish` означает ноль publish, но marker уже true:**
  control helper сохранил attempt, затем 10 s не получил connection к HiveMQ и упал до publish.
  Не retry вслепую: проверить `confirmed=false`, ноль control ID в 3 phone logs и bounded TCP
  reachability; записать pre-publish failure. Только после этого разрешён один controlled retry
  того же ещё не опубликованного ID. Fallback broker бесполезен, если телефоны сидят на HiveMQ.
- **Windows PowerShell 5 + redirected native stderr + `$ErrorActionPreference="Stop"` может дать
  ложный terminating `NativeCommandError` до проверки exit code.** Не только `java -version`:
  Cargo/cargo-ndk progress, Gradle/JVM, Git fetch и ADB diagnostics также могут штатно писать в
  stderr. В critical harness не использовать прямое `(& native 2>&1)`; запускать через
  `Start-Process` с separate stdout/stderr, затем проверять ExitCode + command-specific outcome +
  positive marker/artifact/hash. `pidof exit=1+empty`, `git diff --quiet exit=1`, `git grep exit=1`
  имеют свои узкие нормальные значения; остальные nonzero не разрешать глобально. Полная матрица
  и шаблон — `docs/BACKUP_AND_CLEAN_PC_RECOVERY.md` §6.
- **Прерванный milestone-backup не удалять вслепую:** пока есть `INCOMPLETE.tmp`, проверить
  уже созданные bundle/source/artifacts и их hashes, продолжить с точки остановки, заново создать
  `SHA256SUMS.txt`, и удалять marker только после завершения. Отдельная независимая verify-фаза
  всё равно обязательна. Полный recovery — `docs/BACKUP_AND_CLEAN_PC_RECOVERY.md`.
- **Windows `git archive` может положить text files в ZIP с CRLF:** тогда
  `git hash-object --no-filters <extracted>` не совпадает с LF blob commit и даёт ложную ошибку.
  Проверять двумя независимыми способами: USB bytes совпадают со свежим `git archive` того же
  commit по SHA-256; `git hash-object --path=<repo-relative> <file>` после clean-фильтра совпадает
  с `git rev-parse <commit>:<path>`. Для `v11.16.10` оба условия прошли на 4 файлах.
- **`git fsck --connectivity-only` может показать `dangling commit` из stash/доп.refs:** если
  exit code=0 и connectivity завершена, это информационное сообщение, не повреждение bundle.
- **PowerShell ломает аргументы вида `-Pname=value` для `gradlew.bat`** → ошибка
  `Task '.16.5' not found`. **Лечится**: задавать версию через env-переменную
  `$env:GITHUB_REF_NAME = "v11.16.X"` (build.gradle.kts её читает), БЕЗ `-P`.
- **Windows Defender (антивирус) вызывает ВРЕМЕННЫЕ падения сборки**: краш JVM
  (`zip.dll`, `EXCEPTION_IN_PAGE_ERROR`), падение `kapt`, падение `lint`
  (`unsafe memory access` / `Could not initialize class org.jetbrains.uast.UastFacade`),
  «corrupt cache». **Обычно проходит повторным запуском**; для надёжности — добавить папку
  проекта и `C:\Users\<user>\.gradle` в исключения Windows Defender.
- **`lintVitalAnalyzeRelease` падает на release-сборке** → для ТЕСТА собираем без lint
  (`-x lintVitalAnalyzeRelease -x lintVitalReportRelease`), предварительно `.\gradlew --stop`
  (сбросить зависший lint-сервис). Lint не нужен для тестового APK.
- **Ставить тестовый APK поверх релиза v11.16.4 БЕЗ потери данных**: только **release-APK
  тем же ключом** (`p2p-release.jks` есть в репо) и с версией **выше** (v11.16.5 →
  versionCode 11_016_005 > 11_016_004). Debug-APK потребовал бы удаления → потеря данных.
- **Команды adb для теста** (телефоны по USB, отладка включена):
  - список подключённых: `adb devices`
  - установка на конкретный: `adb -s <serial> install -r <apk>`
  - установка (если один телефон): `adb install -r <apk>`
- **Тест-сценарии пишем по именам телефонов** (см. раздел 4): «Анна пишет Стасу, Стас
  выключает телефон» и т.п. Если появился новый телефон — спросить имя и привязать к serial.
- ⚠️ Это НЕ баг PowerShell как таковой: после `-P` (починено env-var) остальные падения —
  это Gradle/JVM + антивирус. Менять оболочку на cmd не лечит lint/краши кэша.

- **Хостовый `cargo test` в `rust-core` НЕ работает** на этой машине: падает на сборке
  C-крипто-либ `ring`/`aws-lc-sys` через MSVC (`cl.exe ... exit code 2`). При этом
  **`build-rust.ps1` (сборка под Android через NDK) работает**. Значит: Rust-логику
  проверяем через **`build-rust.ps1` (компилируется ли) + тесты на телефонах**, а НЕ через
  хостовый `cargo test`. В sandbox агента `cargo`/`rustc` тоже нет.

### 8.1. Эталонная команда сборки тестового release-APK

```powershell
Set-Location C:\APUMIR-arena-test\android-app
.\gradlew --stop
$env:GITHUB_REF_NAME = "v11.16.5"
.\gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease
```

APK появится: `app\build\outputs\apk\release\app-release.apk`

### 8.2. Журнал (продолжение)

- **2026-08-13 (доп.3)** — сборка release-APK на Windows: PowerShell ломает `-P` → версия
  через env-var; Defender вызывает временные падения (zip.dll / kapt / lint / corrupt cache)
  → повторный запуск или исключения Defender; `lintVitalAnalyzeRelease` падает → тестовый
  APK собираем без lint; поверх v11.16.4 ставим только release-APK с версией выше.
- **2026-08-13 (доп.4) — КРИТИЧНО:** тестируемый relay `https://p2p-relay.1985vzhem.workers.dev`
  ОТВЕЧАЕТ (`/health` → ok), но **`/poll` и `/send` возвращают 404** — релейный inbox
  (store-and-forward) НЕ развёрнут. Это блокирует офлайн-доставку и ACK/DELIVERED (G1).
  Онлайн-сообщения ходят через MQTT/P2P напрямую (relay не нужен). Приложение ждёт от Worker:
  `GET /health`; `POST /register {node_id,public_key,display_name}`; `GET /lookup?node_id=`;
  `GET /version`; `POST /send {to,from,payload}`; `GET /poll?node=` → `{ok,messages:[{from,payload}]}`.
  Фикс = W1/W2: написать полный Worker (KV + TTL ≥30 дней) и задеплоить. Исходника Worker'а
  в репо НЕТ.
- **2026-08-13 (доп.5):**
  - **Реальный G1 найден и пофиксен в Rust:** получатель шлёт ACK `ack|messageId` через MQTT,
    но Rust-ядро отправителя его молча выбрасывало (парсился только формат сообщения из 5 полей).
    Плюс событие называлось `message_delivered`, а Kotlin ждёт `delivery_ack`. Фикс (коммит
    `0c992b9`): событие → `delivery_ack`; в обработчике `p2pm2/msg/` детектим `ack|` → эмитим
    `MessageDelivered`. ACK идёт по транспортy телефон↔телефон (соответствует принципу).
  - **ОКОНЧАТЕЛЬНОЕ лекарство от «флаковых» падений сборки** = исключения Windows Defender
    (PowerShell от администратора): `Add-MpPreference -ExclusionPath "C:\APUMIR-arena-test"`,
    `-ExclusionPath "C:\Users\<user>\.gradle"`, `-ExclusionPath "C:\Users\<user>\.cargo"`.
    Лечит ВСЕ краши: `zip.dll`, `kapt`, `lint`, `link.exe` (`0xc0000006`).
  - **Желание пользователя: desktop-версия** — нативное приложение для ПК, ставится напрямую
    (без Android-лаунчера/эмулятора). Уже в плане: MASTER_PLAN Фаза 9.6 «Desktop app на Tauri»
    + «CLI/headless relay node». Rust-core кросс-платформенный → переиспользуем. Варианты UI:
    Tauri (Rust+web) или Compose Multiplatform (переиспользование UI с Android).
- **2026-08-13 (доп.6) — ВЕРИФИЦИРОВАНО на телефонах:** ✓✓ (DELIVERED) работает — ACK доходит
  до отправителя через MQTT (фикс `0c992b9` подтверждён: Анна→Стас, у Анны появились две
  галочки). **Медленно** (~несколько секунд): причина — `send_message_mqtt` каждый раз создаёт
  НОВОЕ MQTT-соединение (connect+publish+disconnect). Окончательный фикс производительности =
  переиспользовать постоянное MQTT-соединение (умеренный рефакторинг; в очереди). Следующий
  функциональный шаг = **D3 офлайн-доставка** (телефон хранит сообщение и доставляет, когда
  получатель доступен) — основная цель пользователя.
- **2026-08-13 (доп.7) — РЕЛИЗ v11.16.5 ОПУБЛИКОВАН:** tag `v11.16.5` → `b83d9b3`; APK
  `P2P-Messenger-v11.16.5.apk` SHA-256 `993ae5eb…`; отмечен Latest. Включает: ✓✓ DELIVERED,
  recipient-aware routing (нет утечки на 3+ тел), фикс шторма, базовую офлайн-доставку.
  Workflow отключали на релиз → потом **запушили фикс с guard** (`add4b7d`, пушл пользователь,
  бот не может) + включили обратно.
- **2026-08-13 (доп.8) — mesh M0–M2 готовы:** `docs/MESH_DELIVERY.md` (дизайн), M1 `RelayQueue`
  (`network/relay_queue.rs`), M2 `network/wire.rs` (конверты relay/receipt/gsumm + base64 dep).
  Оба компилируются (`build-rust.ps1`). В `run_mqtt_transport` старый relay ОТКЛЮЧЁН (фикс
  `b83d9b3`) — чужие сообщения дропаются. M3 вернёт их правильно (через RelayQueue + дедуп).

---

## 9. M3 — подробное руководство по реализации (для следующей сессии)

**Цель M3:** реальная пересылка через третий телефон — A шлёт офлайн-B, C (online) хранит и
доставляет B, когда тот появится; receipt расходится → cleanup; A получает ✓✓. Без шторма.

### 9.1. ГЛАВНОЕ ПРАВИЛО (шторм-урок) ⚠️
**Перед любым enqueue в RelayQueue — проверить `relay_queue.contains(msg_id)`.** Если уже есть —
ПРОПУСТИТЬ (не re-enqueue, не re-relay). Это единственная защита от петли (старый relay петлил
из-за отсутствия дедупа: узел получал свою же публикацию и ставил снова). Плюс `hop_count`
(`MAX_HOPS=8`) и TTL.

### 9.2. Точки интеграции (код)
- **`rust-core/src/engine/core.rs` → `run_mqtt_transport`** — MQTT event loop. В ветке `p2pm2/msg/`:
  `ack|…`→MessageDelivered; 5-полей сообщение → `if recipient==node_id` MessageReceived, иначе drop.
- **`network/relay_queue.rs`** — `RelayQueue`: `enqueue/contains/for_recipient/digest/remove/cleanup_expired`.
- **`network/wire.rs`** — `build_relay/build_receipt/build_gossip_summary/parse` (→ `MeshEnvelope`).
- Старый `MessageQueue` (параметр `queue` в `run_mqtt_transport`) — sender-side (свои сообщения при
  peer-discovered). НЕ трогать; `RelayQueue` — отдельная сущность для ЧУЖИХ.

### 9.3. Подшаги M3 (каждый — отдельный коммит + телефонный тест)
1. **Проброс `RelayQueue`**: поле `relay_queue: Option<Arc<RelayQueue>>` в `P2PCore`, init в
   `start()`, передать параметром в `run_mqtt_transport` (как `queue`).
2. **(a) handle `relay|…`**: `wire::parse` → `Relay`: `recipient==node_id` → доставить (decode
   `e2e_payload` → текст → `MessageReceived`) + послать `receipt` origin; иначе →
   `if !contains(msg_id) && !hops_exceeded { enqueue(msg с hop+1) }`.
3. **(b) handle `receipt|…`**: `relay_queue.remove(msg_id)`; если `origin==node_id` → `MessageDelivered` (✓✓).
4. **(c) gossip**, разделён безопасно:
   - **(c.1)** на presence → адресно отправить `gsumm`; target принимает/парсит, без relay.
     Лимиты: 256 items, 64 KiB, 60 с/peer, 8 summaries/30 с global.
   - **(c.2)** сравнить summary и переслать отсутствующие relay: 16/256 KiB за round,
     32/512 KiB за 30 с, envelope ≤64 KiB, ≤64 кандидата, fair cursor. Relay-path/r1/r2
     проверены; код r3 non-retained resend + bounded dedup ждёт Windows build/test.
5. **(d) send-path**: в `send_message`, если recipient офлайн (нет addr) — собрать `relay`-конверт
   (`wire::build_relay`, `e2e_payload`=байты текста, hop=0, ttl) и опубликовать в mesh-топик →
   онлайн-узлы примут в RelayQueue.

### 9.4. Mesh-топик
`p2pm2/msg/` (переиспользовать; роутить по префиксу тега). Все подписаны на `p2pm2/#`, каждый
применяет дедуп + проверку получателя.

### 9.5. Тест M3 (3 телефона: Стас/Женя/Анна)
1. B — режим полёта. A и C онлайн.  2. A→B: A публикует `relay` → C хранит в RelayQueue.
3. A уходит офлайн.  4. B возвращается → C доставляет (B: MessageReceived + receipt).
5. receipt → C.remove(msg_id); A (вернулся) → DELIVERED ✓✓.
6. Проверить: **шторма нет** (logcat тихий), **C не показывает** чужое, B получил, у A ✓✓.

### 9.6. Цикл сборка/тест
Rust-правка → `.uild-rust.ps1` → APK (`assembleRelease -x lint…`, версия через
`$env:GITHUB_REF_NAME`) → `adb -s <serial> install -r …app-release.apk` (3 тел.) →
`adb logcat -d -s CoreServerService:I RustBridge:I`. Перед `git pull` — `git stash` (`.so` мешает).
Хостовый `cargo test` НЕ работает (ring/aws-lc MSVC).

### 9.7. Старт новой сессии
1. Прочитать **весь** `docs/AI_COLLABORATION_NOTES.md` (⚙️ принцип, 🌐 mesh, разделы 2.1, 6 и 9),
   `docs/MASTER_PLAN_v2.md` и `docs/MESH_DELIVERY.md`; перед relevant gate также прочитать
   `docs/SECURITY_RESILIENCE_TEST_PLAN.md` и `docs/BACKUP_AND_CLEAN_PC_RECOVERY.md`.
2. Проверить, что текущая Arena-ветка — только `arena/01a000bc-apumir`; не checkout старые branch
   names из исторического журнала. Не менять фиксированную ветку и не делать release/tag/PR без
   отдельного разрешения.
3. M3(a/b/c), reconnect, unique receipt, dedup/cleanup и первый low-volume security smoke уже
   проверены; не повторять. r4.4 dual runtime также PASS. Mixed N↔N-1 и r4.5 остаются release gates,
   но пользователь явно разрешил не блокировать ими M3(d).
4. M3(d) source commit `61e1580…`, Rust+Kotlin overlays и signed APK v11.16.16 уже PASS. Exact
   APK/native/state hashes — в CURRENT OVERRIDE и доп.167. Rust/transfer/recovery/Gradle не повторять
   и evidence не удалять. Windows HEAD остаётся base `8cea566…`; dirty overlay не равен Git history
   synchronization, generated `.so` не commit, icon F263…ACA9 сохранить.
5. Data-preserving install и controlled launch/readiness v11.16.16 PASS 3/3; state SHAs
   59D5…6295/6561…84E2, stable PIDs 22055/11575/11449. Не reinstall/relaunch, не clear logs.
   Automatic offline acceptance/network toggles/one manual Anna→Stas UI message уже разрешены;
   prepare harness 5c63d1b execution pending. No synthetic publish/retry.
6. Перед phone-командой назвать все три телефона и предупредить подключить; отдельного подтверждения
   подключения не ждать, но начать с exact read-only visibility/install-state/version/UID/
   firstInstall/data/process gate и остановиться до launch при absent/unauthorized/offline. Acceptance: recipient
   offline, origin app sends, third phone stores, origin disconnects, recipient returns and gets
   exactly one UI message, receipt cleans relay and origin eventually DELIVERED.
7. Активные `%TEMP%` states/logs, APK/native и hash-linked evidence не удалять. После закрытия gate
   сделать bounded inventory/cleanup; milestone-backup предлагать только после действительно крупной
   проверенной APK/этапа, не после docs-only или промежуточного Rust compile.
- **2026-08-19 (доп.216) — в конец roadmap добавлено устойчивое финансирование:** только после
  launch-ready/privacy/security gates сначала отдельное ненавязчивое меню добровольной поддержки
  разработки и инфраструктуры без ограничения функций для не-доноров. Самым последним пунктом
  добавлены privacy-first рекламные места вне private chats, поиск рекламодателей и формальный
  advertiser onboarding (verification/moderation/contract/pilot/kill switch). Запрещены message/
  contact/social-graph targeting, скрытая реклама, pay-to-win referrals и передача identity/IP.
- **2026-08-19 (доп.217) — первый R1 build gate безопасно не стартовал:** после успешного pull
  PowerShell StrictMode остановил script на scalar `.Count` до Rust/Gradle/ADB/phone changes.
  Исходный gate не повторять; добавлен recovery script с новым TEMP prefix и принудительным array.
- **2026-08-19 (доп.218) — R1 identity-bound wire production compile PASS:** recovery state
  `B6D20F8C…48B06`, arm64 native `D0B63A23…5212D`, debug APK 29,398,117 B
  `B3949CC6…9FE96`; Rust release compile и Gradle assembleDebug завершились успешно. Показанный
  `NativeCommandError` был ожидаемым отображением native stderr под Windows PowerShell и не являлся
  failure: exit code=0, итоговый outcome=PASS. Host tests/ADB/phones `False/False/False`.
- **2026-08-19 (доп.219) — R1 UniFFI source:** добавлен узкий security API create/verify/extract.
  Create использует OS CSPRNG nonce и только installed sidecar + переданный persisted binding;
  extract возвращает legacy routing ID только после полной проверки binding/signature/time. Private
  seed/public diagnostics не расширены. Добавлен native+bindgen+APK gate; runtime URL/UI ещё не wired.
- **2026-08-19 (доп.220) — R1 verified-token UniFFI build PASS:** state
  `F21D66C5…9B811`, native `836B009E…B391A`, normalized generated Kotlin binding
  `B1232420…1F9AA`, debug APK 29,414,505 B `96DA15A9…87721`. Три ожидаемых UniFFI markers
  присутствуют; Gradle PASS. `NativeCommandError`/отсутствующий ktlint — не failure, native exit=0 и
  binding отдельно нормализован. Persistence/ADB/phones `False/False/False`. Generated binding ещё
  должен пройти exact-hash acceptance и отдельный commit; `.so` не коммитить.
- **2026-08-19 (доп.221) — generated R1 UniFFI binding acceptance recovery PASS:** Windows local
  commit `95270bc` содержит ровно normalized binding `B1232420…1F9AA` и успешно отправлен как
  remote tip fixed branch через explicit `HEAD:refs/heads/arena/01a0149e-apumir`; force push/ADB/
  phones `False/False/False`. Native `836B009E…B391A` остался единственным uncommitted artifact.
- **2026-08-19 (доп.222) — R1 HTTPS token codec source:** добавлен bounded unpadded Base64URL codec
  для `https://apumir.app/i?r=…`, strict scheme/host/port/userinfo/path/fragment/duplicate checks и
  Kotlin boundary, который возвращает inviter только после Rust verify+extract. Parser сам по себе
  никогда не даёт attribution. Добавлены negative tests. `apumir.app` требует подтверждения
  владения и `assetlinks.json` до объявления Android Verified App Link; JVM/Gradle gate pending.
- **2026-08-19 (доп.223) — R1 HTTPS codec JVM/APK gate PASS:** state `ADCB4F26…D0DDB`,
  debug APK 29,458,899 B `7B3A2FE5…C4EA0`; strict parser test class executed, Gradle PASS,
  JVM tests/ADB/phones `True/False/False`.
- **2026-08-19 (доп.224) — pending referral runtime source:** verified signed referral обрабатывается
  до legacy contact-only parser, сохраняется device-local через commit и повторно Rust-verified при
  каждом чтении; invalid/expired state удаляется. Backup/device transfer уже глобально запрещены.
  Raw deep link/token/full node и registry error text больше не логируются. Добавлен HTTPS intent
  filter, но autoVerify не считается действующим до domain ownership + `assetlinks.json`.
- **2026-08-19 (доп.225) — pending runtime JVM/APK compile PASS:** state `F2AE21DF…05C8C`,
  debug APK 29,430,973 B `FCED83B9…E5D15`; parser tests повторно PASS, instrumentation/ADB/phones
  `False/False/False`.
- **2026-08-19 (доп.226) — isolated pending instrumentation source:** test namespace
  `apu_pending_referral_test_instrumented_v1` создаёт real signed token через installed sidecar,
  проверяет persist/load, signature tamper auto-clear и expiry auto-clear, затем удаляет только test
  prefs. Production pending prefs/profile/seed/binding не изменяются. Build/device gate pending.
- **2026-08-19 (доп.227) — pending androidTest build PASS:** state `42F74B58…B1731`, app APK
  29,444,062 B `902137B2…6480C`, test APK 967,832 B `E825E00E…908C5`; ADB/phones
  `False/False`. Next — data-preserving isolated test on Stas only, with production profile/node/
  signing/pending snapshots and no data clear/force-stop.
- **2026-08-19 (доп.228) — первый Stas pending phone gate безопасно остановлен на read-only gate:**
  ADB device и versionName v11.16.28 видны, но harness искал отсутствующий на этом Android формат
  `userId=` в dumpsys. Install/test/state creation/ADB mutation не начинались. Исходный gate не
  повторять; recovery-01 использует exact `pm list packages -U` UID visibility и новые evidence names.
- **2026-08-19 (доп.229) — pending referral Stas phone recovery PASS:** state
  `837DC56A…7CB5`; persist/load/tamper/expiry `PASS/PASS/PASS/PASS`, profile/node/signing/production
  pending preserved `True/True/True/True`, test prefs/package removed, no data clear/force-stop.
  Stas updated data-preserving v11.16.28→v11.16.31. R1 pending persistence slice закрыт.
- **2026-08-19 (доп.230) — secure file transfer поднят в ближайший roadmap:** сразу после закрытия
  текущего R1, до продолжения R2/Groups, запланирован bounded 10 MiB E2E streaming/chunk MVP для
  личного чата с SAF, manifest/hash/AEAD, resume/dedup/quotas и 2/3-phone acceptance. Большие bytes
  не помещаются в MQTT text envelope/Room/Compose и не загружаются целиком в RAM.
- **2026-08-19 (доп.231) — новый development PC/toolchain plan:** добавлен
  `docs/NEW_DEVELOPMENT_PC.md` с minimum/recommended/practical maximum, desktop/laptop criteria,
  обязательными Windows/Android SDK 35/JDK21/MSVC linker/Rust/cargo-ndk/GitHub/backup components и
  initial inventory. Рекомендуемый баланс: x86-64 CPU 12–16 cores, 64 GiB RAM, TLC NVMe 2 TB,
  второй/внешний backup SSD и UPS; Windows-on-ARM и рабочий build на HDD не рекомендуются.
- **2026-08-19 (доп.232) — outgoing signed share source без преждевременного public enable:**
  `IdentitySigningKeyStore` создаёт bounded seven-day token только из installed sidecar + verified
  persisted binding и сразу self-verifies; ShareProfile готов формировать HTTPS Base64URL link в IO
  context. `DEPLOYMENT_ENABLED=false` оставлен намеренно: до реального DNS/HTTPS landing и
  `assetlinks.json` пользователю продолжает выдаваться legacy contact-only link, чтобы не публиковать
  неработающий домен и не обещать referral attribution. Build gate pending.
- **2026-08-19 (доп.233) — outgoing referral share JVM/APK PASS:** state `9E359300…1767`,
  debug APK 29,430,977 B `FDD3F87E…9DED`; public deployment/ADB/phones `False/False/False`.
  Пользователь сообщил приватный candidate web endpoint; по его просьбе endpoint не внесён в repo,
  логи или публичные материалы. Deployment switch остаётся OFF до отдельной ownership/TLS/landing/
  assetlinks проверки.
- **2026-08-19 (доп.234) — Secure File Transfer F0 source начат:** authoritative design
  `docs/SECURE_FILE_TRANSFER.md`; добавлен pure Rust bounded manifest/chunk crypto foundation:
  10 MiB max, 16–256 KiB power-of-two chunks (default 128 KiB), canonical sender/recipient/name/MIME/
  geometry/hash/TTL binding и XChaCha20-Poly1305 chunk AEAD с deterministic transfer-ID/index nonce.
  Source tests покрывают boundaries, retry determinism, tamper/wrong key/index/manifest и malicious
  metadata. Transport/Room/UI/UniFFI пока не wired; Android production compile gate pending.
- **2026-08-19 (доп.235) — File Transfer F0 first gate safely stopped at known host linker blocker:**
  Android `cargo ndk check --tests` needs Windows host proc-macro/build-script linker and failed because
  `link.exe` is absent. State `059A13A9…5AFBA`; native/APK not produced, transport/ADB/phones false.
  Gate не повторять. Recovery-01 с новым evidence prefix выполняет только production Android Rust +
  Gradle; source tests не объявлять executed до нового ПК с MSVC linker.
- **2026-08-19 (доп.236) — public release text policy по прямому решению пользователя:** GitHub
  Release description должен быть коротким (главная польза + 3–6 пунктов) и пройти redaction.
  Не переносить phone serial/topology, private endpoints, node/message/referral IDs, local paths,
  evidence/state/log details, branch/recovery mechanics или secrets. Exact будущий public text всегда
  отдельно показать владельцу перед публикацией. Policy: `docs/RELEASE_PUBLICATION_POLICY.md`.
- **2026-08-19 (доп.237) — File F0 production Rust compile PASS, wrapper expectation corrected:**
  recovery-01 state `AD01EB9E…D68FB`; Rust завершил release compile и скопировал `.so`, но hash
  остался `836B009E…B391A`. Это ожидаемо: новый pure module пока не reachable из UniFFI/engine и LTO
  полностью удалил его из final native. Wrapper ошибочно требовал hash change и остановился до
  Gradle; ADB/phones false. Recovery-02 не повторяет Rust, hash-links prior state/log, принимает
  unchanged native как ожидаемый и выполняет только Gradle APK build с новыми evidence names.
- **2026-08-19 (доп.238) — File F0 production+Gradle recovery PASS:** state `9D227908…86C7B`,
  APK 29,430,973 B `F806DCDE…E0624`; prior Rust release compile hash-linked, native unchanged after
  LTO expected. Host tests/transport/ADB/phones false.
- **2026-08-19 (доп.239) — File F0 Android runtime seam source:** добавлен no-input/no-output-secret
  UniFFI diagnostic, который внутри production Rust выполняет 128 KiB XChaCha chunk roundtrip и
  rejects tamper/wrong index/changed manifest. Android instrumentation вызывает только boolean seam;
  пользовательские bytes/keys не принимает и не возвращает. Functional streaming API ещё не wired;
  native/bindgen/app+test APK build pending.
- **2026-08-19 (доп.240) — File F0 Android runtime build PASS:** state `8D556A99…95F2`, native
  `1D6478B2…4B23`, normalized generated binding `A6973996…25E7`, app APK 29,430,973 B
  `3888C58D…0245`, test APK 967,890 B `C7637E4C…0D5D`; transport/ADB/phones false. Отсутствующий
  ktlint и PowerShell NativeCommandError не failures: Rust/Gradle exit 0. Next exact-hash generated
  binding acceptance; `.so` never commit.
- **2026-08-19 (доп.241) — File F0 generated binding accepted:** Windows commit `e36d1ba` contains
  only normalized binding `A6973996…25E7` and was pushed by explicit HEAD→fixed remote ref. Native
  `1D6478B2…4B23` remains local/uncommitted; transport/ADB/phones false. Next isolated data-preserving
  runtime diagnostic on Stas v11.16.31→v11.16.34.
- **2026-08-19 (доп.242) — File F0 Stas Android runtime PASS:** state `79DE5FAB…0B3F1`;
  roundtrip/tamper/wrong-index/changed-manifest all PASS, profile/node/signing/production pending
  preserved, test package removed, app relaunched, no data clear/force-stop. Stas data-preserving
  update v11.16.31→v11.16.34. F0 on-device crypto gate закрыт; host tests ждут новый MSVC linker.
- **2026-08-19 (доп.243) — File F1 durable schema source:** AppDatabase v5→v6 получает additive
  `file_transfers` + `file_transfer_chunks` с FK cascade, bounded metadata/progress DAO и без keys,
  plaintext или filesystem paths в Room. Explicit migration добавлена перед существующим fallback;
  isolated androidTest создаёт v5 sentinel, мигрирует, проверяет preservation/new tables/FK cascade и
  удаляет только test DB. Compile/test APK и real profile-preserving migration gates pending.
- **2026-08-19 (доп.244) — File F1 schema build PASS после transient GitHub stop:** первый pull
  безопасно остановился до script. Повторный gate state `C1126464…D532F`, app APK 29,447,357 B
  `616E5CDC…E5E7`, test APK 971,810 B `099BE931…70B7`; ADB/phones false.
- **2026-08-19 (доп.245) — real production v5→v6 migration acceptance source:** instrumentation
  требует существующую `messenger_database` v5, внутри одной SQLite upgrade transaction hashes only
  legacy row IDs/counts before/after, применяет additive migration, затем открывает DB generated Room
  v6 validator и требует пустые new transfer tables. Message/contact contents не читаются/логируются;
  DB не удаляется. Rebuilt test APK и Stas data-preserving gate pending.
- **2026-08-19 (доп.246) — File F1 production migration test APK build PASS:** state
  `F79BB5ED…CB6DC`; app APK unchanged `616E5CDC…E5E7`, rebuilt test APK 976,465 B
  `759CACD7…C6C7D`; ADB/phones false. Next Stas v11.16.34 production DB exact v5→v6 migration,
  legacy ID-set/count preservation + generated Room schema validation, no DB delete/data clear/force-stop.
- **2026-08-19 (доп.247) — File F1 first Stas migration harness failed after additive SQL:** local
  state `05927DDB…0CAE`, log `9D6A2EAC…51AC`. Legacy ID/count before-after assertion and additive
  migration completed, then generated Room open rejected stale identity: expected v6
  `1600df8c…eee0`, found old v5 `451378b6…fff5`. Cause: test invoked Migration directly through raw
  SQLite helper, which advanced user_version but bypassed RoomOpenHelper identity update. App was
  installed v11.16.35; test package likely remains. No DB delete/data clear/force-stop. Gate must not
  repeat; later GitHub retries stopped before script.
- **2026-08-19 (доп.248) — migration identity recovery source:** normal untouched-v5 test corrected
  so Room owns upgrade. One-time Stas recovery requires exact stale v6+old hash+empty new tables,
  hashes legacy ID sets/counts, temporarily re-arms user_version=5, then idempotent `IF NOT EXISTS`
  Migration runs through Room, performs full generated schema validation and writes exact v6 hash;
  legacy state/new-table emptiness rechecked. No direct hash overwrite and no DB deletion.
- **2026-08-19 (доп.249) — File F1 identity recovery test APK build PASS:** state
  `0395F146…349E2`, app APK unchanged `616E5CDC…E5E7`, test APK 978,516 B
  `CF36DE70…C196`; ADB/phones false. Recovery phone gate performs no app install: exact stale
  v6/old-hash/empty-table precondition, legacy/profile/signing/pending preservation, Room-owned
  idempotent migration+schema/hash repair, test-package cleanup and stable app relaunch.
- **2026-08-19 (доп.250) — Stas File F1 Room identity recovery PASS:** state
  `2A1F901E…4E3B1`; stale v6/old hash safely re-armed, Room-owned idempotent migration validated full
  v6 schema and wrote correct identity. Legacy IDs/counts preserved, new tables empty, profile/node/
  signing/pending preserved; no app install, DB delete, data clear or force-stop. Test package removed,
  app relaunched stable. F1 migration incident closed.
- **2026-08-19 (доп.251) — F1 encrypted chunk file store source:** app-private no-backup
  `file_transfers/v1/<transfer-id>/chunks` store accepts only bounded ciphertext (16..256KiB+tag),
  strict 16-byte hex IDs/index cap, global 64 MiB quota, fsync + required atomic move, idempotent same
  ciphertext retry and reject-different overwrite. Read is exact bounded, traversal/symlink rejected,
  delete is scoped/idempotent. JVM tests cover defensive copy, retry/conflict, quota/partial, bounds,
  traversal/index and cleanup. No plaintext/key/path enters Room; build gate pending.
- **2026-08-19 (доп.252) — F1 chunk store JVM/APK gate PASS:** state `A233D405…821E`, app APK
  29,447,357 B `FDFD1B09…A910`; defensive store tests PASS, transport/ADB/phones false.
- **2026-08-19 (доп.253) — F1 SAF streaming source inspection:** pure bounded inspector sanitizes
  provider filename (control/separators, UTF-8 ≤255), normalizes/falls back MIME, rejects declared or
  measured >10 MiB and size changes, computes SHA-256 with one 64 KiB buffer then wipes it. Android
  adapter accepts only content:// and never logs/persists URI/path. JVM tests cover known/empty hash,
  metadata sanitization, Unicode boundary, MIME fallback, mismatch/oversize and read request ceiling.
  Picker UI, persistable permission and encryption pipeline not wired; build gate pending.
- **2026-08-19 (доп.254) — F1 SAF inspector JVM/APK PASS:** state `913EEA7C…3A071`, app APK
  29,463,741 B `843B046A…02D28`; inspector+chunk-store regression tests PASS, picker/transport/ADB/
  phones false.
- **2026-08-19 (доп.255) — F1 functional Rust crypto seam source:** canonical manifest strict decoder
  rejects every truncation/trailing bytes; create API uses OS CSPRNG 16-byte transfer ID and binds
  sender/recipient/sanitized metadata/size/hash/TTL. Parse/encrypt/decrypt UniFFI APIs operate on
  bounded one-chunk ByteArrays; borrowed key and encryption plaintext copies are wiped in Rust.
  File key generation/persistence/export deliberately not exposed: caller key must remain transient
  until Android Keystore transfer vault + E2E key envelope exist. Native/bindgen/APK gate pending.
- **2026-08-19 (доп.256) — F1 functional crypto API build PASS after two safe GitHub stops:** state
  `D56BECDE…043CB`, native `95D96A41…B5D80`, normalized binding `FA074353…C4493`, app APK
  29,480,125 B `2EC36B50…3930B`; key vault/transport/ADB/phones false. ktlint/NativeCommandError are
  non-failures, Rust/Gradle exit 0. Next exact-hash binding-only acceptance; `.so` never commit.
- **2026-08-19 (доп.257) — functional file crypto binding acceptance PASS despite locked stale pack:**
  Git pull/repack could not unlink one old pack because another Windows process held it; answering no
  allowed safe continuation. Binding-only commit `2477417` pushed to fixed branch, binding
  `FA074353…C4493`, native `95D96A41…B5D80` remains uncommitted. Object data not lost; do not run
  cleanup/gc while lock owner unknown.
- **2026-08-19 (доп.258) — isolated functional Android file pipeline test source:** test-only
  noBackup directory + transient SecureRandom key exercises create/strict parse/encrypt/bounded atomic
  ciphertext store/read/decrypt/idempotent retry/tamper+truncation reject/scoped cleanup. Uses existing
  local node ID but no message/contact contents, transport, production DB/store or persistent key;
  key/plaintext arrays wiped in finally. Test APK/device gate pending.
- **2026-08-19 (доп.259) — functional file Android test APK build PASS:** output pasted twice but is
  one identical evidence state `3981EC29…58198`; app APK unchanged `2EC36B50…3930B`, test APK
  983,160 B `125AED71…57DF8`; persistent key/production store/transport/ADB/phones false. Next
  data-preserving Stas v11.16.35→v11.16.38 isolated runtime gate.
- **2026-08-19 (доп.260) — F1 functional pipeline Stas PASS:** state `B9717734…40999`;
  manifest/create/parse, encrypt→atomic store→read→decrypt, idempotent retry and tamper/truncation
  rejection PASS. Test store/package removed; profile/node/signing/pending/DB preserved. Stas
  data-preserving v11.16.35→v11.16.38; no persistent key, production store, transport, DB delete,
  data clear or force-stop.
- **2026-08-19 (доп.261) — F1 device-bound transfer key vault source:** exact 61-byte v1 envelope
  `[version,IV12,ciphertext32+GCMtag16]`, non-exportable Android Keystore AES-256-GCM alias,
  transfer-ID-bound AAD, AtomicFile+fsync under noBackup transfer directory. Existing unreadable/
  tampered key is never overwritten; import is create-once or constant-time same-key idempotent;
  mismatch rejected. Plain key only exists in bounded callback and is wiped. JVM framing tests and
  isolated alias/root instrumentation cover stability, zeroization, import conflict, tamper/no
  overwrite and cleanup. No production key is created; build gates pending.
- **2026-08-19 (доп.262) — F1 key vault JVM/app/test build PASS:** state `CF489FDC…C8D7`, app APK
  29,496,509 B `E4F885B1…4756`, test APK 987,405 B `3580B819…61B6`; production key/transport/ADB/
  phones false. Output pasted twice but evidence identical. Before phone gate test strengthened to assert
  isolated root and Android Keystore alias are both absent after finally cleanup; test-only rebuild
  required, original build remains valid compile evidence but not final cleanup acceptance.
- **2026-08-19 (доп.263) — F1 key-vault explicit-cleanup test build PASS:** state
  `81DF0CB9…32BB4`, app APK unchanged `E4F885B1…4756`, rebuilt test APK 987,530 B
  `722C2113…1F4D4`; production key/ADB/phones false. Next Stas v11.16.38→v11.16.39 isolated
  Android Keystore gate, with exact test alias/root cleanup and production root/state preservation.
- **2026-08-19 (доп.264) — F1 key vault Stas PASS despite misleading retry:** first visible command
  reported GitHub stop, next saw existing evidence because gate had already completed. Read-only local
  diagnostic confirmed state `37C9DFDB…D19A7`, log `7705DEDA…E01B4`, v11.16.38→v11.16.39;
  stable/zeroized/import-conflict/tamper PASS, test alias/root/package removed, production state
  preserved, no production key/store/transport, DB delete/data clear/force-stop.
- **2026-08-19 (доп.265) — outgoing local file preparation owner source:** source inspector first pass
  hashes/sanitizes; Rust creates canonical random-ID manifest; Room inserts PREPARING; manifest is
  atomic/idempotent app-private; device-bound key created; source reopened and read in exact ≤128KiB
  chunks, each Rust-encrypted, atomic-stored and chunk/progress persisted monotonically. Second-pass
  exact size+SHA protects mutable providers; failure removes scoped files+DB when cleanup succeeds,
  process death intentionally leaves resumable PREPARING state. Empty files supported. No publish,
  key export, URI/path persistence or UI enable yet. Chunk store gains bounded manifest.v1 tests.
- **2026-08-19 (доп.266) — outgoing preparation owner JVM/APK build PASS:** state
  `3201C860…A7F38`, app APK 29,496,509 B `4BD7B538…8B740`; chunk/source/key-envelope regressions PASS,
  production preparation/UI/transport/ADB/phones false.
- **2026-08-19 (доп.267) — isolated outgoing preparation integration source:** owner accepts injected
  test store/key access while Hilt production constructor remains fixed. Android test uses FileProvider
  content://, 200,000-byte deterministic source (2 chunks), in-memory Room, isolated noBackup root and
  Keystore alias; asserts PREPARED monotonic DB/chunk state, manifest parse, decrypt exact source,
  scoped DB/files/key/root/source cleanup. Production DB/store/key and transport untouched. Test APK/
  device gate pending.
- **2026-08-19 (доп.268) — outgoing preparation integration build PASS:** state
  `1F0740B4…5A437`, app APK 29,496,509 B `33CFC806…2B52`, test APK 993,315 B
  `E39EDF97…84032`; production preparation/key/store/transport/ADB/phones false. Next Stas
  v11.16.39→v11.16.41 isolated two-chunk preparation execution with exact cleanup/state preservation.
- **2026-08-19 (доп.269) — outgoing preparation Stas gate уже PASS; поздний повтор не нужен:**
  canonical evidence из предыдущего успешного запуска: state `651847EC…026B`; two chunks/Room
  PREPARED/exact decrypt PASS, test DB/files/key/source/package removed, profile/node/signing/pending/
  production DB preserved, no production preparation/key/store/transport, DB delete/data clear/
  force-stop. Stas v11.16.39→v11.16.41. Последняя повторная команда остановилась на GitHub до script
  и ничего не меняла. Gate закрыт и больше не повторяется.
- **2026-08-19 (доп.270) — controlled real two-phone file/photo network harness source:** receiver
  instrumentation owns its Rust MQTT engine, accepts only exact sender+random run ID, strict-parses
  manifests, atomic-stores/decrypts and verifies SHA; validates deterministic 4096-byte file and a
  generated 96×96 PNG. Sender creates real manifests/XChaCha ciphertext and publishes addressed
  offer/chunk packets. Gate supplies a random 32-byte test key out-of-band and never persists it:
  therefore this proves actual cross-phone bytes/routing, NOT production E2E key exchange/UI.
  App services must remain stopped during instrumentation so ciphertext is never shown as chat text.
  Test APK and Anna↔Stas online gate pending; Anna requires corrected Room v5→v6 migration first.
- **2026-08-19 (доп.271) — F2 cross-phone harness test APK build PASS:** state
  `183C801A…07D6D`, app APK unchanged `33CFC806…2B52`, test APK 1,000,060 B
  `152E66A2…E13B6`; production key exchange/UI/ADB/phones false. Phone gate: Anna sender
  v11.16.28 (first corrected Room v5→v6 migration), Stas receiver v11.16.41; random run/key never
  printed/persisted, receiver starts first, real addressed MQTT carries encrypted 4096-byte file and
  generated PNG, then exact state/test cleanup and both apps relaunch.
- **2026-08-19 (доп.272) — first F2 Anna→Stas network gate timeout:** state
  `6D8BDC49…7E2E`, Anna corrected DB migration PASS log `7CC3D781…E31D2`, sender PASS log
  `30C4A216…F1089`; receiver emitted only test-start then timed out, combined log absent. Anna app
  update/migration occurred; DB delete/data clear/force-stop false. Root cause hypothesis from code:
  Stas app was not replace-installed because already v11.16.41, so its foreground service could drain
  Rust events before instrumentation and persist up to four APUFILETEST1 packets as chat rows.
- **2026-08-19 (доп.273) — F2 recovery source:** receiver writes/deletes ready marker and waits 180s;
  sender repeats offer/chunk twice. Exact cleanup test selects only synthetic prefix+ID-pattern rows,
  requires expected Anna sender, deletes at most four, repairs affected chat lastMessage/time/unread
  from remaining messages, and asserts no synthetic content. Recovery will replace-install both apps
  to stop competing engines, skip already-PASS Anna migration, wait for ready+subscription, use new
  run/key/evidence and preserve production roots/identity. Test APK/recovery gate pending.
- **2026-08-19 (доп.274) — F2 cross-phone recovery test APK build PASS:** state
  `8F860AB1…DB137`, app APK unchanged `33CFC806…2B52`, test APK 1,003,889 B
  `D084BF28…48893`; ADB/phones false. Recovery requires both v11.16.41, replace-installs both to
  stop services, exact synthetic cleanup on Stas, ready marker+12s subscription wait, repeated packets,
  new ephemeral run/key/evidence, and final production state/test artifact validation.
- **2026-08-19 (доп.275) — F2 recovery-01 also timed out after ready:** state `EA0F77BB…F2114`,
  cleanup PASS `4CE66DE7…4E33`, sender PASS `EBAEA87A…D1435`, receiver only test-start
  `05C4950A…214A`; ready marker remained, root absent. Read-only phone diagnostic: both v11.16.41,
  Anna no process, Stas target process present, no relevant Rust MQTT/ConnAck/subscription lines.
  Thus no offer reached receiver; receiver then likely blocked in RustBridge.shutdown. No DB delete/
  data clear/force-stop. Recovery-01 must not repeat.
- **2026-08-19 (доп.276) — F2 recovery-02 source uses deterministic ADB TCP tunnel:** receiver cleanup
  now removes ready before teardown and does not synchronously stop a potentially stalled MQTT engine;
  sender accepts test-only tcp_port and sends normal four-field direct TCP frames. Phone gate will
  replace-install both apps, map Anna device localhost through PC to Stas engine port 7778, transmit
  real encrypted file+PNG bytes, then remove forward/reverse mappings. This proves cross-device direct
  byte transport but remains a controlled ADB tunnel, not production internet routing/key exchange/UI.
- **2026-08-19 (доп.277) — F2 direct-tunnel test APK build PASS:** state `990BEDB6…07FA`, app APK
  unchanged `33CFC806…2B52`, test APK 1,004,252 B `63C0C3E5…1BEAC`; production network/key exchange/
  ADB/phones false. Phone gate maps Anna device localhost:37778 via host:47778 to Stas:7778,
  replace-installs both to terminate stale engines, cleans exact old synthetic/test artifacts, then
  removes forward/reverse mappings in success and finally paths.
- **2026-08-19 (доп.278) — F2 engine-based ADB tunnel hung before sender:** user interrupted after
  ~10m. State `6EA472D0…8CD2`, cleanup PASS `4D91F0DF…9520F`, sender absent, receiver only test-start
  `05C4950A…214A`. Phone read-only diagnostic showed Anna no process, Stas target process present,
  ready marker PRESENT/root ABSENT. Thus hang occurred in receiver RustBridge.initialize/listener
  path before sender; no encrypted transfer, DB delete/data clear/force-stop false. Gate must not repeat.
- **2026-08-19 (доп.279) — F2 recovery-03 removes network engine from harness:** receiver binds a
  test-only Java ServerSocket on device localhost, then writes ready; sender uses length-prefixed Java
  Socket frames and exact 3-byte ACK through ADB reverse/forward. Rust remains responsible for real
  manifest/XChaCha crypto only. No MQTT, RustBridge engine start/stop or production TCP parser in this
  controlled transport proof. Test APK and new evidence/gate pending.
- **2026-08-19 (доп.280) — F2 raw-socket tunnel test APK build PASS:** state
  `17A17636…DAFB5`, app APK unchanged `33CFC806…2B52`, test APK 1,005,936 B
  `026F7B2A…AB275`; Rust network/production network/ADB/phones false. Gate uses fresh ports
  Anna:37779→host:47779→Stas:39001, length-prefix ≤256KiB, exact ACK, and always removes mappings.
- **2026-08-20 (доп.281) — bounded GitHub retry runner:** пользователь подтвердил, что browser GitHub
  работает, но git HTTPS эпизодически получает connect timeout. Добавлен generic verified runner:
  максимум 5 pull attempts с delays 5/15/30/60s, retry только для сетевых/TLS ошибок, немедленный
  stop для auth/non-FF/worktree ошибок, `gc.auto=0` против старых pack unlink prompts, затем exact
  HEAD+blob verification до gate. Бесконечных retries нет; phone gate не стартует без pull PASS.
- **2026-08-20 (доп.282) — raw-socket tunnel also failed; tunnel experiments stopped:** state
  `B8F94FE0…7CAED`, cleanup PASS `E91A18C6…C373F`; sender connected but timed out reading ACK,
  receiver only test-start/root result absent. No production transfer and no DB delete/data clear/
  force-stop. User explicitly directed to finish actual arbitrary file/photo/video code instead of
  more harness work. No further MQTT/ADB-tunnel recovery attempts.
- **2026-08-20 (доп.283) — production F2 packet layer source:** versioned binary codec fragments
  encrypted offer/chunk payloads into ≤24KiB frames (max 16, reassembled max 256KiB+AEAD tag), binds
  type/transfer ID/item/fragment geometry/length and domain-separated SHA-256 truncation digest,
  strict-decodes and rejects corruption/truncation/trailing/missing/duplicate/mixed fragments. This is
  transport integrity/framing, not key authentication. JVM boundary/negative tests added; build gate
  pending. Next authenticated transfer-key envelope then production transport/UI.
- **2026-08-20 (доп.284) — production packet codec JVM/APK PASS:** state `B40A10D7…65FDB`, app APK
  29,512,893 B `4C15B772…330EA`; key exchange/transport/UI/ADB/phones false.
- **2026-08-20 (доп.285) — user explicitly changed referral rewards to outbound-media entitlements:**
  central `FileTransferRankPolicy` maps qualified direct thresholds 1/3/10/.../1000 to photo/file/
  video unlocks and per-file 5MiB→4GiB rank ceilings. Effective limit is always min(rank, technical);
  current F1 stays 10MiB. Unknown MIME is generic file. Receiving/download, text, crypto, priority,
  privacy and already accepted transfers are never rank-gated. Outgoing preparation now requires an
  explicit qualified count. Boundary/category/technical-cap tests added. This supersedes earlier
  cosmetics-only/no-feature-blocking text only for outbound media, and carries spam/growth UX risk.
- **2026-08-20 (доп.286) — outbound media rank policy JVM/APK PASS:** state `72718C87…04047`, app APK
  29,512,893 B `9297117F…1D114`; referral runtime/UI/transport/ADB/phones false.
- **2026-08-20 (доп.287) — community creation rank rules added by user:** fresh install/no rank keeps
  text, contacts/basic settings, receiving/downloading media and joining existing groups/channels.
  Group creation requires 10 qualified direct referrals (`Проводник`); channel creation requires 30
  (`Навигатор`). Central entitlement exposes create-group/channel and user-facing unlock summary;
  tests cover 9/10 and 29/30 boundaries plus base access. Future enforcement must exist in UI and
  domain/repository/API, not only disabled buttons. Outbound media table remains 1 photo, 3 files,
  10 video with growing per-file rank limits.
- **2026-08-20 (доп.288) — automatic proxy entitlement + test-phone max rank:** technical choice
  threshold 20 (`Организатор`). Application schedules/cancels periodic collector by rank; worker
  rechecks every run; TelegramRelay refuses active proxy and clears Authenticator below rank; UI bulk
  collect/autopick also gates. Manual proxy diagnostics remain available. Added debug-only separate
  rank override store, ignored in release, and instrumentation setter; count 1000 unlocks all current
  media/group/proxy/channel policy without creating receipts. User authorized setting max test rank on
  all PC-connected test phones; unknown serials must be identified before mutation.
- **2026-08-20 (доп.289) — proxy rank + debug override build PASS:** state `7A13F9E3…C7A5B`, app APK
  29,512,893 B `6368E84B…10DD`, test APK 1,005,936 B `883BD01F…B522`; threshold/max 20/1000,
  production count/ADB/phones false. Output pasted repeatedly but evidence identical.
- **2026-08-20 (доп.290) — max-rank connected test-phone gate:** exact allowlist Anna/Zhenya/Stas;
  any unknown serial aborts before mutation. Gate replace-updates debug app, conditionally Room-migrates
  old v11.16.23/28 DB via corrected test, sets separate debug override=1000, removes test package,
  preserves profile/node/pending/real referral prefs and launches stable app. Existing signing state is
  preserved by app logic; old phones may create their normal sidecar on launch. No fake receipts,
  production count, app uninstall, DB delete, data clear or force-stop.
- **2026-08-20 (доп.291) — first max-rank phone gate parser-safe stop:** verified runner pulled script,
  then Windows PowerShell parser rejected interpolated `$Role:` before executing any script statement.
  No ADB/app/data/evidence changes. Original script must not repeat; recovery-01 uses `${Role}:`, new
  TEMP state/log names and same exact offline build artifacts.
- **2026-08-20 (доп.292) — connected test phones max-rank gate PASS:** state `3BDF368C…73EE`;
  connected known test phones set debug override 1000/ALL, production receipts/count unchanged, no
  app uninstall/DB delete/data clear/force-stop. Output pasted twice but evidence identical.
- **2026-08-20 (доп.293) — rank information UI source:** Settings gets `Ранги и возможности` route.
  Screen reads central qualified count (including debug-only lab override), highlights current rank and
  lists every threshold, unlocked media/group/proxy/channel capability and per-file rank limit, with
  explicit basic text/receive/join access and technical-limit disclaimer. Build gate pending.
- **2026-08-20 (доп.294) — rank benefits UI JVM/APK PASS:** state `FE36024E…7B668`, app APK
  29,529,277 B `BE7357E1…E95BF9`; file transport/ADB/phones false. Settings route now compiles and uses
  the same central entitlement table as media/group/proxy/channel gates.
- **2026-08-20 (доп.295) — max debug rank connected phones PASS:** state `3BDF368C…73EE`;
  connected known test phones received debug override 1000/ALL, production referral receipts/count
  unchanged, no app uninstall/DB delete/data clear/force-stop. This authorizes future file/group/proxy/
  channel acceptance on the lab phones without fabricating qualified receipts.
- **2026-08-20 (доп.296) — owner approved production three-phone file plan and waits for ready APK:**
  Anna/Zhenya/Stas are available. Stop installing intermediate file APKs. Next phone installation only
  after authenticated recipient key exchange, production offer/chunk/ACK owner, durable encrypted
  third-phone chunk custody with multi-day TTL/restart resume, cleanup/exactly-once, and chat picker/
  progress are all wired and offline-built. Acceptance includes sender/recipient/relay independently
  offline for days and all-three-offline retention (no delivery claim while every node is offline).
- **2026-08-20 (доп.297) — production authenticated file exchange source started:** added strict
  signed `apu-file-exchange-binding-v1`: nested verified identity binding + separate static X25519
  public key + timestamp + Ed25519 signature. Creation requires installed sidecar and exact local
  identity match; foreign binding rejected. Wire parser rejects all truncation/trailing/tamper.
  UniFFI create/verify/extract node/public APIs added; supplied X25519 secret copy is wiped in Rust.
  This is public-key binding only; Android device-bound static secret storage, contact pinning and
  per-transfer key envelope are next and no APK is sent to phones until full path is wired.
- **2026-08-20 (доп.298) — owner authorizes final file-transfer install/test/release sequence:** finish
  authenticated exchange, production transport, third-phone durable multi-day custody, UI/resume;
  then install one candidate on Anna/Zhenya/Stas, close acceptance and publish a short redacted release.
  This is explicit release authorization for that completed candidate, not permission to publish an
  intermediate build. Exact public notes/checksum still undergo final redaction review.
- **2026-08-20 (доп.299) — next major task fixed after file release:** full Groups + Topics + Admin
  Cabinet, not a demo: rank-10 create gate, join without rank, owner/admin/mod/member roles, requests,
  permissions/ban/mute/invites/audit, topic lifecycle/unread/mute, epoch key rotation and three-phone
  durable offline/admin acceptance. Added to nearest roadmap after file-transfer release.
- **2026-08-20 (доп.300) — signed X25519 file-exchange binding build PASS:** state
  `88673823…F7A6E`, native `81384E50…AD790`, generated binding `8C0BB329…D9304`, APK
  29,545,661 B `5B570E4A…39A42`; secret store/transport/ADB/phones false. Next binding-only acceptance;
  `.so` remains uncommitted.
- **2026-08-20 (доп.301) — file-exchange generated binding accepted:** Windows binding-only commit
  `2c252ce`, binding `8C0BB329…D9304`, native remains local `81384E50…AD790`.
- **2026-08-20 (доп.302) — device-bound static X25519 exchange store source:** dedicated Android
  Keystore AES-256-GCM alias wraps one random 32-byte secret in exact v1 envelope/AAD; signed public
  exchange binding persists separately. Startup recreates a candidate from the unwrapped secret and
  requires existing signed binding node+X25519 public equality, never overwriting malformed/mismatched
  state. Secret is callback-bounded and wiped. CoreServerService initializes after Ed sidecar and logs
  hashes/status only. Compile/instrumentation pending; no phone install before full candidate.
- **2026-08-20 (доп.303) — contact file-exchange TOFU pinning source:** additive Room v6→v7 table
  stores only public signed binding/base64+SHA/X25519 public/trust timestamps. `FileExchangePeerStore`
  verifies nested signature/node before first insert, treats exact repeat idempotently and rejects any
  different key for same legacy node (including insert races) until future explicit QR/recovery flow.
  Binding reads reverify signature/node/hash. App migration chain and current tests updated to v7;
  historical one-time Stas v6 identity recovery is @Ignore to prevent rerun. Compile/migration gates
  pending; no phone install before full candidate.
- **2026-08-20 (доп.304) — authenticated per-transfer file-key envelope source:** X25519 static-static
  DH between signed/pinned exchange bindings, HKDF-SHA256 bound to manifest hash and ordered sender/
  recipient legacy IDs, random XChaCha nonce and 32-byte file-key AEAD. Wire carries sender signed
  exchange binding, recipient-binding hash, manifest hash, nonce/ciphertext and sender Ed25519
  signature. Recipient requires its exact installed identity+exchange secret; wrong recipient,
  manifest, secret, signature or tamper fail. UniFFI create/open wipe supplied secret/file-key copies.
  Compile/runtime gates pending; no phone installation.
- **2026-08-20 (доп.305) — authenticated file-key envelope build PASS:** state `31ED6BB0…EAAC9`,
  native `1096A43A…B259`, generated binding `734B1F69…D56DC`, APK 29,578,429 B
  `6F23AF5F…EB0CE`; contact pinning source ready, transport/ADB/phones false. Binding-only acceptance
  next; native remains uncommitted.
- **2026-08-20 (доп.306) — outgoing preparation now owns authenticated key envelope:** production
  constructor requires `FileExchangePeerStore`; preparation refuses recipient without pinned binding,
  reads local signed exchange binding, borrows device X25519 secret + per-transfer key only in nested
  wiping callbacks, creates Rust authenticated envelope, and atomically stores bounded key-envelope.v1
  beside manifest/chunks before PREPARED. Isolated historical preparation test keeps exchange disabled;
  production path cannot. Chunk store adds exact idempotent/conflict tests. Build gate pending.
- **2026-08-20 (доп.307) — F3 production file transport source complete (sender owner + receiver
  ingest + chat UI, build gate pending):** file packets now ride the EXISTING durable text
  transport (direct QUIC or encrypted M8 relay custody) as `apu-file1|<base64 packet>` texts,
  one encoded fragment per message, deterministic delimiter-safe message IDs
  (`f<tid>o<frag>` / `f<tid>c<chunk>f<frag>` / `f<tid>a<count>`) so mesh dedup makes every
  re-pump idempotent. New bounded strict components: `FileOfferPdu` (manifest+key envelope+signed
  sender binding, strict decode), `FileTransferWire` (48KiB wire budget, strict base64),
  `FileTransferSender` (windowed pump: offer first, then ≤120 in-flight chunk-fragment messages
  beyond receiver-confirmed contiguous prefix; 120ms pacing; honest states PREPARED→TRANSFERRING→
  SENT→COMPLETE; restart resume via Room+chunk files; re-pump throttle 2min without ack progress),
  `FileTransferReceiver` (bounded fragment reassembly 64 items/4MiB, fail-closed offer checks:
  sender match, MY recipient match, expiry, binding signature, TOFU pin change reject; chunk
  geometry check; durable encrypted chunk store; deterministic per-chunk file-ACKs back to sender;
  stream decrypt+whole-file SHA-256 before plaintext becomes visible; VERIFY_FAILED deletes
  received plaintext), `ReceivedFileStore` (app-private atomic verified output), `FileTransferRouter`
  (Hilt facade; routes packets BEFORE chat-text save; periodic 20s pump loop + pumps on
  peer_discovered/network connected; skips when engine not running). Multi-day all-phones-offline
  custody comes from the M8 durable relay machinery itself (7-day TTL, sender-local durable
  chunks+Room for restart resume); sender re-push after restart is dedup-idempotent. Chat UI:
  attach button (SAF OpenDocument) → rank-checked preparation → local placeholder message
  (`insertLocalFileMessage`, never rides text transport) → immediate pump; `FileTransferBubble`
  shows honest state/progress; incoming in-progress transfers merged into the chat list.
  CoreServerService ACKs each file-packet message-id so relay cleanup stays exactly-once.
  JVM tests added (PDU/wire/sender window+throttle+empty-file/receiver full flow+negative);
  `kotlinx-coroutines-test` added to test deps. KNOWN GAPS (honest, next slices): (1) OFFER
  metadata (filename/size/media type) is integrity-protected but NOT confidential to relay
  nodes — chunk content and file key are; a Rust offer-AEAD slice must fix this; (2) relay
  per-recipient queue (200) shared with text — window 120 leaves ~80 for text, bursts can
  enqueue-reject (harmless, retried); (3) receiver pull-based missing-chunk request not yet
  (sender cyclic re-push covers loss); (4) received-file SAF export/open button not yet
  (files live in app-private `file_received/v1`); (5) sender does not delete local chunks on
  COMPLETE (waits TTL cleanup slice). No Windows compile gate, no phones, no release yet.
- **2026-08-20 (доп.308) — NEW PC environment + F3 Windows compile gate PASS (после 3 итераций):**
  разработка переехала на новый ПК: канонический клон теперь **`C:\APU-M8`** (старый
  `C:\APUMIR-arena-test` не существует; clone в `C:\Users\User` — старый dirty, не трогать).
  Гэтчи новой машины, все закрыты: (1) системный `JAVA_HOME` указывал на несуществующий
  `D:\Android Studio\jbr` — навсегда исправлен на `C:\Program Files\Android\Android Studio\jbr`
  (User-level SetEnvironmentVariable); SDK — `%LOCALAPPDATA%\Android\Sdk`, local.properties с
  forward-slash `sdk.dir`. (2) Клон был single-branch — `git config remote.origin.fetch
  "+refs/heads/*:refs/remotes/origin/*"` + повторный fetch; локальный оверлей `.so` убран в
  stash до checkout. (3) GitHub 443 периодически отказывает — лечится повторами pull/fetch
  (известно и раньше). Gate-история F3 на 45d27db (ветка `arena/01a02092-apumir`): первый
  прогон — 3 ошибки компиляции K2 (неоднозначный sumOf с неразрешимым селектором, smart cast на
  var в замыкании, отсутствие default у errorCode) — фикс `2371bcc`; второй — 13 падений JVM
  от `android.util.Log` ("Method not mocked") + виртуальное время теста троттлинга уходило за
  expiry фикстуры — фикс `08d6389` (`unitTests.isReturnDefaultValues = true` + expiry 1ч);
  третий — 5 падений из-за тестового фикстура (байты transferId ≠ его hex-форма, чанки
  навсегда оседали в pre-offer буфере; один тест проходил «по ошибке») — фикс `45d27db`.
  Итог: **`testDebugUnitTest` 88/88 PASS, `assembleDebug` BUILD SUCCESSFUL** (main-код для APK
  не менялся после первого зелёного assembleDebug — дальше правились только тесты и
  testOptions). Rust/.so не пересобирались и не needed: свежий arm64 `.so` (6,935,552 B)
  закоммичен в `3eaae63` вместе с актуальными bindings; lib.udl не менялся. Телефоны не
  трогали; релиз не делали.
- **2026-08-20 (доп.309) — File-HELLO handshake закрывает deadlock «первого файла»:** обнаружено
  при подготовке телефонного acceptance: sender требует закреплённый X25519-binding получателя
  (доп.306), а пиннинг существовал ТОЛЬКО из входящего file-offer (доп.303) → два телефона,
  никогда не обменивавшиеся файлами, не могли отправить первый файл друг другу. Новый срез:
  крошечное durable-сообщение `apu-file-hello1|<base64 подписанного binding>` (≤512B,
  детерминированный per-pair-per-direction msg id `fh<sha256-32>`, throttle 60 c на контакта).
  Приём: строгая проверка подписи и совпадения node с отправителем → TOFU-пин (смена ключа
  по-прежнему отвергается) → при ПЕРВОМ пине авто-ответ своим HELLO (обе стороны оказываются
  закреплены). Рассылка: pump-цикл (20 c) автоматически шлёт HELLO всем unpinned pk_-контактам
  (durable → доехав при появлении получателя); UI при ошибке «binding is not pinned» сам
  отправляет HELLO и показывает человекочитаемое сообщение.HELLO никогда не попадает в чат
  (перехватывается до сохранения), relay-cleanup через штатный delivery-ACK. JVM-тесты:
  wire round-trip/oversize, детерминизм ID, PINNED_NEW→PINNED_ALREADY, mismatch sender reject,
  changed-key reject, not-hello, маршрутизация до chat-text. Build gate нового среза —
  следующий прогон на Windows вместе с release-кандидатом для телефонов.
- **2026-08-21 (доп.310) — первый телефонный file-acceptance: сеть диктует правила; найдено 3 бага, 2 закрыты сразу:**
  v11.17.0 (debug-signed, тот же debug-ключ что стоял на телефонах — release-jks несовместим с установленным
  v11.16.45!) установлена на Анну/Жену/Стаса поверх, данные сохранены. Предыстория-гэтчи: подпись приложения на
  телефонах = `CN=Android Debug` (`C:\Users\User\.android\debug.keystore`, SHA256 40448024…a186), а НЕ p2p-release.jks
  (f843…a5f7) → ставим `assembleDebug`; закоммиченный `jniLibs/arm64 .so` СТАРЕ bindings → стартовый
  UnsatisfiedLinkError на `uniffi_p2p_core_checksum_func_clear_identity_signing_seed` → вылечено пересборкой
  `build-rust.ps1 -Features mqtt-dual-broker -Arch arm64-v8a` на новой машине (кэш target/ оказался актуальным;
  строковый поиск символов в .so через ASCII.GetString — НЕ НАДЁЖЕН, рабочая проверка = запуск приложения).
  Итоги сети дня: провайдер сильно режет канал к broker.hivemq.com:1883 / broker.emqx.io:1883 (с ПК Test-NetConnection
  идёт с 1-3 попыток; test.mosquitto.org мёртв; в приложении — бесконечные "Connection closed by peer abruptly"/
  "Network timeout", публикации редко проходят). Мелкие сообщения (текст/заглушки/офферы/ACK) протискиваются,
  крупные (~33КБ base64 чанк-фрагменты) — НЕТ: у Жени дошли оба оффера (manifest+key-envelope на диске) и ноль
  чанков; у Анны все пакеты durably retained и re-pump каждые 2 мин — офлайн-очередь работает как задумано.
  LAN-direct не спасал из-за бага (1): **mDNS publish одноразовый при старте** — Стас (не менял сеть) виден всем
  с pk, Анна/Женя после смен сетей «исчезли» (соседи видели только чужие записи без node_id). Баг (2): локальная
  заглушка файла имела статус QUEUED_OFFLINE → FULL SYNC повторной отправкой УШЛА получателю как обычный текст
  («пришло фото» = текст-заглушка, не файл). Баг (3, открыт): чанк-фрагмент 24KiB велик для изрезанных каналов
  (нужен меньший фрагмент на broker-пути и/или LAN/прокси приоритет). Фиксы этого среза: mDNS re-publish каждые
  60 c (unpublish+publish_self в discover-цикле); статус заглушки → `LOCAL_FILE` (вне выборок retry); добавлены
  Log.i на успешных путях приёмника (offer accepted / chunk stored) — раньше их отсутствие стоило часов
  диагностики. РЕШЕНИЕ пользователя по следующему приоритету: **прокси-канал (MTProto) для транспорта** —
  «наши прокси должны использоваться, чтобы не резало и не дёргался канал» (Приоритет 7); плюс в очереди:
  mDNS-анонсы уже чинятся здесь, LAN-direct приоритет над брокерами, кнопка открыть/сохранить принятый файл,
  меньшие фрагменты для broker-пути. Windows gate нового среза: build-rust + assembleDebug + 95 JVM-тестов PASS
  ожидается следующим прогоном; телефоны ждут переустановки.
- **2026-08-21 (доп.311) — acceptance-прогресс + фикс размера фрагментов:** новая сборка (f9b4c57,
  lastUpdateTime 11:21) на всех трёх телефонах; оффер от Анны **дошёл и принят** Жениной стороной
  (`File offer accepted: … IMG…jpg, 56726 B, 1 chunks` — новый receiver-лог работает). Подтверждён
  размерный порог изрезанного канала: все мелкие конверты (текст/HELLO/оффер/ACK) проходят, крупные
  (~33КБ base64 чанк-фрагмент) — нет. Срез: `MAX_FRAGMENT_PAYLOAD_BYTES` 24KiB → **9KiB**,
  `MAX_FRAGMENTS` 16 → **32** (32×9KiB ≥ 256KiB+tag reassembly cap; Kotlin-only, без Rust/binding
  изменений; окно отправителя само пересчитывается ≤120 сообщений). Ожидание: ~12КБ base64
  сообщения проходят как обычные тексты. Открыто: QUIC LAN-direct всё ещё не наблюдался в паре
  Анна↔Женя (проверить connect timeout vs mDNS re-publish эффект); прокси-канал (MTProto) —
  следующий большой срез по решению пользователя.
- **2026-08-21 (доп.312) — ПЕРВЫЙ ПОЛНЫЙ ФАЙЛОВЫЙ PASS (скрытый багом отображения) + фикс чат-роутинга:**
  тест А (фото 3КБ, Анна→Женя): у Анны «Доставлено ✓» — т.е. Женин телефон реально принял файл,
  проверил SHA-256, сохранил и отправил финальный file-ACK; тестовая теория размера подтверждена
  (мелкие пакеты проходят изрезанный канал). Но Женя «ничего не видел»: входящий transfer и
  сообщение о завершении записывались в chat_id из конверта отправителя — это ЛОКАЛЬНЫЙ UUID чата
  Анны, которого на Жене нет → бабл в несуществующем чате. Фикс: router.routeIncoming подставляет
  локальный чат по senderId (getChatByContactId), fallback — исходный chatId. Пайплайн файлов
  теперь сквозной: мелкий файл доставлен end-to-end по изрезанному broker-каналу с честными
  статусами. Осталось: пере-тест 55КБ на 9KiB-фрагментах, LAN-direct QUIC (не наблюдался у пары
  Анна↔Женя), кнопка открыть/сохранить, прокси-канал MTProto.
- **2026-08-21 (доп.313) — итог марафона: файловый конвейер доказан, канал — главный тормоз:**
  ЗАКРЫТО за сегодня: (1) v11.17.0 debug на всех трёх телефонах (та же debug-подпись, данные целы;
  fresh `.so` собран на новой машине build-rust.ps1); (2) File-HELLO handshake — ключи Анна↔Женя
  закреплены; (3) **первый полный end-to-end PASS файла**: фото 3КБ Анна→Женя — доставлено,
  расшифровано, SHA-256 проверен, финальный ACK вернулся, у Анны «Доставлено ✓»; (4) фикс чат-
  роутинга: входящие файлы больше не прячутся в чужом chat_id (Анна увидела «0/3» с прогресс-баром
  — UI приёма работает); (5) 9KiB-фрагменты; (6) mDNS re-publish 60с; (7) заглушки не улетают
  текстом; (8) JVM-гейт 88/88 на новой машине `C:\APU-M8` (JAVA_HOME=Android Studio jbr permanently;
  clone из single-branch переведён на all-refs). БЛОКЕРЫ/открытое: канал до публичных брокеров
  нестабилен часами (тексты по 15+ минут не уходят; ПК-проверка: hivemq/emqx коннектятся с 1-3
  попытки, test.mosquitto мёртв) → фото >3КБ стоят в durable-очередях (0/3 часами) — ничего не
  теряется, но ждёт окна; LAN-direct QUIC не сработал у пары Анна↔Женя (подозрение на client
  isolation роутера: mDNS multicast проходит, unicast таймаутится); прокси-срез MTProto (решение
  пользователя) + спокойные переподключения + запасной брокер — СЛЕДУЮЩИЙ БОЛЬШОЙ СРЕЗ; также
  в очереди: кнопка открыть/сохранить файл, офлайн-сценарий «все выключены на часы», чистка
  осиротевших transfer-строк от старых попыток. Все push-гэтчи дня (debug-подпись, устаревший .so
  в git, stash, single-branch, PSU-моргание GitHub — лечится повторами pull) записаны в доп.308-312.
- **2026-08-21 (доп.314) — ускорение повторов + кнопка «Сохранить в папку»:** 26КБ-фото доставлено
  (9KiB-фрагменты работают на реальных размерах; медленно из-за канала). REPUMP_INTERVAL_MS 2мин →
  **30с** (re-pump дешёв: детерминированные ID + локальный dedup, в сеть без изменений не уходит) —
  очереди прокачиваются в 4 раза чаще при «окнах» канала. Новый UI-путь экспорта: у завершённого
  входящего файла кнопка «Сохранить в папку» → SAF CreateDocument (octet-stream, имя файла из
  манифеста) → потоковое копирование из app-private verified storage в выбранное место
  (router.exportReceivedFile). ViewModel: pendingSave → лаунчер → копирование с честным
  результатом в snackbar. Следующий большой срез остаётся «прокси + любая сеть» (решение
  пользователя, доп.310-313).
- **2026-08-21 (доп.315) — ФАЙЛОВЫЙ ЭТАП ЗАКРЫТ: полный цикл Женя→Стас PASS; подготовка релиза:**
  фото 26КБ: доставлено, у Стаса кнопка «Сохранить в папку» → сохранено → **открылось в галерее**;
  у Жени «Доставлено ✓». Итого за день подтверждены: 3КБ (Анна→Женя) и 26КБ (Женя→Стас) end-to-end,
  прогресс-бары, честные статусы, durable-очереди (ничего не потеряно за день сетевого хаоса),
  экспорт в галерею. Висящие Анна↔Женя = старые очереди в ожидании окна канала (не цикл). ВАЖНО
  для релиза: release-workflow НЕ собирал Rust → взял бы устаревший committed `.so` → публичный APK
  падал бы при старте; workflow теперь сам ставит NDK 28.2.13676358 + rust-toolchain + cargo-ndk и
  собирает все три ABI из исходников (`--features mqtt-dual-broker`), release помечен prerelease.
  Локальный `.so` на телефонах (00:15) БЕЗ mDNS-фикса — при следующем прогоне build-rust на ПК
  перекомпилируется (ядро менялось). Пользователь дал явное разрешение на публикацию релиза;
  публичный текст показан владельцу на утверждение (политика RELEASE_PUBLICATION_POLICY): версия
  **v11.17.1**, prerelease, APK+checksum из CI, текст без внутренних деталей. После релиза:
  прокси-срез MTProto («любая сеть»), офлайн-сценарий «все выключены на часы», cancel-кнопка
 transfer, чистка осиротевших transfer-строк.
- **2026-08-21 (доп.316) — релиз v11.17.1 в процессе: тег поставлен, первый CI-прогон упал на native-шаге:**
  пользователь утвердил СТАБИЛЬНЫЙ релиз (не prerelease; в приложении UpdateChecker смотрит
  `releases/latest`, поэтому финальный релиз обязан быть latest non-prerelease — переключим после
  сборки через gh release edit); из публичного текста убраны «известное ограничение» и упоминания
  внешних ресурсов. Патч workflow-v1 применён владельцем (c1dfd82, у arena-токета нет workflows-
  permission — правки workflow только через ПК владельца). Тег v11.17.1 поставлен; CI-прогон
  32471818680 упал в шаге Build native core (~1мин; логи не отдаются — results-receiver EOF,
  известная сетка). Диагноз по конструкции: `sdkmanager --install "ndk;…" || true` маскировал сбой
  + `${{ env.ANDROID_HOME }}` в step-env + перенос строки из v1-скрипта. Патч-v2
  (`scripts/patch-release-workflow-v2.ps1`): GITHUB_ENV для ANDROID_NDK_HOME, test -x clang
  (громкий сбой в правильном месте), однострочный cargo ndk. План: владелец применяет v2+push,
  тег v11.17.1 передвигается на новый коммит (delete+re-tag), CI пересобирает, затем
  gh release edit --latest --prerelease=false + финальный редактированный текст + checksum asset.
- **2026-08-21 (доп.317) — РЕЛИЗ v11.17.1 ОПУБЛИКОВАН (stable, latest):** после патча workflow-v2
  владельцем (6deb532; пуш потребовал явного refspec — «Everything up-to-date» лгал из-за устаревшего
  remote-tracking) тег v11.17.1 передвинут на 6deb532; CI-прогон 32473695695 собрал Rust из исходников
  (3 ABI, cargo-ndk, NDK 28.2) + assembleRelease за 11m13s PASS. Release создан автоматически, затем
  gh release edit: title «APU v11.17.1», утверждённый владельцем публичный текст (без «известных
  ограничений» и внешних ресурсов по его требованию), **prerelease=false, latest** — API releases/latest
  отдаёт v11.17.1, UpdateChecker предложит обновление всем. APK asset 35,285,255 B; checksum asset
  отложен (release-assets CDN недоступен из песочницы — EOF; владелец снимет SHA-256 локально).
  Продуктовые решения владельца записаны: рукопожатие/доставка через любых третьих онлайн-телефонов
  (уже так); ГИГАБАЙТНЫЕ файлы — ТОЛЬКО напрямую телефон↔телефон и быстро (следующий большой срез
  вместе с MTProto-прокси «любая сеть»).
- **2026-08-21 (доп.318) — Порядок: ПК-диск и репозиторий вычищены:** пользователь удалил (по явному
  списку, после инвентаризации) 9 старых копий проекта (~2.9 ГБ: APUMIR-arena-test-old, -m8c-gate-old,
  -v11.16.4-final, -junk-clone, APUMIR, APU-RELEASE-v11.16.23, APU-M8-PC-RECOVERY, APU-TOOLS,
  APUMIR-transfer), остатки старого клона в профиле C:\Users\User (главная ловушка для новых
  ИИ-сессий), ~190 apu-* файлов в %TEMP% (сохранён только свежий apu-v11.17.1.apk), битый ярлык
  «АРХИВ на диске D»; apu-icon-original.zip перенесён в Документы. На диске осталась ЕДИНСТВЕННАЯ
  рабочая копия **C:\APU-M8** (2.1 ГБ, кэш Rust-target сохранён для быстрых сборок). В репозитории:
  из корня удалены одноразовые *.py/temp_core.txt/logs/.kotlin (git-история хранит); 137 одноразовых
  гейт-скриптов перенесены в scripts/archive/, в scripts/ остались только универсальные
  (check_structure, invoke_verified_gate_with_github_retry, patch-release-workflow-v2,
  set_max_test_rank*). Обновление с публичного релиза на тестовые телефоны НЕ ставится поверх
  (debug-подпись vs release-подпись — INSTALL_FAILED_UPDATE_INCOMPATIBLE, ожидаемо); тестовые
  телефоны обновляются только adb-debug-сборками; миграция тестовых телефонов на release-канал —
  отдельная задача бэклога.
