$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedApplicationCommit = "0181b3496cde81477a01e49f8d9977d7c325a2ca"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$ExpectedNativeSize = 7248576
$ExpectedNativeHash = "E6C34E86F18D9F63B9A641E3FD9FAFD67D5F1B7101729B2CB3DF25163380095B"
$Runtime3StatePath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-anna-runtime3.json"
$ExpectedRuntime3StateHash = "3375CA6B351D82CA4B5B4B2C6E2961E7CC79A9085437A8042486599710CCDC3B"
$StatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-preflight1.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-preflight1-evidence"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$PackageName = "com.vladimir.messenger"

$Phones = @(
    [pscustomobject]@{
        Name = "Anna"
        Serial = "AUYF6R5923006121"
        ExpectedUid = 10425
        ExpectedVersionName = "v11.16.15"
        ExpectedVersionCode = 11016015
        ExpectedFirstInstall = "2026-08-08 11:40:39"
    },
    [pscustomobject]@{
        Name = "Zhenya"
        Serial = "3B665800EES00000"
        ExpectedUid = 10395
        ExpectedVersionName = "v11.16.13"
        ExpectedVersionCode = 11016013
        ExpectedFirstInstall = "2026-08-08 17:31:18"
    },
    [pscustomobject]@{
        Name = "Stas"
        Serial = "11567254BK001192"
        ExpectedUid = 10387
        ExpectedVersionName = "v11.16.13"
        ExpectedVersionCode = 11016013
        ExpectedFirstInstall = "2026-08-10 12:41:10"
    }
)

if (Test-Path -LiteralPath $StatePath) {
    throw "R4.4 MIXED PREFLIGHT1 ALREADY ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "R4.4 MIXED PREFLIGHT1 EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
}
foreach ($RequiredPath in @($Adb, $Runtime3StatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required mixed-version preflight input missing: $RequiredPath"
    }
}

$Runtime3StateHash = (Get-FileHash -LiteralPath $Runtime3StatePath -Algorithm SHA256).Hash
if ($Runtime3StateHash -ne $ExpectedRuntime3StateHash) {
    throw "Runtime3 state hash mismatch: $Runtime3StateHash"
}
$Runtime3State = Get-Content -LiteralPath $Runtime3StatePath -Raw | ConvertFrom-Json
$Runtime3Valid = $Runtime3State.outcome -eq "PASS"
$Runtime3Valid = $Runtime3Valid -and $Runtime3State.installStarted -eq $false
$Runtime3Valid = $Runtime3Valid -and $Runtime3State.launchStarted -eq $true
$Runtime3Valid = $Runtime3Valid -and [int64]$Runtime3State.annaFinal.VersionCode -eq 11016015
$Runtime3Valid = $Runtime3Valid -and [string]$Runtime3State.annaNewProcessId -eq "12943"
$Runtime3Valid = $Runtime3Valid -and $Runtime3State.zhenyaTouched -eq $false
$Runtime3Valid = $Runtime3Valid -and $Runtime3State.stasTouched -eq $false
$Runtime3Valid = $Runtime3Valid -and $Runtime3State.userPayloadPublished -eq $false
$Runtime3Valid = $Runtime3Valid -and $Runtime3State.automaticRetry -eq $false
if (-not $Runtime3Valid) {
    throw "Runtime3 state does not prove the exact Anna r4.4 PASS"
}

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $CurrentBranch -ne $ExpectedBranch) {
    throw "Wrong branch: expected=$ExpectedBranch actual=$CurrentBranch"
}
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Cannot resolve Windows HEAD"
}
& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) {
    throw "Application source differs from exact r4.4 integration source"
}

$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedBefore = @($StatusBefore | Where-Object { $_ -ne " M $GeneratedSoRelative" })
if ($UnexpectedBefore.Count -gt 0) {
    throw "Unexpected preflight worktree changes: $($UnexpectedBefore -join '; ')"
}
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$NativeFile = Get-Item -LiteralPath $SoPath
$NativeHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($NativeFile.Length -ne $ExpectedNativeSize -or $NativeHash -ne $ExpectedNativeHash) {
    throw "Dual integration native mismatch: size=$($NativeFile.Length) hash=$NativeHash"
}

New-Item -ItemType Directory -Path $EvidenceDir | Out-Null

function Invoke-Captured {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $StandardOutputPath = Join-Path $EvidenceDir "$Label.stdout.log"
    $StandardErrorPath = Join-Path $EvidenceDir "$Label.stderr.log"
    $NativeProcess = Start-Process `
        -FilePath $File `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $StandardOutputPath `
        -RedirectStandardError $StandardErrorPath `
        -PassThru
    if (-not $NativeProcess.WaitForExit(120000)) {
        throw "$Label process $($NativeProcess.Id) timed out"
    }
    $NativeProcess.WaitForExit()
    $NativeProcess.Refresh()
    $RawExitCode = $NativeProcess.ExitCode
    $ExitCodeAvailable = $null -ne $RawExitCode
    $NormalizedExitCode = $null
    if ($ExitCodeAvailable) {
        $NormalizedExitCode = [int]$RawExitCode
    }
    return [pscustomobject]@{
        ExitCodeAvailable = $ExitCodeAvailable
        ExitCode = $NormalizedExitCode
        StandardOutput = @(Get-Content -LiteralPath $StandardOutputPath)
        StandardError = @(Get-Content -LiteralPath $StandardErrorPath)
        StandardOutputPath = $StandardOutputPath
        StandardErrorPath = $StandardErrorPath
    }
}

function Get-PhoneSnapshot {
    param([Parameter(Mandatory = $true)][object]$Phone)

    $SafeName = $Phone.Name.ToLowerInvariant()
    $UidResult = Invoke-Captured $Adb @(
        "-s", $Phone.Serial, "shell", "cmd", "package", "list", "packages", "-U", $PackageName
    ) "$SafeName-uid"
    $PackageResult = Invoke-Captured $Adb @(
        "-s", $Phone.Serial, "shell", "dumpsys", "package", $PackageName
    ) "$SafeName-package"
    $ProcessResult = Invoke-Captured $Adb @(
        "-s", $Phone.Serial, "shell", "pidof", $PackageName
    ) "$SafeName-process"

    $StderrCount = $UidResult.StandardError.Count
    $StderrCount += $PackageResult.StandardError.Count
    $StderrCount += $ProcessResult.StandardError.Count
    if ($StderrCount -ne 0) {
        throw "$($Phone.Name) snapshot returned stderr"
    }
    if ($UidResult.ExitCodeAvailable -and $UidResult.ExitCode -ne 0) {
        throw "$($Phone.Name) UID command failed"
    }
    if ($PackageResult.ExitCodeAvailable -and $PackageResult.ExitCode -ne 0) {
        throw "$($Phone.Name) package command failed"
    }
    if ($ProcessResult.ExitCodeAvailable -and $ProcessResult.ExitCode -notin @(0, 1)) {
        throw "$($Phone.Name) process command failed"
    }

    $UidText = $UidResult.StandardOutput -join ""
    $PackageText = $PackageResult.StandardOutput -join "`n"
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
        throw "$($Phone.Name) snapshot parse failed"
    }

    $ProcessText = ($ProcessResult.StandardOutput -join " ").Trim()
    $ProcessIds = @()
    if (-not [string]::IsNullOrWhiteSpace($ProcessText)) {
        $ProcessTokens = @($ProcessText -split "\s+")
        $InvalidTokens = @($ProcessTokens | Where-Object { $_ -notmatch "^\d+$" })
        if ($InvalidTokens.Count -ne 0) {
            throw "$($Phone.Name) process output parse failed"
        }
        $ProcessIds = @($ProcessTokens)
    }
    if (
        $ProcessResult.ExitCodeAvailable -and
        $ProcessResult.ExitCode -eq 1 -and
        $ProcessIds.Count -ne 0
    ) {
        throw "$($Phone.Name) pidof exit/output mismatch"
    }

    return [pscustomobject]@{
        Name = $Phone.Name
        Serial = $Phone.Serial
        Uid = [int64]$UidMatch.Groups[1].Value
        VersionName = $VersionNameMatch.Groups[1].Value.Trim()
        VersionCode = [int64]$VersionCodeMatch.Groups[1].Value
        FirstInstall = $FirstInstallMatch.Groups[1].Value.Trim()
        DataDir = $DataDirMatch.Groups[1].Value.Trim()
        ProcessIds = @($ProcessIds)
        Running = $ProcessIds.Count -gt 0
        ProcessExitCodeAvailable = $ProcessResult.ExitCodeAvailable
        ProcessExitCode = $ProcessResult.ExitCode
    }
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$Snapshots = [ordered]@{}
$ConnectedPhones = 0

try {
    $DevicesResult = Invoke-Captured $Adb @("devices") "adb-devices"
    if ($DevicesResult.ExitCodeAvailable -and $DevicesResult.ExitCode -ne 0) {
        throw "adb devices failed"
    }
    $ConnectedLines = @(
        $DevicesResult.StandardOutput |
            Select-Object -Skip 1 |
            Where-Object { $_ -match "\S" }
    )
    foreach ($Phone in $Phones) {
        $DevicePattern = "^{0}\s+device$" -f [regex]::Escape($Phone.Serial)
        $DeviceCount = @($ConnectedLines | Where-Object { $_ -match $DevicePattern }).Count
        if ($DeviceCount -ne 1) {
            throw "$($Phone.Name) is not connected exactly once as an authorized device"
        }
        $ConnectedPhones++
    }

    foreach ($Phone in $Phones) {
        $Snapshot = Get-PhoneSnapshot $Phone
        $IdentityValid = $Snapshot.Uid -eq $Phone.ExpectedUid
        $IdentityValid = $IdentityValid -and $Snapshot.VersionName -eq $Phone.ExpectedVersionName
        $IdentityValid = $IdentityValid -and $Snapshot.VersionCode -eq $Phone.ExpectedVersionCode
        $IdentityValid = $IdentityValid -and $Snapshot.FirstInstall -eq $Phone.ExpectedFirstInstall
        $IdentityValid = $IdentityValid -and $Snapshot.DataDir -eq "/data/user/0/$PackageName"
        $IdentityValid = $IdentityValid -and $Snapshot.ProcessIds.Count -eq 1
        if (-not $IdentityValid) {
            throw "$($Phone.Name) mixed-version identity/process gate failed"
        }
        $Snapshots[$Phone.Name] = $Snapshot
    }

    $Outcome = "PASS"
}
catch {
    $Failure = $_.Exception.Message
    throw
}
finally {
    $State = [ordered]@{
        schema = 1
        purpose = "read-only r4.4 mixed v11.16.15/v11.16.13 three-phone preflight1"
        outcome = $Outcome
        failure = $Failure
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        applicationCommit = $ExpectedApplicationCommit
        windowsHead = $CurrentHead
        runtime3State = $Runtime3StatePath
        runtime3StateSha256 = $Runtime3StateHash
        connectedPhones = $ConnectedPhones
        expectedPhones = @("Anna", "Zhenya", "Stas")
        snapshots = $Snapshots
        evidenceDirectory = $EvidenceDir
        adbReadOnly = $true
        installed = $false
        launched = $false
        forceStopped = $false
        logcatCleared = $false
        networkChanged = $false
        userPayloadPublished = $false
        automaticRetry = $false
    }
    $State | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:              $Outcome"
    Write-Host "State:                $StatePath"
    Write-Host "State SHA256:         $StateHash"
    Write-Host "Connected phones:     $ConnectedPhones / 3"
    foreach ($PhoneName in @("Anna", "Zhenya", "Stas")) {
        if ($Snapshots.Contains($PhoneName)) {
            $Snapshot = $Snapshots[$PhoneName]
            Write-Host ("{0}: {1}/{2}, PID={3}" -f $PhoneName, $Snapshot.VersionName, $Snapshot.VersionCode, ($Snapshot.ProcessIds -join ","))
        }
    }
    Write-Host "Phone changes:        False"
    Write-Host "User payload:         False"
}

if ($Outcome -ne "PASS") {
    throw "r4.4 mixed-version preflight1 did not pass; see state: $StatePath"
}

Write-Host "R4.4 MIXED-VERSION THREE-PHONE PREFLIGHT1 PASS"
