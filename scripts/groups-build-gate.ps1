param(
    [string]$ExpectedCommit = '',
    [switch]$RunMigrationTest
)

# ============================================================================
# groups-build-gate.ps1 - compile and unit-test gate for the Groups section.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
#
# Run:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\groups-build-gate.ps1
# Optional: pin the expected commit so a failed git pull cannot be mistaken
# for a green run (a known trap recorded in docs/AI_HANDOFF.md).
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'
$AndroidRoot = Join-Path $RepoRoot 'android-app'
$Gradlew = Join-Path $AndroidRoot 'gradlew.bat'
$AdbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

if (-not (Test-Path -LiteralPath $Gradlew)) {
    Write-Output "FATAL: gradlew.bat not found at $Gradlew"
    exit 1
}

Push-Location $RepoRoot
try {
    Write-Output "===== repo state in $RepoRoot ====="
    $Head = (& git rev-parse HEAD | Out-String).Trim()
    $Branch = (& git rev-parse --abbrev-ref HEAD | Out-String).Trim()
    Write-Output "Repo HEAD:   $Head"
    Write-Output "Repo branch: $Branch"

    if ($Head -eq '' -or $Head.Length -lt 7) {
        Write-Output 'FATAL: git rev-parse returned nothing. Is git on PATH and is this a repo?'
        exit 1
    }

    $Dirty = (& git status --porcelain | Out-String).Trim()
    if ($Dirty -ne '') {
        Write-Output 'WARNING: the working tree is not clean:'
        Write-Output $Dirty
        Write-Output 'Uncommitted changes will take part in the build.'
    }

    if ($ExpectedCommit -ne '' -and $Head -ne $ExpectedCommit) {
        Write-Output ''
        Write-Output "FATAL: HEAD $Head does not match the expected commit $ExpectedCommit."
        Write-Output 'The build would test different code. Fix the checkout first:'
        Write-Output '  cd C:\APU-M8'
        Write-Output '  git fetch origin'
        Write-Output '  git checkout arena/01a03c3d-apumir'
        Write-Output '  git log --oneline -1'
        Write-Output 'A failed git pull is a known trap (docs/AI_HANDOFF.md), so this gate'
        Write-Output 'refuses to continue on a wrong HEAD instead of reporting a fake pass.'
        exit 1
    }
    if ($ExpectedCommit -eq '') {
        Write-Output 'NOTE: -ExpectedCommit was not passed, HEAD is not pinned.'
    }
}
finally {
    Pop-Location
}

Push-Location $AndroidRoot
try {
    # Step 1: JVM unit tests for the Groups logic only.
    # Deliberately not mixed with assemble in the same --tests invocation
    # (rule from docs/AI_HANDOFF.md).
    Write-Output ''
    Write-Output '===== step 1: groups unit tests ====='
    & $Gradlew --console=plain :app:testDebugUnitTest `
        --tests 'com.vladimir.messenger.data.group.*'
    $TestExit = $LASTEXITCODE
    Write-Output "unit tests exit code: $TestExit"
    if ($TestExit -ne 0) {
        Write-Output 'RESULT: UNIT TESTS FAILED - stop here, do not build.'
        exit $TestExit
    }

    # A zero exit code alone does not prove the tests ran: report the real
    # counters from the JUnit XML so "0 tests matched" cannot look like a pass.
    $ReportDir = Join-Path $AndroidRoot 'app\build\test-results\testDebugUnitTest'
    $TotalTests = 0
    $TotalFailures = 0
    $TotalSkipped = 0
    if (Test-Path -LiteralPath $ReportDir) {
        foreach ($File in Get-ChildItem -LiteralPath $ReportDir -Filter 'TEST-*.xml') {
            $Doc = [xml](Get-Content -LiteralPath $File.FullName)
            $Suite = $Doc.testsuite
            $TotalTests += [int]$Suite.tests
            $TotalFailures += [int]$Suite.failures + [int]$Suite.errors
            $TotalSkipped += [int]$Suite.skipped
            Write-Output ("  {0}: tests={1} failures={2} errors={3} skipped={4}" -f `
                $Suite.name, $Suite.tests, $Suite.failures, $Suite.errors, $Suite.skipped)
        }
    } else {
        Write-Output "WARNING: no test reports found in $ReportDir"
    }
    Write-Output ("unit test totals: tests=$TotalTests failures=$TotalFailures skipped=$TotalSkipped")
    if ($TotalTests -eq 0) {
        Write-Output 'RESULT: NO TESTS RAN - the --tests filter matched nothing.'
        Write-Output 'Treat this as a failure, not as a green run.'
        exit 1
    }
    if ($TotalFailures -ne 0) {
        Write-Output 'RESULT: UNIT TESTS REPORTED FAILURES'
        exit 1
    }

    # Step 2: main source compilation, including Room schema validation and
    # Hilt graph generation for GroupsModule.
    Write-Output ''
    Write-Output '===== step 2: compileDebugKotlin ====='
    & $Gradlew --console=plain :app:compileDebugKotlin
    $CompileExit = $LASTEXITCODE
    Write-Output "compile exit code: $CompileExit"
    if ($CompileExit -ne 0) {
        Write-Output 'RESULT: COMPILE FAILED'
        exit $CompileExit
    }

    # Step 3: debug APK. Room migration 7->8 is only exercised at runtime,
    # so the APK is the artifact to install over an existing v7 database.
    Write-Output ''
    Write-Output '===== step 3: assembleDebug ====='
    & $Gradlew --console=plain :app:assembleDebug
    $AssembleExit = $LASTEXITCODE
    Write-Output "assemble exit code: $AssembleExit"
    if ($AssembleExit -ne 0) {
        Write-Output 'RESULT: ASSEMBLE FAILED'
        exit $AssembleExit
    }

    # Step 4: the androidTest sources, which contain the instrumented
    # migration test. This compiles on the host and needs no phone, so a bad
    # import or a wrong Room API is caught here instead of after the phone is
    # plugged in and connectedDebugAndroidTest is already running.
    Write-Output ''
    Write-Output '===== step 4: compileDebugAndroidTestKotlin ====='
    & $Gradlew --console=plain :app:compileDebugAndroidTestKotlin
    $AndroidTestExit = $LASTEXITCODE
    Write-Output "androidTest compile exit code: $AndroidTestExit"
    if ($AndroidTestExit -ne 0) {
        Write-Output 'RESULT: ANDROIDTEST COMPILE FAILED - the phone check would fail too.'
        exit $AndroidTestExit
    }

    # Optional: exercise migration 7 -> 8 on a real device database.
    # Deliberately behind a switch - it installs a test APK and needs a phone,
    # and phones are only touched with the owner's explicit go-ahead.
    if ($RunMigrationTest) {
        Write-Output ''
        Write-Output '===== step 5: migration 7 -> 8 on a connected phone ====='
        Write-Output 'NOTE: the second test opens the real messenger_database on that phone.'
        Write-Output 'Prefer scripts\groups-phone-check.ps1, which backs the database up first.'
        $MigrationClasses = 'com.vladimir.messenger.data.local.GroupsMigrationInstrumentedTest,' +
            'com.vladimir.messenger.data.local.GroupsProductionMigrationInstrumentedTest'
        $RunnerArg = '-Pandroid.testInstrumentationRunnerArguments.class=' + $MigrationClasses
        & $AdbPath devices
        & $Gradlew --console=plain :app:connectedDebugAndroidTest $RunnerArg
        $MigrationExit = $LASTEXITCODE
        Write-Output "migration test exit code: $MigrationExit"
        if ($MigrationExit -ne 0) {
            Write-Output 'RESULT: MIGRATION TEST FAILED - do not ship this database change.'
            exit $MigrationExit
        }
        Write-Output 'RESULT: migration 7 -> 8 verified on a real device database.'
    } else {
        Write-Output ''
        Write-Output 'NOTE: migration 7 -> 8 was NOT exercised. The host gate cannot run it.'
        Write-Output 'AppModule uses fallbackToDestructiveMigration(), so a wrong migration is'
        Write-Output 'a data-loss risk on upgrade. To verify on a phone that already holds a'
        Write-Output 'version 7 database, rerun with -RunMigrationTest.'
    }

    Write-Output ''
    Write-Output 'RESULT: GREEN - groups unit tests, compile, assemble and the'
    Write-Output 'androidTest sources (migration test included) all passed.'
    Write-Output 'This is a HOST gate only. It is not an acceptance run on real phones.'
    Write-Output 'Next, on the phone: scripts\groups-phone-check.ps1 backs the database'
    Write-Output 'up, runs the migration test on the device, installs and launches the app.'
    exit 0
}
finally {
    Pop-Location
}
