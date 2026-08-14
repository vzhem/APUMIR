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

После каждой существенно важной **проверенной** сборки (новый milestone, исправленный blocker,
release candidate/стабильный release или точка перед рискованной миграцией) ИИ обязан сам
предложить владельцу сохранить APU на внешний диск/флешку. Не копировать без согласия: сначала
спросить букву диска и проверить место. Полная копия включает Git bundle+history, точный source
commit, проверенный APK и `libp2p_core.so`, SHA-256, environment/milestone manifests, docs и
инструкцию восстановления/сборки/запуска на чистом Windows-ПК. Проверить bundle и все hashes.
Учитывать signing material: рекомендовать зашифрованный носитель, не копировать tokens/`.env`/
переписку/private identity keys. Каноническая процедура:
`docs/BACKUP_AND_CLEAN_PC_RECOVERY.md`. Обычную неудачную промежуточную сборку milestone не считать.

### 0.2. Обязательный журнал ошибок и обходов

Каждую обнаруженную ошибку, ловушку инструмента и безопасный workaround ИИ обязан сразу
записывать в `AI_COLLABORATION_NOTES.md` и в тематическую инструкцию/план. Не оставлять решение
только в чате. Фиксировать: симптом, причину, точный безопасный обход, как проверить результат
и что нельзя делать. Если обход снижает безопасность или является временным — явно отметить
риск и будущий правильный fix. Перед похожим шагом новая ИИ-сессия сначала ищет существующий
гэтч и не заставляет пользователя повторно проходить уже известную ошибку.

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
- ⚠️ **Код из Arena-sandbox не виден на моей машине, пока его не запушить.** Чтобы я мог
  собрать/протестировать, ИИ должен закоммитить + запушить в `arena/<id>-apumir`, а я
  подтягиваю ветку в `C:\APUMIR-arena-test`:
  ```powershell
  Set-Location C:\APUMIR-arena-test
  git fetch origin
  git checkout arena/019ffc32-apumir
  ```
- Если путь к клону вдруг неизвестен — найди `gradlew.bat`:
  ```powershell
  Get-ChildItem -Path C:\ -Filter gradlew.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 5 FullName
  ```
- ⚠️ Чат/терминал иногда превращает точки в пакете (`com.vladimir.messenger.data`) в
  гиперссылку. В командах вида `gradlew --tests "..."` следи, чтобы FQN не исказился —
  лучше скажи мне вставить строку вручную.

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

Ветка: `arena/019ffc32-apumir` (все коммиты на remote). Канонический клон для сборки/теста:
`C:\APUMIR-arena-test` (arm64-v8a). Сборки: `build-rust.ps1` (Rust) + gradlew assembleRelease
(см. раздел 8.1). Хостовый `cargo test` НЕ работает (ring/aws-lc MSVC) — см. раздел 8.

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

**M3(c.2-r3) завершён. Следующий предлагаемый шаг — milestone-backup `v11.16.10` на внешний
носитель; M3(d) не начинать без отдельного согласования.**
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
  errors=0. PID `8350` был transient/already exited и не содержал attack snapshot. Functional
  attack PASS можно финализировать membership-aware по сохранённым snapshots; attack не повторять.

---

## 8. Сборка и тест APK на Windows (гэтчи)

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
- **PowerShell parser: function calls вокруг `+` надо заключать в скобки:** внутри hashtable
  `Count-Literal ... +` на переносе дал parser error, после чего остаток paste снова выполнялся
  отдельными командами и породил ложный INCOMPLETE/`else` errors. Правильно:
  `(Count-Literal ...) + (Count-Literal ...)`. Для длинной read-only диагностики безопаснее
  записать Python/`.ps1` как here-string, сначала compile/parse, затем выполнить; PASS/state/clear
  остаются только внутри валидного файла.
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
- **`$ErrorActionPreference = "Stop"` превращает обычный stderr native-команды в
  `NativeCommandError`** (проверено на `java -version`, который штатно пишет версию в stderr).
  Для environment capture запускать через `cmd.exe /d /c "<command> 2>&1"`, записывать exit
  code и output; не отключать проверки всего backup-скрипта ради одной команды.
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
1. Прочитать **весь** `docs/AI_COLLABORATION_NOTES.md` (⚙️ принцип, 🌐 mesh, раздел 9).
2. Прочитать `docs/MESH_DELIVERY.md`, `docs/SECURITY_RESILIENCE_TEST_PLAN.md` и правило
   milestone-backup в `docs/BACKUP_AND_CLEAN_PC_RECOVERY.md`.
3. Проверить, что текущая ветка — `arena/019ffc32-apumir`. M3.1, M3(a), M3(b) и авто-receipt
   M3(b.1) собраны и проверены на Анне/Жене/Стасе: дедуп, cleanup, origin-delivery без шторма.
4. M3(c.1), c.2 relay-path, r1 reconnect и r2 unique receipt проверены. R2 выявил stale
   retained relay blocker.
5. R3 `v11.16.10` полностью проверен: local delivery=1, duplicate suppressed=1, receipts=2,
   обе очереди cleanup=1, origin-delivery=1, seen tombstones, retained relay=0; после reconnect
   Анны re-subscribe/probe=1, все повторные mesh/UI counts=0 при тех же PID.
6. Milestone-backup на незашифрованном `F:` полностью проверен и безопасно извлечён: 24 files,
   23 manifest entries, bundle/offline restore/source ZIP/artifacts passed; manifest SHA-256 —
   в доп.22. Флешку хранить физически защищённо.
7. Low-volume security smoke generation 2: attack semantics PASS; extra PID Жени был transient,
   единственный текущий процесс снова expected `24000`. Следующий шаг — membership-aware local
   finalizer по сохранённым snapshots, затем одна normal control delivery. Attack не повторять;
   M3(d), UI/background пока не трогать.

