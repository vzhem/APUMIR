$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$LaunchStatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-launch.json"
$RecoveryStatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-launch-analysis-recovery.json"
$ExpectedLaunchStateHash = "3AA96B1AE79C9EEB5CBEF99DCC4FAC13E7A14A23DD95F530989C138140737E47"

if (Test-Path -LiteralPath $RecoveryStatePath) {
    throw "LAUNCH ANALYSIS RECOVERY ALREADY EXISTS - DO NOT REPEAT: $RecoveryStatePath"
}
if (-not (Test-Path -LiteralPath $LaunchStatePath)) {
    throw "Launch state missing: $LaunchStatePath"
}

$LaunchStateHash = (Get-FileHash -LiteralPath $LaunchStatePath -Algorithm SHA256).Hash
if ($LaunchStateHash -ne $ExpectedLaunchStateHash) {
    throw "Launch state hash mismatch: $LaunchStateHash"
}

$LaunchState = Get-Content -LiteralPath $LaunchStatePath -Raw | ConvertFrom-Json
if ($LaunchState.Outcome -ne "INCOMPLETE_DO_NOT_REPEAT" -or
    $LaunchState.LaunchStarted -ne $true -or
    $LaunchState.PassedDeviceCount -ne 2 -or
    $LaunchState.ForceStopped -ne $false -or
    $LaunchState.LogcatCleared -ne $false -or
    $LaunchState.NetworkChanged -ne $false -or
    $LaunchState.UserPayloadPublished -ne $false -or
    $LaunchState.AutomaticRetry -ne $false) {
    throw "Launch state does not match the immutable one-shot incomplete result"
}

$EvidenceDir = [string]$LaunchState.EvidenceDirectory
if (-not (Test-Path -LiteralPath $EvidenceDir)) {
    throw "Launch evidence directory missing: $EvidenceDir"
}

function Convert-EpochToUtc {
    param(
        [Parameter(Mandatory = $true)]
        [double]$Epoch
    )

    $DateTime = [DateTimeOffset]::FromUnixTimeMilliseconds(
        [int64][math]::Floor($Epoch * 1000.0)
    )
    return $DateTime.UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
}

function Get-P2pEntries {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$Lines,

        [Parameter(Mandatory = $true)]
        [string]$ProcessId,

        [Parameter(Mandatory = $true)]
        [int64]$LaunchEpoch
    )

    $Entries = [System.Collections.Generic.List[object]]::new()
    $EscapedProcessId = [regex]::Escape($ProcessId)
    $Pattern = "^\s*(\d+\.\d+)\s+{0}\s+(\d+)\s+([VDIWEF])\s+p2p_core:\s*(.*)$" -f $EscapedProcessId

    foreach ($Line in $Lines) {
        $Match = [regex]::Match($Line, $Pattern)
        if (-not $Match.Success) {
            continue
        }

        $Epoch = [double]::Parse(
            $Match.Groups[1].Value,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        if ($Epoch -lt [double]$LaunchEpoch) {
            continue
        }

        $Entries.Add([pscustomobject]@{
            Epoch = $Epoch
            Utc = Convert-EpochToUtc -Epoch $Epoch
            ProcessId = $ProcessId
            ThreadId = $Match.Groups[2].Value
            Priority = $Match.Groups[3].Value
            Message = $Match.Groups[4].Value
            RawLine = $Line
        })
    }

    return @($Entries)
}

function Get-HeartbeatCounters {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    $Names = @(
        "incoming", "connacks", "forwarded", "best_effort_drops",
        "loss_intolerant_buffered", "loss_intolerant_pending",
        "loss_intolerant_backpressure", "poll_errors", "request_timeouts", "request_errors"
    )

    $Values = [ordered]@{}
    foreach ($Name in $Names) {
        $Match = [regex]::Match($Message, ("{0}=(\d+)" -f [regex]::Escape($Name)))
        if (-not $Match.Success) {
            throw "Heartbeat field missing: $Name"
        }
        $Values[$Name] = [int64]$Match.Groups[1].Value
    }

    $PollMatch = [regex]::Match($Message, "polls=(\d+)/(\d+)")
    if (-not $PollMatch.Success) {
        throw "Heartbeat polls field missing"
    }
    $Values["polls_completed"] = [int64]$PollMatch.Groups[1].Value
    $Values["polls_started"] = [int64]$PollMatch.Groups[2].Value
    return [pscustomobject]$Values
}

$ErrorMetricNames = @(
    "BestEffortDrop", "LossIntolerantBackpressure", "LossIntolerantInvariant",
    "LivenessStalled", "EventLoopEnded", "ChannelClosed", "SessionStartFailed",
    "RestartRequired", "RestartScheduled", "RestartFailed", "SessionRecovered",
    "RequestTimeout", "RequestError", "CrashAnr"
)

$RecoveredResults = [System.Collections.Generic.List[object]]::new()

foreach ($Analysis in $LaunchState.AnalysisDevices) {
    $Name = [string]$Analysis.Name
    $SafeName = $Name.ToLowerInvariant()
    $ProcessId = [string]$Analysis.ProcessId
    $LaunchEpoch = [int64]$Analysis.LaunchEpoch

    $LaunchRecord = @(
        $LaunchState.LaunchDevices | Where-Object { $_.Name -eq $Name }
    )[0]
    if ($null -eq $LaunchRecord) {
        throw "Launch record missing: $Name"
    }

    $EarlyPath = Join-Path $EvidenceDir ("{0}-early-p2p-core.stdout.log" -f $SafeName)
    $LatePath = Join-Path $EvidenceDir ("{0}-late-p2p-core.stdout.log" -f $SafeName)
    foreach ($Path in @($EarlyPath, $LatePath)) {
        if (-not (Test-Path -LiteralPath $Path)) {
            throw "Native evidence missing: $Path"
        }
    }

    $EarlyRaw = @(Get-Content -LiteralPath $EarlyPath)
    $LateRaw = @(Get-Content -LiteralPath $LatePath)
    $EarlyEntries = @(Get-P2pEntries -Lines $EarlyRaw -ProcessId $ProcessId -LaunchEpoch $LaunchEpoch)
    $LateEntries = @(Get-P2pEntries -Lines $LateRaw -ProcessId $ProcessId -LaunchEpoch $LaunchEpoch)
    $CombinedEntries = @($EarlyEntries) + @($LateEntries)
    $Entries = @($CombinedEntries | Sort-Object Epoch, RawLine -Unique)

    if ($Entries.Count -lt 1) {
        throw "No saved native entries for current launch PID: $Name"
    }

    $FirstEpoch = [double]$Entries[0].Epoch
    $LastEpoch = [double]$Entries[-1].Epoch
    $FirstOffsetSeconds = [math]::Round($FirstEpoch - [double]$LaunchEpoch, 3)

    $HeartbeatEntries = @(
        $Entries | Where-Object { $_.Message.Contains("MQTT LIVENESS HEARTBEAT:") }
    )
    if ($HeartbeatEntries.Count -lt 1) {
        throw "Saved heartbeat missing: $Name"
    }

    $LastHeartbeatEntry = $HeartbeatEntries[-1]
    $Counters = Get-HeartbeatCounters -Message $LastHeartbeatEntry.Message
    $Metrics = $Analysis.Metrics

    $StartedIds = @($LaunchRecord.StartedSnapshot.ProcessIds | ForEach-Object { [string]$_ })
    $PostIds = @($Analysis.PostSnapshot.ProcessIds | ForEach-Object { [string]$_ })
    $ProcessStable = $StartedIds.Count -eq 1 -and
        $PostIds.Count -eq 1 -and
        $StartedIds[0] -eq $ProcessId -and
        $PostIds[0] -eq $ProcessId

    $DirectReady = [int64]$Metrics.SessionReady -ge 1 -and
        [int64]$Metrics.ConnectionAcknowledged -ge 1 -and
        [int64]$Metrics.SubscriptionRequested -ge 1

    $ErrorCountsZero = $true
    $NonzeroErrors = [System.Collections.Generic.List[string]]::new()
    foreach ($MetricName in $ErrorMetricNames) {
        $Value = [int64]$Metrics.$MetricName
        if ($Value -ne 0) {
            $ErrorCountsZero = $false
            $NonzeroErrors.Add("$MetricName=$Value")
        }
    }

    $RuntimeReady = $ProcessStable -and
        [int64]$Metrics.Heartbeat -ge 1 -and
        [int64]$Metrics.IncomingPublish -ge 1 -and
        $Counters.connacks -ge 1 -and
        $Counters.incoming -ge 1 -and
        $Counters.forwarded -ge 1 -and
        $Counters.polls_completed -ge 1 -and
        $Counters.polls_started -ge $Counters.polls_completed -and
        $Counters.loss_intolerant_pending -eq 0 -and
        $Counters.request_timeouts -eq 0 -and
        $Counters.request_errors -eq 0 -and
        $ErrorCountsZero

    $StartupLikelyEvicted = -not $DirectReady -and $FirstOffsetSeconds -gt 10

    $RecoveredResults.Add([pscustomobject]@{
        Name = $Name
        Serial = $Analysis.Serial
        ProcessId = $ProcessId
        ProcessStable = $ProcessStable
        LaunchEpoch = $LaunchEpoch
        FirstPreservedUtc = $Entries[0].Utc
        LastPreservedUtc = $Entries[-1].Utc
        FirstPreservedOffsetSeconds = $FirstOffsetSeconds
        EarlyEntryCount = $EarlyEntries.Count
        LateEntryCount = $LateEntries.Count
        UniqueEntryCount = $Entries.Count
        DirectReadyMarkers = $DirectReady
        StartupLikelyEvicted = $StartupLikelyEvicted
        RuntimeReady = $RuntimeReady
        IncomingPublishCount = [int64]$Metrics.IncomingPublish
        HeartbeatCount = [int64]$Metrics.Heartbeat
        LastHeartbeatUtc = $LastHeartbeatEntry.Utc
        LastHeartbeat = $LastHeartbeatEntry.Message
        HeartbeatCounters = $Counters
        ErrorCountsZero = $ErrorCountsZero
        NonzeroErrors = @($NonzeroErrors)
        ParentFailures = @($Analysis.Failures)
        EarlyEvidencePath = $EarlyPath
        EarlyEvidenceSha256 = (Get-FileHash -LiteralPath $EarlyPath -Algorithm SHA256).Hash
        LateEvidencePath = $LatePath
        LateEvidenceSha256 = (Get-FileHash -LiteralPath $LatePath -Algorithm SHA256).Hash
    })
}

if ($RecoveredResults.Count -ne 3) {
    throw "Expected three recovered device results"
}

$RuntimePassCount = @($RecoveredResults | Where-Object { $_.RuntimeReady }).Count
$DirectReadyCount = @($RecoveredResults | Where-Object { $_.DirectReadyMarkers }).Count
$AnnaResult = @($RecoveredResults | Where-Object { $_.Name -eq "Anna" })[0]

if ($RuntimePassCount -ne 3) {
    $Failures = @(
        $RecoveredResults | Where-Object { -not $_.RuntimeReady } | ForEach-Object {
            $FailureTemplate = "{0}: stable={1}, incoming={2}, heartbeat={3}, errorsZero={4}"
            $FailureTemplate -f $_.Name, $_.ProcessStable, $_.IncomingPublishCount,
                $_.HeartbeatCount, $_.ErrorCountsZero
        }
    )
    throw "Runtime recovery did not pass 3/3: $($Failures -join '; ')"
}
if ($DirectReadyCount -ne 2) {
    throw "Expected exactly 2/3 direct startup-marker results, actual=$DirectReadyCount"
}
if ($null -eq $AnnaResult -or $AnnaResult.DirectReadyMarkers -or -not $AnnaResult.RuntimeReady) {
    throw "Anna is not the single runtime-inferred readiness result"
}

$EvidenceManifest = @(
    Get-ChildItem -LiteralPath $EvidenceDir -File |
        Sort-Object Name |
        ForEach-Object {
            [pscustomobject]@{
                Name = $_.Name
                Length = $_.Length
                Sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        }
)

$RecoveryState = [ordered]@{
    Schema = 1
    Purpose = "read-only v11.16.13 launch runtime recovery from saved early/late evidence"
    Outcome = "PASS_RUNTIME_DIRECT_STARTUP_2_OF_3"
    AnalyzedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    ScriptPath = $PSCommandPath
    ScriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    ParentLaunchState = $LaunchStatePath
    ParentLaunchStateSha256 = $LaunchStateHash
    ParentOutcome = $LaunchState.Outcome
    RuntimePassCount = $RuntimePassCount
    DirectReadyMarkerCount = $DirectReadyCount
    AnnaDirectStartupEvidence = "INCOMPLETE_NOT_REPEATED"
    RelaunchPerformed = $false
    AdbCommandsUsed = $false
    LogcatReadAgain = $false
    ForceStopped = $false
    LogcatCleared = $false
    NetworkChanged = $false
    UserPayloadPublished = $false
    Devices = @($RecoveredResults)
    EvidenceManifest = $EvidenceManifest
}

$RecoveryState | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $RecoveryStatePath -Encoding UTF8
$RecoveryStateHash = (Get-FileHash -LiteralPath $RecoveryStatePath -Algorithm SHA256).Hash

Write-Host "LAUNCH RUNTIME RECOVERY PASS 3/3"
Write-Host "Recovery state:          $RecoveryStatePath"
Write-Host "Recovery state SHA256:   $RecoveryStateHash"
Write-Host "Parent launch SHA256:    $LaunchStateHash"
Write-Host "Runtime pass count:      $RuntimePassCount"
Write-Host "Direct startup markers:  $DirectReadyCount / 3"
Write-Host "Relaunch performed:      False"
Write-Host "ADB/logcat read again:   False"
Write-Host ""

foreach ($Result in $RecoveredResults) {
    Write-Host "=== $($Result.Name), PID $($Result.ProcessId) ==="
    Write-Host "Process stable:                 $($Result.ProcessStable)"
    Write-Host "Direct ready markers:           $($Result.DirectReadyMarkers)"
    Write-Host "Runtime ready:                  $($Result.RuntimeReady)"
    Write-Host "Startup likely evicted:         $($Result.StartupLikelyEvicted)"
    Write-Host "First preserved offset seconds: $($Result.FirstPreservedOffsetSeconds)"
    Write-Host "Incoming publishes:             $($Result.IncomingPublishCount)"
    Write-Host "Heartbeat count:                $($Result.HeartbeatCount)"
    Write-Host "Last heartbeat UTC:             $($Result.LastHeartbeatUtc)"
    $Result.HeartbeatCounters | ConvertTo-Json -Depth 4
}
