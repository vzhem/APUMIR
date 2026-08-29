# ============================================================================
# backup-to-flash.ps1 - refresh the APUMIR backup on the white flash drive.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\backup-to-flash.ps1
#
# The flash is found BY LABEL (APU_BACKUP), so the drive letter may change.
# It mirrors the repo (minus regenerable build folders), rewrites the
# single-file bundle and verifies it. Round 46, owner's standing wish:
# after every big update the agent offers "insert the white flash and run
# this one command".
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'

$Vol = Get-Volume -FileSystemLabel 'APU_BACKUP' -ErrorAction SilentlyContinue
if ($null -eq $Vol -or [string]::IsNullOrEmpty($Vol.DriveLetter)) {
    Write-Output 'FLASH NOT FOUND: insert the white flash drive labelled APU_BACKUP'
    Write-Output 'and run this script again.'
    exit 1
}
$Drive = $Vol.DriveLetter + ':'
Write-Output "flash found: $Drive (label $($Vol.FileSystemLabel))"

# ---- mirror the repo --------------------------------------------------------
Write-Output ''
Write-Output '===== robocopy repo -> flash ====='
$Dest = Join-Path $Drive 'APU-BACKUP\APUMIR'
& robocopy $RepoRoot $Dest /MIR /XD build .gradle .idea .kotlin target captures /XF *.apk
$Rc = $LASTEXITCODE
Write-Output "robocopy exit code: $Rc (0-8 is fine, above 8 is an error)"
if ($Rc -gt 8) {
    Write-Output 'RESULT: ROBOCOPY FAILED - backup NOT updated.'
    exit 1
}

# ---- single-file bundle of the whole repo -----------------------------------
Write-Output ''
Write-Output '===== rewriting apumir-full.bundle ====='
$Bundle = Join-Path $Drive 'APU-BACKUP\apumir-full.bundle'
& git -C $RepoRoot bundle create $Bundle --all
if ($LASTEXITCODE -ne 0) {
    Write-Output 'RESULT: BUNDLE FAILED.'
    exit 1
}

# ---- verify what we just wrote ----------------------------------------------
Write-Output ''
Write-Output '===== verify bundle ====='
& git -C $RepoRoot bundle verify $Bundle
if ($LASTEXITCODE -ne 0) {
    Write-Output 'RESULT: BUNDLE VERIFY FAILED.'
    exit 1
}

Write-Output ''
Write-Output "RESULT: OK - backup refreshed on $Drive (APU-BACKUP folder + apumir-full.bundle)."
