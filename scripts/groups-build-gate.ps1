param(
    [string]$ExpectedCommit = '',
    [switch]$RunMigrationTest,
    [switch]$Clean
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

    if ($ExpectedCommit -ne '' -and $Head -ne $ExpectedCommit -and -not $Head.StartsWith($ExpectedCommit)) {
        Write-Output ''
        Write-Output "FATAL: HEAD $Head does not match the expected commit $ExpectedCommit."
        Write-Output 'The build would test different code. Fix the checkout first:'
        Write-Output '  cd C:\APU-M8'
        Write-Output '  git fetch origin'
        # No branch name is hardcoded here: the previous hint named a branch
        # that no longer exists and sent the user nowhere. Ask git instead.
        & git fetch origin --quiet 2>$null
        $Where = (& git branch -r --contains $ExpectedCommit 2>&1 | Out-String).Trim()
        if ($Where -ne '' -and $Where -notmatch 'fatal|error|unknown revision') {
            Write-Output "Commit $ExpectedCommit is on:"
            $Where -split "`r?`n" | Where-Object { $_ -match 'origin/' } | ForEach-Object {
                Write-Output ('  git checkout ' + ($_.Trim() -replace '^origin/', ''))
            }
        }
        else {
            Write-Output "  git fetch was not enough: $ExpectedCommit is not on origin."
            Write-Output '  Ask which branch holds it before building anything.'
        }
        Write-Output '  git pull --ff-only origin <that branch>'
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
    # Delete the previous debug APK before anything else.
    #
    # Twice already this gate stopped before step 3 (a compile error, then a
    # failing unit test), and the next "adb install" silently pushed the OLD
    # app-debug.apk that was still lying in build/outputs. The phones were then
    # tested against stale code and every "still broken" report was worthless.
    # With no APK on disk the install fails loudly instead of lying.
    $DebugApk = Join-Path $AndroidRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (Test-Path $DebugApk) {
        Remove-Item $DebugApk -Force
        Write-Output "removed the previous debug apk, so a failed gate cannot be installed by mistake"
    }

    # Step 0: cross-check the migration SQL against the Room entities. KSP does
    # not look inside @Query or migration strings, and Room itself only compares
    # the schema when the database is opened on a device, so this is the only
    # host-side defence against a migration that Room would reject. Needs
    # Python; when Python is absent the step is skipped, not failed.
    # The migration named here is the LAST one: the checker compares a single
    # migration against the current entities, so an older one (7 -> 8) now
    # differs by exactly the columns the newer migrations add.
    Write-Output ''
    Write-Output '===== step 0: Room schema cross-check ====='
    $Python = $null
    foreach ($Candidate in @('py', 'python', 'python3')) {
        if (Get-Command $Candidate -ErrorAction SilentlyContinue) { $Python = $Candidate; break }
    }
    if (-not $Python) {
        Write-Output 'NOTE: no Python on PATH, the schema cross-check was skipped.'
    } else {
        $PyArgs = @()
        if ($Python -eq 'py') { $PyArgs = @('-3') }
        $DataLocal = Join-Path $RepoRoot 'android-app\app\src\main\java\com\vladimir\messenger\data\local'
        & $Python @PyArgs (Join-Path $RepoRoot 'tools\sandbox\check_room_schema.py') `
            (Join-Path $DataLocal 'AppDatabase.kt') (Join-Path $DataLocal 'entity') 'MIGRATION_11_12'
        $SchemaExit = $LASTEXITCODE
        Write-Output "schema cross-check exit code: $SchemaExit"
        if ($SchemaExit -ne 0) {
            Write-Output 'RESULT: SCHEMA CROSS-CHECK FAILED - Room would reject this migration.'
            exit $SchemaExit
        }
    }

    # Optional clean: wipe stale incremental caches (kapt stubs, ksp) before
    # building. Round 40 showed a real trap: after a fix commit kapt kept old
    # stubs and reported 'AvatarDao could not be resolved' for a file that is
    # committed and fine. Use -Clean once after any confusing compile error.
    if ($Clean) {
        Write-Output ''
        Write-Output '===== optional clean: wiping stale incremental caches ====='
        & $Gradlew --console=plain clean
        $CleanExit = $LASTEXITCODE
        Write-Output "gradle clean exit code: $CleanExit"
        if ($CleanExit -ne 0) {
            Write-Output 'RESULT: GRADLE CLEAN FAILED.'
            exit $CleanExit
        }
    }

    # Step 0b: rust core tests. The core is compiled by CI, never by this gate,
    # so a broken Rust change stayed invisible here and only failed ~13 minutes
    # later on the release runner. cargo test is cheap and catches it.
    #
    # Requires BOTH cargo and a C toolchain: the core pulls in ring and
    # aws-lc-sys, which compile C and need MSVC cl.exe on Windows. Round 79
    # had cargo installed but no Visual Studio build tools, so this step failed
    # with "cl.exe not found" and blocked an otherwise good build. That is a
    # missing local tool, never a defect in the code, so it must SKIP, not fail.
    # CI builds the core from source on every release and remains the real gate.
    # Note 201/202: host C builds (MSVC) previously crashed this machine, so
    # installing a C toolchain here is deliberately NOT recommended.
    $CargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
    $CCompiler = Get-Command cl.exe -ErrorAction SilentlyContinue
    if ($null -eq $CCompiler -and $env:CC) { $CCompiler = Get-Command $env:CC -ErrorAction SilentlyContinue }
    if ($null -eq $CargoCmd -or $null -eq $CCompiler) {
        $Missing = if ($null -eq $CargoCmd) { 'cargo' } else { 'a C compiler (MSVC cl.exe)' }
        Write-Output ''
        Write-Output "===== step 0b: rust core tests - SKIPPED ($Missing not found) ====="
        Write-Output 'The core needs ring/aws-lc-sys, which compile C on the host. This is a'
        Write-Output 'local convenience step only: CI builds the core from source on every'
        Write-Output 'release and stays the real check. Do NOT install MSVC just for this -'
        Write-Output 'note 201/202 records host C builds causing crashes on this machine.'
    } else {
        Write-Output ''
        Write-Output '===== step 0b: rust core tests ====='
        $CargoExit = 0
        Push-Location (Join-Path $RepoRoot 'rust-core')
        try {
            & cargo test --lib
            $CargoExit = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        Write-Output "rust core tests exit code: $CargoExit"
        if ($CargoExit -ne 0) {
            Write-Output 'RESULT: RUST CORE TESTS FAILED - stop here, do not build.'
            exit $CargoExit
        }
    }

    # Step 1: JVM unit tests. Groups plus the areas this branch touched outside
    # that package: the rank policy that now gates attachments, the link parsers
    # the QR scanner routes through, and the referral attribution added in
    # round 51.
    # Still a test-only invocation, deliberately not mixed with assemble in the
    # same --tests call (rule from docs/AI_HANDOFF.md).
    #
    # This list is a FILTER, not a report: a test class outside it compiles and
    # is silently never run, while the totals below still look green. Round 51
    # shipped ReferralWireTest and ReferralCreditPolicyTest that way - the gate
    # reported the same 108 tests as before and gave no hint. When a new test
    # package appears, add its --tests line here in the same commit.
    Write-Output ''
    Write-Output '===== step 1: unit tests (groups, rank policy, link parsers, referrals, calls, peer rating, sealing) ====='
    & $Gradlew --console=plain :app:testDebugUnitTest `
        --tests 'com.vladimir.messenger.data.group.*' `
        --tests 'com.vladimir.messenger.data.file.FileTransferRankPolicyTest' `
        --tests 'com.vladimir.messenger.data.referral.*' `
        --tests 'com.vladimir.messenger.data.call.*' `
        --tests 'com.vladimir.messenger.data.peer.*' `
        --tests 'com.vladimir.messenger.data.security.*' `
        --tests 'com.vladimir.messenger.util.*'
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

    # A test class that compiled but never ran is invisible in the totals above:
    # round 51 shipped two referral test classes that the --tests filter did not
    # cover, and the gate reported the very same 108 tests as before. List what
    # did not run so that stays visible. NOTE only, never a failure, and wrapped
    # so that a problem here cannot break an otherwise green gate.
    try {
        $TestSrc = Join-Path $AndroidRoot 'app\src\test\java'
        if (Test-Path -LiteralPath $TestSrc) {
            $RanNames = @()
            if (Test-Path -LiteralPath $ReportDir) {
                $RanNames = @(Get-ChildItem -LiteralPath $ReportDir -Filter 'TEST-*.xml' |
                    ForEach-Object { $_.BaseName -replace '^TEST-', '' })
            }
            $NotRun = @(Get-ChildItem -LiteralPath $TestSrc -Recurse -Filter '*Test.kt' | ForEach-Object {
                $Relative = $_.FullName.Substring($TestSrc.Length + 1)
                (($Relative -replace '\\', '.') -replace '\.kt$', '')
            } | Where-Object { $RanNames -notcontains $_ } | Sort-Object)
            if ($NotRun.Count -gt 0) {
                Write-Output ("NOTE: {0} test classes under app\src\test were NOT run by the filter:" -f $NotRun.Count)
                $NotRun | ForEach-Object { Write-Output ("  " + $_) }
            }
        }
    } catch {
        Write-Output ("NOTE: could not list the untested classes: " + $_.Exception.Message)
    }

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

    # Show what was produced, so freshness on the phone can be compared against
    # "dumpsys package <pkg> | Select-String lastUpdateTime" after the install.
    if (Test-Path $DebugApk) {
        $ApkItem = Get-Item $DebugApk
        Write-Output ("debug apk: {0} bytes, built {1}" -f $ApkItem.Length, $ApkItem.LastWriteTime)
        Write-Output "install: adb install -r -t -d `"$DebugApk`""
    } else {
        Write-Output 'WARNING: assembleDebug succeeded but the debug apk was not found.'
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
        Write-Output 'RESULT: the migrations up to the current schema were verified on a real device database.'
    } else {
        Write-Output ''
        Write-Output 'NOTE: the database migrations were NOT exercised. The host gate cannot run them.'
        Write-Output 'AppModule uses fallbackToDestructiveMigration(), so a wrong migration is'
        Write-Output 'a data-loss risk on upgrade. The current schema is version 12; 11 -> 12'
        Write-Output 'adds the avatars registry (10 -> 11 adds nicknames). To verify on a phone that'
        Write-Output 'already holds an older database, rerun with -RunMigrationTest - it wipes app data, back up first.'
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
