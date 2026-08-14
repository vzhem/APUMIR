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

## 6. Известные ошибки PowerShell и безопасное продолжение

### `else` не распознан как команда

**Симптом:** `else : Имя "else" не распознано...` после того, как пользователь отдельно вставил
и выполнил `if { ... }`, а затем отдельной вставкой — `else { ... }`.

**Причина:** интерактивный Windows PowerShell уже завершил синтаксическую конструкцию `if`.
`else` должен попасть в тот же parse/paste-блок.

**Обход:** давать и вставлять `if { ... } else { ... }` одним блоком или не использовать
отдельный `else`. Если сам `if` уже успешно показал нужный результат, отдельная ошибка `else`
не отменяет этот результат. Не повторять опасные/изменяющие команды только ради удаления текста
ошибки из консоли.

### `NativeCommandError` на успешном `java -version`

**Симптом:** при `$ErrorActionPreference = "Stop"` backup прекращается на `java -version`, хотя
Java исправна и выводит нормальную версию.

**Причина:** Java штатно пишет version text в stderr; Windows PowerShell 5 может превратить
stderr native-программы в terminating `NativeCommandError`.

**Безопасный обход:** не отключать строгую проверку всего backup. Для environment capture
направлять stderr в stdout внутри `cmd.exe`, отдельно записывать exit code:

```powershell
Set-Location C:\APUMIR-arena-test

$Output = cmd.exe /d /c "java -version 2>&1" | Out-String
$ExitCode = $LASTEXITCODE
Write-Host $Output.Trim()
Write-Host ("Exit code: {0}" -f $ExitCode)
```

Тот же шаблон применять к `git`, `rustc`, `cargo`, `cargo ndk`, `adb` и `gradlew` при сборе
`ENVIRONMENT.txt`. Не считать команду успешной только по тексту: сохранять и exit code.

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
