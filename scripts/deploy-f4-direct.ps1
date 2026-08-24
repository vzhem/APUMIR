param(
    [switch]$CollectLogs
)

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'
$AndroidRoot = Join-Path $RepoRoot 'android-app'
$Gradlew = Join-Path $AndroidRoot 'gradlew.bat'
$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$RequiredCommit = '60b20afc37a4a772a07188acfd96a78d233cc54c'
$StasSerial = '11567254BK001192'
$AnyaSerial = 'AUYF6R5923006121'
$Package = 'com.vladimir.messenger'

if ($CollectLogs) {
    foreach ($S in @($StasSerial, $AnyaSerial)) {
        Write-Output "===== $S ====="
        & $Adb -s $S logcat -d -v time | Select-String -Pattern 'FileTransfer|APULAN|lan-direct|Direct file|Exception|FATAL' | Select-Object -Last 40
    }
    exit 0
}

Write-Output 'BLOCK STARTED'

if (-not (Test-Path -LiteralPath $Adb)) { throw "adb not found: $Adb" }
if (-not (Test-Path -LiteralPath $Gradlew)) { throw "gradlew not found: $Gradlew" }

Set-Location -LiteralPath $RepoRoot
git merge-base --is-ancestor $RequiredCommit HEAD
if ($LASTEXITCODE -ne 0) {
    throw "Repo HEAD does not contain commit $RequiredCommit. Run first: git -c http.version=HTTP/1.1 pull --ff-only origin arena/01a0290d-apumir"
}
$Head = (git rev-parse HEAD) | Select-Object -First 1
Write-Output "Repo HEAD: $Head"
$Dirty = @(git status --porcelain=v1 --untracked-files=no)
if ($Dirty.Count -ne 0) { throw "Worktree is dirty: $($Dirty -join '; ')" }

$Stamp = Get-Date -Format 'HHmmss'
$LogRoot = Join-Path $env:TEMP ('apu-f4-deploy-' + $Stamp)
New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null
Write-Output "Evidence: $LogRoot"

function Invoke-Step {
    param([string]$Label, [string]$FilePath, [string[]]$ArgumentList, [string]$WorkingDirectory)
    $Out = Join-Path $LogRoot ($Label + '.stdout.log')
    $Err = Join-Path $LogRoot ($Label + '.stderr.log')
    Write-Output "=== $Label ==="
    $Proc = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory -RedirectStandardOutput $Out -RedirectStandardError $Err -Wait -PassThru
    if (Test-Path -LiteralPath $Out) { Get-Content -LiteralPath $Out -Tail 50 | Write-Output }
    if (Test-Path -LiteralPath $Err) { Get-Content -LiteralPath $Err -Tail 20 | Write-Output }
    if ($Proc.ExitCode -ne 0) { throw "$Label failed with exit code $($Proc.ExitCode). Logs: $Out ; $Err" }
}

Write-Output '=== UNIT TESTS (may stay silent up to ~3 min) ==='
Invoke-Step -Label 'unit-tests' -FilePath $env:ComSpec -WorkingDirectory $AndroidRoot -ArgumentList @('/c', $Gradlew, '--no-daemon', ':app:testDebugUnitTest', '--tests', 'com.vladimir.messenger.data.file.*')

Write-Output '=== BUILDS (may stay silent up to ~4 min) ==='
$AppApk = Join-Path $AndroidRoot 'app\build\outputs\apk\debug\app-debug.apk'
$TestApk = Join-Path $AndroidRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
Invoke-Step -Label 'builds' -FilePath $env:ComSpec -WorkingDirectory $AndroidRoot -ArgumentList @('/c', $Gradlew, '--no-daemon', ':app:assembleDebug', ':app:assembleDebugAndroidTest')
if (-not (Test-Path -LiteralPath $AppApk)) { throw "app apk missing: $AppApk" }
if (-not (Test-Path -LiteralPath $TestApk)) { throw "test apk missing: $TestApk" }

Write-Output '=== PHONES + INSTALL ==='
$DeviceLines = @(& $Adb devices | Where-Object { $_ -match 'device$' })
foreach ($S in @($StasSerial, $AnyaSerial)) {
    if (@($DeviceLines | Where-Object { $_.StartsWith($S) }).Count -eq 0) { throw "Phone not visible in adb devices: $S" }
}
foreach ($S in @($StasSerial, $AnyaSerial)) {
    Write-Output "--- install on $S ---"
    & $Adb -s $S install -r -t -d $AppApk
    if ($LASTEXITCODE -ne 0) { throw "app install failed on $S" }
    & $Adb -s $S install -r -t -d $TestApk
    if ($LASTEXITCODE -ne 0) { throw "test apk install failed on $S" }
}

Write-Output '=== DEMO FILES (64 MB + 1 GB, push takes up to ~2 min) ==='
$File64 = Join-Path $LogRoot 'apu-demo-64mb.bin'
$File1g = Join-Path $LogRoot 'apu-demo-1gb.bin'
$Stream1 = [System.IO.File]::Create($File64)
$Stream1.SetLength(64MB)
$Stream1.Close()
$Stream2 = [System.IO.File]::Create($File1g)
$Stream2.SetLength(1GB)
$Stream2.Close()
Write-Output ("Created local demo files: " + (Get-Item -LiteralPath $File64).Length + " and " + (Get-Item -LiteralPath $File1g).Length + " bytes")
& $Adb -s $StasSerial shell df -h /sdcard
& $Adb -s $StasSerial push $File64 /sdcard/Download/apu-demo-64mb.bin
if ($LASTEXITCODE -ne 0) { throw 'push 64mb failed' }
& $Adb -s $StasSerial push $File1g /sdcard/Download/apu-demo-1gb.bin
if ($LASTEXITCODE -ne 0) { throw 'push 1gb failed' }
Write-Output 'Demo files are now in /sdcard/Download/ on the Stas phone.'

Write-Output '=== WI-FI CHECK ==='
$StasIpOut = (& $Adb -s $StasSerial shell ip -f inet addr show wlan0) -join ' '
$AnyaIpOut = (& $Adb -s $AnyaSerial shell ip -f inet addr show wlan0) -join ' '
if ($StasIpOut -notmatch 'inet\s+(\d+\.\d+\.\d+\.\d+)/') { throw 'Stas phone is not on Wi-Fi (no IPv4 on wlan0).' }
$StasIp = $Matches[1]
if ($AnyaIpOut -notmatch 'inet\s+(\d+\.\d+\.\d+\.\d+)/') { throw 'Anya phone is not on Wi-Fi (no IPv4 on wlan0).' }
$AnyaIp = $Matches[1]
Write-Output "Stas Wi-Fi IP: $StasIp ; Anya Wi-Fi IP: $AnyaIp"
$StasSub = ($StasIp.Split('.')[0..2] -join '.')
$AnyaSub = ($AnyaIp.Split('.')[0..2] -join '.')
if ($StasSub -ne $AnyaSub) { throw "Phones are on different subnets: $StasSub vs $AnyaSub" }

Write-Output '=== LAUNCH APPS ==='
& $Adb -s $StasSerial shell am force-stop $Package
& $Adb -s $AnyaSerial shell am force-stop $Package
Start-Sleep -Seconds 2
& $Adb -s $StasSerial shell am start -n "$Package/.MainActivity"
& $Adb -s $AnyaSerial shell am start -n "$Package/.MainActivity"
Write-Output 'Waiting 20 seconds for engines and LAN servers to start...'
Start-Sleep -Seconds 20

Write-Output ''
Write-Output '=========================================================='
Write-Output 'F4 IN-APP DIRECT TRANSFER DEPLOYED - READY FOR MANUAL SEND'
Write-Output '=========================================================='
Write-Output "Stas phone (TECNO LI6): $StasSerial"
Write-Output "Anya phone (MTN NX1):   $AnyaSerial"
Write-Output 'Demo files on the Stas phone in /sdcard/Download/:'
Write-Output '  apu-demo-64mb.bin'
Write-Output '  apu-demo-1gb.bin'
Write-Output "Evidence dir: $LogRoot"
Write-Output 'MANUAL NEXT STEP: on the Stas phone open the chat with Anya,'
Write-Output 'attach /sdcard/Download/apu-demo-64mb.bin via the file picker, and send.'
Write-Output 'After the send attempt run:'
Write-Output '  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-f4-direct.ps1 -CollectLogs'
