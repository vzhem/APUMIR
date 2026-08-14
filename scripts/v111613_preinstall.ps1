$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ApkPath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-build2.apk"
$ValidationStatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-apk-validation-recovery.json"
$StatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-preinstall2.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-preinstall2-evidence"

$ExpectedApkSize = 22599180
$ExpectedApkHash = "5A26728BE78941C7D0CA6FE8FBE24AD81679A57F60FE87B35999014F3D7C03BA"
$ExpectedCertHash = "F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7"

$Devices = @(
    [pscustomobject]@{
        Name = "Anna"
        Serial = "AUYF6R5923006121"
        ExpectedUid = 10425
        ExpectedFirstInstallTime = "2026-08-08 11:40:39"
    },
    [pscustomobject]@{
        Name = "Zhenya"
        Serial = "3B665800EES00000"
        ExpectedUid = 10395
        ExpectedFirstInstallTime = "2026-08-08 17:31:18"
    },
    [pscustomobject]@{
        Name = "Stas"
        Serial = "11567254BK001192"
        ExpectedUid = 10387
        ExpectedFirstInstallTime = "2026-08-10 12:41:10"
    }
)

if (Test-Path -LiteralPath $StatePath) {
    throw "PREINSTALL2 ALREADY COMPLETED OR ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "PREINSTALL2 EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
}

foreach ($RequiredPath in @($Adb, $ApkPath, $ValidationStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required file missing: $RequiredPath"
    }
}

$Validation = Get-Content -LiteralPath $ValidationStatePath -Raw | ConvertFrom-Json
if ($Validation.Outcome -ne "PASS") {
    throw "APK validation state is not PASS"
}
if ($Validation.BuildRepeated -ne $false -or
    $Validation.PhonesChanged -ne $false -or
    $Validation.Installed -ne $false -or
    $Validation.Launched -ne $false) {
    throw "Unexpected effect recorded in APK validation state"
}
if ($Validation.VersionName -ne "v11.16.13" -or $Validation.VersionCode -ne 11016013) {
    throw "Validated APK version mismatch"
}
if ($Validation.SignerCertificateSha256 -ne $ExpectedCertHash) {
    throw "Validated APK signer mismatch"
}

$ApkFile = Get-Item -LiteralPath $ApkPath
$ApkHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
if ($ApkFile.Length -ne $ExpectedApkSize -or $ApkHash -ne $ExpectedApkHash) {
    throw "Authoritative APK identity mismatch"
}

New-Item -ItemType Directory -Path $EvidenceDir | Out-Null

function Invoke-CapturedNative {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$ArgumentList,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $StdoutPath = Join-Path $EvidenceDir ("{0}.stdout.log" -f $Label)
    $StderrPath = Join-Path $EvidenceDir ("{0}.stderr.log" -f $Label)

    $NativeProcess = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath `
        -Wait `
        -PassThru

    $StdoutLines = if (Test-Path -LiteralPath $StdoutPath) {
        @(Get-Content -LiteralPath $StdoutPath)
    } else {
        @()
    }
    $StderrLines = if (Test-Path -LiteralPath $StderrPath) {
        @(Get-Content -LiteralPath $StderrPath)
    } else {
        @()
    }

    return [pscustomobject]@{
        ExitCode = $NativeProcess.ExitCode
        StdoutLines = $StdoutLines
        StderrLines = $StderrLines
        StdoutPath = $StdoutPath
        StderrPath = $StderrPath
    }
}

$DevicesResult = Invoke-CapturedNative `
    -FilePath $Adb `
    -ArgumentList @("devices") `
    -Label "adb-devices"

if ($DevicesResult.ExitCode -ne 0) {
    throw "adb devices failed: exit=$($DevicesResult.ExitCode)"
}

$Connected = @(
    $DevicesResult.StdoutLines |
        Select-Object -Skip 1 |
        Where-Object { $_ -match "\S" }
)

foreach ($Device in $Devices) {
    $SerialPattern = "^{0}\s+device$" -f [regex]::Escape($Device.Serial)
    $ExactDevice = @($Connected | Where-Object { $_ -match $SerialPattern })
    if ($ExactDevice.Count -ne 1) {
        throw "$($Device.Name) is not connected exactly once as device"
    }
}

$Results = [System.Collections.Generic.List[object]]::new()

foreach ($Device in $Devices) {
    $SafeName = $Device.Name.ToLowerInvariant()

    $UidResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "shell", "cmd", "package", "list", "packages", "-U",
            "com.vladimir.messenger"
        ) `
        -Label ("{0}-package-uid" -f $SafeName)

    if ($UidResult.ExitCode -ne 0) {
        throw "Package UID query failed: $($Device.Name), exit=$($UidResult.ExitCode)"
    }

    $PackageLine = $UidResult.StdoutLines -join ""
    $UidMatch = [regex]::Match($PackageLine.Trim(), "uid:(\d+)")
    if (-not $UidMatch.Success) {
        throw "APU package UID missing: $($Device.Name)"
    }

    $AppUid = [int]$UidMatch.Groups[1].Value
    if ($AppUid -ne $Device.ExpectedUid) {
        throw "UID mismatch: $($Device.Name), expected=$($Device.ExpectedUid), actual=$AppUid"
    }

    $DumpResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "shell", "dumpsys", "package", "com.vladimir.messenger"
        ) `
        -Label ("{0}-package-dump" -f $SafeName)

    if ($DumpResult.ExitCode -ne 0) {
        throw "Package dump failed: $($Device.Name), exit=$($DumpResult.ExitCode)"
    }

    $PackageDump = $DumpResult.StdoutLines -join "`n"
    $VersionCodeMatch = [regex]::Match($PackageDump, "versionCode=(\d+)")
    $VersionNameMatch = [regex]::Match($PackageDump, "versionName=([^\s\r\n]+)")
    $FirstInstallMatch = [regex]::Match($PackageDump, "firstInstallTime=([^\r\n]+)")
    $DataDirMatch = [regex]::Match($PackageDump, "dataDir=([^\s\r\n]+)")

    if (-not $VersionCodeMatch.Success -or
        -not $VersionNameMatch.Success -or
        -not $FirstInstallMatch.Success -or
        -not $DataDirMatch.Success) {
        throw "Required package fields missing: $($Device.Name)"
    }

    $CurrentVersionCode = [int64]$VersionCodeMatch.Groups[1].Value
    $CurrentVersionName = $VersionNameMatch.Groups[1].Value.Trim()
    $FirstInstallTime = $FirstInstallMatch.Groups[1].Value.Trim()
    $DataDir = $DataDirMatch.Groups[1].Value.Trim()

    if ($CurrentVersionCode -ne 11016012 -or $CurrentVersionName -ne "v11.16.12") {
        throw "Unexpected installed version: $($Device.Name), $CurrentVersionName/$CurrentVersionCode"
    }
    if ($FirstInstallTime -ne $Device.ExpectedFirstInstallTime) {
        throw "firstInstallTime mismatch: $($Device.Name)"
    }
    if ($DataDir -ne "/data/user/0/com.vladimir.messenger") {
        throw "Unexpected dataDir: $($Device.Name), $DataDir"
    }

    $PidResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "shell", "pidof", "com.vladimir.messenger"
        ) `
        -Label ("{0}-pidof" -f $SafeName)

    if ($PidResult.ExitCode -notin @(0, 1)) {
        throw "Unexpected pidof exit: $($Device.Name), exit=$($PidResult.ExitCode)"
    }

    $ProcessIds = @(
        $PidResult.StdoutLines |
            ForEach-Object { $_ -split "\s+" } |
            Where-Object { $_ -match "^\d+$" }
    )

    if ($PidResult.ExitCode -eq 0 -and $ProcessIds.Count -lt 1) {
        throw "pidof exit 0 without PID: $($Device.Name)"
    }
    if ($PidResult.ExitCode -eq 1 -and $ProcessIds.Count -ne 0) {
        throw "pidof exit 1 with PID output: $($Device.Name)"
    }

    $Results.Add([pscustomobject]@{
        Name = $Device.Name
        Serial = $Device.Serial
        AppUid = $AppUid
        VersionName = $CurrentVersionName
        VersionCode = $CurrentVersionCode
        FirstInstallTime = $FirstInstallTime
        DataDir = $DataDir
        ProcessRunning = ($ProcessIds.Count -gt 0)
        ProcessIds = @($ProcessIds)
        PidofExitCode = $PidResult.ExitCode
    })
}

$State = [ordered]@{
    Schema = 2
    Purpose = "parser-validated read-only v11.16.13 preinstall snapshot"
    Outcome = "PASS"
    CapturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    ScriptPath = $PSCommandPath
    ScriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    ApkPath = $ApkPath
    ApkSize = $ApkFile.Length
    ApkSha256 = $ApkHash
    ApkVersionName = $Validation.VersionName
    ApkVersionCode = $Validation.VersionCode
    ApkSignerCertificateSha256 = $Validation.SignerCertificateSha256
    ValidationState = $ValidationStatePath
    ValidationStateSha256 = (Get-FileHash -LiteralPath $ValidationStatePath -Algorithm SHA256).Hash
    EvidenceDirectory = $EvidenceDir
    Devices = @($Results)
    AdbReadOnly = $true
    Installed = $false
    ForceStopped = $false
    Launched = $false
    LogcatCleared = $false
    NetworkChanged = $false
    PublicTrafficSent = $false
    PhonesChanged = $false
}

$State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StatePath -Encoding UTF8
if (-not (Test-Path -LiteralPath $StatePath)) {
    throw "Preinstall state write failed"
}
$StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

Write-Host ""
Write-Host "PREINSTALL2 READ-ONLY PASS 3/3"
Write-Host "State:        $StatePath"
Write-Host "State SHA256: $StateHash"
Write-Host "APK SHA256:   $ApkHash"
Write-Host ""

$Results |
    Select-Object Name, Serial, AppUid, VersionName, VersionCode, ProcessRunning, ProcessIds,
        FirstInstallTime, DataDir |
    Format-List

Write-Host "Install performed: False"
Write-Host "Phones changed:    False"
