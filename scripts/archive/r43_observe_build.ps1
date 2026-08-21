$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedApplicationCommit = "2689dbb933523bb410d4a0ce22f5122863f0ba63"
$ExpectedPreviousSoHash = "E706A9009F28E842F6A030D0CCC7BABB28D56E20DEFB5FB34117FD87F032E7E5"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$BuildScript = Join-Path $RepoRoot "build-rust.ps1"
$StatePath = Join-Path $env:TEMP "apu-r4.3-observe-rust-build.json"
$StdoutPath = Join-Path $env:TEMP "apu-r4.3-observe-rust-build.stdout.log"
$StderrPath = Join-Path $env:TEMP "apu-r4.3-observe-rust-build.stderr.log"
$LogPath = Join-Path $env:TEMP "apu-r4.3-observe-rust-build.log"
$PowerShellExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$FeatureName = "mqtt-secondary-observe"

$Artifacts = @($StatePath, $StdoutPath, $StderrPath, $LogPath)
$Existing = @($Artifacts | Where-Object { Test-Path -LiteralPath $_ })
if ($Existing.Count -gt 0) {
    throw "R4.3 OBSERVE BUILD ALREADY ATTEMPTED - DO NOT REPEAT: $($Existing -join ', ')"
}

foreach ($RequiredPath in @($SoPath, $BuildScript, $PowerShellExe)) {
    if (-not (Test-Path -LiteralPath $RequiredPath)) {
        throw "Required build input missing: $RequiredPath"
    }
}

$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Cannot resolve Windows HEAD"
}

& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) {
    throw "Application/build source differs from expected r4.3 commit"
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
if ($PreviousSoHash -ne $ExpectedPreviousSoHash) {
    throw "Previous r1b4 .so hash mismatch: $PreviousSoHash"
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

$StartedUtc = (Get-Date).ToUniversalTime()
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$Outcome = "FAIL_DO_NOT_RETRY_AUTOMATICALLY"
$Failure = $null
$BuildAttempted = $false
$BuildExitCode = $null
$FinishedReleaseCount = 0
$FeatureMarkerCount = 0
$CompilerWarningCount = 0
$CompilerErrorCount = 0
$GeneratedSoSize = $null
$GeneratedSoHash = $null

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
        -Wait `
        -PassThru

    $BuildExitCode = $BuildProcess.ExitCode
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
        $BuildLines | Where-Object { $_ -match "Cargo features:\s*mqtt-secondary-observe" }
    ).Count
    $CompilerWarningCount = @(
        $BuildLines | Where-Object { $_ -match "^\s*warning(?:\[|:)" }
    ).Count
    $CompilerErrorCount = @(
        $BuildLines | Where-Object { $_ -match "^\s*error(?:\[|:)" }
    ).Count

    if ($BuildExitCode -ne 0) {
        throw "r4.3 feature build failed: exit=$BuildExitCode"
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
    if (-not (Test-Path -LiteralPath $SoPath)) {
        throw "Generated arm64 .so missing"
    }

    $GeneratedSoFile = Get-Item -LiteralPath $SoPath
    $GeneratedSoSize = $GeneratedSoFile.Length
    $GeneratedSoHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
    if ($GeneratedSoSize -le 0) {
        throw "Generated arm64 .so is empty"
    }
    if ($GeneratedSoHash -eq $PreviousSoHash) {
        throw "Feature build .so hash did not change"
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
        purpose = "r4.3 feature-gated secondary observe-only Android Rust build"
        outcome = $Outcome
        failure = $Failure
        startedUtc = $StartedUtc.ToString("o")
        completedUtc = (Get-Date).ToUniversalTime().ToString("o")
        durationSeconds = [math]::Round($Stopwatch.Elapsed.TotalSeconds, 2)
        expectedApplicationCommit = $ExpectedApplicationCommit
        windowsHead = $CurrentHead
        feature = $FeatureName
        buildAttempted = $BuildAttempted
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
        apkBuilt = $false
        phonesChanged = $false
        publicTrafficSent = $false
    }

    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "Outcome:                 $Outcome"
    Write-Host "State:                   $StatePath"
    Write-Host "State SHA256:            $StateHash"
    Write-Host "Feature:                 $FeatureName"
    Write-Host "Build attempted:         $BuildAttempted"
    Write-Host "Build exit code:         $BuildExitCode"
    Write-Host "Finished release count:  $FinishedReleaseCount"
    Write-Host "Feature marker count:    $FeatureMarkerCount"
    Write-Host "Compiler warnings:       $CompilerWarningCount"
    Write-Host "Compiler errors:         $CompilerErrorCount"
    Write-Host "Previous .so SHA256:     $PreviousSoHash"
    Write-Host "Generated .so size:      $GeneratedSoSize"
    Write-Host "Generated .so SHA256:    $GeneratedSoHash"
    Write-Host "Phones changed:          False"
    Write-Host "Duration seconds:        $([math]::Round($Stopwatch.Elapsed.TotalSeconds, 2))"
}

if ($Outcome -ne "PASS") {
    throw "r4.3 observe build did not pass; see state: $StatePath"
}
