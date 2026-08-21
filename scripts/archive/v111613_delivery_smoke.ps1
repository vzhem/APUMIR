$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$PublisherScript = Join-Path $PSScriptRoot "v111613_publish_once.py"
$RuntimeStatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-launch-analysis-recovery.json"
$StatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-delivery-smoke.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-delivery-smoke-evidence"
$RequestPath = Join-Path $EvidenceDir "publisher-request.json"
$PublisherResultPath = Join-Path $EvidenceDir "publisher-result.json"
$ExpectedRuntimeStateHash = "7FDAF9BD2634A322186AECB704A393F132D9B66D9A891C568A9DB3E041C3A89F"
$TargetVersionName = "v11.16.13"
$TargetVersionCode = 11016013
$PackageName = "com.vladimir.messenger"
$BrokerHost = "broker.hivemq.com"
$BrokerPort = 1883

$Devices = @(
    [pscustomobject]@{ Name = "Anna"; Serial = "AUYF6R5923006121"; ExpectedUid = 10425 },
    [pscustomobject]@{ Name = "Zhenya"; Serial = "3B665800EES00000"; ExpectedUid = 10395 },
    [pscustomobject]@{ Name = "Stas"; Serial = "11567254BK001192"; ExpectedUid = 10387 }
)

if (Test-Path -LiteralPath $StatePath) {
    throw "DELIVERY SMOKE ALREADY COMPLETED OR ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "DELIVERY SMOKE EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
}
foreach ($RequiredPath in @($Adb, $PublisherScript, $RuntimeStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required file missing: $RequiredPath"
    }
}

$RuntimeStateHash = (Get-FileHash -LiteralPath $RuntimeStatePath -Algorithm SHA256).Hash
if ($RuntimeStateHash -ne $ExpectedRuntimeStateHash) {
    throw "Runtime state hash mismatch: $RuntimeStateHash"
}

$RuntimeState = Get-Content -LiteralPath $RuntimeStatePath -Raw | ConvertFrom-Json
if ($RuntimeState.Outcome -ne "PASS_RUNTIME_DIRECT_STARTUP_2_OF_3" -or
    $RuntimeState.RuntimePassCount -ne 3 -or
    $RuntimeState.RelaunchPerformed -ne $false -or
    $RuntimeState.UserPayloadPublished -ne $false) {
    throw "Runtime state is not a clean v11.16.13 PASS"
}

$LaunchStatePath = [string]$RuntimeState.ParentLaunchState
$LaunchState = Get-Content -LiteralPath $LaunchStatePath -Raw | ConvertFrom-Json
$LaunchEvidenceDir = [string]$LaunchState.EvidenceDirectory
if (-not (Test-Path -LiteralPath $LaunchEvidenceDir)) {
    throw "Launch evidence directory missing"
}

New-Item -ItemType Directory -Path $EvidenceDir | Out-Null

function Save-JsonAtomic {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [object]$Value
    )

    $TemporaryPath = "$Path.tmp"
    $Json = $Value | ConvertTo-Json -Depth 14
    [IO.File]::WriteAllText($TemporaryPath, $Json, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $TemporaryPath -Destination $Path -Force
}

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

function Get-CurrentDeviceState {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Device,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $SafeName = $Device.Name.ToLowerInvariant()
    $UidResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "shell", "cmd", "package", "list", "packages", "-U",
            $PackageName
        ) `
        -Label ("{0}-{1}-uid" -f $SafeName, $Label)

    $DumpResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @("-s", $Device.Serial, "shell", "dumpsys", "package", $PackageName) `
        -Label ("{0}-{1}-package" -f $SafeName, $Label)

    $PidResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @("-s", $Device.Serial, "shell", "pidof", $PackageName) `
        -Label ("{0}-{1}-pidof" -f $SafeName, $Label)

    if ($UidResult.ExitCode -ne 0 -or $DumpResult.ExitCode -ne 0 -or $PidResult.ExitCode -notin @(0, 1)) {
        throw "Device state query failed: $($Device.Name), label=$Label"
    }

    $UidText = $UidResult.StdoutLines -join ""
    $DumpText = $DumpResult.StdoutLines -join "`n"
    $UidMatch = [regex]::Match($UidText, "uid:(\d+)")
    $VersionNameMatch = [regex]::Match($DumpText, "versionName=([^\s\r\n]+)")
    $VersionCodeMatch = [regex]::Match($DumpText, "versionCode=(\d+)")
    if (-not $UidMatch.Success -or -not $VersionNameMatch.Success -or -not $VersionCodeMatch.Success) {
        throw "Cannot parse device state: $($Device.Name)"
    }

    $ProcessIds = @(
        $PidResult.StdoutLines |
            ForEach-Object { $_ -split "\s+" } |
            Where-Object { $_ -match "^\d+$" }
    )

    return [pscustomobject]@{
        AppUid = [int]$UidMatch.Groups[1].Value
        VersionName = $VersionNameMatch.Groups[1].Value.Trim()
        VersionCode = [int64]$VersionCodeMatch.Groups[1].Value
        ProcessIds = @($ProcessIds)
        ProcessRunning = ($ProcessIds.Count -gt 0)
        PidofExitCode = $PidResult.ExitCode
    }
}

function Get-DeviceEpoch {
    param([Parameter(Mandatory = $true)][pscustomobject]$Device)

    $Result = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @("-s", $Device.Serial, "shell", "date", "+%s") `
        -Label ("{0}-baseline-epoch" -f $Device.Name.ToLowerInvariant())

    if ($Result.ExitCode -ne 0) {
        throw "Device epoch query failed: $($Device.Name)"
    }
    $Text = ($Result.StdoutLines -join "").Trim()
    if ($Text -notmatch "^\d+$") {
        throw "Invalid device epoch: $($Device.Name)"
    }
    return [int64]$Text
}

function Get-FilteredLogLines {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Device,

        [Parameter(Mandatory = $true)]
        [string]$ProcessId,

        [Parameter(Mandatory = $true)]
        [int64]$NotBeforeEpoch,

        [Parameter(Mandatory = $true)]
        [string]$Kind
    )

    $SafeName = $Device.Name.ToLowerInvariant()
    if ($Kind -eq "native") {
        $Arguments = @(
            "-s", $Device.Serial, "logcat", "-d", "-v", "epoch", "p2p_core:V", "*:S"
        )
    } else {
        $Arguments = @(
            "-s", $Device.Serial, "logcat", "-d", "-v", "epoch",
            "CoreServerService:V", "RustBridge:V", "AndroidRuntime:E", "libc:F", "*:S"
        )
    }

    $Result = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList $Arguments `
        -Label ("{0}-after-{1}" -f $SafeName, $Kind)

    if ($Result.ExitCode -ne 0) {
        throw "Log capture failed: $($Device.Name), kind=$Kind"
    }

    $Filtered = [System.Collections.Generic.List[string]]::new()
    $PidPattern = "\s{0}\s" -f [regex]::Escape($ProcessId)
    foreach ($Line in $Result.StdoutLines) {
        $EpochMatch = [regex]::Match($Line, "^\s*(\d+\.\d+)")
        if (-not $EpochMatch.Success) {
            continue
        }
        $Epoch = [double]::Parse(
            $EpochMatch.Groups[1].Value,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        if ($Epoch -ge [double]$NotBeforeEpoch -and $Line -match $PidPattern) {
            $Filtered.Add($Line)
        }
    }
    return @($Filtered)
}

function Count-Lines {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$Lines,

        [Parameter(Mandatory = $true)]
        [string[]]$Needles
    )

    $Count = 0
    foreach ($Line in $Lines) {
        $MatchesAll = $true
        foreach ($Needle in $Needles) {
            if (-not $Line.Contains($Needle)) {
                $MatchesAll = $false
                break
            }
        }
        if ($MatchesAll) {
            $Count++
        }
    }
    return $Count
}

function Get-SavedPeerSets {
    $Sets = [ordered]@{}
    foreach ($Analysis in $LaunchState.AnalysisDevices) {
        $Name = [string]$Analysis.Name
        $SafeName = $Name.ToLowerInvariant()
        $ProcessId = [string]$Analysis.ProcessId
        $LaunchEpoch = [double]$Analysis.LaunchEpoch
        $PidPattern = "^\s*(\d+\.\d+)\s+{0}\s+" -f [regex]::Escape($ProcessId)
        $Ids = [System.Collections.Generic.List[string]]::new()

        foreach ($Phase in @("early", "late")) {
            $Path = Join-Path $LaunchEvidenceDir ("{0}-{1}-p2p-core.stdout.log" -f $SafeName, $Phase)
            if (-not (Test-Path -LiteralPath $Path)) {
                throw "Launch peer evidence missing: $Path"
            }
            foreach ($Line in @(Get-Content -LiteralPath $Path)) {
                $LineMatch = [regex]::Match($Line, $PidPattern)
                if (-not $LineMatch.Success) {
                    continue
                }
                $Epoch = [double]::Parse(
                    $LineMatch.Groups[1].Value,
                    [System.Globalization.CultureInfo]::InvariantCulture
                )
                if ($Epoch -lt $LaunchEpoch) {
                    continue
                }
                $PeerMatch = [regex]::Match($Line, "MQTT: peer online: .*\((pk_[A-Za-z0-9_-]+)\)")
                if ($PeerMatch.Success) {
                    $Ids.Add($PeerMatch.Groups[1].Value)
                }
            }
        }
        $Sets[$Name] = @($Ids | Sort-Object -Unique)
    }
    return $Sets
}

function Resolve-NodeIds {
    param([Parameter(Mandatory = $true)][object]$PeerSets)

    $Resolved = [ordered]@{}
    foreach ($Device in $Devices) {
        $OtherNames = @($Devices | Where-Object { $_.Name -ne $Device.Name } | ForEach-Object { $_.Name })
        $OwnPeers = @($PeerSets[$Device.Name])
        $FirstOtherPeers = @($PeerSets[$OtherNames[0]])
        $SecondOtherPeers = @($PeerSets[$OtherNames[1]])
        $Candidates = @(
            $FirstOtherPeers |
                Where-Object {
                    $SecondOtherPeers -contains $_ -and $OwnPeers -notcontains $_
                } |
                Sort-Object -Unique
        )
        if ($Candidates.Count -ne 1) {
            throw "Cannot resolve exactly one node ID for $($Device.Name): $($Candidates -join ',')"
        }
        $Resolved[$Device.Name] = $Candidates[0]
    }

    if (@($Resolved.Values | Sort-Object -Unique).Count -ne 3) {
        throw "Resolved node IDs are not unique"
    }
    return $Resolved
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$PublishCalled = $false
$PublishConfirmed = $false
$TestId = $null
$MessageText = $null
$Topic = $null
$Envelope = $null
$NodeIds = $null
$PublisherExitCode = $null
$PhoneStates = [System.Collections.Generic.List[object]]::new()
$Metrics = [ordered]@{}
$Failures = [System.Collections.Generic.List[string]]::new()

try {
    $LaunchState = Get-Content -LiteralPath $LaunchStatePath -Raw | ConvertFrom-Json
    $PeerSets = Get-SavedPeerSets
    $NodeIds = Resolve-NodeIds -PeerSets $PeerSets

    $DevicesResult = Invoke-CapturedNative -FilePath $Adb -ArgumentList @("devices") -Label "adb-devices"
    if ($DevicesResult.ExitCode -ne 0) {
        throw "adb devices failed"
    }
    $Connected = @($DevicesResult.StdoutLines | Select-Object -Skip 1 | Where-Object { $_ -match "\S" })

    foreach ($Device in $Devices) {
        $SerialPattern = "^{0}\s+device$" -f [regex]::Escape($Device.Serial)
        if (@($Connected | Where-Object { $_ -match $SerialPattern }).Count -ne 1) {
            throw "$($Device.Name) is not connected exactly once"
        }

        $RuntimeDevice = @($RuntimeState.Devices | Where-Object { $_.Name -eq $Device.Name })[0]
        $Current = Get-CurrentDeviceState -Device $Device -Label "pre"
        if ($Current.AppUid -ne $Device.ExpectedUid -or
            $Current.VersionName -ne $TargetVersionName -or
            $Current.VersionCode -ne $TargetVersionCode -or
            $Current.ProcessIds.Count -ne 1 -or
            [string]$Current.ProcessIds[0] -ne [string]$RuntimeDevice.ProcessId) {
            throw "Runtime identity/PID precheck failed: $($Device.Name)"
        }

        $BaselineEpoch = Get-DeviceEpoch -Device $Device
        $PhoneStates.Add([pscustomobject]@{
            Name = $Device.Name
            Serial = $Device.Serial
            ExpectedProcessId = [string]$RuntimeDevice.ProcessId
            BaselineEpoch = $BaselineEpoch
            PreState = $Current
            FinalState = $null
        })
    }

    $PyPath = (Get-Command py -ErrorAction Stop).Source
    $PahoCheck = Invoke-CapturedNative `
        -FilePath $PyPath `
        -ArgumentList @("-3", $PublisherScript, "--check") `
        -Label "python-paho-check"
    if ($PahoCheck.ExitCode -ne 0 -or ($PahoCheck.StdoutLines -join "") -notmatch "paho-ok") {
        throw "paho publisher preflight failed before state/publish"
    }

    $TimestampPart = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $GuidPart = [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $Suffix = "{0}-{1}" -f $TimestampPart, $GuidPart
    $TestId = "v111613-smoke-$Suffix"
    $MessageText = "APU v11.16.13 functional smoke $Suffix"
    $OriginNodeId = [string]$NodeIds["Anna"]
    $RecipientNodeId = [string]$NodeIds["Stas"]
    $PayloadBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($MessageText))
    $Topic = "p2pm2/msg/$RecipientNodeId"
    $Envelope = "relay|$TestId|$RecipientNodeId|$OriginNodeId|apu-v111613-smoke|3600|0|$PayloadBase64"

    # Prove the fresh GUID-derived ID is absent before publication.
    foreach ($Analysis in $LaunchState.AnalysisDevices) {
        $SafeName = ([string]$Analysis.Name).ToLowerInvariant()
        foreach ($Phase in @("early", "late")) {
            $Path = Join-Path $LaunchEvidenceDir ("{0}-{1}-p2p-core.stdout.log" -f $SafeName, $Phase)
            if (Select-String -LiteralPath $Path -SimpleMatch $TestId -Quiet) {
                throw "Fresh smoke ID already exists in launch evidence"
            }
        }
    }

    $Request = [ordered]@{
        schema = 1
        testId = $TestId
        broker = [ordered]@{ host = $BrokerHost; port = $BrokerPort }
        topic = $Topic
        envelope = $Envelope
        qos = 1
        retained = $false
    }
    Save-JsonAtomic -Path $RequestPath -Value $Request

    $StateBeforePublish = [ordered]@{
        schema = 1
        status = "READY_TO_PUBLISH_ONCE"
        createdUtc = (Get-Date).ToUniversalTime().ToString("o")
        completedUtc = $null
        userApproved = $true
        runtimeState = $RuntimeStatePath
        runtimeStateSha256 = $RuntimeStateHash
        scriptPath = $PSCommandPath
        scriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        publisherScriptPath = $PublisherScript
        publisherScriptSha256 = (Get-FileHash -LiteralPath $PublisherScript -Algorithm SHA256).Hash
        testId = $TestId
        messageText = $MessageText
        nodeIds = $NodeIds
        broker = [ordered]@{ host = $BrokerHost; port = $BrokerPort }
        topic = $Topic
        envelope = $Envelope
        qos = 1
        retained = $false
        phoneStates = @($PhoneStates)
        publishCalled = $false
        publishConfirmed = $false
        publisherExitCode = $null
        publisherResult = $null
        metrics = $null
        failures = @()
        evidenceDirectory = $EvidenceDir
        forceStopped = $false
        launched = $false
        logcatCleared = $false
        networkChanged = $false
        publicTrafficSent = $false
        automaticRetry = $false
    }
    Save-JsonAtomic -Path $StatePath -Value $StateBeforePublish

    # Final PID membership immediately before the only publisher process.
    foreach ($Device in $Devices) {
        $Phone = @($PhoneStates | Where-Object { $_.Name -eq $Device.Name })[0]
        $Current = Get-CurrentDeviceState -Device $Device -Label "immediate-prepublish"
        if ($Current.ProcessIds.Count -ne 1 -or [string]$Current.ProcessIds[0] -ne $Phone.ExpectedProcessId) {
            throw "PID changed immediately before publish: $($Device.Name)"
        }
    }

    $PublisherRun = Invoke-CapturedNative `
        -FilePath $PyPath `
        -ArgumentList @("-3", $PublisherScript, $RequestPath, $PublisherResultPath) `
        -Label "publisher-once"
    $PublisherExitCode = $PublisherRun.ExitCode

    if (Test-Path -LiteralPath $PublisherResultPath) {
        $PublisherResult = Get-Content -LiteralPath $PublisherResultPath -Raw | ConvertFrom-Json
        $PublishCalled = [bool]$PublisherResult.publishCalled
        $PublishConfirmed = [bool]$PublisherResult.publishConfirmed
    } else {
        $PublisherResult = $null
    }

    if ($PublishCalled) {
        Start-Sleep -Seconds 30
    } else {
        Start-Sleep -Seconds 2
    }

    foreach ($Device in $Devices) {
        $Phone = @($PhoneStates | Where-Object { $_.Name -eq $Device.Name })[0]
        $Final = Get-CurrentDeviceState -Device $Device -Label "final"
        $Phone.FinalState = $Final

        $NativeLines = @(Get-FilteredLogLines `
            -Device $Device `
            -ProcessId $Phone.ExpectedProcessId `
            -NotBeforeEpoch ($Phone.BaselineEpoch - 1) `
            -Kind "native")
        $KotlinLines = @(Get-FilteredLogLines `
            -Device $Device `
            -ProcessId $Phone.ExpectedProcessId `
            -NotBeforeEpoch ($Phone.BaselineEpoch - 1) `
            -Kind "kotlin")

        $UnexpectedRelay = 0
        foreach ($Needle in @(
            "MESH relay: duplicate local delivery $TestId suppressed",
            "MESH relay: previously seen $TestId ignored after cleanup",
            "MESH relay: conflicting origin for $TestId",
            "MESH relay: failed to store $TestId",
            "MESH relay: $TestId reached hop limit",
            "MESH relay: queue unavailable, dropped $TestId"
        )) {
            $UnexpectedRelay += Count-Lines -Lines $NativeLines -Needles @($Needle)
        }

        $Metrics[$Device.Name] = [ordered]@{
            relayInput = Count-Lines -Lines $NativeLines -Needles @("MQTT IN:", "relay|$TestId|")
            stored = Count-Lines -Lines $NativeLines -Needles @("MESH relay: stored $TestId for ")
            localDelivery = Count-Lines -Lines $NativeLines -Needles @("MESH relay: $TestId delivered to local recipient")
            receiptInput = Count-Lines -Lines $NativeLines -Needles @("MQTT IN:", "receipt|$TestId|")
            receiptSent = Count-Lines -Lines $NativeLines -Needles @("MESH receipt: $TestId sent automatically to unique origin topic")
            removed = Count-Lines -Lines $NativeLines -Needles @("MESH receipt: removed $TestId for ")
            originDelivery = Count-Lines -Lines $NativeLines -Needles @("MESH receipt: $TestId delivered at origin")
            retainedReceiptCleared = Count-Lines -Lines $NativeLines -Needles @("MESH receipt: cleared retained topic for $TestId")
            uiMessageReceived = Count-Lines -Lines $KotlinLines -Needles @("MESSAGE_RECEIVED:", "msgId=$TestId")
            unexpectedRelay = $UnexpectedRelay
            mqttErrors = Count-Lines -Lines $NativeLines -Needles @("MQTT error:")
            overflowDrops = Count-Lines -Lines $NativeLines -Needles @("MQTT OVERFLOW DROP:")
            lossBackpressure = Count-Lines -Lines $NativeLines -Needles @("MQTT LOSS-INTOLERANT BACKPRESSURE:")
            lossInvariant = Count-Lines -Lines $NativeLines -Needles @("MQTT LOSS-INTOLERANT INBOX INVARIANT:")
            stalls = Count-Lines -Lines $NativeLines -Needles @("MQTT LIVENESS STALLED:")
            restarts = Count-Lines -Lines $NativeLines -Needles @("MQTT SESSION RESTART")
            requestTimeouts = Count-Lines -Lines $NativeLines -Needles @("MQTT REQUEST TIMEOUT:")
            requestErrors = Count-Lines -Lines $NativeLines -Needles @("MQTT REQUEST ERROR:")
            crashAnr = Count-Lines -Lines $KotlinLines -Needles @("FATAL EXCEPTION")
            expectedPidStable = ($Final.ProcessIds.Count -eq 1 -and [string]$Final.ProcessIds[0] -eq $Phone.ExpectedProcessId)
            finalProcessIds = @($Final.ProcessIds)
        }
    }

    if ($PublisherExitCode -ne 0) { $Failures.Add("publisher exit=$PublisherExitCode") }
    if (-not $PublishCalled) { $Failures.Add("publishCalled=false") }
    if (-not $PublishConfirmed) { $Failures.Add("publishConfirmed=false") }

    $Expected = [ordered]@{
        Anna = [ordered]@{ relayInput=1; stored=1; localDelivery=0; receiptInput=1; receiptSent=0; removed=1; originDelivery=1; retainedReceiptCleared=1; uiMessageReceived=0 }
        Zhenya = [ordered]@{ relayInput=1; stored=1; localDelivery=0; receiptInput=1; receiptSent=0; removed=1; originDelivery=0; retainedReceiptCleared=0; uiMessageReceived=0 }
        Stas = [ordered]@{ relayInput=1; stored=0; localDelivery=1; receiptInput=1; receiptSent=1; removed=0; originDelivery=0; retainedReceiptCleared=0; uiMessageReceived=1 }
    }

    foreach ($Device in $Devices) {
        $Name = $Device.Name
        foreach ($MetricName in $Expected[$Name].Keys) {
            if ([int]$Metrics[$Name][$MetricName] -ne [int]$Expected[$Name][$MetricName]) {
                $Failures.Add("$Name $MetricName expected=$($Expected[$Name][$MetricName]) actual=$($Metrics[$Name][$MetricName])")
            }
        }
        foreach ($ZeroName in @(
            "unexpectedRelay", "mqttErrors", "overflowDrops", "lossBackpressure", "lossInvariant",
            "stalls", "restarts", "requestTimeouts", "requestErrors", "crashAnr"
        )) {
            if ([int]$Metrics[$Name][$ZeroName] -ne 0) {
                $Failures.Add("$Name $ZeroName=$($Metrics[$Name][$ZeroName])")
            }
        }
        if (-not [bool]$Metrics[$Name].expectedPidStable) {
            $Failures.Add("$Name PID changed")
        }
    }

    if ($Failures.Count -ne 0) {
        throw "Delivery smoke matrix incomplete: $(@($Failures) -join '; ')"
    }

    $Outcome = "PASS"
}
catch {
    $Failure = $_.Exception.Message
    throw
}
finally {
    if (Test-Path -LiteralPath $PublisherResultPath) {
        $PublisherResultForState = Get-Content -LiteralPath $PublisherResultPath -Raw | ConvertFrom-Json
        $PublishCalled = [bool]$PublisherResultForState.publishCalled
        $PublishConfirmed = [bool]$PublisherResultForState.publishConfirmed
    } else {
        $PublisherResultForState = $null
    }

    $FinalState = [ordered]@{
        schema = 1
        status = $Outcome
        error = $Failure
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        userApproved = $true
        runtimeState = $RuntimeStatePath
        runtimeStateSha256 = $RuntimeStateHash
        scriptPath = $PSCommandPath
        scriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        publisherScriptPath = $PublisherScript
        publisherScriptSha256 = (Get-FileHash -LiteralPath $PublisherScript -Algorithm SHA256).Hash
        testId = $TestId
        messageText = $MessageText
        nodeIds = $NodeIds
        broker = [ordered]@{ host = $BrokerHost; port = $BrokerPort }
        topic = $Topic
        envelope = $Envelope
        qos = 1
        retained = $false
        phoneStates = @($PhoneStates)
        publishCalled = $PublishCalled
        publishConfirmed = $PublishConfirmed
        publisherExitCode = $PublisherExitCode
        publisherResult = $PublisherResultForState
        metrics = $Metrics
        failures = @($Failures)
        evidenceDirectory = $EvidenceDir
        forceStopped = $false
        launched = $false
        logcatCleared = $false
        networkChanged = $false
        publicTrafficSent = $PublishCalled
        automaticRetry = $false
    }

    Save-JsonAtomic -Path $StatePath -Value $FinalState
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:           $Outcome"
    Write-Host "State:             $StatePath"
    Write-Host "State SHA256:      $StateHash"
    Write-Host "Test ID:           $TestId"
    Write-Host "Publish called:    $PublishCalled"
    Write-Host "Publish confirmed: $PublishConfirmed"
    Write-Host "QoS/retained:      1 / False"
    Write-Host "Automatic retry:   False"
}

if ($Outcome -ne "PASS") {
    throw "Delivery smoke did not pass; see immutable state: $StatePath"
}

Write-Host ""
Write-Host "V11.16.13 FUNCTIONAL DELIVERY SMOKE PASS"
foreach ($Device in $Devices) {
    $Name = $Device.Name
    $SummaryTemplate = "{0}: relay/store/local/receipt/remove/origin/UI={1}/{2}/{3}/{4}/{5}/{6}/{7}"
    Write-Host ($SummaryTemplate -f $Name,
        $Metrics[$Name].relayInput,
        $Metrics[$Name].stored,
        $Metrics[$Name].localDelivery,
        $Metrics[$Name].receiptInput,
        $Metrics[$Name].removed,
        $Metrics[$Name].originDelivery,
        $Metrics[$Name].uiMessageReceived)
}
