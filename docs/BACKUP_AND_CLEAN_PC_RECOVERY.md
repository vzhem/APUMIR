# APU — резервная копия важной сборки и запуск на чистом Windows-ПК

Этот документ входит в проект и должен попадать в каждую milestone-копию. Репозиторий и
папки могут технически называться `APUMIR`, но пользовательское имя продукта — только **APU**.

## 1. Обязательное правило для ИИ

После каждой **существенно важной и проверенной** версии ИИ обязан сам напомнить владельцу:

> Эта сборка является важной точкой сохранения. Хотите сделать проверенную резервную копию
> APU на внешний диск/флешку вместе с исходниками, Git-историей, APK, контрольными суммами и
> инструкцией запуска на чистом ПК?

Напоминание делать после проверки версии и до перехода к следующему крупному этапу. Важная
точка — это хотя бы один из случаев:

- на реальных телефонах пройден новый milestone или исправлен серьёзный blocker;
- готов release candidate либо опубликован стабильный release;
- предстоит рискованная миграция, большой refactor, смена форматов/ключей/сборочной системы;
- пользователь отдельно сказал, что текущее состояние особенно важно сохранить.

Обычную неудачную/промежуточную сборку без новых подтверждённых результатов milestone-копией
не называть. Копирование не начинать без согласия пользователя. После согласия сначала спросить
букву внешнего диска и проверить свободное место. Команды давать маленькими PowerShell-шагами;
каждый блок начинает `Set-Location`.

## 2. Что должна содержать milestone-копия

В версионной папке `APU-<version>-<date>-<short-commit>` сохранить:

1. `APU-source.bundle` — полный Git bundle со всеми доступными ветками, тегами и историей;
2. `APU-source-<commit>.zip` — удобная копия файлов точно из проверенного коммита;
3. проверенный `APU-<version>.apk`, его размер и SHA-256;
4. проверенный `libp2p_core.so` для `arm64-v8a` как отдельный артефакт;
5. `MILESTONE.txt`: версия, полный commit, ветка, дата, результаты теста и известные ограничения;
6. `ENVIRONMENT.txt`: версии Java, Git, Gradle, Rust, `cargo-ndk`, ADB/SDK/NDK и пути;
7. эту инструкцию и основные документы из `docs/`;
8. `SHA256SUMS.txt` для всех файлов копии.

GitHub полезен как дополнительная копия, но не заменяет автономный внешний носитель.
Сборочные кэши (`build`, `.gradle`, `target`), `local.properties`, временные логи и stash в
milestone-копию не нужны. Stash не является надёжной резервной копией.

### Безопасность

Полная копия проекта может содержать release signing material. Внешний диск желательно
зашифровать BitLocker To Go; recovery key хранить отдельно от этого диска. Не класть в копию
GitHub tokens, пароли, `.env`, Git credentials, переписку и private identity keys телефонов.
Пользовательские данные телефонов требуют отдельного явно согласованного зашифрованного export.
Milestone-копию с signing material нельзя публиковать в общий доступ.

## 3. Создание копии на внешний диск

Ниже шаблон. ИИ перед выполнением обязан подставить фактические `<version>`, `<branch>`,
`<commit>`, SHA-256 проверенного APK и выбранную пользователем букву диска. Не предлагать
угадывать букву диска.

Сначала проверить носитель, commit и артефакты:

```powershell
Set-Location C:\APUMIR-arena-test

$ExternalRoot = "E:\APU-backups"       # заменить после ответа пользователя
$Version = "v11.16.X"                   # заменить фактической проверенной версией
$ExpectedBranch = "arena/SESSION-apumir" # заменить фактической веткой
$ExpectedCommit = "FULL_COMMIT_SHA"    # заменить полным SHA
$ExpectedApkHash = "APK_SHA256"        # заменить проверенным SHA-256

$DriveRoot = [System.IO.Path]::GetPathRoot($ExternalRoot)
if (-not (Test-Path $DriveRoot)) {
    throw "Внешний диск не найден: $DriveRoot"
}
$DriveName = $DriveRoot.Substring(0,1)

$Branch = (git branch --show-current).Trim()
$Commit = (git rev-parse HEAD).Trim()
if ($Branch -ne $ExpectedBranch) {
    throw "Ожидалась ветка $ExpectedBranch, найдена $Branch"
}
if ($Commit -ne $ExpectedCommit) {
    throw "Ожидался commit $ExpectedCommit, найден $Commit"
}

$Apk = (Resolve-Path ".\android-app\app\build\outputs\apk\release\app-release.apk").Path
$NativeLib = (Resolve-Path ".\android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so").Path
$ActualApkHash = (Get-FileHash $Apk -Algorithm SHA256).Hash
if ($ActualApkHash -ne $ExpectedApkHash) {
    throw "SHA-256 APK не совпадает: $ActualApkHash"
}

Get-PSDrive -Name $DriveName | Select-Object Name, Free, Used
Write-Host "PRECHECK PASSED"
```

После успешного precheck создать набор. Значения переменных должны оставаться из предыдущего
блока; если открыто новое окно PowerShell, ИИ обязан дать блок заново со всеми значениями.

```powershell
Set-Location C:\APUMIR-arena-test

$ShortCommit = (git rev-parse --short=7 HEAD).Trim()
$DateStamp = Get-Date -Format "yyyy-MM-dd"
$BackupDir = Join-Path $ExternalRoot ("APU-{0}-{1}-{2}" -f $Version, $DateStamp, $ShortCommit)
$ArtifactsDir = Join-Path $BackupDir "artifacts"
$DocsDir = Join-Path $BackupDir "docs"

New-Item -ItemType Directory -Force $ArtifactsDir, $DocsDir | Out-Null

$BundlePath = Join-Path $BackupDir "APU-source.bundle"
$SourceZipPath = Join-Path $BackupDir ("APU-source-{0}.zip" -f $ShortCommit)

git bundle create $BundlePath --all
if ($LASTEXITCODE -ne 0) { throw "Не удалось создать Git bundle" }

git bundle verify $BundlePath
if ($LASTEXITCODE -ne 0) { throw "Git bundle не прошёл verify" }

git archive --format=zip --output $SourceZipPath HEAD
if ($LASTEXITCODE -ne 0) { throw "Не удалось создать source ZIP" }

Copy-Item $Apk (Join-Path $ArtifactsDir ("APU-{0}.apk" -f $Version))
Copy-Item $NativeLib (Join-Path $ArtifactsDir "libp2p_core-arm64-v8a.so")
Copy-Item ".\docs\*.md" $DocsDir

git status --short | Set-Content (Join-Path $BackupDir "WORKTREE_STATUS.txt") -Encoding UTF8

$Milestone = @(
    "Product: APU"
    "Version: $Version"
    "Branch: $Branch"
    "Commit: $Commit"
    "Created: $([DateTimeOffset]::Now.ToString('o'))"
    "APK SHA-256: $ExpectedApkHash"
    "Test result: REPLACE_WITH_VERIFIED_RESULT"
    "Known limitations: REPLACE_WITH_KNOWN_LIMITATIONS"
)
$Milestone | Set-Content (Join-Path $BackupDir "MILESTONE.txt") -Encoding UTF8

function Get-NativeVersionReport {
    param([string]$Label, [string]$CommandLine)
    $Output = cmd.exe /d /c "$CommandLine 2>&1" | Out-String
    $ExitCode = $LASTEXITCODE
    return @(("--- {0} (exit code {1}) ---" -f $Label, $ExitCode), $Output.Trim(), "")
}

$EnvironmentReport = @()
$EnvironmentReport += "Created: $([DateTimeOffset]::Now.ToString('o'))"
$EnvironmentReport += "ANDROID_HOME=$env:ANDROID_HOME"
$EnvironmentReport += "ANDROID_SDK_ROOT=$env:ANDROID_SDK_ROOT"
$EnvironmentReport += "ANDROID_NDK_HOME=$env:ANDROID_NDK_HOME"
$EnvironmentReport += Get-NativeVersionReport "Git" "git --version"
$EnvironmentReport += Get-NativeVersionReport "Java" "java -version"
$EnvironmentReport += Get-NativeVersionReport "Rust compiler" "rustc --version"
$EnvironmentReport += Get-NativeVersionReport "Cargo" "cargo --version"
$EnvironmentReport += Get-NativeVersionReport "cargo-ndk" "cargo ndk --version"
$EnvironmentReport += Get-NativeVersionReport "ADB" "adb version"
$EnvironmentReport += Get-NativeVersionReport "Gradle wrapper" "android-app\gradlew.bat --version"
if ($env:ANDROID_HOME -and (Test-Path (Join-Path $env:ANDROID_HOME "ndk"))) {
    $EnvironmentReport += "--- installed NDK folders ---"
    $EnvironmentReport += (Get-ChildItem (Join-Path $env:ANDROID_HOME "ndk") -Directory |
        Select-Object -ExpandProperty Name | Out-String).Trim()
}
$EnvironmentReport | Set-Content (Join-Path $BackupDir "ENVIRONMENT.txt") -Encoding UTF8

Get-ChildItem $BackupDir -File -Recurse |
    Where-Object Name -ne "SHA256SUMS.txt" |
    Sort-Object FullName |
    ForEach-Object {
        $Hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
        $Relative = $_.FullName.Substring($BackupDir.Length + 1)
        "{0}  {1}" -f $Hash, $Relative
    } | Set-Content (Join-Path $BackupDir "SHA256SUMS.txt") -Encoding ASCII

Write-Host "BACKUP CREATED: $BackupDir"
Get-ChildItem $BackupDir -Recurse | Select-Object FullName, Length
```

Перед отключением носителя выполнить независимую проверку всех hashes. Этот блок запускается в
том же окне либо получает от ИИ фактический `$BackupDir`:

```powershell
Set-Location C:\APUMIR-arena-test

$Failures = 0
Get-Content (Join-Path $BackupDir "SHA256SUMS.txt") | ForEach-Object {
    if ($_ -match '^([0-9A-F]{64})  (.+)$') {
        $Expected = $Matches[1]
        $Relative = $Matches[2]
        $File = Join-Path $BackupDir $Relative
        $Actual = (Get-FileHash $File -Algorithm SHA256).Hash
        if ($Actual -ne $Expected) {
            Write-Host "HASH FAILED: $Relative" -ForegroundColor Red
            $Failures++
        }
    }
}
if ($Failures -ne 0) { throw "Повреждены файлы: $Failures" }

Write-Host "ALL BACKUP HASHES PASSED"
```

Только после `ALL BACKUP HASHES PASSED` копию считать готовой. Желательно безопасно извлечь
диск и один раз проверить чтение `MILESTONE.txt` на другом компьютере.

## 4. Восстановление на новом чистом Windows-ПК

### 4.1. Установить базовые инструменты

Нужны 64-bit Windows, Git, JDK 21, Android Studio/SDK, Rustup и PowerShell. Python нужен только
для тестовых MQTT-скриптов, а не для обычной сборки. Если доступен `winget`:

```powershell
Set-Location C:\

winget install --id Git.Git -e
winget install --id EclipseAdoptium.Temurin.21.JDK -e
winget install --id Google.AndroidStudio -e
winget install --id Rustlang.Rustup -e
winget install --id Python.Python.3.12 -e
```

Закрыть и заново открыть PowerShell. В Android Studio открыть **SDK Manager** и установить:

- Android SDK Platform 35;
- Android SDK Build-Tools;
- Android SDK Platform-Tools;
- Android SDK Command-line Tools (latest);
- NDK (Side by side), предпочтительно ту же версию, что записана в `ENVIRONMENT.txt`.

Проверить инструменты:

```powershell
Set-Location C:\

git --version
java -version
rustc --version
cargo --version
adb version
```

### 4.2. Настроить Android SDK/NDK и Rust

Обычный SDK-путь после Android Studio — `%LOCALAPPDATA%\Android\Sdk`. Если в
`ENVIRONMENT.txt` указана конкретная NDK-версия, подставить её вместо выбора последней.

```powershell
Set-Location C:\

$Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not (Test-Path $Sdk)) { throw "Android SDK не найден: $Sdk" }

$Ndk = Get-ChildItem (Join-Path $Sdk "ndk") -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
if ($null -eq $Ndk) { throw "Android NDK не установлен" }

[Environment]::SetEnvironmentVariable("ANDROID_HOME", $Sdk, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $Sdk, "User")
[Environment]::SetEnvironmentVariable("ANDROID_NDK_HOME", $Ndk.FullName, "User")

$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_NDK_HOME = $Ndk.FullName
$env:Path += ";$Sdk\platform-tools;$Sdk\cmdline-tools\latest\bin"

rustup default stable
rustup target add aarch64-linux-android
cargo install cargo-ndk --locked
```

Для тестовых MQTT-инъекций при необходимости:

```powershell
Set-Location C:\

py -3 -m pip install paho-mqtt
```

### 4.3. Восстановить исходники из внешнего носителя

Сначала прочитать `MILESTONE.txt` и подставить его branch/commit. Не использовать ZIP как
единственную исходную копию: основной способ — Git bundle.

```powershell
Set-Location C:\

$BackupDir = "E:\APU-backups\APU-v11.16.X-DATE-COMMIT" # заменить фактическим путём
$ExpectedBranch = "BRANCH_FROM_MILESTONE"
$ExpectedCommit = "FULL_COMMIT_FROM_MILESTONE"
$RestoreDir = "C:\APU-restored"

if (-not (Test-Path (Join-Path $BackupDir "APU-source.bundle"))) {
    throw "APU-source.bundle не найден"
}
if (Test-Path $RestoreDir) {
    throw "Папка уже существует: $RestoreDir"
}

git clone (Join-Path $BackupDir "APU-source.bundle") $RestoreDir
if ($LASTEXITCODE -ne 0) { throw "Не удалось восстановить Git bundle" }

Set-Location $RestoreDir
git checkout $ExpectedBranch
git reset --hard $ExpectedCommit

$ActualCommit = (git rev-parse HEAD).Trim()
if ($ActualCommit -ne $ExpectedCommit) {
    throw "Восстановлен неверный commit: $ActualCommit"
}

Write-Host "SOURCE RESTORE PASSED"
```

Создать локальную настройку SDK; этот файл не коммитится:

```powershell
Set-Location C:\APU-restored

$Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$SdkForGradle = $Sdk -replace '\\', '/'
"sdk.dir=$SdkForGradle" | Set-Content ".\android-app\local.properties" -Encoding ASCII
Get-Content ".\android-app\local.properties"
```

### 4.4. Собрать Rust и APK

Версию брать из `MILESTONE.txt`:

```powershell
Set-Location C:\APU-restored

.\build-rust.ps1
if ($LASTEXITCODE -ne 0) { throw "Rust Android build failed" }
```

```powershell
Set-Location C:\APU-restored\android-app

$env:GITHUB_REF_NAME = "v11.16.X" # заменить версией из MILESTONE.txt
.\gradlew.bat :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease
if ($LASTEXITCODE -ne 0) { throw "APK build failed" }

$Metadata = Get-Content ".\app\build\outputs\apk\release\output-metadata.json" -Raw |
    ConvertFrom-Json
$Metadata.elements | Select-Object versionName, versionCode, outputFile
Get-FileHash ".\app\build\outputs\apk\release\app-release.apk" -Algorithm SHA256
```

Предупреждение `Unable to strip libp2p_core.so` само по себе не является ошибкой. Успех —
`BUILD SUCCESSFUL`, ожидаемые `versionName/versionCode` и существующий APK.

Проект для разработки открывать в Android Studio через папку
`C:\APU-restored\android-app`. При первом открытии дождаться Gradle Sync.

### 4.5. Проверить сохранённый APK и установить на телефон

Сначала сверить **сохранённый, уже протестированный APK** с `MILESTONE.txt`/`SHA256SUMS.txt`.
Повторно собранный APK может иметь другой SHA из-за версий инструментов, поэтому его сравнивают
по source commit, версии и тесту, а не объявляют битым только из-за иного SHA.

```powershell
Set-Location C:\APU-restored

$SavedApk = "E:\APU-backups\APU-v11.16.X-DATE-COMMIT\artifacts\APU-v11.16.X.apk"
Get-FileHash $SavedApk -Algorithm SHA256
adb devices
```

После включения USB debugging и подтверждения телефона:

```powershell
Set-Location C:\APU-restored

$Serial = "PHONE_SERIAL"
$SavedApk = "E:\APU-backups\APU-v11.16.X-DATE-COMMIT\artifacts\APU-v11.16.X.apk"
adb -s $Serial install -r $SavedApk
adb -s $Serial shell dumpsys package com.vladimir.messenger |
    Select-String -Pattern "versionCode=|versionName="
```

`install -r` сохраняет существующие данные приложения. Не делать uninstall, если данные нужны.

## 5. Когда восстановление считается доказанным

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

### 5.1. Безопасное извлечение внешнего носителя

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

## 6. Известные ошибки PowerShell и безопасное продолжение

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

**Связанное правило parser compatibility:** raw stdout/stderr должны быть записаны до parsing.
Нельзя считать build неуспешным только потому, что новая версия native tool изменила английский
label, пробелы, регистр или separators digest. Для certificate/hash искать однозначную semantic
строку, брать значение после label, удалять separators (`[^0-9A-Fa-f]`) и требовать ровно 64 hex.
Exit 0 и общий marker всё равно недостаточны без exact identity hash. Если build, artifact и signer
verify уже завершились, parser failure исправлять read-only анализом сохранённого output/artifact;
не удалять evidence, не rebuild и не перезаписывать готовый APK. Тот же принцип применять к
`aapt`, `apksigner`, `dumpsys`, Cargo/Gradle markers и любым version-dependent CLI outputs.

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

### Source ZIP даёт raw Git blob mismatch на Windows

**Симптом:** ZIP проходит общий SHA-256 и воспроизводится новым `git archive`, но
`git hash-object --no-filters <extracted-file>` не равен `git rev-parse <commit>:<path>`.

**Причина:** Windows `git archive`/attributes могут выдать text с CRLF, тогда как blob в Git
нормализован с LF. Raw hash сравнивает разные представления одного текста и создаёт ложный fail.

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
