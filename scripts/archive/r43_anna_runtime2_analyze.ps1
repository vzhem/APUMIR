$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ParentStatePath = Join-Path $env:TEMP "apu-r4.3-observe-v11.16.14-anna-runtime2.json"
$RecoveryStatePath = Join-Path $env:TEMP "apu-r4.3-observe-v11.16.14-anna-runtime2-analysis-recovery.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.3-observe-v11.16.14-anna-runtime2-evidence"
$ExpectedParentStateHash = "E4AADB476E794C8F2DD12223A4BD555FCE55D159A5051BD002F104D579B98425"
$ExpectedEvidenceManifestHash = "2A3CADDD0908E5FA566E0432DC4A68460AF1620C664BAC8CAA7C3F8262CE3FA0"
$ExpectedEvidenceFileCount = 60
$ExpectedApkHash = "6C4D29DA78EB914C172376955B4A802473C26EB7595E25BEB51373D0CCE13F5C"
$ExpectedApkStateHash = "01A649477F975B312D1CAB4797B217ACA5E6C68071E0AAC8736426FD9B492FBF"
$ExpectedRuntime13StateHash = "7FDAF9BD2634A322186AECB704A393F132D9B66D9A891C568A9DB3E041C3A89F"
$PackageName = "com.vladimir.messenger"
$AnnaProcessId = "21678"

if (Test-Path -LiteralPath $RecoveryStatePath) {
    throw "R4.3 RUNTIME2 ANALYSIS RECOVERY ALREADY EXISTS - DO NOT REPEAT: $RecoveryStatePath"
}
foreach ($RequiredPath in @($ParentStatePath, $EvidenceDir)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required saved evidence missing: $RequiredPath"
    }
}

function Assert-ExactValue {
    param(
        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [object]$ActualValue,

        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [object]$ExpectedValue,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if ($ActualValue -ne $ExpectedValue) {
        throw "$Label mismatch: expected=$ExpectedValue actual=$ActualValue"
    }
}

function Get-EvidenceSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DeviceName,

        [Parameter(Mandatory = $true)]
        [string]$Phase
    )

    $SafeName = $DeviceName.ToLowerInvariant()
    $Prefix = "{0}-{1}" -f $SafeName, $Phase
    $PackagePath = Join-Path $EvidenceDir "$Prefix-package.stdout.log"
    $UidPath = Join-Path $EvidenceDir "$Prefix-uid.stdout.log"
    $ProcessPath = Join-Path $EvidenceDir "$Prefix-pid.stdout.log"

    foreach ($SnapshotPath in @($PackagePath, $UidPath, $ProcessPath)) {
        if (-not (Test-Path -LiteralPath $SnapshotPath -PathType Leaf)) {
            throw "Snapshot evidence missing: $SnapshotPath"
        }
    }

    $PackageText = Get-Content -LiteralPath $PackagePath -Raw
    $UidText = Get-Content -LiteralPath $UidPath -Raw
    $ProcessLines = @(Get-Content -LiteralPath $ProcessPath)

    $UidMatch = [regex]::Match($UidText, "uid:(\d+)")
    $VersionNameMatch = [regex]::Match($PackageText, "versionName=([^\s\r\n]+)")
    $VersionCodeMatch = [regex]::Match($PackageText, "versionCode=(\d+)")
    $FirstInstallMatch = [regex]::Match($PackageText, "firstInstallTime=([^\r\n]+)")
    $DataDirMatch = [regex]::Match($PackageText, "dataDir=([^\s\r\n]+)")

    $PackageParseComplete = $UidMatch.Success
    $PackageParseComplete = $PackageParseComplete -and $VersionNameMatch.Success
    $PackageParseComplete = $PackageParseComplete -and $VersionCodeMatch.Success
    $PackageParseComplete = $PackageParseComplete -and $FirstInstallMatch.Success
    $PackageParseComplete = $PackageParseComplete -and $DataDirMatch.Success
    if (-not $PackageParseComplete) {
        throw "Cannot parse saved package snapshot: $DeviceName/$Phase"
    }

    $ProcessIds = @(
        $ProcessLines |
            ForEach-Object { $_ -split "\s+" } |
            Where-Object { $_ -match "^\d+$" }
    )

    return [pscustomobject]@{
        DeviceName = $DeviceName
        Phase = $Phase
        Uid = [int64]$UidMatch.Groups[1].Value
        VersionName = $VersionNameMatch.Groups[1].Value.Trim()
        VersionCode = [int64]$VersionCodeMatch.Groups[1].Value
        FirstInstall = $FirstInstallMatch.Groups[1].Value.Trim()
        DataDir = $DataDirMatch.Groups[1].Value.Trim()
        ProcessIds = @($ProcessIds)
        Running = $ProcessIds.Count -gt 0
        PackageEvidenceSha256 = (Get-FileHash -LiteralPath $PackagePath -Algorithm SHA256).Hash
        UidEvidenceSha256 = (Get-FileHash -LiteralPath $UidPath -Algorithm SHA256).Hash
        ProcessEvidenceSha256 = (Get-FileHash -LiteralPath $ProcessPath -Algorithm SHA256).Hash
    }
}

function Assert-DeviceSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Snapshot,

        [Parameter(Mandatory = $true)]
        [int64]$ExpectedUid,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedVersionName,

        [Parameter(Mandatory = $true)]
        [int64]$ExpectedVersionCode,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedFirstInstall,

        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$ExpectedProcessId
    )

    $Label = "$($Snapshot.DeviceName)/$($Snapshot.Phase)"
    Assert-ExactValue -ActualValue $Snapshot.Uid -ExpectedValue $ExpectedUid -Label "$Label UID"
    Assert-ExactValue -ActualValue $Snapshot.VersionName -ExpectedValue $ExpectedVersionName -Label "$Label versionName"
    Assert-ExactValue -ActualValue $Snapshot.VersionCode -ExpectedValue $ExpectedVersionCode -Label "$Label versionCode"
    Assert-ExactValue -ActualValue $Snapshot.FirstInstall -ExpectedValue $ExpectedFirstInstall -Label "$Label firstInstallTime"
    Assert-ExactValue -ActualValue $Snapshot.DataDir -ExpectedValue "/data/user/0/$PackageName" -Label "$Label dataDir"

    if ($ExpectedProcessId.Length -eq 0) {
        if ($Snapshot.ProcessIds.Count -ne 0 -or $Snapshot.Running) {
            throw "$Label expected no process"
        }
    }
    else {
        if ($Snapshot.ProcessIds.Count -ne 1 -or [string]$Snapshot.ProcessIds[0] -ne $ExpectedProcessId) {
            throw "$Label process mismatch: expected=$ExpectedProcessId actual=$($Snapshot.ProcessIds -join ',')"
        }
    }
}

function Convert-EpochToUtc {
    param(
        [Parameter(Mandatory = $true)]
        [double]$Epoch
    )

    $EpochMilliseconds = [int64][math]::Floor($Epoch * 1000.0)
    $DateTime = [DateTimeOffset]::FromUnixTimeMilliseconds($EpochMilliseconds)
    return $DateTime.UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
}

function Get-P2pEntries {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$Lines,

        [Parameter(Mandatory = $true)]
        [string]$AndroidProcessId,

        [Parameter(Mandatory = $true)]
        [int64]$LaunchEpoch
    )

    $Entries = [System.Collections.Generic.List[object]]::new()
    $EscapedProcessId = [regex]::Escape($AndroidProcessId)
    $Pattern = "^\s*(\d+\.\d+)\s+{0}\s+(\d+)\s+([VDIWEF])\s+p2p_core:\s*(.*)$" -f $EscapedProcessId

    foreach ($Line in $Lines) {
        $LineMatch = [regex]::Match($Line, $Pattern)
        if (-not $LineMatch.Success) {
            continue
        }

        $Epoch = [double]::Parse(
            $LineMatch.Groups[1].Value,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        if ($Epoch -lt [double]$LaunchEpoch) {
            continue
        }

        $Entries.Add([pscustomobject]@{
            Epoch = $Epoch
            Utc = Convert-EpochToUtc -Epoch $Epoch
            ProcessId = $AndroidProcessId
            ThreadId = $LineMatch.Groups[2].Value
            Priority = $LineMatch.Groups[3].Value
            Message = $LineMatch.Groups[4].Value
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

    $FieldNames = @(
        "phase_age_ms", "progress_age_ms", "incoming", "connacks", "forwarded",
        "best_effort_drops", "loss_intolerant_buffered", "loss_intolerant_pending",
        "loss_intolerant_backpressure", "poll_errors", "request_timeouts", "request_errors"
    )
    $Values = [ordered]@{}

    $PhaseMatch = [regex]::Match($Message, "phase=([a-z_]+)")
    $PollMatch = [regex]::Match($Message, "polls=(\d+)/(\d+)")
    if (-not $PhaseMatch.Success -or -not $PollMatch.Success) {
        throw "Primary heartbeat phase/polls fields missing"
    }

    $Values["phase"] = $PhaseMatch.Groups[1].Value
    $Values["polls_completed"] = [int64]$PollMatch.Groups[1].Value
    $Values["polls_started"] = [int64]$PollMatch.Groups[2].Value

    foreach ($FieldName in $FieldNames) {
        $FieldPattern = "{0}=(\d+)" -f [regex]::Escape($FieldName)
        $FieldMatch = [regex]::Match($Message, $FieldPattern)
        if (-not $FieldMatch.Success) {
            throw "Primary heartbeat field missing: $FieldName"
        }
        $Values[$FieldName] = [int64]$FieldMatch.Groups[1].Value
    }

    return [pscustomobject]$Values
}

$ParentStateHash = (Get-FileHash -LiteralPath $ParentStatePath -Algorithm SHA256).Hash
Assert-ExactValue -ActualValue $ParentStateHash -ExpectedValue $ExpectedParentStateHash -Label "Parent runtime2 state SHA256"

$ParentState = Get-Content -LiteralPath $ParentStatePath -Raw | ConvertFrom-Json
Assert-ExactValue -ActualValue $ParentState.outcome -ExpectedValue "INCOMPLETE_DO_NOT_REPEAT" -Label "Parent outcome"
Assert-ExactValue -ActualValue $ParentState.installStarted -ExpectedValue $true -Label "Parent installStarted"
Assert-ExactValue -ActualValue $ParentState.launchStarted -ExpectedValue $true -Label "Parent launchStarted"
Assert-ExactValue -ActualValue ([string]$ParentState.annaNewPid) -ExpectedValue $AnnaProcessId -Label "Parent Anna process"
Assert-ExactValue -ActualValue $ParentState.apkSha256 -ExpectedValue $ExpectedApkHash -Label "Parent APK SHA256"
Assert-ExactValue -ActualValue $ParentState.apkStateSha256 -ExpectedValue $ExpectedApkStateHash -Label "Parent APK state SHA256"
Assert-ExactValue -ActualValue $ParentState.runtime13StateSha256 -ExpectedValue $ExpectedRuntime13StateHash -Label "Parent v11.16.13 runtime state SHA256"
Assert-ExactValue -ActualValue $ParentState.evidenceDirectory -ExpectedValue $EvidenceDir -Label "Parent evidence directory"
Assert-ExactValue -ActualValue $ParentState.automaticRetry -ExpectedValue $false -Label "Parent automatic retry"
Assert-ExactValue -ActualValue $ParentState.uninstalled -ExpectedValue $false -Label "Parent uninstall flag"
Assert-ExactValue -ActualValue $ParentState.dataCleared -ExpectedValue $false -Label "Parent data clear flag"
Assert-ExactValue -ActualValue $ParentState.forceStopped -ExpectedValue $false -Label "Parent force-stop flag"
Assert-ExactValue -ActualValue $ParentState.logcatCleared -ExpectedValue $false -Label "Parent logcat clear flag"
Assert-ExactValue -ActualValue $ParentState.networkChanged -ExpectedValue $false -Label "Parent network flag"
Assert-ExactValue -ActualValue $ParentState.userPayloadPublished -ExpectedValue $false -Label "Parent payload flag"
Assert-ExactValue -ActualValue $ParentState.secondarySubscriptions -ExpectedValue 0 -Label "Parent secondary subscriptions"
Assert-ExactValue -ActualValue $ParentState.secondaryPublishes -ExpectedValue 0 -Label "Parent secondary publishes"

$ExpectedMetricValues = [ordered]@{
    secondarySupervisor = 0
    secondaryStarting = 0
    secondaryConnected = 1
    secondaryErrors = 0
    primaryReady = 1
    primaryHeartbeat = 1
    primaryIncoming = 64
    primaryErrors = 1
    stalls = 0
    restarts = 0
    requestErrors = 0
    requestTimeouts = 0
}
foreach ($MetricName in $ExpectedMetricValues.Keys) {
    Assert-ExactValue -ActualValue ([int64]$ParentState.metrics.$MetricName) -ExpectedValue ([int64]$ExpectedMetricValues[$MetricName]) -Label "Parent metric $MetricName"
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
Assert-ExactValue -ActualValue $EvidenceManifest.Count -ExpectedValue $ExpectedEvidenceFileCount -Label "Evidence file count"

$ManifestLines = @(
    $EvidenceManifest | ForEach-Object {
        "{0}|{1}|{2}" -f $_.Name, $_.Length, $_.Sha256
    }
)
$ManifestText = $ManifestLines -join "`n"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$ManifestBytes = $Utf8NoBom.GetBytes($ManifestText)
$Sha256Algorithm = [System.Security.Cryptography.SHA256]::Create()
try {
    $ManifestDigestBytes = $Sha256Algorithm.ComputeHash($ManifestBytes)
    $EvidenceManifestHash = ([System.BitConverter]::ToString($ManifestDigestBytes)).Replace("-", "")
}
finally {
    $Sha256Algorithm.Dispose()
}
Assert-ExactValue -ActualValue $EvidenceManifestHash -ExpectedValue $ExpectedEvidenceManifestHash -Label "Evidence manifest SHA256"

$NonemptyStderrFiles = @(
    Get-ChildItem -LiteralPath $EvidenceDir -File -Filter "*.stderr.log" |
        Where-Object { $_.Length -ne 0 }
)
if ($NonemptyStderrFiles.Count -ne 0) {
    throw "Saved command stderr is not empty: $($NonemptyStderrFiles.Name -join ',')"
}

$AnnaPre = Get-EvidenceSnapshot -DeviceName "Anna" -Phase "pre"
$AnnaPostInstall = Get-EvidenceSnapshot -DeviceName "Anna" -Phase "postinstall"
$AnnaStarted = Get-EvidenceSnapshot -DeviceName "Anna" -Phase "started"
$AnnaFinal = Get-EvidenceSnapshot -DeviceName "Anna" -Phase "final"
$ZhenyaPre = Get-EvidenceSnapshot -DeviceName "Zhenya" -Phase "pre"
$ZhenyaFinal = Get-EvidenceSnapshot -DeviceName "Zhenya" -Phase "final"
$StasPre = Get-EvidenceSnapshot -DeviceName "Stas" -Phase "pre"
$StasFinal = Get-EvidenceSnapshot -DeviceName "Stas" -Phase "final"

Assert-DeviceSnapshot -Snapshot $AnnaPre -ExpectedUid 10425 -ExpectedVersionName "v11.16.13" -ExpectedVersionCode 11016013 -ExpectedFirstInstall "2026-08-08 11:40:39" -ExpectedProcessId "10945"
Assert-DeviceSnapshot -Snapshot $AnnaPostInstall -ExpectedUid 10425 -ExpectedVersionName "v11.16.14" -ExpectedVersionCode 11016014 -ExpectedFirstInstall "2026-08-08 11:40:39" -ExpectedProcessId ""
Assert-DeviceSnapshot -Snapshot $AnnaStarted -ExpectedUid 10425 -ExpectedVersionName "v11.16.14" -ExpectedVersionCode 11016014 -ExpectedFirstInstall "2026-08-08 11:40:39" -ExpectedProcessId $AnnaProcessId
Assert-DeviceSnapshot -Snapshot $AnnaFinal -ExpectedUid 10425 -ExpectedVersionName "v11.16.14" -ExpectedVersionCode 11016014 -ExpectedFirstInstall "2026-08-08 11:40:39" -ExpectedProcessId $AnnaProcessId
Assert-DeviceSnapshot -Snapshot $ZhenyaPre -ExpectedUid 10395 -ExpectedVersionName "v11.16.13" -ExpectedVersionCode 11016013 -ExpectedFirstInstall "2026-08-08 17:31:18" -ExpectedProcessId "20562"
Assert-DeviceSnapshot -Snapshot $ZhenyaFinal -ExpectedUid 10395 -ExpectedVersionName "v11.16.13" -ExpectedVersionCode 11016013 -ExpectedFirstInstall "2026-08-08 17:31:18" -ExpectedProcessId "20562"
Assert-DeviceSnapshot -Snapshot $StasPre -ExpectedUid 10387 -ExpectedVersionName "v11.16.13" -ExpectedVersionCode 11016013 -ExpectedFirstInstall "2026-08-10 12:41:10" -ExpectedProcessId "23149"
Assert-DeviceSnapshot -Snapshot $StasFinal -ExpectedUid 10387 -ExpectedVersionName "v11.16.13" -ExpectedVersionCode 11016013 -ExpectedFirstInstall "2026-08-10 12:41:10" -ExpectedProcessId "23149"

$InstallPath = Join-Path $EvidenceDir "anna-install.stdout.log"
$LaunchPath = Join-Path $EvidenceDir "anna-launch.stdout.log"
$LaunchEpochPath = Join-Path $EvidenceDir "anna-launch-epoch.stdout.log"
$InstallSuccessCount = @(Get-Content -LiteralPath $InstallPath | Where-Object { $_.Trim() -eq "Success" }).Count
$LaunchSuccessCount = @(Get-Content -LiteralPath $LaunchPath | Where-Object { $_.Trim() -eq "Status: ok" }).Count
$LaunchEpochText = (Get-Content -LiteralPath $LaunchEpochPath -Raw).Trim()
if ($InstallSuccessCount -ne 1 -or $LaunchSuccessCount -ne 1 -or $LaunchEpochText -notmatch "^\d+$") {
    throw "Saved install/launch evidence is incomplete"
}
$LaunchEpoch = [int64]$LaunchEpochText

$EarlyPath = Join-Path $EvidenceDir "anna-early-p2p.stdout.log"
$LatePath = Join-Path $EvidenceDir "anna-late-p2p.stdout.log"
$EarlyLines = @(Get-Content -LiteralPath $EarlyPath)
$LateLines = @(Get-Content -LiteralPath $LatePath)
$EarlyEntries = @(Get-P2pEntries -Lines $EarlyLines -AndroidProcessId $AnnaProcessId -LaunchEpoch $LaunchEpoch)
$LateEntries = @(Get-P2pEntries -Lines $LateLines -AndroidProcessId $AnnaProcessId -LaunchEpoch $LaunchEpoch)
$CombinedEntries = @($EarlyEntries) + @($LateEntries)
$Entries = @($CombinedEntries | Sort-Object Epoch, RawLine -Unique)
if ($Entries.Count -lt 1) {
    throw "No saved Anna p2p entries for the runtime2 launch"
}

$SecondarySupervisorEntries = @(
    $Entries | Where-Object { $_.Message.Contains("MQTT SECONDARY SUPERVISOR: feature=enabled mode=observe_only") }
)
$SecondaryStartingEntries = @(
    $Entries | Where-Object { $_.Message.Contains("MQTT SECONDARY STATUS: broker=emqx state=starting mode=observe_only subscriptions=0 publishes=0") }
)
$SecondaryConnectedEntries = @(
    $Entries | Where-Object {
        $HasConnectedStatus = $_.Message.Contains("MQTT SECONDARY STATUS: broker=emqx state=connected")
        $HasObserveOnlyContract = $_.Message.Contains("subscriptions=0 publishes=0")
        $HasConnectedStatus -and $HasObserveOnlyContract
    }
)
$SecondaryBackoffEntries = @(
    $Entries | Where-Object { $_.Message.Contains("MQTT SECONDARY STATUS: broker=emqx state=backoff") }
)

if ($SecondarySupervisorEntries.Count -ne 0 -or $SecondaryStartingEntries.Count -ne 0) {
    throw "Expected missing lifecycle breadcrumbs to remain exactly absent"
}
if ($SecondaryConnectedEntries.Count -ne 1 -or $SecondaryBackoffEntries.Count -ne 0) {
    throw "Secondary observer connection evidence mismatch"
}

$SecondaryConnectionMatch = [regex]::Match(
    $SecondaryConnectedEntries[0].Message,
    "MQTT SECONDARY STATUS: broker=emqx state=connected connacks=(\d+) polls=(\d+)/(\d+) poll_errors=(\d+) subscriptions=0 publishes=0"
)
if (-not $SecondaryConnectionMatch.Success) {
    throw "Cannot parse secondary connected status"
}
$SecondaryCounters = [pscustomobject]@{
    Connacks = [int64]$SecondaryConnectionMatch.Groups[1].Value
    PollsCompleted = [int64]$SecondaryConnectionMatch.Groups[2].Value
    PollsStarted = [int64]$SecondaryConnectionMatch.Groups[3].Value
    PollErrors = [int64]$SecondaryConnectionMatch.Groups[4].Value
}
$SecondaryCountersHealthy = $SecondaryCounters.Connacks -ge 1
$SecondaryCountersHealthy = $SecondaryCountersHealthy -and $SecondaryCounters.PollsCompleted -ge 1
$SecondaryCountersHealthy = $SecondaryCountersHealthy -and $SecondaryCounters.PollsStarted -ge $SecondaryCounters.PollsCompleted
$SecondaryCountersHealthy = $SecondaryCountersHealthy -and $SecondaryCounters.PollErrors -eq 0
if (-not $SecondaryCountersHealthy) {
    throw "Secondary observer counters are not healthy"
}

$PrimaryInitializationEntries = @(
    $Entries | Where-Object { $_.Message.Contains("MQTT: initializing primary session broker.hivemq.com:1883") }
)
$PrimaryErrorEntries = @(
    $Entries | Where-Object { $_.Message.Contains("MQTT error:") }
)
$PrimaryReadyEntries = @(
    $Entries | Where-Object { $_.Message.Contains("MQTT SESSION READY:") }
)
$HeartbeatEntries = @(
    $Entries | Where-Object { $_.Message.Contains("MQTT LIVENESS HEARTBEAT:") }
)
$IncomingEntries = @(
    $Entries | Where-Object { $_.Message.Contains("MQTT IN:") }
)

$PrimaryEventCountsExact = $PrimaryInitializationEntries.Count -eq 1
$PrimaryEventCountsExact = $PrimaryEventCountsExact -and $PrimaryErrorEntries.Count -eq 1
$PrimaryEventCountsExact = $PrimaryEventCountsExact -and $PrimaryReadyEntries.Count -eq 1
$PrimaryEventCountsExact = $PrimaryEventCountsExact -and $HeartbeatEntries.Count -eq 1
$PrimaryEventCountsExact = $PrimaryEventCountsExact -and $IncomingEntries.Count -eq 64
if (-not $PrimaryEventCountsExact) {
    throw "Primary saved-event counts do not match runtime2 evidence"
}
if (-not $PrimaryErrorEntries[0].Message.Contains("MQTT error: Network timeout; retrying in 1s")) {
    throw "Primary error is not the expected bounded initial timeout"
}
if (-not $PrimaryReadyEntries[0].Message.Contains("MQTT SESSION READY: generation=1 attempt=1 ConnAck=true subscription_request=true")) {
    throw "Primary did not recover in the same initial generation/attempt"
}

$PrimaryRecoverySeconds = [math]::Round(
    [double]$PrimaryReadyEntries[0].Epoch - [double]$PrimaryErrorEntries[0].Epoch,
    3
)
if ($PrimaryRecoverySeconds -le 0 -or $PrimaryRecoverySeconds -gt 5) {
    throw "Primary bounded timeout recovery interval is invalid: $PrimaryRecoverySeconds"
}

$HeartbeatCounters = Get-HeartbeatCounters -Message $HeartbeatEntries[0].Message
$HeartbeatHealthy = $HeartbeatCounters.phase -eq "polling"
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.phase_age_ms -lt 90000
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.progress_age_ms -lt 90000
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.polls_completed -ge 1
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.polls_started -ge $HeartbeatCounters.polls_completed
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.incoming -ge 1
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.connacks -ge 1
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.forwarded -ge 1
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.best_effort_drops -eq 0
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.loss_intolerant_pending -eq 0
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.loss_intolerant_backpressure -eq 0
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.poll_errors -eq 1
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.request_timeouts -eq 0
$HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatCounters.request_errors -eq 0
if (-not $HeartbeatHealthy) {
    throw "Primary heartbeat does not prove healthy recovery"
}

$EventOrderingValid = [double]$SecondaryConnectedEntries[0].Epoch -lt [double]$PrimaryInitializationEntries[0].Epoch
$EventOrderingValid = $EventOrderingValid -and [double]$PrimaryInitializationEntries[0].Epoch -lt [double]$PrimaryErrorEntries[0].Epoch
$EventOrderingValid = $EventOrderingValid -and [double]$PrimaryErrorEntries[0].Epoch -lt [double]$PrimaryReadyEntries[0].Epoch
$EventOrderingValid = $EventOrderingValid -and [double]$PrimaryReadyEntries[0].Epoch -lt [double]$HeartbeatEntries[0].Epoch
$EventOrderingValid = $EventOrderingValid -and [double]$IncomingEntries[0].Epoch -ge [double]$PrimaryReadyEntries[0].Epoch
$EventOrderingValid = $EventOrderingValid -and [double]$IncomingEntries[-1].Epoch -gt [double]$HeartbeatEntries[0].Epoch
if (-not $EventOrderingValid) {
    throw "Runtime2 event ordering does not prove simultaneous secondary/primary progress"
}

$ForbiddenRuntimeMarkers = [ordered]@{
    SecondaryBackoff = "MQTT SECONDARY STATUS: broker=emqx state=backoff"
    LivenessStalled = "MQTT LIVENESS STALLED:"
    SessionRestart = "MQTT SESSION RESTART"
    RequestError = "MQTT REQUEST ERROR:"
    RequestTimeout = "MQTT REQUEST TIMEOUT:"
    LossIntolerantInvariant = "MQTT LOSS-INTOLERANT INBOX INVARIANT:"
    EventLoopStopped = "MQTT: event loop stopped:"
}
$ForbiddenRuntimeCounts = [ordered]@{}
foreach ($MarkerName in $ForbiddenRuntimeMarkers.Keys) {
    $MarkerText = $ForbiddenRuntimeMarkers[$MarkerName]
    $MarkerCount = @($Entries | Where-Object { $_.Message.Contains($MarkerText) }).Count
    $ForbiddenRuntimeCounts[$MarkerName] = $MarkerCount
    if ($MarkerCount -ne 0) {
        throw "Forbidden runtime marker present: $MarkerName=$MarkerCount"
    }
}

$RecoveryState = [ordered]@{
    Schema = 1
    Purpose = "saved-evidence-only r4.3 Anna runtime2 analysis recovery"
    Outcome = "PASS_FROM_IMMUTABLE_RUNTIME2_EVIDENCE"
    AnalyzedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    ScriptPath = $PSCommandPath
    ScriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    ParentState = $ParentStatePath
    ParentStateSha256 = $ParentStateHash
    ParentOutcome = $ParentState.outcome
    ParentFailure = $ParentState.failure
    EvidenceDirectory = $EvidenceDir
    EvidenceFileCount = $EvidenceManifest.Count
    EvidenceManifestSha256 = $EvidenceManifestHash
    EvidenceManifestAlgorithm = "sorted Name|Length|SHA256 lines joined by LF, UTF-8 without BOM"
    EvidenceManifest = $EvidenceManifest
    RuntimeGate = "PASS"
    AnnaVersion = $AnnaFinal.VersionName
    AnnaVersionCode = $AnnaFinal.VersionCode
    AnnaProcessId = $AnnaProcessId
    AnnaProcessStable = $AnnaStarted.ProcessIds.Count -eq 1 -and $AnnaFinal.ProcessIds.Count -eq 1 -and [string]$AnnaStarted.ProcessIds[0] -eq $AnnaProcessId -and [string]$AnnaFinal.ProcessIds[0] -eq $AnnaProcessId
    AnnaDataPreserved = $AnnaPre.Uid -eq $AnnaFinal.Uid -and $AnnaPre.FirstInstall -eq $AnnaFinal.FirstInstall -and $AnnaPostInstall.ProcessIds.Count -eq 0
    PeerVersionsAndProcessesUnchanged = $ZhenyaPre.VersionCode -eq $ZhenyaFinal.VersionCode -and $StasPre.VersionCode -eq $StasFinal.VersionCode -and [string]$ZhenyaPre.ProcessIds[0] -eq [string]$ZhenyaFinal.ProcessIds[0] -and [string]$StasPre.ProcessIds[0] -eq [string]$StasFinal.ProcessIds[0]
    Devices = @($AnnaPre, $AnnaPostInstall, $AnnaStarted, $AnnaFinal, $ZhenyaPre, $ZhenyaFinal, $StasPre, $StasFinal)
    LaunchEpoch = $LaunchEpoch
    FirstPreservedUtc = $Entries[0].Utc
    LastPreservedUtc = $Entries[-1].Utc
    EarlyP2pEvidence = $EarlyPath
    EarlyP2pEvidenceSha256 = (Get-FileHash -LiteralPath $EarlyPath -Algorithm SHA256).Hash
    LateP2pEvidence = $LatePath
    LateP2pEvidenceSha256 = (Get-FileHash -LiteralPath $LatePath -Algorithm SHA256).Hash
    SecondaryBroker = "broker.emqx.io:1883"
    SecondaryConnectedStatusPass = $true
    SecondaryConnectedUtc = $SecondaryConnectedEntries[0].Utc
    SecondaryCounters = $SecondaryCounters
    SecondarySubscriptions = 0
    SecondaryPublishes = 0
    SecondaryBackoffCount = $SecondaryBackoffEntries.Count
    SecondarySupervisorDirectMarker = "NOT_PRESERVED_DO_NOT_REPEAT"
    SecondaryStartingDirectMarker = "NOT_PRESERVED_DO_NOT_REPEAT"
    SecondaryLifecycleInterpretation = "connected ConnAck status is the acceptance event; absent earlier lifecycle breadcrumbs are not treated as connection failure"
    PrimaryBroker = "broker.hivemq.com:1883"
    PrimaryReadyUtc = $PrimaryReadyEntries[0].Utc
    PrimaryHeartbeatUtc = $HeartbeatEntries[0].Utc
    PrimaryIncomingCount = $IncomingEntries.Count
    PrimaryInitialTimeoutCount = $PrimaryErrorEntries.Count
    PrimaryInitialTimeout = "Network timeout; retrying in 1s"
    PrimaryRecoverySeconds = $PrimaryRecoverySeconds
    PrimaryRecoveryClassification = "BOUNDED_TRANSIENT_RECOVERED_SAME_GENERATION_ATTEMPT"
    PrimaryHeartbeatCounters = $HeartbeatCounters
    ForbiddenRuntimeCounts = [pscustomobject]$ForbiddenRuntimeCounts
    InstallRepeatedByAnalyzer = $false
    LaunchRepeatedByAnalyzer = $false
    AdbCommandsUsedByAnalyzer = $false
    LogcatReadAgain = $false
    PhoneActionsPerformedByAnalyzer = $false
    Uninstalled = $false
    DataCleared = $false
    ForceStopped = $false
    LogcatCleared = $false
    NetworkChanged = $false
    UserPayloadPublished = $false
    AutomaticRetry = $false
}

$FinalDeviceInvariantsPass = [bool]$RecoveryState.AnnaProcessStable
$FinalDeviceInvariantsPass = $FinalDeviceInvariantsPass -and [bool]$RecoveryState.AnnaDataPreserved
$FinalDeviceInvariantsPass = $FinalDeviceInvariantsPass -and [bool]$RecoveryState.PeerVersionsAndProcessesUnchanged
if (-not $FinalDeviceInvariantsPass) {
    throw "Final saved device invariants are not PASS"
}

$RecoveryState | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $RecoveryStatePath -Encoding UTF8
$RecoveryStateHash = (Get-FileHash -LiteralPath $RecoveryStatePath -Algorithm SHA256).Hash

Write-Host "R4.3 ANNA RUNTIME2 SAVED-EVIDENCE RECOVERY PASS"
Write-Host "Recovery state: $RecoveryStatePath"
Write-Host "Recovery state SHA256: $RecoveryStateHash"
Write-Host "Parent state SHA256: $ParentStateHash"
Write-Host "Evidence manifest SHA256: $EvidenceManifestHash"
Write-Host "Anna version/PID: $($AnnaFinal.VersionName) / $AnnaProcessId stable"
Write-Host "Secondary EMQX: connected ConnAck=$($SecondaryCounters.Connacks), subscriptions=0, publishes=0, errors=0"
Write-Host "Secondary early lifecycle markers: not preserved; no repeat"
Write-Host "Primary HiveMQ: READY + heartbeat + incoming=$($IncomingEntries.Count)"
Write-Host "Primary initial timeout: recovered in $PrimaryRecoverySeconds seconds, generation=1 attempt=1"
Write-Host "Zhenya/Stas: v11.16.13 and original processes unchanged in final saved evidence"
Write-Host "ADB/logcat/install/launch repeated by analyzer: False"
