param(
    [string]$ExpectedCommit = ''
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

    Write-Output ''
    Write-Output 'RESULT: GREEN - groups unit tests, compile and assemble all passed.'
    Write-Output 'This is a HOST gate only. It is not an acceptance run on real phones.'
    Write-Output 'Manual check on a phone that already has v11.18.0 installed:'
    Write-Output '  1. install the debug APK with: adb install -r -t -d app-debug.apk'
    Write-Output '  2. confirm the app opens (Room migration 7 -> 8 ran)'
    Write-Output '  3. menu -> Gruppy -> create a group, add a topic, send a message'
    exit 0
}
finally {
    Pop-Location
}
