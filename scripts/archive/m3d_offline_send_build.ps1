$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedApplicationCommit = "61e1580ff85aa1cfaed1f9e7a7522f1cd8e5d602"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$ExpectedBaselineSoSize = 7248576
$ExpectedBaselineSoHash = "E6C34E86F18D9F63B9A641E3FD9FAFD67D5F1B7101729B2CB3DF25163380095B"
$IconRelative = "design/branding/app-icon/source/apu-icon-original.png"
$IconPath = Join-Path $RepoRoot $IconRelative
$ExpectedIconSize = 1980451
$ExpectedIconHash = "F2638C88A3EAB243766B8F4755183C89A3E1FFCB72B45A0BBC5F3D398C83ACA9"
$IntegrationStatePath = Join-Path $env:TEMP "apu-r4.4-dual-integration-rust-build.json"
$ExpectedIntegrationStateHash = "A3E247756AC92DC77B836DDF19DC8B509B6A6DE9E6CB493AD22287A36DB5B3E4"
$BuildScript = Join-Path $RepoRoot "build-rust.ps1"
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$CargoToml = Join-Path $RepoRoot "rust-core\Cargo.toml"
$CoreSource = Join-Path $RepoRoot "rust-core\src\engine\core.rs"
$OfflineSource = Join-Path $RepoRoot "rust-core\src\network\offline_send.rs"
$BridgeSource = Join-Path $RepoRoot "android-app\app\src\main\java\com\vladimir\messenger\data\RustBridge.kt"
$RepositorySource = Join-Path $RepoRoot "android-app\app\src\main\java\com\vladimir\messenger\data\repository\ChatRepository.kt"
$DaoSource = Join-Path $RepoRoot "android-app\app\src\main\java\com\vladimir\messenger\data\local\dao\MessageDao.kt"
$StatePath = Join-Path $env:TEMP "apu-m3d-offline-send-rust-build.json"
$StdoutPath = Join-Path $env:TEMP "apu-m3d-offline-send-rust-build.stdout.log"
$StderrPath = Join-Path $env:TEMP "apu-m3d-offline-send-rust-build.stderr.log"
$LogPath = Join-Path $env:TEMP "apu-m3d-offline-send-rust-build.log"
$PowerShellExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$FeatureName = "mqtt-dual-broker"
$BuildTimeoutMilliseconds = 900000

$Artifacts = @($StatePath, $StdoutPath, $StderrPath, $LogPath)
$ExistingArtifacts = @($Artifacts | Where-Object { Test-Path -LiteralPath $_ })
if ($ExistingArtifacts.Count -gt 0) {
    throw "M3D OFFLINE SEND BUILD ALREADY ATTEMPTED - DO NOT REPEAT: $($ExistingArtifacts -join ', ')"
}
foreach ($RequiredPath in @(
    $BuildScript, $SoPath, $CargoToml, $CoreSource, $OfflineSource, $BridgeSource,
    $RepositorySource, $DaoSource, $IntegrationStatePath, $IconPath, $PowerShellExe
)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required M3(d) build input missing: $RequiredPath"
    }
}
if ($RepoRoot -notmatch '^[Cc]:\\') { throw "APU build must run from drive C: $RepoRoot" }
foreach ($EnvironmentPath in @($env:CARGO_HOME, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $env:ANDROID_NDK_HOME)) {
    if ($EnvironmentPath -and $EnvironmentPath -match '^[Dd]:\\') {
        throw "Drive D path is forbidden for APU build: $EnvironmentPath"
    }
}

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $CurrentBranch -ne $ExpectedBranch) {
    throw "Wrong branch: expected=$ExpectedBranch actual=$CurrentBranch"
}
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0) { throw "Cannot resolve Windows HEAD" }
& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) { throw "Application source differs from exact M3(d) commit" }

$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$AllowedStatus = @(" M $GeneratedSoRelative", "?? $IconRelative")
$UnexpectedBefore = @($StatusBefore | Where-Object { $_ -notin $AllowedStatus })
if (
    $UnexpectedBefore.Count -gt 0 -or
    $StatusBefore.Count -ne 2 -or
    $StatusBefore -notcontains " M $GeneratedSoRelative" -or
    $StatusBefore -notcontains "?? $IconRelative"
) { throw "Unexpected pre-build worktree changes: $($StatusBefore -join '; ')" }
$BaselineSo = Get-Item -LiteralPath $SoPath
$BaselineSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($BaselineSo.Length -ne $ExpectedBaselineSoSize -or $BaselineSoHash -ne $ExpectedBaselineSoHash) {
    throw "M3(d) baseline native mismatch: size=$($BaselineSo.Length) hash=$BaselineSoHash"
}
$IconFile = Get-Item -LiteralPath $IconPath
$IconHash = (Get-FileHash -LiteralPath $IconPath -Algorithm SHA256).Hash
if ($IconFile.Length -ne $ExpectedIconSize -or $IconHash -ne $ExpectedIconHash) {
    throw "APU icon original mismatch: size=$($IconFile.Length) hash=$IconHash"
}
$IntegrationStateHash = (Get-FileHash -LiteralPath $IntegrationStatePath -Algorithm SHA256).Hash
if ($IntegrationStateHash -ne $ExpectedIntegrationStateHash) {
    throw "r4.4 integration state hash mismatch: $IntegrationStateHash"
}
$IntegrationState = Get-Content -LiteralPath $IntegrationStatePath -Raw | ConvertFrom-Json
if (
    $IntegrationState.outcome -ne "PASS" -or
    $IntegrationState.runtimeIntegrationEnabled -ne $true -or
    $IntegrationState.wireFormatChanged -ne $false -or
    $IntegrationState.generatedSoSha256 -ne $ExpectedBaselineSoHash
) { throw "r4.4 integration baseline contract mismatch" }

$Tokens = $null
$ParseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $BuildScript, [ref]$Tokens, [ref]$ParseErrors
) | Out-Null
if (@($ParseErrors).Count -ne 0) { throw "build-rust.ps1 has parser errors" }

$CargoText = Get-Content -LiteralPath $CargoToml -Raw
$CoreText = Get-Content -LiteralPath $CoreSource -Raw
$OfflineText = Get-Content -LiteralPath $OfflineSource -Raw
$BridgeText = Get-Content -LiteralPath $BridgeSource -Raw
$RepositoryText = Get-Content -LiteralPath $RepositorySource -Raw
$DaoText = Get-Content -LiteralPath $DaoSource -Raw
$StaticValid = $CargoText -match '(?m)^mqtt-dual-broker\s*=\s*\[\]\s*$'
$StaticValid = $StaticValid -and $CoreText -match 'MQTT_OUTBOUND_COMMAND_CAPACITY:\s*usize\s*=\s*256'
$StaticValid = $StaticValid -and $CoreText -match 'outbound_rx\.try_recv\(\)'
$StaticValid = $StaticValid -and $CoreText -match 'transport\.send_mesh_relay\(&recipient, &envelope\)\.await'
$StaticValid = $StaticValid -and $CoreText -match 'status:\s*"queued_offline"\.into\(\)'
$StaticValid = $StaticValid -and $OfflineText -match 'MAX_MESH_RELAY_ENVELOPE_BYTES:\s*usize\s*=\s*64 \* 1024'
$StaticValid = $StaticValid -and $OfflineText -match 'wire::build_relay'
$StaticValid = $StaticValid -and $OfflineText -match 'ttl\.as_secs\(\),\s*\r?\n\s*0,'
$StaticValid = $StaticValid -and $BridgeText -notmatch 'val mqttOk = sendMessageMqtt'
$StaticValid = $StaticValid -and $RepositoryText -notmatch 'CloudflareRelay'
$StaticValid = $StaticValid -and $RepositoryText -match 'MessageStatus\.QUEUED_OFFLINE\.name'
$StaticValid = $StaticValid -and $RepositoryText -match 'MessageChannel\.STORE_FORWARD\.name'
$StaticValid = $StaticValid -and ([regex]::Matches($DaoText, "'QUEUED_OFFLINE'").Count -ge 2)
if (-not $StaticValid) { throw "M3(d) source static contract mismatch" }

function Wait-ExactProcess {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory = $true)][int]$TimeoutMilliseconds,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $Exited = $Process.WaitForExit($TimeoutMilliseconds)
    if (-not $Exited) { throw "$Label process $($Process.Id) timed out" }
    $Process.WaitForExit(); $Process.Refresh()
    $RawExitCode = $Process.ExitCode
    if ($null -eq $RawExitCode) { throw "$Label exit code unavailable" }
    return [pscustomobject]@{ Exited=$true; ExitCode=[int]$RawExitCode; ProcessId=$Process.Id }
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$BuildAttempted = $false
$BuildProcessId = $null
$BuildExitCode = $null
$FinishedReleaseCount = 0
$StdoutFeatureMarkerCount = 0
$CompilerWarningCount = 0
$CompilerErrorCount = 0
$GeneratedSoSize = $null
$GeneratedSoHash = $null
$StartedUtc = (Get-Date).ToUniversalTime()
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

try {
    $Command = @"
`$ErrorActionPreference = 'Continue'
Set-Location '$RepoRoot'
& '$BuildScript' -Features '$FeatureName'
exit `$LASTEXITCODE
"@
    $EncodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command))
    $BuildAttempted = $true
    $BuildProcess = Start-Process -FilePath $PowerShellExe `
        -ArgumentList @("-NoProfile", "-EncodedCommand", $EncodedCommand) `
        -WorkingDirectory $RepoRoot -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath -PassThru
    $BuildProcessId = $BuildProcess.Id
    $BuildWait = Wait-ExactProcess $BuildProcess $BuildTimeoutMilliseconds "M3(d) Android Rust build"
    $BuildExitCode = $BuildWait.ExitCode

    $StdoutLines = @(Get-Content -LiteralPath $StdoutPath)
    $StderrLines = @(Get-Content -LiteralPath $StderrPath)
    $BuildLines = @($StdoutLines) + @($StderrLines)
    @("=== STDOUT ===",$StdoutLines,"=== STDERR ===",$StderrLines) |
        Set-Content -LiteralPath $LogPath -Encoding UTF8
    $FinishedReleaseCount = @($BuildLines | Where-Object { $_ -match 'Finished.*release' }).Count
    $StdoutFeatureMarkerCount = @($StdoutLines | Where-Object { $_ -match 'Cargo features:\s*mqtt-dual-broker' }).Count
    $CompilerWarningCount = @($BuildLines | Where-Object { $_ -match '^\s*warning(?:\[|:)' }).Count
    $CompilerErrorCount = @($BuildLines | Where-Object { $_ -match '^\s*error(?:\[|:)' }).Count

    if ($BuildExitCode -ne 0) { throw "M3(d) build failed: exit=$BuildExitCode" }
    if ($FinishedReleaseCount -ne 1) { throw "Expected one Finished release marker: $FinishedReleaseCount" }
    if ($StdoutFeatureMarkerCount -ne 1) { throw "Expected one stdout feature marker: $StdoutFeatureMarkerCount" }
    if ($CompilerErrorCount -ne 0) { throw "Compiler errors found: $CompilerErrorCount" }

    $GeneratedSo = Get-Item -LiteralPath $SoPath
    $GeneratedSoSize = $GeneratedSo.Length
    $GeneratedSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
    if ($GeneratedSoSize -le 0 -or $GeneratedSoHash -eq $BaselineSoHash) {
        throw "Generated M3(d) native is empty or unchanged"
    }
    $FinalStatus = @(& git status --porcelain=v1 --untracked-files=all)
    $UnexpectedFinal = @($FinalStatus | Where-Object { $_ -notin $AllowedStatus })
    $IconHashAfter = (Get-FileHash -LiteralPath $IconPath -Algorithm SHA256).Hash
    if (
        $UnexpectedFinal.Count -gt 0 -or
        $FinalStatus.Count -ne 2 -or
        $FinalStatus -notcontains " M $GeneratedSoRelative" -or
        $FinalStatus -notcontains "?? $IconRelative" -or
        $IconHashAfter -ne $ExpectedIconHash
    ) { throw "Unexpected build outputs/icon change: $($FinalStatus -join '; ')" }
    $Outcome = "PASS"
}
catch { $Failure = $_.Exception.Message; throw }
finally {
    $Stopwatch.Stop()
    $State = [ordered]@{
        schema=1; purpose="M3(d) automatic offline mesh send-path Android Rust dual-feature build"
        outcome=$Outcome; failure=$Failure; startedUtc=$StartedUtc.ToString("o")
        completedUtc=(Get-Date).ToUniversalTime().ToString("o")
        durationSeconds=[math]::Round($Stopwatch.Elapsed.TotalSeconds,2)
        expectedApplicationCommit=$ExpectedApplicationCommit; windowsHead=$CurrentHead
        feature=$FeatureName; integrationState=$IntegrationStatePath
        integrationStateSha256=$IntegrationStateHash; buildAttempted=$BuildAttempted
        buildProcessId=$BuildProcessId; buildExitCode=$BuildExitCode
        finishedReleaseCount=$FinishedReleaseCount; stdoutFeatureMarkerCount=$StdoutFeatureMarkerCount
        compilerWarningCount=$CompilerWarningCount; compilerErrorCount=$CompilerErrorCount
        baselineSoSize=$BaselineSo.Length; baselineSoSha256=$BaselineSoHash
        generatedSoSize=$GeneratedSoSize; generatedSoSha256=$GeneratedSoHash
        stdoutPath=$StdoutPath; stderrPath=$StderrPath; logPath=$LogPath
        commandCapacity=256; commandDrainPerLoop=32; maxRelayEnvelopeBytes=65536
        iconOriginalPath=$IconPath; iconOriginalSize=$IconFile.Length; iconOriginalSha256=$IconHash
        iconOriginalTracked=$false; iconOriginalChanged=$false
        wireFormatChanged=$false; roomSchemaChanged=$false; transientMessageMqttRemoved=$true
        cloudflareContentFallbackRemoved=$true; apkBuilt=$false; adbUsed=$false
        phonesChanged=$false; publicTrafficSent=$false; automaticRetry=$false
    }
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash
    Write-Host ""; Write-Host "Outcome:                 $Outcome"
    Write-Host "State:                   $StatePath"; Write-Host "State SHA256:            $StateHash"
    Write-Host "Source commit:           $ExpectedApplicationCommit"; Write-Host "Feature:                 $FeatureName"
    Write-Host "Build process/exit:      $BuildProcessId / $BuildExitCode"
    Write-Host "Finished/feature marker: $FinishedReleaseCount / $StdoutFeatureMarkerCount"
    Write-Host "Warnings/errors:         $CompilerWarningCount / $CompilerErrorCount"
    Write-Host "Generated .so size:      $GeneratedSoSize"; Write-Host "Generated .so SHA256:    $GeneratedSoHash"
    Write-Host "Icon original SHA256:    $IconHash (unchanged/untracked)"
    Write-Host "Wire/Room schema changed: False / False"
    Write-Host "APK/ADB/phones/traffic:  False / False / False / False"
}
if ($Outcome -ne "PASS") { throw "M3(d) build did not pass; see state: $StatePath" }
Write-Host "M3D AUTOMATIC OFFLINE SEND ANDROID RUST BUILD PASS"
