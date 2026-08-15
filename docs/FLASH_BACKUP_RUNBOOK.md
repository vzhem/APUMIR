# APU — обязательная памятка ИИ по копии на флешку

> **HARD RULE:** перед любой командой, которая читает, форматирует или заполняет флешку APU,
> следующий ИИ-чат обязан полностью прочитать этот документ, `MASTER_PLAN_v2.md` и текущий
> `AI_COLLABORATION_NOTES.md`. Эта памятка имеет приоритет над старыми общими backup-примерами.

## 1. Что пользователь называет копией на флешку

По умолчанию нужна **компактная переносимая recovery-копия**: на новом Windows-ПК с интернетом
владелец восстанавливает исходники и Git-историю, скачивает Java/Android SDK/Rust/Gradle
dependencies и может продолжить разработку либо установить сохранённую APK на Android-телефон.

Это **не forensic/evidence dump**. Полные build-кэши, `%TEMP%\apu-*`, logcat, Gradle/Rust targets и
весь Windows workspace рекурсивно в переносимую копию не входят. Полный evidence archive можно
создавать только по отдельной явной просьбе пользователя, в отдельной папке/на отдельном носителе.
Никогда не смешивать два вида копии и не толковать слова «все копии» как разрешение копировать
кэши и временный мусор.

APU — Android-приложение: на новом ПК сама APK не запускается как Windows-программа. APK можно
проверить и установить на телефон; исходники можно открыть и собрать после загрузки инструментов.

## 2. Неподвижные правила

1. Все Windows-команды начинаются `Set-Location C:\APUMIR-arena-test` и выполняются в одном
   guarded `& { ... }`.
2. Сначала спросить/получить букву диска. Никогда её не угадывать.
3. До изменения — отдельный read-only gate: drive letter, label, filesystem, size/free, DiskNumber,
   FriendlyName, BusType, PartitionStyle, DriveType, IsSystem, IsBoot.
4. **Форматирование этой флешки навсегда запрещено.** Одноразовый format уже завершён и принят.
   Будущий ИИ не предлагает и не выполняет `Format-Volume`, `format`, `Clear-Disk` или пересоздание
   partition даже при partial/старой копии. Если identity/filesystem/health неверны — остановиться и
   попросить другой носитель; не «исправлять» форматированием.
5. Принятый format state immutable и не повторяется. Partial portable backup исправляется только
   bounded заменой файлов/версий после read-only inventory.
6. Перед каждой ротацией заново доказать filesystem/label, физический DiskNumber/name/USB identity
   и health. Наличие принятой старой копии нормально: флешка не обязана быть пустой.
7. Portable copy строится **allowlist-ом**, а не `robocopy <repo> /E` без исключений.
8. Всегда хранить минимум две проверенные подписанные APK: `previous` и `latest`.
9. Новую APK нельзя назвать `latest` до проверки size, SHA-256, package/version и Android signer.
10. Сначала полностью записать и проверить новую `latest`; только потом прежняя `latest` становится
    `previous`. Не удалять единственную рабочую предыдущую версию до final verify.
11. Все файлы копии получают SHA-256 manifest; Git bundle проходит `git bundle verify`.
12. Перед безопасным извлечением выполнить независимую повторную hash-проверку с флешки.
13. Не использовать Arena Downloads. Versioned scripts передавать authenticated inline Base64 с
    gzip hash, raw hash и `Parser::ParseFile` zero-errors gate.
14. Телефоны для source/APK flash backup не нужны. Не выполнять ADB-команды.

## 3. Обязательная структура переносимой копии

```text
<FLASH>:\APU_PORTABLE\
├── README_FIRST.txt
├── MILESTONE.json
├── SHA256SUMS.txt
├── versions\
│   ├── previous-vX.Y.Z\
│   │   ├── APU-vX.Y.Z.apk
│   │   └── APU-vX.Y.Z.apk.sha256
│   ├── latest-vX.Y.Z\
│   │   ├── APU-vX.Y.Z.apk
│   │   └── APU-vX.Y.Z.apk.sha256
│   ├── LATEST.txt
│   └── LATEST.json
├── source\
│   ├── APUMIR-all-refs.bundle
│   ├── APUMIR-portable-source\
│   ├── WORKTREE.patch
│   ├── INDEX.patch
│   ├── UNTRACKED_ESSENTIAL.txt
│   └── SOURCE_MANIFEST.json
├── native\
│   ├── libp2p_core-arm64-v8a.so
│   └── libp2p_core-arm64-v8a.so.sha256
├── signing-private\
│   ├── PRIVATE_DO_NOT_SHARE.txt
│   └── p2p-release.jks
└── restore\
    ├── RESTORE_APU.ps1
    ├── VERIFY_BACKUP.ps1
    └── INSTALL_TOOLS_FROM_INTERNET.md
```

Если фактическое имя keystore другое, записать его в manifest и восстановить в ожидаемый Gradle
path. Signing material нельзя коммитить, загружать в GitHub Release или показывать в чате. Флешку
с signing material желательно защитить BitLocker To Go; recovery key держать отдельно.

## 4. Что обязательно сохранить

### 4.1. Две APK

Для `previous` и `latest` сохранить:

- versionName/versionCode и package;
- exact bytes и SHA-256;
- доказательство Android V2/V3 signature;
- SHA-256 signer certificate;
- ссылку на immutable build state и его SHA-256;
- известные ограничения версии.

`LATEST.txt` должен простым текстом первой строкой говорить `LATEST APU VERSION: ...`.
`LATEST.json` содержит обе версии и поле `latest`, чтобы человек и скрипт не гадали по датам файлов.

### 4.2. Исходники и история

Сохранить одновременно:

1. `git bundle create ... --all` со всеми доступными local refs и `git bundle verify`;
2. компактное portable source tree текущего Windows worktree;
3. `git diff --binary` и `git diff --cached --binary`;
4. bounded список essential untracked files;
5. base HEAD, branch, tested application commit и более новый docs commit отдельно;
6. exact generated `arm64-v8a/libp2p_core.so`, использованный проверенной APK;
7. release signing keystore отдельно как private material;
8. Gradle wrapper, Rust/Kotlin source, manifests, scripts, docs и branding assets.

Это важно: проверенная Windows-сборка может содержать незакоммиченные overlays. Один `git archive
HEAD` или один bundle их не сохраняет. Portable tree + patches + untracked essential manifest нужны
обязательно.

## 5. Что запрещено копировать в portable backup

По умолчанию исключить на любой глубине:

```text
.git/                         # вместо неё bundle; не переносить credentials/config
.gradle/
**/build/
rust-core/target/
target/
android-app/.kotlin/
.idea/
.vscode/
.cxx/
.externalNativeBuild/
node_modules/
coverage/
dist/
out/
__pycache__/
*.pyc
*.log
logs/
android-app/local.properties
local.properties
%TEMP%\apu-*                 # states/evidence не копировать wholesale
```

Также не копировать Android SDK, NDK, JDK, Rust toolchains, Gradle caches, Cargo registry/cache,
GitHub credentials, `.env`, phone private identity/data, logcat и переписку. Инструменты и публичные
dependencies новый ПК скачивает из интернета.

Допускается небольшой bounded provenance-набор: final build/install/launch/format state JSON и их
SHA-256, перечисленные явно в `MILESTONE.json`. Не копировать все временные каталоги по wildcard.

## 6. Процедура по шагам

### Шаг A — определить цель

Спросить: portable recovery или отдельно forensic evidence? Если пользователь просто говорит
«копия на флешку/для нового ПК», выбирать portable recovery.

### Шаг B — read-only gate носителя

Показать пользователю точную букву и физическую identity. Требовать:

- разрешённый removable bus (`USB`/явно согласованный внешний диск);
- `IsSystem=False`, `IsBoot=False`;
- expected DiskNumber/FriendlyName/size range;
- отсутствие другого неожиданного mounted partition на том же диске.

Read-only gate ничего не форматирует и не удаляет.

### Шаг C — одноразовый format уже завершён; никогда не повторять

Исторический format F: принят state SHA-256 из раздела 9. Это не шаблон для будущих запусков.
Начиная со следующей ротации разрешены только чтение, staged copy, hash verification и bounded
удаление версии старше `previous` **после** успешной записи новой `latest`.

Если copy прервана, не форматировать и не очищать корень целиком. Прочитать `INCOMPLETE.json`,
сверить manifests и продолжить/заменить только известные portable paths. Не трогать последнюю
проверенную пару previous/latest до готовности новой пары.

### Шаг D — preflight portable contents

До первой записи на флешку проверить на диске C:

- обе APK и build states;
- signer identity;
- native library;
- keystore presence;
- Git/branch/HEAD/status;
- список essential untracked;
- planned file count и planned bytes после exclusions;
- свободное место.

Если planned size неожиданно велик (обычно сотни MB, а не многие GB), остановиться и показать
largest files/directories. Не «решать» это копированием всего подряд.

### Шаг E — staged compact copy

Сначала создать `<FLASH>:\APU_PORTABLE\INCOMPLETE.json`. При ротации существующую пару не
удалять. Сначала записать новую APK во временную `versions/incoming-*`, проверить hash/signature,
затем атомарно переименовать прежнюю `latest` в `previous`, `incoming` в `latest`, обновить
`LATEST.*` и только после final verify удалить версию старше нового `previous`.

Затем независимо сохранить/обновить:

1. versions previous/latest;
2. source bundle и verify;
3. portable source allowlist/exclusion copy;
4. patches + untracked manifest;
5. native/signing private files;
6. restore scripts/docs;
7. manifests/hashes.

Не использовать широкие source glob/`/E` без directory/file exclusions. Любой robocopy лог писать
на C: или в маленький `manifests` файл, не копировать recursive logs обратно в source.

### Шаг F — final verify

Требовать одновременно:

- exact hashes обеих APK;
- signer certificate и version labels;
- exact native hash;
- `git bundle verify` PASS;
- no forbidden directory/file report;
- все `SHA256SUMS.txt` entries PASS при чтении именно с флешки;
- restore script Parser PASS;
- temporary clone из bundle в новый пустой C: path и expected commit reachable;
- portable tree/patch/untracked overlays воспроизводят записанный source manifest.

Только после этого заменить `INCOMPLETE.json` на `BACKUP_COMPLETE.json`. Широкий recursive delete
для marker cleanup не нужен: удалить можно ровно один known marker после final PASS.

### Шаг G — безопасное извлечение

Закрыть все handles и использовать штатное Windows «Извлечь» в Проводнике, затем дождаться
исчезновения буквы. Не вынимать флешку во время copy/hash/verify и не придумывать неподдерживаемую
PowerShell-команду eject без отдельной проверки на этой Windows.

## 7. Восстановление на новом ПК

`RESTORE_APU.ps1` должен:

1. проверить весь `SHA256SUMS.txt` до копирования;
2. предложить новый пустой путь на C: (по умолчанию `C:\APU`);
3. clone из `APUMIR-all-refs.bundle` на recorded base commit/branch;
4. наложить `WORKTREE.patch`, `INDEX.patch` и essential untracked overlay;
5. вернуть private keystore в exact Gradle path без публикации;
6. проверить source manifest, native/APK hashes и signer identity;
7. показать `INSTALL_TOOLS_FROM_INTERNET.md`.

На новом ПК из интернета устанавливаются Git, JDK, Android Studio/SDK/NDK, Rustup, cargo-ndk и
Gradle dependencies. Не переносить их кэши с флешки. После установки Rust проверять только
`build-rust.ps1`, Android — documented Gradle release procedure. Не делать автоматическую сборку,
пока source restore hashes не прошли.

Для немедленного использования без сборки владелец берёт APK из `versions/latest-*`, сверяет hash и
устанавливает её на телефон только отдельным явно согласованным data-preserving install шагом.

## 8. Ошибки, которые нельзя повторять

1. **Нельзя:** `robocopy C:\APUMIR-arena-test F:\... /E` без exclusions. Это тащит build-кэши,
   `.git`, временные артефакты и может занимать гигабайты.
2. **Нельзя:** копировать все `%TEMP%\apu-*` «на всякий случай». Portable backup не evidence dump.
3. **Нельзя:** когда-либо снова форматировать принятую APU-флешку. Старые версии удаляются только
   bounded rotation после проверки новой previous/latest пары; partial copy не повод форматировать.
4. **Нельзя:** повторять consumed destructive/ambiguous harness. Новый version/state после анализа.
5. **PowerShell 5 native stderr:** при `$ErrorActionPreference='Stop'` конструкция `2>&1` может дать
   terminating `NativeCommandError` до проверки `$LASTEXITCODE`. Для Git/robocopy использовать
   `Start-Process` с отдельными stdout/stderr и bounded timeout либо локально контролируемый режим.
6. **Пустые деревья:** не полагаться на `$Measure.Sum` под StrictMode. Считать `[int64]$Total=0` и
   складывать `.Length` каждого файла; пустой список обязан вернуть 0.
7. **CRLF/LF:** `git apply` на Windows может изменить line endings. До исполнения versioned script
   нормализовать в памяти, доказать expected SHA-256, записать, повторно проверить hash и Parser.
8. **Большой inline Base64:** transfer может повредиться. Gzip/raw hashes должны остановить процесс
   до write/execute. Не называть это ошибкой пользователя и не повторять тот же payload вслепую;
   использовать меньший exact patch от уже verified source.
9. **PowerShell aliases регистронезависимы:** никогда не называть helper одной буквой или коротким
   общим словом (`H`, `h`, `?`, `%`, `r`, `cat` и т. п.). В Windows PowerShell имя `H` разрешилось
   как встроенный alias `Get-History`, поэтому вызов `H $bytes` стал ошибочным `Get-History -Id 0`.
   Использовать уникальные Verb-Noun имена вроде `Get-ApuBytesSha256Exact` и вызывать полным именем
   с named parameters. Перед критическим wrapper проверять `Get-Command <name> -All`; любой alias/
   cmdlet conflict блокирует запуск до write/execute. Не повторять тот же wrapper после alias error.
10. **Не считать backup готовым**, если был только copy. Нужны hashes, bundle verify, forbidden scan,
   restore rehearsal и final marker.
11. **Не коммитить generated `.so`/keystore** ради backup. Сохранить их как private artifacts.

## 9. Текущая ротация v11.16.16

Для текущей portable-копии:

- previous: v11.16.15, 22,664,716 B,
  SHA-256 `B675770D043E4ABA5D6D099275F489DB9666A9B16792DD45000A9EC2D243E9B2`;
- latest: v11.16.16, 22,664,712 B,
  SHA-256 `446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D`;
- signer certificate SHA-256:
  `F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7`;
- F: format state SHA-256:
  `A75443F8D302B8D856237F63C2122ABA4C676A6456078066125EF1455E1FACFF`;
- flash identity: F:, Disk 2, `General UDisk`, USB, exFAT, label `APU_BACKUP`;
- accepted portable backup: `F:\APU_PORTABLE`, 278 manifest entries, previous+latest hashes PASS,
  bundle verify PASS, forbidden scan PASS, restore rehearsal PASS;
- final backup state SHA-256:
  `A96500612DD1AC80D908F1F49ADE9536931E512D387C2FD0EDA8CB82772D2483`.

Флешку больше никогда не форматировать. Draft GitHub prerelease остаётся unpublished/empty до
отдельной загрузки exact latest APK и remote re-download/hash verification.
