$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedApplicationCommit = "0181b3496cde81477a01e49f8d9977d7c325a2ca"
$ExpectedNativeSize = 7248576
$ExpectedNativeHash = "E6C34E86F18D9F63B9A641E3FD9FAFD67D5F1B7101729B2CB3DF25163380095B"
$ExpectedIntegrationStateHash = "A3E247756AC92DC77B836DDF19DC8B509B6A6DE9E6CB493AD22287A36DB5B3E4"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$HarnessPath = Join-Path $RepoRoot "scripts\r44_v111615_apk_build.ps1"
$IntegrationStatePath = Join-Path $env:TEMP "apu-r4.4-dual-integration-rust-build.json"
$StatePath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-apk-build.json"
$StableApkPath = Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15.apk"
$Artifacts = @(
    $StatePath,
    $StableApkPath,
    (Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-apk-build.log"),
    (Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-apk-build.stdout.log"),
    (Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-apk-build.stderr.log"),
    (Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-java.stdout.log"),
    (Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-java.stderr.log"),
    (Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-aapt.stdout.log"),
    (Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-aapt.stderr.log"),
    (Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-signer.stdout.log"),
    (Join-Path $env:TEMP "apu-r4.4-dual-v11.16.15-signer.stderr.log")
)

$ExistingArtifacts = @($Artifacts | Where-Object { Test-Path -LiteralPath $_ })
if ($ExistingArtifacts.Count -gt 0) {
    throw "V11.16.15 APK harness may already have run - do not repeat: $($ExistingArtifacts -join ', ')"
}
foreach ($RequiredPath in @($SoPath, $HarnessPath, $IntegrationStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required APK input missing: $RequiredPath"
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
    throw "Application source differs from exact v11.16.15 input"
}
& git diff --quiet HEAD -- scripts/r44_v111615_apk_build.ps1
if ($LASTEXITCODE -ne 0) {
    throw "APK harness differs from committed version"
}

$Status = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedStatus = @($Status | Where-Object { $_ -ne " M $GeneratedSoRelative" })
if ($UnexpectedStatus.Count -gt 0) {
    throw "Unexpected worktree changes: $($UnexpectedStatus -join '; ')"
}

$NativeFile = Get-Item -LiteralPath $SoPath
$NativeHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($NativeFile.Length -ne $ExpectedNativeSize -or $NativeHash -ne $ExpectedNativeHash) {
    throw "Dual native identity mismatch: size=$($NativeFile.Length) hash=$NativeHash"
}
$IntegrationStateHash = (Get-FileHash -LiteralPath $IntegrationStatePath -Algorithm SHA256).Hash
if ($IntegrationStateHash -ne $ExpectedIntegrationStateHash) {
    throw "Dual integration state hash mismatch: $IntegrationStateHash"
}

$Tokens = $null
$ParseErrors = $null
$HarnessAst = [System.Management.Automation.Language.Parser]::ParseFile(
    $HarnessPath,
    [ref]$Tokens,
    [ref]$ParseErrors
)
if (@($ParseErrors).Count -ne 0) {
    $ParseErrors | Format-List
    throw "APK artifact harness has parser errors"
}

$ScriptText = Get-Content -LiteralPath $HarnessPath -Raw
if ($ScriptText -match '(?i)\$(args|pid)(?![A-Za-z0-9_])') {
    throw "Automatic-variable conflict found in APK harness"
}
if ($ScriptText -match '(?im)^\s*-Wait(?:\s|$)') {
    throw "Unbounded Start-Process -Wait is forbidden"
}

# Single quotes are intentional: `$Gradlew` stays literal during cross-script inspection
$ExpectedBuildInvocation = '& ''$Gradlew'' '':app:assembleRelease'' ''--no-daemon'''
$GradleInvocationCount = [regex]::Matches(
    $ScriptText,
    [regex]::Escape($ExpectedBuildInvocation)
).Count
if ($GradleInvocationCount -ne 1) {
    throw "Expected exactly one literal Gradle APK invocation, actual=$GradleInvocationCount"
}

$CommandAsts = @(
    $HarnessAst.FindAll(
        {
            param($AstNode)
            $AstNode -is [System.Management.Automation.Language.CommandAst]
        },
        $true
    )
)
$ForbiddenCommandNames = @("adb", "adb.exe", "cargo", "cargo.exe")
foreach ($CommandAst in $CommandAsts) {
    $CommandName = $CommandAst.GetCommandName()
    if ($CommandName -and $CommandName -in $ForbiddenCommandNames) {
        throw "Forbidden command in APK harness: $CommandName"
    }
}

Write-Host "R4.4 V11.16.15 VERSIONED APK RUNNER PASS"
Write-Host "Runner SHA256:  $((Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash)"
Write-Host "Harness SHA256: $((Get-FileHash -LiteralPath $HarnessPath -Algorithm SHA256).Hash)"
Write-Host "Previous launcher stopped before this harness; artifact paths are absent."
Write-Host "One artifact-only build begins now. No ADB/phones/public traffic."
Write-Host ""

& $HarnessPath
