$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$Adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Repo = "C:\APU-M8"
$Stas = "11567254BK001192"
$Package = "com.vladimir.messenger"
$TestPackage = "com.vladimir.messenger.test"
$Runner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$TestClass = "com.vladimir.messenger.data.referral.PendingReferralStoreInstrumentedTest"
$App = Join-Path $Repo "android-app\app\build\outputs\apk\debug\app-debug.apk"
$Test = Join-Path $Repo "android-app\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$BuildState = Join-Path $env:TEMP "apu-referral-r1-pending-androidtest-build-state.json"
$State = Join-Path $env:TEMP "apu-referral-r1-pending-stas-phone-state.json"
$Log = Join-Path $env:TEMP "apu-referral-r1-pending-stas-phone.log"

$ExpectedBuildState = "42F74B582DD98FF5B6D46BD54A96DCCDACFE7D4B4D8D108A951058A149B1731E"
$ExpectedApp = "902137B2A31759A13C6483C861399F5B41EE7F66407B46D70127504E6B86480C"
$ExpectedTest = "E825E00EFFA1975BA18F9B6143464CB2BD1EFAD055C223BB2C7EA4B5C91908C5"

$Existing = @(@($State, $Log) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "Stas pending phone gate already attempted; preserve evidence" }
if ((Get-FileHash $BuildState -Algorithm SHA256).Hash -ne $ExpectedBuildState -or
    (Get-FileHash $App -Algorithm SHA256).Hash -ne $ExpectedApp -or
    (Get-FileHash $Test -Algorithm SHA256).Hash -ne $ExpectedTest) {
    throw "Build evidence/APK mismatch"
}

$Devices = @(& $Adb devices)
if (-not ($Devices -match "^$Stas\s+device$")) { throw "Stas unavailable or unauthorized" }
$PackageDump = @(& $Adb -s $Stas shell dumpsys package $Package)
$UidLine = ($PackageDump | Where-Object { $_ -match "userId=" } | Select-Object -First 1)
$VersionBefore = (($PackageDump | Where-Object { $_ -match "^\s*versionName=" } | Select-Object -First 1) -as [string]).Trim()
if (-not $UidLine -or $VersionBefore -ne "versionName=v11.16.28") {
    throw "Stas install/version visibility gate failed: $VersionBefore"
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
    $Output = ((& $Adb -s $Stas exec-out run-as $Package sh -c "if [ -f '$RelativePath' ]; then echo PRESENT; else echo ABSENT; fi") -join "`n").Trim()
    if ($Output -notin @("PRESENT", "ABSENT")) { throw "Cannot inspect private file state" }
    return $Output
}
function XmlValue([string]$Xml, [string]$Name) {
    $Pattern = '<string name="' + [regex]::Escape($Name) + '">([^<]+)</string>'
    return ([regex]::Match($Xml, $Pattern)).Groups[1].Value
}

$ProfileBefore = ReadPrivateFile "shared_prefs/p2p_prefs.xml"
$SigningBefore = ReadPrivateFile "shared_prefs/apu_identity_signing.xml"
$NodeBefore = XmlValue $ProfileBefore "node_id"
$BindingBefore = XmlValue $SigningBefore "identity_binding_v1"
if (-not $NodeBefore -or -not $BindingBefore) { throw "Profile/signing visibility gate failed" }
$ProfileHash = HashText $ProfileBefore
$NodeHash = HashText $NodeBefore
$SigningHash = HashText $SigningBefore
$BindingHash = HashText $BindingBefore
$PendingPath = "shared_prefs/apu_pending_referral.xml"
$TestPendingPath = "shared_prefs/apu_pending_referral_test_instrumented_v1.xml"
$PendingStateBefore = PrivateFileState $PendingPath
$PendingHashBefore = if ($PendingStateBefore -eq "PRESENT") { HashText (ReadPrivateFile $PendingPath) } else { $null }
if ((PrivateFileState $TestPendingPath) -ne "ABSENT") { throw "Isolated test prefs already exist" }

$Result = [ordered]@{
    schema = 1
    outcome = "INCOMPLETE_DO_NOT_REPEAT"
    startedUtc = (Get-Date).ToUniversalTime().ToString("o")
    serialRole = "Stas"
    versionBefore = $VersionBefore
    profileSha256 = $ProfileHash
    nodeIdSha256 = $NodeHash
    signingPrefsSha256 = $SigningHash
    bindingBase64Sha256 = $BindingHash
    productionPendingStateBefore = $PendingStateBefore
    productionPendingSha256Before = $PendingHashBefore
    replaceInstall = $true
    uninstallApp = $false
    dataClear = $false
    forceStop = $false
}
$Result | ConvertTo-Json -Depth 7 | Set-Content $State -Encoding UTF8

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
    $Instrumentation | Set-Content $Log -Encoding UTF8
    if ($InstrumentationCode -ne 0 -or
        $Instrumentation -notmatch "OK \(1 test\)" -or
        $Instrumentation -notmatch "INSTRUMENTATION_CODE: -1") {
        throw "Pending referral instrumentation failed"
    }

    & $Adb -s $Stas uninstall $TestPackage | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Test package cleanup failed" }

    $ProfileAfter = ReadPrivateFile "shared_prefs/p2p_prefs.xml"
    $SigningAfter = ReadPrivateFile "shared_prefs/apu_identity_signing.xml"
    $PendingStateAfter = PrivateFileState $PendingPath
    $PendingHashAfter = if ($PendingStateAfter -eq "PRESENT") { HashText (ReadPrivateFile $PendingPath) } else { $null }
    if ((HashText $ProfileAfter) -ne $ProfileHash -or
        (HashText (XmlValue $ProfileAfter "node_id")) -ne $NodeHash -or
        (HashText $SigningAfter) -ne $SigningHash -or
        (HashText (XmlValue $SigningAfter "identity_binding_v1")) -ne $BindingHash -or
        $PendingStateAfter -ne $PendingStateBefore -or
        $PendingHashAfter -ne $PendingHashBefore -or
        (PrivateFileState $TestPendingPath) -ne "ABSENT") {
        throw "Production identity/pending state changed or test prefs remained"
    }

    $DumpAfter = @(& $Adb -s $Stas shell dumpsys package $Package)
    $VersionAfter = (($DumpAfter | Where-Object { $_ -match "^\s*versionName=" } | Select-Object -First 1) -as [string]).Trim()
    if ($VersionAfter -ne "versionName=v11.16.31") { throw "Stas version mismatch after update" }
    & $Adb -s $Stas shell am start -W -n "$Package/.MainActivity" | Out-Null

    $Result.outcome = "PASS"
    $Result.completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    $Result.versionAfter = $VersionAfter
    $Result.instrumentationLogSha256 = (Get-FileHash $Log -Algorithm SHA256).Hash
    $Result.persistLoadPassed = $true
    $Result.tamperAutoClearPassed = $true
    $Result.expiryAutoClearPassed = $true
    $Result.productionStatePreserved = $true
    $Result.testPrefsRemoved = $true
    $Result.testPackageRemoved = $true
    $Result.appRelaunched = $true
} finally {
    $Result | ConvertTo-Json -Depth 7 | Set-Content $State -Encoding UTF8
}

$StateHash = (Get-FileHash $State -Algorithm SHA256).Hash
Write-Host "`nOutcome: $($Result.outcome)"
Write-Host "State: $State"
Write-Host "State SHA256: $StateHash"
Write-Host "Persist/load/tamper/expiry: PASS/PASS/PASS/PASS"
Write-Host "Profile/node/signing/pending preserved: True/True/True/True"
Write-Host "Test prefs/package removed: True/True"
Write-Host "Data clear/force-stop: False/False"
Write-Host "REFERRAL R1 PENDING STAS PHONE PASS"
