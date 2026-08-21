$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$InstallStatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-install.json"
$StatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-launch.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-launch-evidence"
$ExpectedInstallStateHash = "2B7C9B74AEA366AA5530351223E956F0087DE70FC71461E282192DBA8D2CC4B7"
$TargetVersionName = "v11.16.13"
$TargetVersionCode = 11016013
$EarlyWaitSeconds = 45
$LateWaitSeconds = 120

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
    throw "V11.16.13 LAUNCH ALREADY COMPLETED OR ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "V11.16.13 LAUNCH EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
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
if ($InstallState.Outcome -ne "PASS" -or
    $InstallState.VerifiedInstallCount -ne 3 -or
    $InstallState.TargetVersionName -ne $TargetVersionName -or
    $InstallState.TargetVersionCode -ne $TargetVersionCode -or
    $InstallState.ForceStopped -ne $false -or
    $InstallState.Launched -ne $false) {
    throw "Install state is not a clean v11.16.13 PASS"
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
        DataDir = $DataDirMatch.Groups[1].Value.Trim()
        ProcessRunning = ($ProcessIds.Count -gt 0)
        ProcessIds = @($ProcessIds)
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

    if ($Result.ExitCode -ne 0) {
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

    if ($Result.ExitCode -ne 0) {
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

    if ($Result.ExitCode -ne 0) {
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

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$LaunchStarted = $false
$PreflightResults = [System.Collections.Generic.List[object]]::new()
$LaunchResults = [System.Collections.Generic.List[object]]::new()
$AnalysisResults = [System.Collections.Generic.List[object]]::new()

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

        if ($LaunchResult.ExitCode -ne 0 -or $StatusOkCount -ne 1) {
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
            LaunchExitCode = $LaunchResult.ExitCode
            StatusOkCount = $StatusOkCount
            LaunchStdoutPath = $LaunchResult.StdoutPath
            LaunchStderrPath = $LaunchResult.StderrPath
            ProcessId = [string]$Started.ProcessIds[0]
            StartedSnapshot = $Started
        })
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

        $Metrics = [ordered]@{
            SessionInitializing = Count-LinesContaining -Lines $P2pLines -Text "MQTT: initializing primary session"
            EventLoopStarted = Count-LinesContaining -Lines $P2pLines -Text "MQTT: event loop started"
            ConnectionAcknowledged = Count-LinesContaining -Lines $P2pLines -Text "MQTT: connection acknowledged by broker"
            SubscriptionRequested = Count-LinesContaining -Lines $P2pLines -Text "MQTT: subscription requested after ConnAck"
            SessionReady = Count-LinesContaining -Lines $P2pLines -Text "MQTT SESSION READY:"
            PresenceQueued = Count-LinesContaining -Lines $P2pLines -Text "MQTT SESSION: presence request queued"
            IncomingPublish = Count-LinesContaining -Lines $P2pLines -Text "MQTT IN:"
            Heartbeat = Count-LinesContaining -Lines $P2pLines -Text "MQTT LIVENESS HEARTBEAT:"
            PollError = Count-LinesContaining -Lines $P2pLines -Text "MQTT error:"
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

        $Failures = [System.Collections.Generic.List[string]]::new()
        if ($Metrics.SessionReady -lt 1) { $Failures.Add("session ready missing") }
        if ($Metrics.ConnectionAcknowledged -lt 1) { $Failures.Add("connection acknowledgement missing") }
        if ($Metrics.SubscriptionRequested -lt 1) { $Failures.Add("subscription request missing") }
        if ($Metrics.Heartbeat -lt 1) { $Failures.Add("heartbeat missing") }
        if ($HeartbeatConnacks -lt 1) { $Failures.Add("heartbeat connacks missing") }
        if ($HeartbeatPending -ne 0) { $Failures.Add("loss-intolerant pending is not zero") }
        if ($HeartbeatRequestTimeouts -ne 0) { $Failures.Add("heartbeat request timeout counter nonzero") }
        if ($HeartbeatRequestErrors -ne 0) { $Failures.Add("heartbeat request error counter nonzero") }

        foreach ($MetricName in @(
            "BestEffortDrop", "LossIntolerantBackpressure", "LossIntolerantInvariant",
            "LivenessStalled", "EventLoopEnded", "ChannelClosed", "SessionStartFailed",
            "RestartRequired", "RestartScheduled", "RestartFailed", "SessionRecovered",
            "RequestTimeout", "RequestError", "CrashAnr"
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
        Purpose = "single controlled v11.16.13 launch with early/late native evidence"
        Outcome = $Outcome
        Failure = $Failure
        CompletedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        UserApprovedLaunch = $true
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
        PassedDeviceCount = $PassedCount
        PreflightDevices = @($PreflightResults)
        LaunchDevices = @($LaunchResults | Select-Object Name, Serial, LaunchEpoch, LaunchExitCode, StatusOkCount, LaunchStdoutPath, LaunchStderrPath, ProcessId, StartedSnapshot)
        AnalysisDevices = @($AnalysisResults)
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
    Write-Host "Launch started:      $LaunchStarted"
    Write-Host "Passed device count: $PassedCount"
    Write-Host "Total wait seconds:  $($EarlyWaitSeconds + $LateWaitSeconds)"
    Write-Host "Force-stop used:     False"
    Write-Host "Logcat cleared:      False"
    Write-Host "User payload:        False"
}

if ($Outcome -ne "PASS") {
    throw "Launch did not pass; see immutable state: $StatePath"
}

Write-Host ""
Write-Host "LAUNCH V11.16.13 RUNTIME PASS 3/3"
foreach ($Result in $AnalysisResults) {
    Write-Host "=== $($Result.Name), PID $($Result.ProcessId) ==="
    $Result.Metrics | ConvertTo-Json -Depth 4
    Write-Host "Last heartbeat: $($Result.LastHeartbeat)"
}
