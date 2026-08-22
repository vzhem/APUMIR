$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$ParentStatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-zhenya-launch1.json"
$ExpectedParentStateHash = "DCC2C15E8FF7BD03656851417B3E85F52B1F36ABCD07B78FD0D15EDA9216D0A4"
$ParentEvidenceDir = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-zhenya-launch1-evidence"
$AnalysisStatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-zhenya-launch1-analysis.json"
$PackageName = "com.vladimir.messenger"

if (Test-Path -LiteralPath $AnalysisStatePath) {
    throw "R4.4 MIXED ZHENYA LAUNCH1 ANALYSIS ALREADY EXISTS - DO NOT REPEAT: $AnalysisStatePath"
}
if (-not (Test-Path -LiteralPath $ParentStatePath -PathType Leaf)) {
    throw "Zhenya launch1 parent state missing: $ParentStatePath"
}
if (-not (Test-Path -LiteralPath $ParentEvidenceDir -PathType Container)) {
    throw "Zhenya launch1 parent evidence missing: $ParentEvidenceDir"
}

$ParentStateHash = (Get-FileHash -LiteralPath $ParentStatePath -Algorithm SHA256).Hash
if ($ParentStateHash -ne $ExpectedParentStateHash) {
    throw "Zhenya launch1 parent state hash mismatch: $ParentStateHash"
}
$ParentState = Get-Content -LiteralPath $ParentStatePath -Raw | ConvertFrom-Json
$ParentValid = $ParentState.outcome -eq "INCOMPLETE_DO_NOT_REPEAT"
$ParentValid = $ParentValid -and $ParentState.failure -eq "Zhenya is no longer stopped; controlled launch is forbidden"
$ParentValid = $ParentValid -and $ParentState.launchStarted -eq $false
$ParentValid = $ParentValid -and [string]::IsNullOrWhiteSpace([string]$ParentState.zhenyaNewProcessId)
$ParentValid = $ParentValid -and $null -eq $ParentState.metrics
$ParentValid = $ParentValid -and $ParentState.installStarted -eq $false
$ParentValid = $ParentValid -and $ParentState.annaLaunched -eq $false
$ParentValid = $ParentValid -and $ParentState.stasLaunched -eq $false
$ParentValid = $ParentValid -and $ParentState.uninstalled -eq $false
$ParentValid = $ParentValid -and $ParentState.dataCleared -eq $false
$ParentValid = $ParentValid -and $ParentState.forceStopped -eq $false
$ParentValid = $ParentValid -and $ParentState.logcatCleared -eq $false
$ParentValid = $ParentValid -and $ParentState.networkChanged -eq $false
$ParentValid = $ParentValid -and $ParentState.userPayloadPublished -eq $false
$ParentValid = $ParentValid -and $ParentState.automaticRetry -eq $false
if (-not $ParentValid) {
    throw "Parent state does not prove the exact pre-launch stop"
}

$ExpectedEvidenceNames = [System.Collections.Generic.List[string]]::new()
$ExpectedEvidenceNames.Add("adb-devices.stdout.log")
$ExpectedEvidenceNames.Add("adb-devices.stderr.log")
foreach ($SafeName in @("anna", "zhenya", "stas")) {
    foreach ($Kind in @("uid", "package", "process")) {
        $ExpectedEvidenceNames.Add("$SafeName-pre-$Kind.stdout.log")
        $ExpectedEvidenceNames.Add("$SafeName-pre-$Kind.stderr.log")
    }
}

$ActualEvidenceNames = @(
    Get-ChildItem -LiteralPath $ParentEvidenceDir -File |
        Select-Object -ExpandProperty Name |
        Sort-Object
)
$ExpectedSortedNames = @($ExpectedEvidenceNames | Sort-Object)
$EvidenceNameDifferences = @(
    Compare-Object $ExpectedSortedNames $ActualEvidenceNames
)
if (
    $ActualEvidenceNames.Count -ne $ExpectedSortedNames.Count -or
    $EvidenceNameDifferences.Count -ne 0
) {
    throw "Launch1 evidence set proves commands beyond the expected pre-launch snapshot"
}

$EvidenceHashes = [ordered]@{}
foreach ($EvidenceName in $ExpectedSortedNames) {
    $EvidencePath = Join-Path $ParentEvidenceDir $EvidenceName
    $EvidenceHashes[$EvidenceName] = (
        Get-FileHash -LiteralPath $EvidencePath -Algorithm SHA256
    ).Hash
    if ($EvidenceName -like "*.stderr.log" -and (Get-Item -LiteralPath $EvidencePath).Length -ne 0) {
        throw "Saved command stderr is not empty: $EvidenceName"
    }
}

function Test-SnapshotIdentity {
    param(
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][int64]$ExpectedUid,
        [Parameter(Mandatory = $true)][string]$ExpectedVersionName,
        [Parameter(Mandatory = $true)][int64]$ExpectedVersionCode,
        [Parameter(Mandatory = $true)][string]$ExpectedFirstInstall
    )
    $Valid = [int64]$Snapshot.Uid -eq $ExpectedUid
    $Valid = $Valid -and [string]$Snapshot.VersionName -eq $ExpectedVersionName
    $Valid = $Valid -and [int64]$Snapshot.VersionCode -eq $ExpectedVersionCode
    $Valid = $Valid -and [string]$Snapshot.FirstInstall -eq $ExpectedFirstInstall
    $Valid = $Valid -and [string]$Snapshot.DataDir -eq "/data/user/0/$PackageName"
    return $Valid
}

$Anna = $ParentState.preSnapshots.Anna
$Zhenya = $ParentState.preSnapshots.Zhenya
$Stas = $ParentState.preSnapshots.Stas
if (-not (Test-SnapshotIdentity $Anna 10425 "v11.16.15" 11016015 "2026-08-08 11:40:39")) {
    throw "Saved Anna pre-launch identity mismatch"
}
if (-not (Test-SnapshotIdentity $Zhenya 10395 "v11.16.13" 11016013 "2026-08-08 17:31:18")) {
    throw "Saved Zhenya pre-launch identity mismatch"
}
if (-not (Test-SnapshotIdentity $Stas 10387 "v11.16.13" 11016013 "2026-08-10 12:41:10")) {
    throw "Saved Stas pre-launch identity mismatch"
}

$AnnaProcessIds = @($Anna.ProcessIds)
$ZhenyaProcessIds = @($Zhenya.ProcessIds)
$StasProcessIds = @($Stas.ProcessIds)
if ($AnnaProcessIds.Count -ne 1 -or [string]$AnnaProcessIds[0] -ne "12943") {
    throw "Saved Anna pre-launch PID mismatch"
}
if ($ZhenyaProcessIds.Count -ne 1 -or [string]$ZhenyaProcessIds[0] -notmatch "^\d+$") {
    throw "Saved evidence does not prove one running Zhenya process"
}
if ($StasProcessIds.Count -ne 1 -or [string]$StasProcessIds[0] -ne "23149") {
    throw "Saved Stas pre-launch PID mismatch"
}

$Analysis = [ordered]@{
    schema = 1
    purpose = "saved-only analysis of pre-action r4.4 mixed Zhenya launch1 stop"
    outcome = "PASS_FROM_IMMUTABLE_EVIDENCE"
    completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    parentState = $ParentStatePath
    parentStateSha256 = $ParentStateHash
    parentEvidenceDirectory = $ParentEvidenceDir
    parentEvidenceSha256 = $EvidenceHashes
    evidenceFileCount = $ActualEvidenceNames.Count
    anna = $Anna
    zhenya = $Zhenya
    stas = $Stas
    zhenyaObservedProcessId = [string]$ZhenyaProcessIds[0]
    launchCommandEvidenceExists = $false
    launchStarted = $false
    adbCommandsUsedByAnalyzer = $false
    phonesChangedByLaunch1 = $false
    userPayloadPublished = $false
    automaticRetry = $false
}
$Analysis | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $AnalysisStatePath -Encoding UTF8
$AnalysisHash = (Get-FileHash -LiteralPath $AnalysisStatePath -Algorithm SHA256).Hash

Write-Host "R4.4 MIXED ZHENYA LAUNCH1 SAVED ANALYSIS PASS"
Write-Host "Analysis state:       $AnalysisStatePath"
Write-Host "Analysis SHA256:      $AnalysisHash"
Write-Host "Evidence files:       $($ActualEvidenceNames.Count)"
Write-Host "Anna PID:             $($AnnaProcessIds[0])"
Write-Host "Zhenya observed PID:  $($ZhenyaProcessIds[0])"
Write-Host "Stas PID:             $($StasProcessIds[0])"
Write-Host "Launch command:       False"
Write-Host "ADB commands:         0"
Write-Host "Phone changes:        False"
