$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedWindowsHead = "8cea566e50f439810e29fb1dc4ac14dc69b5fbc6"
$ExpectedApplicationCommit = "61e1580ff85aa1cfaed1f9e7a7522f1cd8e5d602"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ApkPath = Join-Path $env:TEMP "apu-m3d-v11.16.16.apk"
$ApkBuildStatePath = Join-Path $env:TEMP "apu-m3d-v11.16.16-apk-build.json"
$TransferStatePath = Join-Path $env:TEMP "apu-m3d-kotlin-overlay-transfer.json"
$RecoveryStatePath = Join-Path $env:TEMP "apu-m3d-kotlin-chunk-recovery.json"
$StatePath = Join-Path $env:TEMP "apu-m3d-v11.16.16-install.json"
$EvidenceDir = Join-Path $env:TEMP "apu-m3d-v11.16.16-install-evidence"

$ExpectedApkSize = 22664712
$ExpectedApkHash = "446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D"
$ExpectedNativeSize = 7263416
$ExpectedNativeHash = "27B9D4DC87CA7046D9F862F9ED153FDDD48C26E4053B620FE46986D25D1FD26C"
$ExpectedCertHash = "F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7"
$ExpectedApkBuildStateHash = "917077E82C25DFF9A020713BA4A391DD49D7AFD81446367F87A23B17216AFABC"
$ExpectedTransferStateHash = "A4D8DB1165B1427CB3C23BDB612B183769CE1746D3175C966C17AC880F205154"
$ExpectedRecoveryStateHash = "545DC670A643EE017A71E4CC46374764FA891EA810D3AFE099C2E875B685CA98"
$TargetVersionName = "v11.16.16"
$TargetVersionCode = 11016016
$PackageName = "com.vladimir.messenger"
$ExpectedDataDir = "/data/user/0/com.vladimir.messenger"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$IconRelative = "design/branding/app-icon/source/apu-icon-original.png"
$ExpectedIconSize = 1980451
$ExpectedIconHash = "F2638C88A3EAB243766B8F4755183C89A3E1FFCB72B45A0BBC5F3D398C83ACA9"
$ScriptRelative = "scripts/m3d_v111616_install.ps1"

$Devices = @(
    [pscustomobject]@{
        Name = "Anna"
        Serial = "AUYF6R5923006121"
        ExpectedUid = 10425
        ExpectedFirstInstallTime = "2026-08-08 11:40:39"
        ExpectedVersionName = "v11.16.15"
        ExpectedVersionCode = 11016015
    },
    [pscustomobject]@{
        Name = "Zhenya"
        Serial = "3B665800EES00000"
        ExpectedUid = 10395
        ExpectedFirstInstallTime = "2026-08-08 17:31:18"
        ExpectedVersionName = "v11.16.13"
        ExpectedVersionCode = 11016013
    },
    [pscustomobject]@{
        Name = "Stas"
        Serial = "11567254BK001192"
        ExpectedUid = 10387
        ExpectedFirstInstallTime = "2026-08-10 12:41:10"
        ExpectedVersionName = "v11.16.13"
        ExpectedVersionCode = 11016013
    }
)

if (Test-Path -LiteralPath $StatePath) {
    throw "M3(d) v11.16.16 install already has state; do not repeat: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "M3(d) v11.16.16 install evidence already exists; do not repeat: $EvidenceDir"
}

function Invoke-CapturedNative {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][int]$TimeoutMilliseconds
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

    $Exited = $NativeProcess.WaitForExit($TimeoutMilliseconds)
    if (-not $Exited) {
        try {
            $NativeProcess.Kill()
        } catch {
        }
        throw "Native command timed out: $Label, process=$($NativeProcess.Id)"
    }
    $NativeProcess.WaitForExit()
    $NativeProcess.Refresh()

    $RawExitCode = $NativeProcess.ExitCode
    $ExitCodeAvailable = $null -ne $RawExitCode
    $ExitCode = if ($ExitCodeAvailable) {
        [int]$RawExitCode
    } else {
        $null
    }
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
        ProcessId = $NativeProcess.Id
        ExitCodeAvailable = $ExitCodeAvailable
        ExitCode = $ExitCode
        StdoutLines = $StdoutLines
        StderrLines = $StderrLines
        StdoutPath = $StdoutPath
        StderrPath = $StderrPath
    }
}

function Assert-NoNativeFailure {
    param(
        [Parameter(Mandatory = $true)][pscustomobject]$Result,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
        throw "$Label failed: exit=$($Result.ExitCode)"
    }
}

function Get-DeviceSnapshot {
    param(
        [Parameter(Mandatory = $true)][pscustomobject]$Device,
        [Parameter(Mandatory = $true)][string]$Phase
    )

    $SafeName = $Device.Name.ToLowerInvariant()
    $SafePhase = $Phase.ToLowerInvariant()

    $UidResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @(
            "-s", $Device.Serial, "shell", "cmd", "package", "list", "packages", "-U",
            $PackageName
        ) `
        -Label ("{0}-{1}-uid" -f $SafeName, $SafePhase) `
        -TimeoutMilliseconds 30000
    Assert-NoNativeFailure -Result $UidResult -Label "$($Device.Name) UID query"

    $PackageLine = ($UidResult.StdoutLines -join "").Trim()
    $UidMatch = [regex]::Match($PackageLine, "^package:com\.vladimir\.messenger\s+uid:(\d+)$")
    if (-not $UidMatch.Success) {
        throw "Package UID output is not exact: $($Device.Name), phase=$Phase, output=$PackageLine"
    }

    $DumpResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @("-s", $Device.Serial, "shell", "dumpsys", "package", $PackageName) `
        -Label ("{0}-{1}-package" -f $SafeName, $SafePhase) `
        -TimeoutMilliseconds 30000
    Assert-NoNativeFailure -Result $DumpResult -Label "$($Device.Name) package query"

    $PackageDump = $DumpResult.StdoutLines -join "`n"
    $VersionCodeMatches = @([regex]::Matches($PackageDump, "versionCode=(\d+)"))
    $VersionNameMatches = @([regex]::Matches($PackageDump, "versionName=([^\s\r\n]+)"))
    $FirstInstallMatches = @([regex]::Matches($PackageDump, "firstInstallTime=([^\r\n]+)"))
    $DataDirMatches = @([regex]::Matches($PackageDump, "dataDir=([^\s\r\n]+)"))

    if (
        $VersionCodeMatches.Count -lt 1 -or
        $VersionNameMatches.Count -lt 1 -or
        $FirstInstallMatches.Count -ne 1 -or
        $DataDirMatches.Count -lt 1
    ) {
        throw "Required package fields missing or ambiguous: $($Device.Name), phase=$Phase"
    }

    $VersionCodes = @($VersionCodeMatches | ForEach-Object { [int64]$_.Groups[1].Value } | Sort-Object -Unique)
    $VersionNames = @($VersionNameMatches | ForEach-Object { $_.Groups[1].Value.Trim() } | Sort-Object -Unique)
    $DataDirs = @($DataDirMatches | ForEach-Object { $_.Groups[1].Value.Trim() } | Sort-Object -Unique)
    if ($VersionCodes.Count -ne 1 -or $VersionNames.Count -ne 1 -or $DataDirs.Count -ne 1) {
        throw "Package identity fields are ambiguous: $($Device.Name), phase=$Phase"
    }

    $PidResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @("-s", $Device.Serial, "shell", "pidof", $PackageName) `
        -Label ("{0}-{1}-pidof" -f $SafeName, $SafePhase) `
        -TimeoutMilliseconds 30000
    $ProcessIds = @(
        $PidResult.StdoutLines |
            ForEach-Object { $_ -split "\s+" } |
            Where-Object { $_ -match "^\d+$" } |
            Sort-Object -Unique
    )
    $ProcessOutput = ($PidResult.StdoutLines -join " ").Trim()
    if ($ProcessOutput -and $ProcessIds.Count -lt 1) {
        throw "Unexpected pidof output: $($Device.Name), phase=$Phase, output=$ProcessOutput"
    }
    if ($PidResult.ExitCodeAvailable) {
        $ValidPidOutcome = (
            ($PidResult.ExitCode -eq 0 -and $ProcessIds.Count -ge 1) -or
            ($PidResult.ExitCode -eq 1 -and $ProcessIds.Count -eq 0)
        )
        if (-not $ValidPidOutcome) {
            throw "Unexpected pidof outcome: $($Device.Name), phase=$Phase, exit=$($PidResult.ExitCode)"
        }
    }

    return [pscustomobject]@{
        AppUid = [int]$UidMatch.Groups[1].Value
        VersionName = $VersionNames[0]
        VersionCode = $VersionCodes[0]
        FirstInstallTime = $FirstInstallMatches[0].Groups[1].Value.Trim()
        DataDir = $DataDirs[0]
        ProcessRunning = ($ProcessIds.Count -gt 0)
        ProcessIds = @($ProcessIds)
        PidofExitCodeAvailable = $PidResult.ExitCodeAvailable
        PidofExitCode = $PidResult.ExitCode
    }
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$CurrentBranch = $null
$CurrentHead = $null
$PreflightComplete = $false
$InstallStarted = $false
$InstallCallCount = 0
$PreflightResults = [System.Collections.Generic.List[object]]::new()
$InstallResults = [System.Collections.Generic.List[object]]::new()
$ApkFile = $null
$ApkHash = $null
$VerifiedCount = 0

try {
    if (
        $RepoRoot -notmatch '^[Cc]:\\' -or
        $Adb -notmatch '^[Cc]:\\' -or
        $ApkPath -notmatch '^[Cc]:\\'
    ) {
        throw "APU repo, ADB and APK must all use drive C"
    }

    foreach ($RequiredPath in @(
        $Adb, $ApkPath, $ApkBuildStatePath, $TransferStatePath, $RecoveryStatePath,
        (Join-Path $RepoRoot $GeneratedSoRelative), (Join-Path $RepoRoot $IconRelative)
    )) {
        if (-not (Test-Path -LiteralPath $RequiredPath)) {
            throw "Required install input missing: $RequiredPath"
        }
    }

    $ApkBuildStateHash = (Get-FileHash -LiteralPath $ApkBuildStatePath -Algorithm SHA256).Hash
    $TransferStateHash = (Get-FileHash -LiteralPath $TransferStatePath -Algorithm SHA256).Hash
    $RecoveryStateHash = (Get-FileHash -LiteralPath $RecoveryStatePath -Algorithm SHA256).Hash
    if (
        $ApkBuildStateHash -ne $ExpectedApkBuildStateHash -or
        $TransferStateHash -ne $ExpectedTransferStateHash -or
        $RecoveryStateHash -ne $ExpectedRecoveryStateHash
    ) {
        throw "M3(d) APK/transfer/recovery state identity mismatch"
    }

    $ApkBuildState = Get-Content -LiteralPath $ApkBuildStatePath -Raw | ConvertFrom-Json
    $TransferState = Get-Content -LiteralPath $TransferStatePath -Raw | ConvertFrom-Json
    $RecoveryState = Get-Content -LiteralPath $RecoveryStatePath -Raw | ConvertFrom-Json
    if (
        $ApkBuildState.outcome -ne "PASS" -or
        $ApkBuildState.applicationCommit -ne $ExpectedApplicationCommit -or
        $ApkBuildState.versionName -ne $TargetVersionName -or
        [int64]$ApkBuildState.versionCode -ne $TargetVersionCode -or
        $ApkBuildState.apkSha256 -ne $ExpectedApkHash -or
        [int64]$ApkBuildState.apkSize -ne $ExpectedApkSize -or
        $ApkBuildState.signerV2 -ne $true -or
        $ApkBuildState.signerCertificateSha256 -ne $ExpectedCertHash -or
        $ApkBuildState.embeddedNativeSha256 -ne $ExpectedNativeHash -or
        [int64]$ApkBuildState.embeddedNativeSize -ne $ExpectedNativeSize -or
        $ApkBuildState.phonesChanged -ne $false -or
        $ApkBuildState.adbCommandsUsed -ne $false -or
        $TransferState.outcome -ne "PASS" -or
        $TransferState.harnessStateSha256 -ne $ExpectedApkBuildStateHash -or
        $TransferState.apkSha256 -ne $ExpectedApkHash -or
        $TransferState.phonesChanged -ne $false -or
        $RecoveryState.outcome -ne "PASS" -or
        $RecoveryState.innerTransferStateSha256 -ne $ExpectedTransferStateHash -or
        $RecoveryState.apkBuildStateSha256 -ne $ExpectedApkBuildStateHash -or
        $RecoveryState.apkSha256 -ne $ExpectedApkHash -or
        $RecoveryState.phonesChanged -ne $false
    ) {
        throw "Saved evidence does not prove exact approved APK"
    }

    $ApkFile = Get-Item -LiteralPath $ApkPath
    $ApkHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
    if ($ApkFile.Length -ne $ExpectedApkSize -or $ApkHash -ne $ExpectedApkHash) {
        throw "Authoritative v11.16.16 APK identity mismatch"
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
            throw "M3(d) source mismatch before install: $RelativePath / $WorkingBlob"
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
        "?? $ScriptRelative"
    )
    $StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
    $UnexpectedBefore = @($StatusBefore | Where-Object { $_ -notin $ExpectedStatus })
    if ($LASTEXITCODE -ne 0 -or $UnexpectedBefore.Count -gt 0 -or $StatusBefore.Count -ne $ExpectedStatus.Count) {
        throw "Unexpected Windows worktree before install: $($StatusBefore -join '; ')"
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
        throw "Native/icon identity mismatch before install"
    }

    New-Item -ItemType Directory -Path $EvidenceDir | Out-Null

    $DevicesResult = Invoke-CapturedNative `
        -FilePath $Adb `
        -ArgumentList @("devices") `
        -Label "adb-devices" `
        -TimeoutMilliseconds 30000
    Assert-NoNativeFailure -Result $DevicesResult -Label "adb devices"
    $Connected = @(
        $DevicesResult.StdoutLines |
            Select-Object -Skip 1 |
            Where-Object { $_ -match "\S" }
    )
    foreach ($Device in $Devices) {
        $SerialPattern = "^{0}\s+device$" -f [regex]::Escape($Device.Serial)
        $ExactDevice = @($Connected | Where-Object { $_ -match $SerialPattern })
        if ($ExactDevice.Count -ne 1) {
            throw "$($Device.Name) is absent, unauthorized, offline, or duplicated"
        }
    }

    # All read-only identity checks finish before the first install call.
    foreach ($Device in $Devices) {
        $Pre = Get-DeviceSnapshot -Device $Device -Phase "pre"
        $PreflightResults.Add([pscustomobject]@{
            Name = $Device.Name
            Serial = $Device.Serial
            Snapshot = $Pre
        })

        if (
            $Pre.AppUid -ne $Device.ExpectedUid -or
            $Pre.VersionName -ne $Device.ExpectedVersionName -or
            $Pre.VersionCode -ne $Device.ExpectedVersionCode -or
            $Pre.FirstInstallTime -ne $Device.ExpectedFirstInstallTime -or
            $Pre.DataDir -ne $ExpectedDataDir
        ) {
            throw "Preinstall identity mismatch: $($Device.Name), version=$($Pre.VersionName)/$($Pre.VersionCode), uid=$($Pre.AppUid)"
        }
    }
    $PreflightComplete = $true

    Write-Host "M3D V11.16.16 READ-ONLY PHONE GATE PASS 3/3"
    $PreflightResults | ForEach-Object {
        Write-Host ("{0}: {1}/{2}, UID={3}, running={4}, PIDs={5}" -f
            $_.Name,
            $_.Snapshot.VersionName,
            $_.Snapshot.VersionCode,
            $_.Snapshot.AppUid,
            $_.Snapshot.ProcessRunning,
            ($_.Snapshot.ProcessIds -join ","))
    }
    Write-Host "Starting approved data-preserving installs..."

    foreach ($Device in $Devices) {
        $SafeName = $Device.Name.ToLowerInvariant()
        $PreRecord = @($PreflightResults | Where-Object { $_.Name -eq $Device.Name })[0]
        $InstallStarted = $true
        $InstallCallCount++

        $InstallResult = Invoke-CapturedNative `
            -FilePath $Adb `
            -ArgumentList @("-s", $Device.Serial, "install", "-r", $ApkPath) `
            -Label ("{0}-install" -f $SafeName) `
            -TimeoutMilliseconds 180000
        $InstallLines = @($InstallResult.StdoutLines) + @($InstallResult.StderrLines)
        $SuccessCount = @($InstallLines | Where-Object { $_.Trim() -eq "Success" }).Count
        $InstallNativeFailed = $InstallResult.ExitCodeAvailable -and $InstallResult.ExitCode -ne 0
        if ($InstallNativeFailed -or $SuccessCount -ne 1) {
            $InstallResults.Add([pscustomobject]@{
                Name = $Device.Name
                Serial = $Device.Serial
                Pre = $PreRecord.Snapshot
                InstallProcessId = $InstallResult.ProcessId
                InstallExitCodeAvailable = $InstallResult.ExitCodeAvailable
                InstallExitCode = $InstallResult.ExitCode
                InstallSuccessCount = $SuccessCount
                Post = $null
                Verified = $false
            })
            throw "Approved install failed or ambiguous: $($Device.Name), exit=$($InstallResult.ExitCode), success=$SuccessCount"
        }

        $Post = Get-DeviceSnapshot -Device $Device -Phase "post"
        $Verified = (
            $Post.AppUid -eq $Device.ExpectedUid -and
            $Post.VersionName -eq $TargetVersionName -and
            $Post.VersionCode -eq $TargetVersionCode -and
            $Post.FirstInstallTime -eq $Device.ExpectedFirstInstallTime -and
            $Post.DataDir -eq $ExpectedDataDir -and
            -not $Post.ProcessRunning
        )
        $InstallResults.Add([pscustomobject]@{
            Name = $Device.Name
            Serial = $Device.Serial
            Pre = $PreRecord.Snapshot
            InstallProcessId = $InstallResult.ProcessId
            InstallExitCodeAvailable = $InstallResult.ExitCodeAvailable
            InstallExitCode = $InstallResult.ExitCode
            InstallSuccessCount = $SuccessCount
            Post = $Post
            Verified = $Verified
        })
        if (-not $Verified) {
            throw "Post-install data/version/process gate failed: $($Device.Name)"
        }
    }

    $VerifiedCount = @($InstallResults | Where-Object { $_.Verified }).Count
    if ($InstallCallCount -ne 3 -or $VerifiedCount -ne 3) {
        throw "M3(d) install verification did not pass exactly 3/3"
    }

    $StatusAfter = @(& git status --porcelain=v1 --untracked-files=all)
    $UnexpectedAfter = @($StatusAfter | Where-Object { $_ -notin $ExpectedStatus })
    if ($LASTEXITCODE -ne 0 -or $UnexpectedAfter.Count -gt 0 -or $StatusAfter.Count -ne $ExpectedStatus.Count) {
        throw "Windows worktree changed unexpectedly during phone install"
    }
    $FinalApkHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
    $FinalIconHash = (Get-FileHash -LiteralPath $IconPath -Algorithm SHA256).Hash
    if ($FinalApkHash -ne $ExpectedApkHash -or $FinalIconHash -ne $ExpectedIconHash) {
        throw "APK/icon changed during install"
    }

    $Outcome = "PASS"
}
catch {
    $Failure = $_.Exception.Message
    throw
}
finally {
    $VerifiedCount = @($InstallResults | Where-Object { $_.Verified }).Count
    $State = [ordered]@{
        schema = 1
        purpose = "M3(d) approved data-preserving v11.16.16 install on Anna Zhenya Stas"
        outcome = $Outcome
        failure = $Failure
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        userApprovedInstall = $true
        windowsBranch = $CurrentBranch
        windowsHead = $CurrentHead
        applicationCommit = $ExpectedApplicationCommit
        scriptPath = $PSCommandPath
        scriptSha256 = if (Test-Path -LiteralPath $PSCommandPath) {
            (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        } else {
            $null
        }
        apkPath = $ApkPath
        apkSize = if ($null -ne $ApkFile) { $ApkFile.Length } else { $null }
        apkSha256 = $ApkHash
        targetVersionName = $TargetVersionName
        targetVersionCode = $TargetVersionCode
        signerCertificateSha256 = $ExpectedCertHash
        apkBuildStateSha256 = $ExpectedApkBuildStateHash
        transferStateSha256 = $ExpectedTransferStateHash
        recoveryStateSha256 = $ExpectedRecoveryStateHash
        evidenceDirectory = $EvidenceDir
        preflightComplete = $PreflightComplete
        installStarted = $InstallStarted
        installCallCount = $InstallCallCount
        verifiedInstallCount = $VerifiedCount
        preflightDevices = @($PreflightResults)
        installDevices = @($InstallResults)
        usedAdbInstallReplace = $InstallStarted
        uninstalled = $false
        dataCleared = $false
        forceStopped = $false
        launched = $false
        logcatCleared = $false
        networkChanged = $false
        publicTrafficSent = $false
        automaticRetry = $false
    }
    $State | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:                $Outcome"
    Write-Host "State:                  $StatePath"
    Write-Host "State SHA256:           $StateHash"
    Write-Host "Read-only gate complete:$PreflightComplete"
    Write-Host "Install calls:          $InstallCallCount"
    Write-Host "Verified installs:      $VerifiedCount"
    Write-Host "Target version:         $TargetVersionName / $TargetVersionCode"
    Write-Host "Uninstall/data clear:   False / False"
    Write-Host "Force-stop/launch:      False / False"
    Write-Host "Network/logcat/public:  False / False / False"
}

if ($Outcome -ne "PASS") {
    throw "M3(d) v11.16.16 install did not pass; inspect immutable state"
}

Write-Host ""
Write-Host "M3D V11.16.16 DATA-PRESERVING INSTALL PASS 3/3"
$InstallResults | ForEach-Object {
    Write-Host ("{0}: {1}/{2} -> {3}/{4}; UID {5}->{6}; firstInstall/data preserved={7}/{8}; stopped={9}" -f
        $_.Name,
        $_.Pre.VersionName,
        $_.Pre.VersionCode,
        $_.Post.VersionName,
        $_.Post.VersionCode,
        $_.Pre.AppUid,
        $_.Post.AppUid,
        ($_.Pre.FirstInstallTime -eq $_.Post.FirstInstallTime),
        ($_.Pre.DataDir -eq $_.Post.DataDir),
        (-not $_.Post.ProcessRunning))
}
