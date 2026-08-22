$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedWindowsHead = "8cea566e50f439810e29fb1dc4ac14dc69b5fbc6"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$LaunchStatePath = Join-Path $env:TEMP "apu-m3d-v11.16.16-launch.json"
$ExpectedLaunchStateHash = "6561A0636034B4782605D008C649F20A7D0A72DB014568B84CEF84AB380F84E2"
$StatePath = Join-Path $env:TEMP "apu-m3d-offline-acceptance-prepare.json"
$EvidenceDir = Join-Path $env:TEMP "apu-m3d-offline-acceptance-prepare-evidence"
$PackageName = "com.vladimir.messenger"
$TargetVersionName = "v11.16.16"
$TargetVersionCode = 11016016
$ScriptRelative = "scripts/m3d_offline_prepare.ps1"
$ObservationSeconds = 35

$Devices = @(
    [pscustomobject]@{
        Name = "Anna"
        Serial = "AUYF6R5923006121"
        ExpectedUid = 10425
        ExpectedFirstInstallTime = "2026-08-08 11:40:39"
        ExpectedProcessId = "22055"
        Role = "origin"
    },
    [pscustomobject]@{
        Name = "Zhenya"
        Serial = "3B665800EES00000"
        ExpectedUid = 10395
        ExpectedFirstInstallTime = "2026-08-08 17:31:18"
        ExpectedProcessId = "11575"
        Role = "relay"
    },
    [pscustomobject]@{
        Name = "Stas"
        Serial = "11567254BK001192"
        ExpectedUid = 10387
        ExpectedFirstInstallTime = "2026-08-10 12:41:10"
        ExpectedProcessId = "11449"
        Role = "offline_recipient"
    }
)

if (Test-Path -LiteralPath $StatePath) {
    throw "M3(d) offline prepare already has state; do not repeat: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "M3(d) offline prepare evidence already exists; do not repeat: $EvidenceDir"
}
foreach ($RequiredPath in @($Adb, $LaunchStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required prepare input missing: $RequiredPath"
    }
}

$LaunchStateHash = (Get-FileHash -LiteralPath $LaunchStatePath -Algorithm SHA256).Hash
if ($LaunchStateHash -ne $ExpectedLaunchStateHash) {
    throw "Launch readiness state hash mismatch: $LaunchStateHash"
}
$LaunchState = Get-Content -LiteralPath $LaunchStatePath -Raw | ConvertFrom-Json
if (
    $LaunchState.Outcome -ne "PASS" -or
    [int]$LaunchState.LaunchCallCount -ne 3 -or
    [int]$LaunchState.PassedDeviceCount -ne 3 -or
    $LaunchState.VersionName -ne $TargetVersionName -or
    [int64]$LaunchState.VersionCode -ne $TargetVersionCode -or
    $LaunchState.ForceStopped -ne $false -or
    $LaunchState.LogcatCleared -ne $false -or
    $LaunchState.NetworkChanged -ne $false -or
    $LaunchState.UserPayloadPublished -ne $false -or
    $LaunchState.AutomaticRetry -ne $false
) {
    throw "Launch state is not exact readiness PASS"
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

$ExpectedStatus = @(
    " M android-app/app/src/main/java/com/vladimir/messenger/data/RustBridge.kt",
    " M android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/MessageDao.kt",
    " M android-app/app/src/main/java/com/vladimir/messenger/data/repository/ChatRepository.kt",
    " M android-app/app/src/main/java/com/vladimir/messenger/domain/model/MessageChannel.kt",
    " M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so",
    " M rust-core/src/engine/core.rs",
    " M rust-core/src/lib.rs",
    " M rust-core/src/network/mod.rs",
    "?? design/branding/app-icon/source/apu-icon-original.png",
    "?? rust-core/src/network/offline_send.rs",
    "?? scripts/m3d_kotlin_apk_build.ps1",
    "?? scripts/m3d_v111616_install.ps1",
    "?? scripts/m3d_v111616_launch.ps1",
    "?? $ScriptRelative"
)
$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedBefore = @($StatusBefore | Where-Object { $_ -notin $ExpectedStatus })
if ($LASTEXITCODE -ne 0 -or $UnexpectedBefore.Count -gt 0 -or $StatusBefore.Count -ne $ExpectedStatus.Count) {
    throw "Unexpected Windows worktree before offline prepare: $($StatusBefore -join '; ')"
}

New-Item -ItemType Directory -Path $EvidenceDir | Out-Null

function Invoke-Captured {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Label,
        [int]$TimeoutMilliseconds = 60000
    )

    $StdoutPath = Join-Path $EvidenceDir "$Label.stdout.log"
    $StderrPath = Join-Path $EvidenceDir "$Label.stderr.log"
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
    return [pscustomobject]@{
        ProcessId = $NativeProcess.Id
        ExitCodeAvailable = $ExitCodeAvailable
        ExitCode = $ExitCode
        Stdout = @(Get-Content -LiteralPath $StdoutPath)
        Stderr = @(Get-Content -LiteralPath $StderrPath)
        StdoutPath = $StdoutPath
        StderrPath = $StderrPath
    }
}

function Assert-SemanticSuccess {
    param(
        [Parameter(Mandatory = $true)][pscustomobject]$Result,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
        throw "$Label failed: exit=$($Result.ExitCode)"
    }
}

function Get-PhoneSnapshot {
    param(
        [Parameter(Mandatory = $true)][pscustomobject]$Device,
        [Parameter(Mandatory = $true)][string]$Phase
    )

    $SafeName = $Device.Name.ToLowerInvariant()
    $UidResult = Invoke-Captured $Adb @(
        "-s", $Device.Serial, "shell", "cmd", "package", "list", "packages", "-U", $PackageName
    ) "$SafeName-$Phase-uid"
    $PackageResult = Invoke-Captured $Adb @(
        "-s", $Device.Serial, "shell", "dumpsys", "package", $PackageName
    ) "$SafeName-$Phase-package"
    $ProcessResult = Invoke-Captured $Adb @(
        "-s", $Device.Serial, "shell", "pidof", $PackageName
    ) "$SafeName-$Phase-process"
    $WifiResult = Invoke-Captured $Adb @(
        "-s", $Device.Serial, "shell", "settings", "get", "global", "wifi_on"
    ) "$SafeName-$Phase-wifi"
    $DataResult = Invoke-Captured $Adb @(
        "-s", $Device.Serial, "shell", "settings", "get", "global", "mobile_data"
    ) "$SafeName-$Phase-data"
    $AirplaneResult = Invoke-Captured $Adb @(
        "-s", $Device.Serial, "shell", "settings", "get", "global", "airplane_mode_on"
    ) "$SafeName-$Phase-airplane"
    Assert-SemanticSuccess -Result $UidResult -Label "$($Device.Name) UID snapshot"
    Assert-SemanticSuccess -Result $PackageResult -Label "$($Device.Name) package snapshot"
    Assert-SemanticSuccess -Result $WifiResult -Label "$($Device.Name) Wi-Fi snapshot"
    Assert-SemanticSuccess -Result $DataResult -Label "$($Device.Name) mobile data snapshot"
    Assert-SemanticSuccess -Result $AirplaneResult -Label "$($Device.Name) airplane snapshot"

    $UidText = ($UidResult.Stdout -join "").Trim()
    $PackageText = $PackageResult.Stdout -join "`n"
    $ProcessText = ($ProcessResult.Stdout -join " ").Trim()
    $UidMatch = [regex]::Match($UidText, "uid:(\d+)")
    $VersionNameMatch = [regex]::Match($PackageText, "versionName=([^\s\r\n]+)")
    $VersionCodeMatch = [regex]::Match($PackageText, "versionCode=(\d+)")
    $FirstInstallMatch = [regex]::Match($PackageText, "firstInstallTime=([^\r\n]+)")
    $DataDirMatch = [regex]::Match($PackageText, "dataDir=([^\s\r\n]+)")
    if (-not $UidMatch.Success -or -not $VersionNameMatch.Success -or -not $VersionCodeMatch.Success -or
        -not $FirstInstallMatch.Success -or -not $DataDirMatch.Success) {
        throw "Phone snapshot parse failed: $($Device.Name), phase=$Phase"
    }
    $ProcessIds = @()
    if ($ProcessText) {
        $ProcessIds = @($ProcessText -split "\s+" | Where-Object { $_ -match "^\d+$" })
        if ($ProcessIds.Count -lt 1) {
            throw "Process output parse failed: $($Device.Name), phase=$Phase"
        }
    }
    if ($ProcessResult.ExitCodeAvailable) {
        $PidOutcomeValid = (
            ($ProcessResult.ExitCode -eq 0 -and $ProcessIds.Count -ge 1) -or
            ($ProcessResult.ExitCode -eq 1 -and $ProcessIds.Count -eq 0)
        )
        if (-not $PidOutcomeValid) {
            throw "pidof outcome mismatch: $($Device.Name), phase=$Phase"
        }
    }

    $Wifi = ($WifiResult.Stdout -join "").Trim()
    $MobileData = ($DataResult.Stdout -join "").Trim()
    $Airplane = ($AirplaneResult.Stdout -join "").Trim()
    if ($Wifi -notin @("0", "1") -or $MobileData -notin @("0", "1") -or $Airplane -notin @("0", "1")) {
        throw "Network settings parse failed: $($Device.Name), phase=$Phase, wifi=$Wifi data=$MobileData airplane=$Airplane"
    }

    return [pscustomobject]@{
        Name = $Device.Name
        Serial = $Device.Serial
        Role = $Device.Role
        Uid = [int]$UidMatch.Groups[1].Value
        VersionName = $VersionNameMatch.Groups[1].Value.Trim()
        VersionCode = [int64]$VersionCodeMatch.Groups[1].Value
        FirstInstallTime = $FirstInstallMatch.Groups[1].Value.Trim()
        DataDir = $DataDirMatch.Groups[1].Value.Trim()
        ProcessIds = @($ProcessIds)
        ProcessRunning = ($ProcessIds.Count -gt 0)
        WifiOn = [int]$Wifi
        MobileDataOn = [int]$MobileData
        AirplaneModeOn = [int]$Airplane
    }
}

function Get-DeviceEpoch {
    param([Parameter(Mandatory = $true)][pscustomobject]$Device)
    $Result = Invoke-Captured $Adb @("-s", $Device.Serial, "shell", "date", "+%s") "$($Device.Name.ToLowerInvariant())-phase-epoch"
    Assert-SemanticSuccess -Result $Result -Label "$($Device.Name) epoch"
    $Value = ($Result.Stdout -join "").Trim()
    if ($Value -notmatch "^\d+$") {
        throw "Invalid device epoch: $($Device.Name), value=$Value"
    }
    return [int64]$Value
}

function Get-NewP2pLines {
    param(
        [Parameter(Mandatory = $true)][pscustomobject]$Device,
        [Parameter(Mandatory = $true)][string]$AndroidProcessId,
        [Parameter(Mandatory = $true)][int64]$FromEpoch,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $Result = Invoke-Captured $Adb @(
        "-s", $Device.Serial, "logcat", "-d", "-v", "epoch", "p2p_core:V", "*:S"
    ) "$($Device.Name.ToLowerInvariant())-$Label-p2p"
    Assert-SemanticSuccess -Result $Result -Label "$($Device.Name) p2p capture"
    $Pattern = "^\s*(\d+\.\d+)\s+{0}\s+" -f [regex]::Escape($AndroidProcessId)
    return @(
        foreach ($Line in $Result.Stdout) {
            $Match = [regex]::Match($Line, $Pattern)
            if ($Match.Success) {
                $Epoch = [double]::Parse($Match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
                if ($Epoch -ge [double]$FromEpoch) { $Line }
            }
        }
    )
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$PreflightComplete = $false
$NetworkChangeStarted = $false
$StasWifiDisableCalled = $false
$StasDataDisableCalled = $false
$PreSnapshots = [System.Collections.Generic.List[object]]::new()
$PostSnapshots = [System.Collections.Generic.List[object]]::new()
$PhaseEpochs = [ordered]@{}
$ObservationMetrics = [ordered]@{}
$TestText = "M3D-OFFLINE-{0}" -f [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

try {
    $DevicesResult = Invoke-Captured $Adb @("devices") "adb-devices"
    Assert-SemanticSuccess -Result $DevicesResult -Label "adb devices"
    $Connected = @($DevicesResult.Stdout | Select-Object -Skip 1 | Where-Object { $_ -match "\S" })
    foreach ($Device in $Devices) {
        $Pattern = "^{0}\s+device$" -f [regex]::Escape($Device.Serial)
        if (@($Connected | Where-Object { $_ -match $Pattern }).Count -ne 1) {
            throw "$($Device.Name) absent/unauthorized/offline before prepare"
        }
    }

    foreach ($Device in $Devices) {
        $Snapshot = Get-PhoneSnapshot -Device $Device -Phase "pre"
        $PreSnapshots.Add($Snapshot)
        if (
            $Snapshot.Uid -ne $Device.ExpectedUid -or
            $Snapshot.VersionName -ne $TargetVersionName -or
            $Snapshot.VersionCode -ne $TargetVersionCode -or
            $Snapshot.FirstInstallTime -ne $Device.ExpectedFirstInstallTime -or
            $Snapshot.DataDir -ne "/data/user/0/$PackageName" -or
            $Snapshot.ProcessIds.Count -ne 1 -or
            [string]$Snapshot.ProcessIds[0] -ne $Device.ExpectedProcessId -or
            $Snapshot.AirplaneModeOn -ne 0
        ) {
            throw "Exact preflight mismatch: $($Device.Name)"
        }
        $PhaseEpochs[$Device.Name] = Get-DeviceEpoch -Device $Device
    }
    $PreflightComplete = $true

    $Stas = @($Devices | Where-Object { $_.Name -eq "Stas" })[0]
    $NetworkChangeStarted = $true
    $WifiDisable = Invoke-Captured $Adb @("-s", $Stas.Serial, "shell", "svc", "wifi", "disable") "stas-disable-wifi"
    $StasWifiDisableCalled = $true
    Assert-SemanticSuccess -Result $WifiDisable -Label "Stas Wi-Fi disable"
    $DataDisable = Invoke-Captured $Adb @("-s", $Stas.Serial, "shell", "svc", "data", "disable") "stas-disable-data"
    $StasDataDisableCalled = $true
    Assert-SemanticSuccess -Result $DataDisable -Label "Stas mobile data disable"

    Start-Sleep -Seconds $ObservationSeconds

    foreach ($Device in $Devices) {
        $Snapshot = Get-PhoneSnapshot -Device $Device -Phase "post"
        $PostSnapshots.Add($Snapshot)
        $Pre = @($PreSnapshots | Where-Object { $_.Name -eq $Device.Name })[0]
        if (
            $Snapshot.Uid -ne $Pre.Uid -or
            $Snapshot.VersionName -ne $Pre.VersionName -or
            $Snapshot.VersionCode -ne $Pre.VersionCode -or
            $Snapshot.FirstInstallTime -ne $Pre.FirstInstallTime -or
            $Snapshot.DataDir -ne $Pre.DataDir -or
            $Snapshot.ProcessIds.Count -ne 1 -or
            [string]$Snapshot.ProcessIds[0] -ne $Device.ExpectedProcessId
        ) {
            throw "Identity/PID changed during prepare: $($Device.Name)"
        }
        if ($Device.Name -eq "Stas") {
            if ($Snapshot.WifiOn -ne 0 -or $Snapshot.MobileDataOn -ne 0 -or $Snapshot.AirplaneModeOn -ne 0) {
                throw "Stas is not fully offline after approved toggles"
            }
        } else {
            if ($Snapshot.WifiOn -ne $Pre.WifiOn -or $Snapshot.MobileDataOn -ne $Pre.MobileDataOn -or
                $Snapshot.AirplaneModeOn -ne $Pre.AirplaneModeOn) {
                throw "Network settings changed unexpectedly: $($Device.Name)"
            }
        }

        $Lines = @(Get-NewP2pLines -Device $Device -AndroidProcessId $Device.ExpectedProcessId `
            -FromEpoch $PhaseEpochs[$Device.Name] -Label "observe")
        $ObservationMetrics[$Device.Name] = [ordered]@{
            LineCount = $Lines.Count
            Incoming = @($Lines | Where-Object { $_.Contains("MQTT IN:") }).Count
            Errors = @($Lines | Where-Object { $_.Contains("MQTT error:") }).Count
            SecondaryBackoff = @($Lines | Where-Object { $_.Contains("MQTT SECONDARY STATUS: broker=emqx state=backoff") }).Count
            Stall = @($Lines | Where-Object { $_.Contains("MQTT LIVENESS STALLED:") }).Count
            Restart = @($Lines | Where-Object { $_.Contains("MQTT SESSION RESTART") }).Count
        }
    }

    foreach ($Name in @("Anna", "Zhenya")) {
        if ([int]$ObservationMetrics[$Name].Incoming -lt 1 -or [int]$ObservationMetrics[$Name].Stall -ne 0 -or
            [int]$ObservationMetrics[$Name].Restart -ne 0) {
            throw "$Name is not healthy online after Stas offline transition"
        }
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
        purpose = "M3(d) automatic offline acceptance prepare: Stas offline, Anna/Zhenya online"
        outcome = $Outcome
        failure = $Failure
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        userApprovedNetworkChanges = $true
        launchState = $LaunchStatePath
        launchStateSha256 = $LaunchStateHash
        roles = [ordered]@{ origin = "Anna"; relay = "Zhenya"; recipient = "Stas" }
        nodeIds = [ordered]@{
            Anna = "pk_591a15c0f5d659ebbb407bd377214ecc"
            Zhenya = "pk_9f43c5971a820d4f6bc5dc4f4dca4f8b"
            Stas = "pk_7dc6b7c52ae086094e7b367b4df5bd0c"
        }
        testText = $TestText
        preflightComplete = $PreflightComplete
        networkChangeStarted = $NetworkChangeStarted
        stasWifiDisableCalled = $StasWifiDisableCalled
        stasDataDisableCalled = $StasDataDisableCalled
        preSnapshots = @($PreSnapshots)
        postSnapshots = @($PostSnapshots)
        phaseEpochs = $PhaseEpochs
        observationSeconds = $ObservationSeconds
        observationMetrics = $ObservationMetrics
        userMessageSent = $false
        originNetworkChanged = $false
        recipientNetworkRestored = $false
        originNetworkRestored = $false
        installed = $false
        launched = $false
        forceStopped = $false
        logcatCleared = $false
        dataCleared = $false
        publicSyntheticPublish = $false
        automaticRetry = $false
    }
    $State | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:                   $Outcome"
    Write-Host "State:                     $StatePath"
    Write-Host "State SHA256:              $StateHash"
    Write-Host "Preflight complete:        $PreflightComplete"
    Write-Host "Stas Wi-Fi/data disabled:  $StasWifiDisableCalled / $StasDataDisableCalled"
    Write-Host "Test text for next step:   $TestText"
    Write-Host "User message sent:         False"
    Write-Host "Install/launch/force-stop: False / False / False"
    Write-Host "Logcat clear/data clear:   False / False"
}

if ($Outcome -ne "PASS") {
    throw "M3(d) offline acceptance prepare did not pass; inspect immutable state"
}

Write-Host ""
Write-Host "M3D OFFLINE ACCEPTANCE PREPARE PASS"
Write-Host "Stas is offline; Anna and Zhenya are online. Do not send until instructed."
Write-Host "Next manual UI text: $TestText"
