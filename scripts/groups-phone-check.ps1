param(
    [string]$ExpectedCommit = '',
    [string]$DeviceSerial = 'AUYF6R5923006121',
    [switch]$SkipBackup,
    [string]$RestoreFrom = ''
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
# the acceptance test opens the phone's real messenger_database through Room,
# so the migration really runs on real chats, and launching the app afterwards
# does the same again. AppModule uses fallbackToDestructiveMigration(), so a
# broken migration would wipe existing chats. The script therefore proves the
# copy really landed on the PC and stops if it did not.
#
# How the copy is made: "run-as <pkg> cp ... /sdcard/..." does NOT work - under
# scoped storage an app cannot write to the root of external storage, adb
# answers "Permission denied". Instead the file is streamed straight to the PC
# with "adb exec-out run-as <pkg> cat databases/<file>". exec-out is required,
# plain "adb shell" would corrupt the binary with CRLF translation. The stream
# goes through Start-Process -RedirectStandardOutput: PowerShell's own ">"
# operator re-encodes native output as text and would destroy the file.
# The copy is then checked against the SQLite header and the device file size.
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

# adb writes progress and warnings to stderr. Under ErrorActionPreference=Stop a
# redirected native stderr can abort the script on PowerShell 5.1, so every text
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

# Streams a file from the device to the PC byte for byte.
function Start-AdbToFile {
    param([string[]]$AdbArgs, [string]$OutFile)
    if (Test-Path -LiteralPath $OutFile) { Remove-Item -LiteralPath $OutFile -Force }
    $All = @('-s', $DeviceSerial) + $AdbArgs
    $Proc = Start-Process -FilePath $Adb -ArgumentList $All `
        -RedirectStandardOutput $OutFile -NoNewWindow -Wait -PassThru
    return $Proc.ExitCode
}

# Writes a local file into the app private directory byte for byte.
# adb shell passes host to device input unchanged, and StandardInput.BaseStream
# is the raw byte stream: PowerShell has no operator that can pipe a file into
# the stdin of a native command without re-encoding it as text.
function Send-FileToDevice {
    param([string]$LocalFile, [string]$RemoteName)
    $Psi = New-Object System.Diagnostics.ProcessStartInfo
    $Psi.FileName = $Adb
    # Single quotes survive both the .NET command line parser and adb, which
    # rejoins its arguments with spaces, so the device shell is the one that
    # strips them and hands "cat > databases/x" to sh -c. A bare > would be
    # taken by the device shell running as the shell user instead.
    $Psi.Arguments = "-s $DeviceSerial shell run-as $Package sh -c 'cat > databases/$RemoteName'"
    $Psi.RedirectStandardInput = $true
    $Psi.RedirectStandardError = $true
    $Psi.UseShellExecute = $false
    $Proc = [System.Diagnostics.Process]::Start($Psi)
    $Bytes = [System.IO.File]::ReadAllBytes($LocalFile)
    $Proc.StandardInput.BaseStream.Write($Bytes, 0, $Bytes.Length)
    $Proc.StandardInput.Close()
    $StdErr = $Proc.StandardError.ReadToEnd()
    $Proc.WaitForExit()
    return "$StdErr"
}

# ---- restore mode: put a previous backup back on the phone ------------------
if ($RestoreFrom -ne '') {
    Write-Output ''
    Write-Output "===== RESTORE MODE: $RestoreFrom -> $DeviceSerial ====="
    if (-not (Test-Path -LiteralPath $RestoreFrom)) {
        Write-Output "FATAL: no such folder: $RestoreFrom"
        exit 1
    }
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Seconds 1
    foreach ($Name in $DbNames) {
        $Local = Join-Path $RestoreFrom $Name
        if (-not (Test-Path -LiteralPath $Local)) {
            Write-Output "  skipped $Name (not in the backup folder)"
            continue
        }
        $Size = (Get-Item -LiteralPath $Local).Length
        $Err = Send-FileToDevice $Local $Name
        $Check = Invoke-Adb @('shell', "run-as $Package ls -l databases/$Name")
        Write-Output "  wrote $Name ($Size bytes), device now says:"
        Write-Output ('    ' + $Check.Trim())
        if ("$Err" -ne '') { Write-Output ('    stderr: ' + ("$Err" -replace "`n", ' ')) }
    }
    Write-Output 'Restore finished. Open the app and check that the old chats are there.'
    exit 0
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
    Write-Output 'The app is NOT installed on this phone, there is no database to protect.'
    $IsInstalled = $false
} else {
    $IsInstalled = $true
    $Dump -split "`n" | Select-String -Pattern 'versionName|lastUpdateTime|firstInstallTime|pkgFlags=' |
        ForEach-Object { $_.ToString().Trim() }
}

$IsDebuggable = $false
if ($IsInstalled) {
    foreach ($Line in ($Dump -split "`n")) {
        if ($Line -match 'pkgFlags=\[' -and $Line -match 'DEBUGGABLE') { $IsDebuggable = $true }
    }
}

# ---- back up the real database ---------------------------------------------
$BackedUpMain = $false
$MainDbOnPhone = $false
$DbVersionBefore = 0

if ($SkipBackup) {
    Write-Output ''
    Write-Output 'WARNING: -SkipBackup was passed. The phone database is NOT protected,'
    Write-Output 'and its version cannot be read, so only the scratch-database test will run.'
} elseif (-not $IsInstalled) {
    Write-Output ''
    Write-Output 'No backup needed: no installed database on this phone.'
} elseif (-not $IsDebuggable) {
    Write-Output ''
    Write-Output 'RESULT: STOPPED BEFORE TOUCHING THE PHONE.'
    Write-Output 'The installed build is not debuggable, so "run-as" cannot read its data'
    Write-Output 'directory and no backup is possible. A release-signed build also cannot'
    Write-Output 'be replaced by this debug APK, so the test would fail anyway.'
    exit 1
} else {
    Write-Output ''
    Write-Output "===== backing up the phone database to $BackupDir ====="
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    # Flush what we can before copying: a stopped app closes its database.
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Seconds 1
    $DbList = Invoke-Adb @('shell', "run-as $Package ls -l databases")
    Write-Output 'Device databases directory:'
    $DbList -split "`n" | ForEach-Object { Write-Output ('  ' + $_.Trim()) }

    $DeviceSizes = @{}
    foreach ($Line in ($DbList -split "`n")) {
        $Fields = ($Line.Trim() -split '\s+')
        if ($Fields.Count -ge 5) {
            $NameField = $Fields[$Fields.Count - 1]
            if ($NameField -match '^messenger_database' -and $Fields[4] -match '^\d+$') {
                $DeviceSizes[$NameField] = [int64]$Fields[4]
            }
        }
    }
    $MainDbOnPhone = $DeviceSizes.ContainsKey('messenger_database')

    foreach ($Name in $DbNames) {
        $Local = Join-Path $BackupDir $Name
        Start-AdbToFile @('exec-out', 'run-as', $Package, 'cat', "databases/$Name") $Local | Out-Null
        if (-not (Test-Path -LiteralPath $Local)) {
            Write-Output "  not copied: $Name (no file on the PC)"
            continue
        }
        $Size = (Get-Item -LiteralPath $Local).Length
        if ($Size -le 0) {
            Write-Output "  not copied: $Name (empty file)"
            continue
        }
        $Note = ''
        if ($DeviceSizes.ContainsKey($Name)) {
            if ($DeviceSizes[$Name] -ne $Size) {
                $Note = ' SIZE MISMATCH, device has ' + $DeviceSizes[$Name]
            } else {
                $Note = ' size matches the device'
            }
        }
        Write-Output "  saved $Name ($Size bytes)$Note"
        if ($Name -eq 'messenger_database') { $BackedUpMain = $true }
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

    # Read the schema version straight out of the SQLite header (offset 60,
    # big endian; verified against a real SQLite file). Caveat: in WAL mode the
    # newest header can sit in the -wal file, so this read may report an older
    # version than SQLite itself would. Every branch below is safe either way:
    # reading 8 really means 8, and reading 7 is re-checked authoritatively by
    # the instrumented test, which asks SQLiteOpenHelper.
    if ($BackedUpMain) {
        $LocalMain = Join-Path $BackupDir 'messenger_database'
        $Header = Get-Content -LiteralPath $LocalMain -Encoding Byte -TotalCount 100
        $Magic = [System.Text.Encoding]::ASCII.GetString($Header, 0, 15)
        if ($Magic -ne 'SQLite format 3') {
            Write-Output ''
            Write-Output "RESULT: STOPPED - the copied file is not a SQLite database (header '$Magic')."
            Write-Output 'The transfer corrupted it, so there is no usable backup.'
            exit 1
        }
        $DbVersionBefore = ([int]$Header[60] * 16777216) + ([int]$Header[61] * 65536) +
            ([int]$Header[62] * 256) + [int]$Header[63]
        Write-Output "Copied file is a valid SQLite database, schema version: $DbVersionBefore"
        if ($DbVersionBefore -ge 8) {
            Write-Output ''
            Write-Output 'RESULT: STOPPED - this phone is already at schema version 8, the'
            Write-Output 'migration already ran here. To test it again, restore the backup'
            Write-Output 'from this folder or use a phone that is still on version 7.'
            exit 1
        }
    }

    Write-Output 'Restore with, if ever needed (this script, restore mode):'
    Write-Output "  powershell -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath"
    Write-Output "    -DeviceSerial $DeviceSerial -RestoreFrom $BackupDir"
}

# ---- step 1: instrumented migration tests ----------------------------------
# GroupsMigrationInstrumentedTest           - the migration SQL on a scratch
#                                             database, nothing real is touched
# GroupsProductionMigrationInstrumentedTest - Room itself opens the phone's
#                                             messenger_database, runs the
#                                             migration, validates the schema
#                                             and proves no legacy row changed.
#                                             It needs a version 7 database.
Write-Output ''
Write-Output '===== step 1: migration 7 -> 8 instrumented tests ====='
$ProductionClass = 'com.vladimir.messenger.data.local.GroupsProductionMigrationInstrumentedTest'
$Classes = 'com.vladimir.messenger.data.local.GroupsMigrationInstrumentedTest'
$ProductionRan = $false
if ($DbVersionBefore -eq 7) {
    $Classes = $Classes + ',' + $ProductionClass
    $ProductionRan = $true
} elseif ($DbVersionBefore -gt 0) {
    Write-Output "NOTE: the phone database is at version $DbVersionBefore, not 7."
    Write-Output 'Only the scratch-database test will run. The Room acceptance test needs'
    Write-Output 'a version 7 database; report this version and the missing migrations'
    Write-Output 'will be added to its chain.'
} else {
    Write-Output 'NOTE: the phone database version is unknown (no backup), so only the'
    Write-Output 'scratch-database test will run.'
}
$Arg = '-Pandroid.testInstrumentationRunnerArguments.class=' + $Classes
Write-Output "classes: $Classes"
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
            '  {0}: tests={1} failures={2} errors={3} skipped={4}' -f
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
if ($ProductionRan) {
    Write-Output 'RESULT: migration 7 -> 8 passed on a real device, Room schema accepted.'
} else {
    Write-Output 'RESULT: the migration SQL passed on a scratch database only.'
    Write-Output 'Room schema validation on the real database was NOT performed.'
}

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
Write-Output '===== step 3: launch the app (real migration happens here) ====='
Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
Invoke-Adb @('shell', 'logcat', '-c') | Out-Null
Invoke-Adb @('shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
Start-Sleep -Seconds 8
Write-Output 'App launched. Only matching logcat lines are shown below, so an empty'
Write-Output 'list means no crash and no migration error was logged:'
$Log = Invoke-Adb @('logcat', '-d', '-v', 'time')
$Hits = $Log -split "`n" |
    Select-String -Pattern 'AndroidRuntime|IllegalStateException|Migration didn|destructive|GroupR'
if ($Hits) {
    $Hits | Select-Object -Last 30 | ForEach-Object { $_.ToString() }
} else {
    Write-Output '  (no matching lines)'
}

# Independent proof from outside the app: stop it so the WAL is checkpointed,
# then read the schema version out of the copied header again.
Write-Output ''
Write-Output '===== database after the launch ====='
if ($IsDebuggable -or -not $IsInstalled) {
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb @('shell', "run-as $Package ls -l databases") -split "`n" |
        Select-String -Pattern 'messenger_database' |
        ForEach-Object { $_.ToString().Trim() }
    $AfterDir = Join-Path $BackupDir 'after'
    New-Item -ItemType Directory -Force -Path $AfterDir | Out-Null
    $AfterFile = Join-Path $AfterDir 'messenger_database'
    Start-AdbToFile @('exec-out', 'run-as', $Package, 'cat', 'databases/messenger_database') $AfterFile | Out-Null
    if ((Test-Path -LiteralPath $AfterFile) -and ((Get-Item -LiteralPath $AfterFile).Length -gt 0)) {
        $AfterHeader = Get-Content -LiteralPath $AfterFile -Encoding Byte -TotalCount 100
        $AfterMagic = [System.Text.Encoding]::ASCII.GetString($AfterHeader, 0, 15)
        if ($AfterMagic -eq 'SQLite format 3') {
            $DbVersionAfter = ([int]$AfterHeader[60] * 16777216) + ([int]$AfterHeader[61] * 65536) +
                ([int]$AfterHeader[62] * 256) + [int]$AfterHeader[63]
            Write-Output "schema version after the launch: $DbVersionAfter (before: $DbVersionBefore)"
            if ($DbVersionAfter -eq 8) {
                Write-Output 'RESULT: the app itself migrated the real database to version 8.'
            } else {
                Write-Output "RESULT: PROBLEM - the database is still at version $DbVersionAfter."
                Write-Output 'The app did not complete the migration. Do not use it, restore the backup.'
                exit 1
            }
        } else {
            Write-Output 'WARNING: the file copied after the launch is not a SQLite database.'
        }
    } else {
        Write-Output 'WARNING: could not copy the database back after the launch.'
    }
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
