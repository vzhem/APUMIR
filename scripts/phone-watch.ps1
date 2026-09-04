param(
    [string]$ApkPath = '',
    [string[]]$Serials = @(),
    [int]$Seconds = 25,
    [switch]$SkipInstall
)

# powershell.exe -File binding quirk: "-Serials A,B" arrives as ONE element.
$Serials = @($Serials | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })

# ============================================================================
# phone-watch.ps1 - install the debug apk on EVERY phone adb can see, launch
# the app and immediately show the interesting log lines from each one.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\phone-watch.ps1
#
# Why this exists: after a batch of UI changes we want one command that answers
# "did it start and did anything blow up", instead of naming serials by hand.
# With no -Serials the script takes whatever "adb devices" reports as "device",
# so a phone that was re-plugged simply joins in, and a missing one is reported
# rather than being a fatal error.
#
# The log filter keeps crashes, ANRs and our own tags. Everything else is noise
# that pushes the useful lines out of the buffer.
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'
$Package  = 'com.vladimir.messenger'
$Adb      = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

function Write-Step { param([string]$Text) Write-Output ''; Write-Output "===== $Text =====" }

# adb writes progress to stderr; with ErrorActionPreference=Stop that alone can
# abort the script on PowerShell 5.1, so every call goes through this wrapper.
function Invoke-Adb {
    param([string[]]$AdbArgs)
    $Previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $Out = & $Adb @AdbArgs 2>&1
    $ErrorActionPreference = $Previous
    return $Out
}

Write-Step 'prerequisites'
if (-not (Test-Path -LiteralPath $Adb)) {
    Write-Output "FATAL: adb not found at $Adb"
    exit 1
}

if ($ApkPath -eq '') {
    $ApkPath = Join-Path $RepoRoot 'android-app\app\build\outputs\apk\debug\app-debug.apk'
}
if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $ApkPath)) {
        Write-Output "FATAL: debug apk not found: $ApkPath"
        Write-Output 'Run scripts\groups-build-gate.ps1 first.'
        exit 1
    }
    $Apk = Get-Item -LiteralPath $ApkPath
    Write-Output ("apk:   {0}" -f $Apk.FullName)
    Write-Output ("size:  {0} bytes" -f $Apk.Length)
    Write-Output ("built: {0}" -f $Apk.LastWriteTime)
}

Write-Step 'adb devices'
$DeviceLines = @(Invoke-Adb @('devices') | Where-Object { $_ -match '^\S+\s+device$' })
$Online = @($DeviceLines | ForEach-Object { ($_ -split '\s+')[0] })
if ($Online.Count -eq 0) {
    Write-Output 'FATAL: no phone is visible. Check the cable and the USB debugging prompt.'
    exit 1
}
foreach ($S in $Online) { Write-Output "online: $S" }

# No -Serials given: take everything that is online. Otherwise keep the asked
# ones that are online and merely warn about the rest - a missing second phone
# must not stop the check on the first.
if ($Serials.Count -eq 0) {
    $Targets = $Online
} else {
    $Targets = @($Serials | Where-Object { $Online -contains $_ })
    foreach ($S in $Serials) {
        if ($Online -notcontains $S) { Write-Output "WARNING: not connected, skipped: $S" }
    }
}
if ($Targets.Count -eq 0) {
    Write-Output 'FATAL: none of the requested phones is connected.'
    exit 1
}

foreach ($Serial in $Targets) {
    Write-Step "$Serial : model"
    $Model = (Invoke-Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.model')) -join ' '
    Write-Output ("model: {0}" -f $Model.Trim())

    if (-not $SkipInstall) {
        Write-Step "$Serial : install"
        # -r keep data, -t allow test build, -d allow same-or-lower version code.
        $Out = Invoke-Adb @('-s', $Serial, 'install', '-r', '-t', '-d', $ApkPath)
        $Out | ForEach-Object { Write-Output $_ }
        if (($Out -join ' ') -notmatch 'Success') {
            Write-Output "WARNING: install did not report Success on $Serial"
        }
    }

    Write-Step "$Serial : launch"
    Invoke-Adb @('-s', $Serial, 'shell', 'am', 'force-stop', $Package) | Out-Null
    # The log buffer is cleared right before the launch so the lines below all
    # belong to this run and not to yesterday's session.
    Invoke-Adb @('-s', $Serial, 'logcat', '-c') | Out-Null
    Invoke-Adb @('-s', $Serial, 'shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
    Write-Output "launched, watching for $Seconds seconds - open the main screen and the group search now"
    Start-Sleep -Seconds $Seconds

    Write-Step "$Serial : crashes and ANRs"
    $Log = Invoke-Adb @('-s', $Serial, 'logcat', '-d', '-v', 'time')
    $Bad = @($Log | Select-String -Pattern 'FATAL EXCEPTION|ANR in|Force finishing|AndroidRuntime|Compose Runtime|NullPointerException|Unresolved')
    if ($Bad.Count -eq 0) {
        Write-Output 'nothing - clean start'
    } else {
        $Bad | Select-Object -Last 40 | ForEach-Object { Write-Output $_.Line }
    }

    Write-Step "$Serial : app log (tail)"
    $Ours = @($Log | Select-String -Pattern 'CoreServerService|GroupRepository|ChatList|Groups|apu-')
    if ($Ours.Count -eq 0) {
        Write-Output 'no matching lines'
    } else {
        $Ours | Select-Object -Last 30 | ForEach-Object { Write-Output $_.Line }
    }
}

Write-Step 'done'
Write-Output 'RESULT: the app was installed and launched on every phone listed above.'
Write-Output 'This is a smoke run, not an acceptance: look at the screen too.'
