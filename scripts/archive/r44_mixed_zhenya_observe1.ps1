$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedApplicationCommit = "0181b3496cde81477a01e49f8d9977d7c325a2ca"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$ExpectedNativeSize = 7248576
$ExpectedNativeHash = "E6C34E86F18D9F63B9A641E3FD9FAFD67D5F1B7101729B2CB3DF25163380095B"
$ParentAnalysisPath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-zhenya-launch1-analysis.json"
$ExpectedParentAnalysisHash = "5325CDB85CC6A3D88019270BFCE1E7ACDDD8E56A18FF17CEE89F3F1FBB21CAF4"
$StatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-zhenya-observe1.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-zhenya-observe1-evidence"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$PackageName = "com.vladimir.messenger"
$EarlyWaitSeconds = 15
$LateWaitSeconds = 135

$Phones = @(
    [pscustomobject]@{
        Name = "Anna"
        Serial = "AUYF6R5923006121"
        ExpectedUid = 10425
        ExpectedVersionName = "v11.16.15"
        ExpectedVersionCode = 11016015
        ExpectedFirstInstall = "2026-08-08 11:40:39"
        ExpectedProcessId = "12943"
    },
    [pscustomobject]@{
        Name = "Zhenya"
        Serial = "3B665800EES00000"
        ExpectedUid = 10395
        ExpectedVersionName = "v11.16.13"
        ExpectedVersionCode = 11016013
        ExpectedFirstInstall = "2026-08-08 17:31:18"
        ExpectedProcessId = "14811"
    },
    [pscustomobject]@{
        Name = "Stas"
        Serial = "11567254BK001192"
        ExpectedUid = 10387
        ExpectedVersionName = "v11.16.13"
        ExpectedVersionCode = 11016013
        ExpectedFirstInstall = "2026-08-10 12:41:10"
        ExpectedProcessId = "23149"
    }
)
$Zhenya = @($Phones | Where-Object { $_.Name -eq "Zhenya" })[0]

if (Test-Path -LiteralPath $StatePath) {
    throw "R4.4 MIXED ZHENYA OBSERVE1 ALREADY ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "R4.4 MIXED ZHENYA OBSERVE1 EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
}
foreach ($RequiredPath in @($Adb, $ParentAnalysisPath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required Zhenya observe input missing: $RequiredPath"
    }
}

$ParentAnalysisHash = (Get-FileHash -LiteralPath $ParentAnalysisPath -Algorithm SHA256).Hash
if ($ParentAnalysisHash -ne $ExpectedParentAnalysisHash) {
    throw "Zhenya launch1 analysis hash mismatch: $ParentAnalysisHash"
}
$ParentAnalysis = Get-Content -LiteralPath $ParentAnalysisPath -Raw | ConvertFrom-Json
$ParentValid = $ParentAnalysis.outcome -eq "PASS_FROM_IMMUTABLE_EVIDENCE"
$ParentValid = $ParentValid -and [string]$ParentAnalysis.zhenyaObservedProcessId -eq "14811"
$ParentValid = $ParentValid -and [int]$ParentAnalysis.evidenceFileCount -eq 20
$ParentValid = $ParentValid -and $ParentAnalysis.launchCommandEvidenceExists -eq $false
$ParentValid = $ParentValid -and $ParentAnalysis.launchStarted -eq $false
$ParentValid = $ParentValid -and $ParentAnalysis.adbCommandsUsedByAnalyzer -eq $false
$ParentValid = $ParentValid -and $ParentAnalysis.phonesChangedByLaunch1 -eq $false
$ParentValid = $ParentValid -and $ParentAnalysis.userPayloadPublished -eq $false
$ParentValid = $ParentValid -and $ParentAnalysis.automaticRetry -eq $false
if (-not $ParentValid) {
    throw "Parent analysis does not prove exact current-PID observe prerequisites"
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
    throw "Unexpected pre-observe worktree changes: $($UnexpectedBefore -join '; ')"
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
    param(
        [Parameter(Mandatory = $true)][object]$Phone,
        [Parameter(Mandatory = $true)][string]$Phase
    )

    $SafeName = $Phone.Name.ToLowerInvariant()
    $UidResult = Invoke-Captured $Adb @(
        "-s", $Phone.Serial, "shell", "cmd", "package", "list", "packages", "-U", $PackageName
    ) "$SafeName-$Phase-uid"
    $PackageResult = Invoke-Captured $Adb @(
        "-s", $Phone.Serial, "shell", "dumpsys", "package", $PackageName
    ) "$SafeName-$Phase-package"
    $ProcessResult = Invoke-Captured $Adb @(
        "-s", $Phone.Serial, "shell", "pidof", $PackageName
    ) "$SafeName-$Phase-process"

    $StderrCount = $UidResult.StandardError.Count
    $StderrCount += $PackageResult.StandardError.Count
    $StderrCount += $ProcessResult.StandardError.Count
    if ($StderrCount -ne 0) {
        throw "$($Phone.Name) snapshot returned stderr at $Phase"
    }
    if ($UidResult.ExitCodeAvailable -and $UidResult.ExitCode -ne 0) {
        throw "$($Phone.Name) UID command failed at $Phase"
    }
    if ($PackageResult.ExitCodeAvailable -and $PackageResult.ExitCode -ne 0) {
        throw "$($Phone.Name) package command failed at $Phase"
    }
    if ($ProcessResult.ExitCodeAvailable -and $ProcessResult.ExitCode -notin @(0, 1)) {
        throw "$($Phone.Name) process command failed at $Phase"
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
        throw "$($Phone.Name) snapshot parse failed at $Phase"
    }

    $ProcessText = ($ProcessResult.StandardOutput -join " ").Trim()
    $ProcessIds = @()
    if (-not [string]::IsNullOrWhiteSpace($ProcessText)) {
        $ProcessTokens = @($ProcessText -split "\s+")
        $InvalidTokens = @($ProcessTokens | Where-Object { $_ -notmatch "^\d+$" })
        if ($InvalidTokens.Count -ne 0) {
            throw "$($Phone.Name) process output parse failed at $Phase"
        }
        $ProcessIds = @($ProcessTokens)
    }
    if (
        $ProcessResult.ExitCodeAvailable -and
        $ProcessResult.ExitCode -eq 1 -and
        $ProcessIds.Count -ne 0
    ) {
        throw "$($Phone.Name) pidof exit/output mismatch at $Phase"
    }

    return [pscustomobject]@{
        Name = $Phone.Name
        Phase = $Phase
        Uid = [int64]$UidMatch.Groups[1].Value
        VersionName = $VersionNameMatch.Groups[1].Value.Trim()
        VersionCode = [int64]$VersionCodeMatch.Groups[1].Value
        FirstInstall = $FirstInstallMatch.Groups[1].Value.Trim()
        DataDir = $DataDirMatch.Groups[1].Value.Trim()
        ProcessIds = @($ProcessIds)
        Running = $ProcessIds.Count -gt 0
    }
}

function Test-IdentityAndProcess {
    param(
        [Parameter(Mandatory = $true)][object]$Phone,
        [Parameter(Mandatory = $true)][object]$Snapshot
    )
    $Valid = $Snapshot.Uid -eq $Phone.ExpectedUid
    $Valid = $Valid -and $Snapshot.VersionName -eq $Phone.ExpectedVersionName
    $Valid = $Valid -and $Snapshot.VersionCode -eq $Phone.ExpectedVersionCode
    $Valid = $Valid -and $Snapshot.FirstInstall -eq $Phone.ExpectedFirstInstall
    $Valid = $Valid -and $Snapshot.DataDir -eq "/data/user/0/$PackageName"
    $Valid = $Valid -and $Snapshot.ProcessIds.Count -eq 1
    $Valid = $Valid -and [string]$Snapshot.ProcessIds[0] -eq $Phone.ExpectedProcessId
    return $Valid
}

function Get-DeviceEpoch {
    $Result = Invoke-Captured $Adb @("-s", $Zhenya.Serial, "shell", "date", "+%s") "zhenya-observe-epoch"
    $EpochText = ($Result.StandardOutput -join "").Trim()
    if ($Result.StandardError.Count -ne 0 -or $EpochText -notmatch "^\d+$") {
        throw "Zhenya observe epoch capture failed"
    }
    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
        throw "Zhenya observe epoch command failed"
    }
    return [int64]$EpochText
}

function Get-NativeLines {
    param([Parameter(Mandatory = $true)][string]$Phase)

    $Result = Invoke-Captured $Adb @(
        "-s", $Zhenya.Serial, "logcat", "-d", "-v", "epoch", "p2p_core:V", "*:S"
    ) "zhenya-$Phase-p2p"
    if ($Result.StandardError.Count -ne 0) {
        throw "Zhenya native log capture returned stderr at $Phase"
    }
    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
        throw "Zhenya native log capture failed at $Phase"
    }

    $Filtered = [System.Collections.Generic.List[string]]::new()
    $Pattern = "^\s*(\d+\.\d+)\s+14811\s+"
    foreach ($Line in $Result.StandardOutput) {
        $LineMatch = [regex]::Match($Line, $Pattern)
        if (-not $LineMatch.Success) {
            continue
        }
        $LineEpoch = [double]::Parse(
            $LineMatch.Groups[1].Value,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        if ($LineEpoch -ge [double]$ObservationEpoch) {
            $Filtered.Add($Line)
        }
    }
    return @($Filtered)
}

function Count-Text {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Lines,
        [Parameter(Mandatory = $true)][string]$Text
    )
    return @($Lines | Where-Object { $_.Contains($Text) }).Count
}

function Get-LastLineEpoch {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Lines)
    if ($Lines.Count -eq 0) {
        return $null
    }
    $LineMatch = [regex]::Match($Lines[-1], "^\s*(\d+\.\d+)")
    if (-not $LineMatch.Success) {
        throw "Cannot parse saved event epoch"
    }
    return [double]::Parse(
        $LineMatch.Groups[1].Value,
        [System.Globalization.CultureInfo]::InvariantCulture
    )
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$ObservationStarted = $false
$ObservationEpoch = $null
$PreSnapshots = [ordered]@{}
$FinalSnapshots = [ordered]@{}
$Metrics = $null
$LastHeartbeat = $null

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
        if (@($ConnectedLines | Where-Object { $_ -match $DevicePattern }).Count -ne 1) {
            throw "$($Phone.Name) is not connected exactly once as an authorized device"
        }
    }

    foreach ($Phone in $Phones) {
        $Snapshot = Get-PhoneSnapshot $Phone "pre"
        $PreSnapshots[$Phone.Name] = $Snapshot
        if (-not (Test-IdentityAndProcess $Phone $Snapshot)) {
            throw "$($Phone.Name) current-PID pre-observe gate failed"
        }
    }

    $ObservationEpoch = Get-DeviceEpoch
    $ObservationStarted = $true
    Start-Sleep -Seconds $EarlyWaitSeconds
    $EarlyLines = @(Get-NativeLines "early")
    Start-Sleep -Seconds $LateWaitSeconds
    $LateLines = @(Get-NativeLines "late")

    foreach ($Phone in $Phones) {
        $Snapshot = Get-PhoneSnapshot $Phone "final"
        $FinalSnapshots[$Phone.Name] = $Snapshot
        if (-not (Test-IdentityAndProcess $Phone $Snapshot)) {
            throw "$($Phone.Name) current-PID final gate failed"
        }
    }

    $AllLines = @(@($EarlyLines) + @($LateLines) | Sort-Object -Unique)
    $ReadyLines = @($AllLines | Where-Object { $_.Contains("MQTT SESSION READY:") })
    $ReconnectLines = @($AllLines | Where-Object { $_.Contains("MQTT: connection restored; subscription requested") })
    $ErrorLines = @($AllLines | Where-Object { $_.Contains("MQTT error:") })
    $HeartbeatLines = @($AllLines | Where-Object { $_.Contains("MQTT LIVENESS HEARTBEAT:") })

    $Metrics = [ordered]@{
        sessionReadyInWindow = $ReadyLines.Count
        reconnectInWindow = $ReconnectLines.Count
        incomingPublish = Count-Text $AllLines "MQTT IN:"
        heartbeat = $HeartbeatLines.Count
        pollErrors = $ErrorLines.Count
        overflowDrops = Count-Text $AllLines "MQTT OVERFLOW DROP:"
        lossIntolerantBackpressure = Count-Text $AllLines "MQTT LOSS-INTOLERANT BACKPRESSURE:"
        inboxInvariant = Count-Text $AllLines "MQTT LOSS-INTOLERANT INBOX INVARIANT:"
        stalls = Count-Text $AllLines "MQTT LIVENESS STALLED:"
        eventLoopEnded = Count-Text $AllLines "MQTT LIVENESS: EventLoop task"
        channelClosed = Count-Text $AllLines "MQTT LIVENESS: core notification channel closed"
        restartRequired = Count-Text $AllLines "MQTT SESSION RESTART REQUIRED:"
        restartScheduled = Count-Text $AllLines "MQTT SESSION RESTART SCHEDULED:"
        restartFailed = Count-Text $AllLines "MQTT SESSION RESTART FAILED:"
        requestTimeouts = Count-Text $AllLines "MQTT REQUEST TIMEOUT:"
        requestErrors = Count-Text $AllLines "MQTT REQUEST ERROR:"
    }

    if ($ErrorLines.Count -gt 0) {
        $RecoveryLines = @(@($ReadyLines) + @($ReconnectLines) | Sort-Object -Unique)
        $LastRecoveryEpoch = Get-LastLineEpoch $RecoveryLines
        $LastErrorEpoch = Get-LastLineEpoch $ErrorLines
        if ($null -eq $LastRecoveryEpoch -or $LastRecoveryEpoch -le $LastErrorEpoch) {
            throw "Zhenya HiveMQ session did not recover after its last observed error"
        }
    }

    if ($HeartbeatLines.Count -lt 1) {
        throw "Zhenya current-PID heartbeat missing"
    }
    $LastHeartbeat = $HeartbeatLines[-1]
    $HeartbeatHealthy = $LastHeartbeat -match "phase=polling"
    $HeartbeatHealthy = $HeartbeatHealthy -and $LastHeartbeat -match "connacks=([1-9]\d*)"
    $HeartbeatHealthy = $HeartbeatHealthy -and $LastHeartbeat -match "loss_intolerant_pending=0"
    $HeartbeatHealthy = $HeartbeatHealthy -and $LastHeartbeat -match "request_timeouts=0"
    $HeartbeatHealthy = $HeartbeatHealthy -and $LastHeartbeat -match "request_errors=0"
    if (-not $HeartbeatHealthy) {
        throw "Zhenya current-PID heartbeat counters are not healthy"
    }

    $RuntimePass = $Metrics.incomingPublish -ge 1
    $RuntimePass = $RuntimePass -and $Metrics.heartbeat -ge 1
    $RuntimePass = $RuntimePass -and $Metrics.overflowDrops -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.lossIntolerantBackpressure -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.inboxInvariant -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.stalls -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.eventLoopEnded -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.channelClosed -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.restartRequired -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.restartScheduled -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.restartFailed -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.requestTimeouts -eq 0
    $RuntimePass = $RuntimePass -and $Metrics.requestErrors -eq 0
    if (-not $RuntimePass) {
        throw "Zhenya current-PID observe matrix incomplete: $($Metrics | ConvertTo-Json -Compress)"
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
        purpose = "observe-only current-PID Zhenya v11.16.13 HiveMQ readiness for r4.4 mixed acceptance"
        outcome = $Outcome
        failure = $Failure
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        applicationCommit = $ExpectedApplicationCommit
        windowsHead = $CurrentHead
        parentLaunch1Analysis = $ParentAnalysisPath
        parentLaunch1AnalysisSha256 = $ParentAnalysisHash
        evidenceDirectory = $EvidenceDir
        observationStarted = $ObservationStarted
        observationEpoch = $ObservationEpoch
        earlyWaitSeconds = $EarlyWaitSeconds
        lateWaitSeconds = $LateWaitSeconds
        observedProcessId = "14811"
        preSnapshots = $PreSnapshots
        finalSnapshots = $FinalSnapshots
        metrics = $Metrics
        lastHeartbeat = $LastHeartbeat
        requiredUsbPhones = @("Anna", "Zhenya", "Stas")
        installStarted = $false
        launchStarted = $false
        forceStopped = $false
        logcatCleared = $false
        networkChanged = $false
        userPayloadPublished = $false
        automaticRetry = $false
    }
    $State | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:               $Outcome"
    Write-Host "State:                 $StatePath"
    Write-Host "State SHA256:          $StateHash"
    Write-Host "Observed Zhenya PID:   14811"
    Write-Host "Metrics:               $($Metrics | ConvertTo-Json -Compress)"
    Write-Host "Install/launch:        False / False"
    Write-Host "Phone changes/payload: False / False"
}

if ($Outcome -ne "PASS") {
    throw "r4.4 mixed Zhenya observe1 did not pass; see state: $StatePath"
}

Write-Host "R4.4 MIXED ZHENYA V11.16.13 OBSERVE1 PASS"
