# APU — резервная копия, флешка и новый ПК

**Единственный документ на эту тему.** Он заменил `PC_TRANSFER.md`,
`RESTORE_ON_NEW_PC.md`, `FLASH_BACKUP_RUNBOOK.md` и
`BACKUP_AND_CLEAN_PC_RECOVERY.md` — все четыре остались в репозитории
заглушками, которые указывают сюда.

Про выбор железа и установку программ — отдельный документ
`docs/NEW_DEVELOPMENT_PC.md`. Точка входа в проект — `docs/START_HERE.md`.

---

## 1. Картотека: что где лежит

| Что | Где |
|---|---|
| Сделать полную копию | `scripts/backup-to-usb.ps1` |
| Сделать быструю копию | `scripts/backup-to-flash.ps1` |
| Восстановить на новом ПК | `scripts/restore-from-usb.ps1` |
| Флешка | белая, метка тома **`APU_BACKUP`**, сейчас буква F:, exFAT |
| Ключ подписи релиза | `android-app/app/p2p-release.jks` — **лежит в git**, едет внутри истории |
| Журнал всех раундов | `docs/AI_COLLABORATION_NOTES.md` (читать с конца) |

Раскладка на флешке после `backup-to-usb.ps1`:

```
F:\APUMIR-backup-<дата-время>\
├── restore-from-usb.ps1        скрипт восстановления (лежит здесь не зря:
│                               на новом ПК репозитория ещё нет)
├── RESTORE-ON-NEW-PC.md        этот документ
├── MANIFEST.txt                sha256 и размер каждого файла
├── repo-state.txt              HEAD, ветка, число коммитов и тегов, чистота дерева
├── repo\apumir-mirror.git      вся история: все ветки, все теги, remote-ссылки
├── repo\apumir-all.bundle      та же история одним файлом (~20 МБ)
├── machine-local\              local.properties, .env — этого в git нет
├── apks\                       собранные APK — тоже вне git
├── toolchain\                  только с -IncludeToolchainCaches
├── uncommitted-changes.patch   только если дерево было не чистым
└── untracked\                  только если были неотслеживаемые файлы
```

Раскладка после `backup-to-flash.ps1` — другая, не путать:
`F:\APU-BACKUP\APUMIR` (зеркало рабочего дерева без build-папок) и
`F:\APU-BACKUP\apumir-full.bundle`.

## 2. Сделать копию — две команды

Оба скрипта ищут флешку **по метке `APU_BACKUP`**, поэтому `-Drive` указывать не
нужно и буква может быть любой.

Полная копия — перед поездкой и перед сменой ПК:

```
powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\backup-to-usb.ps1
```

С флагом `-IncludeToolchainCaches` дополнительно копирует дистрибутив Gradle и
adb (~1,4 ГБ), чтобы новый ПК собирал проект без интернета.

Быстрая копия рабочего дерева — после крупного обновления:

```
powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\backup-to-flash.ps1
```

Каждая полная копия ложится в свою папку с датой и ничего не перезаписывает,
так что старая остаётся точкой отката.

Три вещи, которые `backup-to-usb.ps1` делает не зря — все три пойманы на
реальных отказах:

1. Отказывается работать на поверхностном (`--depth`) клоне и **реально
   клонирует** бандл обратно. `git bundle verify` печатает «records a complete
   history» даже для бандла из поверхностного клона, и такой бандл падает при
   восстановлении с `Failed to traverse parents of commit …`.
2. Сверяет локальные ветки с `origin/*` внутри зеркала. Клон зеркала мапит
   только `refs/heads/*` и выбрасывает `refs/remotes/*`, поэтому отставшая
   локальная ветка утащила бы новый ПК назад. Скрипт подтягивает её, если
   `origin` впереди, и никогда не выбрасывает то, что впереди локально.
3. Кладёт `restore-from-usb.ps1` в корень копии — на новом ПК взять его больше
   неоткуда.

## 3. Что должно быть в копии

Обязательно:

- вся история git — все ветки и все теги, в двух видах (зеркало и бандл);
- ключ подписи релиза `android-app/app/p2p-release.jks` (едет внутри истории;
  скрипт отдельно проверяет, что он доехал);
- `local.properties` — его git игнорирует, а без него SDK не найдётся;
- `.env` / `.dev.vars`, если они есть;
- манифест с sha256 каждого файла — без него недокопированную флешку не отличить
  от хорошей;
- скрипт восстановления и этот документ.

Размер APK **не** признак свежести: у v11.24.0, v11.25.0 и v11.26.0 он совпал до
байта при разных дайджестах. Признак — `built:` у файла и sha256.

**Флешка после бэкапа содержит ключ подписи релиза в распакованном виде.**
Потерять её не страшно — ключ есть на GitHub. Дать ей уйти не туда — страшно.

## 4. Восстановление на новом ПК

Скрипт лежит в корне копии; `-File` и `-BackupDir` указывают на одну и ту же
папку:

```
powershell -NoProfile -ExecutionPolicy Bypass -File F:\APUMIR-backup-<дата-время>\restore-from-usb.ps1 -BackupDir F:\APUMIR-backup-<дата-время>
```

По умолчанию проект встанет в `C:\APU-M8`; другое место — через `-Target`.
Добавь `-RestoreToolchain`, если копия делалась с `-IncludeToolchainCaches`.

Что скрипт делает сверх обычного `git clone`:

1. Сверяет каждый файл с `MANIFEST.txt` по sha256 — недокопированная флешка
   падает сразу, а не через час сборки.
2. Клонирует из зеркала, где есть все ветки и теги.
3. **Перенаправляет `origin` на GitHub.** Иначе `origin` останется флешкой, и
   первый же `git push` запишет обратно на неё.
4. Создаёт локальные ветки: у клона зеркала может не быть рабочей копии вовсе.
5. Возвращает `local.properties` и предупреждает, что `sdk.dir` в нём указывает
   на старый ПК и его надо переписать.
6. Проверяет, что на месте ключ подписи, обе `libp2p_core.so`, gradle wrapper,
   скрипт гейта и журнал раундов.

**`restore-from-usb.ps1` ни разу не запускался по-настоящему.** Отдельные его
команды проверены настоящим git, целиком — нет. Проверить, не трогая рабочий
клон:

```
powershell -NoProfile -ExecutionPolicy Bypass -File F:\APUMIR-backup-<дата-время>\restore-from-usb.ps1 -BackupDir F:\APUMIR-backup-<дата-время> -Target %TEMP%\apumir-restore-test
```

## 5. Что доустановить на новом ПК

Копия **не** содержит тулчейн и учётные данные — их на флешку класть нельзя.

1. **JDK.** Сборка целилась в Java 17 (`sourceCompatibility = VERSION_17`);
   скрипты берут JDK из `C:\Program Files\Android\Android Studio\jbr`.
2. **Android SDK**: платформа **35**, build-tools, platform-tools. `minSdk` 26.
   Gradle 9.5.0, AGP 8.7.3, Kotlin 2.0.21 — их заберёт gradle wrapper, если есть
   интернет, либо возьми с флешки через `-IncludeToolchainCaches`.
3. **`sdk.dir`** в `android-app\local.properties` — переписать на путь к SDK
   нового ПК.
4. **GitHub CLI** и `gh auth login`. Токены и пароли на флешку не кладутся
   принципиально.
5. **Драйверы adb** для телефонов и включённая отладка по USB.

Rust-тулчейн для обычной работы не нужен: `libp2p_core.so` для `arm64-v8a` и
`armeabi-v7a` лежат в git. Правки в `rust-core` собрать нельзя ни на старом, ни
на новом ПК — для них нужен отдельный стенд (см. `docs/NEW_DEVELOPMENT_PC.md`).

**Телефоны при переезде не теряют ничего**: данные приложения живут на телефонах,
а не на ПК. После переезда команды adb работают так же.

## 6. Обновление копии

Копия — это снимок. После каждого релиза и перед поездкой просто запусти бэкап
заново: он создаст новую папку с новой датой.

---

Ниже дословно перенесены три блока из прежних документов: списки того, что
запрещено копировать, неподвижные правила, критерий доказанного восстановления
и накопленные ошибки PowerShell. Формулировки сохранены как были.

---

---

## 7. Что запрещено копировать в копию

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

---

## 8. Неподвижные правила копии

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

---

## 9. Когда восстановление считается доказанным

Нужны все результаты:

- Git checkout совпал с полным commit из `MILESTONE.txt`;
- `build-rust.ps1` завершился успешно;
- release APK собрался с ожидаемой версией;
- hashes файлов внешней копии прошли проверку;
- сохранённый APK установился и запускается;
- для milestone-функции повторён её короткий smoke test.

Если отсутствует хотя бы `APU-source.bundle`, `MILESTONE.txt`, проверенный APK или hashes,
копию не считать полной. После каждого изменения этой инструкции следующая milestone-копия
должна включать обновлённую версию файла.

### Безопасное извлечение внешнего носителя

После final hash verify закрыть окна/файлы носителя и перейти на внутренний диск. Arena-agent не
может сам нажать кнопку на Windows пользователя; он обязан честно дать готовую команду. Для `F:`:

```powershell
Set-Location C:\APUMIR-arena-test

$DriveLetter = "F:"
$Shell = New-Object -ComObject Shell.Application
$DriveItem = $Shell.Namespace(17).ParseName($DriveLetter)
if ($null -eq $DriveItem) { throw "Drive item not found: $DriveLetter" }

$DriveItem.InvokeVerb("Eject")
Start-Sleep -Seconds 10

if (Test-Path "$DriveLetter\") {
    throw "Drive is still mounted; use the Windows Safely Remove Hardware icon"
}

Write-Host "EXTERNAL DRIVE SAFELY EJECTED"
```

Физически вынимать флешку только если путь исчез и Windows не сообщает о занятом устройстве.
Если команда не извлекла диск, не использовать `mountvol /p` как слепой обход: закрыть Explorer,
редактор, антивирусное сканирование и применить значок «Безопасное извлечение устройства».

---

## 10. Известные ошибки PowerShell и безопасное продолжение

### Automatic variables case-insensitive

PowerShell variable names case-insensitive. Нельзя использовать `$Pid` как локальную переменную
или parameter: это read-only automatic `$PID`. Нельзя использовать `$Args` как function parameter:
это automatic `$args`, и binder/call может передать NULL вместо ожидаемого ArgumentList. Для native
wrappers всегда `$ProcessId`/`$AndroidProcessId` и `$ArgumentList`. Проверять versioned scripts
case-insensitive scan по списку automatic variables до phone/build actions.

### Runtime-marker eviction и восстановившаяся сетевая ошибка

Lifecycle-строки `starting`/`supervisor`/первый `ConnAck` могут исчезнуть из Android logcat до
snapshot. Не перезапускать приложение только ради таких breadcrumbs. Более поздний status
`connected` с реальным `connacks>=1`, нулём protocol errors и точным safety contract является
более сильным доказательством состоявшейся session; ранние markers при этом честно записываются как
`NOT_PRESERVED`, а не искусственно объявляются найденными.

Так же нельзя использовать `MQTT error count == 0` как универсальный runtime gate. Допустимый
случай: единичная bounded timeout/backoff-ошибка, после которой в короткий ограниченный срок та же
session/generation/attempt получает настоящий ConnAck/READY, продолжает polling/incoming/heartbeat,
не имеет stall/restart/request failure, не накапливает pending/backpressure и сохраняет PID.
Ошибка считается реальной незакрытой проблемой, если последующего ready/progress нет, ошибки
повторяются tight loop, растут backoff/pending, появляется stall/restart/request timeout либо
меняется process. Классификацию делать versioned saved-evidence-only analyzer по immutable parent
state + manifest hashes; analyzer не вызывает ADB/logcat/install/launch и пишет отдельный recovery
state. Исходный incomplete state никогда не переписывать.

### USB подключён, но `adb devices` пуст

Физически вставленный кабель не доказывает ADB visibility. Если saved stdout содержит только
`List of devices attached` без serial, это не `unauthorized` и не ошибка APU: Windows/ADB вообще
не видит телефон. До install/launch ничего не повторять. Разблокировать нужный телефон, выбрать
USB mode «Передача файлов», проверить Developer options → USB debugging, принять RSA prompt;
проверить data-capable cable/другой USB port. Не делать app data clear/force-stop/relaunch.
Следующий gate — только read-only `adb devices -l`; продолжать phone-changing harness можно лишь
при exact ожидаемом serial со status `device`. `unauthorized`, `offline`, другой serial или пустой
list — STOP. **Непосредственно перед любой командой с ADB/install/launch/logcat ИИ обязан назвать
конкретные нужные телефоны и предупредить подключить их.** Отдельного ответа-подтверждения ждать
не нужно: команду можно дать сразу после предупреждения, но её read-only visibility gate обязан
остановить дальнейшие изменения при absent/unauthorized/offline. Если шаг PC-only, явно сказать,
что телефоны не требуются.

После `Start-Process -PassThru` direct `$Process.ExitCode` иногда остаётся blank/$null даже после
bounded + parameterless WaitForExit/Refresh. `$null -ne 0` создаёт false fail, а `[int]$null`
опаснее: превращается в ложный `0`. Wrapper обязан сначала сохранить raw value и отдельный
`ExitCodeAvailable=($null -ne $RawExitCode)`; cast допустим только при available=true. Если exit
unavailable, success разрешается лишь command-specific semantic gate: например exact device line
или exact install `Success` при empty stderr и последующих package/hash checks. Не объявлять generic
native success по cast 0 и не повторять уже выполненный phone-changing command.

Если install уже дал single exact `Success`, а harness остановился на postinstall snapshot до
launch, recovery сначала только saved-only: immutable state hash с install=true/launch=false,
install stdout/stderr hashes, package UID/versionCode/versionName/firstInstallTime/dataDir и empty
postinstall process evidence. При полном совпадении install считается завершённым и не повторяется;
следующий distinct harness делает live installed/stopped preflight и ровно один оставшийся launch.
Если хотя бы один saved marker/hash расходится — STOP, не угадывать и не reinstall автоматически.

### `else` не распознан как команда

**Симптом:** `else : Имя "else" не распознано...` после того, как пользователь отдельно вставил
и выполнил `if { ... }`, а затем отдельной вставкой — `else { ... }`.

**Причина:** интерактивный Windows PowerShell уже завершил синтаксическую конструкцию `if`.
`else` должен попасть в тот же parse/paste-блок.

**Обход:** давать и вставлять `if { ... } else { ... }` одним блоком или не использовать
отдельный `else`. Если сам `if` уже успешно показал нужный результат, отдельная ошибка `else`
не отменяет этот результат. Не повторять опасные/изменяющие команды только ради удаления текста
ошибки из консоли.

### Native stderr, `NativeCommandError` и command-specific exit codes

**Симптом:** при `$ErrorActionPreference = "Stop"` guarded block прекращается на исправной native
программе, например `java -version`, хотя она печатает правильную версию и фактически должна
завершиться с exit code 0.

**Общая причина:** внешняя программа сама выбирает stdout/stderr. Windows PowerShell 5 при
перенаправлении native stderr через `2>&1` превращает строки stderr в `ErrorRecord`; при
`$ErrorActionPreference = "Stop"` первый такой record становится terminating
`NativeCommandError` **до** проверки `$LASTEXITCODE`. Наличие stderr само по себе не означает
ошибку программы.

Известные взаимосвязанные случаи:

- `java -version` штатно пишет всю версию в stderr;
- Cargo / `cargo ndk` пишут progress и compiler warnings в stderr даже при успешном build;
- Gradle/JVM, Git fetch и ADB daemon могут писать нормальный progress/diagnostics в stderr;
- `adb shell pidof <package>` возвращает exit 1 + empty output, когда process просто отсутствует;
- `git diff --quiet` возвращает exit 1, когда differences есть; `git grep` — когда совпадений нет.

**Обязательное правило для critical harness:** не вызывать expected-stderr native command как
`(& command 2>&1)` внутри outer `ErrorActionPreference=Stop`. Запускать через `Start-Process` с
раздельными stdout/stderr files, затем проверять `.ExitCode`, command-specific allowed outcomes,
positive success marker и artifact/hash. Пример для Java:

```powershell
Set-Location C:\APUMIR-arena-test
& {
    $ErrorActionPreference = "Stop"

    $Java = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\java.exe"
    $Stdout = Join-Path $env:TEMP "apu-java-version.stdout.log"
    $Stderr = Join-Path $env:TEMP "apu-java-version.stderr.log"

    $Process = Start-Process -FilePath $Java -ArgumentList "-version" `
        -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr -Wait -PassThru
    $Text = (@(Get-Content $Stdout) + @(Get-Content $Stderr)) -join "`n"

    if ($Process.ExitCode -ne 0 -or $Text -notmatch '17\.0\.17') {
        throw "Approved Java check failed: exit=$($Process.ExitCode)"
    }
    Write-Host $Text
}
```

Для Cargo/Gradle дополнительно требовать `Finished release`/`BUILD SUCCESSFUL`; для APK — exact
version, signer и embedded native hash. Exit 0 без positive marker/artifact недостаточен. Stderr
при exit 0 не скрывать: сохранять в evidence log и отличать warning/progress от `error:`.
PowerShell child/host serialization может продублировать `Write-Host` marker в stderr/CLIXML:
uniqueness feature/config marker считать по authoritative stdout, а не по объединённым streams.
Stderr duplicate не удалять; записать counts/hashes обоих streams. `Finished release`, exit0,
compiler errors=0 и generated artifact/hash проверяются независимо. Combined marker count=2 при
stdout=1 не является compile failure и исправляется saved-evidence recovery без rebuild.

Для команд с нормальным nonzero outcome parser обязан явно разрешать только точную комбинацию,
например `pidof exit=1 + empty`; любой другой exit/output — failure. Нельзя глобально считать
exit 1 успешным и нельзя ослаблять весь outer guard через `ErrorActionPreference=Continue`.

Если старый one-shot block уже остановился на `NativeCommandError`, его state/log не удалять и
блок не повторять. Сначала read-only доказать `BuildAttempted/publishCalled=false`, отсутствие
artifact/network effect и сохранность входных hashes; затем использовать distinct `build2`/
`recovery2` state paths с исправленным native wrapper.

**Fetch уже прошёл, поздний `ls-remote` недоступен:** не повторять network step вслепую. Если
`git fetch` завершился exit0, `FETCH_HEAD` равен ожидаемому commit и соответствующий local
`refs/remotes/origin/<branch>` тоже равен ему, fetched object уже локально проверяем. Поздний
redundant `ls-remote` timeout/empty не отменяет fetch. Продолжить отдельным local-only block:
проверить branch/worktree/artifact hashes, оба local refs, затем mixed reset/точечный restore.
Если FETCH_HEAD или tracking ref расходятся — STOP, не угадывать и не force/reset.
`Test-NetConnection github.com -Port 443=True` доказывает только один TCP handshake и не гарантирует,
что следующий Git HTTPS-запрос переживёт временный outage. Если fetch после такого PASS всё же
таймаутится, не менять proxy/DNS/config и не повторять большой launcher: сохранить worktree,
сделать отдельный read-only DNS/TCP/`curl.exe -I` report, подождать и позже выполнить один isolated
fetch. Untracked artifact не удалять через `git clean`; обычный fetch/mixed reset его не трогает.

### GitHub недоступен и Arena не передаёт скачиваемый файл

Incremental `git bundle` с prerequisite=точный Windows HEAD остаётся правильным переносимым
форматом: агент создаёт его, выполняет `git bundle verify`, фиксирует size/SHA-256; Windows после
реального получения файла повторяет size/SHA/verify и делает `git fetch <local.bundle>
refs/heads/<session-branch>`. Но **наличие bundle у агента не доказывает, что пользователь смог его
скачать**. В проверенном случае Arena file viewer/Download и поиск в browser Downloads не доставили
файл на Windows. Это ограничение интерфейса передачи, а не ошибка пользователя. Не отправлять его
повторно искать карточку, кнопку Download или файл в Downloads.

Рабочий fallback без скачивания — authenticated source overlay прямо в PowerShell:

1. Зафиксировать exact Windows branch/HEAD/worktree и target application commit.
2. Создать минимальный patch только нужной независимой части (например сначала Rust, затем Kotlin)
   через Git diff от exact base; не включать generated binaries или untracked artwork.
3. Deterministic gzip-сжать patch, записать размер/SHA-256 gzip и размер/SHA-256 raw patch,
   закодировать gzip в Base64.
4. Встроить Base64 в полный PC-only PowerShell here-string. Блок начинается с
   `Set-Location C:\APUMIR-arena-test` и одного atomic
   `& { $ErrorActionPreference = "Stop"; ... }`; сам создаёт `C:\APUMIR-transfer`.
5. После Base64 decode проверить **размер и SHA-256 gzip**, после распаковки отдельно проверить
   **размер и SHA-256 patch**. Base64 без этих проверок не является identity/authenticity gate.
6. До apply проверить exact branch/HEAD, ожидаемый `git status`, hashes generated `.so` и каждого
   сохраняемого untracked artifact. Затем выполнить `git apply --check --whitespace=error-all`,
   `git apply`, проверить exact список изменённых paths и filter-aware `git hash-object --path`
   каждого target против заранее записанного blob ID.
7. Build запускать только после всех source gates, bounded child process с отдельными stdout/stderr
   и immutable state. PASS требует positive completion marker, zero compiler errors и exact нового
   artifact/hash; raw process ExitCode может быть unavailable в Windows wrapper, поэтому один blank
   exit не отменяет сильные независимые markers, но availability обязана быть честно записана.

Если inline Base64 прошёл `FromBase64String`, но decoded gzip не совпал по size/SHA-256, это
**transfer-integrity failure**: чат/clipboard передал не те байты, даже если текст визуально похож.
Не повторять тот же огромный block и не удалять созданный gzip. Поскольку hash gate стоит до
`GzipStream`, `git apply` и build, такой stop сам по себе не меняет source/APK. Следующий шаг только
read-only: фактические path/size/SHA-256 сохранённого gzip, наличие decompressed runner/patch/state,
branch/HEAD/status и hashes охраняемых native/icon. Если размер совпадает, сравнить fixed-size chunk
hashes (например 1024 B). При малом числе mismatch не пересылать весь payload: передать только exact
chunk bytes, проверить chunk length/SHA, скопировать старый файл в новый distinct byte array,
заменить exact offset и потребовать полный expected file SHA. Только затем decompress/ParseFile/run.
Старые bytes остаются evidence; recovery не перезаписывает их. Не обвинять пользователя и не
ослаблять hash gate ради продолжения.

Offline patch переносит содержимое, но не Git history: Windows `HEAD` остаётся base commit, а
worktree становится dirty. Не объявлять его синхронизированным target commit. Историю позже
согласовать отдельно; generated `.so` не commit, untracked icon не терять. Запрещены `git clean`,
broad stash/pop, blind hard reset и automatic retry после partial/ambiguous attempt. Если overlay
разделён Rust/Kotlin, Rust compile PASS не разрешает APK compile до применения и проверки Kotlin
части. Bundle можно снова выбрать только когда есть реально работающий канал доставки файла; он не
переносит untracked artifacts и не заменяет eventual push/history reconciliation.

**Связанное правило parser compatibility:** raw stdout/stderr должны быть записаны до parsing.
Нельзя считать build неуспешным только потому, что новая версия native tool изменила английский
label, пробелы, регистр или separators digest. Для certificate/hash искать однозначную semantic
строку, брать значение после label, удалять separators (`[^0-9A-Fa-f]`) и требовать ровно 64 hex.
Exit 0 и общий marker всё равно недостаточны без exact identity hash. Если build, artifact и signer
verify уже завершились, parser failure исправлять read-only анализом сохранённого output/artifact;
не удалять evidence, не rebuild и не перезаписывать готовый APK. Тот же принцип применять к
`aapt`, `apksigner`, `dumpsys`, Cargo/Gradle markers и любым version-dependent CLI outputs.

**Cleanup временных test-файлов:** пока gate не закрыт, все state/evidence в `%TEMP%`, на которые
ссылаются последующие harness hashes, считаются активными и не удаляются. Versioned scripts в Git и
точные APK/native artifacts не являются временным мусором. После итогового PASS сначала сделать
read-only inventory: путь, размер, SHA-256, кем referenced и final/intermediate classification.
Удалять можно только согласованный bounded список unreferenced intermediate logs/states; final PASS
manifests/evidence и authoritative artifacts сначала включить в принятую milestone/evidence копию.
Не использовать широкие `Remove-Item $env:TEMP\apu-* -Recurse` и не чистить evidence автоматически.

**PowerShell alias collision:** имена команд регистронезависимы. Не использовать helper names из
одной буквы/общего слова (`H`, `h`, `r`, `cat`, `%`, `?`): `H` разрешился как alias `Get-History` и
превратил hash helper в `Get-History -Id 0`. Использовать уникальный Verb-Noun (`Get-ApuBytesSha256Exact`),
полное имя + named parameters и до critical wrapper проверять `Get-Command <name> -All`.

**Long critical harness и ParserError:** большие install/test/backup blocks хранить versioned `.ps1`
в `scripts/`, а не вставлять сотнями строк в interactive prompt. Перед запуском использовать
`[System.Management.Automation.Language.Parser]::ParseFile` и требовать zero parse errors. Не
разрывать binary operators (`-join`, `-f`, `-replace`, `+`) между строками; безопаснее сначала
получить `$RawLines`, затем отдельным atomic expression `$Text = $RawLines -join "`n"`.

PowerShell полностью парсит scriptblock до исполнения. Если большой paste получил `ParserError`,
outer `& {}` не начинался, но оставшиеся строки clipboard могут затем автоматически выполниться
уже вне guard с NULL/stale variables. После первого `ParserError` немедленно остановиться и не
выполнять остаток. Последующий текст `PASS` недействителен, если перед ним нет успешной записи
non-empty state path, проверки `Test-Path` и state SHA-256. Для нового исправленного запуска
использовать distinct state/evidence names; старый parser output не выдавать за test attempt.

**Cross-script literal с `$variable`:** double-quoted launcher string интерполирует переменную
самого launcher. Если она там не объявлена, expected text незаметно превращается в empty/другую
строку и даёт ложный count. Для поиска буквального кода вроде `$Gradlew` использовать single-quoted
PowerShell string с doubled inner quotes или AST. До retry доказать, что throw был выше вызова
harness и output state/artifacts отсутствуют; старый launcher не повторять.

**Nested PowerShell build и `Start-Process -Wait`:** не использовать неограниченный `-Wait` для
child PowerShell, который сам запускает Cargo/Gradle process tree. На Windows child может уже
завершиться и записать `Finished release`/artifact, но parent wrapper продолжит ждать до `Ctrl+C`,
не записав exit/state. Запускать child с `-PassThru` без `-Wait`, затем ждать exact child PID через
`.WaitForExit(boundedMilliseconds)`; timeout сохраняет state и process tree diagnostics, но не
перезапускает build автоматически. Если build logs уже имеют positive marker и artifact exact,
сначала доказать отсутствие cargo/rustc/child descendants, безопасно освободить только wrapper,
затем оформить read-only recovery state. Не удалять logs и не rebuild готовый artifact.

Wait helper не должен возвращать bare scalar exit code 0: в сложном PowerShell pipeline такой
результат может стать `$null` и дать ложный preflight failure. Возвращать объект с явными полями
`Exited`, `ExitCode`, `ProcessId`, затем читать `.ExitCode`; после успешного bounded wait вызвать
parameterless `.WaitForExit()` для flush redirected stdout/stderr.

### Backup остановился после создания части файлов

**Симптом:** папка milestone уже существует, некоторые большие файлы готовы, но остался
`INCOMPLETE.tmp`, отсутствуют `SHA256SUMS.txt` или final verify.

**Обход:** не удалять папку и не начинать всё заново. Сначала проверить обязательные файлы,
повторно выполнить `git bundle verify`, проверить точные APK/native hashes, продолжить с точки
остановки, затем полностью пересоздать `SHA256SUMS.txt`. `INCOMPLETE.tmp` удалять только после
записи всех файлов. После resume всё равно выполнить независимую hash-проверку и пробный clone в
новую временную папку.

Если marker отсутствует, но verify ещё не записан, не угадывать состояние: проверить
`BACKUP_STATUS.txt`, manifests и hashes, не перезаписывая артефакты без причины.

### Tested code commit и более новый documentation commit

APK может быть собран из tested code commit, а инструкции — дополнены отдельными docs-only
commit. Перед backup доказать `git diff --quiet <code> <docs> -- rust-core android-app`. В
`MILESTONE.txt` записать оба SHA. Bundle обязан содержать оба; restore делает checkout нужной
ветки и `reset --hard` на documentation commit. Если application code между ними отличается,
такой docs commit нельзя приписывать уже протестированному APK без новой сборки/теста.

### Text file даёт raw hash mismatch на Windows

**Симптом:** рабочий `.ps1`/другой text file проходит `git diff --quiet`, но его raw SHA-256
отличается от sandbox; либо ZIP проходит общий SHA-256 и воспроизводится новым `git archive`, но
`git hash-object --no-filters <extracted-file>` не равен `git rev-parse <commit>:<path>`.

**Причина:** Windows `core.autocrlf`/attributes могут выдать text с CRLF, тогда как Git blob и
sandbox используют LF. Raw hash сравнивает разные представления одного текста и создаёт ложный
fail. Для executable APK/native raw SHA обязателен; для committed text cross-platform identity
проверять filter-aware, а raw working-tree SHA использовать только при заранее закреплённом EOL.

Для рабочего harness безопасный gate: `git diff --quiet HEAD -- <path>`, затем
`git hash-object --path=<path> <working-file>` == `git rev-parse HEAD:<path>`, затем PowerShell
`Parser::ParseFile` и semantic/static checks. При одном лишь raw CRLF/LF mismatch не делать новый
fetch/reset и не запускать harness автоматически.

**Правильная проверка:** одновременно доказать:

1. SHA-256 извлечённого файла USB ZIP равен SHA-256 файла из свежего `git archive` того же commit;
2. filter-aware hash совпадает с commit blob:

```powershell
Set-Location C:\APUMIR-arena-test

$RelativePath = "rust-core/src/engine/core.rs"
$ExtractedFile = "C:\TEMP\archive\rust-core\src\engine\core.rs"
$Commit = "FULL_DOCUMENTATION_COMMIT"

$PathArgument = "--path={0}" -f $RelativePath
$FilteredBlob = (git hash-object $PathArgument $ExtractedFile).Trim()
$ExpectedBlob = (git rev-parse ("{0}:{1}" -f $Commit, $RelativePath)).Trim()

if ($FilteredBlob -ne $ExpectedBlob) {
    throw "Filter-aware source ZIP check failed"
}
```

Не заменять общий SHA-256 manifest только filter-aware проверкой: нужны оба независимых слоя.

### `git fsck` показывает dangling commits после clone из полного bundle

Полный `--all` bundle может содержать stash и дополнительные remote refs. Пробный clone не
обязан привязать каждый такой объект к своей рабочей ветке, поэтому `git fsck` может напечатать
`dangling commit`. Если `fsck` завершился с exit code 0, connectivity проверена, нужные commits
доступны и checkout чистый — это информационное сообщение, а не повреждение backup.

---

## 11. Ошибки, которые нельзя повторять

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
