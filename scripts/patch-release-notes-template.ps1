# ============================================================================
# patch-release-notes-template.ps1 - fix the release description template in
# .github/workflows/build-release.yml.
# ASCII only in comments on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\patch-release-notes-template.ps1
#
# Why a script and not a commit: the sandbox bot is not allowed to push files
# under .github/workflows (GitHub App without the "workflows" permission), so
# the owner applies this on the Windows clone and commits it himself.
#
# What is wrong: the template text lost its capital letters somewhere between
# editors ("vtomaticheskaya sborka", "stanovka", "azreshite") and every
# Actions-built release page showed it, v11.69.3 included. The new text keeps
# the same structure; the public notes are still replaced later by
# promote-release.ps1 -NotesFile (docs/RELEASE_PUBLICATION_POLICY.md).
#
# The script is idempotent: run twice, the second run reports "already patched".
# ============================================================================

$ErrorActionPreference = 'Stop'
$RepoRoot = 'C:\APU-M8'
$Path = Join-Path $RepoRoot '.github\workflows\build-release.yml'

if (-not (Test-Path -LiteralPath $Path)) {
    Write-Output "FATAL: workflow not found: $Path"
    exit 1
}

# Read as raw UTF-8 and keep the BOM the file has today.
$Bytes = [System.IO.File]::ReadAllBytes($Path)
$HasBom = ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF)
$Text = [System.Text.Encoding]::UTF8.GetString($Bytes)
if ($HasBom) { $Text = $Text.TrimStart([char]0xFEFF) }

# Old block, exactly as committed (the broken words are intentional here).
$OldLines = @(
    '          body: |',
    '            ## P2P Messenger ${{ steps.get_version.outputs.VERSION }}',
    '',
    ('            ' + [char]0x0432 + [char]0x0442 + [char]0x043E + [char]0x043C + [char]0x0430 + [char]0x0442 + [char]0x0438 + [char]0x0447 + [char]0x0435 + [char]0x0441 + [char]0x043A + [char]0x0430 + [char]0x044F + ' ' + [char]0x0441 + [char]0x0431 + [char]0x043E + [char]0x0440 + [char]0x043A + [char]0x0430 + ' ' + [char]0x0438 + [char]0x0437 + ' ' + [char]0x0442 + [char]0x0435 + [char]0x0433 + [char]0x0430 + '.'),
    '',
    ('            ### ' + [char]0x0441 + [char]0x0442 + [char]0x0430 + [char]0x043D + [char]0x043E + [char]0x0432 + [char]0x043A + [char]0x0430),
    ('            1. ' + [char]0x0421 + [char]0x043A + [char]0x0430 + [char]0x0447 + [char]0x0430 + [char]0x0439 + [char]0x0442 + [char]0x0435 + ' app-release.apk'),
    ('            2. ' + [char]0x0430 + [char]0x0437 + [char]0x0440 + [char]0x0435 + [char]0x0448 + [char]0x0438 + [char]0x0442 + [char]0x0435 + ' ' + [char]0x0443 + [char]0x0441 + [char]0x0442 + [char]0x0430 + [char]0x043D + [char]0x043E + [char]0x0432 + [char]0x043A + [char]0x0443 + ' ' + [char]0x0438 + [char]0x0437 + ' ' + [char]0x043D + [char]0x0435 + [char]0x0438 + [char]0x0437 + [char]0x0432 + [char]0x0435 + [char]0x0441 + [char]0x0442 + [char]0x043D + [char]0x044B + [char]0x0445 + ' ' + [char]0x0438 + [char]0x0441 + [char]0x0442 + [char]0x043E + [char]0x0447 + [char]0x043D + [char]0x0438 + [char]0x043A + [char]0x043E + [char]0x0432),
    ('            3. ' + [char]0x0441 + [char]0x0442 + [char]0x0430 + [char]0x043D + [char]0x043E + [char]0x0432 + [char]0x0438 + [char]0x0442 + [char]0x0435)
)

# New block. Russian is spelled out in code points so this file stays ASCII.
function Ru { param([int[]]$Codes) return -join ($Codes | ForEach-Object { [char]$_ }) }
$Avtomaticheskaya = Ru 0x0410,0x0432,0x0442,0x043E,0x043C,0x0430,0x0442,0x0438,0x0447,0x0435,0x0441,0x043A,0x0430,0x044F
$Sborka           = Ru 0x0441,0x0431,0x043E,0x0440,0x043A,0x0430
$Iz               = Ru 0x0438,0x0437
$Tega             = Ru 0x0442,0x0435,0x0433,0x0430
$Ustanovka        = Ru 0x0423,0x0441,0x0442,0x0430,0x043D,0x043E,0x0432,0x043A,0x0430
$Skachaite        = Ru 0x0421,0x043A,0x0430,0x0447,0x0430,0x0439,0x0442,0x0435
$Razreshite       = Ru 0x0420,0x0430,0x0437,0x0440,0x0435,0x0448,0x0438,0x0442,0x0435
$Ustanovku        = Ru 0x0443,0x0441,0x0442,0x0430,0x043D,0x043E,0x0432,0x043A,0x0443
$Neizvestnykh     = Ru 0x043D,0x0435,0x0438,0x0437,0x0432,0x0435,0x0441,0x0442,0x043D,0x044B,0x0445
$Istochnikov      = Ru 0x0438,0x0441,0x0442,0x043E,0x0447,0x043D,0x0438,0x043A,0x043E,0x0432
$Ustanovite       = Ru 0x0423,0x0441,0x0442,0x0430,0x043D,0x043E,0x0432,0x0438,0x0442,0x0435
$Poverkh          = Ru 0x043F,0x043E,0x0432,0x0435,0x0440,0x0445
$Prezhnei         = Ru 0x043F,0x0440,0x0435,0x0436,0x043D,0x0435,0x0439
$Versii           = Ru 0x0432,0x0435,0x0440,0x0441,0x0438,0x0438
$Perepiska        = Ru 0x043F,0x0435,0x0440,0x0435,0x043F,0x0438,0x0441,0x043A,0x0430
$Sokhranitsya     = Ru 0x0441,0x043E,0x0445,0x0440,0x0430,0x043D,0x0438,0x0442,0x0441,0x044F
$Dash             = [char]0x2014

$NewLines = @(
    '          # Placeholder. Before publishing (promote-release.ps1 -NotesFile) it is',
    '          # replaced with the public notes written by docs/RELEASE_PUBLICATION_POLICY.md:',
    '          #   gh release edit vX.Y.Z --notes-file docs/RELEASE_NOTES_vX.Y.Z.md',
    '          body: |',
    '            ## APU ${{ steps.get_version.outputs.VERSION }}',
    '',
    "            $Avtomaticheskaya $Sborka $Iz $Tega.",
    '',
    "            ### $Ustanovka",
    "            1. $Skachaite app-release.apk.",
    "            2. $Razreshite $Ustanovku $Iz $Neizvestnykh $Istochnikov.",
    "            3. $Ustanovite $Poverkh $Prezhnei $Versii $Dash $Perepiska $Sokhranitsya."
)

# The file uses LF or CRLF; detect and keep it.
$Nl = "`n"
if ($Text.Contains("`r`n")) { $Nl = "`r`n" }
$OldBlock = ($OldLines -join $Nl)
$NewBlock = ($NewLines -join $Nl)

if ($Text.Contains($NewBlock)) {
    Write-Output 'already patched - nothing to do.'
    exit 0
}
if (-not $Text.Contains($OldBlock)) {
    Write-Output 'FATAL: the old template block was not found - the workflow changed upstream.'
    Write-Output 'Open .github/workflows/build-release.yml and fix the "body:" text by hand.'
    exit 1
}

$Text = $Text.Replace($OldBlock, $NewBlock)
$Utf8 = New-Object System.Text.UTF8Encoding($HasBom)
[System.IO.File]::WriteAllText($Path, $Text, $Utf8)

Write-Output 'patched .github/workflows/build-release.yml'
Write-Output 'Check:  git -C C:\APU-M8 diff .github/workflows/build-release.yml'
Write-Output 'Commit: git -C C:\APU-M8 commit -am "ci: fix the release description template"'
Write-Output 'Push:   git -C C:\APU-M8 push'
exit 0
