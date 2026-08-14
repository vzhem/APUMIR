$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ApkPath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-build2.apk"
$ValidationStatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-apk-validation-recovery.json"
$PreinstallStatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-preinstall2.json"
$StatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-install.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-install-evidence"

$ExpectedApkSize = 22599180
$ExpectedApkHash = "5A26728BE78941C7D0CA6FE8FBE24AD81679A57F60FE87B35999014F3D7C03BA"
$ExpectedCertHash = "F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7"
$ExpectedPreinstallStateHash = "F57C880884CE7AFDD177393DF5BA879372CD2BF3C0E04998B89795D33B90C575"
$TargetVersionName = "v11.16.13"
$TargetVersionCode = 11016013

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
    throw "V11.16.13 INSTALL ALREADY COMPLETED OR ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "V11.16.13 INSTALL EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
}

foreach ($RequiredPath in @($Adb, $ApkPath, $ValidationStatePath, $PreinstallStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required file missing: $RequiredPath"
    }
}

$PreinstallStateHash = (Get-FileHash -LiteralPath $PreinstallStatePath -Algorithm SHA256).Hash
if ($PreinstallStateHash -ne $ExpectedPreinstallStateHash) {
    throw "Preinstall state hash mismatch: $PreinstallStateHash"
}

$PreinstallState = Get-Content -LiteralPath $PreinstallStatePath -Raw | ConvertFrom-Json
if ($PreinstallState.Outcome -ne "PASS" -or $PreinstallState.Installed -ne $false) {
    throw "Preinstall state is not a clean PASS"
}

$Validation = Get-Content -LiteralPath $ValidationStatePath -Raw | ConvertFrom-Json
if ($Validation.Outcome -ne "PASS" -or
    $Validation.VersionName -ne $TargetVersionName -or
    $Validation.VersionCode -ne $TargetVersionCode -or
    $Validation.SignerCertificateSha256 -ne $ExpectedCertHash) {
    throw "APK validation identity mismatch"
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

function Get-DeviceSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Device,

        [Parameter(Mandatory = $true)]
        [string]$Phase
    )

    $SafeName = $Device.Name.ToLowerInvariant()
    $SafePhase = $Phase.ToLowerInvariant()

    $UidResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "shell", "cmd", "package", "list", "packages", "-U",
            "com.vladimir.messenger"
        ) `
        -Label ("{0}-{1}-package-uid" -f $SafeName, $SafePhase)

    if ($UidResult.ExitCode -ne 0) {
        throw "Package UID query failed: $($Device.Name), phase=$Phase"
    }

    $PackageLine = $UidResult.StdoutLines -join ""
    $UidMatch = [regex]::Match($PackageLine.Trim(), "uid:(\d+)")
    if (-not $UidMatch.Success) {
        throw "Package UID missing: $($Device.Name), phase=$Phase"
    }

    $DumpResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "shell", "dumpsys", "package", "com.vladimir.messenger"
        ) `
        -Label ("{0}-{1}-package-dump" -f $SafeName, $SafePhase)

    if ($DumpResult.ExitCode -ne 0) {
        throw "Package dump failed: $($Device.Name), phase=$Phase"
    }

    $PackageDump = $DumpResult.StdoutLines -join "`n"
    $VersionCodeMatch = [regex]::Match($PackageDump, "versionCode=(\d+)")
    $VersionNameMatch = [regex]::Match($PackageDump, "versionName=([^\s\r\n]+)")
    $FirstInstallMatch = [regex]::Match($PackageDump, "firstInstallTime=([^\r\n]+)")
    $LastUpdateMatch = [regex]::Match($PackageDump, "lastUpdateTime=([^\r\n]+)")
    $DataDirMatch = [regex]::Match($PackageDump, "dataDir=([^\s\r\n]+)")

    if (-not $VersionCodeMatch.Success -or
        -not $VersionNameMatch.Success -or
        -not $FirstInstallMatch.Success -or
        -not $DataDirMatch.Success) {
        throw "Required package fields missing: $($Device.Name), phase=$Phase"
    }

    $PidResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "shell", "pidof", "com.vladimir.messenger"
        ) `
        -Label ("{0}-{1}-pidof" -f $SafeName, $SafePhase)

    if ($PidResult.ExitCode -notin @(0, 1)) {
        throw "Unexpected pidof exit: $($Device.Name), phase=$Phase, exit=$($PidResult.ExitCode)"
    }

    $ProcessIds = @(
        $PidResult.StdoutLines |
            ForEach-Object { $_ -split "\s+" } |
            Where-Object { $_ -match "^\d+$" }
    )

    if ($PidResult.ExitCode -eq 0 -and $ProcessIds.Count -lt 1) {
        throw "pidof exit 0 without PID: $($Device.Name), phase=$Phase"
    }
    if ($PidResult.ExitCode -eq 1 -and $ProcessIds.Count -ne 0) {
        throw "pidof exit 1 with PID output: $($Device.Name), phase=$Phase"
    }

    return [pscustomobject]@{
        AppUid = [int]$UidMatch.Groups[1].Value
        VersionName = $VersionNameMatch.Groups[1].Value.Trim()
        VersionCode = [int64]$VersionCodeMatch.Groups[1].Value
        FirstInstallTime = $FirstInstallMatch.Groups[1].Value.Trim()
        LastUpdateTime = if ($LastUpdateMatch.Success) {
            $LastUpdateMatch.Groups[1].Value.Trim()
        } else {
            $null
        }
        DataDir = $DataDirMatch.Groups[1].Value.Trim()
        ProcessRunning = ($ProcessIds.Count -gt 0)
        ProcessIds = @($ProcessIds)
        PidofExitCode = $PidResult.ExitCode
    }
}

$Outcome = "FAIL_DO_NOT_RETRY_AUTOMATICALLY"
$Failure = $null
$InstallStarted = $false
$PreflightResults = [System.Collections.Generic.List[object]]::new()
$InstallResults = [System.Collections.Generic.List[object]]::new()

try {
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

    # Complete all prechecks before the first phone-changing command.
    foreach ($Device in $Devices) {
        $Pre = Get-DeviceSnapshot -Device $Device -Phase "pre"

        if ($Pre.AppUid -ne $Device.ExpectedUid) {
            throw "Preinstall UID mismatch: $($Device.Name)"
        }
        if ($Pre.VersionName -ne "v11.16.12" -or $Pre.VersionCode -ne 11016012) {
            throw "Unexpected preinstall version: $($Device.Name), $($Pre.VersionName)/$($Pre.VersionCode)"
        }
        if ($Pre.FirstInstallTime -ne $Device.ExpectedFirstInstallTime) {
            throw "Preinstall firstInstallTime mismatch: $($Device.Name)"
        }
        if ($Pre.DataDir -ne "/data/user/0/com.vladimir.messenger") {
            throw "Preinstall dataDir mismatch: $($Device.Name), $($Pre.DataDir)"
        }

        $PreflightResults.Add([pscustomobject]@{
            Name = $Device.Name
            Serial = $Device.Serial
            Snapshot = $Pre
        })
    }

    foreach ($Device in $Devices) {
        $SafeName = $Device.Name.ToLowerInvariant()
        $PreRecord = @(
            $PreflightResults | Where-Object { $_.Name -eq $Device.Name }
        )[0]

        $InstallStarted = $true
        $InstallResult = Invoke-CapturedNative `
            -FilePath $Adb `
            -ArgumentList @("-s", $Device.Serial, "install", "-r", $ApkPath) `
            -Label ("{0}-install" -f $SafeName)

        $InstallLines = @($InstallResult.StdoutLines) + @($InstallResult.StderrLines)
        $InstallText = $InstallLines -join "`n"
        $SuccessCount = @(
            $InstallLines | Where-Object { $_.Trim() -eq "Success" }
        ).Count

        if ($InstallResult.ExitCode -ne 0 -or $SuccessCount -ne 1) {
            $InstallResults.Add([pscustomobject]@{
                Name = $Device.Name
                Serial = $Device.Serial
                Pre = $PreRecord.Snapshot
                InstallExitCode = $InstallResult.ExitCode
                InstallSuccessCount = $SuccessCount
                InstallStdoutPath = $InstallResult.StdoutPath
                InstallStderrPath = $InstallResult.StderrPath
                Post = $null
                Verified = $false
            })
            throw "adb install failed: $($Device.Name), exit=$($InstallResult.ExitCode), success=$SuccessCount, output=$InstallText"
        }

        $Post = Get-DeviceSnapshot -Device $Device -Phase "post"
        $Verified = $Post.AppUid -eq $Device.ExpectedUid -and
            $Post.VersionName -eq $TargetVersionName -and
            $Post.VersionCode -eq $TargetVersionCode -and
            $Post.FirstInstallTime -eq $Device.ExpectedFirstInstallTime -and
            $Post.DataDir -eq "/data/user/0/com.vladimir.messenger" -and
            -not $Post.ProcessRunning

        $InstallResults.Add([pscustomobject]@{
            Name = $Device.Name
            Serial = $Device.Serial
            Pre = $PreRecord.Snapshot
            InstallExitCode = $InstallResult.ExitCode
            InstallSuccessCount = $SuccessCount
            InstallStdoutPath = $InstallResult.StdoutPath
            InstallStderrPath = $InstallResult.StderrPath
            Post = $Post
            Verified = $Verified
        })

        if (-not $Verified) {
            throw "Post-install identity/process gate failed: $($Device.Name)"
        }
    }

    if ($InstallResults.Count -ne 3 -or @($InstallResults | Where-Object { -not $_.Verified }).Count -ne 0) {
        throw "Install verification did not pass 3/3"
    }

    $Outcome = "PASS"
}
catch {
    $Failure = $_.Exception.Message
    throw
}
finally {
    $VerifiedCount = @($InstallResults | Where-Object { $_.Verified }).Count

    $State = [ordered]@{
        Schema = 1
        Purpose = "guarded data-preserving v11.16.13 install"
        Outcome = $Outcome
        Failure = $Failure
        CompletedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        UserApprovedInstall = $true
        ScriptPath = $PSCommandPath
        ScriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        ApkPath = $ApkPath
        ApkSize = $ApkFile.Length
        ApkSha256 = $ApkHash
        TargetVersionName = $TargetVersionName
        TargetVersionCode = $TargetVersionCode
        SignerCertificateSha256 = $ExpectedCertHash
        ValidationState = $ValidationStatePath
        ValidationStateSha256 = (Get-FileHash -LiteralPath $ValidationStatePath -Algorithm SHA256).Hash
        PreinstallState = $PreinstallStatePath
        PreinstallStateSha256 = $PreinstallStateHash
        EvidenceDirectory = $EvidenceDir
        InstallStarted = $InstallStarted
        VerifiedInstallCount = $VerifiedCount
        PreflightDevices = @($PreflightResults)
        InstallDevices = @($InstallResults)
        UsedAdbInstallReplace = $InstallStarted
        Uninstalled = $false
        DataCleared = $false
        ForceStopped = $false
        Launched = $false
        LogcatCleared = $false
        NetworkChanged = $false
        PublicTrafficSent = $false
    }

    $State | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:                $Outcome"
    Write-Host "State:                  $StatePath"
    Write-Host "State SHA256:           $StateHash"
    Write-Host "Install started:        $InstallStarted"
    Write-Host "Verified install count: $VerifiedCount"
    Write-Host "Target version:         $TargetVersionName / $TargetVersionCode"
    Write-Host "Force-stop used:        False"
    Write-Host "Launch used:            False"
    Write-Host "Data clear/uninstall:   False"
}

if ($Outcome -ne "PASS") {
    throw "Install did not pass; see immutable state: $StatePath"
}

Write-Host ""
Write-Host "INSTALL V11.16.13 PASS 3/3"
$InstallResults | ForEach-Object {
    Write-Host ("{0}: UID {1}->{2}; version {3}/{4}; firstInstall preserved={5}; dataDir preserved={6}; postProcessRunning={7}" -f
        $_.Name,
        $_.Pre.AppUid,
        $_.Post.AppUid,
        $_.Post.VersionName,
        $_.Post.VersionCode,
        ($_.Pre.FirstInstallTime -eq $_.Post.FirstInstallTime),
        ($_.Pre.DataDir -eq $_.Post.DataDir),
        $_.Post.ProcessRunning)
}
