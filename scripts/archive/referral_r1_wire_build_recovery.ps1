$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "49ae4db3"
$OldSo = "3A0B876A9E53719F0934CAE127E90806C69FD6D169498F7F07B5F31320DE2172"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Version = "v11.16.29"
$Prefix = Join-Path $env:TEMP "apu-referral-r1-wire-build-recovery-01"
$State = "$Prefix-state.json"
$RustLog = "$Prefix-rust.log"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($State, $RustLog, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count) { throw "R1 wire recovery already attempted; preserve evidence" }

$Head = ((& git rev-parse HEAD) -join "").Trim()
& git diff --quiet $ExpectedSource $Head -- rust-core android-app
if ($LASTEXITCODE -ne 0) { throw "Source mismatch" }

$ExpectedStatus = " M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne $ExpectedStatus) { throw "Unexpected worktree" }

$So = Join-Path $RepoRoot "android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so"
if ((Get-FileHash $So -Algorithm SHA256).Hash -ne $OldSo) { throw "Native baseline mismatch" }

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$NewSo = $null
$ApkHash = $null
$ApkSize = $null
try {
    $RustCore = Join-Path $RepoRoot "rust-core"
    $Jni = Join-Path $RepoRoot "android-app\app\src\main\jniLibs"
    Push-Location $RustCore
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & cargo ndk -t arm64-v8a -o $Jni build --release --features mqtt-dual-broker *>&1 |
            Tee-Object $RustLog | Out-Host
        $Code = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($Code -ne 0) { throw "Rust build failed: $Code" }
    } finally { Pop-Location }

    $NewSo = (Get-FileHash $So -Algorithm SHA256).Hash
    if ($NewSo -eq $OldSo) { throw "Expected native change missing" }

    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;" + $env:Path
    $env:GITHUB_REF_NAME = $Version
    $Android = Join-Path $RepoRoot "android-app"
    Push-Location $Android
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & .\gradlew.bat --no-daemon :app:assembleDebug *>&1 |
            Tee-Object $GradleLog | Out-Host
        $Code = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($Code -ne 0) { throw "Gradle failed: $Code" }
    } finally { Pop-Location }

    $Apk = Join-Path $RepoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk"
    $ApkSize = (Get-Item $Apk).Length
    $ApkHash = (Get-FileHash $Apk -Algorithm SHA256).Hash
    $Outcome = "PASS"
} catch {
    $Failure = $_.Exception.Message
    throw
} finally {
    [ordered]@{
        schema = 1
        purpose = "R1 identity-bound referral wire production compile"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        version = $Version
        oldSo = $OldSo
        newSo = $NewSo
        apkSize = $ApkSize
        apkSha256 = $ApkHash
        hostTestsRun = $false
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $State -Encoding UTF8
    $StateHash = (Get-FileHash $State -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $State"
    Write-Host "State SHA256: $StateHash"
    Write-Host "Native: $NewSo"
    Write-Host "APK: $ApkSize / $ApkHash"
    Write-Host "Host tests/ADB/phones: False/False/False"
}
if ($Outcome -ne "PASS") { throw "R1 wire build failed" }
Write-Host "REFERRAL R1 WIRE BUILD RECOVERY PASS"
