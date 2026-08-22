$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedApplicationCommit = "0181b3496cde81477a01e49f8d9977d7c325a2ca"
$ExpectedNativeSize = 7248576
$ExpectedNativeHash = "E6C34E86F18D9F63B9A641E3FD9FAFD67D5F1B7101729B2CB3DF25163380095B"
$ExpectedCertHash = "F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7"
$VersionName = "v11.16.15"
$VersionCode = 11016015
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
$StatePath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-apk-build.json"
$LogPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-apk-build.log"
$StdoutPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-apk-build.stdout.log"
$StderrPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-apk-build.stderr.log"
$JavaStdoutPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-java.stdout.log"
$JavaStderrPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-java.stderr.log"
$AaptStdoutPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-aapt.stdout.log"
$AaptStderrPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-aapt.stderr.log"
$SignerStdoutPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-signer.stdout.log"
$SignerStderrPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-signer.stderr.log"
$StableApkPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15.apk"
$IntegrationStatePath = Join-Path $env:TEMP "apu-r4.4-dual-integration-rust-build.json"
$ExpectedIntegrationStateHash = "A3E247756AC92DC77B836DDF19DC8B509B6A6DE9E6CB493AD22287A36DB5B3E4"

$Artifacts = @(
    $StatePath, $LogPath, $StdoutPath, $StderrPath, $JavaStdoutPath, $JavaStderrPath,
    $AaptStdoutPath, $AaptStderrPath, $SignerStdoutPath, $SignerStderrPath, $StableApkPath
)
$Existing = @($Artifacts | Where-Object { Test-Path -LiteralPath $_ })
if ($Existing.Count -gt 0) {
    throw "R4.4 V11.16.15 APK BUILD ALREADY ATTEMPTED - DO NOT REPEAT: $($Existing -join ', ')"
}
foreach ($RequiredPath in @($SoPath, $JavaExe, $SdkPath, $Gradlew, $PowerShellExe, $IntegrationStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required build input missing: $RequiredPath"
    }
}

if ($RepoRoot -notmatch '^[Cc]:\\' -or $JdkPath -notmatch '^[Cc]:\\' -or $SdkPath -notmatch '^[Cc]:\\') {
    throw "APU/JDK/Android SDK must all run from drive C"
}

$IntegrationStateHash = (Get-FileHash -LiteralPath $IntegrationStatePath -Algorithm SHA256).Hash
if ($IntegrationStateHash -ne $ExpectedIntegrationStateHash) {
    throw "Dual integration build state hash mismatch: $IntegrationStateHash"
}
$IntegrationState = Get-Content -LiteralPath $IntegrationStatePath -Raw | ConvertFrom-Json
$IntegrationStateValid = $IntegrationState.outcome -eq "PASS"
$IntegrationStateValid = $IntegrationStateValid -and $IntegrationState.expectedSourceCommit -eq $ExpectedApplicationCommit
$IntegrationStateValid = $IntegrationStateValid -and $IntegrationState.feature -eq "mqtt-dual-broker"
$IntegrationStateValid = $IntegrationStateValid -and $IntegrationState.generatedSoSha256 -eq $ExpectedNativeHash
$IntegrationStateValid = $IntegrationStateValid -and [int64]$IntegrationState.generatedSoSize -eq $ExpectedNativeSize
$IntegrationStateValid = $IntegrationStateValid -and $IntegrationState.runtimeIntegrationEnabled -eq $true
$IntegrationStateValid = $IntegrationStateValid -and $IntegrationState.wireFormatChanged -eq $false
$IntegrationStateValid = $IntegrationStateValid -and $IntegrationState.phonesChanged -eq $false
$IntegrationStateValid = $IntegrationStateValid -and $IntegrationState.publicTrafficSent -eq $false
$IntegrationStateValid = $IntegrationStateValid -and $IntegrationState.automaticRetry -eq $false
if (-not $IntegrationStateValid) {
    throw "Dual integration state does not prove the exact APK input"
}

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $CurrentBranch -ne $ExpectedBranch) {
    throw "Wrong branch: expected=$ExpectedBranch actual=$CurrentBranch"
}
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($CurrentHead)) {
    throw "Cannot resolve Windows HEAD"
}
& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) {
    throw "Application/build source differs from expected r4.4 integration commit"
}

$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedBefore = @($StatusBefore | Where-Object { $_ -ne " M $GeneratedSoRelative" })
if ($UnexpectedBefore.Count -gt 0) {
    throw "Unexpected pre-build worktree changes: $($UnexpectedBefore -join '; ')"
}

$NativeFile = Get-Item -LiteralPath $SoPath
$NativeHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($NativeFile.Length -ne $ExpectedNativeSize -or $NativeHash -ne $ExpectedNativeHash) {
    throw "Dual integration native identity mismatch: size=$($NativeFile.Length), hash=$NativeHash"
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
    $UnexpectedFinal = @($FinalStatus | Where-Object { $_ -ne " M $GeneratedSoRelative" })
    if ($UnexpectedFinal.Count -gt 0) {
        throw "Unexpected APK build outputs: $($UnexpectedFinal -join '; ')"
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
        purpose = "r4.4 dual-session v11.16.15 APK artifact build"
        outcome = $Outcome
        failure = $Failure
        startedUtc = $StartedUtc.ToString("o")
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        durationSeconds = [math]::Round($Stopwatch.Elapsed.TotalSeconds, 2)
        applicationCommit = $ExpectedApplicationCommit
        windowsHead = $CurrentHead
        integrationBuildState = $IntegrationStatePath
        integrationBuildStateSha256 = $IntegrationStateHash
        feature = "mqtt-dual-broker"
        runtimeIntegrationEnabled = $true
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
    throw "r4.4 v11.16.15 APK build did not pass; see state: $StatePath"
}

Write-Host "R4.4 V11.16.15 APK ARTIFACT BUILD PASS"
