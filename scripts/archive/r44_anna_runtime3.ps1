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
$ParentRuntimeStatePath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-anna-runtime2.json"
$ExpectedParentRuntimeStateHash = "78AF1BFC1AC9A3869973EAE5C2E1CCE8C6A08E6B081F8540C0CF3F74B34DA0B5"
$ParentEvidenceDir = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-anna-runtime2-evidence"
$ExpectedParentEvidenceHashes = [ordered]@{
    "anna-install.stdout.log" = "C4AA470ACAFCF8576C15B0F0B5AE7E852D1EC5413372D0E6A9272E788E1306D3"
    "anna-install.stderr.log" = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"
    "anna-postinstall-uid.stdout.log" = "B6D5357A42B0EE2DDC80CF0F5AC7F323DE67C01C3B25F4D7342A0A9914E34FD7"
    "anna-postinstall-uid.stderr.log" = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"
    "anna-postinstall-package.stdout.log" = "E353F7F6883A3D75A30C52B73E846317CC5C91F890F5D73D8BF9E4ECA41C6199"
    "anna-postinstall-package.stderr.log" = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"
    "anna-postinstall-process.stdout.log" = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"
    "anna-postinstall-process.stderr.log" = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"
}
$StatePath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-anna-runtime3.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-anna-runtime3-evidence"
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
foreach ($RequiredPath in @(
    $Adb,
    $ApkPath,
    $ApkStatePath,
    $IntegrationStatePath,
    $ParentRuntimeStatePath
)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required runtime input missing: $RequiredPath"
    }
}
if (-not (Test-Path -LiteralPath $ParentEvidenceDir -PathType Container)) {
    throw "Parent runtime evidence directory missing: $ParentEvidenceDir"
}

$ParentRuntimeStateHash = (Get-FileHash -LiteralPath $ParentRuntimeStatePath -Algorithm SHA256).Hash
if ($ParentRuntimeStateHash -ne $ExpectedParentRuntimeStateHash) {
    throw "Parent runtime state hash mismatch: $ParentRuntimeStateHash"
}
$ParentRuntimeState = Get-Content -LiteralPath $ParentRuntimeStatePath -Raw | ConvertFrom-Json
$ParentStoppedAfterInstall = $ParentRuntimeState.outcome -eq "INCOMPLETE_DO_NOT_REPEAT"
$ParentStoppedAfterInstall = $ParentStoppedAfterInstall -and $ParentRuntimeState.failure -eq "Anna pidof returned success without process at postinstall"
$ParentStoppedAfterInstall = $ParentStoppedAfterInstall -and $ParentRuntimeState.installStarted -eq $true
$ParentStoppedAfterInstall = $ParentStoppedAfterInstall -and $ParentRuntimeState.launchStarted -eq $false
$ParentStoppedAfterInstall = $ParentStoppedAfterInstall -and $ParentRuntimeState.zhenyaTouched -eq $false
$ParentStoppedAfterInstall = $ParentStoppedAfterInstall -and $ParentRuntimeState.stasTouched -eq $false
$ParentStoppedAfterInstall = $ParentStoppedAfterInstall -and $ParentRuntimeState.userPayloadPublished -eq $false
$ParentStoppedAfterInstall = $ParentStoppedAfterInstall -and $ParentRuntimeState.automaticRetry -eq $false
if (-not $ParentStoppedAfterInstall) {
    throw "Parent runtime state does not prove install-complete/launch-not-started"
}

$ParentEvidenceActualHashes = [ordered]@{}
foreach ($EvidenceName in $ExpectedParentEvidenceHashes.Keys) {
    $EvidencePath = Join-Path $ParentEvidenceDir $EvidenceName
    if (-not (Test-Path -LiteralPath $EvidencePath -PathType Leaf)) {
        throw "Parent runtime evidence missing: $EvidencePath"
    }
    $EvidenceHash = (Get-FileHash -LiteralPath $EvidencePath -Algorithm SHA256).Hash
    if ($EvidenceHash -ne $ExpectedParentEvidenceHashes[$EvidenceName]) {
        throw "Parent runtime evidence hash mismatch: $EvidenceName $EvidenceHash"
    }
    $ParentEvidenceActualHashes[$EvidenceName] = $EvidenceHash
}

$SavedInstallStdout = @(Get-Content -LiteralPath (Join-Path $ParentEvidenceDir "anna-install.stdout.log"))
$SavedInstallStderr = @(Get-Content -LiteralPath (Join-Path $ParentEvidenceDir "anna-install.stderr.log"))
$SavedUidText = @(Get-Content -LiteralPath (Join-Path $ParentEvidenceDir "anna-postinstall-uid.stdout.log")) -join ""
$SavedPackageText = @(Get-Content -LiteralPath (Join-Path $ParentEvidenceDir "anna-postinstall-package.stdout.log")) -join "`n"
$SavedProcessText = @(Get-Content -LiteralPath (Join-Path $ParentEvidenceDir "anna-postinstall-process.stdout.log")) -join ""
$SavedUidStderr = @(Get-Content -LiteralPath (Join-Path $ParentEvidenceDir "anna-postinstall-uid.stderr.log"))
$SavedPackageStderr = @(Get-Content -LiteralPath (Join-Path $ParentEvidenceDir "anna-postinstall-package.stderr.log"))
$SavedProcessStderr = @(Get-Content -LiteralPath (Join-Path $ParentEvidenceDir "anna-postinstall-process.stderr.log"))

$SavedUidMatch = [regex]::Match($SavedUidText, "uid:(\d+)")
$SavedVersionNameMatch = [regex]::Match($SavedPackageText, "versionName=([^\s\r\n]+)")
$SavedVersionCodeMatch = [regex]::Match($SavedPackageText, "versionCode=(\d+)")
$SavedFirstInstallMatch = [regex]::Match($SavedPackageText, "firstInstallTime=([^\r\n]+)")
$SavedDataDirMatch = [regex]::Match($SavedPackageText, "dataDir=([^\s\r\n]+)")
$SavedInstallSuccessCount = @($SavedInstallStdout | Where-Object { $_.Trim() -eq "Success" }).Count

$SavedInstallValid = $SavedInstallSuccessCount -eq 1
$SavedInstallValid = $SavedInstallValid -and $SavedInstallStderr.Count -eq 0
$SavedInstallValid = $SavedInstallValid -and $SavedUidStderr.Count -eq 0
$SavedInstallValid = $SavedInstallValid -and $SavedPackageStderr.Count -eq 0
$SavedInstallValid = $SavedInstallValid -and $SavedProcessStderr.Count -eq 0
$SavedInstallValid = $SavedInstallValid -and [string]::IsNullOrWhiteSpace($SavedProcessText)
$SavedInstallValid = $SavedInstallValid -and $SavedUidMatch.Success
$SavedInstallValid = $SavedInstallValid -and $SavedVersionNameMatch.Success
$SavedInstallValid = $SavedInstallValid -and $SavedVersionCodeMatch.Success
$SavedInstallValid = $SavedInstallValid -and $SavedFirstInstallMatch.Success
$SavedInstallValid = $SavedInstallValid -and $SavedDataDirMatch.Success
if ($SavedInstallValid) {
    $SavedInstallValid = [int64]$SavedUidMatch.Groups[1].Value -eq $AnnaUid
    $SavedInstallValid = $SavedInstallValid -and $SavedVersionNameMatch.Groups[1].Value.Trim() -eq "v11.16.15"
    $SavedInstallValid = $SavedInstallValid -and [int64]$SavedVersionCodeMatch.Groups[1].Value -eq 11016015
    $SavedInstallValid = $SavedInstallValid -and $SavedFirstInstallMatch.Groups[1].Value.Trim() -eq $AnnaFirstInstall
    $SavedInstallValid = $SavedInstallValid -and $SavedDataDirMatch.Groups[1].Value.Trim() -eq "/data/user/0/$PackageName"
}
if (-not $SavedInstallValid) {
    throw "Saved parent evidence does not prove the exact data-preserving v11.16.15 install"
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
    $RawExitCode = $Process.ExitCode
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

function Get-AnnaSnapshot {
    param([Parameter(Mandatory = $true)][string]$Phase)

    $UidResult = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "cmd", "package", "list", "packages", "-U", $PackageName) "anna-$Phase-uid"
    $PackageResult = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "dumpsys", "package", $PackageName) "anna-$Phase-package"
    $ProcessResult = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "pidof", $PackageName) "anna-$Phase-process"
    $SnapshotStderrCount = $UidResult.StandardError.Count
    $SnapshotStderrCount += $PackageResult.StandardError.Count
    $SnapshotStderrCount += $ProcessResult.StandardError.Count
    if ($SnapshotStderrCount -ne 0) {
        throw "Anna snapshot returned stderr at $Phase"
    }
    if ($UidResult.ExitCodeAvailable -and $UidResult.ExitCode -ne 0) {
        throw "Anna UID snapshot command failed at $Phase"
    }
    if ($PackageResult.ExitCodeAvailable -and $PackageResult.ExitCode -ne 0) {
        throw "Anna package snapshot command failed at $Phase"
    }
    if ($ProcessResult.ExitCodeAvailable -and $ProcessResult.ExitCode -notin @(0, 1)) {
        throw "Anna process snapshot command failed at $Phase"
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

    $ProcessText = ($ProcessResult.StandardOutput -join " ").Trim()
    $ProcessIds = @()
    if (-not [string]::IsNullOrWhiteSpace($ProcessText)) {
        $ProcessTokens = @($ProcessText -split "\s+")
        $InvalidProcessTokens = @($ProcessTokens | Where-Object { $_ -notmatch "^\d+$" })
        if ($InvalidProcessTokens.Count -ne 0) {
            throw "Anna pidof output parse failed at $Phase"
        }
        $ProcessIds = @($ProcessTokens)
    }
    if (
        $ProcessResult.ExitCodeAvailable -and
        $ProcessResult.ExitCode -eq 1 -and
        $ProcessIds.Count -ne 0
    ) {
        throw "Anna pidof exit/output mismatch at $Phase"
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
        ProcessExitCodeAvailable = $ProcessResult.ExitCodeAvailable
        ProcessExitCode = $ProcessResult.ExitCode
    }
}

function Get-DeviceEpoch {
    $Result = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "date", "+%s") "anna-launch-epoch"
    $EpochText = ($Result.StandardOutput -join "").Trim()
    if ($Result.StandardError.Count -ne 0 -or $EpochText -notmatch "^\d+$") {
        throw "Anna epoch capture failed"
    }
    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
        throw "Anna epoch command failed"
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
    if ($Result.StandardError.Count -ne 0) {
        throw "Anna native log capture returned stderr at $Phase"
    }
    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) {
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
$PreLaunchSnapshot = $null
$StartedSnapshot = $null
$FinalSnapshot = $null
$LaunchEpoch = $null
$NewProcessId = $null
$Metrics = $null
$HeartbeatLine = $null

try {
    $DevicesResult = Invoke-Captured $Adb @("devices") "adb-devices"
    if ($DevicesResult.StandardError.Count -ne 0) {
        throw "adb devices returned stderr"
    }
    if ($DevicesResult.ExitCodeAvailable -and $DevicesResult.ExitCode -ne 0) {
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

    $PreLaunchSnapshot = Get-AnnaSnapshot "prelaunch"
    $PreLaunchIdentityValid = $PreLaunchSnapshot.Uid -eq $AnnaUid
    $PreLaunchIdentityValid = $PreLaunchIdentityValid -and $PreLaunchSnapshot.VersionName -eq "v11.16.15"
    $PreLaunchIdentityValid = $PreLaunchIdentityValid -and $PreLaunchSnapshot.VersionCode -eq 11016015
    $PreLaunchIdentityValid = $PreLaunchIdentityValid -and $PreLaunchSnapshot.FirstInstall -eq $AnnaFirstInstall
    $PreLaunchIdentityValid = $PreLaunchIdentityValid -and $PreLaunchSnapshot.DataDir -eq "/data/user/0/$PackageName"
    $PreLaunchIdentityValid = $PreLaunchIdentityValid -and -not $PreLaunchSnapshot.Running
    if (-not $PreLaunchIdentityValid) {
        throw "Anna exact installed/stopped prelaunch gate failed"
    }

    $LaunchEpoch = Get-DeviceEpoch
    $LaunchStarted = $true
    $LaunchResult = Invoke-Captured $Adb @("-s", $AnnaSerial, "shell", "am", "start", "-W", "-n", "$PackageName/.MainActivity") "anna-launch"
    $LaunchOkCount = @($LaunchResult.StandardOutput | Where-Object { $_.Trim() -eq "Status: ok" }).Count
    if ($LaunchResult.StandardError.Count -ne 0 -or $LaunchOkCount -ne 1) {
        throw "Anna launch semantic gate failed"
    }
    if ($LaunchResult.ExitCodeAvailable -and $LaunchResult.ExitCode -ne 0) {
        throw "Anna launch command failed"
    }

    $StartedSnapshot = Get-AnnaSnapshot "started"
    $StartedIdentityValid = $StartedSnapshot.ProcessIds.Count -eq 1
    $StartedIdentityValid = $StartedIdentityValid -and $StartedSnapshot.Uid -eq $AnnaUid
    $StartedIdentityValid = $StartedIdentityValid -and $StartedSnapshot.VersionCode -eq 11016015
    $StartedIdentityValid = $StartedIdentityValid -and $StartedSnapshot.FirstInstall -eq $AnnaFirstInstall
    if (-not $StartedIdentityValid) {
        throw "Anna new process/identity is missing"
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
        schema = 2
        purpose = "approved Anna-only r4.4 dual-session v11.16.15 launch-only runtime3"
        outcome = $Outcome
        failure = $Failure
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        userApproved = $true
        parentRuntimeState = $ParentRuntimeStatePath
        parentRuntimeStateSha256 = $ParentRuntimeStateHash
        parentRuntimeInstallStarted = $ParentRuntimeState.installStarted
        parentRuntimeLaunchStarted = $ParentRuntimeState.launchStarted
        parentEvidenceDirectory = $ParentEvidenceDir
        parentEvidenceSha256 = $ParentEvidenceActualHashes
        savedInstallEvidenceValidated = $SavedInstallValid
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
        installAlreadyCompletedByParent = $true
        installStarted = $InstallStarted
        launchStarted = $LaunchStarted
        launchEpoch = $LaunchEpoch
        annaPreLaunch = $PreLaunchSnapshot
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
    throw "r4.4 Anna launch-only runtime did not pass; see state: $StatePath"
}

Write-Host "R4.4 ANNA DUAL-SESSION RUNTIME3 PASS"
