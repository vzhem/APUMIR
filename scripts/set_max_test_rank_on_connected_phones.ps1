$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$Adb = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Repo = "C:\APU-M8"
$Package = "com.vladimir.messenger"
$TestPackage = "com.vladimir.messenger.test"
$Runner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$OverrideClass = "com.vladimir.messenger.data.referral.TestRankOverrideInstrumentedTest"
$MigrationClass = "com.vladimir.messenger.data.local.FileTransferProductionMigrationInstrumentedTest"
$App = Join-Path $Repo "android-app\app\build\outputs\apk\debug\app-debug.apk"
$Test = Join-Path $Repo "android-app\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$BuildState = Join-Path $env:TEMP "apu-proxy-rank-test-override-build-state.json"
$StatePath = Join-Path $env:TEMP "apu-max-test-rank-connected-phones-state.json"
$LogDirectory = Join-Path $env:TEMP "apu-max-test-rank-connected-phones-logs"

$ExpectedBuildState = "7A13F9E37BF9920B695B7427C965E2AE59854132BA2E3F993BE37569997C7A5B"
$ExpectedApp = "6368E84B14ED8536401C672524DEC8B8A915469077092ACAC61D8E3CB0FA10DD"
$ExpectedTest = "883BD01F557AAC6FBAA0DD8CFF88D7A37FFDC224418838CC6A5D2BD558CCB522"
$MaxTestRank = 1000
$KnownPhones = [ordered]@{
    "AUYF6R5923006121" = "Anna"
    "3B665800EES00000" = "Zhenya"
    "11567254BK001192" = "Stas"
}

if ((Test-Path $StatePath) -or (Test-Path $LogDirectory)) {
    throw "Max test-rank gate already attempted; preserve evidence"
}
if ((Get-FileHash $BuildState -Algorithm SHA256).Hash -ne $ExpectedBuildState -or
    (Get-FileHash $App -Algorithm SHA256).Hash -ne $ExpectedApp -or
    (Get-FileHash $Test -Algorithm SHA256).Hash -ne $ExpectedTest) {
    throw "Rank override build evidence/APK mismatch"
}

$DeviceLines = @(& $Adb devices)
$Connected = @($DeviceLines | ForEach-Object {
    if ($_ -match "^([^\s]+)\s+device$") { $Matches[1] }
} | Where-Object { $_ })
if ($Connected.Count -eq 0) { throw "No authorized test phones connected" }
$Unknown = @($Connected | Where-Object { -not $KnownPhones.Contains($_) })
if ($Unknown.Count -ne 0) {
    throw "Unknown connected serial(s); identify phone ownership before rank mutation"
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
function VersionOf([string]$Serial) {
    $Dump = @(& $Adb -s $Serial shell dumpsys package $Package)
    return (($Dump | Where-Object { $_ -match "^\s*versionName=" } | Select-Object -First 1) -as [string]).Trim()
}
function OptionalFileHash([string]$Serial, [string]$Path) {
    $State = PrivatePathState $Serial $Path
    return [ordered]@{
        state = $State
        hash = if ($State -eq "PRESENT") { HashText (ReadPrivateFile $Serial $Path) } else { $null }
    }
}
function RunInstrumentation([string]$Serial, [string[]]$Extras, [string]$LogPath) {
    $Arguments = @("-s", $Serial, "shell", "am", "instrument", "-w", "-r") + $Extras + @($Runner)
    $OldPreference = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    $Output = ((& $Adb @Arguments 2>&1 | ForEach-Object { $_.ToString() }) -join "`n")
    $Code = $LASTEXITCODE; $ErrorActionPreference = $OldPreference
    $Output | Set-Content $LogPath -Encoding UTF8
    if ($Code -ne 0 -or $Output -notmatch "OK \(1 test\)" -or $Output -notmatch "INSTRUMENTATION_CODE: -1") {
        throw "Instrumentation failed for $($KnownPhones[$Serial])"
    }
}

$Preflight = @()
foreach ($Serial in $Connected) {
    $Role = $KnownPhones[$Serial]
    $Version = VersionOf $Serial
    if ($Version -notmatch "^versionName=v11\.16\.(23|28|41|45)$") {
        throw "Unsupported pre-version for $Role: $Version"
    }
    $Profile = ReadPrivateFile $Serial "shared_prefs/p2p_prefs.xml"
    $Node = XmlValue $Profile "node_id"
    if (-not $Node -or (PrivatePathState $Serial "databases/messenger_database") -ne "PRESENT") {
        throw "Profile/database visibility failed for $Role"
    }
    $Preflight += [ordered]@{
        serial = $Serial; role = $Role; version = $Version
        profileHash = HashText $Profile; nodeHash = HashText $Node
        signing = OptionalFileHash $Serial "shared_prefs/apu_identity_signing.xml"
        pending = OptionalFileHash $Serial "shared_prefs/apu_pending_referral.xml"
        realReferral = OptionalFileHash $Serial "shared_prefs/apu_referral_qualification.xml"
        needsDatabaseMigration = $Version -match "^versionName=v11\.16\.(23|28)$"
    }
}

New-Item -ItemType Directory -Path $LogDirectory | Out-Null
$Result = [ordered]@{
    schema = 1; outcome = "INCOMPLETE_DO_NOT_REPEAT"
    startedUtc = (Get-Date).ToUniversalTime().ToString("o")
    connectedRoles = @($Preflight | ForEach-Object { $_.role })
    testOverride = $MaxTestRank; productionReceiptsCreated = $false
    uninstallApp = $false; databaseDelete = $false; dataClear = $false; forceStop = $false
    phones = @()
}
$Result | ConvertTo-Json -Depth 8 | Set-Content $StatePath -Encoding UTF8

try {
    foreach ($Phone in $Preflight) {
        $Serial = $Phone.serial; $Role = $Phone.role
        $InstallApp = ((& $Adb -s $Serial install -r $App) -join " ").Trim()
        if ($LASTEXITCODE -ne 0 -or $InstallApp -notmatch "Success") { throw "App update failed for $Role" }
        $InstallTest = ((& $Adb -s $Serial install -r $Test) -join " ").Trim()
        if ($LASTEXITCODE -ne 0 -or $InstallTest -notmatch "Success") { throw "Test install failed for $Role" }
        if ($Phone.needsDatabaseMigration) {
            RunInstrumentation $Serial @("-e", "class", $MigrationClass) (Join-Path $LogDirectory "$Role-migration.log")
        }
        RunInstrumentation $Serial @(
            "-e", "class", $OverrideClass,
            "-e", "qualified_referrals", $MaxTestRank.ToString()
        ) (Join-Path $LogDirectory "$Role-rank.log")
        & $Adb -s $Serial uninstall $TestPackage | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Test cleanup failed for $Role" }

        $ProfileAfter = ReadPrivateFile $Serial "shared_prefs/p2p_prefs.xml"
        if ((HashText $ProfileAfter) -ne $Phone.profileHash -or
            (HashText (XmlValue $ProfileAfter "node_id")) -ne $Phone.nodeHash) {
            throw "Profile/node changed for $Role"
        }
        foreach ($Field in @("pending", "realReferral")) {
            $Before = $Phone[$Field]
            $AfterPath = if ($Field -eq "pending") { "shared_prefs/apu_pending_referral.xml" } else { "shared_prefs/apu_referral_qualification.xml" }
            $After = OptionalFileHash $Serial $AfterPath
            if ($After.state -ne $Before.state -or $After.hash -ne $Before.hash) {
                throw "Production referral state changed for $Role"
            }
        }
        $TestPrefs = ReadPrivateFile $Serial "shared_prefs/apu_test_entitlements.xml"
        if ($TestPrefs -notmatch '<int name="qualified_direct_override_v1" value="1000"') {
            throw "Max test rank not persisted for $Role"
        }
        & $Adb -s $Serial shell am start -W -n "$Package/.MainActivity" | Out-Null
        Start-Sleep -Seconds 5
        $PidText = ((& $Adb -s $Serial shell pidof $Package) -join " ").Trim()
        if ($PidText -notmatch "^[0-9]+(\s+[0-9]+)*$") { throw "APU not stable for $Role" }
        $Result.phones += [ordered]@{
            role = $Role; versionBefore = $Phone.version; versionAfter = VersionOf $Serial
            nodeIdSha256 = $Phone.nodeHash; testRank = $MaxTestRank
            databaseMigrated = [bool]$Phone.needsDatabaseMigration
            appStable = $true
        }
    }
    $Result.outcome = "PASS"; $Result.completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    $Result.allConnectedKnownPhonesRank1000 = $true
    $Result.allCurrentEntitlementsUnlocked = $true
    $Result.productionReferralStatePreserved = $true
} finally {
    $Result | ConvertTo-Json -Depth 8 | Set-Content $StatePath -Encoding UTF8
}

$StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
Write-Host "`nOutcome: $($Result.outcome)"; Write-Host "State: $StatePath"; Write-Host "State SHA256: $StateHash"
Write-Host "Connected known test phones max rank: PASS"
Write-Host "Test rank/current entitlements: 1000/ALL"
Write-Host "Production referral receipts/count changed: False"
Write-Host "App uninstall/DB delete/data clear/force-stop: False/False/False/False"
Write-Host "MAX TEST RANK CONNECTED PHONES PASS"
