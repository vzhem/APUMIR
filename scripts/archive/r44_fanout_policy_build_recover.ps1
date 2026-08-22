$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedSourceCommit = "e3b806f9876219a625e99c17740e59138fefaab5"
$ExpectedParentStateHash = "311C29BFE2402EBEEBA853087C133EF23D05D67590B0AE6A38BF940A1BE57495"
$ExpectedPreviousSoHash = "D7A2216EF0210CBCD59685A6E76CF4D8284B87A8052C799280CBEEC4DD95FE95"
$ExpectedPolicySourceHash = "F4113314E8C8A5D0AD747890388F77AC98592F4D8AEEEFC5282870BD07AC8F7E"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$PolicySource = Join-Path $RepoRoot "rust-core\src\network\mqtt_fanout.rs"
$ParentStatePath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build.json"
$StdoutPath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build.stdout.log"
$StderrPath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build.stderr.log"
$LogPath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build.log"
$RecoveryStatePath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build-recovery.json"
$FeatureMarkerPattern = "Cargo features:\s*mqtt-dual-broker"

if (Test-Path -LiteralPath $RecoveryStatePath) {
    throw "R4.4 FANOUT POLICY BUILD RECOVERY ALREADY EXISTS - DO NOT REPEAT: $RecoveryStatePath"
}
foreach ($RequiredPath in @($ParentStatePath, $StdoutPath, $StderrPath, $LogPath, $SoPath, $PolicySource)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required completed-build evidence missing: $RequiredPath"
    }
}

if ($RepoRoot -notmatch '^[Cc]:\\') {
    throw "APU recovery must run from drive C: $RepoRoot"
}

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $CurrentBranch -ne $ExpectedBranch) {
    throw "Wrong branch: expected=$ExpectedBranch actual=$CurrentBranch"
}
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Cannot resolve Windows HEAD"
}
& git diff --quiet $ExpectedSourceCommit $CurrentHead -- rust-core build-rust.ps1
if ($LASTEXITCODE -ne 0) {
    throw "Application/build source differs from exact compiled r4.4 policy source"
}

$ParentStateHash = (Get-FileHash -LiteralPath $ParentStatePath -Algorithm SHA256).Hash
if ($ParentStateHash -ne $ExpectedParentStateHash) {
    throw "Parent build state hash mismatch: $ParentStateHash"
}
$ParentState = Get-Content -LiteralPath $ParentStatePath -Raw | ConvertFrom-Json

$ParentMatchesCompletedChild = $ParentState.outcome -eq "FAIL_DO_NOT_RETRY_AUTOMATICALLY"
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $ParentState.buildAttempted -eq $true
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and [int]$ParentState.buildProcessId -eq 8048
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and [int]$ParentState.buildExitCode -eq 0
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and [int]$ParentState.finishedReleaseCount -eq 1
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and [int]$ParentState.featureMarkerCount -eq 2
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and [int]$ParentState.compilerWarningCount -eq 25
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and [int]$ParentState.compilerErrorCount -eq 0
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $ParentState.policySourceSha256 -eq $ExpectedPolicySourceHash
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $ParentState.previousSoSha256 -eq $ExpectedPreviousSoHash
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $null -eq $ParentState.generatedSoSize
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $null -eq $ParentState.generatedSoSha256
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $ParentState.runtimeIntegrationEnabled -eq $false
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $ParentState.apkBuilt -eq $false
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $ParentState.adbUsed -eq $false
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $ParentState.phonesChanged -eq $false
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $ParentState.publicTrafficSent -eq $false
$ParentMatchesCompletedChild = $ParentMatchesCompletedChild -and $ParentState.automaticRetry -eq $false
if (-not $ParentMatchesCompletedChild) {
    throw "Parent state does not prove completed child build followed by marker-only wrapper stop"
}

$StdoutLines = @(Get-Content -LiteralPath $StdoutPath)
$StderrLines = @(Get-Content -LiteralPath $StderrPath)
$CombinedLines = @($StdoutLines) + @($StderrLines)
$StdoutFeatureMarkerCount = @(
    $StdoutLines | Where-Object { $_ -match $FeatureMarkerPattern }
).Count
$StderrFeatureMarkerCount = @(
    $StderrLines | Where-Object { $_ -match $FeatureMarkerPattern }
).Count
$CombinedFeatureMarkerCount = @(
    $CombinedLines | Where-Object { $_ -match $FeatureMarkerPattern }
).Count
$FinishedReleaseCount = @(
    $CombinedLines | Where-Object { $_ -match "Finished.*release" }
).Count
$CompilerErrorCount = @(
    $CombinedLines | Where-Object { $_ -match "^\s*error(?:\[|:)" }
).Count
$StderrIsCliXml = ($StderrLines -join "`n") -match "CLIXML"

if ($StdoutFeatureMarkerCount -ne 1) {
    throw "Expected exactly one authoritative stdout feature marker, actual=$StdoutFeatureMarkerCount"
}
if ($StderrFeatureMarkerCount -ne 1 -or $CombinedFeatureMarkerCount -ne 2) {
    throw "Saved duplicate-marker shape changed: stderr=$StderrFeatureMarkerCount combined=$CombinedFeatureMarkerCount"
}
if ($FinishedReleaseCount -ne 1 -or $CompilerErrorCount -ne 0) {
    throw "Completed Cargo evidence mismatch: finished=$FinishedReleaseCount errors=$CompilerErrorCount"
}

$PolicySourceHash = (Get-FileHash -LiteralPath $PolicySource -Algorithm SHA256).Hash
if ($PolicySourceHash -ne $ExpectedPolicySourceHash) {
    throw "Compiled policy source hash mismatch: $PolicySourceHash"
}
$GeneratedSoFile = Get-Item -LiteralPath $SoPath
$GeneratedSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($GeneratedSoFile.Length -le 0 -or $GeneratedSoHash -eq $ExpectedPreviousSoHash) {
    throw "Generated policy .so is missing, empty, or unchanged"
}

$FinalStatus = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedFinal = @(
    $FinalStatus | Where-Object { $_ -ne " M $GeneratedSoRelative" }
)
if ($UnexpectedFinal.Count -gt 0) {
    throw "Unexpected recovery worktree changes: $($UnexpectedFinal -join '; ')"
}

$RecoveryState = [ordered]@{
    schema = 1
    purpose = "saved-artifact recovery for completed r4.4 fanout policy Android Rust build"
    outcome = "PASS_FROM_COMPLETED_BUILD_EVIDENCE"
    analyzedUtc = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = $PSCommandPath
    scriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    expectedSourceCommit = $ExpectedSourceCommit
    windowsHead = $CurrentHead
    parentState = $ParentStatePath
    parentStateSha256 = $ParentStateHash
    parentOutcome = $ParentState.outcome
    parentFailure = $ParentState.failure
    childProcessId = [int]$ParentState.buildProcessId
    childExitCode = [int]$ParentState.buildExitCode
    finishedReleaseCount = $FinishedReleaseCount
    compilerWarningCount = [int]$ParentState.compilerWarningCount
    compilerErrorCount = $CompilerErrorCount
    stdoutFeatureMarkerCount = $StdoutFeatureMarkerCount
    stderrFeatureMarkerCount = $StderrFeatureMarkerCount
    combinedFeatureMarkerCount = $CombinedFeatureMarkerCount
    stderrIsCliXml = $StderrIsCliXml
    markerInterpretation = "stdout is authoritative; stderr CLIXML/host duplicate is retained evidence"
    policySource = $PolicySource
    policySourceSha256 = $PolicySourceHash
    previousSoSha256 = $ExpectedPreviousSoHash
    generatedSoSize = $GeneratedSoFile.Length
    generatedSoSha256 = $GeneratedSoHash
    stdoutPath = $StdoutPath
    stdoutSha256 = (Get-FileHash -LiteralPath $StdoutPath -Algorithm SHA256).Hash
    stderrPath = $StderrPath
    stderrSha256 = (Get-FileHash -LiteralPath $StderrPath -Algorithm SHA256).Hash
    combinedLogPath = $LogPath
    combinedLogSha256 = (Get-FileHash -LiteralPath $LogPath -Algorithm SHA256).Hash
    maxFanout = 2
    retainedTargetCapacity = 4096
    runtimeIntegrationEnabled = $false
    buildRepeated = $false
    apkBuilt = $false
    adbUsed = $false
    phonesChanged = $false
    publicTrafficSent = $false
    automaticRetry = $false
}

$RecoveryState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $RecoveryStatePath -Encoding UTF8
$RecoveryStateHash = (Get-FileHash -LiteralPath $RecoveryStatePath -Algorithm SHA256).Hash

Write-Host "R4.4 FANOUT POLICY BUILD RECOVERY PASS"
Write-Host "Recovery state:          $RecoveryStatePath"
Write-Host "Recovery state SHA256:   $RecoveryStateHash"
Write-Host "Parent state SHA256:     $ParentStateHash"
Write-Host "Child exit/Finished:     $($ParentState.buildExitCode) / $FinishedReleaseCount"
Write-Host "Feature markers out/err: $StdoutFeatureMarkerCount / $StderrFeatureMarkerCount"
Write-Host "Compiler warnings/errors:$($ParentState.compilerWarningCount) / $CompilerErrorCount"
Write-Host "Policy source SHA256:    $PolicySourceHash"
Write-Host "Generated .so size:      $($GeneratedSoFile.Length)"
Write-Host "Generated .so SHA256:    $GeneratedSoHash"
Write-Host "Build repeated:          False"
Write-Host "Runtime/APK/phones:      False / False / False"
