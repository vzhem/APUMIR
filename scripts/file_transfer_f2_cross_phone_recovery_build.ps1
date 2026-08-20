$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "191c0626ad739583ae4583aa95e8c777101c084f"
$ExpectedNative = "95D96A416F0B8A9404D59D19AE749095ADE728B0C14BC943784DB00DA33B5D80"
$ExpectedBinding = "FA0743536328C1827EDBD9D380048B90F1B9CE1C7861D01E1B7C20A02F6C4493"
$ExpectedApp = "33CFC80617269318C711632F04E299511254CD75598C4C68061AFD7023582B52"
$FailedState = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-phone-state.json"
$FailedMigration = Join-Path $env:TEMP "apu-file-transfer-f2-anna-migration.log"
$FailedSender = Join-Path $env:TEMP "apu-file-transfer-f2-anna-sender.log"
$ExpectedFailedState = "6D8BDC4950E1773CA51C337B18ACCEAD37F323FDBA7F122505847B65C3F87E2E"
$ExpectedFailedMigration = "7CC3D7813C0949D61E6D4D2B07EE19BDF30F7B730241B437790C221FCCBE31D2"
$ExpectedFailedSender = "30C4A216A20BAF9C6CFE87465BF201A1058477E6EBC234736839F8A0B78F1089"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-transfer-f2-cross-phone-recovery-build"
$StatePath = "$Prefix-state.json"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "Cross-phone recovery build already attempted; preserve evidence" }
if ((Get-FileHash $FailedState -Algorithm SHA256).Hash -ne $ExpectedFailedState -or
    (Get-FileHash $FailedMigration -Algorithm SHA256).Hash -ne $ExpectedFailedMigration -or
    (Get-FileHash $FailedSender -Algorithm SHA256).Hash -ne $ExpectedFailedSender) {
    throw "Failed cross-phone evidence mismatch"
}
$MigrationText = Get-Content $FailedMigration -Raw
$SenderText = Get-Content $FailedSender -Raw
if ($MigrationText -notmatch "OK \(1 test\)" -or $SenderText -notmatch "OK \(1 test\)") {
    throw "Prior migration/sender did not pass"
}

$Head = ((& git rev-parse HEAD) -join "").Trim()
& git diff --quiet $ExpectedSource $Head -- android-app rust-core
if ($LASTEXITCODE -ne 0) { throw "Source mismatch" }
$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$AppPath = "android-app/app/build/outputs/apk/debug/app-debug.apk"
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne " M $NativePath") { throw "Unexpected worktree" }
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $ExpectedNative -or
    (Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $ExpectedBinding -or
    (Get-FileHash $AppPath -Algorithm SHA256).Hash -ne $ExpectedApp) {
    throw "Artifact baseline mismatch"
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$TestHash = $null
$TestSize = $null
try {
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;" + $env:Path
    $env:GITHUB_REF_NAME = "v11.16.41"
    Push-Location (Join-Path $RepoRoot "android-app")
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & .\gradlew.bat --no-daemon :app:assembleDebugAndroidTest *>&1 |
            Tee-Object $GradleLog | Out-Host
        $Code = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($Code -ne 0) { throw "Gradle cross-phone recovery build failed: $Code" }
    } finally { Pop-Location }
    if ((Get-FileHash $AppPath -Algorithm SHA256).Hash -ne $ExpectedApp) {
        throw "App APK changed during test-only build"
    }
    $TestPath = Join-Path $RepoRoot "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    $TestSize = (Get-Item $TestPath).Length
    $TestHash = (Get-FileHash $TestPath -Algorithm SHA256).Hash
    $Outcome = "PASS"
} catch {
    $Failure = $_.Exception.Message
    throw
} finally {
    [ordered]@{
        schema = 1
        purpose = "File F2 Anna-Stas network recovery harness build"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        failedStateSha256 = $ExpectedFailedState
        failedMigrationLogSha256 = $ExpectedFailedMigration
        failedSenderLogSha256 = $ExpectedFailedSender
        appApkSha256 = $ExpectedApp
        testApkSize = $TestSize
        testApkSha256 = $TestHash
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $StatePath"
    Write-Host "State SHA256: $StateHash"
    Write-Host "App APK unchanged: $ExpectedApp"
    Write-Host "Test APK: $TestSize / $TestHash"
    Write-Host "ADB/phones: False/False"
}
if ($Outcome -ne "PASS") { throw "Cross-phone recovery build failed" }
Write-Host "FILE TRANSFER F2 CROSS-PHONE RECOVERY BUILD PASS"
