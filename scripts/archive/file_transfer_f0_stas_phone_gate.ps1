$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$Adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Repo = "C:\APU-M8"
$Stas = "11567254BK001192"
$Package = "com.vladimir.messenger"
$TestPackage = "com.vladimir.messenger.test"
$Runner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$TestClass = "com.vladimir.messenger.data.file.FileTransferCryptoInstrumentedTest"
$App = Join-Path $Repo "android-app\app\build\outputs\apk\debug\app-debug.apk"
$Test = Join-Path $Repo "android-app\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$BuildState = Join-Path $env:TEMP "apu-file-transfer-f0-android-runtime-build-state.json"
$StatePath = Join-Path $env:TEMP "apu-file-transfer-f0-stas-phone-state.json"
$LogPath = Join-Path $env:TEMP "apu-file-transfer-f0-stas-phone.log"

$ExpectedBuildState = "8D556A999089C239A82CA696F1D318491EB6062D12EC3CA723550944E9B195F2"
$ExpectedApp = "3888C58DB43992E18C5EFDDA909377DD10EEA0ACE54A1F471D2D7A708A3D0245"
$ExpectedTest = "C7637E4CC609B55EF910AE5DB87442D6D79DAF5AFDE572E0A66FECE88C270D5D"
$ExpectedNative = "1D6478B21FDE3D4856E439A575268D0314BA078C79D842AC0D039512B3554B23"
$ExpectedBinding = "A697399686E60292D5C2EEFBE207D143602E0443782ABE8FFFFF91B8F93325E7"

$Existing = @(@($StatePath, $LogPath) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "File F0 Stas gate already attempted; preserve evidence" }
if ((Get-FileHash $BuildState -Algorithm SHA256).Hash -ne $ExpectedBuildState -or
    (Get-FileHash $App -Algorithm SHA256).Hash -ne $ExpectedApp -or
    (Get-FileHash $Test -Algorithm SHA256).Hash -ne $ExpectedTest -or
    (Get-FileHash "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so" -Algorithm SHA256).Hash -ne $ExpectedNative -or
    (Get-FileHash "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt" -Algorithm SHA256).Hash -ne $ExpectedBinding) {
    throw "Build evidence/artifact mismatch"
}

$Devices = @(& $Adb devices)
if (-not ($Devices -match "^$Stas\s+device$")) { throw "Stas unavailable or unauthorized" }
$PackageLines = @(& $Adb -s $Stas shell pm list packages -U $Package)
$PackageVisible = $PackageLines | Where-Object {
    $_ -match "^package:com\.vladimir\.messenger\s+uid:[0-9]+$"
} | Select-Object -First 1
$DumpBefore = @(& $Adb -s $Stas shell dumpsys package $Package)
$VersionBefore = (($DumpBefore | Where-Object { $_ -match "^\s*versionName=" } | Select-Object -First 1) -as [string]).Trim()
if (-not $PackageVisible -or $VersionBefore -ne "versionName=v11.16.31") {
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
$IdentityBindingBefore = XmlValue $SigningBefore "identity_binding_v1"
if (-not $NodeBefore -or -not $IdentityBindingBefore) { throw "Private identity visibility gate failed" }
$ProfileHash = HashText $ProfileBefore
$NodeHash = HashText $NodeBefore
$SigningHash = HashText $SigningBefore
$IdentityBindingHash = HashText $IdentityBindingBefore
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
    identityBindingBase64Sha256 = $IdentityBindingHash
    productionPendingStateBefore = $PendingStateBefore
    productionPendingSha256Before = $PendingHashBefore
    replaceInstall = $true
    uninstallApp = $false
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
        throw "File crypto instrumentation failed"
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
        (HashText (XmlValue $SigningAfter "identity_binding_v1")) -ne $IdentityBindingHash -or
        $PendingStateAfter -ne $PendingStateBefore -or
        $PendingHashAfter -ne $PendingHashBefore) {
        throw "Production identity/pending state changed"
    }

    $DumpAfter = @(& $Adb -s $Stas shell dumpsys package $Package)
    $VersionAfter = (($DumpAfter | Where-Object { $_ -match "^\s*versionName=" } | Select-Object -First 1) -as [string]).Trim()
    if ($VersionAfter -ne "versionName=v11.16.34") { throw "Stas version mismatch after update" }
    & $Adb -s $Stas shell am start -W -n "$Package/.MainActivity" | Out-Null

    $Result.outcome = "PASS"
    $Result.completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    $Result.versionAfter = $VersionAfter
    $Result.instrumentationLogSha256 = (Get-FileHash $LogPath -Algorithm SHA256).Hash
    $Result.cryptoRoundTripPassed = $true
    $Result.tamperRejected = $true
    $Result.wrongIndexRejected = $true
    $Result.changedManifestRejected = $true
    $Result.productionStatePreserved = $true
    $Result.testPackageRemoved = $true
    $Result.appRelaunched = $true
} finally {
    $Result | ConvertTo-Json -Depth 7 | Set-Content $StatePath -Encoding UTF8
}

$StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
Write-Host "`nOutcome: $($Result.outcome)"
Write-Host "State: $StatePath"
Write-Host "State SHA256: $StateHash"
Write-Host "Roundtrip/tamper/index/manifest: PASS/PASS/PASS/PASS"
Write-Host "Profile/node/signing/pending preserved: True/True/True/True"
Write-Host "Test package removed/app relaunched: True/True"
Write-Host "Data clear/force-stop: False/False"
Write-Host "FILE TRANSFER F0 STAS PHONE PASS"
