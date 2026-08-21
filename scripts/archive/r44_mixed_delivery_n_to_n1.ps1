$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedApplicationCommit = "0181b3496cde81477a01e49f8d9977d7c325a2ca"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$ExpectedNativeSize = 7248576
$ExpectedNativeHash = "E6C34E86F18D9F63B9A641E3FD9FAFD67D5F1B7101729B2CB3DF25163380095B"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$PublisherScript = Join-Path $PSScriptRoot "v111613_publish_once.py"
$PublisherRelative = "scripts/v111613_publish_once.py"
$ExpectedPublisherBlob = "7252d9cf8e3c25fa346d18811be71e1f3c44cd67"
$IdentityStatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-identity1.json"
$ExpectedIdentityStateHash = "ED16F594A36E388B7ABED0172FAD5AE43839F72B8167BBED5BEB55FF94EF5435"
$StatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-delivery-n-to-n1.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-delivery-n-to-n1-evidence"
$RequestPath = Join-Path $EvidenceDir "publisher-request.json"
$PublisherResultPath = Join-Path $EvidenceDir "publisher-result.json"
$PackageName = "com.vladimir.messenger"
$BrokerHost = "broker.hivemq.com"
$BrokerPort = 1883

$Devices = @(
    [pscustomobject]@{ Name="Anna"; Serial="AUYF6R5923006121"; ExpectedUid=10425; ExpectedVersionName="v11.16.15"; ExpectedVersionCode=11016015; ExpectedProcessId="12943" },
    [pscustomobject]@{ Name="Zhenya"; Serial="3B665800EES00000"; ExpectedUid=10395; ExpectedVersionName="v11.16.13"; ExpectedVersionCode=11016013; ExpectedProcessId="14811" },
    [pscustomobject]@{ Name="Stas"; Serial="11567254BK001192"; ExpectedUid=10387; ExpectedVersionName="v11.16.13"; ExpectedVersionCode=11016013; ExpectedProcessId="23149" }
)

if (Test-Path -LiteralPath $StatePath) {
    throw "R4.4 MIXED N-TO-N1 DELIVERY ALREADY ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "R4.4 MIXED N-TO-N1 EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
}
foreach ($RequiredPath in @($Adb, $PublisherScript, $IdentityStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required mixed delivery input missing: $RequiredPath"
    }
}
$PublisherCommittedBlob = ((& git rev-parse ("HEAD:{0}" -f $PublisherRelative)) -join "").Trim()
$PublisherPathArgument = "--path={0}" -f $PublisherRelative
$PublisherWorkingBlob = ((& git hash-object $PublisherPathArgument -- $PublisherScript) -join "").Trim()
if (
    $PublisherCommittedBlob -ne $ExpectedPublisherBlob -or
    $PublisherWorkingBlob -ne $ExpectedPublisherBlob
) { throw "Publisher helper filter-aware Git identity mismatch" }
$IdentityStateHash = (Get-FileHash -LiteralPath $IdentityStatePath -Algorithm SHA256).Hash
if ($IdentityStateHash -ne $ExpectedIdentityStateHash) { throw "Identity state hash mismatch: $IdentityStateHash" }
$IdentityState = Get-Content -LiteralPath $IdentityStatePath -Raw | ConvertFrom-Json
$IdentityValid = $IdentityState.outcome -eq "PASS"
$IdentityValid = $IdentityValid -and [string]$IdentityState.nodeIds.Anna -eq "pk_591a15c0f5d659ebbb407bd377214ecc"
$IdentityValid = $IdentityValid -and [string]$IdentityState.nodeIds.Zhenya -eq "pk_9f43c5971a820d4f6bc5dc4f4dca4f8b"
$IdentityValid = $IdentityValid -and [string]$IdentityState.nodeIds.Stas -eq "pk_7dc6b7c52ae086094e7b367b4df5bd0c"
$IdentityValid = $IdentityValid -and $IdentityState.installStarted -eq $false
$IdentityValid = $IdentityValid -and $IdentityState.launchStarted -eq $false
$IdentityValid = $IdentityValid -and $IdentityState.userPayloadPublished -eq $false
$IdentityValid = $IdentityValid -and $IdentityState.publicTrafficInjected -eq $false
if (-not $IdentityValid) { throw "Identity state contract mismatch" }

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $CurrentBranch -ne $ExpectedBranch) { throw "Wrong branch: $CurrentBranch" }
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0) { throw "Cannot resolve Windows HEAD" }
& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) { throw "Application source differs from exact r4.4 integration source" }
$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedBefore = @($StatusBefore | Where-Object { $_ -ne " M $GeneratedSoRelative" })
if ($UnexpectedBefore.Count -gt 0) { throw "Unexpected delivery worktree changes: $($UnexpectedBefore -join '; ')" }
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$NativeFile = Get-Item -LiteralPath $SoPath
$NativeHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($NativeFile.Length -ne $ExpectedNativeSize -or $NativeHash -ne $ExpectedNativeHash) { throw "Dual native mismatch" }

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
        -PassThru
    if (-not $NativeProcess.WaitForExit(120000)) {
        throw "$Label process $($NativeProcess.Id) timed out"
    }
    $NativeProcess.WaitForExit()
    $NativeProcess.Refresh()

    $StdoutLines = @(Get-Content -LiteralPath $StdoutPath)
    $StderrLines = @(Get-Content -LiteralPath $StderrPath)
    $RawExitCode = $NativeProcess.ExitCode
    $ExitCodeAvailable = $null -ne $RawExitCode
    $NormalizedExitCode = $null
    if ($ExitCodeAvailable) { $NormalizedExitCode = [int]$RawExitCode }

    return [pscustomobject]@{
        ExitCodeAvailable = $ExitCodeAvailable
        ExitCode = $NormalizedExitCode
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

    if ($UidResult.StderrLines.Count + $DumpResult.StderrLines.Count + $PidResult.StderrLines.Count -ne 0) {
        throw "Device state query returned stderr: $($Device.Name), label=$Label"
    }
    if ($UidResult.ExitCodeAvailable -and $UidResult.ExitCode -ne 0) { throw "UID query failed: $($Device.Name)" }
    if ($DumpResult.ExitCodeAvailable -and $DumpResult.ExitCode -ne 0) { throw "Package query failed: $($Device.Name)" }
    if ($PidResult.ExitCodeAvailable -and $PidResult.ExitCode -notin @(0, 1)) { throw "PID query failed: $($Device.Name)" }

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

    if ($Result.StderrLines.Count -ne 0) { throw "Device epoch returned stderr: $($Device.Name)" }
    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
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

    if ($Result.StderrLines.Count -ne 0) { throw "Log capture returned stderr: $($Device.Name), kind=$Kind" }
    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
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

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$PublishCalled = $false
$PublishConfirmed = $false
$TestId = $null
$MessageText = $null
$Topic = $null
$Envelope = $null
$NodeIds = $null
$OriginNodeId = $null
$RecipientNodeId = $null
$RelayNodeId = $null
$PublisherExitCode = $null
$PublisherExitCodeAvailable = $false
$PhoneStates = [System.Collections.Generic.List[object]]::new()
$Metrics = [ordered]@{}
$Failures = [System.Collections.Generic.List[string]]::new()

try {
    $NodeIds = [ordered]@{
        Anna = [string]$IdentityState.nodeIds.Anna
        Zhenya = [string]$IdentityState.nodeIds.Zhenya
        Stas = [string]$IdentityState.nodeIds.Stas
    }

    $DevicesResult = Invoke-CapturedNative -FilePath $Adb -ArgumentList @("devices") -Label "adb-devices"
    if ($DevicesResult.ExitCodeAvailable -and $DevicesResult.ExitCode -ne 0) {
        throw "adb devices failed"
    }
    $Connected = @($DevicesResult.StdoutLines | Select-Object -Skip 1 | Where-Object { $_ -match "\S" })

    foreach ($Device in $Devices) {
        $SerialPattern = "^{0}\s+device$" -f [regex]::Escape($Device.Serial)
        if (@($Connected | Where-Object { $_ -match $SerialPattern }).Count -ne 1) {
            throw "$($Device.Name) is not connected exactly once"
        }

        $Current = Get-CurrentDeviceState -Device $Device -Label "pre"
        if ($Current.AppUid -ne $Device.ExpectedUid -or
            $Current.VersionName -ne $Device.ExpectedVersionName -or
            $Current.VersionCode -ne $Device.ExpectedVersionCode -or
            $Current.ProcessIds.Count -ne 1 -or
            [string]$Current.ProcessIds[0] -ne $Device.ExpectedProcessId) {
            throw "Mixed identity/PID precheck failed: $($Device.Name)"
        }

        $BaselineEpoch = Get-DeviceEpoch -Device $Device
        $PhoneStates.Add([pscustomobject]@{
            Name = $Device.Name
            Serial = $Device.Serial
            ExpectedProcessId = $Device.ExpectedProcessId
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
    if (($PahoCheck.StdoutLines -join "") -notmatch "paho-ok" -or $PahoCheck.StderrLines.Count -ne 0) {
        throw "paho publisher semantic preflight failed before state/publish"
    }
    if ($PahoCheck.ExitCodeAvailable -and $PahoCheck.ExitCode -ne 0) {
        throw "paho publisher command preflight failed before state/publish"
    }

    $TimestampPart = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $GuidPart = [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $Suffix = "{0}-{1}" -f $TimestampPart, $GuidPart
    $TestId = "r44-mixed-n-to-n1-$Suffix"
    $MessageText = "APU mixed N to N-1 check $Suffix"
    $OriginNodeId = [string]$NodeIds["Anna"]
    $RecipientNodeId = [string]$NodeIds["Zhenya"]
    $RelayNodeId = [string]$NodeIds["Stas"]
    $PayloadBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($MessageText))
    $Topic = "p2pm2/msg/$RecipientNodeId"
    $Envelope = "relay|$TestId|$RecipientNodeId|$OriginNodeId|apu-r44-mixed-n-to-n1|3600|0|$PayloadBase64"

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
        identityState = $IdentityStatePath
        identityStateSha256 = $IdentityStateHash
        direction = "N_TO_N_MINUS_1"
        originPhone = "Anna"
        recipientPhone = "Zhenya"
        relayPhone = "Stas"
        relayNodeId = $RelayNodeId
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
        controlledTestPayloadPublished = $false
        installStarted = $false
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
    $PublisherExitCodeAvailable = $PublisherRun.ExitCodeAvailable
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

    if ($PublisherExitCodeAvailable -and $PublisherExitCode -ne 0) { $Failures.Add("publisher exit=$PublisherExitCode") }
    if (-not $PublishCalled) { $Failures.Add("publishCalled=false") }
    if (-not $PublishConfirmed) { $Failures.Add("publishConfirmed=false") }

    $Expected = [ordered]@{
        Anna = [ordered]@{ relayInput=1; stored=1; localDelivery=0; receiptInput=1; receiptSent=0; removed=1; originDelivery=1; retainedReceiptCleared=1; uiMessageReceived=0 }
        Zhenya = [ordered]@{ relayInput=1; stored=0; localDelivery=1; receiptInput=1; receiptSent=1; removed=0; originDelivery=0; retainedReceiptCleared=0; uiMessageReceived=1 }
        Stas = [ordered]@{ relayInput=1; stored=1; localDelivery=0; receiptInput=1; receiptSent=0; removed=1; originDelivery=0; retainedReceiptCleared=0; uiMessageReceived=0 }
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
        identityState = $IdentityStatePath
        identityStateSha256 = $IdentityStateHash
        direction = "N_TO_N_MINUS_1"
        originPhone = "Anna"
        recipientPhone = "Zhenya"
        relayPhone = "Stas"
        relayNodeId = $RelayNodeId
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
        publisherExitCodeAvailable = $PublisherExitCodeAvailable
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
        controlledTestPayloadPublished = $PublishCalled
        logicalPublishCount = $(if ($PublishCalled) { 1 } else { 0 })
        installStarted = $false
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
    throw "Mixed N-to-N-1 delivery did not pass; see immutable state: $StatePath"
}

Write-Host ""
Write-Host "R4.4 MIXED N-TO-N-1 DELIVERY PASS"
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
