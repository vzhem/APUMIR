$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedSourceCommit = "e3b806f9876219a625e99c17740e59138fefaab5"
$ExpectedPreviousSoHash = "D7A2216EF0210CBCD59685A6E76CF4D8284B87A8052C799280CBEEC4DD95FE95"
$ExpectedPreviousSoSize = 7193912
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$BuildScript = Join-Path $RepoRoot "build-rust.ps1"
$PolicySource = Join-Path $RepoRoot "rust-core\src\network\mqtt_fanout.rs"
$CargoToml = Join-Path $RepoRoot "rust-core\Cargo.toml"
$NetworkMod = Join-Path $RepoRoot "rust-core\src\network\mod.rs"
$StatePath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build.json"
$StdoutPath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build.stdout.log"
$StderrPath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build.stderr.log"
$LogPath = Join-Path $env:TEMP "apu-r4.4-fanout-policy-rust-build.log"
$PowerShellExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$FeatureName = "mqtt-dual-broker"
$BuildTimeoutMilliseconds = 900000

$Artifacts = @($StatePath, $StdoutPath, $StderrPath, $LogPath)
$ExistingArtifacts = @($Artifacts | Where-Object { Test-Path -LiteralPath $_ })
if ($ExistingArtifacts.Count -gt 0) {
    throw "R4.4 FANOUT POLICY BUILD ALREADY ATTEMPTED - DO NOT REPEAT: $($ExistingArtifacts -join ', ')"
}

foreach ($RequiredPath in @($SoPath, $BuildScript, $PolicySource, $CargoToml, $NetworkMod, $PowerShellExe)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
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
    throw "Application/build source differs from exact r4.4 policy source commit"
}

$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedBefore = @(
    $StatusBefore | Where-Object { $_ -ne " M $GeneratedSoRelative" }
)
if ($UnexpectedBefore.Count -gt 0) {
    throw "Unexpected pre-build worktree changes: $($UnexpectedBefore -join '; ')"
}

$PreviousSoFile = Get-Item -LiteralPath $SoPath
$PreviousSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($PreviousSoFile.Length -ne $ExpectedPreviousSoSize -or $PreviousSoHash -ne $ExpectedPreviousSoHash) {
    throw "Previous r4.3 feature .so identity mismatch: size=$($PreviousSoFile.Length) hash=$PreviousSoHash"
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
$NetworkModText = Get-Content -LiteralPath $NetworkMod -Raw
$PolicyText = Get-Content -LiteralPath $PolicySource -Raw
if ($CargoText -notmatch '(?m)^mqtt-dual-broker\s*=\s*\[\]\s*$') {
    throw "mqtt-dual-broker feature declaration missing"
}
if ($NetworkModText -notmatch '(?s)#\[cfg\(feature\s*=\s*"mqtt-dual-broker"\)\]\s*pub mod mqtt_fanout;') {
    throw "mqtt_fanout module is not hard-gated"
}
$PolicyContractValid = $PolicyText -match 'MAX_MQTT_PUBLISH_FANOUT:\s*usize\s*=\s*2'
$PolicyContractValid = $PolicyContractValid -and $PolicyText -match 'MAX_RETAINED_BROKER_TARGETS:\s*usize\s*=\s*4_096'
$PolicyContractValid = $PolicyContractValid -and $PolicyText -notmatch 'AsyncClient|EventLoop|MqttOptions|tokio::spawn'
if (-not $PolicyContractValid) {
    throw "r4.4 isolated fanout policy contract mismatch"
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
$FeatureMarkerCount = 0
$CompilerWarningCount = 0
$CompilerErrorCount = 0
$GeneratedSoSize = $null
$GeneratedSoHash = $null
$PolicySourceHash = (Get-FileHash -LiteralPath $PolicySource -Algorithm SHA256).Hash

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
        -Label "r4.4 fanout policy Android Rust build"
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
    $FeatureMarkerCount = @(
        $BuildLines | Where-Object { $_ -match "Cargo features:\s*mqtt-dual-broker" }
    ).Count
    $CompilerWarningCount = @(
        $BuildLines | Where-Object { $_ -match "^\s*warning(?:\[|:)" }
    ).Count
    $CompilerErrorCount = @(
        $BuildLines | Where-Object { $_ -match "^\s*error(?:\[|:)" }
    ).Count

    if ($BuildExitCode -ne 0) {
        throw "r4.4 fanout policy feature build failed: exit=$BuildExitCode"
    }
    if ($FinishedReleaseCount -ne 1) {
        throw "Expected one Finished release marker, actual=$FinishedReleaseCount"
    }
    if ($FeatureMarkerCount -ne 1) {
        throw "Feature marker missing or duplicated: $FeatureMarkerCount"
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
        throw "r4.4 policy feature .so hash did not change from r4.3 observer binary"
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
        purpose = "r4.4 isolated bounded fanout and retained-target policy Android Rust build"
        outcome = $Outcome
        failure = $Failure
        startedUtc = $StartedUtc.ToString("o")
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        durationSeconds = [math]::Round($Stopwatch.Elapsed.TotalSeconds, 2)
        expectedSourceCommit = $ExpectedSourceCommit
        windowsHead = $CurrentHead
        feature = $FeatureName
        policySource = $PolicySource
        policySourceSha256 = $PolicySourceHash
        buildAttempted = $BuildAttempted
        buildProcessId = $BuildProcessId
        buildExitCode = $BuildExitCode
        finishedReleaseCount = $FinishedReleaseCount
        featureMarkerCount = $FeatureMarkerCount
        compilerWarningCount = $CompilerWarningCount
        compilerErrorCount = $CompilerErrorCount
        previousSoSize = $PreviousSoFile.Length
        previousSoSha256 = $PreviousSoHash
        generatedSoSize = $GeneratedSoSize
        generatedSoSha256 = $GeneratedSoHash
        stdoutPath = $StdoutPath
        stderrPath = $StderrPath
        logPath = $LogPath
        maxFanout = 2
        retainedTargetCapacity = 4096
        runtimeIntegrationEnabled = $false
        apkBuilt = $false
        adbUsed = $false
        phonesChanged = $false
        publicTrafficSent = $false
        automaticRetry = $false
    }

    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:                 $Outcome"
    Write-Host "State:                   $StatePath"
    Write-Host "State SHA256:            $StateHash"
    Write-Host "Source commit:           $ExpectedSourceCommit"
    Write-Host "Feature:                 $FeatureName"
    Write-Host "Build process ID:        $BuildProcessId"
    Write-Host "Build exit code:         $BuildExitCode"
    Write-Host "Finished release count:  $FinishedReleaseCount"
    Write-Host "Feature marker count:    $FeatureMarkerCount"
    Write-Host "Compiler warnings:       $CompilerWarningCount"
    Write-Host "Compiler errors:         $CompilerErrorCount"
    Write-Host "Policy source SHA256:    $PolicySourceHash"
    Write-Host "Previous .so SHA256:     $PreviousSoHash"
    Write-Host "Generated .so size:      $GeneratedSoSize"
    Write-Host "Generated .so SHA256:    $GeneratedSoHash"
    Write-Host "Runtime integration:     False"
    Write-Host "Phones/public traffic:   False / False"
    Write-Host "Duration seconds:        $([math]::Round($Stopwatch.Elapsed.TotalSeconds, 2))"
}

if ($Outcome -ne "PASS") {
    throw "r4.4 fanout policy build did not pass; see state: $StatePath"
}

Write-Host "R4.4 FANOUT POLICY ANDROID RUST BUILD PASS"
