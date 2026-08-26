param(
    [string]$ExpectedCommit = '',
    [string]$DeviceSerial = 'AUYF6R5923006121',
    [switch]$SkipBackup
)

# ============================================================================
# groups-phone-check.ps1 - verify migration 7 -> 8 and the Groups UI on ONE
# named phone. ASCII only on purpose: PowerShell 5.1 misreads UTF-8 no-BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\groups-phone-check.ps1 -ExpectedCommit <hash>
#
# Default device is Anya (MTN NX1). Pass -DeviceSerial to use another one.
#
# Why the backup step exists and why it is verified:
# the second instrumented test opens the phone's real messenger_database
# through Room, so the migration really runs on real chats, and launching the
# app afterwards does the same again. AppModule uses
# fallbackToDestructiveMigration(), so a broken migration would wipe existing
# chats. "adb shell run-as" only works for a debuggable build, so the script
# proves the copy really landed on the PC and stops if it did not.
# Note the test build does NOT use fallbackToDestructiveMigration: on a wrong
# migration it throws instead of recreating the database.
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'
$AndroidRoot = Join-Path $RepoRoot 'android-app'
$Gradlew = Join-Path $AndroidRoot 'gradlew.bat'
$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$Package = 'com.vladimir.messenger'
$ApkPath = Join-Path $AndroidRoot 'app\build\outputs\apk\debug\app-debug.apk'
$BackupDir = Join-Path $env:TEMP ('apu-groups-db-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$DbNames = @('messenger_database', 'messenger_database-wal', 'messenger_database-shm')

if (-not (Test-Path -LiteralPath $Adb)) {
    Write-Output "FATAL: adb not found at $Adb"
    exit 1
}

# adb writes progress and warnings to stderr. Under ErrorActionPreference=Stop
# a redirected native stderr can abort the script on PowerShell 5.1, so every
# adb call goes through this helper with a local Continue preference.
function Invoke-Adb {
    param([string[]]$AdbArgs)
    $Previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $Lines = & $Adb -s $DeviceSerial @AdbArgs 2>&1 | ForEach-Object { "$_" }
    }
    finally {
        $ErrorActionPreference = $Previous
    }
    return ($Lines -join "`n")
}

# ---- repo state -------------------------------------------------------------
Push-Location $RepoRoot
try {
    $Head = (& git rev-parse HEAD | Out-String).Trim()
    $Branch = (& git rev-parse --abbrev-ref HEAD | Out-String).Trim()
    Write-Output "Repo HEAD:   $Head"
    Write-Output "Repo branch: $Branch"
    if ($ExpectedCommit -ne '' -and $Head -ne $ExpectedCommit) {
        Write-Output "FATAL: HEAD $Head does not match $ExpectedCommit - wrong code would be tested."
        exit 1
    }
    if ($ExpectedCommit -eq '') {
        Write-Output 'NOTE: -ExpectedCommit was not passed, HEAD is not pinned.'
    }
}
finally {
    Pop-Location
}

# ---- exactly one phone, the named one --------------------------------------
Write-Output ''
Write-Output '===== connected devices ====='
& $Adb devices
$Present = (& $Adb devices | Select-String -Pattern $DeviceSerial -SimpleMatch)
if (-not $Present) {
    Write-Output ''
    Write-Output "FATAL: phone $DeviceSerial is not connected. Nothing was installed."
    Write-Output 'Connect it and rerun, or pass -DeviceSerial <serial>.'
    exit 1
}
Write-Output "Target phone: $DeviceSerial"

# Pin Gradle to this device so a second connected phone is never touched.
$env:ANDROID_SERIAL = $DeviceSerial

# ---- what is installed right now -------------------------------------------
Write-Output ''
Write-Output '===== installed build before the test ====='
$Dump = Invoke-Adb @('shell', "dumpsys package $Package")
$DumpText = "$Dump"
if ($DumpText -notmatch 'versionName=') {
    Write-Output "The app is NOT installed on this phone, there is no database to protect."
    $IsInstalled = $false
} else {
    $IsInstalled = $true
    $Dump -split "`n" | Select-String -Pattern 'versionName|lastUpdateTime|firstInstallTime|lags=\[' |
        ForEach-Object { $_.ToString().Trim() }
}

$IsDebuggable = $false
if ($IsInstalled) {
    foreach ($Line in ($Dump -split "`n")) {
        if ($Line -match 'lags=\[' -and $Line -match 'DEBUGGABLE') { $IsDebuggable = $true }
    }
}

# ---- back up the real database ---------------------------------------------
$BackedUpMain = $false
$MainDbOnPhone = $false

if ($SkipBackup) {
    Write-Output ''
    Write-Output 'WARNING: -SkipBackup was passed. The phone database is NOT protected.'
    Write-Output 'If the migration is wrong, existing chats on this phone are lost.'
} elseif (-not $IsInstalled) {
    Write-Output ''
    Write-Output 'No backup needed: no installed database on this phone.'
} elseif (-not $IsDebuggable) {
    Write-Output ''
    Write-Output 'RESULT: STOPPED BEFORE TOUCHING THE PHONE.'
    Write-Output 'The installed build is not debuggable, so "run-as" cannot read its data'
    Write-Output 'directory and no backup is possible. A release-signed build also cannot'
    Write-Output 'be replaced by this debug APK, so the test would fail anyway.'
    Write-Output 'Either install a debug build first, or rerun with -SkipBackup knowing'
    Write-Output 'that the chats on this phone are then unprotected.'
    exit 1
} else {
    Write-Output ''
    Write-Output "===== backing up the phone database to $BackupDir ====="
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    # Flush the WAL into the main file before copying.
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Seconds 1
    $DbList = Invoke-Adb @('shell', "run-as $Package ls -l databases")
    Write-Output 'Device databases directory:'
    $DbList -split "`n" | ForEach-Object { Write-Output ("  " + $_.Trim()) }
    $MainDbOnPhone = ($DbList -match 'messenger_database')

    foreach ($Name in $DbNames) {
        $Remote = '/sdcard/apu-backup-' + $Name
        $Local = Join-Path $BackupDir $Name
        $CopyOut = Invoke-Adb @('shell', "run-as $Package cp databases/$Name $Remote")
        Invoke-Adb @('pull', $Remote, $Local) | Out-Null
        Invoke-Adb @('shell', 'rm', $Remote) | Out-Null
        if ((Test-Path -LiteralPath $Local) -and ((Get-Item -LiteralPath $Local).Length -gt 0)) {
            $Size = (Get-Item -LiteralPath $Local).Length
            Write-Output "  saved $Name ($Size bytes)"
            if ($Name -eq 'messenger_database') { $BackedUpMain = $true }
        } else {
            Write-Output "  not copied: $Name"
            if ("$CopyOut" -ne '') { Write-Output ("    device said: " + ("$CopyOut" -replace "`n", ' ')) }
        }
    }

    if ($MainDbOnPhone -and -not $BackedUpMain) {
        Write-Output ''
        Write-Output 'RESULT: STOPPED - messenger_database exists on the phone but the'
        Write-Output 'backup did not land on the PC. Running the test now would risk the'
        Write-Output 'existing chats with no way back. Fix the copy first, or rerun with'
        Write-Output '-SkipBackup if losing them is acceptable.'
        exit 1
    }
    if (-not $MainDbOnPhone) {
        Write-Output 'NOTE: messenger_database was not found on the device.'
    }
    Write-Output 'Restore with, if ever needed:'
    Write-Output "  adb -s $DeviceSerial push <file> /sdcard/x"
    Write-Output "  adb -s $DeviceSerial shell `"run-as $Package cp /sdcard/x databases/<file>`""
}

# ---- step 1: instrumented migration tests ----------------------------------
# Two tests, in this order:
#   GroupsMigrationInstrumentedTest          - the migration SQL on a scratch
#                                              database, nothing real is touched
#   GroupsProductionMigrationInstrumentedTest - Room itself opens the phone's
#                                              messenger_database, runs the
#                                              migration, validates the schema
#                                              and proves no legacy row changed
Write-Output ''
Write-Output '===== step 1: migration 7 -> 8 instrumented tests ====='
$Classes = 'com.vladimir.messenger.data.local.GroupsMigrationInstrumentedTest,' +
    'com.vladimir.messenger.data.local.GroupsProductionMigrationInstrumentedTest'
$Arg = '-Pandroid.testInstrumentationRunnerArguments.class=' + $Classes
Push-Location $AndroidRoot
try {
    & $Gradlew --console=plain :app:connectedDebugAndroidTest $Arg
    $TestExit = $LASTEXITCODE
}
finally {
    Pop-Location
}
Write-Output "migration test exit code: $TestExit"

# A zero exit code alone does not prove the tests ran: read the real counters.
$AndroidTestResults = Join-Path $AndroidRoot 'app\build\outputs\androidTest-results\connected'
$DevTests = 0
$DevFailures = 0
if (Test-Path -LiteralPath $AndroidTestResults) {
    foreach ($File in Get-ChildItem -LiteralPath $AndroidTestResults -Recurse -Filter 'TEST-*.xml') {
        $Doc = [xml](Get-Content -LiteralPath $File.FullName)
        $Suite = $Doc.testsuite
        $DevTests += [int]$Suite.tests
        $DevFailures += [int]$Suite.failures + [int]$Suite.errors
        Write-Output (
            "  {0}: tests={1} failures={2} errors={3} skipped={4}" -f
            $Suite.name, $Suite.tests, $Suite.failures, $Suite.errors, $Suite.skipped
        )
    }
} else {
    Write-Output "WARNING: no androidTest reports found in $AndroidTestResults"
}
Write-Output "device test totals: tests=$DevTests failures=$DevFailures"

if ($DevTests -eq 0 -or $DevFailures -ne 0 -or $TestExit -ne 0) {
    Write-Output ''
    Write-Output 'RESULT: MIGRATION TESTS DID NOT PASS.'
    Write-Output 'Do NOT open the app on this phone until the migration is fixed.'
    Write-Output "Database backup (if taken): $BackupDir"
    exit 1
}
Write-Output 'RESULT: migration 7 -> 8 passed on a real device, Room schema accepted.'

# ---- step 2: install the app and prove which build landed ------------------
Write-Output ''
Write-Output '===== step 2: install debug APK ====='
if (-not (Test-Path -LiteralPath $ApkPath)) {
    Write-Output "FATAL: APK not found at $ApkPath - run groups-build-gate.ps1 first."
    exit 1
}
& $Adb -s $DeviceSerial install -r -t -d $ApkPath
$InstallExit = $LASTEXITCODE
Write-Output "install exit code: $InstallExit"
if ($InstallExit -ne 0) {
    Write-Output 'RESULT: INSTALL FAILED. A release-signed build cannot be replaced by a debug one.'
    Write-Output "Database backup (if taken): $BackupDir"
    exit $InstallExit
}

Write-Output ''
Write-Output '===== installed build freshness (dumpsys, not logcat) ====='
Invoke-Adb @('shell', "dumpsys package $Package") -split "`n" |
    Select-String -Pattern 'versionName|lastUpdateTime|firstInstallTime' |
    ForEach-Object { $_.ToString().Trim() }

# ---- step 3: launch, this is where the real migration runs -----------------
Write-Output ''
Write-Output '===== step 3: launch the app (real migration 7 -> 8 happens here) ====='
Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
Invoke-Adb @('shell', 'logcat', '-c') | Out-Null
Invoke-Adb @('shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
Start-Sleep -Seconds 8
Write-Output 'App launched. Crash or migration problems would appear below:'
$Log = Invoke-Adb @('logcat', '-d', '-v', 'time')
$Hits = $Log -split "`n" |
    Select-String -Pattern 'AndroidRuntime|IllegalStateException|Migration didn|destructive|GroupR'
if ($Hits) {
    $Hits | Select-Object -Last 30 | ForEach-Object { $_.ToString() }
} else {
    Write-Output '  no crash or migration error lines in logcat'
}

Write-Output ''
Write-Output '===== database size after the migration ====='
if ($IsDebuggable -or -not $IsInstalled) {
    Invoke-Adb @('shell', "run-as $Package ls -l databases") -split "`n" |
        Select-String -Pattern 'messenger_database' |
        ForEach-Object { $_.ToString().Trim() }
}

Write-Output ''
Write-Output 'RESULT: PHONE CHECK DONE.'
Write-Output "Database backup: $BackupDir"
Write-Output ''
Write-Output 'MANUAL CHECKLIST on the phone (menu of the chat list -> Gruppy):'
Write-Output '  1. the app opened and the old chats are still there (data preservation)'
Write-Output '  2. create a group: title, "public" switch, "topics" switch'
Write-Output '  3. in the group: the General topic is present'
Write-Output '  4. create a second topic, send a message, watch the counter on the topic chip'
Write-Output '  5. open the gear icon: tabs Overview / Members / Requests / Links / Stats / Permissions'
Write-Output '  6. Links tab: the invite text and a QR image are side by side'
Write-Output '  7. Members tab: the search field filters by name or node id'
Write-Output '  8. pin a message, confirm the pinned block appears at the top'
Write-Output '  9. Stats tab: member, admin, topic and per-day message counts are non-empty'
