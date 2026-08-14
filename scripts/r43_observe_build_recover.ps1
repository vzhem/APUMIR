$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedHead = "c6164ba482b7f0e5073161e6d4ef5c84f444913a"
$ExpectedApplicationCommit = "2689dbb933523bb410d4a0ce22f5122863f0ba63"
$ExpectedParentStateHash = "1A81D7F0ABE883146C06E9641A2C43AD49F94C80CD35C94A633143964BD42D03"
$ExpectedPreviousSoHash = "E706A9009F28E842F6A030D0CCC7BABB28D56E20DEFB5FB34117FD87F032E7E5"
$ExpectedGeneratedSoSize = 7193912
$ExpectedGeneratedSoHash = "D7A2216EF0210CBCD59685A6E76CF4D8284B87A8052C799280CBEEC4DD95FE95"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$ParentStatePath = Join-Path $env:TEMP "apu-r4.3-observe-rust-build.json"
$StdoutPath = Join-Path $env:TEMP "apu-r4.3-observe-rust-build.stdout.log"
$StderrPath = Join-Path $env:TEMP "apu-r4.3-observe-rust-build.stderr.log"
$RecoveryStatePath = Join-Path $env:TEMP "apu-r4.3-observe-rust-build-recovery.json"

if (Test-Path -LiteralPath $RecoveryStatePath) {
    throw "R4.3 BUILD RECOVERY ALREADY EXISTS - DO NOT REPEAT: $RecoveryStatePath"
}
foreach ($RequiredPath in @($ParentStatePath, $StdoutPath, $StderrPath, $SoPath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required recovery evidence missing: $RequiredPath"
    }
}

$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $CurrentHead -ne $ExpectedHead) {
    throw "Unexpected Windows HEAD: $CurrentHead"
}
& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) {
    throw "Application/build source differs from expected r4.3 source"
}

$ParentStateHash = (Get-FileHash -LiteralPath $ParentStatePath -Algorithm SHA256).Hash
if ($ParentStateHash -ne $ExpectedParentStateHash) {
    throw "Parent wrapper state hash mismatch: $ParentStateHash"
}
$ParentState = Get-Content -LiteralPath $ParentStatePath -Raw | ConvertFrom-Json
if ($ParentState.outcome -ne "FAIL_DO_NOT_RETRY_AUTOMATICALLY" -or
    $ParentState.buildAttempted -ne $true -or
    $ParentState.buildExitCode -ne $null -or
    $ParentState.previousSoSha256 -ne $ExpectedPreviousSoHash -or
    $ParentState.phonesChanged -ne $false) {
    throw "Parent state does not match interrupted-wrapper evidence"
}

$StdoutLines = @(Get-Content -LiteralPath $StdoutPath)
$StderrLines = @(Get-Content -LiteralPath $StderrPath)
$FeatureMarkerCount = @(
    $StdoutLines | Where-Object { $_ -match "Cargo features:\s*mqtt-secondary-observe" }
).Count
$FinishedReleaseCount = @(
    $StderrLines | Where-Object { $_ -match "Finished.*release.*in 1m 12s" }
).Count
$CargoWarningSummaryCount = @(
    $StderrLines | Where-Object { $_ -match "generated 9 warnings" }
).Count
$CompilerErrorCount = @(
    $StderrLines | Where-Object { $_ -match "^\s*error(?:\[|:)" }
).Count
$CopiedNativeCount = @(
    $StdoutLines | Where-Object { $_ -match "arm64-v8a\\libp2p_core\.so" }
).Count

if ($FeatureMarkerCount -ne 1) {
    throw "Feature marker count mismatch: $FeatureMarkerCount"
}
if ($FinishedReleaseCount -ne 1) {
    throw "Finished release marker count mismatch: $FinishedReleaseCount"
}
if ($CargoWarningSummaryCount -ne 1) {
    throw "Cargo warning summary count mismatch: $CargoWarningSummaryCount"
}
if ($CompilerErrorCount -ne 0) {
    throw "Compiler error lines found: $CompilerErrorCount"
}
if ($CopiedNativeCount -lt 1) {
    throw "Build completion/native copy marker missing"
}

$GeneratedSoFile = Get-Item -LiteralPath $SoPath
$GeneratedSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($GeneratedSoFile.Length -ne $ExpectedGeneratedSoSize) {
    throw "Generated .so size mismatch: $($GeneratedSoFile.Length)"
}
if ($GeneratedSoHash -ne $ExpectedGeneratedSoHash) {
    throw "Generated .so hash mismatch: $GeneratedSoHash"
}
if ($GeneratedSoHash -eq $ExpectedPreviousSoHash) {
    throw "Generated feature .so unexpectedly equals previous r1b4 .so"
}

$BinaryText = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($SoPath))
$SecondaryStatusMarkerPresent = $BinaryText.Contains("MQTT SECONDARY STATUS")
$SecondarySupervisorMarkerPresent = $BinaryText.Contains("MQTT SECONDARY SUPERVISOR")
$ObserveOnlyMarkerPresent = $BinaryText.Contains("subscriptions=0 publishes=0")
if (-not $SecondaryStatusMarkerPresent -or
    -not $SecondarySupervisorMarkerPresent -or
    -not $ObserveOnlyMarkerPresent) {
    throw "Feature marker strings are missing from generated binary"
}

$FinalStatus = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedStatus = @(
    $FinalStatus | Where-Object { $_ -ne " M $GeneratedSoRelative" }
)
if ($UnexpectedStatus.Count -gt 0) {
    throw "Unexpected worktree changes: $($UnexpectedStatus -join '; ')"
}

$RecoveryState = [ordered]@{
    schema = 1
    purpose = "read-only recovery of completed r4.3 feature build after wrapper wait interruption"
    outcome = "PASS_FROM_COMPLETED_CHILD_EVIDENCE"
    analyzedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    expectedApplicationCommit = $ExpectedApplicationCommit
    windowsHead = $CurrentHead
    feature = "mqtt-secondary-observe"
    parentState = $ParentStatePath
    parentStateSha256 = $ParentStateHash
    parentOutcome = $ParentState.outcome
    parentWrapperInterrupted = $true
    buildRepeated = $false
    stdoutPath = $StdoutPath
    stdoutSha256 = (Get-FileHash -LiteralPath $StdoutPath -Algorithm SHA256).Hash
    stderrPath = $StderrPath
    stderrSha256 = (Get-FileHash -LiteralPath $StderrPath -Algorithm SHA256).Hash
    featureMarkerCount = $FeatureMarkerCount
    finishedReleaseCount = $FinishedReleaseCount
    cargoWarningSummaryCount = $CargoWarningSummaryCount
    compilerErrorCount = $CompilerErrorCount
    copiedNativeCount = $CopiedNativeCount
    previousSoSha256 = $ExpectedPreviousSoHash
    generatedSoSize = $GeneratedSoFile.Length
    generatedSoSha256 = $GeneratedSoHash
    secondaryStatusMarkerPresent = $SecondaryStatusMarkerPresent
    secondarySupervisorMarkerPresent = $SecondarySupervisorMarkerPresent
    observeOnlyMarkerPresent = $ObserveOnlyMarkerPresent
    apkBuilt = $false
    phonesChanged = $false
    publicTrafficSent = $false
}

$RecoveryState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $RecoveryStatePath -Encoding UTF8
$RecoveryStateHash = (Get-FileHash -LiteralPath $RecoveryStatePath -Algorithm SHA256).Hash

Write-Host "R4.3 FEATURE BUILD RECOVERY PASS"
Write-Host "Recovery state:          $RecoveryStatePath"
Write-Host "Recovery state SHA256:   $RecoveryStateHash"
Write-Host "Parent state SHA256:     $ParentStateHash"
Write-Host "Feature marker count:    $FeatureMarkerCount"
Write-Host "Finished release count: $FinishedReleaseCount"
Write-Host "Compiler errors:         $CompilerErrorCount"
Write-Host "Generated .so size:      $($GeneratedSoFile.Length)"
Write-Host "Generated .so SHA256:    $GeneratedSoHash"
Write-Host "Secondary markers:       $SecondaryStatusMarkerPresent / $SecondarySupervisorMarkerPresent / $ObserveOnlyMarkerPresent"
Write-Host "Build repeated:          False"
Write-Host "Phones changed:          False"
