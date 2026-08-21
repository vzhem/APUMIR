$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedApplicationCommit = "61e1580ff85aa1cfaed1f9e7a7522f1cd8e5d602"
$ExpectedWindowsHead = "8cea566e50f439810e29fb1dc4ac14dc69b5fbc6"
$ExpectedNativeSize = 7263416
$ExpectedNativeHash = "27B9D4DC87CA7046D9F862F9ED153FDDD48C26E4053B620FE46986D25D1FD26C"
$ExpectedCertHash = "F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7"
$VersionName = "v11.16.16"
$VersionCode = 11016016
$ExpectedPackageName = "com.vladimir.messenger"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$JdkPath = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
$JavaExe = Join-Path $JdkPath "bin\java.exe"
$SdkPath = "$env:LOCALAPPDATA\Android\Sdk"
$AndroidAppPath = Join-Path $RepoRoot "android-app"
$Gradlew = Join-Path $AndroidAppPath "gradlew.bat"
$GradleApkPath = Join-Path $AndroidAppPath "app\build\outputs\apk\release\app-release.apk"
$PowerShellExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$StatePath = Join-Path $env:TEMP "apu-m3d-v11.16.16-apk-build.json"
$LogPath = Join-Path $env:TEMP "apu-m3d-v11.16.16-apk-build.log"
$StdoutPath = Join-Path $env:TEMP "apu-m3d-v11.16.16-apk-build.stdout.log"
$StderrPath = Join-Path $env:TEMP "apu-m3d-v11.16.16-apk-build.stderr.log"
$JavaStdoutPath = Join-Path $env:TEMP "apu-m3d-v11.16.16-java.stdout.log"
$JavaStderrPath = Join-Path $env:TEMP "apu-m3d-v11.16.16-java.stderr.log"
$AaptStdoutPath = Join-Path $env:TEMP "apu-m3d-v11.16.16-aapt.stdout.log"
$AaptStderrPath = Join-Path $env:TEMP "apu-m3d-v11.16.16-aapt.stderr.log"
$SignerStdoutPath = Join-Path $env:TEMP "apu-m3d-v11.16.16-signer.stdout.log"
$SignerStderrPath = Join-Path $env:TEMP "apu-m3d-v11.16.16-signer.stderr.log"
$StableApkPath = Join-Path $env:TEMP "apu-m3d-v11.16.16.apk"
$RustBuildStatePath = Join-Path $env:TEMP "apu-m3d-rust-direct-build.json"
$ExpectedRustBuildStateHash = "7589A0349386640443039B2C09EB311F269C342473715CB22E23E5314A1716A1"
$IconRelative = "design/branding/app-icon/source/apu-icon-original.png"
$IconPath = Join-Path $RepoRoot $IconRelative
$ExpectedIconSize = 1980451
$ExpectedIconHash = "F2638C88A3EAB243766B8F4755183C89A3E1FFCB72B45A0BBC5F3D398C83ACA9"

$Artifacts = @(
    $StatePath, $LogPath, $StdoutPath, $StderrPath, $JavaStdoutPath, $JavaStderrPath,
    $AaptStdoutPath, $AaptStderrPath, $SignerStdoutPath, $SignerStderrPath, $StableApkPath
)
$Existing = @($Artifacts | Where-Object { Test-Path -LiteralPath $_ })
if ($Existing.Count -gt 0) {
    throw "M3(d) V11.16.16 APK BUILD ALREADY ATTEMPTED - DO NOT REPEAT: $($Existing -join ', ')"
}
foreach ($RequiredPath in @($SoPath, $IconPath, $JavaExe, $SdkPath, $Gradlew, $PowerShellExe, $RustBuildStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required build input missing: $RequiredPath"
    }
}

if ($RepoRoot -notmatch '^[Cc]:\\' -or $JdkPath -notmatch '^[Cc]:\\' -or $SdkPath -notmatch '^[Cc]:\\') {
    throw "APU/JDK/Android SDK must all run from drive C"
}

$RustBuildStateHash = (Get-FileHash -LiteralPath $RustBuildStatePath -Algorithm SHA256).Hash
if ($RustBuildStateHash -ne $ExpectedRustBuildStateHash) {
    throw "M3(d) Rust build state hash mismatch: $RustBuildStateHash"
}
$RustBuildState = Get-Content -LiteralPath $RustBuildStatePath -Raw | ConvertFrom-Json
$RustBuildStateValid = $RustBuildState.outcome -eq "PASS"
$RustBuildStateValid = $RustBuildStateValid -and $RustBuildState.windowsHead -eq $ExpectedWindowsHead
$RustBuildStateValid = $RustBuildStateValid -and $RustBuildState.patchSha256 -eq "BCB546D61C01852790FB0EAF7B2BD1BD85A20B3D6DCB5570F1B1CD42DE45F7F9"
$RustBuildStateValid = $RustBuildStateValid -and [int]$RustBuildState.finishedReleaseCount -eq 1
$RustBuildStateValid = $RustBuildStateValid -and [int]$RustBuildState.featureMarkerCount -eq 1
$RustBuildStateValid = $RustBuildStateValid -and [int]$RustBuildState.compilerErrorCount -eq 0
$RustBuildStateValid = $RustBuildStateValid -and $RustBuildState.generatedSoSha256 -eq $ExpectedNativeHash
$RustBuildStateValid = $RustBuildStateValid -and [int64]$RustBuildState.generatedSoSize -eq $ExpectedNativeSize
$RustBuildStateValid = $RustBuildStateValid -and $RustBuildState.iconSha256 -eq $ExpectedIconHash
$RustBuildStateValid = $RustBuildStateValid -and $RustBuildState.gitHubUsed -eq $false
$RustBuildStateValid = $RustBuildStateValid -and $RustBuildState.adbUsed -eq $false
$RustBuildStateValid = $RustBuildStateValid -and $RustBuildState.phonesChanged -eq $false
$RustBuildStateValid = $RustBuildStateValid -and $RustBuildState.automaticRetry -eq $false
if (-not $RustBuildStateValid) {
    throw "M3(d) Rust build state does not prove the exact APK input"
}

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $CurrentBranch -ne $ExpectedBranch) {
    throw "Wrong branch: expected=$ExpectedBranch actual=$CurrentBranch"
}
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $CurrentHead -ne $ExpectedWindowsHead) {
    throw "Unexpected Windows HEAD: $CurrentHead"
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
        throw "M3(d) application source mismatch: $RelativePath / $WorkingBlob"
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
    "?? scripts/m3d_kotlin_apk_build.ps1"
)
$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedBefore = @($StatusBefore | Where-Object { $_ -notin $ExpectedStatus })
if ($UnexpectedBefore.Count -gt 0 -or $StatusBefore.Count -ne $ExpectedStatus.Count) {
    throw "Unexpected pre-build worktree: $($StatusBefore -join '; ')"
}

$NativeFile = Get-Item -LiteralPath $SoPath
$NativeHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($NativeFile.Length -ne $ExpectedNativeSize -or $NativeHash -ne $ExpectedNativeHash) {
    throw "M3(d) native identity mismatch: size=$($NativeFile.Length), hash=$NativeHash"
}
$IconFile = Get-Item -LiteralPath $IconPath
$IconHash = (Get-FileHash -LiteralPath $IconPath -Algorithm SHA256).Hash
if ($IconFile.Length -ne $ExpectedIconSize -or $IconHash -ne $ExpectedIconHash) {
    throw "APU icon identity mismatch before APK build"
}

function Get-HsErrManifest {
    $Items = @(
        Get-ChildItem -LiteralPath $RepoRoot -Filter "hs_err_pid*.log" -File -Recurse |
            Sort-Object FullName
    )
    return @(
        $Items | ForEach-Object {
            $HsErrHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            $ManifestLine = "{0}|{1}|{2}" -f $_.FullName, $_.Length, $HsErrHash
            $ManifestLine
        }
    )
}

function Start-CapturedPowerShell {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptText,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$StandardOutputPath,
        [Parameter(Mandatory = $true)][string]$StandardErrorPath
    )

    $Encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($ScriptText))
    return Start-Process `
        -FilePath $PowerShellExe `
        -ArgumentList @("-NoProfile", "-EncodedCommand", $Encoded) `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $StandardOutputPath `
        -RedirectStandardError $StandardErrorPath `
        -PassThru
}

function Wait-ExactProcess {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory = $true)][int]$TimeoutMilliseconds,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $Exited = $Process.WaitForExit($TimeoutMilliseconds)
    if (-not $Exited) {
        throw "$Label exact child PID $($Process.Id) timed out after $TimeoutMilliseconds ms"
    }
    $Process.WaitForExit()
    $Process.Refresh()
    return [pscustomobject]@{
        Exited = $true
        ExitCode = [int]$Process.ExitCode
        ProcessId = $Process.Id
    }
}

$OriginalEnvironment = [ordered]@{
    JAVA_HOME = $env:JAVA_HOME
    ANDROID_HOME = $env:ANDROID_HOME
    ANDROID_SDK_ROOT = $env:ANDROID_SDK_ROOT
    GRADLE_USER_HOME = $env:GRADLE_USER_HOME
    GITHUB_REF_NAME = $env:GITHUB_REF_NAME
    Path = $env:Path
}

$StartedUtc = (Get-Date).ToUniversalTime()
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$Outcome = "FAIL_DO_NOT_RETRY_AUTOMATICALLY"
$Failure = $null
$BuildAttempted = $false
$BuildProcessId = $null
$BuildExitCode = $null
$BuildSuccessfulCount = 0
$JavaExitCode = $null
$AaptExitCode = $null
$SignerExitCode = $null
$ActualPackageName = $null
$ActualVersionName = $null
$ActualVersionCode = $null
$ApkSize = $null
$ApkHash = $null
$SignerV2 = $false
$SignerCertHash = $null
$EmbeddedNativeSize = $null
$EmbeddedNativeHash = $null
$EnvironmentRestored = $false
$HsErrBefore = @(Get-HsErrManifest)
$HsErrAfter = $null

try {
    $env:JAVA_HOME = $JdkPath
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_SDK_ROOT = $SdkPath
    $env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
    $env:GITHUB_REF_NAME = $VersionName
    $env:Path = "$JdkPath\bin;$env:Path"

    $JavaProcess = Start-Process `
        -FilePath $JavaExe `
        -ArgumentList @("-version") `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $JavaStdoutPath `
        -RedirectStandardError $JavaStderrPath `
        -PassThru
    $JavaWait = Wait-ExactProcess -Process $JavaProcess -TimeoutMilliseconds 30000 -Label "Java version"
    $JavaExitCode = $JavaWait.ExitCode
    $JavaText = (@(Get-Content $JavaStdoutPath) + @(Get-Content $JavaStderrPath)) -join "`n"
    if ($JavaExitCode -ne 0 -or $JavaText -notmatch "17\.0\.17") {
        throw "Approved Java check failed: exit=$JavaExitCode"
    }

    if (Test-Path -LiteralPath $GradleApkPath) {
        Remove-Item -LiteralPath $GradleApkPath -Force
    }

    $BuildCommand = @"
`$ErrorActionPreference = 'Continue'
Set-Location '$AndroidAppPath'
& '$Gradlew' ':app:assembleRelease' '--no-daemon'
exit `$LASTEXITCODE
"@
    $BuildAttempted = $true
    $BuildProcess = Start-CapturedPowerShell `
        -ScriptText $BuildCommand `
        -WorkingDirectory $AndroidAppPath `
        -StandardOutputPath $StdoutPath `
        -StandardErrorPath $StderrPath
    $BuildProcessId = $BuildProcess.Id
    $BuildWait = Wait-ExactProcess -Process $BuildProcess -TimeoutMilliseconds 900000 -Label "Gradle build"
    $BuildExitCode = $BuildWait.ExitCode

    $StdoutLines = @(Get-Content -LiteralPath $StdoutPath)
    $StderrLines = @(Get-Content -LiteralPath $StderrPath)
    $BuildLines = @($StdoutLines) + @($StderrLines)
    @("=== STDOUT ===", $StdoutLines, "=== STDERR ===", $StderrLines) |
        Set-Content -LiteralPath $LogPath -Encoding UTF8
    $BuildSuccessfulCount = @($BuildLines | Where-Object { $_ -match "BUILD SUCCESSFUL" }).Count

    if ($BuildExitCode -ne 0 -or $BuildSuccessfulCount -ne 1) {
        throw "Gradle build gate failed: exit=$BuildExitCode, successMarkers=$BuildSuccessfulCount"
    }
    if (-not (Test-Path -LiteralPath $GradleApkPath)) {
        throw "Gradle APK missing"
    }

    Copy-Item -LiteralPath $GradleApkPath -Destination $StableApkPath
    $StableApk = Get-Item -LiteralPath $StableApkPath
    $ApkSize = $StableApk.Length
    $ApkHash = (Get-FileHash -LiteralPath $StableApkPath -Algorithm SHA256).Hash

    $BuildTools = @(
        Get-ChildItem -LiteralPath (Join-Path $SdkPath "build-tools") -Directory |
            Sort-Object Name -Descending |
            Where-Object {
                $HasAapt = Test-Path (Join-Path $_.FullName "aapt.exe")
                $HasApkSigner = Test-Path (Join-Path $_.FullName "apksigner.bat")
                $HasAapt -and $HasApkSigner
            }
    )[0]
    if ($null -eq $BuildTools) {
        throw "Android build-tools not found"
    }
    $Aapt = Join-Path $BuildTools.FullName "aapt.exe"
    $ApkSigner = Join-Path $BuildTools.FullName "apksigner.bat"

    $AaptProcess = Start-Process `
        -FilePath $Aapt `
        -ArgumentList @("dump", "badging", $StableApkPath) `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $AaptStdoutPath `
        -RedirectStandardError $AaptStderrPath `
        -PassThru
    $AaptWait = Wait-ExactProcess -Process $AaptProcess -TimeoutMilliseconds 30000 -Label "AAPT"
    $AaptExitCode = $AaptWait.ExitCode
    $Badging = (@(Get-Content $AaptStdoutPath) + @(Get-Content $AaptStderrPath)) -join "`n"
    $VersionMatch = [regex]::Match($Badging, "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
    if ($AaptExitCode -ne 0 -or -not $VersionMatch.Success) {
        throw "AAPT version inspection failed"
    }
    $ActualPackageName = $VersionMatch.Groups[1].Value
    $ActualVersionCode = [int64]$VersionMatch.Groups[2].Value
    $ActualVersionName = $VersionMatch.Groups[3].Value
    if ($ActualPackageName -ne $ExpectedPackageName -or $ActualVersionName -ne $VersionName -or $ActualVersionCode -ne $VersionCode) {
        throw "APK identity mismatch: $ActualPackageName $ActualVersionName/$ActualVersionCode"
    }

    $SignerCommand = @"
`$ErrorActionPreference = 'Continue'
& '$ApkSigner' 'verify' '--verbose' '--print-certs' '$StableApkPath'
exit `$LASTEXITCODE
"@
    $SignerProcess = Start-CapturedPowerShell `
        -ScriptText $SignerCommand `
        -WorkingDirectory $RepoRoot `
        -StandardOutputPath $SignerStdoutPath `
        -StandardErrorPath $SignerStderrPath
    $SignerWait = Wait-ExactProcess -Process $SignerProcess -TimeoutMilliseconds 60000 -Label "APK signer"
    $SignerExitCode = $SignerWait.ExitCode
    $SignerLines = @(Get-Content $SignerStdoutPath) + @(Get-Content $SignerStderrPath)
    $SignerText = $SignerLines -join "`n"
    $SignerV2 = $SignerText -match "Verified using v2 scheme .*:\s*true"
    if ($SignerExitCode -ne 0 -or -not $SignerV2) {
        throw "APK V2 signer gate failed"
    }

    $DigestCandidates = [System.Collections.Generic.List[string]]::new()
    foreach ($Line in $SignerLines) {
        if ($Line -match "(?i)certificate" -and $Line -match "(?i)sha-?256" -and $Line -match "(?i)digest|fingerprint") {
            $ValueMatch = [regex]::Match($Line, "(?i)(?:digest|fingerprint)\s*[:=]\s*(.+)$")
            if ($ValueMatch.Success) {
                $Normalized = [regex]::Replace($ValueMatch.Groups[1].Value, "[^0-9A-Fa-f]", "").ToUpperInvariant()
                if ($Normalized.Length -eq 64) {
                    $DigestCandidates.Add($Normalized)
                }
            }
        }
    }
    $UniqueDigests = @($DigestCandidates | Sort-Object -Unique)
    if ($UniqueDigests.Count -ne 1) {
        throw "Expected one signer certificate digest"
    }
    $SignerCertHash = $UniqueDigests[0]
    if ($SignerCertHash -ne $ExpectedCertHash) {
        throw "Signer certificate mismatch: $SignerCertHash"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $Zip = [IO.Compression.ZipFile]::OpenRead($StableApkPath)
    try {
        $Entry = $Zip.GetEntry("lib/arm64-v8a/libp2p_core.so")
        if ($null -eq $Entry) {
            throw "Embedded feature native missing"
        }
        $EmbeddedNativeSize = $Entry.Length
        $Stream = $Entry.Open()
        try {
            $Sha = [Security.Cryptography.SHA256]::Create()
            try {
                $HashBytes = $Sha.ComputeHash($Stream)
            } finally {
                $Sha.Dispose()
            }
        } finally {
            $Stream.Dispose()
        }
        $EmbeddedNativeHash = ([BitConverter]::ToString($HashBytes)).Replace("-", "")
    } finally {
        $Zip.Dispose()
    }
    if ($EmbeddedNativeSize -ne $ExpectedNativeSize -or $EmbeddedNativeHash -ne $ExpectedNativeHash) {
        throw "Embedded native mismatch: size=$EmbeddedNativeSize hash=$EmbeddedNativeHash"
    }

    $FinalStatus = @(& git status --porcelain=v1 --untracked-files=all)
    $UnexpectedFinal = @($FinalStatus | Where-Object { $_ -notin $ExpectedStatus })
    if ($UnexpectedFinal.Count -gt 0 -or $FinalStatus.Count -ne $ExpectedStatus.Count) {
        throw "Unexpected APK build outputs: $($FinalStatus -join '; ')"
    }
    $FinalIconHash = (Get-FileHash -LiteralPath $IconPath -Algorithm SHA256).Hash
    if ($FinalIconHash -ne $ExpectedIconHash) {
        throw "APU icon changed during APK build"
    }

    $HsErrAfter = @(Get-HsErrManifest)
    $HsErrBeforeText = $HsErrBefore -join "`n"
    $HsErrAfterText = $HsErrAfter -join "`n"
    if ($HsErrAfterText -ne $HsErrBeforeText) {
        throw "New or changed JVM crash report detected"
    }

    $Outcome = "PASS"
}
catch {
    $Failure = $_.Exception.Message
    throw
}
finally {
    $Stopwatch.Stop()
    $env:JAVA_HOME = $OriginalEnvironment.JAVA_HOME
    $env:ANDROID_HOME = $OriginalEnvironment.ANDROID_HOME
    $env:ANDROID_SDK_ROOT = $OriginalEnvironment.ANDROID_SDK_ROOT
    $env:GRADLE_USER_HOME = $OriginalEnvironment.GRADLE_USER_HOME
    $env:GITHUB_REF_NAME = $OriginalEnvironment.GITHUB_REF_NAME
    $env:Path = $OriginalEnvironment.Path
    $EnvironmentRestored = $env:JAVA_HOME -eq $OriginalEnvironment.JAVA_HOME
    $EnvironmentRestored = $EnvironmentRestored -and $env:ANDROID_HOME -eq $OriginalEnvironment.ANDROID_HOME
    $EnvironmentRestored = $EnvironmentRestored -and $env:ANDROID_SDK_ROOT -eq $OriginalEnvironment.ANDROID_SDK_ROOT
    $EnvironmentRestored = $EnvironmentRestored -and $env:GRADLE_USER_HOME -eq $OriginalEnvironment.GRADLE_USER_HOME
    $EnvironmentRestored = $EnvironmentRestored -and $env:GITHUB_REF_NAME -eq $OriginalEnvironment.GITHUB_REF_NAME
    $EnvironmentRestored = $EnvironmentRestored -and $env:Path -eq $OriginalEnvironment.Path

    if ($null -eq $HsErrAfter) {
        $HsErrAfter = @(Get-HsErrManifest)
    }
    $HsErrSetUnchanged = ($HsErrBefore -join "`n") -eq ($HsErrAfter -join "`n")
    if ($Outcome -eq "PASS" -and -not $EnvironmentRestored) {
        $Outcome = "FAIL_DO_NOT_RETRY_AUTOMATICALLY"
        $Failure = "Process-local build environment was not restored"
    }

    $State = [ordered]@{
        schema = 1
        purpose = "M3(d) automatic offline send v11.16.16 APK artifact build"
        outcome = $Outcome
        failure = $Failure
        startedUtc = $StartedUtc.ToString("o")
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        durationSeconds = [math]::Round($Stopwatch.Elapsed.TotalSeconds, 2)
        applicationCommit = $ExpectedApplicationCommit
        windowsHead = $CurrentHead
        rustBuildState = $RustBuildStatePath
        rustBuildStateSha256 = $RustBuildStateHash
        feature = "mqtt-dual-broker"
        automaticOfflineSendSource = $true
        kotlinOverlayApplied = $true
        wireFormatChanged = $false
        javaExitCode = $JavaExitCode
        buildAttempted = $BuildAttempted
        buildProcessId = $BuildProcessId
        buildExitCode = $BuildExitCode
        buildSuccessfulCount = $BuildSuccessfulCount
        aaptExitCode = $AaptExitCode
        signerExitCode = $SignerExitCode
        packageName = $ActualPackageName
        versionName = $ActualVersionName
        versionCode = $ActualVersionCode
        apkPath = $StableApkPath
        apkSize = $ApkSize
        apkSha256 = $ApkHash
        signerV2 = $SignerV2
        signerCertificateSha256 = $SignerCertHash
        embeddedNativeSize = $EmbeddedNativeSize
        embeddedNativeSha256 = $EmbeddedNativeHash
        expectedNativeSha256 = $ExpectedNativeHash
        hsErrBeforeCount = $HsErrBefore.Count
        hsErrAfterCount = $HsErrAfter.Count
        hsErrSetUnchanged = $HsErrSetUnchanged
        apkBuilt = (Test-Path -LiteralPath $StableApkPath)
        phonesChanged = $false
        adbCommandsUsed = $false
        installed = $false
        launched = $false
        publicTrafficSent = $false
        environmentRestored = $EnvironmentRestored
        automaticRetry = $false
    }
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:                $Outcome"
    Write-Host "State:                  $StatePath"
    Write-Host "State SHA256:           $StateHash"
    Write-Host "Build PID/exit:         $BuildProcessId / $BuildExitCode"
    Write-Host "BUILD SUCCESSFUL count: $BuildSuccessfulCount"
    Write-Host "Version:                $ActualVersionName / $ActualVersionCode"
    Write-Host "APK size:               $ApkSize"
    Write-Host "APK SHA256:             $ApkHash"
    Write-Host "Signer V2/cert:         $SignerV2 / $SignerCertHash"
    Write-Host "Embedded native:        $EmbeddedNativeSize / $EmbeddedNativeHash"
    Write-Host "Phones changed:         False"
    Write-Host "Duration seconds:       $([math]::Round($Stopwatch.Elapsed.TotalSeconds, 2))"
}

if ($Outcome -ne "PASS") {
    throw "M3(d) v11.16.16 APK build did not pass; see state: $StatePath"
}

Write-Host "M3D AUTOMATIC OFFLINE SEND V11.16.16 APK BUILD PASS"
