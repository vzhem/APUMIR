param(
    [string]$ApkPath = '',
    [string[]]$Serials = @('11567254BK001192', 'AUYF6R5923006121')
)

# powershell.exe -File binding quirk (hit 2026-09-01): "-Serials A,B" arrives as
# ONE element 'A,B', and a bare second token after it lands in $ApkPath
# ('debug apk not found: 3B665800EES00000'). Normalize: accept a quoted
# comma-separated string, split it here; never two bare tokens.
$Serials = @($Serials | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })

# ============================================================================
# install-debug-on-phones.ps1 - put the freshly built DEBUG apk on named phones
# and open the app. ASCII only on purpose: PowerShell 5.1 misreads UTF-8 no-BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\install-debug-on-phones.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\install-debug-on-phones.ps1 -Serials 11567254BK001192
#
# Why this script exists:
#   deploy-f4-direct.ps1 is the file-transfer acceptance run - it also creates
#   64 MB and 1 GB demo files, pushes them and checks Wi-Fi. To just LOOK at a
#   UI change on a phone that is far too much. This script only installs and
#   launches.
#
# What it does NOT do:
#   it does not touch app data, does not wipe anything, does not run tests.
#   Install is "adb install -r -t -d" as the project rules require.
#   A debug apk cannot be installed over a release one (different signing key),
#   so if a phone carries a release build the install will fail loudly.
#
# Proof of freshness: after the install the script prints "lastUpdateTime"
# from dumpsys, because logcat markers rotate away.
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'
$AndroidRoot = Join-Path $RepoRoot 'android-app'
$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$Package = 'com.vladimir.messenger'

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $AndroidRoot 'app\build\outputs\apk\debug\app-debug.apk'
}

function Write-Step { param([string]$Text) Write-Output ''; Write-Output "===== $Text =====" }

# adb writes progress and warnings to stderr; under ErrorActionPreference=Stop
# a redirected native stderr can abort the whole script on PowerShell 5.1.
function Invoke-Adb {
    param([string[]]$AdbArgs)
    $Previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $Out = & $Adb @AdbArgs 2>&1
    $ErrorActionPreference = $Previous
    return $Out
}

# ---- 0. prerequisites -------------------------------------------------------
Write-Step 'prerequisites'
if (-not (Test-Path -LiteralPath $Adb)) {
    Write-Output "FATAL: adb not found at $Adb"
    exit 1
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    Write-Output "FATAL: debug apk not found: $ApkPath"
    Write-Output 'Build it first:'
    Write-Output '  powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\groups-build-gate.ps1'
    exit 1
}
$ApkInfo = Get-Item -LiteralPath $ApkPath
Write-Output "apk:    $ApkPath"
Write-Output "size:   $($ApkInfo.Length) bytes"
Write-Output "built:  $($ApkInfo.LastWriteTime)"
Write-Output "phones: $($Serials -join ', ')"

# ---- 1. phones must be visible ---------------------------------------------
Write-Step 'adb devices'
$DeviceLines = @(Invoke-Adb @('devices') | Where-Object { $_ -match 'device$' })
foreach ($Line in $DeviceLines) { Write-Output $Line }
$Missing = @()
foreach ($S in $Serials) {
    if (@($DeviceLines | Where-Object { $_.StartsWith($S) }).Count -eq 0) { $Missing += $S }
}
if ($Missing.Count -gt 0) {
    Write-Output ''
    Write-Output "FATAL: not visible in adb devices: $($Missing -join ', ')"
    Write-Output 'Check the USB cable and the debugging permission on the phone.'
    exit 1
}

# ---- 2. install -------------------------------------------------------------
foreach ($S in $Serials) {
    Write-Step "install on $S"
    $Out = Invoke-Adb @('-s', $S, 'install', '-r', '-t', '-d', $ApkPath)
    $Out | ForEach-Object { Write-Output $_ }
    if ($LASTEXITCODE -ne 0) {
        Write-Output "RESULT: install FAILED on $S"
        exit 1
    }
}

# ---- 3. proof of freshness + launch ----------------------------------------
foreach ($S in $Serials) {
    Write-Step "proof on $S"
    $Dump = Invoke-Adb @('-s', $S, 'shell', 'dumpsys', 'package', $Package) |
        Where-Object { $_ -match 'versionName|lastUpdateTime|firstInstallTime' }
    $Dump | ForEach-Object { Write-Output $_ }
    Invoke-Adb @('-s', $S, 'shell', 'am', 'start', '-n', "$Package/.MainActivity") | Out-Null
    Write-Output "app launched on $S"
}

Write-Output ''
Write-Output 'RESULT: OK - debug apk installed and the app opened.'
Write-Output 'lastUpdateTime above is the proof which build is on the phone.'
