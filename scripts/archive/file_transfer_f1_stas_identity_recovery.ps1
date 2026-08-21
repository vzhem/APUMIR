$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$Adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Repo = "C:\APU-M8"
$Stas = "11567254BK001192"
$Package = "com.vladimir.messenger"
$TestPackage = "com.vladimir.messenger.test"
$Runner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$TestClass = "com.vladimir.messenger.data.local.FileTransferMigrationIdentityRecoveryInstrumentedTest"
$App = Join-Path $Repo "android-app\app\build\outputs\apk\debug\app-debug.apk"
$Test = Join-Path $Repo "android-app\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$FailedState = Join-Path $env:TEMP "apu-file-transfer-f1-stas-migration-phone-state.json"
$FailedLog = Join-Path $env:TEMP "apu-file-transfer-f1-stas-migration-phone.log"
$BuildState = Join-Path $env:TEMP "apu-file-transfer-f1-identity-recovery-build-state.json"
$StatePath = Join-Path $env:TEMP "apu-file-transfer-f1-stas-identity-recovery-01-state.json"
$LogPath = Join-Path $env:TEMP "apu-file-transfer-f1-stas-identity-recovery-01.log"

$ExpectedFailedState = "05927DDB3A8F83F211ACDE50E249BC59DCCAD7468C6E771D25B4B3BD56D40CAE"
$ExpectedFailedLog = "9D6A2EACCDD0D07DACEF927488204AE1452DB9EE09ABFC4E47015F33C7FA51AC"
$ExpectedBuildState = "0395F14639B16CFFDC896188AEA8EEB4BEE9A1492DD5B0340BAE11D8D1D349E2"
$ExpectedApp = "616E5CDC6AAA86F8A82C19BCCD2A297AD2A4C5775C7082F14ED44343B095E5E7"
$ExpectedTest = "CF36DE7016B512647DD6CBBB81264681A5B9521FB30A7E14520B19CA0FA7C196"

$Existing = @(@($StatePath, $LogPath) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "Stas identity recovery already attempted; preserve evidence" }
if ((Get-FileHash $FailedState -Algorithm SHA256).Hash -ne $ExpectedFailedState -or
    (Get-FileHash $FailedLog -Algorithm SHA256).Hash -ne $ExpectedFailedLog -or
    (Get-FileHash $BuildState -Algorithm SHA256).Hash -ne $ExpectedBuildState -or
    (Get-FileHash $App -Algorithm SHA256).Hash -ne $ExpectedApp -or
    (Get-FileHash $Test -Algorithm SHA256).Hash -ne $ExpectedTest) {
    throw "Recovery evidence/APK mismatch"
}
$Failed = Get-Content $FailedState -Raw | ConvertFrom-Json
if ($Failed.outcome -ne "INCOMPLETE_DO_NOT_REPEAT" -or
    $Failed.databaseDelete -ne $false -or $Failed.dataClear -ne $false -or $Failed.forceStop -ne $false) {
    throw "Unexpected prior migration state"
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
    $Output = ((& $Adb -s $Stas exec-out run-as $Package sh -c "if [ -f '$RelativePath' ]; then echo PRESENT; else echo ABSENT; fi") -join "`n").Trim()
    if ($Output -notin @("PRESENT", "ABSENT")) { throw "Cannot inspect private file state" }
    return $Output
}
function XmlValue([string]$Xml, [string]$Name) {
    $Pattern = '<string name="' + [regex]::Escape($Name) + '">([^<]+)</string>'
    return ([regex]::Match($Xml, $Pattern)).Groups[1].Value
}

if ((PrivateFileState "databases/messenger_database") -ne "PRESENT") { throw "Production DB missing" }
$ProfileBefore = ReadPrivateFile "shared_prefs/p2p_prefs.xml"
$SigningBefore = ReadPrivateFile "shared_prefs/apu_identity_signing.xml"
$ProfileHash = HashText $ProfileBefore
$NodeHash = HashText (XmlValue $ProfileBefore "node_id")
$SigningHash = HashText $SigningBefore
$IdentityBindingHash = HashText (XmlValue $SigningBefore "identity_binding_v1")
if ($ProfileHash -ne $Failed.profileSha256 -or $NodeHash -ne $Failed.nodeIdSha256 -or
    $SigningHash -ne $Failed.signingPrefsSha256 -or
    $IdentityBindingHash -ne $Failed.identityBindingBase64Sha256) {
    throw "Identity state changed since failed migration"
}
$PendingPath = "shared_prefs/apu_pending_referral.xml"
$PendingStateBefore = PrivateFileState $PendingPath
$PendingHashBefore = if ($PendingStateBefore -eq "PRESENT") { HashText (ReadPrivateFile $PendingPath) } else { $null }
if ($PendingStateBefore -ne $Failed.productionPendingStateBefore -or
    $PendingHashBefore -ne $Failed.productionPendingSha256Before) {
    throw "Production pending state changed since failed migration"
}

$Result = [ordered]@{
    schema = 1
    outcome = "INCOMPLETE_DO_NOT_REPEAT"
    startedUtc = (Get-Date).ToUniversalTime().ToString("o")
    phoneRole = "Stas"
    versionBefore = $VersionBefore
    staleDatabaseVersion = 6
    staleRoomIdentitySha256 = "451378b646892da8c31c499c2f93fff5"
    expectedRoomIdentitySha256 = "1600df8ca0acdcfc6be40910e2e5eee0"
    priorFailedStateSha256 = $ExpectedFailedState
    priorFailedLogSha256 = $ExpectedFailedLog
    profileSha256 = $ProfileHash
    nodeIdSha256 = $NodeHash
    signingPrefsSha256 = $SigningHash
    identityBindingBase64Sha256 = $IdentityBindingHash
    productionPendingState = $PendingStateBefore
    productionPendingSha256 = $PendingHashBefore
    appInstall = $false
    databaseDelete = $false
    dataClear = $false
    forceStop = $false
}
$Result | ConvertTo-Json -Depth 7 | Set-Content $StatePath -Encoding UTF8

try {
    $InstallTest = ((& $Adb -s $Stas install -r $Test) -join " ").Trim()
    if ($LASTEXITCODE -ne 0 -or $InstallTest -notmatch "Success") { throw "Recovery test APK install failed" }

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
        throw "Room identity recovery instrumentation failed"
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
        $PendingStateAfter -ne $PendingStateBefore -or $PendingHashAfter -ne $PendingHashBefore) {
        throw "Production identity/pending state changed during recovery"
    }

    & $Adb -s $Stas shell am start -W -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 8
    $ProcessIdText = ((& $Adb -s $Stas shell pidof $Package) -join " ").Trim()
    if ($ProcessIdText -notmatch "^[0-9]+(\s+[0-9]+)*$") { throw "APU did not remain running after recovery" }

    $Result.outcome = "PASS"
    $Result.completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    $Result.databaseAfterVersion = 6
    $Result.roomIdentityRepaired = $true
    $Result.generatedRoomSchemaValidated = $true
    $Result.legacyIdSetsAndCountsPreserved = $true
    $Result.newTransferTablesEmpty = $true
    $Result.productionIdentityPendingPreserved = $true
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
Write-Host "Room identity old -> v6: Repaired/PASS"
Write-Host "Legacy IDs/counts/schema/new tables: Preserved/PASS/Empty"
Write-Host "Profile/node/signing/pending preserved: True/True/True/True"
Write-Host "App install/DB delete/data clear/force-stop: False/False/False/False"
Write-Host "FILE TRANSFER F1 STAS IDENTITY RECOVERY PASS"
