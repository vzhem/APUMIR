# ============================================================================
# lib-instrumented-tests.ps1 - dot-sourced helper, not meant to be run alone.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 no-BOM.
#
#   . (Join-Path $PSScriptRoot 'lib-instrumented-tests.ps1')
#
# WHY THIS EXISTS
# The first device runs used ":app:connectedDebugAndroidTest". That task wires
# uninstall tasks as finalizers: after the tests finish, Gradle removes BOTH the
# test APK and the application APK, and with the application goes its data
# directory. Evidence from the owner's console:
#   - on Anya's phone the package was present before the run (versionName=v11.16,
#     lastUpdateTime=2026-08-25 21:27:20) and "not installed" right after it;
#   - on Stas's phone messenger_database was 2572288 bytes and schema version 7
#     before the run, and the install that followed reported
#     firstInstallTime == lastUpdateTime == 2026-08-26 21:30:32, i.e. a fresh
#     install with a 4096 byte empty database;
#   - set-rank.ps1 got "run-as: unknown package: com.vladimir.messenger" in the
#     very next command, after its test had already passed.
# So the migration really was proven on Stas's 2.5 MB version 7 database, and
# then the database was deleted by the build tool, not by the migration.
#
# This helper instead builds both APKs, installs them with plain adb and runs
# the instrumentation with "adb shell am instrument". Nothing is uninstalled
# except the test package at the end, which does not touch application data.
# ============================================================================

$script:PackageName = 'com.vladimir.messenger'
$script:TestPackageName = 'com.vladimir.messenger.test'
$script:RunnerName = 'androidx.test.runner.AndroidJUnitRunner'

function Invoke-AdbRaw {
    param([string]$Serial, [string[]]$AdbArgs, [string]$AdbPath)
    $Previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $Lines = & $AdbPath -s $Serial @AdbArgs 2>&1 | ForEach-Object { "$_" }
    }
    finally {
        $ErrorActionPreference = $Previous
    }
    return ($Lines -join "`n")
}

# Builds and installs the application and the test APK, runs the selected
# instrumentation classes and parses the JUnit text output. "am instrument"
# exits 0 even when tests fail, so the verdict comes from the output, not from
# the exit code.
# Everything this function prints goes through Write-Host on purpose: a
# Write-Output inside a function lands in the same pipeline as its return value,
# so $Result = Invoke-InstrumentedTests ... would receive an array of strings
# plus the hashtable instead of the hashtable alone.
function Invoke-InstrumentedTests {
    param(
        [string]$Serial,
        [string]$AdbPath,
        [string]$AndroidRoot,
        [string]$Classes,
        [hashtable]$ExtraArgs = @{}
    )

    $Gradlew = Join-Path $AndroidRoot 'gradlew.bat'
    $AppApk = Join-Path $AndroidRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $TestApk = Join-Path $AndroidRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'

    Write-Host ''
    Write-Host '--- building both APKs (no connectedAndroidTest, it uninstalls the app) ---'
    Push-Location $AndroidRoot
    try {
        & $Gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
        $BuildExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    Write-Host "build exit code: $BuildExit"
    if ($BuildExit -ne 0) {
        return @{ Ok = $false; Reason = 'BUILD FAILED'; Tests = 0; Failures = 0; Output = '' }
    }
    foreach ($Apk in @($AppApk, $TestApk)) {
        if (-not (Test-Path -LiteralPath $Apk)) {
            return @{ Ok = $false; Reason = "APK not found: $Apk"; Tests = 0; Failures = 0; Output = '' }
        }
    }

    Write-Host ''
    Write-Host '--- installing application and test APK (data is preserved) ---'
    foreach ($Apk in @($AppApk, $TestApk)) {
        $Out = Invoke-AdbRaw $Serial @('install', '-r', '-t', '-d', $Apk) $AdbPath
        if ("$Out" -notmatch 'Success') {
            Write-Host "$Out"
            return @{
                Ok = $false; Reason = "install failed for $Apk"; Tests = 0; Failures = 0; Output = "$Out"
            }
        }
        Write-Host ("  installed " + (Split-Path $Apk -Leaf))
    }

    Write-Host ''
    Write-Host '--- running instrumentation ---'
    $InstrumentArgs = @('shell', 'am', 'instrument', '-w')
    foreach ($Key in $ExtraArgs.Keys) {
        $InstrumentArgs += @('-e', $Key, [string]$ExtraArgs[$Key])
    }
    $InstrumentArgs += @('-e', 'class', $Classes)
    $InstrumentArgs += ($script:TestPackageName + '/' + $script:RunnerName)
    Write-Host ("  classes: " + $Classes)
    $Output = Invoke-AdbRaw $Serial $InstrumentArgs $AdbPath
    Write-Host $Output

    $Tests = 0
    $Failures = 0
    $OkMatch = [regex]::Match("$Output", 'OK \((\d+) test')
    $RunMatch = [regex]::Match("$Output", 'Tests run:\s*(\d+),\s*Failures:\s*(\d+)')
    if ($RunMatch.Success) {
        $Tests = [int]$RunMatch.Groups[1].Value
        $Failures = [int]$RunMatch.Groups[2].Value
    } elseif ($OkMatch.Success) {
        $Tests = [int]$OkMatch.Groups[1].Value
    }

    $Reason = ''
    if ("$Output" -match 'INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT.*Error|Unable to find instrumentation') {
        $Reason = 'instrumentation did not start'
    } elseif ($Tests -eq 0) {
        $Reason = 'no test reported a result'
    } elseif ($Failures -ne 0 -or "$Output" -match 'FAILURES!!!') {
        $Reason = "$Failures test(s) failed"
    }

    # The test package is not part of the product; removing it does not touch
    # the application package or its data directory.
    Invoke-AdbRaw $Serial @('uninstall', $script:TestPackageName) $AdbPath | Out-Null
    Write-Host ("  test package removed: " + $script:TestPackageName)

    return @{
        Ok = ($Reason -eq ''); Reason = $Reason; Tests = $Tests; Failures = $Failures; Output = "$Output"
    }
}
