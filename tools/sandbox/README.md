# Подготовка песочницы (2026-08-26)

Рабочий материал для сессии `arena/01a03c3d-apumir`. Здесь: чем песочница
располагает, что уже проверено, где в коде живут незакрытые задачи и что
запускать вечером на Windows владельца.

## 1. Чем песочница располагает (проверено замером, не по памяти)

| Инструмент | Наличие | Как проверено |
|---|---|---|
| `cargo` / `rustc` | нет | `command -v` пусто; `~/.cargo`, `~/.rustup` отсутствуют |
| `java` / `gradle` / `kotlinc` | нет | `command -v` пусто |
| `adb` | нет | `command -v` пусто (и не нужен — телефоны не трогаем) |
| `python3` 3.11.2, `pip` 23.0.1 | есть | `--version` |

Доступ в сеть избирательный, замерен кодами ответа `curl`:

- доступны: `github.com` → 200, `pypi.org` → 200;
- закрыты: `crates.io`, `index.crates.io`, `repo.maven.apache.org`,
  `services.gradle.org`, `static.rust-lang.org`, `deb.debian.org` → 000.

`rust-core/vendor` отсутствует, `rust-core/Cargo.lock` отсутствует (он в
`.gitignore`), `apt-get install openjdk-17-jdk-headless` →
`E: Unable to locate package`.

**Вывод:** `cargo test --lib` и `:app:testDebugUnitTest` в этой песочнице
незапускаемы, и поставить тулчейн тоже нельзя (rustup тянет бинарники со
`static.rust-lang.org`, а он закрыт). Любой прогон, требующий компиляции,
остаётся на Windows владельцу и помечается как непроверенный здесь.

## 2. Базовая линия кода

`git log --oneline -1` → `f0ca016 Drop redundant snapshot patch file`.
Клон **shallow**: `git rev-parse --is-shallow-repository` → `true`,
`git rev-list --count HEAD` → `1`. Это значит, что правило из
`docs/AI_HANDOFF.md` про сверку HEAD перед коммитом здесь особенно важно:
истории для сравнения локально почти нет.

Сверка с выпущенным релизом (тег подтянут отдельно:
`git fetch origin refs/tags/v11.18.0:refs/tags/v11.18.0`):

```
git diff --stat v11.18.0^{commit} f0ca016
 docs/AI_COLLABORATION_NOTES.md |   3 ++
 docs/AI_HANDOFF.md             | 100 +++++++++++++++++++++++++++++++++++++++++
```

То есть **мой стартовый код совпадает с выпущенным v11.18.0**, отличие —
только документация. Никакого дрейфа продакшн-кода нет.

Отдельно: `origin/main` (`6d28249`, 2026-08-17) **отстаёт** — в нём нет ни
`docs/AI_HANDOFF.md`, ни `LanDirectChannel.kt`. Вся текущая работа живёт в
ветках `arena/*`, не в `main`. Базой считать `main` нельзя.

Ветви `arena/01a03c3d-apumir` на origin ещё нет — первый push её создаст.

## 3. Проверочный харнес: `tools/sandbox/struct_check.py`

Лексер, а не «грубый brace-чекер по кавычкам», который
`docs/AI_HANDOFF.md` запрещает (он врёт на апострофах в KDoc).

Языки: Kotlin, Rust, PowerShell. Учитывает строки, raw-строки, символьные
литералы, вложенные блочные комментарии, шаблоны Kotlin `${...}`,
интерполяцию и here-строки PowerShell, lifetime'ы Rust против символьных
литералов. Для `.ps1` дополнительно разбирает не-ASCII на «в комментариях»
(предупреждение) и «в коде/строках» (ошибка при `--ascii=strict`).

Запуск:

```
python3 tools/sandbox/struct_check.py --self-test
python3 tools/sandbox/struct_check.py android-app rust-core tools scripts
python3 tools/sandbox/struct_check.py --ascii=strict scripts/<новый>.ps1
```

Результаты фактических прогонов:

- `--self-test` → **36/36 пройдено**, код возврата 0;
- прогон по `android-app rust-core tools scripts` → **проверено файлов: 381,
  с ошибками: 0**, предупреждений 5 (все — не-ASCII в архивных скриптах
  прошлых релизов и в комментариях `scripts/patch-release-workflow-v2.ps1`),
  код возврата 0;
- мутационная проверка: удаление одной `}` из копии `LanDirectChannel.kt`
  ловится (`строка 38: незакрытая '{'`), удаление `}` из копии
  `scripts/deploy-f4-direct.ps1` ловится (`строка 129: незакрытая '{'`),
  вставка кириллицы в код `.ps1` ловится при `--ascii=strict`.

**Чего харнес НЕ доказывает:** типы, семантику, компиляцию. Зелёный прогон
здесь не равен зелёному `cargo test` / Gradle-гейту.

Известное ограничение: `'a;` в Rust (lifetime там, где нужен символьный
литерал) ошибкой не считается — это задача компилятора.

## 4. Якоря в коде под незакрытые задачи

Номера строк сверены на `f0ca016`.

**Задача 1 — контрольный замер скорости после пайплайна.**
Пайплайн приёма: `LanDirectChannel.kt:124` `serveConnection`, executor
`LanDirectChannel.kt:130` (`ThreadPoolExecutor` + `ArrayBlockingQueue(1024)`
+ `CallerRunsPolicy`). Счётчик кадров: `LanDirectChannel.kt:165` —
`if (frames == 1 || frames % 512 == 0) diag("lan-frames from $senderId: $frames")`.
Сбор логов: `scripts/deploy-f4-direct.ps1:23`, тег `lan-frames` в фильтре уже есть.

**Задача 2 — приёмка ≥1 ГиБ реальным документом.**
`FileTransferReceiver.kt` (приём, `MAX_PENDING_BYTES = 32 МиБ` на строке 587,
`MAX_BUFFERED_CHUNK_BYTES = 16 МиБ` на 590), `ReceivedFileStore.kt`,
геометрия чанков `FileTransferReceiver.kt:495`.

**Задача 3 — троттлинг `lan-seek` для мёртвых нод.**
Отправка seek: `FileTransferRouter.kt:94`. TTL повторов:
`LanDirectChannel.kt:516` `ESTABLISH_RETRY_TTL_MS = 15_000L`, проверка
на `LanDirectChannel.kt:410`.

Расхождение с журналом, которое надо уточнить до правки: в notes написано
«relay queue 500 переполнена», но в коде
`MAX_QUEUE_PER_NODE: usize = 1_000` (`rust-core/src/config/defaults.rs:59`)
**не используется нигде** — объявление найдено, обращений нет (проверено
`grep` по всем `.rs` и `.kt`). Число 500 в коде встречается как
`MAX_GOSSIP_CACHE: usize = 500` (`rust-core/src/engine/core.rs:1030`,
применяется на 1325 для обрезки `seen_gossip`, то есть это дедup-кэш, а не
очередь доставки). Реальные лимиты `relay_store` передаются вызывающим
через параметр `limit`. Прежде чем троттлить, стоит понять, какое именно
сообщение в логах дало «500».

**Задача 4 — mesh-кастодиальная offline-доставка (написано, не подключено).**
Rust: `rust-core/src/network/file_custody.rs` (1928 строк;
`inventory` :562, `load_for_delivery` :632,
`pull_missing_for_authenticated_recipient` :732),
`file_custody_receipt.rs` (707), `file_custody_replication.rs` (734),
`storage/relay_at_rest.rs` (872).
В FFI (`rust-core/src/lib.udl`) из кастодии наружу смотрит только
`relay_custody_mode()` (строка 201) — то есть планировщик и приём
действительно не выведены в движок/FFI/Android, что сходится с журналом.

**Задача 5 — LAN-aware размер кадра.**
Лимит фрагмента: `FileTransferPacketCodec.kt:15`
`MAX_FRAGMENT_PAYLOAD_BYTES = 4 * 1024`; обоснование в комментарии на
строках 9–10 того же файла (~33 КБ base64-фрагменты не доходили живым
MQTT-путём, наблюдение 2026-08-21).
Важно не перепутать: `MAX_FRAME_BYTES = 4 * 1024 * 1024`
(`LanDirectChannel.kt:509`) — это уже лимит LAN-кадра, 4 **МиБ**, и
`MAX_FILE_CHUNK_BYTES = 4 * 1024 * 1024` (`rust-core/src/crypto/file_transfer.rs:19`).
Поднимать надо именно `MAX_FRAGMENT_PAYLOAD_BYTES`, и только для LAN-пути.

## 5. Вечерний прогон на Windows

Только файлами, как требует `docs/AI_HANDOFF.md`:

```
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-f4-direct.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-f4-direct.ps1 -CollectLogs
```

Перед прогоном сверить, что на телефоне свежий код: `dumpsys` по
`lastUpdateTime` (маркеры в logcat ротируются шумом). Деплой-скрипт сам
гоняет тесты перед сборкой; отдельно смешивать юнит-тесты с assemble в одном
вызове `--tests` не надо.

Телефоны — только с явным предупреждением и поимённо: Стас = TECNO LI6
(`11567254BK001192`), Аня = MTN NX1 (`AUYF6R5923006121`).
