$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "05fccf12eb5f75a25aad8652c3ef9694550593a5"
$ExpectedNative = "836B009E903E8BAF6A4E5F478954B6A46723DDE18FF0B83DF8D8AD03F8BB391A"
$ExpectedBinding = "B123242009C7E3D1387BE7EEC982FA4EF1971F99BAD42D97F068E478E971F9AA"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-referral-r1-pending-runtime-build"
$StatePath = "$Prefix-state.json"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "Pending runtime gate already attempted; preserve evidence" }
$Head = ((& git rev-parse HEAD) -join "").Trim()
& git diff --quiet $ExpectedSource $Head -- android-app rust-core
if ($LASTEXITCODE -ne 0) { throw "Source mismatch" }

$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne " M $NativePath") { throw "Unexpected worktree" }
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $ExpectedNative) { throw "Native mismatch" }
if ((Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $ExpectedBinding) { throw "Binding mismatch" }

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$ApkHash = $null
$ApkSize = $null
try {
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;" + $env:Path
    $env:GITHUB_REF_NAME = "v11.16.31"
    Push-Location (Join-Path $RepoRoot "android-app")
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & .\gradlew.bat --no-daemon `
            :app:testDebugUnitTest `
            --tests "com.vladimir.messenger.util.ReferralInviteLinkTest" `
            :app:assembleDebug *>&1 | Tee-Object $GradleLog | Out-Host
        $Code = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($Code -ne 0) { throw "Gradle test/build failed: $Code" }
    } finally { Pop-Location }
    $ApkPath = Join-Path $RepoRoot "android-app/app/build/outputs/apk/debug/app-debug.apk"
    $ApkSize = (Get-Item $ApkPath).Length
    $ApkHash = (Get-FileHash $ApkPath -Algorithm SHA256).Hash
    $Outcome = "PASS"
} catch {
    $Failure = $_.Exception.Message
    throw
} finally {
    [ordered]@{
        schema = 1
        purpose = "R1 pending verified referral runtime compile"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        apkSize = $ApkSize
        apkSha256 = $ApkHash
        pendingInstrumentationRun = $false
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $StatePath"
    Write-Host "State SHA256: $StateHash"
    Write-Host "APK: $ApkSize / $ApkHash"
    Write-Host "Instrumentation/ADB/phones: False/False/False"
}
if ($Outcome -ne "PASS") { throw "Pending runtime gate failed" }
Write-Host "REFERRAL R1 PENDING RUNTIME BUILD PASS"
