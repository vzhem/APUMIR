$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$ParentStatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-preflight1.json"
$ExpectedParentStateHash = "FBD7A04551572762E2FD1FC95A39E10723DE21F7D06ED8F43C1BE357A78E44CE"
$ParentEvidenceDir = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-preflight1-evidence"
$AnalysisStatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-preflight1-analysis.json"
$PackageName = "com.vladimir.messenger"

if (Test-Path -LiteralPath $AnalysisStatePath) {
    throw "R4.4 MIXED PREFLIGHT1 ANALYSIS ALREADY EXISTS - DO NOT REPEAT: $AnalysisStatePath"
}
if (-not (Test-Path -LiteralPath $ParentStatePath -PathType Leaf)) {
    throw "Parent mixed preflight1 state missing: $ParentStatePath"
}
if (-not (Test-Path -LiteralPath $ParentEvidenceDir -PathType Container)) {
    throw "Parent mixed preflight1 evidence missing: $ParentEvidenceDir"
}

$ParentStateHash = (Get-FileHash -LiteralPath $ParentStatePath -Algorithm SHA256).Hash
if ($ParentStateHash -ne $ExpectedParentStateHash) {
    throw "Parent mixed preflight1 state hash mismatch: $ParentStateHash"
}
$ParentState = Get-Content -LiteralPath $ParentStatePath -Raw | ConvertFrom-Json
$ParentValid = $ParentState.outcome -eq "INCOMPLETE_DO_NOT_REPEAT"
$ParentValid = $ParentValid -and $ParentState.failure -eq "Zhenya mixed-version identity/process gate failed"
$ParentValid = $ParentValid -and [int]$ParentState.connectedPhones -eq 3
$ParentValid = $ParentValid -and $ParentState.adbReadOnly -eq $true
$ParentValid = $ParentValid -and $ParentState.installed -eq $false
$ParentValid = $ParentValid -and $ParentState.launched -eq $false
$ParentValid = $ParentValid -and $ParentState.forceStopped -eq $false
$ParentValid = $ParentValid -and $ParentState.logcatCleared -eq $false
$ParentValid = $ParentValid -and $ParentState.networkChanged -eq $false
$ParentValid = $ParentValid -and $ParentState.userPayloadPublished -eq $false
$ParentValid = $ParentValid -and $ParentState.automaticRetry -eq $false
if (-not $ParentValid) {
    throw "Parent state does not prove the exact read-only Zhenya-gate stop"
}

$RequiredEvidenceNames = @(
    "adb-devices.stdout.log",
    "adb-devices.stderr.log",
    "anna-uid.stdout.log",
    "anna-uid.stderr.log",
    "anna-package.stdout.log",
    "anna-package.stderr.log",
    "anna-process.stdout.log",
    "anna-process.stderr.log",
    "zhenya-uid.stdout.log",
    "zhenya-uid.stderr.log",
    "zhenya-package.stdout.log",
    "zhenya-package.stderr.log",
    "zhenya-process.stdout.log",
    "zhenya-process.stderr.log"
)
$EvidenceHashes = [ordered]@{}
foreach ($EvidenceName in $RequiredEvidenceNames) {
    $EvidencePath = Join-Path $ParentEvidenceDir $EvidenceName
    if (-not (Test-Path -LiteralPath $EvidencePath -PathType Leaf)) {
        throw "Required saved evidence missing: $EvidencePath"
    }
    $EvidenceHashes[$EvidenceName] = (
        Get-FileHash -LiteralPath $EvidencePath -Algorithm SHA256
    ).Hash
}

$UnexpectedStasEvidence = @(
    Get-ChildItem -LiteralPath $ParentEvidenceDir -File |
        Where-Object { $_.Name -like "stas-*" }
)
if ($UnexpectedStasEvidence.Count -ne 0) {
    throw "Unexpected Stas evidence exists despite the recorded pre-Stas stop"
}

function Read-SavedText {
    param([Parameter(Mandatory = $true)][string]$Name)
    $Path = Join-Path $ParentEvidenceDir $Name
    return @(Get-Content -LiteralPath $Path) -join "`n"
}

function Get-SavedSnapshot {
    param([Parameter(Mandatory = $true)][string]$SafeName)

    foreach ($Kind in @("uid", "package", "process")) {
        $StderrPath = Join-Path $ParentEvidenceDir "$SafeName-$Kind.stderr.log"
        if ((Get-Item -LiteralPath $StderrPath).Length -ne 0) {
            throw "$SafeName saved $Kind stderr is not empty"
        }
    }

    $UidText = Read-SavedText "$SafeName-uid.stdout.log"
    $PackageText = Read-SavedText "$SafeName-package.stdout.log"
    $ProcessText = (Read-SavedText "$SafeName-process.stdout.log").Trim()

    $UidMatch = [regex]::Match($UidText, "uid:(\d+)")
    $VersionNameMatch = [regex]::Match($PackageText, "versionName=([^\s\r\n]+)")
    $VersionCodeMatch = [regex]::Match($PackageText, "versionCode=(\d+)")
    $FirstInstallMatch = [regex]::Match($PackageText, "firstInstallTime=([^\r\n]+)")
    $DataDirMatch = [regex]::Match($PackageText, "dataDir=([^\s\r\n]+)")
    $Parsed = $UidMatch.Success -and $VersionNameMatch.Success
    $Parsed = $Parsed -and $VersionCodeMatch.Success
    $Parsed = $Parsed -and $FirstInstallMatch.Success
    $Parsed = $Parsed -and $DataDirMatch.Success
    if (-not $Parsed) {
        throw "$SafeName saved snapshot parse failed"
    }

    $ProcessIds = @()
    if (-not [string]::IsNullOrWhiteSpace($ProcessText)) {
        $ProcessTokens = @($ProcessText -split "\s+")
        $InvalidTokens = @($ProcessTokens | Where-Object { $_ -notmatch "^\d+$" })
        if ($InvalidTokens.Count -ne 0) {
            throw "$SafeName saved process output parse failed"
        }
        $ProcessIds = @($ProcessTokens)
    }

    return [pscustomobject]@{
        Uid = [int64]$UidMatch.Groups[1].Value
        VersionName = $VersionNameMatch.Groups[1].Value.Trim()
        VersionCode = [int64]$VersionCodeMatch.Groups[1].Value
        FirstInstall = $FirstInstallMatch.Groups[1].Value.Trim()
        DataDir = $DataDirMatch.Groups[1].Value.Trim()
        ProcessIds = @($ProcessIds)
        Running = $ProcessIds.Count -gt 0
    }
}

$Anna = Get-SavedSnapshot "anna"
$AnnaValid = $Anna.Uid -eq 10425
$AnnaValid = $AnnaValid -and $Anna.VersionName -eq "v11.16.15"
$AnnaValid = $AnnaValid -and $Anna.VersionCode -eq 11016015
$AnnaValid = $AnnaValid -and $Anna.FirstInstall -eq "2026-08-08 11:40:39"
$AnnaValid = $AnnaValid -and $Anna.DataDir -eq "/data/user/0/$PackageName"
$AnnaValid = $AnnaValid -and $Anna.ProcessIds.Count -eq 1
$AnnaValid = $AnnaValid -and [string]$Anna.ProcessIds[0] -eq "12943"
if (-not $AnnaValid) {
    throw "Saved Anna evidence does not match the parent PASS snapshot"
}

$Zhenya = Get-SavedSnapshot "zhenya"
$ZhenyaMismatches = [System.Collections.Generic.List[string]]::new()
if ($Zhenya.Uid -ne 10395) {
    $ZhenyaMismatches.Add("Uid expected=10395 actual=$($Zhenya.Uid)")
}
if ($Zhenya.VersionName -ne "v11.16.13") {
    $ZhenyaMismatches.Add("VersionName expected=v11.16.13 actual=$($Zhenya.VersionName)")
}
if ($Zhenya.VersionCode -ne 11016013) {
    $ZhenyaMismatches.Add("VersionCode expected=11016013 actual=$($Zhenya.VersionCode)")
}
if ($Zhenya.FirstInstall -ne "2026-08-08 17:31:18") {
    $ZhenyaMismatches.Add("FirstInstall expected=2026-08-08 17:31:18 actual=$($Zhenya.FirstInstall)")
}
if ($Zhenya.DataDir -ne "/data/user/0/$PackageName") {
    $ZhenyaMismatches.Add("DataDir expected=/data/user/0/$PackageName actual=$($Zhenya.DataDir)")
}
if ($Zhenya.ProcessIds.Count -ne 1) {
    $ZhenyaMismatches.Add("ProcessCount expected=1 actual=$($Zhenya.ProcessIds.Count)")
}
if ($ZhenyaMismatches.Count -eq 0) {
    throw "Saved Zhenya evidence has no mismatch despite parent gate failure"
}

$Analysis = [ordered]@{
    schema = 1
    purpose = "saved-only analysis of r4.4 mixed preflight1 Zhenya gate"
    outcome = "PASS_FROM_IMMUTABLE_EVIDENCE"
    completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    parentState = $ParentStatePath
    parentStateSha256 = $ParentStateHash
    parentEvidenceDirectory = $ParentEvidenceDir
    parentEvidenceSha256 = $EvidenceHashes
    anna = $Anna
    zhenya = $Zhenya
    zhenyaMismatches = @($ZhenyaMismatches)
    stasSnapshotAttempted = $false
    adbCommandsUsed = $false
    phonesChanged = $false
    userPayloadPublished = $false
    automaticRetry = $false
}
$Analysis | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $AnalysisStatePath -Encoding UTF8
$AnalysisHash = (Get-FileHash -LiteralPath $AnalysisStatePath -Algorithm SHA256).Hash

Write-Host "R4.4 MIXED PREFLIGHT1 SAVED ANALYSIS PASS"
Write-Host "Analysis state:  $AnalysisStatePath"
Write-Host "Analysis SHA256: $AnalysisHash"
Write-Host "Anna:            $($Anna.VersionName)/$($Anna.VersionCode), PID=$($Anna.ProcessIds -join ',')"
Write-Host "Zhenya:          $($Zhenya.VersionName)/$($Zhenya.VersionCode), PID=$($Zhenya.ProcessIds -join ',')"
Write-Host "Zhenya mismatch: $($ZhenyaMismatches -join '; ')"
Write-Host "Stas attempted:  False"
Write-Host "ADB commands:    0"
Write-Host "Phone changes:   False"
