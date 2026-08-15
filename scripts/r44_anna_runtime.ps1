$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedApplicationCommit = "0181b3496cde81477a01e49f8d9977d7c325a2ca"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$ExpectedNativeSize = 7248576
$ExpectedNativeHash = "E6C34E86F18D9F63B9A641E3FD9FAFD67D5F1B7101729B2CB3DF25163380095B"
$ApkPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15.apk"
$ApkStatePath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-apk-build.json"
$ExpectedApkStateHash = "3D893F5A546008C3166C68026203EF2B395144EA9C072D6E9583E9FE84644078"
$ExpectedApkSize = 22664716
$ExpectedApkHash = "B675770D043E4ABA5D6D099275F489DB9666A9B16792DD45000A9EC2D243E9B2"
$ExpectedCertHash = "F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7"
$IntegrationStatePath = Join-Path $env:TEMP "apu-r4.4-dual-integration-rust-build.json"
$ExpectedIntegrationStateHash = "A3E247756AC92DC77B836DDF19DC8B509B6A6DE9E6CB493AD22287A36DB5B3E4"
$StatePath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-anna-runtime.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-anna-runtime-evidence"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$PackageName = "com.vladimir.messenger"
$AnnaSerial = "AUYF6R5923006121"
$AnnaUid = 10425
$AnnaFirstInstall = "2026-08-08 11:40:39"
$EarlyWaitSeconds = 15
$LateWaitSeconds = 135

if (Test-Path -LiteralPath $StatePath) {
    throw "R4.4 ANNA RUNTIME ALREADY ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "R4.4 ANNA RUNTIME EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
}
foreach ($RequiredPath in @($Adb, $ApkPath, $ApkStatePath, $IntegrationStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required runtime input missing: $RequiredPath"
    }
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
    throw "Unexpected pre-runtime worktree changes: $($UnexpectedBefore -join '; ')"
}
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$NativeFile = Get-Item -LiteralPath $SoPath
$NativeHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($NativeFile.Length -ne $ExpectedNativeSize -or $NativeHash -ne $ExpectedNativeHash) {
    throw "Dual integration native mismatch: size=$($NativeFile.Length) hash=$NativeHash"
}

$ApkStateHash = (Get-FileHash -LiteralPath $ApkStatePath -Algorithm SHA256).Hash
if ($ApkStateHash -ne $ExpectedApkStateHash) {
    throw "APK state hash mismatch: $ApkStateHash"
}
$ApkState = Get-Content -LiteralPath $ApkStatePath -Raw | ConvertFrom-Json
$ApkStateValid = $ApkState.outcome -eq "PASS"
$ApkStateValid = $ApkStateValid -and $ApkState.packageName -eq $PackageName
$ApkStateValid = $ApkStateValid -and $ApkState.versionName -eq "v11.16.15"
$ApkStateValid = $ApkStateValid -and [int64]$ApkState.versionCode -eq 11016015
$ApkStateValid = $ApkStateValid -and $ApkState.signerV2 -eq $true
$ApkStateValid = $ApkStateValid -and $ApkState.signerCertificateSha256 -eq $ExpectedCertHash
$ApkStateValid = $ApkStateValid -and $ApkState.embeddedNativeSha256 -eq $ExpectedNativeHash
$ApkStateValid = $ApkStateValid -and $ApkState.phonesChanged -eq $false
$ApkStateValid = $ApkStateValid -and $ApkState.adbCommandsUsed -eq $false
$ApkStateValid = $ApkStateValid -and $ApkState.automaticRetry -eq $false
if (-not $ApkStateValid) {
    throw "APK state does not prove the exact v11.16.15 artifact"
}
$ApkFile = Get-Item -LiteralPath $ApkPath
$ApkHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
if ($ApkFile.Length -ne $ExpectedApkSize -or $ApkHash -ne $ExpectedApkHash) {
    throw "APK identity mismatch: size=$($ApkFile.Length) hash=$ApkHash"
}

$IntegrationStateHash = (Get-FileHash -LiteralPath $IntegrationStatePath -Algorithm SHA256).Hash
if ($IntegrationStateHash -ne $ExpectedIntegrationStateHash) {
    throw "Integration build state hash mismatch: $IntegrationStateHash"
}
$IntegrationState = Get-Content -LiteralPath $IntegrationStatePath -Raw | ConvertFrom-Json
$IntegrationContractValid = $IntegrationState.outcome -eq "PASS"
$IntegrationContractValid = $IntegrationContractValid -and $IntegrationState.runtimeIntegrationEnabled -eq $true
$IntegrationContractValid = $IntegrationContractValid -and $IntegrationState.wireFormatChanged -eq $false
$IntegrationContractValid = $IntegrationContractValid -and $IntegrationState.generatedSoSha256 -eq $ExpectedNativeHash
if (-not $IntegrationContractValid) {
    throw "Integration build state contract mismatch"
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
    $Process = Start-Process `
        -FilePath $File `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $StandardOutputPath `
        -RedirectStandardError $StandardErrorPath `
        -PassThru
    if (-not $Process.WaitForExit(120000)) {
        throw "$Label process $($Process.Id) timed out"
    }
    $Process.WaitForExit()
    $Process.Refresh()
    return [pscustomobject]@{
        ExitCode = [int]$Process.ExitCode
        StandardOutput = @(Get-Content -LiteralPath $StandardOutputPath)
        StandardError = @(Get-Content -LiteralPath $StandardErrorPath)
        StandardOutputPath = $StandardOutputPath
        StandardErrorPath = $StandardErrorPath
    }
}

function Get-AnnaSnapshot {
    param([Parameter(Mandatory = $true)][string]$Phase)

    $UidResult = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "cmd", "package", "list", "packages", "-U", $PackageName) "anna-$Phase-uid"
    $PackageResult = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "dumpsys", "package", $PackageName) "anna-$Phase-package"
    $ProcessResult = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "pidof", $PackageName) "anna-$Phase-process"
    if ($UidResult.ExitCode -ne 0 -or $PackageResult.ExitCode -ne 0 -or $ProcessResult.ExitCode -notin @(0, 1)) {
        throw "Anna snapshot command failed at $Phase"
    }

    $UidText = $UidResult.StandardOutput -join ""
    $PackageText = $PackageResult.StandardOutput -join "`n"
    $UidMatch = [regex]::Match($UidText, "uid:(\d+)")
    $VersionNameMatch = [regex]::Match($PackageText, "versionName=([^\s\r\n]+)")
    $VersionCodeMatch = [regex]::Match($PackageText, "versionCode=(\d+)")
    $FirstInstallMatch = [regex]::Match($PackageText, "firstInstallTime=([^\r\n]+)")
    $DataDirMatch = [regex]::Match($PackageText, "dataDir=([^\s\r\n]+)")
    $SnapshotParsed = $UidMatch.Success -and $VersionNameMatch.Success
    $SnapshotParsed = $SnapshotParsed -and $VersionCodeMatch.Success
    $SnapshotParsed = $SnapshotParsed -and $FirstInstallMatch.Success
    $SnapshotParsed = $SnapshotParsed -and $DataDirMatch.Success
    if (-not $SnapshotParsed) {
        throw "Anna snapshot parse failed at $Phase"
    }

    $ProcessIds = @(
        $ProcessResult.StandardOutput |
            ForEach-Object { $_ -split "\s+" } |
            Where-Object { $_ -match "^\d+$" }
    )
    if ($ProcessResult.ExitCode -eq 1 -and $ProcessIds.Count -ne 0) {
        throw "Anna pidof exit/output mismatch at $Phase"
    }
    if ($ProcessResult.ExitCode -eq 0 -and $ProcessIds.Count -eq 0) {
        throw "Anna pidof returned success without process at $Phase"
    }

    return [pscustomobject]@{
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

function Get-DeviceEpoch {
    $Result = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "date", "+%s") "anna-launch-epoch"
    $EpochText = ($Result.StandardOutput -join "").Trim()
    if ($Result.ExitCode -ne 0 -or $EpochText -notmatch "^\d+$") {
        throw "Anna epoch capture failed"
    }
    return [int64]$EpochText
}

function Get-NativeLines {
    param(
        [Parameter(Mandatory = $true)][string]$AndroidProcessId,
        [Parameter(Mandatory = $true)][int64]$LaunchEpoch,
        [Parameter(Mandatory = $true)][string]$Phase
    )

    $Result = Invoke-Captured $Adb @("-s", $AnnaSerial, "logcat", "-d", "-v", "epoch", "p2p_core:V", "*:S") "anna-$Phase-p2p"
    if ($Result.ExitCode -ne 0) {
        throw "Anna native log capture failed at $Phase"
    }
    $Filtered = [System.Collections.Generic.List[string]]::new()
    $Pattern = "^\s*(\d+\.\d+)\s+{0}\s+" -f [regex]::Escape($AndroidProcessId)
    foreach ($Line in $Result.StandardOutput) {
        $LineMatch = [regex]::Match($Line, $Pattern)
        if (-not $LineMatch.Success) {
            continue
        }
        $LineEpoch = [double]::Parse(
            $LineMatch.Groups[1].Value,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        if ($LineEpoch -ge [double]$LaunchEpoch) {
            $Filtered.Add($Line)
        }
    }
    return @($Filtered)
}

function Count-Text {
    param(
        [Parameter(Mandatory = $true)][string[]]$Lines,
        [Parameter(Mandatory = $true)][string]$Text
    )
    return @($Lines | Where-Object { $_.Contains($Text) }).Count
}

function Get-LastLineEpoch {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Lines
    )
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
$InstallStarted = $false
$LaunchStarted = $false
$PreSnapshot = $null
$PostInstallSnapshot = $null
$StartedSnapshot = $null
$FinalSnapshot = $null
$LaunchEpoch = $null
$NewProcessId = $null
$Metrics = $null
$HeartbeatLine = $null

try {
    $DevicesResult = Invoke-Captured $Adb @("devices") "adb-devices"
    if ($DevicesResult.ExitCode -ne 0) {
        throw "adb devices failed"
    }
    $AnnaDevicePattern = "^{0}\s+device$" -f [regex]::Escape($AnnaSerial)
    $AnnaDeviceCount = @(
        $DevicesResult.StandardOutput |
            Select-Object -Skip 1 |
            Where-Object { $_ -match $AnnaDevicePattern }
    ).Count
    if ($AnnaDeviceCount -ne 1) {
        throw "Anna is not connected as exactly one authorized device"
    }

    $PreSnapshot = Get-AnnaSnapshot "pre"
    $PreIdentityValid = $PreSnapshot.Uid -eq $AnnaUid
    $PreIdentityValid = $PreIdentityValid -and $PreSnapshot.VersionName -eq "v11.16.14"
    $PreIdentityValid = $PreIdentityValid -and $PreSnapshot.VersionCode -eq 11016014
    $PreIdentityValid = $PreIdentityValid -and $PreSnapshot.FirstInstall -eq $AnnaFirstInstall
    $PreIdentityValid = $PreIdentityValid -and $PreSnapshot.DataDir -eq "/data/user/0/$PackageName"
    $PreIdentityValid = $PreIdentityValid -and $PreSnapshot.ProcessIds.Count -le 1
    if (-not $PreIdentityValid) {
        throw "Anna preinstall identity/version gate failed"
    }

    $InstallStarted = $true
    $InstallResult = Invoke-Captured $Adb @("-s", $AnnaSerial, "install", "-r", $ApkPath) "anna-install"
    $InstallLines = @($InstallResult.StandardOutput) + @($InstallResult.StandardError)
    $InstallSuccessCount = @($InstallLines | Where-Object { $_.Trim() -eq "Success" }).Count
    if ($InstallResult.ExitCode -ne 0 -or $InstallSuccessCount -ne 1) {
        throw "Anna install failed: exit=$($InstallResult.ExitCode) success=$InstallSuccessCount"
    }

    $PostInstallSnapshot = Get-AnnaSnapshot "postinstall"
    $PostInstallValid = $PostInstallSnapshot.Uid -eq $AnnaUid
    $PostInstallValid = $PostInstallValid -and $PostInstallSnapshot.VersionName -eq "v11.16.15"
    $PostInstallValid = $PostInstallValid -and $PostInstallSnapshot.VersionCode -eq 11016015
    $PostInstallValid = $PostInstallValid -and $PostInstallSnapshot.FirstInstall -eq $AnnaFirstInstall
    $PostInstallValid = $PostInstallValid -and $PostInstallSnapshot.DataDir -eq "/data/user/0/$PackageName"
    $PostInstallValid = $PostInstallValid -and -not $PostInstallSnapshot.Running
    if (-not $PostInstallValid) {
        throw "Anna data-preserving postinstall gate failed"
    }

    $LaunchEpoch = Get-DeviceEpoch
    $LaunchStarted = $true
    $LaunchResult = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "am", "start", "-W", "-n", "$PackageName/.MainActivity") "anna-launch"
    $LaunchOkCount = @($LaunchResult.StandardOutput | Where-Object { $_.Trim() -eq "Status: ok" }).Count
    if ($LaunchResult.ExitCode -ne 0 -or $LaunchOkCount -ne 1) {
        throw "Anna launch failed"
    }

    $StartedSnapshot = Get-AnnaSnapshot "started"
    if ($StartedSnapshot.ProcessIds.Count -ne 1) {
        throw "Anna new process is missing"
    }
    $NewProcessId = [string]$StartedSnapshot.ProcessIds[0]

    Start-Sleep -Seconds $EarlyWaitSeconds
    $EarlyLines = @(Get-NativeLines -AndroidProcessId $NewProcessId -LaunchEpoch $LaunchEpoch -Phase "early")
    Start-Sleep -Seconds $LateWaitSeconds
    $LateLines = @(Get-NativeLines -AndroidProcessId $NewProcessId -LaunchEpoch $LaunchEpoch -Phase "late")

    $FinalSnapshot = Get-AnnaSnapshot "final"
    if ($FinalSnapshot.ProcessIds.Count -ne 1 -or [string]$FinalSnapshot.ProcessIds[0] -ne $NewProcessId) {
        throw "Anna process was not stable"
    }
    $FinalIdentityValid = $FinalSnapshot.VersionCode -eq 11016015
    $FinalIdentityValid = $FinalIdentityValid -and $FinalSnapshot.Uid -eq $AnnaUid
    $FinalIdentityValid = $FinalIdentityValid -and $FinalSnapshot.FirstInstall -eq $AnnaFirstInstall
    if (-not $FinalIdentityValid) {
        throw "Anna final identity/version changed"
    }

    $AllLines = @(@($EarlyLines) + @($LateLines) | Sort-Object -Unique)
    $SecondaryReadyLines = @($AllLines | Where-Object { $_.Contains("MQTT SECONDARY READY: broker=emqx") })
    $SecondaryConnectedLines = @($AllLines | Where-Object { $_.Contains("MQTT SECONDARY STATUS: broker=emqx state=connected") })
    $SecondaryBackoffLines = @($AllLines | Where-Object { $_.Contains("MQTT SECONDARY STATUS: broker=emqx state=backoff") })
    $PrimaryReadyLines = @($AllLines | Where-Object { $_.Contains("MQTT SESSION READY:") })
    $PrimaryReconnectLines = @($AllLines | Where-Object { $_.Contains("MQTT: connection restored; subscription requested") })
    $PrimaryErrorLines = @($AllLines | Where-Object { $_.Contains("MQTT error:") })
    $HeartbeatLines = @($AllLines | Where-Object { $_.Contains("MQTT LIVENESS HEARTBEAT:") })
    $FanoutTwoLines = @(
        $AllLines | Where-Object {
            $_.Contains("MQTT FANOUT QUEUED:") -and $_.Contains("brokers=2")
        }
    )
    $DuplicateLines = @($AllLines | Where-Object { $_.Contains("MQTT CROSS-BROKER DUPLICATE DROPPED:") })
    $HiveIngressLines = @($AllLines | Where-Object { $_.Contains("MQTT IN: broker=hivemq") })
    $EmqxIngressLines = @($AllLines | Where-Object { $_.Contains("MQTT IN: broker=emqx") })
    $HiveDuplicateLines = @($DuplicateLines | Where-Object { $_.Contains("broker=hivemq") })
    $EmqxDuplicateLines = @($DuplicateLines | Where-Object { $_.Contains("broker=emqx") })

    $Metrics = [ordered]@{
        secondaryConnected = $SecondaryConnectedLines.Count
        secondaryReady = $SecondaryReadyLines.Count
        secondaryBackoff = $SecondaryBackoffLines.Count
        secondaryStopped = Count-Text $AllLines "MQTT SECONDARY STATUS: broker=emqx state=stopped"
        primaryReady = $PrimaryReadyLines.Count
        primaryReconnect = $PrimaryReconnectLines.Count
        primaryErrors = $PrimaryErrorLines.Count
        primaryHeartbeat = $HeartbeatLines.Count
        fanoutTwo = $FanoutTwoLines.Count
        duplicateDrops = $DuplicateLines.Count
        hiveIngress = $HiveIngressLines.Count
        emqxIngress = $EmqxIngressLines.Count
        hiveDuplicate = $HiveDuplicateLines.Count
        emqxDuplicate = $EmqxDuplicateLines.Count
        stalls = Count-Text $AllLines "MQTT LIVENESS STALLED:"
        restarts = Count-Text $AllLines "MQTT SESSION RESTART"
        requestErrors = Count-Text $AllLines "MQTT REQUEST ERROR:"
        requestTimeouts = Count-Text $AllLines "MQTT REQUEST TIMEOUT:"
        secondaryRequestErrors = Count-Text $AllLines "MQTT SECONDARY REQUEST ERROR:"
        secondaryRequestTimeouts = Count-Text $AllLines "MQTT SECONDARY REQUEST TIMEOUT:"
        inboxInvariant = Count-Text $AllLines "MQTT LOSS-INTOLERANT INBOX INVARIANT:"
    }

    if ($SecondaryBackoffLines.Count -gt 0) {
        $LastSecondaryReadyEpoch = Get-LastLineEpoch -Lines $SecondaryReadyLines
        $LastSecondaryBackoffEpoch = Get-LastLineEpoch -Lines $SecondaryBackoffLines
        if ($null -eq $LastSecondaryReadyEpoch -or $LastSecondaryReadyEpoch -le $LastSecondaryBackoffEpoch) {
            throw "Secondary did not recover after its last backoff"
        }
    }
    if ($PrimaryErrorLines.Count -gt 0) {
        $PrimaryRecoveryLines = @($PrimaryReadyLines) + @($PrimaryReconnectLines)
        $PrimaryRecoveryLines = @($PrimaryRecoveryLines | Sort-Object -Unique)
        $LastPrimaryRecoveryEpoch = Get-LastLineEpoch -Lines $PrimaryRecoveryLines
        $LastPrimaryErrorEpoch = Get-LastLineEpoch -Lines $PrimaryErrorLines
        if ($null -eq $LastPrimaryRecoveryEpoch -or $LastPrimaryRecoveryEpoch -le $LastPrimaryErrorEpoch) {
            throw "Primary did not recover after its last network error"
        }
    }

    if ($HeartbeatLines.Count -lt 1) {
        throw "Primary heartbeat missing"
    }
    $HeartbeatLine = $HeartbeatLines[-1]
    $HeartbeatHealthy = $HeartbeatLine -match "phase=polling"
    $HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatLine -match "connacks=([1-9]\d*)"
    $HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatLine -match "loss_intolerant_pending=0"
    $HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatLine -match "request_timeouts=0"
    $HeartbeatHealthy = $HeartbeatHealthy -and $HeartbeatLine -match "request_errors=0"
    if (-not $HeartbeatHealthy) {
        throw "Primary heartbeat counters are not healthy"
    }

    $RuntimeMatrixPass = $Metrics.secondaryReady -ge 1
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.secondaryStopped -eq 0
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.fanoutTwo -ge 1
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.duplicateDrops -ge 1
    $RuntimeMatrixPass = $RuntimeMatrixPass -and ($Metrics.hiveIngress + $Metrics.hiveDuplicate) -ge 1
    $RuntimeMatrixPass = $RuntimeMatrixPass -and ($Metrics.emqxIngress + $Metrics.emqxDuplicate) -ge 1
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.primaryHeartbeat -ge 1
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.stalls -eq 0
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.restarts -eq 0
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.requestErrors -eq 0
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.requestTimeouts -eq 0
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.secondaryRequestErrors -eq 0
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.secondaryRequestTimeouts -eq 0
    $RuntimeMatrixPass = $RuntimeMatrixPass -and $Metrics.inboxInvariant -eq 0
    if (-not $RuntimeMatrixPass) {
        $MetricsJson = $Metrics | ConvertTo-Json -Compress
        throw "Anna r4.4 runtime matrix incomplete: $MetricsJson"
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
        purpose = "approved Anna-only r4.4 dual-session v11.16.15 runtime"
        outcome = $Outcome
        failure = $Failure
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        userApproved = $true
        applicationCommit = $ExpectedApplicationCommit
        windowsHead = $CurrentHead
        apkState = $ApkStatePath
        apkStateSha256 = $ApkStateHash
        apkPath = $ApkPath
        apkSize = $ApkFile.Length
        apkSha256 = $ApkHash
        integrationState = $IntegrationStatePath
        integrationStateSha256 = $IntegrationStateHash
        evidenceDirectory = $EvidenceDir
        installStarted = $InstallStarted
        launchStarted = $LaunchStarted
        launchEpoch = $LaunchEpoch
        annaPre = $PreSnapshot
        annaPostInstall = $PostInstallSnapshot
        annaStarted = $StartedSnapshot
        annaFinal = $FinalSnapshot
        annaNewProcessId = $NewProcessId
        metrics = $Metrics
        latestHeartbeat = $HeartbeatLine
        configuredBrokers = @("hivemq", "emqx")
        maxSessions = 2
        maxFanout = 2
        duplicateWindowSeconds = 30
        duplicateCapacity = 4096
        wireFormatChanged = $false
        mixedVersionCommonBroker = "hivemq"
        requiredUsbPhones = @("Anna")
        zhenyaTouched = $false
        stasTouched = $false
        uninstalled = $false
        dataCleared = $false
        forceStopped = $false
        logcatCleared = $false
        networkChanged = $false
        userPayloadPublished = $false
        automaticRetry = $false
    }
    $State | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:                    $Outcome"
    Write-Host "State:                      $StatePath"
    Write-Host "State SHA256:               $StateHash"
    Write-Host "Install/launch:             $InstallStarted / $LaunchStarted"
    Write-Host "Anna new process:           $NewProcessId"
    Write-Host "Metrics:                    $($Metrics | ConvertTo-Json -Compress)"
    Write-Host "Zhenya/Stas touched:        False / False"
    Write-Host "User payload published:     False"
}

if ($Outcome -ne "PASS") {
    throw "r4.4 Anna runtime did not pass; see state: $StatePath"
}

Write-Host "R4.4 ANNA DUAL-SESSION RUNTIME PASS"
