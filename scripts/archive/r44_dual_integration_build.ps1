$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedSourceCommit = "0181b3496cde81477a01e49f8d9977d7c325a2ca"
$ExpectedPreviousSoHash = "C8665B5DD723D6853A7D2AA0D88B9E3775B6D3AD6AD2A01E1BC8AC13807E4FCC"
$ExpectedPreviousSoSize = 7180888
$ExpectedPolicyRecoveryHash = "55590C7AB6E431EE301A933267E742DDCFA13277A421318D9B513C971A4CAF03"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$BuildScript = Join-Path $RepoRoot "build-rust.ps1"
$TransportSource = Join-Path $RepoRoot "rust-core\src\network\mqtt_transport.rs"
$OverflowSource = Join-Path $RepoRoot "rust-core\src\network\mqtt_overflow.rs"
$CoreSource = Join-Path $RepoRoot "rust-core\src\engine\core.rs"
$CargoToml = Join-Path $RepoRoot "rust-core\Cargo.toml"
$PolicyRecoveryPath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build-recovery.json"
$StatePath = Join-Path $env:TEMP "apu-r4.4-dual-integration-rust-build.json"
$StdoutPath = Join-Path $env:TEMP "apu-r4.4-dual-integration-rust-build.stdout.log"
$StderrPath = Join-Path $env:TEMP "apu-r4.4-dual-integration-rust-build.stderr.log"
$LogPath = Join-Path $env:TEMP "apu-r4.4-dual-integration-rust-build.log"
$PowerShellExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$FeatureName = "mqtt-dual-broker"
$BuildTimeoutMilliseconds = 900000

$Artifacts = @($StatePath, $StdoutPath, $StderrPath, $LogPath)
$ExistingArtifacts = @($Artifacts | Where-Object { Test-Path -LiteralPath $_ })
if ($ExistingArtifacts.Count -gt 0) {
    throw "R4.4 DUAL INTEGRATION BUILD ALREADY ATTEMPTED - DO NOT REPEAT: $($ExistingArtifacts -join ', ')"
}

foreach ($RequiredPath in @(
    $SoPath,
    $BuildScript,
    $TransportSource,
    $OverflowSource,
    $CoreSource,
    $CargoToml,
    $PolicyRecoveryPath,
    $PowerShellExe
)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required build input missing: $RequiredPath"
    }
}

if ($RepoRoot -notmatch '^[Cc]:\\') {
    throw "APU build must run from drive C: $RepoRoot"
}
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
if ($LASTEXITCODE -ne 0) {
    throw "Cannot resolve Windows HEAD"
}
& git diff --quiet $ExpectedSourceCommit $CurrentHead -- rust-core build-rust.ps1
if ($LASTEXITCODE -ne 0) {
    throw "Application/build source differs from exact r4.4 dual integration commit"
}

$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedBefore = @(
    $StatusBefore | Where-Object { $_ -ne " M $GeneratedSoRelative" }
)
if ($UnexpectedBefore.Count -gt 0) {
    throw "Unexpected pre-build worktree changes: $($UnexpectedBefore -join '; ')"
}

$PolicyRecoveryHash = (Get-FileHash -LiteralPath $PolicyRecoveryPath -Algorithm SHA256).Hash
if ($PolicyRecoveryHash -ne $ExpectedPolicyRecoveryHash) {
    throw "Policy build recovery state hash mismatch: $PolicyRecoveryHash"
}
$PolicyRecovery = Get-Content -LiteralPath $PolicyRecoveryPath -Raw | ConvertFrom-Json
$PolicyRecoveryValid = $PolicyRecovery.outcome -eq "PASS_FROM_COMPLETED_BUILD_EVIDENCE"
$PolicyRecoveryValid = $PolicyRecoveryValid -and $PolicyRecovery.generatedSoSha256 -eq $ExpectedPreviousSoHash
$PolicyRecoveryValid = $PolicyRecoveryValid -and [int64]$PolicyRecovery.generatedSoSize -eq $ExpectedPreviousSoSize
$PolicyRecoveryValid = $PolicyRecoveryValid -and $PolicyRecovery.runtimeIntegrationEnabled -eq $false
$PolicyRecoveryValid = $PolicyRecoveryValid -and $PolicyRecovery.buildRepeated -eq $false
$PolicyRecoveryValid = $PolicyRecoveryValid -and $PolicyRecovery.phonesChanged -eq $false
$PolicyRecoveryValid = $PolicyRecoveryValid -and $PolicyRecovery.publicTrafficSent -eq $false
if (-not $PolicyRecoveryValid) {
    throw "Policy recovery does not prove the exact pre-integration baseline"
}

$PreviousSoFile = Get-Item -LiteralPath $SoPath
$PreviousSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($PreviousSoFile.Length -ne $ExpectedPreviousSoSize -or $PreviousSoHash -ne $ExpectedPreviousSoHash) {
    throw "Previous policy .so identity mismatch: size=$($PreviousSoFile.Length) hash=$PreviousSoHash"
}

$Tokens = $null
$ParseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $BuildScript,
    [ref]$Tokens,
    [ref]$ParseErrors
) | Out-Null
if (@($ParseErrors).Count -ne 0) {
    throw "build-rust.ps1 has parser errors"
}

$CargoText = Get-Content -LiteralPath $CargoToml -Raw
$TransportText = Get-Content -LiteralPath $TransportSource -Raw
$OverflowText = Get-Content -LiteralPath $OverflowSource -Raw
$CoreText = Get-Content -LiteralPath $CoreSource -Raw
$StaticContractValid = $CargoText -match '(?m)^mqtt-dual-broker\s*=\s*\[\]\s*$'
$StaticContractValid = $StaticContractValid -and $TransportText -match 'SECONDARY_BROKER_HOST:\s*&str\s*=\s*"broker\.emqx\.io"'
$StaticContractValid = $StaticContractValid -and $TransportText -match 'MQTT CROSS-BROKER DUPLICATE DROPPED:'
$StaticContractValid = $StaticContractValid -and $TransportText -match 'MQTT FANOUT QUEUED:'
$StaticContractValid = $StaticContractValid -and $TransportText -match 'SecondaryConnectionAcknowledged'
$StaticContractValid = $StaticContractValid -and $TransportText -match 'RetainedBrokerTargetLedger'
$StaticContractValid = $StaticContractValid -and $TransportText -match 'allow_inactive_targets'
$StaticContractValid = $StaticContractValid -and $OverflowText -match 'OwnedSemaphorePermit'
$StaticContractValid = $StaticContractValid -and $OverflowText -match 'reserve_owned'
$StaticContractValid = $StaticContractValid -and $OverflowText -match 'second_broker_owned_push_cannot_race_past_capacity'
$StaticContractValid = $StaticContractValid -and $CoreText -match 'MqttSharedRuntimeState'
$StaticContractValid = $StaticContractValid -and $CoreText -match 'not\(feature\s*=\s*"mqtt-dual-broker"\)'
if (-not $StaticContractValid) {
    throw "r4.4 dual integration static contract mismatch"
}

$TransportClientConstructorCount = [regex]::Matches(
    $TransportText,
    'AsyncClient::new\('
).Count
if ($TransportClientConstructorCount -ne 2) {
    throw "Expected exactly two persistent client constructors, actual=$TransportClientConstructorCount"
}

function Wait-ExactProcess {
    param(
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process]$Process,

        [Parameter(Mandatory = $true)]
        [int]$TimeoutMilliseconds,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $Exited = $Process.WaitForExit($TimeoutMilliseconds)
    if (-not $Exited) {
        throw "$Label exact child process $($Process.Id) timed out after $TimeoutMilliseconds ms"
    }
    $Process.WaitForExit()
    $Process.Refresh()
    return [pscustomobject]@{
        Exited = $true
        ExitCode = [int]$Process.ExitCode
        ProcessId = $Process.Id
    }
}

$StartedUtc = (Get-Date).ToUniversalTime()
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$Outcome = "FAIL_DO_NOT_RETRY_AUTOMATICALLY"
$Failure = $null
$BuildAttempted = $false
$BuildProcessId = $null
$BuildExitCode = $null
$FinishedReleaseCount = 0
$StdoutFeatureMarkerCount = 0
$StderrFeatureMarkerCount = 0
$CompilerWarningCount = 0
$CompilerErrorCount = 0
$GeneratedSoSize = $null
$GeneratedSoHash = $null
$TransportSourceHash = (Get-FileHash -LiteralPath $TransportSource -Algorithm SHA256).Hash
$OverflowSourceHash = (Get-FileHash -LiteralPath $OverflowSource -Algorithm SHA256).Hash
$CoreSourceHash = (Get-FileHash -LiteralPath $CoreSource -Algorithm SHA256).Hash

try {
    $Command = @"
`$ErrorActionPreference = 'Continue'
Set-Location '$RepoRoot'
& '$BuildScript' -Features '$FeatureName'
exit `$LASTEXITCODE
"@
    $EncodedCommand = [Convert]::ToBase64String(
        [Text.Encoding]::Unicode.GetBytes($Command)
    )

    $BuildAttempted = $true
    $BuildProcess = Start-Process `
        -FilePath $PowerShellExe `
        -ArgumentList @("-NoProfile", "-EncodedCommand", $EncodedCommand) `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath `
        -PassThru
    $BuildProcessId = $BuildProcess.Id
    $BuildWait = Wait-ExactProcess `
        -Process $BuildProcess `
        -TimeoutMilliseconds $BuildTimeoutMilliseconds `
        -Label "r4.4 dual integration Android Rust build"
    $BuildExitCode = $BuildWait.ExitCode

    $StdoutLines = @(Get-Content -LiteralPath $StdoutPath)
    $StderrLines = @(Get-Content -LiteralPath $StderrPath)
    $BuildLines = @($StdoutLines) + @($StderrLines)
    @(
        "=== STDOUT ==="
        $StdoutLines
        "=== STDERR ==="
        $StderrLines
    ) | Set-Content -LiteralPath $LogPath -Encoding UTF8

    $FinishedReleaseCount = @(
        $BuildLines | Where-Object { $_ -match "Finished.*release" }
    ).Count
    $StdoutFeatureMarkerCount = @(
        $StdoutLines | Where-Object { $_ -match "Cargo features:\s*mqtt-dual-broker" }
    ).Count
    $StderrFeatureMarkerCount = @(
        $StderrLines | Where-Object { $_ -match "Cargo features:\s*mqtt-dual-broker" }
    ).Count
    $CompilerWarningCount = @(
        $BuildLines | Where-Object { $_ -match "^\s*warning(?:\[|:)" }
    ).Count
    $CompilerErrorCount = @(
        $BuildLines | Where-Object { $_ -match "^\s*error(?:\[|:)" }
    ).Count

    if ($BuildExitCode -ne 0) {
        throw "r4.4 dual integration build failed: exit=$BuildExitCode"
    }
    if ($FinishedReleaseCount -ne 1) {
        throw "Expected one Finished release marker, actual=$FinishedReleaseCount"
    }
    if ($StdoutFeatureMarkerCount -ne 1) {
        throw "Expected one authoritative stdout feature marker, actual=$StdoutFeatureMarkerCount"
    }
    if ($CompilerErrorCount -ne 0) {
        throw "Compiler error lines found: $CompilerErrorCount"
    }

    $GeneratedSoFile = Get-Item -LiteralPath $SoPath
    $GeneratedSoSize = $GeneratedSoFile.Length
    $GeneratedSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
    if ($GeneratedSoSize -le 0) {
        throw "Generated arm64 .so is empty"
    }
    if ($GeneratedSoHash -eq $PreviousSoHash) {
        throw "Dual integration .so hash did not change from isolated policy binary"
    }

    $FinalStatus = @(& git status --porcelain=v1 --untracked-files=all)
    $UnexpectedFinal = @(
        $FinalStatus | Where-Object { $_ -ne " M $GeneratedSoRelative" }
    )
    if ($UnexpectedFinal.Count -gt 0) {
        throw "Unexpected build outputs: $($UnexpectedFinal -join '; ')"
    }

    $Outcome = "PASS"
}
catch {
    $Failure = $_.Exception.Message
    throw
}
finally {
    $Stopwatch.Stop()
    $State = [ordered]@{
        schema = 1
        purpose = "r4.4 atomic bounded dual-session publish and production dedup Android Rust build"
        outcome = $Outcome
        failure = $Failure
        startedUtc = $StartedUtc.ToString("o")
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        durationSeconds = [math]::Round($Stopwatch.Elapsed.TotalSeconds, 2)
        expectedSourceCommit = $ExpectedSourceCommit
        windowsHead = $CurrentHead
        feature = $FeatureName
        policyRecoveryState = $PolicyRecoveryPath
        policyRecoveryStateSha256 = $PolicyRecoveryHash
        buildAttempted = $BuildAttempted
        buildProcessId = $BuildProcessId
        buildExitCode = $BuildExitCode
        finishedReleaseCount = $FinishedReleaseCount
        stdoutFeatureMarkerCount = $StdoutFeatureMarkerCount
        stderrFeatureMarkerCount = $StderrFeatureMarkerCount
        compilerWarningCount = $CompilerWarningCount
        compilerErrorCount = $CompilerErrorCount
        transportSourceSha256 = $TransportSourceHash
        overflowSourceSha256 = $OverflowSourceHash
        coreSourceSha256 = $CoreSourceHash
        previousSoSize = $PreviousSoFile.Length
        previousSoSha256 = $PreviousSoHash
        generatedSoSize = $GeneratedSoSize
        generatedSoSha256 = $GeneratedSoHash
        stdoutPath = $StdoutPath
        stderrPath = $StderrPath
        logPath = $LogPath
        configuredBrokers = @("hivemq", "emqx")
        maxSessions = 2
        maxFanout = 2
        duplicateWindowSeconds = 30
        duplicateCapacity = 4096
        lossIntolerantCapacity = 256
        retainedTargetCapacity = 4096
        wireFormatChanged = $false
        runtimeIntegrationEnabled = $true
        apkBuilt = $false
        adbUsed = $false
        phonesChanged = $false
        publicTrafficSent = $false
        automaticRetry = $false
    }

    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:                    $Outcome"
    Write-Host "State:                      $StatePath"
    Write-Host "State SHA256:               $StateHash"
    Write-Host "Source commit:              $ExpectedSourceCommit"
    Write-Host "Feature:                    $FeatureName"
    Write-Host "Build process ID:           $BuildProcessId"
    Write-Host "Build exit code:            $BuildExitCode"
    Write-Host "Finished release count:     $FinishedReleaseCount"
    Write-Host "Feature markers out/err:    $StdoutFeatureMarkerCount / $StderrFeatureMarkerCount"
    Write-Host "Compiler warnings/errors:   $CompilerWarningCount / $CompilerErrorCount"
    Write-Host "Previous .so SHA256:        $PreviousSoHash"
    Write-Host "Generated .so size:         $GeneratedSoSize"
    Write-Host "Generated .so SHA256:       $GeneratedSoHash"
    Write-Host "Runtime integration:        True"
    Write-Host "Wire format changed:        False"
    Write-Host "APK/phones/public traffic:  False / False / False"
    Write-Host "Duration seconds:           $([math]::Round($Stopwatch.Elapsed.TotalSeconds, 2))"
}

if ($Outcome -ne "PASS") {
    throw "r4.4 dual integration build did not pass; see state: $StatePath"
}

Write-Host "R4.4 DUAL INTEGRATION ANDROID RUST BUILD PASS"
