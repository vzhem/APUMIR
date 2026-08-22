$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$Adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Repo = "C:\APU-M8"
$Anna = "AUYF6R5923006121"
$Stas = "11567254BK001192"
$Package = "com.vladimir.messenger"
$TestPackage = "com.vladimir.messenger.test"
$Runner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$MigrationClass = "com.vladimir.messenger.data.local.FileTransferProductionMigrationInstrumentedTest"
$SenderClass = "com.vladimir.messenger.data.file.FileTransferCrossPhoneSenderInstrumentedTest"
$ReceiverClass = "com.vladimir.messenger.data.file.FileTransferCrossPhoneReceiverInstrumentedTest"
$App = Join-Path $Repo "android-app\app\build\outputs\apk\debug\app-debug.apk"
$Test = Join-Path $Repo "android-app\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$BuildState = Join-Path $env:TEMP "apu-file-transfer-f2-cross-phone-test-build-state.json"
$StatePath = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-phone-state.json"
$MigrationLog = Join-Path $env:TEMP "apu-file-transfer-f2-anna-migration.log"
$SenderLog = Join-Path $env:TEMP "apu-file-transfer-f2-anna-sender.log"
$ReceiverOut = Join-Path $env:TEMP "apu-file-transfer-f2-stas-receiver.stdout.log"
$ReceiverErr = Join-Path $env:TEMP "apu-file-transfer-f2-stas-receiver.stderr.log"
$ReceiverLog = Join-Path $env:TEMP "apu-file-transfer-f2-stas-receiver.log"

$ExpectedBuildState = "183C801A7A56CED0F5FBA12A6E85CE7E66A2419FB8E4B78012C1146EF0407D6D"
$ExpectedApp = "33CFC80617269318C711632F04E299511254CD75598C4C68061AFD7023582B52"
$ExpectedTest = "152E66A2D7D16877D312BFF2FDF8FC44765AAF72AB8DD8708C1187DF77FE13B6"

$EvidencePaths = @($StatePath, $MigrationLog, $SenderLog, $ReceiverOut, $ReceiverErr, $ReceiverLog)
$Existing = @($EvidencePaths | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "F2 Anna-Stas gate already attempted; preserve evidence" }
if ((Get-FileHash $BuildState -Algorithm SHA256).Hash -ne $ExpectedBuildState -or
    (Get-FileHash $App -Algorithm SHA256).Hash -ne $ExpectedApp -or
    (Get-FileHash $Test -Algorithm SHA256).Hash -ne $ExpectedTest) {
    throw "Cross-phone build evidence/APK mismatch"
}

$Devices = @(& $Adb devices)
foreach ($Serial in @($Anna, $Stas)) {
    if (-not ($Devices -match "^$Serial\s+device$")) { throw "Phone unavailable or unauthorized: $Serial" }
}

function VersionOf([string]$Serial) {
    $Dump = @(& $Adb -s $Serial shell dumpsys package $Package)
    return (($Dump | Where-Object { $_ -match "^\s*versionName=" } | Select-Object -First 1) -as [string]).Trim()
}
function PackageVisible([string]$Serial) {
    return [bool](@(& $Adb -s $Serial shell pm list packages -U $Package) | Where-Object {
        $_ -match "^package:com\.vladimir\.messenger\s+uid:[0-9]+$"
    } | Select-Object -First 1)
}
$AnnaBeforeVersion = VersionOf $Anna
$StasBeforeVersion = VersionOf $Stas
if (-not (PackageVisible $Anna) -or $AnnaBeforeVersion -ne "versionName=v11.16.28") {
    throw "Anna visibility/version gate failed: $AnnaBeforeVersion"
}
if (-not (PackageVisible $Stas) -or $StasBeforeVersion -ne "versionName=v11.16.41") {
    throw "Stas visibility/version gate failed: $StasBeforeVersion"
}

function HashText([string]$Text) {
    $Bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $Sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($Sha.ComputeHash($Bytes)) -replace "-", "") }
    finally { $Sha.Dispose() }
}
function ReadPrivateFile([string]$Serial, [string]$RelativePath) {
    return ((& $Adb -s $Serial exec-out run-as $Package cat $RelativePath) -join "`n")
}
function PrivatePathState([string]$Serial, [string]$RelativePath) {
    $Output = ((& $Adb -s $Serial exec-out run-as $Package sh -c "if [ -e '$RelativePath' ]; then echo PRESENT; else echo ABSENT; fi") -join "`n").Trim()
    if ($Output -notin @("PRESENT", "ABSENT")) { throw "Cannot inspect private path on $Serial" }
    return $Output
}
function XmlValue([string]$Xml, [string]$Name) {
    $Pattern = '<string name="' + [regex]::Escape($Name) + '">([^<]+)</string>'
    return ([regex]::Match($Xml, $Pattern)).Groups[1].Value
}
function Snapshot([string]$Serial) {
    if ((PrivatePathState $Serial "databases/messenger_database") -ne "PRESENT") {
        throw "Production DB missing on $Serial"
    }
    $Profile = ReadPrivateFile $Serial "shared_prefs/p2p_prefs.xml"
    $Signing = ReadPrivateFile $Serial "shared_prefs/apu_identity_signing.xml"
    $Node = XmlValue $Profile "node_id"
    $Binding = XmlValue $Signing "identity_binding_v1"
    if (-not $Node -or -not $Binding) { throw "Identity state missing on $Serial" }
    $PendingPath = "shared_prefs/apu_pending_referral.xml"
    $PendingState = PrivatePathState $Serial $PendingPath
    $PendingHash = if ($PendingState -eq "PRESENT") { HashText (ReadPrivateFile $Serial $PendingPath) } else { $null }
    return [ordered]@{
        profile = HashText $Profile
        node = HashText $Node
        nodeValue = $Node
        signing = HashText $Signing
        binding = HashText $Binding
        pendingState = $PendingState
        pendingHash = $PendingHash
        productionRoot = PrivatePathState $Serial "no_backup/file_transfers/v1"
    }
}
function AssertSnapshot([string]$Serial, $Before) {
    $After = Snapshot $Serial
    if ($After.profile -ne $Before.profile -or $After.node -ne $Before.node -or
        $After.signing -ne $Before.signing -or $After.binding -ne $Before.binding -or
        $After.pendingState -ne $Before.pendingState -or $After.pendingHash -ne $Before.pendingHash -or
        $After.productionRoot -ne $Before.productionRoot) {
        throw "Production state changed on $Serial"
    }
}
function RunInstrumentation([string]$Serial, [string[]]$ExtraArguments, [string]$OutputPath) {
    $Arguments = @("-s", $Serial, "shell", "am", "instrument", "-w", "-r") + $ExtraArguments + @($Runner)
    $OldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $Output = ((& $Adb @Arguments 2>&1 | ForEach-Object { $_.ToString() }) -join "`n")
    $Code = $LASTEXITCODE
    $ErrorActionPreference = $OldPreference
    $Output | Set-Content $OutputPath -Encoding UTF8
    if ($Code -ne 0 -or $Output -notmatch "OK \(1 test\)" -or $Output -notmatch "INSTRUMENTATION_CODE: -1") {
        throw "Instrumentation failed on $Serial"
    }
}

$AnnaBefore = Snapshot $Anna
$StasBefore = Snapshot $Stas
$ReceiverTestRoot = "no_backup/file-cross-phone-receiver-test-v1"
if ((PrivatePathState $Stas $ReceiverTestRoot) -ne "ABSENT") { throw "Receiver test root already exists" }

$Result = [ordered]@{
    schema = 1
    outcome = "INCOMPLETE_DO_NOT_REPEAT"
    startedUtc = (Get-Date).ToUniversalTime().ToString("o")
    senderRole = "Anna"
    receiverRole = "Stas"
    annaVersionBefore = $AnnaBeforeVersion
    stasVersionBefore = $StasBeforeVersion
    annaProfileSha256 = $AnnaBefore.profile
    annaNodeIdSha256 = $AnnaBefore.node
    stasProfileSha256 = $StasBefore.profile
    stasNodeIdSha256 = $StasBefore.node
    annaReplaceInstall = $true
    stasReplaceInstall = $false
    testKeyOutOfBand = $true
    productionKeyExchange = $false
    productionUi = $false
    databaseDelete = $false
    dataClear = $false
    forceStop = $false
}
$Result | ConvertTo-Json -Depth 7 | Set-Content $StatePath -Encoding UTF8

$ReceiverProcess = $null
$Key = New-Object byte[] 32
$Rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$Rng.GetBytes($Key)
$Rng.Dispose()
$KeyText = [Convert]::ToBase64String($Key).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$RunBytes = New-Object byte[] 8
$Rng2 = [Security.Cryptography.RandomNumberGenerator]::Create()
$Rng2.GetBytes($RunBytes)
$Rng2.Dispose()
$RunId = ([BitConverter]::ToString($RunBytes) -replace "-", "").ToLowerInvariant()
try {
    $InstallAnna = ((& $Adb -s $Anna install -r $App) -join " ").Trim()
    if ($LASTEXITCODE -ne 0 -or $InstallAnna -notmatch "Success") { throw "Anna app update failed" }
    foreach ($Serial in @($Anna, $Stas)) {
        $InstallTest = ((& $Adb -s $Serial install -r $Test) -join " ").Trim()
        if ($LASTEXITCODE -ne 0 -or $InstallTest -notmatch "Success") { throw "Test APK install failed on $Serial" }
    }

    RunInstrumentation $Anna @("-e", "class", $MigrationClass) $MigrationLog

    $ReceiverArguments = @(
        "-s", $Stas, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ReceiverClass,
        "-e", "file_run_id", $RunId,
        "-e", "expected_sender", $AnnaBefore.nodeValue,
        "-e", "file_test_key", $KeyText,
        $Runner
    )
    $ReceiverProcess = Start-Process -FilePath $Adb -ArgumentList $ReceiverArguments `
        -RedirectStandardOutput $ReceiverOut -RedirectStandardError $ReceiverErr `
        -NoNewWindow -PassThru
    Start-Sleep -Seconds 15

    RunInstrumentation $Anna @(
        "-e", "class", $SenderClass,
        "-e", "file_run_id", $RunId,
        "-e", "recipient_node", $StasBefore.nodeValue,
        "-e", "file_test_key", $KeyText
    ) $SenderLog

    if (-not $ReceiverProcess.WaitForExit(150000)) {
        throw "Receiver instrumentation timeout"
    }
    $ReceiverText = ((Get-Content $ReceiverOut -Raw) + "`n" + (Get-Content $ReceiverErr -Raw)).Trim()
    $ReceiverText | Set-Content $ReceiverLog -Encoding UTF8
    if ($ReceiverProcess.ExitCode -ne 0 -or
        $ReceiverText -notmatch "OK \(1 test\)" -or
        $ReceiverText -notmatch "INSTRUMENTATION_CODE: -1") {
        throw "Receiver cross-phone instrumentation failed"
    }

    foreach ($Serial in @($Anna, $Stas)) {
        & $Adb -s $Serial uninstall $TestPackage | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Test package cleanup failed on $Serial" }
    }
    if ((PrivatePathState $Stas $ReceiverTestRoot) -ne "ABSENT") { throw "Receiver test root remains" }
    AssertSnapshot $Anna $AnnaBefore
    AssertSnapshot $Stas $StasBefore

    if ((VersionOf $Anna) -ne "versionName=v11.16.41" -or
        (VersionOf $Stas) -ne "versionName=v11.16.41") {
        throw "Final phone version mismatch"
    }
    foreach ($Serial in @($Anna, $Stas)) {
        & $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" | Out-Null
    }
    Start-Sleep -Seconds 8
    foreach ($Serial in @($Anna, $Stas)) {
        $PidText = ((& $Adb -s $Serial shell pidof $Package) -join " ").Trim()
        if ($PidText -notmatch "^[0-9]+(\s+[0-9]+)*$") { throw "APU not stable on $Serial" }
    }

    $Result.outcome = "PASS"
    $Result.completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    $Result.annaVersionAfter = VersionOf $Anna
    $Result.stasVersionAfter = VersionOf $Stas
    $Result.annaDatabaseMigratedFiveToSix = $true
    $Result.encryptedFileNetworkRoundTrip = $true
    $Result.encryptedPngNetworkRoundTrip = $true
    $Result.fileSha256Verified = $true
    $Result.pngDecoded = $true
    $Result.testStoresAndPackagesRemoved = $true
    $Result.productionStatePreserved = $true
    $Result.appsRelaunchedAndStable = $true
    $Result.migrationLogSha256 = (Get-FileHash $MigrationLog -Algorithm SHA256).Hash
    $Result.senderLogSha256 = (Get-FileHash $SenderLog -Algorithm SHA256).Hash
    $Result.receiverLogSha256 = (Get-FileHash $ReceiverLog -Algorithm SHA256).Hash
} finally {
    [Array]::Clear($Key, 0, $Key.Length)
    $KeyText = $null
    $RunId = $null
    $Result | ConvertTo-Json -Depth 7 | Set-Content $StatePath -Encoding UTF8
}

$StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
Write-Host "`nOutcome: $($Result.outcome)"
Write-Host "State: $StatePath"
Write-Host "State SHA256: $StateHash"
Write-Host "Encrypted file/photo network roundtrip: PASS/PASS"
Write-Host "File SHA/PNG decode: PASS/PASS"
Write-Host "Anna DB migration 5->6: PASS"
Write-Host "Profiles/nodes/signing/pending/DB preserved: True"
Write-Host "Test stores/packages removed: True"
Write-Host "Production key exchange/UI: False/False"
Write-Host "DB delete/data clear/force-stop: False/False/False"
Write-Host "FILE TRANSFER F2 ANNA-STAS PHONE PASS"
