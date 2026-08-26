param(
    [string]$ExpectedCommit = '',
    [string]$DeviceSerial = 'AUYF6R5923006121',
    [int]$QualifiedReferrals = 1000,
    [switch]$Clear
)

# ============================================================================
# set-rank.ps1 - give ONE named test phone a qualified-referral rank override.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 no-BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\set-rank.ps1 -QualifiedReferrals 1000
#
# This does not invent anything: ReferralRankStore.qualifiedDirectCount already
# reads a debug-only override from the "apu_test_entitlements" preferences when
# BuildConfig.DEBUG is true, and TestRankOverrideInstrumentedTest already takes
# the number as the "qualified_referrals" instrumentation argument, writes it
# through ReferralRankStore.setDebugOverride and asserts that the app itself
# reads the same value back. Release builds reject the write
# (check(BuildConfig.DEBUG)), so a rank cannot be faked in production.
#
# What the number unlocks, from FileTransferRankPolicy:
#    1 -> manual proxy, 10 -> group creation and automatic proxy,
#   30 -> channel creation, 1000 is the top tier.
# MAX_SUPPORTED_COUNT in ReferralRankStore is 1000, so larger values are cut.
#
# The tests are run through lib-instrumented-tests.ps1, NOT through
# :app:connectedDebugAndroidTest - that task uninstalls the application
# afterwards and wipes its data, which is how the previous run destroyed the
# database on this phone. Remove the override later with -Clear.
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'
$AndroidRoot = Join-Path $RepoRoot 'android-app'
$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$Package = 'com.vladimir.messenger'
$PrefsPath = 'shared_prefs/apu_test_entitlements.xml'
$TestClass = 'com.vladimir.messenger.data.referral.TestRankOverrideInstrumentedTest'
$TestMethod = 'setExplicitDebugRankForTestPhone'

. (Join-Path $PSScriptRoot 'lib-instrumented-tests.ps1')

if (-not (Test-Path -LiteralPath $Adb)) {
    Write-Output "FATAL: adb not found at $Adb"
    exit 1
}

# ---- repo state -------------------------------------------------------------
Push-Location $RepoRoot
try {
    $Head = (& git rev-parse HEAD | Out-String).Trim()
    Write-Output "Repo HEAD:   $Head"
    Write-Output ("Repo branch: " + (& git rev-parse --abbrev-ref HEAD | Out-String).Trim())
    if ($ExpectedCommit -ne '' -and $Head -ne $ExpectedCommit) {
        Write-Output "FATAL: HEAD $Head does not match $ExpectedCommit."
        exit 1
    }
}
finally {
    Pop-Location
}

# ---- the named phone --------------------------------------------------------
Write-Output ''
Write-Output '===== connected devices ====='
& $Adb devices
if (-not (& $Adb devices | Select-String -Pattern $DeviceSerial -SimpleMatch)) {
    Write-Output ''
    Write-Output "FATAL: phone $DeviceSerial is not connected. Nothing was changed."
    exit 1
}
Write-Output "Target phone: $DeviceSerial"
$env:ANDROID_SERIAL = $DeviceSerial

function Invoke-Adb {
    param([string[]]$AdbArgs)
    return (Invoke-AdbRaw $DeviceSerial $AdbArgs $Adb)
}

$Dump = Invoke-Adb @('shell', "dumpsys package $Package")
if ("$Dump" -notmatch 'versionName=') {
    Write-Output ''
    Write-Output 'NOTE: the app is not installed on this phone. It will be installed from'
    Write-Output 'the current debug build, and the override will be set in that build.'
} else {
    "$Dump" -split "`n" | Select-String -Pattern 'versionName|lastUpdateTime' |
        ForEach-Object { $_.ToString().Trim() }
    $IsDebuggable = $false
    foreach ($Line in ("$Dump" -split "`n")) {
        if ($Line -match 'pkgFlags=\[' -and $Line -match 'DEBUGGABLE') { $IsDebuggable = $true }
    }
    if (-not $IsDebuggable) {
        Write-Output ''
        Write-Output 'FATAL: the installed build is not debuggable, so ReferralRankStore'
        Write-Output 'ignores the override by design (check(BuildConfig.DEBUG)). A debug APK'
        Write-Output 'cannot replace a release-signed one either.'
        exit 1
    }
}

# ---- clear mode -------------------------------------------------------------
if ($Clear) {
    Write-Output ''
    Write-Output '===== clearing the rank override ====='
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Seconds 1
    $Rm = Invoke-Adb @('shell', "run-as $Package rm $PrefsPath")
    $Check = Invoke-Adb @('shell', "run-as $Package cat $PrefsPath")
    if ("$Check" -match 'qualified_direct_override') {
        Write-Output 'RESULT: FAILED - the override file is still there.'
        Write-Output ("device said: " + ("$Rm" -replace "`n", ' '))
        exit 1
    }
    Write-Output 'RESULT: override removed, the real referral count is used again.'
    exit 0
}

if ($QualifiedReferrals -lt 0 -or $QualifiedReferrals -gt 1000) {
    Write-Output "FATAL: $QualifiedReferrals is out of range. ReferralRankStore.MAX_SUPPORTED_COUNT is 1000."
    exit 1
}

# ---- run the project's own override test ------------------------------------
Write-Output ''
Write-Output "===== setting qualified referrals to $QualifiedReferrals ====="
$Result = Invoke-InstrumentedTests `
    -Serial $DeviceSerial `
    -AdbPath $Adb `
    -AndroidRoot $AndroidRoot `
    -Classes ($TestClass + '#' + $TestMethod) `
    -ExtraArgs @{ qualified_referrals = "$QualifiedReferrals" }

Write-Output ("tests=" + $Result.Tests + " failures=" + $Result.Failures)
if (-not $Result.Ok) {
    Write-Output ("RESULT: FAILED - " + $Result.Reason)
    exit 1
}

# ---- independent proof: read the preferences file back ----------------------
Write-Output ''
Write-Output '===== preferences file on the phone ====='
Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
Start-Sleep -Seconds 1
$Prefs = Invoke-Adb @('shell', "run-as $Package cat $PrefsPath")
if ("$Prefs" -match 'qualified_direct_override') {
    "$Prefs" -split "`n" | ForEach-Object { Write-Output ('  ' + $_.Trim()) }
} else {
    Write-Output 'RESULT: FAILED - the override is not in the preferences file.'
    Write-Output ("device said: " + ("$Prefs" -replace "`n", ' '))
    exit 1
}

Write-Output ''
Write-Output "RESULT: rank override set to $QualifiedReferrals on $DeviceSerial."
Write-Output 'The test asserted that ReferralRankStore.qualifiedDirectCount returns it'
Write-Output 'inside the app process, which is exactly what GroupsModule reads.'
Write-Output 'Group creation is unlocked from 10, channels from 30.'
Write-Output 'The override lives in the app data directory and survives a reboot.'
Write-Output 'It disappears on uninstall; remove it earlier with -Clear.'
