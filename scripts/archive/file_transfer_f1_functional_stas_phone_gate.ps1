$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$Adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Repo = "C:\APU-M8"
$Stas = "11567254BK001192"
$Package = "com.vladimir.messenger"
$TestPackage = "com.vladimir.messenger.test"
$Runner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$TestClass = "com.vladimir.messenger.data.file.FileTransferFunctionalCryptoInstrumentedTest"
$App = Join-Path $Repo "android-app\app\build\outputs\apk\debug\app-debug.apk"
$Test = Join-Path $Repo "android-app\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$BuildState = Join-Path $env:TEMP "apu-file-transfer-f1-functional-androidtest-build-state.json"
$StatePath = Join-Path $env:TEMP "apu-file-transfer-f1-functional-stas-phone-state.json"
$LogPath = Join-Path $env:TEMP "apu-file-transfer-f1-functional-stas-phone.log"

$ExpectedBuildState = "3981EC294107EAFB6EA4B81A10EC8BB85BE1C7E2E2F45D0EF9DC1D680C658198"
$ExpectedApp = "2EC36B50EB9AB14D78A9D9FCDE12FE6DAC70749D928339DA5C38E4D4C0A3930B"
$ExpectedTest = "125AED71A7DC8B67DBF5BD937F2AB5BCF563079202D6A299C02145DDDD757DF8"

$Existing = @(@($StatePath, $LogPath) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "Functional Stas gate already attempted; preserve evidence" }
if ((Get-FileHash $BuildState -Algorithm SHA256).Hash -ne $ExpectedBuildState -or
    (Get-FileHash $App -Algorithm SHA256).Hash -ne $ExpectedApp -or
    (Get-FileHash $Test -Algorithm SHA256).Hash -ne $ExpectedTest) {
    throw "Functional build evidence/APK mismatch"
}

$Devices = @(& $Adb devices)
if (-not ($Devices -match "^$Stas\s+device$")) { throw "Stas unavailable or unauthorized" }
$PackageLines = @(& $Adb -s $Stas shell pm list packages -U $Package)
$PackageVisible = $PackageLines | Where-Object {
    $_ -match "^package:com\.vladimir\.messenger\s+uid:[0-9]+$"
} | Select-Object -First 1
$DumpBefore = @(& $Adb -s $Stas shell dumpsys package $Package)
$VersionBefore = (($DumpBefore | Where-Object { $_ -match "^\s*versionName=" } | Select-Object -First 1) -as [string]).Trim()
if (-not $PackageVisible -or $VersionBefore -ne "versionName=v11.16.35") {
    throw "Stas package/version visibility gate failed: package=$([bool]$PackageVisible), $VersionBefore"
}

function HashText([string]$Text) {
    $Bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $Sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($Sha.ComputeHash($Bytes)) -replace "-", "") }
    finally { $Sha.Dispose() }
}
function ReadPrivateFile([string]$RelativePath) {
    return ((& $Adb -s $Stas exec-out run-as $Package cat $RelativePath) -join "`n")
}
function PrivateFileState([string]$RelativePath) {
    $Output = ((& $Adb -s $Stas exec-out run-as $Package sh -c "if [ -e '$RelativePath' ]; then echo PRESENT; else echo ABSENT; fi") -join "`n").Trim()
    if ($Output -notin @("PRESENT", "ABSENT")) { throw "Cannot inspect private path state" }
    return $Output
}
function XmlValue([string]$Xml, [string]$Name) {
    $Pattern = '<string name="' + [regex]::Escape($Name) + '">([^<]+)</string>'
    return ([regex]::Match($Xml, $Pattern)).Groups[1].Value
}

if ((PrivateFileState "databases/messenger_database") -ne "PRESENT") { throw "Production DB missing" }
$TestStorePath = "no_backup/file-transfer-functional-test-v1"
if ((PrivateFileState $TestStorePath) -ne "ABSENT") { throw "Functional test store already exists" }
$ProfileBefore = ReadPrivateFile "shared_prefs/p2p_prefs.xml"
$SigningBefore = ReadPrivateFile "shared_prefs/apu_identity_signing.xml"
$ProfileHash = HashText $ProfileBefore
$NodeHash = HashText (XmlValue $ProfileBefore "node_id")
$SigningHash = HashText $SigningBefore
$BindingHash = HashText (XmlValue $SigningBefore "identity_binding_v1")
$PendingPath = "shared_prefs/apu_pending_referral.xml"
$PendingStateBefore = PrivateFileState $PendingPath
$PendingHashBefore = if ($PendingStateBefore -eq "PRESENT") { HashText (ReadPrivateFile $PendingPath) } else { $null }

$Result = [ordered]@{
    schema = 1
    outcome = "INCOMPLETE_DO_NOT_REPEAT"
    startedUtc = (Get-Date).ToUniversalTime().ToString("o")
    phoneRole = "Stas"
    versionBefore = $VersionBefore
    profileSha256 = $ProfileHash
    nodeIdSha256 = $NodeHash
    signingPrefsSha256 = $SigningHash
    identityBindingBase64Sha256 = $BindingHash
    productionPendingStateBefore = $PendingStateBefore
    productionPendingSha256Before = $PendingHashBefore
    replaceInstall = $true
    productionStoreTouched = $false
    persistentKeyCreated = $false
    transportUsed = $false
    databaseDelete = $false
    dataClear = $false
    forceStop = $false
}
$Result | ConvertTo-Json -Depth 7 | Set-Content $StatePath -Encoding UTF8

try {
    $InstallApp = ((& $Adb -s $Stas install -r $App) -join " ").Trim()
    if ($LASTEXITCODE -ne 0 -or $InstallApp -notmatch "Success") { throw "Stas app update failed" }
    $InstallTest = ((& $Adb -s $Stas install -r $Test) -join " ").Trim()
    if ($LASTEXITCODE -ne 0 -or $InstallTest -notmatch "Success") { throw "Test APK install failed" }

    $OldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $Instrumentation = ((& $Adb -s $Stas shell am instrument -w -r -e class $TestClass $Runner 2>&1 |
        ForEach-Object { $_.ToString() }) -join "`n")
    $InstrumentationCode = $LASTEXITCODE
    $ErrorActionPreference = $OldPreference
    $Instrumentation | Set-Content $LogPath -Encoding UTF8
    if ($InstrumentationCode -ne 0 -or
        $Instrumentation -notmatch "OK \(1 test\)" -or
        $Instrumentation -notmatch "INSTRUMENTATION_CODE: -1") {
        throw "Functional file pipeline instrumentation failed"
    }

    & $Adb -s $Stas uninstall $TestPackage | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Test package cleanup failed" }
    if ((PrivateFileState $TestStorePath) -ne "ABSENT") { throw "Functional test store remains" }

    $ProfileAfter = ReadPrivateFile "shared_prefs/p2p_prefs.xml"
    $SigningAfter = ReadPrivateFile "shared_prefs/apu_identity_signing.xml"
    $PendingStateAfter = PrivateFileState $PendingPath
    $PendingHashAfter = if ($PendingStateAfter -eq "PRESENT") { HashText (ReadPrivateFile $PendingPath) } else { $null }
    if ((HashText $ProfileAfter) -ne $ProfileHash -or
        (HashText (XmlValue $ProfileAfter "node_id")) -ne $NodeHash -or
        (HashText $SigningAfter) -ne $SigningHash -or
        (HashText (XmlValue $SigningAfter "identity_binding_v1")) -ne $BindingHash -or
        $PendingStateAfter -ne $PendingStateBefore -or $PendingHashAfter -ne $PendingHashBefore -or
        (PrivateFileState "databases/messenger_database") -ne "PRESENT") {
        throw "Production identity/pending/database state changed"
    }

    $DumpAfter = @(& $Adb -s $Stas shell dumpsys package $Package)
    $VersionAfter = (($DumpAfter | Where-Object { $_ -match "^\s*versionName=" } | Select-Object -First 1) -as [string]).Trim()
    if ($VersionAfter -ne "versionName=v11.16.38") { throw "Stas version mismatch after update" }
    & $Adb -s $Stas shell am start -W -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 8
    $ProcessIdText = ((& $Adb -s $Stas shell pidof $Package) -join " ").Trim()
    if ($ProcessIdText -notmatch "^[0-9]+(\s+[0-9]+)*$") { throw "APU did not remain running" }

    $Result.outcome = "PASS"
    $Result.completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    $Result.versionAfter = $VersionAfter
    $Result.manifestCreateParsePassed = $true
    $Result.encryptStoreDecryptPassed = $true
    $Result.idempotentRetryPassed = $true
    $Result.tamperAndTruncationRejected = $true
    $Result.testStoreRemoved = $true
    $Result.productionStatePreserved = $true
    $Result.testPackageRemoved = $true
    $Result.appRelaunchedAndStable = $true
    $Result.instrumentationLogSha256 = (Get-FileHash $LogPath -Algorithm SHA256).Hash
} finally {
    $Result | ConvertTo-Json -Depth 7 | Set-Content $StatePath -Encoding UTF8
}

$StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
Write-Host "`nOutcome: $($Result.outcome)"
Write-Host "State: $StatePath"
Write-Host "State SHA256: $StateHash"
Write-Host "Manifest/encrypt-store-decrypt/retry/tamper: PASS/PASS/PASS/PASS"
Write-Host "Profile/node/signing/pending/DB preserved: True/True/True/True/True"
Write-Host "Test store/package removed: True/True"
Write-Host "Persistent key/production store/transport: False/False/False"
Write-Host "DB delete/data clear/force-stop: False/False/False"
Write-Host "FILE TRANSFER F1 FUNCTIONAL STAS PHONE PASS"
