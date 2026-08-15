$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

# ============================================================
# M8-A/B/D Android Rust compile gate (PC-only, no phones).
# Requires the worktree to be already adopted to the M8 branch
# (guarded adoption block precedes this script in the paste).
# Pattern follows proven scripts/m3d_offline_send_build.ps1.
# ============================================================

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedApplicationCommit = "b881d65a249d184a8179ac42fa54910c595ba838"
$AllowedBranches = @("arena/01a000bc-apumir", "arena/01a00674-apumir")
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$ExpectedBaselineSoSize = 7263416
$ExpectedBaselineSoHash = "27B9D4DC87CA7046D9F862F9ED153FDDD48C26E4053B620FE46986D25D1FD26C"
$IconRelative = "design/branding/app-icon/source/apu-icon-original.png"
$IconPath = Join-Path $RepoRoot $IconRelative
$ExpectedIconSize = 1980451
$ExpectedIconHash = "F2638C88A3EAB243766B8F4755183C89A3E1FFCB72B45A0BBC5F3D398C83ACA9"
$BuildScript = Join-Path $RepoRoot "build-rust.ps1"
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$CargoToml = Join-Path $RepoRoot "rust-core\Cargo.toml"
$CoreSource = Join-Path $RepoRoot "rust-core\src\engine\core.rs"
$RelayQueueSource = Join-Path $RepoRoot "rust-core\src\network\relay_queue.rs"
$RelayStoreSource = Join-Path $RepoRoot "rust-core\src\storage\relay_store.rs"
$OfflineSource = Join-Path $RepoRoot "rust-core\src\network\offline_send.rs"
$StorageMod = Join-Path $RepoRoot "rust-core\src\storage\mod.rs"
$StatePath = Join-Path $env:TEMP "apu-m8-rust-build.json"
$StdoutPath = Join-Path $env:TEMP "apu-m8-rust-build.stdout.log"
$StderrPath = Join-Path $env:TEMP "apu-m8-rust-build.stderr.log"
$LogPath = Join-Path $env:TEMP "apu-m8-rust-build.log"
$PowerShellExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$FeatureName = "mqtt-dual-broker"
$BuildTimeoutMilliseconds = 900000

$Artifacts = @($StatePath, $StdoutPath, $StderrPath, $LogPath)
$ExistingArtifacts = @($Artifacts | Where-Object { Test-Path -LiteralPath $_ })
if ($ExistingArtifacts.Count -gt 0) {
    throw "M8 RUST BUILD ALREADY ATTEMPTED - DO NOT REPEAT: $($ExistingArtifacts -join ', ')"
}
foreach ($RequiredPath in @(
    $BuildScript, $SoPath, $CargoToml, $CoreSource, $RelayQueueSource,
    $RelayStoreSource, $OfflineSource, $StorageMod, $IconPath, $PowerShellExe
)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required M8 build input missing: $RequiredPath"
    }
}
if ($RepoRoot -notmatch '^[Cc]:\\') { throw "APU build must run from drive C: $RepoRoot" }
foreach ($EnvironmentPath in @($env:CARGO_HOME, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $env:ANDROID_NDK_HOME)) {
    if ($EnvironmentPath -and $EnvironmentPath -match '^[Dd]:\\') {
        throw "Drive D path is forbidden for APU build: $EnvironmentPath"
    }
}

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $AllowedBranches -notcontains $CurrentBranch) {
    throw "Wrong branch: allowed=$($AllowedBranches -join ',') actual=$CurrentBranch"
}
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0) { throw "Cannot resolve Windows HEAD" }
& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) { throw "Application source differs from exact M8 commit $ExpectedApplicationCommit" }

$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$AllowedStatus = @(" M $GeneratedSoRelative", "?? $IconRelative")
$UnexpectedBefore = @($StatusBefore | Where-Object { $_ -notin $AllowedStatus })
if (
    $UnexpectedBefore.Count -gt 0 -or
    $StatusBefore.Count -ne 2 -or
    $StatusBefore -notcontains " M $GeneratedSoRelative" -or
    $StatusBefore -notcontains "?? $IconRelative"
) { throw "Unexpected pre-build worktree changes: $($StatusBefore -join '; ')" }
$BaselineSo = Get-Item -LiteralPath $SoPath
$BaselineSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($BaselineSo.Length -ne $ExpectedBaselineSoSize -or $BaselineSoHash -ne $ExpectedBaselineSoHash) {
    throw "M8 baseline native mismatch: size=$($BaselineSo.Length) hash=$BaselineSoHash"
}
$IconFile = Get-Item -LiteralPath $IconPath
$IconHash = (Get-FileHash -LiteralPath $IconPath -Algorithm SHA256).Hash
if ($IconFile.Length -ne $ExpectedIconSize -or $IconHash -ne $ExpectedIconHash) {
    throw "APU icon original mismatch: size=$($IconFile.Length) hash=$IconHash"
}

$Tokens = $null
$ParseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $BuildScript, [ref]$Tokens, [ref]$ParseErrors
) | Out-Null
if (@($ParseErrors).Count -ne 0) { throw "build-rust.ps1 has parser errors" }

# --- M8 source static contract ---
$CargoText = Get-Content -LiteralPath $CargoToml -Raw
$CoreText = Get-Content -LiteralPath $CoreSource -Raw
$RelayQueueText = Get-Content -LiteralPath $RelayQueueSource -Raw
$RelayStoreText = Get-Content -LiteralPath $RelayStoreSource -Raw
$OfflineText = Get-Content -LiteralPath $OfflineSource -Raw
$StorageModText = Get-Content -LiteralPath $StorageMod -Raw
$StaticValid = $CargoText -match '(?m)^mqtt-dual-broker\s*=\s*\[\]\s*$'
$StaticValid = $StaticValid -and $RelayQueueText -match 'pub created_at_ms: i64'
$StaticValid = $StaticValid -and $RelayQueueText -match 'pub expires_at_ms: i64'
$StaticValid = $StaticValid -and $RelayQueueText -match 'pub fn from_persisted'
$StaticValid = $StaticValid -and $RelayQueueText -match 'pub fn validate_durable'
$StaticValid = $StaticValid -and $RelayQueueText -match 'pub fn utc_now_ms'
$StaticValid = $StaticValid -and $RelayQueueText -match 'pub fn is_expired_at'
$StaticValid = $StaticValid -and $RelayQueueText -match 'pub fn remaining_ttl_secs'
$StaticValid = $StaticValid -and $RelayStoreText -match 'pub struct RelayStore'
$StaticValid = $StaticValid -and $RelayStoreText -match 'MIGRATION_RELAY_V1'
$StaticValid = $StaticValid -and $RelayStoreText -match 'CREATE TABLE IF NOT EXISTS relay_messages'
$StaticValid = $StaticValid -and $RelayStoreText -match 'CREATE TABLE IF NOT EXISTS relay_tombstones'
$StaticValid = $StaticValid -and $RelayStoreText -match 'idx_relay_messages_recipient'
$StaticValid = $StaticValid -and $RelayStoreText -match 'idx_relay_messages_expires'
$StaticValid = $StaticValid -and $RelayStoreText -match 'pub fn load_unexpired'
$StaticValid = $StaticValid -and $RelayStoreText -match 'pub fn remove_and_tombstone'
$StaticValid = $StaticValid -and $RelayStoreText -match 'pub fn load_tombstone_ids'
$StaticValid = $StaticValid -and $StorageModText -match 'pub mod relay_store;'
$StaticValid = $StaticValid -and $CoreText -match 'relay_store: Option<Arc<RelayStore>>'
$StaticValid = $StaticValid -and $CoreText -match 'restore_relay_custody'
$StaticValid = $StaticValid -and $CoreText -match 'durable_admitted'
$StaticValid = $StaticValid -and $CoreText -match 'remove_and_tombstone'
$StaticValid = $StaticValid -and $CoreText -match 'record_tombstone'
$StaticValid = $StaticValid -and $CoreText -match 'remaining_ttl_secs\(\)'
$StaticValid = $StaticValid -and $OfflineText -match 'valid_metadata_atom'
$StaticValid = $StaticValid -and $OfflineText -match 'pub use crate::network::relay_queue::MAX_MESH_RELAY_ENVELOPE_BYTES;'
if (-not $StaticValid) { throw "M8 source static contract mismatch" }

function Wait-ExactProcess {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory = $true)][int]$TimeoutMilliseconds,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $Exited = $Process.WaitForExit($TimeoutMilliseconds)
    if (-not $Exited) { throw "$Label process $($Process.Id) timed out" }
    $Process.WaitForExit(); $Process.Refresh()
    $RawExitCode = $Process.ExitCode
    if ($null -eq $RawExitCode) { throw "$Label exit code unavailable" }
    return [pscustomobject]@{ Exited=$true; ExitCode=[int]$RawExitCode; ProcessId=$Process.Id }
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$BuildAttempted = $false
$BuildProcessId = $null
$BuildExitCode = $null
$FinishedReleaseCount = 0
$StdoutFeatureMarkerCount = 0
$CompilerWarningCount = 0
$CompilerErrorCount = 0
$GeneratedSoSize = $null
$GeneratedSoHash = $null
$StartedUtc = (Get-Date).ToUniversalTime()
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

try {
    $Command = @"
`$ErrorActionPreference = 'Continue'
Set-Location '$RepoRoot'
& '$BuildScript' -Features '$FeatureName'
exit `$LASTEXITCODE
"@
    $EncodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command))
    $BuildAttempted = $true
    $BuildProcess = Start-Process -FilePath $PowerShellExe `
        -ArgumentList @("-NoProfile", "-EncodedCommand", $EncodedCommand) `
        -WorkingDirectory $RepoRoot -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath -PassThru
    $BuildProcessId = $BuildProcess.Id
    $BuildWait = Wait-ExactProcess $BuildProcess $BuildTimeoutMilliseconds "M8 Android Rust build"
    $BuildExitCode = $BuildWait.ExitCode

    $StdoutLines = @(Get-Content -LiteralPath $StdoutPath)
    $StderrLines = @(Get-Content -LiteralPath $StderrPath)
    $BuildLines = @($StdoutLines) + @($StderrLines)
    @("=== STDOUT ===",$StdoutLines,"=== STDERR ===",$StderrLines) |
        Set-Content -LiteralPath $LogPath -Encoding UTF8
    $FinishedReleaseCount = @($BuildLines | Where-Object { $_ -match 'Finished.*release' }).Count
    $StdoutFeatureMarkerCount = @($StdoutLines | Where-Object { $_ -match 'Cargo features:\s*mqtt-dual-broker' }).Count
    $CompilerWarningCount = @($BuildLines | Where-Object { $_ -match '^\s*warning(?:\[|:)' }).Count
    $CompilerErrorCount = @($BuildLines | Where-Object { $_ -match '^\s*error(?:\[|:)' }).Count

    if ($BuildExitCode -ne 0) { throw "M8 build failed: exit=$BuildExitCode" }
    if ($FinishedReleaseCount -ne 1) { throw "Expected one Finished release marker: $FinishedReleaseCount" }
    if ($StdoutFeatureMarkerCount -ne 1) { throw "Expected one stdout feature marker: $StdoutFeatureMarkerCount" }
    if ($CompilerErrorCount -ne 0) { throw "Compiler errors found: $CompilerErrorCount" }

    $GeneratedSo = Get-Item -LiteralPath $SoPath
    $GeneratedSoSize = $GeneratedSo.Length
    $GeneratedSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
    if ($GeneratedSoSize -le 0 -or $GeneratedSoHash -eq $BaselineSoHash) {
        throw "Generated M8 native is empty or unchanged"
    }
    $FinalStatus = @(& git status --porcelain=v1 --untracked-files=all)
    $UnexpectedFinal = @($FinalStatus | Where-Object { $_ -notin $AllowedStatus })
    $IconHashAfter = (Get-FileHash -LiteralPath $IconPath -Algorithm SHA256).Hash
    if (
        $UnexpectedFinal.Count -gt 0 -or
        $FinalStatus.Count -ne 2 -or
        $FinalStatus -notcontains " M $GeneratedSoRelative" -or
        $FinalStatus -notcontains "?? $IconRelative" -or
        $IconHashAfter -ne $ExpectedIconHash
    ) { throw "Unexpected build outputs/icon change: $($FinalStatus -join '; ')" }
    $Outcome = "PASS"
}
catch { $Failure = $_.Exception.Message; throw }
finally {
    $Stopwatch.Stop()
    $State = [ordered]@{
        schema=1; purpose="M8-A/B/D durable relay custody Android Rust dual-feature compile gate"
        outcome=$Outcome; failure=$Failure; startedUtc=$StartedUtc.ToString("o")
        completedUtc=(Get-Date).ToUniversalTime().ToString("o")
        durationSeconds=[math]::Round($Stopwatch.Elapsed.TotalSeconds,2)
        expectedApplicationCommit=$ExpectedApplicationCommit; windowsHead=$CurrentHead
        m8aCommit="b5408e2ca284bdb0ad50e6a0089daad2c74c4356"
        m8bdCommit="b881d65a249d184a8179ac42fa54910c595ba838"
        feature=$FeatureName; buildAttempted=$BuildAttempted
        buildProcessId=$BuildProcessId; buildExitCode=$BuildExitCode
        finishedReleaseCount=$FinishedReleaseCount; stdoutFeatureMarkerCount=$StdoutFeatureMarkerCount
        compilerWarningCount=$CompilerWarningCount; compilerErrorCount=$CompilerErrorCount
        baselineSoSize=$BaselineSo.Length; baselineSoSha256=$BaselineSoHash
        generatedSoSize=$GeneratedSoSize; generatedSoSha256=$GeneratedSoHash
        stdoutPath=$StdoutPath; stderrPath=$StderrPath; logPath=$LogPath
        iconOriginalPath=$IconPath; iconOriginalSize=$IconFile.Length; iconOriginalSha256=$IconHash
        iconOriginalChanged=$false; wireFormatChanged=$false; roomSchemaChanged=$false
        sqliteMigrationAdded=$true; apkBuilt=$false; adbUsed=$false
        phonesChanged=$false; publicTrafficSent=$false; automaticRetry=$false
    }
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash
    Write-Host ""; Write-Host "Outcome:                 $Outcome"
    Write-Host "State:                   $StatePath"; Write-Host "State SHA256:            $StateHash"
    Write-Host "Source commit:           $ExpectedApplicationCommit"; Write-Host "Feature:                 $FeatureName"
    Write-Host "Build process/exit:      $BuildProcessId / $BuildExitCode"
    Write-Host "Finished/feature marker: $FinishedReleaseCount / $StdoutFeatureMarkerCount"
    Write-Host "Warnings/errors:         $CompilerWarningCount / $CompilerErrorCount"
    Write-Host "Generated .so size:      $GeneratedSoSize"; Write-Host "Generated .so SHA256:    $GeneratedSoHash"
    Write-Host "Icon original SHA256:    $IconHash (unchanged/untracked)"
    Write-Host "APK/ADB/phones/traffic:  False / False / False / False"
}
if ($Outcome -ne "PASS") { throw "M8 build did not pass; see state: $StatePath" }
Write-Host "M8 DURABLE RELAY CUSTODY ANDROID RUST BUILD PASS"
