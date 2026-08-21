$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

# Single-use continuation for the recorded 2026-08-18 LNK1207 stale-PDB failure.
# It preserves the original gate evidence and stale Cargo target directory,
# then performs one fresh Rust build followed by bindgen and assembleDebug.

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedApplicationCommit = "204fb9f8293aad68496df47dfeb5172dabbcf26c"
$ExpectedPriorStateHash = "2ABC3ACFB36B2A8E541A7049C30E0D8D5FA9468FE9E5A4B7D276F0666236FBF6"
$AllowedBranches = @("arena/01a00674-apumir", "arena/01a013d0-apumir", "arena/01a0149e-apumir")
$FeatureName = "mqtt-dual-broker"
$PowerShellExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"

$PriorStatePath = Join-Path $env:TEMP "apu-m8-c3-compile-state.json"
$PriorRustStderrPath = Join-Path $env:TEMP "apu-m8-c3-rust.stderr.log"
$RecoveryStatePath = Join-Path $env:TEMP "apu-m8-c3-recovery-state.json"
$RecoveryRustOut = Join-Path $env:TEMP "apu-m8-c3-recovery-rust.stdout.log"
$RecoveryRustErr = Join-Path $env:TEMP "apu-m8-c3-recovery-rust.stderr.log"
$RecoveryRustExit = Join-Path $env:TEMP "apu-m8-c3-recovery-rust.exit.txt"
$RecoveryBindgenOut = Join-Path $env:TEMP "apu-m8-c3-recovery-bindgen.stdout.log"
$RecoveryBindgenErr = Join-Path $env:TEMP "apu-m8-c3-recovery-bindgen.stderr.log"
$RecoveryBindgenExit = Join-Path $env:TEMP "apu-m8-c3-recovery-bindgen.exit.txt"
$RecoveryGradleOut = Join-Path $env:TEMP "apu-m8-c3-recovery-gradle.stdout.log"
$RecoveryGradleErr = Join-Path $env:TEMP "apu-m8-c3-recovery-gradle.stderr.log"
$RecoveryGradleExit = Join-Path $env:TEMP "apu-m8-c3-recovery-gradle.exit.txt"
$StaleTargetEvidence = Join-Path $env:TEMP "apu-m8-c3-stale-target-lnk1207"
$RecoveryEvidence = @(
    $RecoveryStatePath, $RecoveryRustOut, $RecoveryRustErr, $RecoveryRustExit,
    $RecoveryBindgenOut, $RecoveryBindgenErr, $RecoveryBindgenExit,
    $RecoveryGradleOut, $RecoveryGradleErr, $RecoveryGradleExit, $StaleTargetEvidence
)
$ExistingRecovery = @($RecoveryEvidence | Where-Object { Test-Path -LiteralPath $_ })
if ($ExistingRecovery.Count -gt 0) {
    throw "M8-C3 recovery already attempted - do not repeat/delete evidence: $($ExistingRecovery -join ', ')"
}
if ($RepoRoot -notmatch '^[Cc]:\\') { throw "Recovery must run from drive C: $RepoRoot" }

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $AllowedBranches -notcontains $CurrentBranch) {
    throw "Wrong branch: $CurrentBranch"
}
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) { throw "Application source differs from exact M8-C3 source" }
$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
if ($StatusBefore.Count -ne 0) { throw "Recovery requires clean worktree: $($StatusBefore -join '; ')" }

foreach ($Path in @($PriorStatePath, $PriorRustStderrPath)) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Prior evidence missing: $Path" }
}
$PriorStateHash = (Get-FileHash -LiteralPath $PriorStatePath -Algorithm SHA256).Hash
if ($PriorStateHash -ne $ExpectedPriorStateHash) { throw "Prior state hash mismatch: $PriorStateHash" }
$PriorState = Get-Content -LiteralPath $PriorStatePath -Raw | ConvertFrom-Json
if ($PriorState.outcome -ne "INCOMPLETE_DO_NOT_REPEAT" -or $PriorState.expectedApplicationCommit -ne $ExpectedApplicationCommit) {
    throw "Prior state is not the exact incomplete M8-C3 gate"
}
$PriorRustError = Get-Content -LiteralPath $PriorRustStderrPath -Raw
if ($PriorRustError -notmatch 'LNK1207' -or $PriorRustError -notmatch '\.pdb') {
    throw "Prior Rust evidence is not the recorded stale/incompatible PDB failure"
}

$CargoTarget = Join-Path $RepoRoot "rust-core\target"
if (-not (Test-Path -LiteralPath $CargoTarget -PathType Container)) { throw "Cargo target cache missing: $CargoTarget" }
$BaselineSoPath = Join-Path $RepoRoot "android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so"
$BindingPath = Join-Path $RepoRoot "android-app\app\src\main\java\uniffi\p2p_core\p2p_core.kt"
$DebugApkPath = Join-Path $RepoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk"
$BaselineSoHash = (Get-FileHash -LiteralPath $BaselineSoPath -Algorithm SHA256).Hash
$BaselineBindingHash = (Get-FileHash -LiteralPath $BindingPath -Algorithm SHA256).Hash

function Invoke-CapturedCommand {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$WorkingDirectory,
        [Parameter(Mandatory=$true)][string]$Command,
        [Parameter(Mandatory=$true)][string]$StdoutPath,
        [Parameter(Mandatory=$true)][string]$StderrPath,
        [Parameter(Mandatory=$true)][string]$ExitMarkerPath,
        [Parameter(Mandatory=$true)][int]$TimeoutMilliseconds
    )
    $Wrapped = @"
`$ErrorActionPreference = 'Continue'
Set-Location '$WorkingDirectory'
& { $Command }
`$Code = if (`$null -eq `$LASTEXITCODE) { 0 } else { [int]`$LASTEXITCODE }
[IO.File]::WriteAllText('$ExitMarkerPath', `$Code.ToString(), [Text.Encoding]::ASCII)
"@
    $Encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Wrapped))
    $Process = Start-Process -FilePath $PowerShellExe -ArgumentList @("-NoProfile", "-EncodedCommand", $Encoded) `
        -WorkingDirectory $WorkingDirectory -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath -PassThru
    if (-not $Process.WaitForExit($TimeoutMilliseconds)) {
        try { Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue } catch {}
        throw "$Label timed out: process=$($Process.Id)"
    }
    $Process.WaitForExit()
    if (-not (Test-Path -LiteralPath $ExitMarkerPath -PathType Leaf)) {
        throw "$Label did not write an exit marker: process=$($Process.Id)"
    }
    $Code = [int](Get-Content -LiteralPath $ExitMarkerPath -Raw).Trim()
    if ($Code -ne 0) { throw "$Label failed: process=$($Process.Id) exit=$Code" }
    return $Process.Id
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$StartedUtc = (Get-Date).ToUniversalTime()
$Stopwatch = [Diagnostics.Stopwatch]::StartNew()
$RustProcessId = $null
$BindgenProcessId = $null
$GradleProcessId = $null
$GeneratedSoHash = $null
$GeneratedBindingHash = $null
$DebugApkHash = $null
$DebugApkSize = $null
$StaleTargetPreserved = $false

try {
    # Preserve, rather than delete, the incompatible MSVC PDB and all related Cargo intermediates.
    Move-Item -LiteralPath $CargoTarget -Destination $StaleTargetEvidence
    $StaleTargetPreserved = $true

    $RustCore = Join-Path $RepoRoot "rust-core"
    $JniOutput = Join-Path $RepoRoot "android-app\app\src\main\jniLibs"
    $RustCommand = "& cargo ndk -t arm64-v8a -o '$JniOutput' build --release --features '$FeatureName'"
    $RustProcessId = Invoke-CapturedCommand -Label "Fresh M8-C3 Android Rust build" `
        -WorkingDirectory $RustCore -Command $RustCommand -StdoutPath $RecoveryRustOut `
        -StderrPath $RecoveryRustErr -ExitMarkerPath $RecoveryRustExit -TimeoutMilliseconds 1200000
    $RustLines = @((Get-Content $RecoveryRustOut), (Get-Content $RecoveryRustErr))
    if (@($RustLines | Where-Object { $_ -match 'Finished.*release' }).Count -ne 1) {
        throw "Fresh Rust build lacks exactly one Finished release marker"
    }
    $GeneratedSoHash = (Get-FileHash -LiteralPath $BaselineSoPath -Algorithm SHA256).Hash
    if ($GeneratedSoHash -eq $BaselineSoHash) { throw "Fresh Rust build left native library unchanged" }

    $BindgenCommand = "& cargo run --manifest-path ..\tools\uniffi-bindgen\Cargo.toml -- generate src/lib.udl --language kotlin --config uniffi.toml --out-dir ..\android-app\app\src\main\java"
    $BindgenProcessId = Invoke-CapturedCommand -Label "M8-C3 UniFFI generation" `
        -WorkingDirectory $RustCore -Command $BindgenCommand -StdoutPath $RecoveryBindgenOut `
        -StderrPath $RecoveryBindgenErr -ExitMarkerPath $RecoveryBindgenExit -TimeoutMilliseconds 1200000
    $GeneratedBindingHash = (Get-FileHash -LiteralPath $BindingPath -Algorithm SHA256).Hash
    if ($GeneratedBindingHash -eq $BaselineBindingHash) { throw "UniFFI binding did not change" }
    $BindingText = Get-Content -LiteralPath $BindingPath -Raw
    foreach ($Marker in @("createEngineDurable", "installRelayAtRestKey", "relayCustodyMode", "relayQuarantineCount")) {
        if ($BindingText -notmatch $Marker) { throw "Generated binding lacks $Marker" }
    }

    $AndroidDir = Join-Path $RepoRoot "android-app"
    $GradleCommand = "& '.\gradlew.bat' --no-daemon :app:assembleDebug"
    $GradleProcessId = Invoke-CapturedCommand -Label "M8-C3 debug APK build" `
        -WorkingDirectory $AndroidDir -Command $GradleCommand -StdoutPath $RecoveryGradleOut `
        -StderrPath $RecoveryGradleErr -ExitMarkerPath $RecoveryGradleExit -TimeoutMilliseconds 1200000
    $GradleLines = @((Get-Content $RecoveryGradleOut), (Get-Content $RecoveryGradleErr))
    if (@($GradleLines | Where-Object { $_ -match 'BUILD SUCCESSFUL' }).Count -lt 1) { throw "Gradle lacks BUILD SUCCESSFUL" }
    $DebugApk = Get-Item -LiteralPath $DebugApkPath
    $DebugApkSize = $DebugApk.Length
    $DebugApkHash = (Get-FileHash -LiteralPath $DebugApkPath -Algorithm SHA256).Hash

    $StatusAfter = @(& git status --porcelain=v1 --untracked-files=all)
    $Allowed = @(
        " M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so",
        " M android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
    )
    $Unexpected = @($StatusAfter | Where-Object { $_ -notin $Allowed })
    if ($StatusAfter.Count -ne 2 -or $Unexpected.Count -gt 0) { throw "Unexpected outputs: $($StatusAfter -join '; ')" }
    $Outcome = "PASS"
}
catch {
    $Failure = $_.Exception.Message
    throw
}
finally {
    $Stopwatch.Stop()
    $State = [ordered]@{
        schema=1; purpose="M8-C3 LNK1207 preserved-cache recovery and compile continuation"
        outcome=$Outcome; failure=$Failure; priorStatePath=$PriorStatePath; priorStateSha256=$PriorStateHash
        startedUtc=$StartedUtc.ToString("o"); completedUtc=(Get-Date).ToUniversalTime().ToString("o")
        durationSeconds=[math]::Round($Stopwatch.Elapsed.TotalSeconds,2)
        expectedApplicationCommit=$ExpectedApplicationCommit; windowsHead=$CurrentHead; branch=$CurrentBranch
        recoveryReason="MSVC LNK1207 incompatible stale PDB"; staleTargetPreserved=$StaleTargetPreserved
        staleTargetEvidencePath=$StaleTargetEvidence; rustProcessId=$RustProcessId
        bindgenProcessId=$BindgenProcessId; gradleProcessId=$GradleProcessId
        baselineSoSha256=$BaselineSoHash; generatedSoSha256=$GeneratedSoHash
        baselineBindingSha256=$BaselineBindingHash; generatedBindingSha256=$GeneratedBindingHash
        debugApkPath=$DebugApkPath; debugApkSize=$DebugApkSize; debugApkSha256=$DebugApkHash
        adbUsed=$false; phonesChanged=$false; publicTrafficSent=$false; automaticRetry=$false
    }
    $State | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $RecoveryStatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $RecoveryStatePath -Algorithm SHA256).Hash
    Write-Host ""; Write-Host "Recovery outcome:       $Outcome"
    Write-Host "Recovery state:         $RecoveryStatePath"; Write-Host "Recovery state SHA256:  $StateHash"
    Write-Host "Prior evidence:         preserved / $PriorStateHash"
    Write-Host "Stale target preserved: $StaleTargetPreserved / $StaleTargetEvidence"
    Write-Host "Rust/Bindgen/Gradle:     $RustProcessId / $BindgenProcessId / $GradleProcessId"
    Write-Host "Generated .so:          $GeneratedSoHash"
    Write-Host "Generated binding:      $GeneratedBindingHash"
    Write-Host "Debug APK:              $DebugApkSize bytes / $DebugApkHash"
    Write-Host "ADB/phones/traffic:     False / False / False"
}
if ($Outcome -ne "PASS") { throw "M8-C3 recovery did not pass; preserve all apu-m8-c3-* evidence" }
Write-Host "M8 A->C3 COMPILE GATE RECOVERY PASS" -ForegroundColor Green
