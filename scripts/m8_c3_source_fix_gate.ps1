$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

# Single-use continuation after the exact E0004 InvalidKeyMaterial compile failure.
# Prior gate/recovery evidence and stale-target evidence remain immutable.

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot
$ExpectedApplicationCommit = "eee46e56b2752699aaceba207b93db6d7c03d68e"
$ExpectedPriorRecoveryHash = "D37E5372074EA30C35B0EB282851C9854F6FFB19B1AD42BC384975B7361402D8"
$AllowedBranches = @("arena/01a00674-apumir", "arena/01a013d0-apumir", "arena/01a0149e-apumir")
$PowerShellExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$Prefix = Join-Path $env:TEMP "apu-m8-c3-source-fix"
$StatePath = "$Prefix-state.json"
$RustOut = "$Prefix-rust.stdout.log"; $RustErr = "$Prefix-rust.stderr.log"; $RustExit = "$Prefix-rust.exit.txt"
$BindgenOut = "$Prefix-bindgen.stdout.log"; $BindgenErr = "$Prefix-bindgen.stderr.log"; $BindgenExit = "$Prefix-bindgen.exit.txt"
$GradleOut = "$Prefix-gradle.stdout.log"; $GradleErr = "$Prefix-gradle.stderr.log"; $GradleExit = "$Prefix-gradle.exit.txt"
$Evidence = @($StatePath,$RustOut,$RustErr,$RustExit,$BindgenOut,$BindgenErr,$BindgenExit,$GradleOut,$GradleErr,$GradleExit)
$Existing = @($Evidence | Where-Object { Test-Path -LiteralPath $_ })
if ($Existing.Count -gt 0) { throw "M8-C3 source-fix gate already attempted; preserve evidence: $($Existing -join ', ')" }
if ($RepoRoot -notmatch '^[Cc]:\\') { throw "Gate must run from drive C: $RepoRoot" }

$Branch = ((& git branch --show-current) -join "").Trim()
$Head = ((& git rev-parse HEAD) -join "").Trim()
if ($AllowedBranches -notcontains $Branch) { throw "Wrong branch: $Branch" }
& git diff --quiet $ExpectedApplicationCommit $Head -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) { throw "Application source differs from exact source-fix commit" }
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 0) { throw "Gate requires clean worktree: $($Status -join '; ')" }

$PriorStatePath = Join-Path $env:TEMP "apu-m8-c3-recovery-state.json"
$PriorRustErr = Join-Path $env:TEMP "apu-m8-c3-recovery-rust.stderr.log"
foreach ($Path in @($PriorStatePath,$PriorRustErr)) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Prior recovery evidence missing: $Path" }
}
$PriorHash = (Get-FileHash -LiteralPath $PriorStatePath -Algorithm SHA256).Hash
if ($PriorHash -ne $ExpectedPriorRecoveryHash) { throw "Prior recovery state hash mismatch: $PriorHash" }
$PriorState = Get-Content -LiteralPath $PriorStatePath -Raw | ConvertFrom-Json
$PriorError = Get-Content -LiteralPath $PriorRustErr -Raw
if ($PriorState.outcome -ne "INCOMPLETE_DO_NOT_REPEAT" -or $PriorError -notmatch 'E0004' -or $PriorError -notmatch 'InvalidKeyMaterial') {
    throw "Prior evidence is not the exact non-exhaustive InvalidKeyMaterial failure"
}
$StaleTargetEvidence = Join-Path $env:TEMP "apu-m8-c3-stale-target-lnk1207"
if (-not (Test-Path -LiteralPath $StaleTargetEvidence -PathType Container)) { throw "Preserved stale target evidence missing" }
$CargoTarget = Join-Path $RepoRoot "rust-core\target"
if (-not (Test-Path -LiteralPath $CargoTarget -PathType Container)) { throw "Fresh Cargo target continuation is missing" }

$SoPath = Join-Path $RepoRoot "android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so"
$BindingPath = Join-Path $RepoRoot "android-app\app\src\main\java\uniffi\p2p_core\p2p_core.kt"
$ApkPath = Join-Path $RepoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk"
$BaselineSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
$BaselineBindingHash = (Get-FileHash -LiteralPath $BindingPath -Algorithm SHA256).Hash

function Invoke-Captured {
    param([string]$Label,[string]$WorkingDirectory,[string]$Command,[string]$Out,[string]$Err,[string]$Exit,[int]$Timeout)
    $Wrapped = @"
`$ErrorActionPreference = 'Continue'
Set-Location '$WorkingDirectory'
& { $Command }
`$Code = if (`$null -eq `$LASTEXITCODE) { 0 } else { [int]`$LASTEXITCODE }
[IO.File]::WriteAllText('$Exit', `$Code.ToString(), [Text.Encoding]::ASCII)
"@
    $Encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Wrapped))
    $Process = Start-Process -FilePath $PowerShellExe -ArgumentList @("-NoProfile","-EncodedCommand",$Encoded) `
        -WorkingDirectory $WorkingDirectory -RedirectStandardOutput $Out -RedirectStandardError $Err -PassThru
    if (-not $Process.WaitForExit($Timeout)) {
        try { Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue } catch {}
        throw "$Label timed out: process=$($Process.Id)"
    }
    $Process.WaitForExit()
    if (-not (Test-Path -LiteralPath $Exit -PathType Leaf)) { throw "$Label lacks exit marker" }
    $Code = [int](Get-Content -LiteralPath $Exit -Raw).Trim()
    if ($Code -ne 0) { throw "$Label failed: process=$($Process.Id) exit=$Code" }
    return $Process.Id
}

$Outcome="INCOMPLETE_DO_NOT_REPEAT"; $Failure=$null; $Started=(Get-Date).ToUniversalTime(); $Timer=[Diagnostics.Stopwatch]::StartNew()
$RustProcess=$null; $BindgenProcess=$null; $GradleProcess=$null
$SoHash=$null; $BindingHash=$null; $ApkHash=$null; $ApkSize=$null
try {
    $RustCore = Join-Path $RepoRoot "rust-core"; $Jni = Join-Path $RepoRoot "android-app\app\src\main\jniLibs"
    $RustProcess = Invoke-Captured "M8-C3 fixed Rust build" $RustCore `
        "& cargo ndk -t arm64-v8a -o '$Jni' build --release --features mqtt-dual-broker" `
        $RustOut $RustErr $RustExit 1200000
    $RustLines = @((Get-Content $RustOut),(Get-Content $RustErr))
    if (@($RustLines | Where-Object { $_ -match 'Finished.*release' }).Count -ne 1) { throw "Rust build lacks one Finished release marker" }
    $SoHash=(Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
    if ($SoHash -eq $BaselineSoHash) { throw "Native library unchanged" }

    $BindgenProcess = Invoke-Captured "M8-C3 bindgen" $RustCore `
        "& cargo run --bin uniffi-bindgen -- generate src/lib.udl --language kotlin --config uniffi.toml --out-dir ..\android-app\app\src\main\java" `
        $BindgenOut $BindgenErr $BindgenExit 1200000
    $BindingHash=(Get-FileHash -LiteralPath $BindingPath -Algorithm SHA256).Hash
    if ($BindingHash -eq $BaselineBindingHash) { throw "UniFFI binding unchanged" }
    $BindingText=Get-Content -LiteralPath $BindingPath -Raw
    foreach ($Marker in @("createEngineDurable","installRelayAtRestKey","relayCustodyMode","relayQuarantineCount")) {
        if ($BindingText -notmatch $Marker) { throw "Binding lacks $Marker" }
    }

    $Android=Join-Path $RepoRoot "android-app"
    $GradleProcess = Invoke-Captured "M8-C3 debug APK build" $Android `
        "& '.\gradlew.bat' --no-daemon :app:assembleDebug" $GradleOut $GradleErr $GradleExit 1200000
    $GradleLines=@((Get-Content $GradleOut),(Get-Content $GradleErr))
    if (@($GradleLines | Where-Object { $_ -match 'BUILD SUCCESSFUL' }).Count -lt 1) { throw "Gradle lacks BUILD SUCCESSFUL" }
    $Apk=Get-Item -LiteralPath $ApkPath; $ApkSize=$Apk.Length; $ApkHash=(Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash

    $Final=@(& git status --porcelain=v1 --untracked-files=all)
    $Allowed=@(" M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"," M android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt")
    $Unexpected=@($Final | Where-Object { $_ -notin $Allowed })
    if ($Final.Count -ne 2 -or $Unexpected.Count -gt 0) { throw "Unexpected outputs: $($Final -join '; ')" }
    $Outcome="PASS"
} catch { $Failure=$_.Exception.Message; throw } finally {
    $Timer.Stop()
    $Result=[ordered]@{schema=1;purpose="M8-C3 source-fix compile continuation";outcome=$Outcome;failure=$Failure
        priorRecoveryStateSha256=$PriorHash;startedUtc=$Started.ToString("o");completedUtc=(Get-Date).ToUniversalTime().ToString("o")
        durationSeconds=[math]::Round($Timer.Elapsed.TotalSeconds,2);sourceFixCommit=$ExpectedApplicationCommit;windowsHead=$Head;branch=$Branch
        rustProcessId=$RustProcess;bindgenProcessId=$BindgenProcess;gradleProcessId=$GradleProcess
        baselineSoSha256=$BaselineSoHash;generatedSoSha256=$SoHash;baselineBindingSha256=$BaselineBindingHash;generatedBindingSha256=$BindingHash
        debugApkPath=$ApkPath;debugApkSize=$ApkSize;debugApkSha256=$ApkHash;adbUsed=$false;phonesChanged=$false;publicTrafficSent=$false}
    $Result|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $StatePath -Encoding UTF8
    $ResultHash=(Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash
    Write-Host "";Write-Host "Source-fix outcome: $Outcome";Write-Host "State: $StatePath";Write-Host "State SHA256: $ResultHash"
    Write-Host "Rust/Bindgen/Gradle: $RustProcess / $BindgenProcess / $GradleProcess";Write-Host "Generated .so: $SoHash"
    Write-Host "Generated binding: $BindingHash";Write-Host "Debug APK: $ApkSize bytes / $ApkHash";Write-Host "ADB/phones/traffic: False / False / False"
}
if ($Outcome -ne "PASS") { throw "M8-C3 source-fix gate did not pass; preserve all evidence" }
Write-Host "M8 A->C3 SOURCE-FIX COMPILE GATE PASS" -ForegroundColor Green
