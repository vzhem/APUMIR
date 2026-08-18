$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

# M8-A -> M8-C3 compile gate (Windows PC only; no APK install and no ADB).
# Run once from a clean checkout on drive C:
#   .\scripts\m8_rust_build_gate.ps1
# The script builds the Android Rust library, regenerates UniFFI Kotlin bindings,
# and compiles a debug APK. Evidence is retained in %TEMP%\apu-m8-c3-*.

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedApplicationCommit = "204fb9f8293aad68496df47dfeb5172dabbcf26c"
$AllowedBranches = @(
    "arena/01a00674-apumir",
    "arena/01a013d0-apumir",
    "arena/01a0149e-apumir"
)
$FeatureName = "mqtt-dual-broker"
$BuildTimeoutMilliseconds = 900000
$BindgenTimeoutMilliseconds = 900000
$GradleTimeoutMilliseconds = 1200000

$RustBuildScript = Join-Path $RepoRoot "build-rust.ps1"
$CargoToml = Join-Path $RepoRoot "rust-core\Cargo.toml"
$UdlPath = Join-Path $RepoRoot "rust-core\src\lib.udl"
$RustLibPath = Join-Path $RepoRoot "rust-core\src\lib.rs"
$CoreSource = Join-Path $RepoRoot "rust-core\src\engine\core.rs"
$AtRestSource = Join-Path $RepoRoot "rust-core\src\storage\relay_at_rest.rs"
$RelayStoreSource = Join-Path $RepoRoot "rust-core\src\storage\relay_store.rs"
$KotlinKeySource = Join-Path $RepoRoot "android-app\app\src\main\java\com\vladimir\messenger\data\security\RelayAtRestMasterKey.kt"
$RustBridgeSource = Join-Path $RepoRoot "android-app\app\src\main\java\com\vladimir\messenger\data\RustBridge.kt"
$GeneratedBindingRelative = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$GeneratedBindingPath = Join-Path $RepoRoot ($GeneratedBindingRelative -replace '/', '\')
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$GeneratedSoPath = Join-Path $RepoRoot ($GeneratedSoRelative -replace '/', '\')
$GradleWrapper = Join-Path $RepoRoot "android-app\gradlew.bat"
$DebugApkPath = Join-Path $RepoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk"
$PowerShellExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"

$StatePath = Join-Path $env:TEMP "apu-m8-c3-compile-state.json"
$RustStdoutPath = Join-Path $env:TEMP "apu-m8-c3-rust.stdout.log"
$RustStderrPath = Join-Path $env:TEMP "apu-m8-c3-rust.stderr.log"
$BindgenStdoutPath = Join-Path $env:TEMP "apu-m8-c3-bindgen.stdout.log"
$BindgenStderrPath = Join-Path $env:TEMP "apu-m8-c3-bindgen.stderr.log"
$GradleStdoutPath = Join-Path $env:TEMP "apu-m8-c3-gradle.stdout.log"
$GradleStderrPath = Join-Path $env:TEMP "apu-m8-c3-gradle.stderr.log"
$EvidencePaths = @(
    $StatePath,
    $RustStdoutPath, $RustStderrPath,
    $BindgenStdoutPath, $BindgenStderrPath,
    $GradleStdoutPath, $GradleStderrPath
)

$ExistingEvidence = @($EvidencePaths | Where-Object { Test-Path -LiteralPath $_ })
if ($ExistingEvidence.Count -gt 0) {
    throw "M8-C3 COMPILE GATE ALREADY ATTEMPTED - do not repeat or delete evidence: $($ExistingEvidence -join ', ')"
}

foreach ($RequiredPath in @(
    $RustBuildScript, $CargoToml, $UdlPath, $RustLibPath, $CoreSource,
    $AtRestSource, $RelayStoreSource, $KotlinKeySource, $RustBridgeSource,
    $GeneratedBindingPath, $GeneratedSoPath, $GradleWrapper, $PowerShellExe
)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required M8-C3 compile input missing: $RequiredPath"
    }
}
if ($RepoRoot -notmatch '^[Cc]:\\') { throw "APU compile gate must run from drive C: $RepoRoot" }
foreach ($EnvironmentPath in @($env:CARGO_HOME, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $env:ANDROID_NDK_HOME)) {
    if ($EnvironmentPath -and $EnvironmentPath -match '^[Dd]:\\') {
        throw "Drive D path is forbidden for APU build inputs: $EnvironmentPath"
    }
}

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $AllowedBranches -notcontains $CurrentBranch) {
    throw "Wrong branch: allowed=$($AllowedBranches -join ',') actual=$CurrentBranch"
}
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0) { throw "Cannot resolve Windows HEAD" }
& git cat-file -e "$ExpectedApplicationCommit^{commit}"
if ($LASTEXITCODE -ne 0) { throw "Expected M8-C3 commit is unavailable: $ExpectedApplicationCommit" }
& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) {
    throw "Application source differs from exact M8-C3 source commit $ExpectedApplicationCommit"
}
$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
if ($StatusBefore.Count -ne 0) {
    throw "Compile gate requires a clean worktree; preserve and reconcile these changes first: $($StatusBefore -join '; ')"
}

$BaselineSo = Get-Item -LiteralPath $GeneratedSoPath
$BaselineSoHash = (Get-FileHash -LiteralPath $GeneratedSoPath -Algorithm SHA256).Hash
$BaselineBindingHash = (Get-FileHash -LiteralPath $GeneratedBindingPath -Algorithm SHA256).Hash

$Tokens = $null
$ParseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $RustBuildScript, [ref]$Tokens, [ref]$ParseErrors
) | Out-Null
if (@($ParseErrors).Count -ne 0) { throw "build-rust.ps1 has parser errors" }

# Static source contract: this is specifically the complete encrypted-custody C3 slice.
$CargoText = Get-Content -LiteralPath $CargoToml -Raw
$UdlText = Get-Content -LiteralPath $UdlPath -Raw
$RustLibText = Get-Content -LiteralPath $RustLibPath -Raw
$CoreText = Get-Content -LiteralPath $CoreSource -Raw
$AtRestText = Get-Content -LiteralPath $AtRestSource -Raw
$RelayStoreText = Get-Content -LiteralPath $RelayStoreSource -Raw
$KotlinKeyText = Get-Content -LiteralPath $KotlinKeySource -Raw
$RustBridgeText = Get-Content -LiteralPath $RustBridgeSource -Raw
$StaticChecks = [ordered]@{
    feature = $CargoText -match '(?m)^mqtt-dual-broker\s*=\s*\[\]\s*$'
    udlInstallKey = $UdlText -match 'install_relay_at_rest_key'
    udlDurableEngine = $UdlText -match 'create_engine_durable'
    udlCustodyMode = $UdlText -match 'relay_custody_mode'
    udlQuarantineCount = $UdlText -match 'relay_quarantine_count'
    rustInstallKey = $RustLibText -match 'pub fn install_relay_at_rest_key'
    rustDurableEngine = $RustLibText -match 'pub fn create_engine_durable'
    custodyType = $CoreText -match 'struct RelayCustody'
    durableMode = $CoreText -match 'durable-encrypted'
    encryptedStore = $CoreText -match 'store_encrypted'
    encryptedRestore = $CoreText -match 'load_unexpired_encrypted'
    encryptedRemoval = $CoreText -match 'remove_encrypted_and_tombstone'
    keySource = $AtRestText -match 'pub struct MasterSecretKeySource'
    quarantineSchema = $RelayStoreText -match 'CREATE TABLE IF NOT EXISTS relay_quarantine'
    kotlinKeystore = $KotlinKeyText -match 'AndroidKeyStore'
    kotlinInstallBeforeCore = $RustBridgeText -match 'createEngineDurable'
}
$FailedStaticChecks = @($StaticChecks.Keys | Where-Object { -not $StaticChecks[$_] })
if ($FailedStaticChecks.Count -gt 0) {
    throw "M8-C3 static source contract mismatch: $($FailedStaticChecks -join ', ')"
}

function Start-CapturedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string]$StdoutPath,
        [Parameter(Mandatory = $true)][string]$StderrPath,
        [Parameter(Mandatory = $true)][int]$TimeoutMilliseconds
    )
    $EncodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command))
    $Process = Start-Process -FilePath $PowerShellExe `
        -ArgumentList @("-NoProfile", "-EncodedCommand", $EncodedCommand) `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath `
        -PassThru
    $Exited = $Process.WaitForExit($TimeoutMilliseconds)
    if (-not $Exited) {
        try { Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue } catch {}
        throw "$Label process $($Process.Id) timed out"
    }
    $Process.WaitForExit()
    $Process.Refresh()
    if ($Process.ExitCode -ne 0) { throw "$Label failed: process=$($Process.Id) exit=$($Process.ExitCode)" }
    return [pscustomobject]@{ ProcessId=$Process.Id; ExitCode=[int]$Process.ExitCode }
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$StartedUtc = (Get-Date).ToUniversalTime()
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$RustProcessId = $null
$BindgenProcessId = $null
$GradleProcessId = $null
$GeneratedSoSize = $null
$GeneratedSoHash = $null
$GeneratedBindingHash = $null
$DebugApkSize = $null
$DebugApkHash = $null

try {
    $RustCommand = @"
`$ErrorActionPreference = 'Stop'
Set-Location '$RepoRoot'
& '$RustBuildScript' -Features '$FeatureName'
if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }
"@
    $RustResult = Start-CapturedProcess -Label "M8-C3 Android Rust build" `
        -WorkingDirectory $RepoRoot -Command $RustCommand `
        -StdoutPath $RustStdoutPath -StderrPath $RustStderrPath `
        -TimeoutMilliseconds $BuildTimeoutMilliseconds
    $RustProcessId = $RustResult.ProcessId

    $RustLines = @((Get-Content -LiteralPath $RustStdoutPath), (Get-Content -LiteralPath $RustStderrPath))
    if (@($RustLines | Where-Object { $_ -match 'Finished.*release' }).Count -ne 1) {
        throw "Rust build lacks exactly one Finished release marker"
    }
    if (@(Get-Content -LiteralPath $RustStderrPath | Where-Object { $_ -match '^\s*error(?:\[|:)' }).Count -ne 0) {
        throw "Rust compiler errors found in captured stderr"
    }
    $GeneratedSo = Get-Item -LiteralPath $GeneratedSoPath
    $GeneratedSoSize = $GeneratedSo.Length
    $GeneratedSoHash = (Get-FileHash -LiteralPath $GeneratedSoPath -Algorithm SHA256).Hash
    if ($GeneratedSoSize -le 0 -or $GeneratedSoHash -eq $BaselineSoHash) {
        throw "Generated M8-C3 native library is empty or unchanged"
    }

    $RustCoreDir = Join-Path $RepoRoot "rust-core"
    $BindgenCommand = @"
`$ErrorActionPreference = 'Stop'
Set-Location '$RustCoreDir'
& cargo run --bin uniffi-bindgen -- generate src/lib.udl --language kotlin --config uniffi.toml --out-dir ..\android-app\app\src\main\java
if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }
"@
    $BindgenResult = Start-CapturedProcess -Label "M8-C3 UniFFI Kotlin generation" `
        -WorkingDirectory $RustCoreDir -Command $BindgenCommand `
        -StdoutPath $BindgenStdoutPath -StderrPath $BindgenStderrPath `
        -TimeoutMilliseconds $BindgenTimeoutMilliseconds
    $BindgenProcessId = $BindgenResult.ProcessId
    $GeneratedBindingHash = (Get-FileHash -LiteralPath $GeneratedBindingPath -Algorithm SHA256).Hash
    if ($GeneratedBindingHash -eq $BaselineBindingHash) { throw "UniFFI Kotlin binding did not change" }
    $GeneratedBindingText = Get-Content -LiteralPath $GeneratedBindingPath -Raw
    foreach ($Marker in @("createEngineDurable", "installRelayAtRestKey", "relayCustodyMode", "relayQuarantineCount")) {
        if ($GeneratedBindingText -notmatch $Marker) { throw "Generated UniFFI binding lacks marker: $Marker" }
    }

    $AndroidDir = Join-Path $RepoRoot "android-app"
    $GradleCommand = @"
`$ErrorActionPreference = 'Stop'
Set-Location '$AndroidDir'
& '$GradleWrapper' --no-daemon :app:assembleDebug
if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }
"@
    $GradleResult = Start-CapturedProcess -Label "M8-C3 Android debug APK build" `
        -WorkingDirectory $AndroidDir -Command $GradleCommand `
        -StdoutPath $GradleStdoutPath -StderrPath $GradleStderrPath `
        -TimeoutMilliseconds $GradleTimeoutMilliseconds
    $GradleProcessId = $GradleResult.ProcessId
    $GradleLines = @((Get-Content -LiteralPath $GradleStdoutPath), (Get-Content -LiteralPath $GradleStderrPath))
    if (@($GradleLines | Where-Object { $_ -match 'BUILD SUCCESSFUL' }).Count -lt 1) {
        throw "Gradle output lacks BUILD SUCCESSFUL"
    }
    if (-not (Test-Path -LiteralPath $DebugApkPath -PathType Leaf)) { throw "Debug APK missing: $DebugApkPath" }
    $DebugApk = Get-Item -LiteralPath $DebugApkPath
    $DebugApkSize = $DebugApk.Length
    $DebugApkHash = (Get-FileHash -LiteralPath $DebugApkPath -Algorithm SHA256).Hash
    if ($DebugApkSize -le 0) { throw "Debug APK is empty" }

    $StatusAfter = @(& git status --porcelain=v1 --untracked-files=all)
    $AllowedStatus = @(" M $GeneratedSoRelative", " M $GeneratedBindingRelative")
    $UnexpectedStatus = @($StatusAfter | Where-Object { $_ -notin $AllowedStatus })
    if ($UnexpectedStatus.Count -gt 0 -or $StatusAfter.Count -ne 2) {
        throw "Unexpected compile outputs in worktree: $($StatusAfter -join '; ')"
    }
    $Outcome = "PASS"
}
catch {
    $Failure = $_.Exception.Message
    throw
}
finally {
    $Stopwatch.Stop()
    $State = [ordered]@{
        schema=1
        purpose="M8-A through M8-C3 encrypted durable custody Windows compile gate"
        outcome=$Outcome
        failure=$Failure
        startedUtc=$StartedUtc.ToString("o")
        completedUtc=(Get-Date).ToUniversalTime().ToString("o")
        durationSeconds=[math]::Round($Stopwatch.Elapsed.TotalSeconds, 2)
        expectedApplicationCommit=$ExpectedApplicationCommit
        windowsHead=$CurrentHead
        branch=$CurrentBranch
        feature=$FeatureName
        rustProcessId=$RustProcessId
        bindgenProcessId=$BindgenProcessId
        gradleProcessId=$GradleProcessId
        baselineSoSize=$BaselineSo.Length
        baselineSoSha256=$BaselineSoHash
        generatedSoSize=$GeneratedSoSize
        generatedSoSha256=$GeneratedSoHash
        baselineBindingSha256=$BaselineBindingHash
        generatedBindingSha256=$GeneratedBindingHash
        debugApkPath=$DebugApkPath
        debugApkSize=$DebugApkSize
        debugApkSha256=$DebugApkHash
        rustStdoutPath=$RustStdoutPath
        rustStderrPath=$RustStderrPath
        bindgenStdoutPath=$BindgenStdoutPath
        bindgenStderrPath=$BindgenStderrPath
        gradleStdoutPath=$GradleStdoutPath
        gradleStderrPath=$GradleStderrPath
        apkInstalled=$false
        adbUsed=$false
        phonesChanged=$false
        publicTrafficSent=$false
        automaticRetry=$false
    }
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash
    Write-Host ""
    Write-Host "Outcome:              $Outcome"
    Write-Host "State:                $StatePath"
    Write-Host "State SHA256:         $StateHash"
    Write-Host "Source commit:        $ExpectedApplicationCommit"
    Write-Host "Rust/Bindgen/Gradle:  $RustProcessId / $BindgenProcessId / $GradleProcessId"
    Write-Host "Generated .so:        $GeneratedSoSize bytes / $GeneratedSoHash"
    Write-Host "Generated binding:   $GeneratedBindingHash"
    Write-Host "Debug APK:            $DebugApkSize bytes / $DebugApkHash"
    Write-Host "ADB/phones/traffic:   False / False / False"
}

if ($Outcome -ne "PASS") { throw "M8-C3 compile gate did not pass; preserve evidence at $StatePath" }
Write-Host "M8 A->C3 ENCRYPTED DURABLE CUSTODY COMPILE GATE PASS" -ForegroundColor Green
