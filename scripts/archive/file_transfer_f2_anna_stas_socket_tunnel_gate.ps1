$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$Adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Repo = "C:\APU-M8"
$Anna = "AUYF6R5923006121"
$Stas = "11567254BK001192"
$Package = "com.vladimir.messenger"
$TestPackage = "com.vladimir.messenger.test"
$Runner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$CleanupClass = "com.vladimir.messenger.data.file.FileTransferCrossPhoneArtifactCleanupInstrumentedTest"
$SenderClass = "com.vladimir.messenger.data.file.FileTransferCrossPhoneSenderInstrumentedTest"
$ReceiverClass = "com.vladimir.messenger.data.file.FileTransferCrossPhoneReceiverInstrumentedTest"
$App = Join-Path $Repo "android-app\app\build\outputs\apk\debug\app-debug.apk"
$Test = Join-Path $Repo "android-app\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$BuildState = Join-Path $env:TEMP "apu-file-transfer-f2-socket-tunnel-build-state.json"
$StatePath = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-socket-tunnel-03-state.json"
$CleanupLog = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-socket-tunnel-03-cleanup.log"
$SenderLog = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-socket-tunnel-03-sender.log"
$ReceiverOut = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-socket-tunnel-03-receiver.stdout.log"
$ReceiverErr = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-socket-tunnel-03-receiver.stderr.log"
$ReceiverLog = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-socket-tunnel-03-receiver.log"

$ExpectedBuildState = "17A17636DD02F14CB4DD88A2940D56D3FAD73B93390B4E3EC5604A15782DAFB5"
$ExpectedApp = "33CFC80617269318C711632F04E299511254CD75598C4C68061AFD7023582B52"
$ExpectedTest = "026F7B2AB790DE15DC51889DC0AC9FE8B89D864604B15842E291AE66129AB275"
$HostForwardPort = 47779
$AnnaReversePort = 37779
$StasServerPort = 39001

$Evidence = @($StatePath, $CleanupLog, $SenderLog, $ReceiverOut, $ReceiverErr, $ReceiverLog)
if (@($Evidence | Where-Object { Test-Path $_ }).Count -ne 0) {
    throw "Socket tunnel phone gate already attempted; preserve evidence"
}
if ((Get-FileHash $BuildState -Algorithm SHA256).Hash -ne $ExpectedBuildState -or
    (Get-FileHash $App -Algorithm SHA256).Hash -ne $ExpectedApp -or
    (Get-FileHash $Test -Algorithm SHA256).Hash -ne $ExpectedTest) {
    throw "Socket tunnel evidence/APK mismatch"
}

$Devices = @(& $Adb devices)
foreach ($Serial in @($Anna, $Stas)) {
    if (-not ($Devices -match "^$Serial\s+device$")) { throw "Phone unavailable: $Serial" }
}
function VersionOf([string]$Serial) {
    $Dump = @(& $Adb -s $Serial shell dumpsys package $Package)
    return (($Dump | Where-Object { $_ -match "^\s*versionName=" } | Select-Object -First 1) -as [string]).Trim()
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
    if ($Output -notin @("PRESENT", "ABSENT")) { throw "Cannot inspect private path" }
    return $Output
}
function XmlValue([string]$Xml, [string]$Name) {
    $Pattern = '<string name="' + [regex]::Escape($Name) + '">([^<]+)</string>'
    return ([regex]::Match($Xml, $Pattern)).Groups[1].Value
}
function Snapshot([string]$Serial) {
    if ((PrivatePathState $Serial "databases/messenger_database") -ne "PRESENT") { throw "DB missing" }
    $Profile = ReadPrivateFile $Serial "shared_prefs/p2p_prefs.xml"
    $Signing = ReadPrivateFile $Serial "shared_prefs/apu_identity_signing.xml"
    $PendingPath = "shared_prefs/apu_pending_referral.xml"
    $PendingState = PrivatePathState $Serial $PendingPath
    return [ordered]@{
        profile = HashText $Profile
        node = HashText (XmlValue $Profile "node_id")
        nodeValue = XmlValue $Profile "node_id"
        signing = HashText $Signing
        binding = HashText (XmlValue $Signing "identity_binding_v1")
        pendingState = $PendingState
        pendingHash = if ($PendingState -eq "PRESENT") { HashText (ReadPrivateFile $Serial $PendingPath) } else { $null }
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
function RunInstrumentation([string]$Serial, [string[]]$Extras, [string]$OutputPath) {
    $Arguments = @("-s", $Serial, "shell", "am", "instrument", "-w", "-r") + $Extras + @($Runner)
    $OldPreference = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    $Output = ((& $Adb @Arguments 2>&1 | ForEach-Object { $_.ToString() }) -join "`n")
    $Code = $LASTEXITCODE; $ErrorActionPreference = $OldPreference
    $Output | Set-Content $OutputPath -Encoding UTF8
    if ($Code -ne 0 -or $Output -notmatch "OK \(1 test\)" -or $Output -notmatch "INSTRUMENTATION_CODE: -1") {
        throw "Instrumentation failed on $Serial"
    }
}
function RemoveMappings() {
    $OldPreference = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    & $Adb -s $Anna reverse --remove "tcp:$AnnaReversePort" | Out-Null
    & $Adb -s $Stas forward --remove "tcp:$HostForwardPort" | Out-Null
    $ErrorActionPreference = $OldPreference
}

if ((VersionOf $Anna) -ne "versionName=v11.16.41" -or (VersionOf $Stas) -ne "versionName=v11.16.41") {
    throw "Both phones must be v11.16.41"
}
$AnnaBefore = Snapshot $Anna
$StasBefore = Snapshot $Stas
$ReceiverRoot = "no_backup/file-cross-phone-receiver-test-v1"
$ReadyPath = "no_backup/file-cross-phone-receiver-ready-v1"
$Result = [ordered]@{
    schema = 1; outcome = "INCOMPLETE_DO_NOT_REPEAT"
    startedUtc = (Get-Date).ToUniversalTime().ToString("o")
    senderRole = "Anna"; receiverRole = "Stas"
    transport = "ADB-tunneled-length-prefixed-Java-socket-test-only"
    testKeyOutOfBand = $true; rustNetworkEngineUsed = $false
    productionNetworkPath = $false; productionKeyExchange = $false; productionUi = $false
    databaseDelete = $false; dataClear = $false; forceStop = $false
}
$Result | ConvertTo-Json -Depth 7 | Set-Content $StatePath -Encoding UTF8

$Key = New-Object byte[] 32
$Rng = [Security.Cryptography.RandomNumberGenerator]::Create(); $Rng.GetBytes($Key); $Rng.Dispose()
$KeyText = [Convert]::ToBase64String($Key).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$RunBytes = New-Object byte[] 8
$Rng2 = [Security.Cryptography.RandomNumberGenerator]::Create(); $Rng2.GetBytes($RunBytes); $Rng2.Dispose()
$RunId = ([BitConverter]::ToString($RunBytes) -replace "-", "").ToLowerInvariant()
$ReceiverProcess = $null
try {
    RemoveMappings
    foreach ($Serial in @($Anna, $Stas)) {
        $InstallApp = ((& $Adb -s $Serial install -r $App) -join " ").Trim()
        if ($LASTEXITCODE -ne 0 -or $InstallApp -notmatch "Success") { throw "App replace install failed" }
        $InstallTest = ((& $Adb -s $Serial install -r $Test) -join " ").Trim()
        if ($LASTEXITCODE -ne 0 -or $InstallTest -notmatch "Success") { throw "Test install failed" }
    }
    & $Adb -s $Stas shell run-as $Package rm -rf $ReceiverRoot | Out-Null
    & $Adb -s $Stas shell run-as $Package rm -f $ReadyPath | Out-Null
    RunInstrumentation $Stas @("-e", "class", $CleanupClass, "-e", "expected_sender", $AnnaBefore.nodeValue) $CleanupLog

    & $Adb -s $Stas forward "tcp:$HostForwardPort" "tcp:$StasServerPort" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Stas forward failed" }
    & $Adb -s $Anna reverse "tcp:$AnnaReversePort" "tcp:$HostForwardPort" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Anna reverse failed" }

    $ReceiverArgs = @(
        "-s", $Stas, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ReceiverClass, "-e", "file_run_id", $RunId,
        "-e", "expected_sender", $AnnaBefore.nodeValue, "-e", "file_test_key", $KeyText,
        "-e", "server_port", $StasServerPort.ToString(), $Runner
    )
    $ReceiverProcess = Start-Process -FilePath $Adb -ArgumentList $ReceiverArgs `
        -RedirectStandardOutput $ReceiverOut -RedirectStandardError $ReceiverErr -NoNewWindow -PassThru
    $Ready = $false
    for ($Attempt = 0; $Attempt -lt 45; $Attempt++) {
        if ((PrivatePathState $Stas $ReadyPath) -eq "PRESENT") { $Ready = $true; break }
        if ($ReceiverProcess.HasExited) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $Ready) { throw "Raw socket receiver did not become ready" }

    RunInstrumentation $Anna @(
        "-e", "class", $SenderClass, "-e", "file_run_id", $RunId,
        "-e", "recipient_node", $StasBefore.nodeValue, "-e", "file_test_key", $KeyText,
        "-e", "tcp_port", $AnnaReversePort.ToString()
    ) $SenderLog

    if (-not $ReceiverProcess.WaitForExit(120000)) { throw "Raw socket receiver timeout" }
    $ReceiverText = ((Get-Content $ReceiverOut -Raw) + "`n" + (Get-Content $ReceiverErr -Raw)).Trim()
    $ReceiverText | Set-Content $ReceiverLog -Encoding UTF8
    if ($ReceiverProcess.ExitCode -ne 0 -or $ReceiverText -notmatch "OK \(1 test\)" -or
        $ReceiverText -notmatch "INSTRUMENTATION_CODE: -1") { throw "Raw socket receiver failed" }

    RemoveMappings
    foreach ($Serial in @($Anna, $Stas)) {
        & $Adb -s $Serial uninstall $TestPackage | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Test cleanup failed" }
    }
    if ((PrivatePathState $Stas $ReceiverRoot) -ne "ABSENT" -or
        (PrivatePathState $Stas $ReadyPath) -ne "ABSENT") { throw "Receiver artifacts remain" }
    AssertSnapshot $Anna $AnnaBefore; AssertSnapshot $Stas $StasBefore
    foreach ($Serial in @($Anna, $Stas)) { & $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" | Out-Null }
    Start-Sleep -Seconds 8
    foreach ($Serial in @($Anna, $Stas)) {
        if (((& $Adb -s $Serial shell pidof $Package) -join " ").Trim() -notmatch "^[0-9]+(\s+[0-9]+)*$") {
            throw "APU not stable"
        }
    }

    $Result.outcome = "PASS"; $Result.completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    $Result.encryptedFileCrossDeviceRoundTrip = $true; $Result.encryptedPngCrossDeviceRoundTrip = $true
    $Result.fileSha256Verified = $true; $Result.pngDecoded = $true; $Result.exactAck = $true
    $Result.receiverReadyObserved = $true; $Result.syntheticArtifactsCleaned = $true
    $Result.tunnelMappingsRemoved = $true; $Result.testArtifactsRemoved = $true
    $Result.productionStatePreserved = $true; $Result.appsRelaunchedAndStable = $true
    $Result.cleanupLogSha256 = (Get-FileHash $CleanupLog -Algorithm SHA256).Hash
    $Result.senderLogSha256 = (Get-FileHash $SenderLog -Algorithm SHA256).Hash
    $Result.receiverLogSha256 = (Get-FileHash $ReceiverLog -Algorithm SHA256).Hash
} finally {
    RemoveMappings
    [Array]::Clear($Key, 0, $Key.Length); $KeyText = $null; $RunId = $null
    $Result | ConvertTo-Json -Depth 7 | Set-Content $StatePath -Encoding UTF8
}

$StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
Write-Host "`nOutcome: $($Result.outcome)"
Write-Host "State: $StatePath"
Write-Host "State SHA256: $StateHash"
Write-Host "Encrypted file/photo cross-device roundtrip: PASS/PASS"
Write-Host "File SHA/PNG decode/exact ACK: PASS/PASS/PASS"
Write-Host "Ready/synthetic cleanup/tunnel removal: PASS/PASS/PASS"
Write-Host "Production state/test artifacts preserved/removed: True/True"
Write-Host "Rust network/production network/key exchange/UI: False/False/False/False"
Write-Host "DB delete/data clear/force-stop: False/False/False"
Write-Host "FILE TRANSFER F2 ANNA-STAS RAW SOCKET PASS"
