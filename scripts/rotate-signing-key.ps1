param(
    [switch]$DryRun
)

# ============================================================================
# rotate-signing-key.ps1 - replace the public release signing key with a
# private one, without breaking updates on the phones.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\rotate-signing-key.ps1 -DryRun
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\rotate-signing-key.ps1
#
# Why. android-app/app/p2p-release.jks and its password sit in the PUBLIC
# repository (build.gradle.kts). Anyone can sign an APK "as APU" and a phone
# will accept it as an update of the real app. A new key alone would make every
# phone refuse the update ("signature mismatch") - people would have to
# uninstall and lose their chats. Android's key rotation (signing lineage,
# Android 9+) solves this: the OLD key certifies the NEW one once, the file
# with that proof ships inside every APK, and phones accept the new key.
#
# What the script does (docs/SIGNING_KEY_ROTATION.md explains each step):
#   1. finds keytool and apksigner in the Android Studio / SDK installation;
#   2. creates C:\APU-KEYS\apu-release-2026.jks with a random password
#      (the folder is OUTSIDE the repository - never commit it);
#   3. runs "apksigner rotate": old key + new key -> android-app\app\apu-signing-lineage
#      (this file is public by design and IS committed);
#   4. stores the new keystore, its password and alias as GitHub Actions
#      secrets APU_RELEASE_KEYSTORE_B64 / _PASSWORD / _KEY_ALIAS with gh;
#   5. prints the new certificate fingerprint for tools/worker/p2p_relay_worker.js
#      and the commands to commit.
#
# It does NOT tag, release, push or touch the phones.
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'
$KeyDir = 'C:\APU-KEYS'
$NewKs = Join-Path $KeyDir 'apu-release-2026.jks'
$PassFile = Join-Path $KeyDir 'apu-release-2026.password.txt'
$NewAlias = 'apu2026'
$OldKs = Join-Path $RepoRoot 'android-app\app\p2p-release.jks'
$OldPass = 'p2p2026release'
$OldAlias = 'p2p'
$Lineage = Join-Path $RepoRoot 'android-app\app\apu-signing-lineage'

function Write-Step { param([string]$Text) Write-Output ''; Write-Output "===== $Text =====" }

# ---- 1. tools ---------------------------------------------------------------
Write-Step '1. tools'
if (-not (Test-Path -LiteralPath $OldKs)) { Write-Output "FATAL: old keystore not found: $OldKs"; exit 1 }

$Keytool = $null
$Candidates = @(
    (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe'),
    (Join-Path $env:ProgramFiles 'Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\keytool.exe')
)
if ($env:JAVA_HOME) { $Candidates = @((Join-Path $env:JAVA_HOME 'bin\keytool.exe')) + $Candidates }
$Adoptium = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
if (Test-Path $Adoptium) {
    Get-ChildItem $Adoptium -Directory | ForEach-Object { $Candidates += (Join-Path $_.FullName 'bin\keytool.exe') }
}
foreach ($c in $Candidates) { if ($c -and (Test-Path -LiteralPath $c)) { $Keytool = $c; break } }
if (-not $Keytool) {
    $Cmd = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($Cmd) { $Keytool = $Cmd.Source }
}
if (-not $Keytool) { Write-Output 'FATAL: keytool.exe not found (Android Studio jbr or a JDK).'; exit 1 }
Write-Output "keytool:   $Keytool"

$SdkRoot = $env:ANDROID_HOME
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$LocalProps = Join-Path $RepoRoot 'android-app\local.properties'
if ((-not (Test-Path $SdkRoot)) -and (Test-Path $LocalProps)) {
    $Line = Get-Content $LocalProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($Line) { $SdkRoot = ($Line -replace '^sdk\.dir=', '') -replace '\\\\', '\' -replace '\\:', ':' }
}
$BuildTools = Join-Path $SdkRoot 'build-tools'
if (-not (Test-Path $BuildTools)) { Write-Output "FATAL: build-tools not found under $SdkRoot"; exit 1 }
$Apksigner = Get-ChildItem $BuildTools -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Apksigner) { Write-Output "FATAL: apksigner.bat not found in $BuildTools"; exit 1 }
Write-Output "apksigner: $Apksigner"

$Gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $Gh) { Write-Output 'FATAL: the GitHub CLI (gh) was not found on PATH.'; exit 1 }
& gh auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Output 'FATAL: gh is not signed in. Run: gh auth login'; exit 1 }
Write-Output 'gh:        signed in'

# ---- 2. the new key ---------------------------------------------------------
Write-Step '2. new keystore'
if (Test-Path -LiteralPath $Lineage) {
    Write-Output "FATAL: $Lineage already exists - the key was rotated before."
    Write-Output 'A second rotation needs "apksigner rotate --in <old lineage>"; do not run this script again.'
    exit 1
}
if (Test-Path -LiteralPath $NewKs) {
    Write-Output "FATAL: $NewKs already exists. Delete it only if you are SURE it was never used for a release."
    exit 1
}
if ($DryRun) {
    Write-Output "DRY RUN: would create $NewKs (alias $NewAlias, RSA 4096, valid 30 years),"
    Write-Output "         write $Lineage, and set three GitHub secrets."
    exit 0
}
New-Item -ItemType Directory -Path $KeyDir -Force | Out-Null

# 32 random characters from a safe alphabet: no quotes, no spaces, no '$'.
$Alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
$Rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
$Bytes = New-Object byte[] 32
$Rng.GetBytes($Bytes)
$NewPass = -join ($Bytes | ForEach-Object { $Alphabet[$_ % $Alphabet.Length] })
[System.IO.File]::WriteAllText($PassFile, $NewPass, (New-Object System.Text.UTF8Encoding($false)))

& $Keytool -genkeypair -v -keystore $NewKs -storetype PKCS12 -alias $NewAlias `
    -keyalg RSA -keysize 4096 -validity 10950 `
    -storepass $NewPass -keypass $NewPass `
    -dname 'CN=APU, O=APU, C=RU' | Out-Null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $NewKs)) { Write-Output 'FATAL: keytool failed.'; exit 1 }
Write-Output "created:   $NewKs"
Write-Output "password:  $PassFile  (keep both OUTSIDE the repository)"

# ---- 3. the lineage: old key certifies the new one ---------------------------
Write-Step '3. signing lineage'
& $Apksigner rotate --out $Lineage `
    --old-signer --ks $OldKs --ks-pass "pass:$OldPass" --ks-key-alias $OldAlias --key-pass "pass:$OldPass" `
    --new-signer --ks $NewKs --ks-pass "pass:$NewPass" --ks-key-alias $NewAlias --key-pass "pass:$NewPass"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $Lineage)) { Write-Output 'FATAL: apksigner rotate failed.'; exit 1 }
Write-Output "written:   $Lineage"
& $Apksigner lineage --print-certs -v --in $Lineage

# ---- 4. GitHub secrets ------------------------------------------------------
Write-Step '4. GitHub Actions secrets'
$B64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($NewKs))
$B64 | & gh secret set APU_RELEASE_KEYSTORE_B64 --repo vzhem/APUMIR
if ($LASTEXITCODE -ne 0) { Write-Output 'FATAL: could not set APU_RELEASE_KEYSTORE_B64'; exit 1 }
$NewPass | & gh secret set APU_RELEASE_KEYSTORE_PASSWORD --repo vzhem/APUMIR
if ($LASTEXITCODE -ne 0) { Write-Output 'FATAL: could not set APU_RELEASE_KEYSTORE_PASSWORD'; exit 1 }
$NewAlias | & gh secret set APU_RELEASE_KEY_ALIAS --repo vzhem/APUMIR
if ($LASTEXITCODE -ne 0) { Write-Output 'FATAL: could not set APU_RELEASE_KEY_ALIAS'; exit 1 }
& gh secret list --repo vzhem/APUMIR

# ---- 5. what to do next -----------------------------------------------------
Write-Step '5. fingerprint of the NEW certificate (for the worker)'
& $Keytool -list -v -keystore $NewKs -storepass $NewPass -alias $NewAlias | Select-String 'SHA256:'
Write-Output ''
Write-Output 'NEXT (see docs/SIGNING_KEY_ROTATION.md):'
Write-Output '  1. add the SHA256 above as the SECOND element of RELEASE_CERT_SHA256S in'
Write-Output '     tools/worker/p2p_relay_worker.js and publish the worker;'
Write-Output '  2. commit the lineage:'
Write-Output '     git -C C:\APU-M8 add android-app/app/apu-signing-lineage'
Write-Output '     git -C C:\APU-M8 commit -m "release: rotate the signing key (lineage)"'
Write-Output '  3. copy C:\APU-KEYS to the backup flash drive; without this folder no'
Write-Output '     future release can be signed.'
exit 0
