$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedWindowsHead = "8cea566e50f439810e29fb1dc4ac14dc69b5fbc6"
$ExpectedApplicationCommit = "61e1580ff85aa1cfaed1f9e7a7522f1cd8e5d602"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$ExpectedNativeSize = 7263416
$ExpectedNativeHash = "27B9D4DC87CA7046D9F862F9ED153FDDD48C26E4053B620FE46986D25D1FD26C"
$IconRelative = "design/branding/app-icon/source/apu-icon-original.png"
$ExpectedIconSize = 1980451
$ExpectedIconHash = "F2638C88A3EAB243766B8F4755183C89A3E1FFCB72B45A0BBC5F3D398C83ACA9"
$ScriptRelative = "scripts/m3d_v111616_launch.ps1"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$InstallStatePath = Join-Path $env:TEMP "apu-m3d-v11.16.16-install.json"
$StatePath = Join-Path $env:TEMP "apu-m3d-v11.16.16-launch.json"
$EvidenceDir = Join-Path $env:TEMP "apu-m3d-v11.16.16-launch-evidence"
$ExpectedInstallStateHash = "59D5F8EDDBBEF2865CFFF5C48E349514502D6635C6F794CD0DBFDD6908846295"
$TargetVersionName = "v11.16.16"
$TargetVersionCode = 11016016
$EarlyWaitSeconds = 15
$LateWaitSeconds = 135

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
    throw "M3D V11.16.16 LAUNCH ALREADY COMPLETED OR ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "M3D V11.16.16 LAUNCH EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
}
foreach ($RequiredPath in @($Adb, $InstallStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required file missing: $RequiredPath"
    }
}

$InstallStateHash = (Get-FileHash -LiteralPath $InstallStatePath -Algorithm SHA256).Hash
if ($InstallStateHash -ne $ExpectedInstallStateHash) {
    throw "Install state hash mismatch: $InstallStateHash"
}

$InstallState = Get-Content -LiteralPath $InstallStatePath -Raw | ConvertFrom-Json
if ($InstallState.outcome -ne "PASS" -or
    [int]$InstallState.verifiedInstallCount -ne 3 -or
    [int]$InstallState.installCallCount -ne 3 -or
    $InstallState.applicationCommit -ne $ExpectedApplicationCommit -or
    $InstallState.targetVersionName -ne $TargetVersionName -or
    [int64]$InstallState.targetVersionCode -ne $TargetVersionCode -or
    $InstallState.uninstalled -ne $false -or
    $InstallState.dataCleared -ne $false -or
    $InstallState.forceStopped -ne $false -or
    $InstallState.launched -ne $false -or
    $InstallState.networkChanged -ne $false -or
    $InstallState.automaticRetry -ne $false) {
    throw "Install state is not a clean M3(d) v11.16.16 PASS"
}

if ($RepoRoot -notmatch '^[Cc]:\\' -or $Adb -notmatch '^[Cc]:\\') {
    throw "APU repo and ADB must use drive C"
}
$CurrentBranch = ((& git branch --show-current) -join "").Trim()
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if (
    $LASTEXITCODE -ne 0 -or
    $CurrentBranch -ne $ExpectedBranch -or
    $CurrentHead -ne $ExpectedWindowsHead
) {
    throw "Unexpected Windows branch/HEAD: $CurrentBranch / $CurrentHead"
}

$ExpectedBlobs = [ordered]@{
    "android-app/app/src/main/java/com/vladimir/messenger/data/RustBridge.kt" = "bd4c8a0ad8747ffeb77ba6e2b5fb3c70ef72b0cd"
    "android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/MessageDao.kt" = "c2ab709f0bd9aaae7442412cd9c2c226194b4e38"
    "android-app/app/src/main/java/com/vladimir/messenger/data/repository/ChatRepository.kt" = "0de777b82402f9d3441e69873c7f35028e31c0e7"
    "android-app/app/src/main/java/com/vladimir/messenger/domain/model/MessageChannel.kt" = "429c1d83d3ecf9fb8f5ab5a08fe7cb174225f36a"
    "rust-core/src/engine/core.rs" = "1b9018f0b68cfa0b4d58d3d3401d24a93ab11f1b"
    "rust-core/src/lib.rs" = "310c3327a3b68ab701baeeb971842d6a639fcf6b"
    "rust-core/src/network/mod.rs" = "c28f8395f78083a7b2e997a7d665ea8350e544c2"
    "rust-core/src/network/offline_send.rs" = "b86868d3bd043ee0c17b61b9a6bd1bab60de84c1"
}
foreach ($RelativePath in $ExpectedBlobs.Keys) {
    $FilePath = Join-Path $RepoRoot $RelativePath
    $PathArgument = "--path={0}" -f $RelativePath
    $WorkingBlob = ((& git hash-object $PathArgument -- $FilePath) -join "").Trim()
    if ($LASTEXITCODE -ne 0 -or $WorkingBlob -ne $ExpectedBlobs[$RelativePath]) {
        throw "M3(d) source mismatch before launch: $RelativePath / $WorkingBlob"
    }
}

$ExpectedStatus = @(
    " M android-app/app/src/main/java/com/vladimir/messenger/data/RustBridge.kt",
    " M android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/MessageDao.kt",
    " M android-app/app/src/main/java/com/vladimir/messenger/data/repository/ChatRepository.kt",
    " M android-app/app/src/main/java/com/vladimir/messenger/domain/model/MessageChannel.kt",
    " M $GeneratedSoRelative",
    " M rust-core/src/engine/core.rs",
    " M rust-core/src/lib.rs",
    " M rust-core/src/network/mod.rs",
    "?? $IconRelative",
    "?? rust-core/src/network/offline_send.rs",
    "?? scripts/m3d_kotlin_apk_build.ps1",
    "?? scripts/m3d_v111616_install.ps1",
    "?? $ScriptRelative"
)
$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedBefore = @($StatusBefore | Where-Object { $_ -notin $ExpectedStatus })
if ($LASTEXITCODE -ne 0 -or $UnexpectedBefore.Count -gt 0 -or $StatusBefore.Count -ne $ExpectedStatus.Count) {
    throw "Unexpected Windows worktree before launch: $($StatusBefore -join '; ')"
}

$NativePath = Join-Path $RepoRoot $GeneratedSoRelative
$NativeFile = Get-Item -LiteralPath $NativePath
$NativeHash = (Get-FileHash -LiteralPath $NativePath -Algorithm SHA256).Hash
$IconPath = Join-Path $RepoRoot $IconRelative
$IconFile = Get-Item -LiteralPath $IconPath
$IconHash = (Get-FileHash -LiteralPath $IconPath -Algorithm SHA256).Hash
if (
    $NativeFile.Length -ne $ExpectedNativeSize -or
    $NativeHash -ne $ExpectedNativeHash -or
    $IconFile.Length -ne $ExpectedIconSize -or
    $IconHash -ne $ExpectedIconHash
) {
    throw "M3(d) native/icon identity mismatch before launch"
}

New-Item -ItemType Directory -Path $EvidenceDir | Out-Null

function Invoke-CapturedNative {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Label,
        [int]$TimeoutMilliseconds = 120000
    )

    $StdoutPath = Join-Path $EvidenceDir ("{0}.stdout.log" -f $Label)
    $StderrPath = Join-Path $EvidenceDir ("{0}.stderr.log" -f $Label)
    $NativeProcess = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath `
        -PassThru

    if (-not $NativeProcess.WaitForExit($TimeoutMilliseconds)) {
        try { $NativeProcess.Kill() } catch { }
        throw "Native command timed out: $Label process=$($NativeProcess.Id)"
    }
    $NativeProcess.WaitForExit()
    $NativeProcess.Refresh()
    $RawExitCode = $NativeProcess.ExitCode
    $ExitCodeAvailable = $null -ne $RawExitCode
    $ExitCode = if ($ExitCodeAvailable) { [int]$RawExitCode } else { $null }
    $StdoutLines = if (Test-Path -LiteralPath $StdoutPath) {
        @(Get-Content -LiteralPath $StdoutPath)
    } else { @() }
    $StderrLines = if (Test-Path -LiteralPath $StderrPath) {
        @(Get-Content -LiteralPath $StderrPath)
    } else { @() }

    return [pscustomobject]@{
        ProcessId = $NativeProcess.Id
        ExitCodeAvailable = $ExitCodeAvailable
        ExitCode = $ExitCode
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

    if ($UidResult.ExitCodeAvailable -and $UidResult.ExitCode -ne 0) {
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

    if ($DumpResult.ExitCodeAvailable -and $DumpResult.ExitCode -ne 0) {
        throw "Package dump failed: $($Device.Name), phase=$Phase"
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
        throw "Required package fields missing: $($Device.Name), phase=$Phase"
    }

    $PidResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "shell", "pidof", "com.vladimir.messenger"
        ) `
        -Label ("{0}-{1}-pidof" -f $SafeName, $SafePhase)

    if ($PidResult.ExitCodeAvailable -and $PidResult.ExitCode -notin @(0, 1)) {
        throw "Unexpected pidof exit: $($Device.Name), phase=$Phase, exit=$($PidResult.ExitCode)"
    }

    $ProcessIds = @(
        $PidResult.StdoutLines |
            ForEach-Object { $_ -split "\s+" } |
            Where-Object { $_ -match "^\d+$" }
    )

    if ($PidResult.ExitCodeAvailable -and $PidResult.ExitCode -eq 0 -and $ProcessIds.Count -lt 1) {
        throw "pidof exit 0 without PID: $($Device.Name), phase=$Phase"
    }
    if ($PidResult.ExitCodeAvailable -and $PidResult.ExitCode -eq 1 -and $ProcessIds.Count -ne 0) {
        throw "pidof exit 1 with PID output: $($Device.Name), phase=$Phase"
    }

    return [pscustomobject]@{
        AppUid = [int]$UidMatch.Groups[1].Value
        VersionName = $VersionNameMatch.Groups[1].Value.Trim()
        VersionCode = [int64]$VersionCodeMatch.Groups[1].Value
        FirstInstallTime = $FirstInstallMatch.Groups[1].Value.Trim()
        DataDir = $DataDirMatch.Groups[1].Value.Trim()
        ProcessRunning = ($ProcessIds.Count -gt 0)
        ProcessIds = @($ProcessIds)
        PidofExitCodeAvailable = $PidResult.ExitCodeAvailable
        PidofExitCode = $PidResult.ExitCode
    }
}

function Get-DeviceEpoch {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Device,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $Result = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @("-s", $Device.Serial, "shell", "date", "+%s") `
        -Label $Label

    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
        throw "Device epoch query failed: $($Device.Name)"
    }

    $Value = ($Result.StdoutLines -join "").Trim()
    if ($Value -notmatch "^\d+$") {
        throw "Invalid device epoch: $($Device.Name), value=$Value"
    }
    return [int64]$Value
}

function Get-P2pSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Device,

        [Parameter(Mandatory = $true)]
        [string]$ProcessId,

        [Parameter(Mandatory = $true)]
        [int64]$LaunchEpoch,

        [Parameter(Mandatory = $true)]
        [string]$Phase
    )

    $SafeName = $Device.Name.ToLowerInvariant()
    $SafePhase = $Phase.ToLowerInvariant()
    $Result = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "logcat", "-d", "-v", "epoch", "p2p_core:V", "*:S"
        ) `
        -Label ("{0}-{1}-p2p-core" -f $SafeName, $SafePhase)

    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
        throw "p2p_core logcat failed: $($Device.Name), phase=$Phase"
    }

    $PidPattern = "^\s*\d+\.\d+\s+{0}\s+" -f [regex]::Escape($ProcessId)
    $Relevant = [System.Collections.Generic.List[string]]::new()
    foreach ($Line in $Result.StdoutLines) {
        if ($Line -notmatch $PidPattern) {
            continue
        }
        $EpochMatch = [regex]::Match($Line, "^\s*(\d+\.\d+)")
        if (-not $EpochMatch.Success) {
            continue
        }
        $Epoch = [double]::Parse(
            $EpochMatch.Groups[1].Value,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        if ($Epoch -ge [double]$LaunchEpoch) {
            $Relevant.Add($Line)
        }
    }
    return @($Relevant)
}

function Get-CrashSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Device,

        [Parameter(Mandatory = $true)]
        [string]$ProcessId,

        [Parameter(Mandatory = $true)]
        [int64]$LaunchEpoch
    )

    $SafeName = $Device.Name.ToLowerInvariant()
    $Result = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "logcat", "-d", "-v", "epoch",
            "AndroidRuntime:E", "ActivityManager:W", "libc:F", "*:S"
        ) `
        -Label ("{0}-late-crash" -f $SafeName)

    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
        throw "Crash logcat failed: $($Device.Name)"
    }

    $Relevant = [System.Collections.Generic.List[string]]::new()
    foreach ($Line in $Result.StdoutLines) {
        $EpochMatch = [regex]::Match($Line, "^\s*(\d+\.\d+)")
        if (-not $EpochMatch.Success) {
            continue
        }
        $Epoch = [double]::Parse(
            $EpochMatch.Groups[1].Value,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        if ($Epoch -lt [double]$LaunchEpoch) {
            continue
        }

        $PidPattern = "\s{0}\s" -f [regex]::Escape($ProcessId)
        $HasIdentity = $Line -match $PidPattern -or $Line -match "com\.vladimir\.messenger"
        $HasCrashSignal = $Line -match "FATAL EXCEPTION|Fatal signal|ANR in com\.vladimir\.messenger|Force finishing activity.*com\.vladimir\.messenger|Process com\.vladimir\.messenger.*has died"
        if ($HasIdentity -and $HasCrashSignal) {
            $Relevant.Add($Line)
        }
    }
    return @($Relevant)
}

function Count-LinesContaining {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$Lines,

        [Parameter(Mandatory = $true)]
        [string]$Text
    )

    return @($Lines | Where-Object { $_.Contains($Text) }).Count
}

function Get-LastLineEpoch {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$Lines
    )

    if ($Lines.Count -eq 0) {
        return $null
    }
    $Match = [regex]::Match($Lines[-1], "^\s*(\d+\.\d+)")
    if (-not $Match.Success) {
        throw "Cannot parse p2p event epoch"
    }
    return [double]::Parse(
        $Match.Groups[1].Value,
        [System.Globalization.CultureInfo]::InvariantCulture
    )
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$LaunchStarted = $false
$LaunchCallCount = 0
$PreflightResults = [System.Collections.Generic.List[object]]::new()
$LaunchResults = [System.Collections.Generic.List[object]]::new()
$AnalysisResults = [System.Collections.Generic.List[object]]::new()

try {
    $DevicesResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @("devices") `
        -Label "adb-devices"

    if ($DevicesResult.ExitCodeAvailable -and $DevicesResult.ExitCode -ne 0) {
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

    # Complete every identity/process precheck before the first launch.
    foreach ($Device in $Devices) {
        $Pre = Get-DeviceSnapshot -Device $Device -Phase "pre"
        if ($Pre.AppUid -ne $Device.ExpectedUid -or
            $Pre.VersionName -ne $TargetVersionName -or
            $Pre.VersionCode -ne $TargetVersionCode -or
            $Pre.FirstInstallTime -ne $Device.ExpectedFirstInstallTime -or
            $Pre.DataDir -ne "/data/user/0/com.vladimir.messenger" -or
            $Pre.ProcessRunning) {
            throw "Launch precheck failed: $($Device.Name)"
        }

        $LaunchEpoch = Get-DeviceEpoch `
            -Device $Device `
            -Label ("{0}-pre-launch-epoch" -f $Device.Name.ToLowerInvariant())

        $PreflightResults.Add([pscustomobject]@{
            Name = $Device.Name
            Serial = $Device.Serial
            Snapshot = $Pre
            LaunchEpoch = $LaunchEpoch
        })
    }

    foreach ($Device in $Devices) {
        $SafeName = $Device.Name.ToLowerInvariant()
        $PreRecord = @(
            $PreflightResults | Where-Object { $_.Name -eq $Device.Name }
        )[0]

        $LaunchStarted = $true
        $LaunchCallCount++
        $LaunchResult = Invoke-CapturedNative `
            -FilePath $Adb `
            -ArgumentList @(
                "-s", $Device.Serial, "shell", "am", "start", "-W", "-n",
                "com.vladimir.messenger/.MainActivity"
            ) `
            -Label ("{0}-launch" -f $SafeName)

        $LaunchLines = @($LaunchResult.StdoutLines) + @($LaunchResult.StderrLines)
        $LaunchText = $LaunchLines -join "`n"
        $StatusOkCount = @($LaunchLines | Where-Object { $_.Trim() -eq "Status: ok" }).Count

        if (($LaunchResult.ExitCodeAvailable -and $LaunchResult.ExitCode -ne 0) -or $StatusOkCount -ne 1) {
            throw "Launch failed: $($Device.Name), exit=$($LaunchResult.ExitCode), statusOk=$StatusOkCount, output=$LaunchText"
        }

        $Started = Get-DeviceSnapshot -Device $Device -Phase "started"
        if (-not $Started.ProcessRunning -or $Started.ProcessIds.Count -ne 1) {
            throw "Expected exactly one process after launch: $($Device.Name)"
        }

        $LaunchResults.Add([pscustomobject]@{
            Name = $Device.Name
            Serial = $Device.Serial
            LaunchEpoch = $PreRecord.LaunchEpoch
            LaunchExitCodeAvailable = $LaunchResult.ExitCodeAvailable
            LaunchExitCode = $LaunchResult.ExitCode
            StatusOkCount = $StatusOkCount
            LaunchStdoutPath = $LaunchResult.StdoutPath
            LaunchStderrPath = $LaunchResult.StderrPath
            ProcessId = [string]$Started.ProcessIds[0]
            StartedSnapshot = $Started
        })
    }
    if ($LaunchCallCount -ne 3 -or $LaunchResults.Count -ne 3) {
        throw "Expected exactly three controlled launches"
    }

    Start-Sleep -Seconds $EarlyWaitSeconds

    foreach ($Device in $Devices) {
        $LaunchRecord = @(
            $LaunchResults | Where-Object { $_.Name -eq $Device.Name }
        )[0]
        $EarlyLines = Get-P2pSnapshot `
            -Device $Device `
            -ProcessId $LaunchRecord.ProcessId `
            -LaunchEpoch $LaunchRecord.LaunchEpoch `
            -Phase "early"
        $LaunchRecord | Add-Member -NotePropertyName EarlyP2pLines -NotePropertyValue @($EarlyLines)
    }

    Start-Sleep -Seconds $LateWaitSeconds

    foreach ($Device in $Devices) {
        $LaunchRecord = @(
            $LaunchResults | Where-Object { $_.Name -eq $Device.Name }
        )[0]

        $Post = Get-DeviceSnapshot -Device $Device -Phase "late"
        if (-not $Post.ProcessRunning -or
            $Post.ProcessIds.Count -ne 1 -or
            [string]$Post.ProcessIds[0] -ne $LaunchRecord.ProcessId) {
            throw "Process stability gate failed: $($Device.Name)"
        }

        $LateLines = Get-P2pSnapshot `
            -Device $Device `
            -ProcessId $LaunchRecord.ProcessId `
            -LaunchEpoch $LaunchRecord.LaunchEpoch `
            -Phase "late"
        $CrashLines = Get-CrashSnapshot `
            -Device $Device `
            -ProcessId $LaunchRecord.ProcessId `
            -LaunchEpoch $LaunchRecord.LaunchEpoch

        $CombinedLines = @($LaunchRecord.EarlyP2pLines) + @($LateLines)
        $P2pLines = @($CombinedLines | Sort-Object -Unique)

        $PrimaryReadyLines = @($P2pLines | Where-Object { $_.Contains("MQTT SESSION READY:") })
        $PrimaryReconnectLines = @($P2pLines | Where-Object { $_.Contains("MQTT: connection restored; subscription requested") })
        $PrimaryErrorLines = @($P2pLines | Where-Object { $_.Contains("MQTT error:") })
        $SecondaryReadyLines = @($P2pLines | Where-Object { $_.Contains("MQTT SECONDARY READY: broker=emqx") })
        $SecondaryConnectedLines = @($P2pLines | Where-Object { $_.Contains("MQTT SECONDARY STATUS: broker=emqx state=connected") })
        $SecondaryBackoffLines = @($P2pLines | Where-Object { $_.Contains("MQTT SECONDARY STATUS: broker=emqx state=backoff") })
        $FanoutTwoLines = @(
            $P2pLines | Where-Object {
                $_.Contains("MQTT FANOUT QUEUED:") -and $_.Contains("brokers=2")
            }
        )
        $DuplicateLines = @($P2pLines | Where-Object { $_.Contains("MQTT CROSS-BROKER DUPLICATE DROPPED:") })
        $HiveIngressLines = @($P2pLines | Where-Object { $_.Contains("MQTT IN: broker=hivemq") })
        $EmqxIngressLines = @($P2pLines | Where-Object { $_.Contains("MQTT IN: broker=emqx") })
        $HiveDuplicateLines = @($DuplicateLines | Where-Object { $_.Contains("broker=hivemq") })
        $EmqxDuplicateLines = @($DuplicateLines | Where-Object { $_.Contains("broker=emqx") })

        $Metrics = [ordered]@{
            SessionInitializing = Count-LinesContaining -Lines $P2pLines -Text "MQTT: initializing primary session"
            EventLoopStarted = Count-LinesContaining -Lines $P2pLines -Text "MQTT: event loop started"
            ConnectionAcknowledged = Count-LinesContaining -Lines $P2pLines -Text "MQTT: connection acknowledged by broker"
            SubscriptionRequested = Count-LinesContaining -Lines $P2pLines -Text "MQTT: subscription requested after ConnAck"
            SessionReady = $PrimaryReadyLines.Count
            PrimaryReconnect = $PrimaryReconnectLines.Count
            PresenceQueued = Count-LinesContaining -Lines $P2pLines -Text "MQTT SESSION: presence request queued"
            IncomingPublish = Count-LinesContaining -Lines $P2pLines -Text "MQTT IN:"
            HiveIngress = $HiveIngressLines.Count
            EmqxIngress = $EmqxIngressLines.Count
            Heartbeat = Count-LinesContaining -Lines $P2pLines -Text "MQTT LIVENESS HEARTBEAT:"
            PollError = $PrimaryErrorLines.Count
            SecondaryConnected = $SecondaryConnectedLines.Count
            SecondaryReady = $SecondaryReadyLines.Count
            SecondaryBackoff = $SecondaryBackoffLines.Count
            SecondaryStopped = Count-LinesContaining -Lines $P2pLines -Text "MQTT SECONDARY STATUS: broker=emqx state=stopped"
            FanoutTwo = $FanoutTwoLines.Count
            DuplicateDrops = $DuplicateLines.Count
            HiveDuplicate = $HiveDuplicateLines.Count
            EmqxDuplicate = $EmqxDuplicateLines.Count
            BestEffortDrop = Count-LinesContaining -Lines $P2pLines -Text "MQTT OVERFLOW DROP:"
            LossIntolerantBackpressure = Count-LinesContaining -Lines $P2pLines -Text "MQTT LOSS-INTOLERANT BACKPRESSURE:"
            LossIntolerantInvariant = Count-LinesContaining -Lines $P2pLines -Text "MQTT LOSS-INTOLERANT INBOX INVARIANT:"
            LivenessStalled = Count-LinesContaining -Lines $P2pLines -Text "MQTT LIVENESS STALLED:"
            EventLoopEnded = Count-LinesContaining -Lines $P2pLines -Text "MQTT LIVENESS: EventLoop task"
            ChannelClosed = Count-LinesContaining -Lines $P2pLines -Text "MQTT LIVENESS: core notification channel closed"
            SessionStartFailed = Count-LinesContaining -Lines $P2pLines -Text "MQTT SESSION START FAILED:"
            RestartRequired = Count-LinesContaining -Lines $P2pLines -Text "MQTT SESSION RESTART REQUIRED:"
            RestartScheduled = Count-LinesContaining -Lines $P2pLines -Text "MQTT SESSION RESTART SCHEDULED:"
            RestartFailed = Count-LinesContaining -Lines $P2pLines -Text "MQTT SESSION RESTART FAILED:"
            SessionRecovered = Count-LinesContaining -Lines $P2pLines -Text "MQTT SESSION RECOVERED:"
            RequestTimeout = Count-LinesContaining -Lines $P2pLines -Text "MQTT REQUEST TIMEOUT:"
            RequestError = Count-LinesContaining -Lines $P2pLines -Text "MQTT REQUEST ERROR:"
            SecondaryRequestTimeout = Count-LinesContaining -Lines $P2pLines -Text "MQTT SECONDARY REQUEST TIMEOUT:"
            SecondaryRequestError = Count-LinesContaining -Lines $P2pLines -Text "MQTT SECONDARY REQUEST ERROR:"
            CrashAnr = @($CrashLines).Count
        }

        $HeartbeatLines = @($P2pLines | Where-Object { $_.Contains("MQTT LIVENESS HEARTBEAT:") })
        $LastHeartbeat = if ($HeartbeatLines.Count -gt 0) {
            $HeartbeatLines[-1]
        } else {
            $null
        }

        $HeartbeatConnacks = $null
        $HeartbeatPending = $null
        $HeartbeatRequestTimeouts = $null
        $HeartbeatRequestErrors = $null

        if ($null -ne $LastHeartbeat) {
            $ConnackMatch = [regex]::Match($LastHeartbeat, "connacks=(\d+)")
            $PendingMatch = [regex]::Match($LastHeartbeat, "loss_intolerant_pending=(\d+)")
            $TimeoutMatch = [regex]::Match($LastHeartbeat, "request_timeouts=(\d+)")
            $ErrorMatch = [regex]::Match($LastHeartbeat, "request_errors=(\d+)")

            if ($ConnackMatch.Success) {
                $HeartbeatConnacks = [int64]$ConnackMatch.Groups[1].Value
            }
            if ($PendingMatch.Success) {
                $HeartbeatPending = [int64]$PendingMatch.Groups[1].Value
            }
            if ($TimeoutMatch.Success) {
                $HeartbeatRequestTimeouts = [int64]$TimeoutMatch.Groups[1].Value
            }
            if ($ErrorMatch.Success) {
                $HeartbeatRequestErrors = [int64]$ErrorMatch.Groups[1].Value
            }
        }

        $PrimaryRecoveredAfterError = $PrimaryErrorLines.Count -eq 0
        if ($PrimaryErrorLines.Count -gt 0) {
            $PrimaryRecoveryLines = @($PrimaryReadyLines) + @($PrimaryReconnectLines)
            $PrimaryRecoveryLines = @($PrimaryRecoveryLines | Sort-Object -Unique)
            $LastPrimaryRecoveryEpoch = Get-LastLineEpoch -Lines $PrimaryRecoveryLines
            $LastPrimaryErrorEpoch = Get-LastLineEpoch -Lines $PrimaryErrorLines
            $PrimaryRecoveredAfterError = (
                $null -ne $LastPrimaryRecoveryEpoch -and
                $LastPrimaryRecoveryEpoch -gt $LastPrimaryErrorEpoch
            )
        }
        $SecondaryRecoveredAfterBackoff = $SecondaryBackoffLines.Count -eq 0
        if ($SecondaryBackoffLines.Count -gt 0) {
            $LastSecondaryReadyEpoch = Get-LastLineEpoch -Lines $SecondaryReadyLines
            $LastSecondaryBackoffEpoch = Get-LastLineEpoch -Lines $SecondaryBackoffLines
            $SecondaryRecoveredAfterBackoff = (
                $null -ne $LastSecondaryReadyEpoch -and
                $LastSecondaryReadyEpoch -gt $LastSecondaryBackoffEpoch
            )
        }

        $Failures = [System.Collections.Generic.List[string]]::new()
        if ($Metrics.SessionReady -lt 1) { $Failures.Add("primary session ready missing") }
        if ($Metrics.ConnectionAcknowledged -lt 1) { $Failures.Add("primary connection acknowledgement missing") }
        if ($Metrics.SubscriptionRequested -lt 1) { $Failures.Add("primary subscription request missing") }
        if ($Metrics.SecondaryReady -lt 1) { $Failures.Add("secondary ready missing") }
        if ($Metrics.FanoutTwo -lt 1) { $Failures.Add("two-broker fanout evidence missing") }
        if ($Metrics.DuplicateDrops -lt 1) { $Failures.Add("cross-broker duplicate evidence missing") }
        if (($Metrics.HiveIngress + $Metrics.HiveDuplicate) -lt 1) { $Failures.Add("HiveMQ evidence missing") }
        if (($Metrics.EmqxIngress + $Metrics.EmqxDuplicate) -lt 1) { $Failures.Add("EMQX evidence missing") }
        if (-not $PrimaryRecoveredAfterError) { $Failures.Add("primary did not recover after last error") }
        if (-not $SecondaryRecoveredAfterBackoff) { $Failures.Add("secondary did not recover after last backoff") }
        if ($Metrics.Heartbeat -lt 1) { $Failures.Add("heartbeat missing") }
        if ($HeartbeatConnacks -lt 1) { $Failures.Add("heartbeat connacks missing") }
        if ($HeartbeatPending -ne 0) { $Failures.Add("loss-intolerant pending is not zero") }
        if ($HeartbeatRequestTimeouts -ne 0) { $Failures.Add("heartbeat request timeout counter nonzero") }
        if ($HeartbeatRequestErrors -ne 0) { $Failures.Add("heartbeat request error counter nonzero") }

        foreach ($MetricName in @(
            "SecondaryStopped", "BestEffortDrop", "LossIntolerantBackpressure",
            "LossIntolerantInvariant", "LivenessStalled", "EventLoopEnded", "ChannelClosed",
            "SessionStartFailed", "RestartRequired", "RestartScheduled", "RestartFailed",
            "SessionRecovered", "RequestTimeout", "RequestError", "SecondaryRequestTimeout",
            "SecondaryRequestError", "CrashAnr"
        )) {
            if ([int64]$Metrics[$MetricName] -ne 0) {
                $Failures.Add("$MetricName count=$($Metrics[$MetricName])")
            }
        }

        $AnalysisResults.Add([pscustomobject]@{
            Name = $Device.Name
            Serial = $Device.Serial
            ProcessId = $LaunchRecord.ProcessId
            LaunchEpoch = $LaunchRecord.LaunchEpoch
            EarlyLineCount = @($LaunchRecord.EarlyP2pLines).Count
            LateLineCount = @($LateLines).Count
            UniqueP2pLineCount = $P2pLines.Count
            Metrics = $Metrics
            LastHeartbeat = $LastHeartbeat
            HeartbeatConnacks = $HeartbeatConnacks
            HeartbeatPending = $HeartbeatPending
            HeartbeatRequestTimeouts = $HeartbeatRequestTimeouts
            HeartbeatRequestErrors = $HeartbeatRequestErrors
            PrimaryRecoveredAfterError = $PrimaryRecoveredAfterError
            SecondaryRecoveredAfterBackoff = $SecondaryRecoveredAfterBackoff
            PostSnapshot = $Post
            Failures = @($Failures)
            Passed = ($Failures.Count -eq 0)
        })
    }

    if ($AnalysisResults.Count -ne 3 -or @($AnalysisResults | Where-Object { -not $_.Passed }).Count -ne 0) {
        $AllFailures = @(
            $AnalysisResults | ForEach-Object {
                $Name = $_.Name
                $_.Failures | ForEach-Object { "${Name}: $_" }
            }
        )
        throw "Launch analysis did not pass 3/3: $($AllFailures -join '; ')"
    }

    $StatusAfter = @(& git status --porcelain=v1 --untracked-files=all)
    $UnexpectedAfter = @($StatusAfter | Where-Object { $_ -notin $ExpectedStatus })
    if ($LASTEXITCODE -ne 0 -or $UnexpectedAfter.Count -gt 0 -or $StatusAfter.Count -ne $ExpectedStatus.Count) {
        throw "Windows worktree changed unexpectedly during launch"
    }
    $FinalNativeHash = (Get-FileHash -LiteralPath $NativePath -Algorithm SHA256).Hash
    $FinalIconHash = (Get-FileHash -LiteralPath $IconPath -Algorithm SHA256).Hash
    if ($FinalNativeHash -ne $ExpectedNativeHash -or $FinalIconHash -ne $ExpectedIconHash) {
        throw "Native/icon changed during launch"
    }

    $Outcome = "PASS"
}
catch {
    $Failure = $_.Exception.Message
    throw
}
finally {
    $PassedCount = @($AnalysisResults | Where-Object { $_.Passed }).Count

    $State = [ordered]@{
        Schema = 1
        Purpose = "single controlled M3(d) v11.16.16 launch 3/3 with dual-broker readiness"
        Outcome = $Outcome
        Failure = $Failure
        CompletedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        UserApprovedLaunch = $true
        ApplicationCommit = $ExpectedApplicationCommit
        WindowsBranch = $CurrentBranch
        WindowsHead = $CurrentHead
        NativeSha256 = $ExpectedNativeHash
        IconSha256 = $ExpectedIconHash
        ScriptPath = $PSCommandPath
        ScriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        InstallState = $InstallStatePath
        InstallStateSha256 = $InstallStateHash
        VersionName = $TargetVersionName
        VersionCode = $TargetVersionCode
        EarlyWaitSeconds = $EarlyWaitSeconds
        LateWaitSeconds = $LateWaitSeconds
        TotalWaitSeconds = $EarlyWaitSeconds + $LateWaitSeconds
        EvidenceDirectory = $EvidenceDir
        LaunchStarted = $LaunchStarted
        LaunchCallCount = $LaunchCallCount
        PassedDeviceCount = $PassedCount
        PreflightDevices = @($PreflightResults)
        LaunchDevices = @($LaunchResults | Select-Object Name, Serial, LaunchEpoch, LaunchExitCodeAvailable, LaunchExitCode, StatusOkCount, LaunchStdoutPath, LaunchStderrPath, ProcessId, StartedSnapshot)
        AnalysisDevices = @($AnalysisResults)
        Installed = $false
        Uninstalled = $false
        DataCleared = $false
        ForceStopped = $false
        LogcatCleared = $false
        NetworkChanged = $false
        UserPayloadPublished = $false
        AutomaticRetry = $false
    }

    $State | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:             $Outcome"
    Write-Host "State:               $StatePath"
    Write-Host "State SHA256:        $StateHash"
    Write-Host "Launch started/calls:$LaunchStarted / $LaunchCallCount"
    Write-Host "Passed device count: $PassedCount"
    Write-Host "Total wait seconds:  $($EarlyWaitSeconds + $LateWaitSeconds)"
    Write-Host "Force-stop used:     False"
    Write-Host "Logcat cleared:      False"
    Write-Host "User payload:        False"
}

if ($Outcome -ne "PASS") {
    throw "M3(d) v11.16.16 launch did not pass; see immutable state: $StatePath"
}

Write-Host ""
Write-Host "M3D V11.16.16 LAUNCH READINESS PASS 3/3"
foreach ($Result in $AnalysisResults) {
    Write-Host "=== $($Result.Name), PID $($Result.ProcessId) ==="
    $Result.Metrics | ConvertTo-Json -Depth 4
    Write-Host "Last heartbeat: $($Result.LastHeartbeat)"
}
