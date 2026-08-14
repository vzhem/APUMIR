Set-Location C:\APUMIR-arena-test

& {
    $ErrorActionPreference = "Stop"

    $PackageName = "com.vladimir.messenger"
    $ExpectedVersionName = "v11.16.11"
    $ExpectedVersionCode = 11016011
    $OldStatePath = Join-Path $env:TEMP "apu-r4.2-v11.16.11-delivery.json"
    $StatePath = Join-Path $env:TEMP "apu-r4.2-v11.16.11-delivery2.json"
    $PublisherPath = Join-Path $env:TEMP "apu-r4.2-v11.16.11-delivery2-publish.py"

    $Phones = @(
        [ordered]@{ Name = "Anna"; Serial = "AUYF6R5923006121" },
        [ordered]@{ Name = "Zhenya"; Serial = "3B665800EES00000" },
        [ordered]@{ Name = "Stas"; Serial = "11567254BK001192" }
    )

    $EvidencePaths = @($StatePath)
    foreach ($Phone in $Phones) {
        $EvidencePaths += Join-Path $env:TEMP ("apu-r4.2-v11.16.11-delivery2-{0}-before.log" -f $Phone.Name)
        $EvidencePaths += Join-Path $env:TEMP ("apu-r4.2-v11.16.11-delivery2-{0}-after.log" -f $Phone.Name)
    }

    function Get-UtcIso {
        [DateTime]::UtcNow.ToString("o")
    }

    function Get-EpochSeconds {
        [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0
    }

    function Save-State {
        param([Parameter(Mandatory = $true)][object]$Value)
        $TemporaryPath = "$StatePath.tmp"
        $Json = $Value | ConvertTo-Json -Depth 12
        [IO.File]::WriteAllText($TemporaryPath, $Json, [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $TemporaryPath -Destination $StatePath -Force
    }

    function Read-State {
        ([IO.File]::ReadAllText($StatePath, [Text.Encoding]::UTF8) | ConvertFrom-Json)
    }

    function Invoke-NativeText {
        param(
            [Parameter(Mandatory = $true)][string]$FilePath,
            [Parameter(Mandatory = $true)][string[]]$ArgumentList,
            [switch]$AllowFailure
        )
        $PreviousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $OutputLines = @(& $FilePath @ArgumentList 2>&1)
            $ExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $PreviousPreference
        }
        $Text = ($OutputLines | Out-String).Trim()
        if ((-not $AllowFailure) -and $ExitCode -ne 0) {
            throw ("Command failed ({0}): {1} {2}`n{3}" -f $ExitCode, $FilePath, ($ArgumentList -join " "), $Text)
        }
        [ordered]@{ ExitCode = $ExitCode; Text = $Text }
    }

    function Invoke-AdbText {
        param(
            [Parameter(Mandatory = $true)][string]$Serial,
            [Parameter(Mandatory = $true)][string[]]$ArgumentList
        )
        $Result = Invoke-NativeText -FilePath "adb" -ArgumentList (@("-s", $Serial) + $ArgumentList)
        $Result.Text
    }

    function Invoke-Python {
        param([Parameter(Mandatory = $true)][string[]]$ArgumentList)
        $PreviousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $OutputLines = @(& py -3 @ArgumentList 2>&1)
            $ExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $PreviousPreference
        }
        [ordered]@{ ExitCode = $ExitCode; Text = (($OutputLines | Out-String).Trim()) }
    }

    function Get-ProcessIds {
        param([Parameter(Mandatory = $true)][string]$Serial)
        $Output = Invoke-AdbText -Serial $Serial -ArgumentList @("shell", "pidof", $PackageName)
        $Ids = @(
            [regex]::Matches($Output, "\d+") |
                ForEach-Object { [int]$_.Value } |
                Sort-Object -Unique
        )
        if ($Ids.Count -eq 0) {
            throw "APU process is not running on $Serial"
        }
        $Ids
    }

    function Get-LogSnapshot {
        param([Parameter(Mandatory = $true)][string]$Serial)
        Invoke-AdbText -Serial $Serial -ArgumentList @(
            "logcat", "-d", "-v", "epoch",
            "CoreServerService:V", "RustBridge:V", "AndroidRuntime:V",
            "ActivityManager:V", "libc:V", "*:S"
        )
    }

    function Get-WindowLines {
        param(
            [Parameter(Mandatory = $true)][string]$Text,
            [Parameter(Mandatory = $true)][double]$NotBeforeEpoch
        )
        $Result = New-Object System.Collections.Generic.List[string]
        foreach ($Line in ($Text -split "`r?`n")) {
            $Match = [regex]::Match($Line, "^\s*(\d+\.\d+)\s+")
            if (-not $Match.Success) { continue }
            $Timestamp = [double]::Parse($Match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
            if ($Timestamp -ge $NotBeforeEpoch) {
                [void]$Result.Add($Line)
            }
        }
        @($Result)
    }

    function Count-Lines {
        param(
            [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Lines,
            [Parameter(Mandatory = $true)][string[]]$Needles
        )
        $Count = 0
        foreach ($Line in $Lines) {
            $AllFound = $true
            foreach ($Needle in $Needles) {
                if (-not $Line.Contains($Needle)) {
                    $AllFound = $false
                    break
                }
            }
            if ($AllFound) { $Count++ }
        }
        $Count
    }

    function Get-PeerIds {
        param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Lines)
        $Ids = New-Object System.Collections.Generic.List[string]
        foreach ($Line in $Lines) {
            $Match = [regex]::Match($Line, "MQTT: peer online: .*\((pk_[A-Za-z0-9_-]+)\)")
            if ($Match.Success) { [void]$Ids.Add($Match.Groups[1].Value) }
        }
        @($Ids | Sort-Object -Unique)
    }

    function Get-OwnNodeId {
        param([Parameter(Mandatory = $true)][string]$Text)
        $Ids = @(
            [regex]::Matches(
                $Text,
                "(?:Engine OK\. NodeId=|Engine started\. NodeId:\s*)(pk_[A-Za-z0-9_-]+)"
            ) | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
        )
        if ($Ids.Count -eq 1) { $Ids[0] } else { $null }
    }

    function Expect-Count {
        param(
            [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$Failures,
            [Parameter(Mandatory = $true)][string]$Label,
            [Parameter(Mandatory = $true)][int]$Actual,
            [Parameter(Mandatory = $true)][int]$Expected
        )
        if ($Actual -ne $Expected) {
            [void]$Failures.Add("$Label expected=$Expected actual=$Actual")
        }
    }

    $State = $null
    $GuardBlocked = $false
    try {
        $ExistingEvidence = @($EvidencePaths | Where-Object { Test-Path -LiteralPath $_ })
        if ($ExistingEvidence.Count -gt 0) {
            $GuardBlocked = $true
            Write-Host "GUARD: delivery2 evidence already exists. No publication was attempted."
            $ExistingEvidence | ForEach-Object { Write-Host "  $_" }
            if (Test-Path -LiteralPath $StatePath) {
                Write-Host ([IO.File]::ReadAllText($StatePath, [Text.Encoding]::UTF8))
            }
            throw "delivery2 is one-shot; existing evidence blocks a repeated run"
        }

        if (-not (Test-Path -LiteralPath $OldStatePath)) {
            throw "Old delivery state is missing; cannot prove that the first attempt did not publish"
        }
        $OldState = [IO.File]::ReadAllText($OldStatePath, [Text.Encoding]::UTF8) | ConvertFrom-Json
        if ([bool]$OldState.publisher.publishCalled -or
            [bool]$OldState.publisher.publishConfirmed -or
            -not [string]::IsNullOrWhiteSpace([string]$OldState.topic) -or
            -not [string]::IsNullOrWhiteSpace([string]$OldState.envelope)) {
            throw "Old delivery state does not prove a pre-publish stop; delivery2 is forbidden"
        }

        Get-Command adb -ErrorAction Stop | Out-Null
        Get-Command py -ErrorAction Stop | Out-Null

        $PahoCheck = Invoke-Python -ArgumentList @("-c", "import paho.mqtt.client; print('paho-ok')")
        if ($PahoCheck.ExitCode -ne 0) {
            throw "paho-mqtt preflight failed before publication: $($PahoCheck.Text)"
        }

        $Suffix = "{0}-{1}" -f [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(), ([Guid]::NewGuid().ToString("N").Substring(0, 8))
        $TestId = "r42-delivery2-$Suffix"
        $MessageText = "APU delivery check $Suffix"
        $State = [ordered]@{
            schemaVersion = 2
            status = "PRECHECK_CREATED"
            createdUtc = Get-UtcIso
            completedUtc = $null
            oldStatePath = $OldStatePath
            oldStateSha256 = (Get-FileHash -LiteralPath $OldStatePath -Algorithm SHA256).Hash
            oldAttemptPublishCalled = [bool]$OldState.publisher.publishCalled
            testId = $TestId
            messageText = $MessageText
            versionName = $ExpectedVersionName
            versionCode = $ExpectedVersionCode
            qos = 1
            retained = $false
            ttlSeconds = 3600
            hop = 0
            broker = [ordered]@{ host = "broker.hivemq.com"; port = 1883 }
            baselineEpoch = $null
            publishEpoch = $null
            topic = $null
            envelope = $null
            phones = [ordered]@{}
            identities = [ordered]@{}
            publisher = [ordered]@{
                startedUtc = $null
                connected = $false
                publishCalled = $false
                publishMid = $null
                publishConfirmed = $false
                confirmedUtc = $null
                helperExitCode = $null
                helperOutput = $null
                error = $null
            }
            metrics = $null
            failures = @()
            error = $null
        }
        Save-State -Value $State

        $Devices = (Invoke-NativeText -FilePath "adb" -ArgumentList @("devices")).Text
        foreach ($Phone in $Phones) {
            $Pattern = "(?m)^" + [regex]::Escape($Phone.Serial) + "\s+device\s*$"
            if (-not [regex]::IsMatch($Devices, $Pattern)) {
                throw "Phone $($Phone.Name) is not connected as an adb device"
            }

            $Dump = Invoke-AdbText -Serial $Phone.Serial -ArgumentList @("shell", "dumpsys", "package", $PackageName)
            $NameMatch = [regex]::Match($Dump, "(?m)^\s*versionName=(\S+)\s*$")
            $CodeMatch = [regex]::Match($Dump, "(?m)^\s*versionCode=(\d+)\b")
            if (-not $NameMatch.Success -or -not $CodeMatch.Success) {
                throw "Cannot parse APU version on $($Phone.Name)"
            }
            if ($NameMatch.Groups[1].Value -ne $ExpectedVersionName -or
                [int]$CodeMatch.Groups[1].Value -ne $ExpectedVersionCode) {
                throw "Wrong APU version on $($Phone.Name)"
            }

            $ProcessIds = @(Get-ProcessIds -Serial $Phone.Serial)
            if ($ProcessIds.Count -ne 1) {
                throw "Expected one current APU PID on $($Phone.Name); current=$($ProcessIds -join ',')"
            }
            $CurrentProcessId = [int]$ProcessIds[0]

            $State.phones[$Phone.Name] = [ordered]@{
                serial = $Phone.Serial
                expectedProcessId = $CurrentProcessId
                initialProcessIds = @($ProcessIds)
                prePublishProcessIds = @()
                finalProcessIds = @()
                beforeSnapshot = Join-Path $env:TEMP ("apu-r4.2-v11.16.11-delivery2-{0}-before.log" -f $Phone.Name)
                afterSnapshot = Join-Path $env:TEMP ("apu-r4.2-v11.16.11-delivery2-{0}-after.log" -f $Phone.Name)
            }
        }

        $State.status = "WAITING_FOR_FRESH_READINESS"
        $State.baselineEpoch = Get-EpochSeconds
        Save-State -Value $State
        Write-Host "Read-only readiness window: waiting 45 seconds for fresh peer traffic..."
        Start-Sleep -Seconds 45

        $BeforeTexts = @{}
        $FreshLines = @{}
        $FreshPeerIds = @{}
        foreach ($Phone in $Phones) {
            $Text = Get-LogSnapshot -Serial $Phone.Serial
            $BeforeTexts[$Phone.Name] = $Text
            [IO.File]::WriteAllText($State.phones[$Phone.Name].beforeSnapshot, $Text, [Text.UTF8Encoding]::new($false))
            $Lines = @(Get-WindowLines -Text $Text -NotBeforeEpoch ([double]$State.baselineEpoch - 1.0))
            $FreshLines[$Phone.Name] = $Lines
            $FreshPeerIds[$Phone.Name] = @(Get-PeerIds -Lines $Lines)

            $PeerCount = Count-Lines -Lines $Lines -Needles @("MQTT: peer online:")
            $MqttErrors = Count-Lines -Lines $Lines -Needles @("MQTT error:")
            $CrashCount = 0
            foreach ($Needle in @("FATAL EXCEPTION", "ANR in $PackageName", "am_anr", "Fatal signal")) {
                $CrashCount += Count-Lines -Lines $Lines -Needles @($Needle)
            }
            if ($PeerCount -lt 1) {
                throw "No fresh peer traffic on $($Phone.Name); publication was not attempted"
            }
            if ($MqttErrors -ne 0 -or $CrashCount -ne 0) {
                throw "Unclean readiness on $($Phone.Name): mqttErrors=$MqttErrors crashAnr=$CrashCount"
            }
        }

        $NodeIds = @{}
        foreach ($Phone in $Phones) {
            $NodeId = Get-OwnNodeId -Text $BeforeTexts[$Phone.Name]
            if ($null -eq $NodeId) {
                $Others = @($Phones | Where-Object { $_.Name -ne $Phone.Name } | ForEach-Object { $_.Name })
                $Candidates = @(
                    $FreshPeerIds[$Others[0]] |
                        Where-Object {
                            ($FreshPeerIds[$Others[1]] -contains $_) -and
                            ($FreshPeerIds[$Phone.Name] -notcontains $_)
                        } |
                        Sort-Object -Unique
                )
                if ($Candidates.Count -ne 1) {
                    throw "Cannot safely resolve node ID for $($Phone.Name); candidates=$($Candidates -join ',')"
                }
                $NodeId = $Candidates[0]
            }
            if ($NodeId.Length -gt 128 -or $NodeId -notmatch "^pk_[A-Za-z0-9_-]+$") {
                throw "Unsafe node ID for $($Phone.Name)"
            }
            $NodeIds[$Phone.Name] = $NodeId
        }
        if (@($NodeIds.Values | Sort-Object -Unique).Count -ne 3) {
            throw "Resolved node IDs are not unique"
        }
        foreach ($Phone in $Phones) {
            $OwnNodeId = $NodeIds[$Phone.Name]
            if ($FreshPeerIds[$Phone.Name] -contains $OwnNodeId) {
                throw "Own node ID appeared as a peer on $($Phone.Name)"
            }
            foreach ($Other in @($Phones | Where-Object { $_.Name -ne $Phone.Name })) {
                if ($FreshPeerIds[$Other.Name] -notcontains $OwnNodeId) {
                    throw "$($Phone.Name) was not freshly observed by $($Other.Name)"
                }
            }
        }

        foreach ($Phone in $Phones) {
            $ProcessIds = @(Get-ProcessIds -Serial $Phone.Serial)
            $ExpectedProcessId = [int]$State.phones[$Phone.Name].expectedProcessId
            $State.phones[$Phone.Name].prePublishProcessIds = @($ProcessIds)
            if ($ProcessIds -notcontains $ExpectedProcessId) {
                throw "PID changed before publish on $($Phone.Name): expected=$ExpectedProcessId current=$($ProcessIds -join ',')"
            }
            $State.identities[$Phone.Name] = $NodeIds[$Phone.Name]
        }

        $OriginNodeId = $NodeIds["Anna"]
        $RecipientNodeId = $NodeIds["Stas"]
        $PayloadBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($MessageText))
        $State.topic = "p2pm2/msg/$RecipientNodeId"
        $State.envelope = "relay|$TestId|$RecipientNodeId|$OriginNodeId|apu-r42-delivery2|3600|0|$PayloadBase64"
        $State.status = "READY_TO_PUBLISH_ONCE"
        Save-State -Value $State

        $PublisherSource = @'
import json
import os
import socket
import sys
import threading
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

path = sys.argv[1]

def now():
    return datetime.now(timezone.utc).isoformat()

def load():
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)

def save(value):
    temporary = path + ".publisher.tmp"
    with open(temporary, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=True, indent=2)
        handle.write("\n")
    os.replace(temporary, path)

def result_code(value):
    try:
        return int(value)
    except Exception:
        raw = getattr(value, "value", None)
        if raw is not None:
            return int(raw)
        return 0 if str(value).lower() in ("0", "success") else -1

state = load()
publisher = state["publisher"]
publisher["startedUtc"] = now()
state["status"] = "PUBLISH_CONNECTING"
save(state)
connected = threading.Event()
published = threading.Event()

try:
    try:
        client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION1,
            client_id="apu_r42d2_" + state["testId"][-10:],
            clean_session=True,
        )
    except (AttributeError, TypeError):
        client = mqtt.Client(
            client_id="apu_r42d2_" + state["testId"][-10:],
            clean_session=True,
        )

    def on_connect(client, userdata, flags, reason_code, *extra):
        if result_code(reason_code) == 0:
            connected.set()

    def on_publish(client, userdata, mid, *extra):
        published.set()

    client.on_connect = on_connect
    client.on_publish = on_publish
    socket.setdefaulttimeout(10.0)
    connect_rc = client.connect(state["broker"]["host"], int(state["broker"]["port"]), 30)
    if int(connect_rc) != 0:
        raise RuntimeError("connect returned %s" % connect_rc)
    client.loop_start()
    if not connected.wait(12.0):
        raise TimeoutError("broker ConnAck was not received before publish")

    publisher["connected"] = True
    state["status"] = "PUBLISH_CONNACK_RECEIVED"
    save(state)

    publisher["publishCalled"] = True
    state["status"] = "PUBLISH_CALLED_ONCE"
    save(state)

    info = client.publish(
        state["topic"],
        state["envelope"].encode("utf-8"),
        qos=1,
        retain=False,
    )
    publisher["publishMid"] = int(info.mid)
    save(state)
    if int(info.rc) != int(mqtt.MQTT_ERR_SUCCESS):
        raise RuntimeError("publish returned rc=%s" % info.rc)

    try:
        info.wait_for_publish(timeout=12.0)
    except TypeError:
        info.wait_for_publish()
    if not (published.wait(1.0) or info.is_published()):
        raise TimeoutError("publish called but PUBACK was not confirmed")

    publisher["publishConfirmed"] = True
    publisher["confirmedUtc"] = now()
    state["status"] = "PUBLISH_CONFIRMED_ANALYSIS_PENDING"
    save(state)
    print(json.dumps({"publishConfirmed": True, "mid": int(info.mid)}, ensure_ascii=True))
except Exception as exc:
    publisher["error"] = "%s: %s" % (type(exc).__name__, exc)
    state["status"] = "PUBLISH_INCOMPLETE_ANALYSIS_REQUIRED"
    save(state)
    print(json.dumps({"publishConfirmed": False, "error": publisher["error"]}, ensure_ascii=True))
    sys.exit(2)
finally:
    try:
        client.disconnect()
    except Exception:
        pass
    try:
        client.loop_stop()
    except Exception:
        pass
'@
        [IO.File]::WriteAllText($PublisherPath, $PublisherSource, [Text.UTF8Encoding]::new($false))

        $State.publishEpoch = Get-EpochSeconds
        $State.status = "PUBLISH_HELPER_STARTING"
        Save-State -Value $State
        Write-Host "Publishing exactly one QoS1 non-retained relay..."
        $PublisherRun = Invoke-Python -ArgumentList @($PublisherPath, $StatePath)
        $State = Read-State
        $State.publisher.helperExitCode = [int]$PublisherRun.ExitCode
        $State.publisher.helperOutput = $PublisherRun.Text
        Save-State -Value $State

        if ([bool]$State.publisher.publishCalled) {
            Write-Host "Publish was called once. Waiting 25 seconds for evidence..."
            Start-Sleep -Seconds 25
        } else {
            Write-Host "Publish was not called. Capturing evidence without retry..."
            Start-Sleep -Seconds 2
        }

        $AnalysisLines = @{}
        foreach ($Phone in $Phones) {
            $Text = Get-LogSnapshot -Serial $Phone.Serial
            $PhoneState = $State.phones.PSObject.Properties[$Phone.Name].Value
            [IO.File]::WriteAllText($PhoneState.afterSnapshot, $Text, [Text.UTF8Encoding]::new($false))
            $AnalysisLines[$Phone.Name] = @(
                Get-WindowLines -Text $Text -NotBeforeEpoch ([double]$State.publishEpoch - 1.0)
            )
        }

        $Metrics = [ordered]@{}
        foreach ($Phone in $Phones) {
            $Name = $Phone.Name
            $Lines = [string[]]$AnalysisLines[$Name]
            $PhoneState = $State.phones.PSObject.Properties[$Name].Value
            $FinalProcessIds = @(Get-ProcessIds -Serial $Phone.Serial)
            $PhoneState.finalProcessIds = @($FinalProcessIds)

            $Unexpected = 0
            foreach ($Needle in @(
                "MESH relay: duplicate local delivery $TestId suppressed",
                "MESH relay: previously seen $TestId ignored after cleanup",
                "MESH relay: conflicting origin for $TestId",
                "MESH relay: failed to store $TestId",
                "MESH relay: $TestId reached hop limit",
                "MESH relay: queue unavailable, dropped $TestId"
            )) {
                $Unexpected += Count-Lines -Lines $Lines -Needles @($Needle)
            }

            $CrashAnr = 0
            foreach ($Needle in @("FATAL EXCEPTION", "ANR in $PackageName", "am_anr", "Fatal signal")) {
                $CrashAnr += Count-Lines -Lines $Lines -Needles @($Needle)
            }
            $AndroidErrors = 0
            foreach ($Needle in @(
                "Error saving incoming message", "Auto-create failed", "Auto-add contact failed",
                "Failed to mark DELIVERED", "Event polling error", "Engine error:"
            )) {
                $AndroidErrors += Count-Lines -Lines $Lines -Needles @($Needle)
            }

            $Metrics[$Name] = [ordered]@{
                relayInput = Count-Lines -Lines $Lines -Needles @("MQTT IN:", "relay|$TestId|")
                stored = Count-Lines -Lines $Lines -Needles @("MESH relay: stored $TestId for ")
                localDelivery = Count-Lines -Lines $Lines -Needles @("MESH relay: $TestId delivered to local recipient")
                receiptInput = Count-Lines -Lines $Lines -Needles @("MQTT IN:", "receipt|$TestId|")
                receiptSent = Count-Lines -Lines $Lines -Needles @("MESH receipt: $TestId sent automatically to unique origin topic")
                removed = Count-Lines -Lines $Lines -Needles @("MESH receipt: removed $TestId for ")
                originDelivery = Count-Lines -Lines $Lines -Needles @("MESH receipt: $TestId delivered at origin")
                retainedReceiptCleared = Count-Lines -Lines $Lines -Needles @("MESH receipt: cleared retained topic for $TestId")
                uiMessageReceived = Count-Lines -Lines $Lines -Needles @("MESSAGE_RECEIVED:", "msgId=$TestId")
                legacyAckInput = Count-Lines -Lines $Lines -Needles @("MQTT IN:", "ack|$TestId")
                unexpectedRelay = $Unexpected
                mqttErrors = Count-Lines -Lines $Lines -Needles @("MQTT error:")
                androidErrors = $AndroidErrors
                crashAnr = $CrashAnr
                expectedPidPresent = ($FinalProcessIds -contains [int]$PhoneState.expectedProcessId)
                finalProcessIds = @($FinalProcessIds)
            }
        }

        $Failures = New-Object System.Collections.Generic.List[string]
        if ($PublisherRun.ExitCode -ne 0) { [void]$Failures.Add("publisher exit=$($PublisherRun.ExitCode)") }
        if (-not [bool]$State.publisher.publishConfirmed) { [void]$Failures.Add("QoS1 publish not confirmed") }

        Expect-Count $Failures "Anna relayInput" $Metrics["Anna"].relayInput 1
        Expect-Count $Failures "Anna stored" $Metrics["Anna"].stored 1
        Expect-Count $Failures "Anna receiptInput" $Metrics["Anna"].receiptInput 1
        Expect-Count $Failures "Anna removed" $Metrics["Anna"].removed 1
        Expect-Count $Failures "Anna originDelivery" $Metrics["Anna"].originDelivery 1
        Expect-Count $Failures "Anna retainedReceiptCleared" $Metrics["Anna"].retainedReceiptCleared 1
        Expect-Count $Failures "Anna localDelivery" $Metrics["Anna"].localDelivery 0
        Expect-Count $Failures "Anna UI" $Metrics["Anna"].uiMessageReceived 0

        Expect-Count $Failures "Zhenya relayInput" $Metrics["Zhenya"].relayInput 1
        Expect-Count $Failures "Zhenya stored" $Metrics["Zhenya"].stored 1
        Expect-Count $Failures "Zhenya receiptInput" $Metrics["Zhenya"].receiptInput 1
        Expect-Count $Failures "Zhenya removed" $Metrics["Zhenya"].removed 1
        Expect-Count $Failures "Zhenya localDelivery" $Metrics["Zhenya"].localDelivery 0
        Expect-Count $Failures "Zhenya originDelivery" $Metrics["Zhenya"].originDelivery 0
        Expect-Count $Failures "Zhenya UI" $Metrics["Zhenya"].uiMessageReceived 0

        Expect-Count $Failures "Stas relayInput" $Metrics["Stas"].relayInput 1
        Expect-Count $Failures "Stas stored" $Metrics["Stas"].stored 0
        Expect-Count $Failures "Stas localDelivery" $Metrics["Stas"].localDelivery 1
        Expect-Count $Failures "Stas receiptInput" $Metrics["Stas"].receiptInput 1
        Expect-Count $Failures "Stas receiptSent" $Metrics["Stas"].receiptSent 1
        Expect-Count $Failures "Stas removed" $Metrics["Stas"].removed 0
        Expect-Count $Failures "Stas originDelivery" $Metrics["Stas"].originDelivery 0
        Expect-Count $Failures "Stas UI" $Metrics["Stas"].uiMessageReceived 1

        foreach ($Phone in $Phones) {
            $Name = $Phone.Name
            Expect-Count $Failures "$Name unexpected relay" $Metrics[$Name].unexpectedRelay 0
            Expect-Count $Failures "$Name MQTT errors" $Metrics[$Name].mqttErrors 0
            Expect-Count $Failures "$Name Android errors" $Metrics[$Name].androidErrors 0
            Expect-Count $Failures "$Name crash/ANR" $Metrics[$Name].crashAnr 0
            if (-not $Metrics[$Name].expectedPidPresent) {
                [void]$Failures.Add("$Name expected PID absent after test")
            }
        }

        $State.metrics = $Metrics
        $State.failures = @($Failures)
        $State.completedUtc = Get-UtcIso
        if ($Failures.Count -eq 0) {
            $State.status = "PASS"
            Save-State -Value $State
            Write-Host ""
            Write-Host "R4.2 v11.16.11 DELIVERY2 PASSED" -ForegroundColor Green
            Write-Host "State: $StatePath"
            foreach ($Phone in $Phones) {
                $Name = $Phone.Name
                Write-Host ("{0}: relay/store/local/receipt/remove/origin/UI/errors/crash={1}/{2}/{3}/{4}/{5}/{6}/{7}/{8}/{9}" -f
                    $Name,
                    $Metrics[$Name].relayInput,
                    $Metrics[$Name].stored,
                    $Metrics[$Name].localDelivery,
                    $Metrics[$Name].receiptInput,
                    $Metrics[$Name].removed,
                    $Metrics[$Name].originDelivery,
                    $Metrics[$Name].uiMessageReceived,
                    ($Metrics[$Name].mqttErrors + $Metrics[$Name].androidErrors),
                    $Metrics[$Name].crashAnr)
            }
        } else {
            $State.status = "INCOMPLETE_DO_NOT_REPEAT"
            Save-State -Value $State
            Write-Host "DELIVERY2 INCOMPLETE. Keep evidence and do not rerun." -ForegroundColor Yellow
            $Failures | ForEach-Object { Write-Host "  $_" }
            throw "delivery2 matrix incomplete"
        }
    } catch {
        $CaughtMessage = $_.Exception.Message
        if ((-not $GuardBlocked) -and (Test-Path -LiteralPath $StatePath)) {
            try {
                $State = Read-State
                if ($State.status -ne "PASS") {
                    $State.status = "INCOMPLETE_DO_NOT_REPEAT"
                    $State.error = $CaughtMessage
                    $State.completedUtc = Get-UtcIso
                    Save-State -Value $State
                }
            } catch {
                Write-Host "WARNING: state update failed; existing evidence was not deleted"
            }
        }
        Write-Host ""
        Write-Host "STOP: $CaughtMessage" -ForegroundColor Yellow
        Write-Host "No automatic retry is allowed. Existing evidence:"
        $EvidencePaths | ForEach-Object {
            if (Test-Path -LiteralPath $_) { Write-Host "  $_" }
        }
        throw
    }
}
