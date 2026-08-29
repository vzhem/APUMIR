param(
    [string]$InviterSerial = '11567254BK001192',
    [string]$InviteeSerial = 'AUYF6R5923006121'
)

# ============================================================================
# referral-proof.ps1 - read-only report: did an invite really raise the rank?
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\referral-proof.ps1
#
# The script changes NOTHING on the phones: it only reads dumpsys, three
# shared_prefs files through run-as and the logcat buffer. Run it after the
# invite flow (invitee opens the inviter link, adds the contact, sends a
# message) to see where the chain stopped if the rank did not move.
#
# What the files mean, from data/referral:
#   apu_referral_qualification.xml -> qualified_direct_count_v1, the REAL
#       counter read by ReferralRankStore.qualifiedDirectCount and therefore by
#       FileTransferRankPolicy, the rank screen and the attachment gating.
#   apu_test_entitlements.xml -> the DEBUG override. While this file exists the
#       override wins and hides the real counter, so an honest referral test
#       starts with scripts\set-rank.ps1 -Clear on both phones.
#   apu_referral_attribution.xml -> inviter_for_<node> and token_for_<node> plus
#       attributed_contacts_v1 on the invitee side, credited_invitees_v1 on the
#       inviter side. This is what makes crediting idempotent. The same file
#       keeps last_rejection_v1, the reason of the most recent refusal, so a
#       rotated logcat buffer cannot hide why an invite was not credited.
#
# Since the signed variant (envelope APUREF1|attr|2) a referral is credited only
# when the invitee carries the inviter signed token, the invitee identity is
# NEWER than that token, and the same identity has not been credited before. An
# identity created before the link was generated is refused with the reason
# "invitee identity is not new" - that is the newcomer rule working, not a bug.
# To see a positive result the invitee phone needs a fresh identity: clear the
# app data (or reinstall), start it, then open the link.
# ============================================================================

$ErrorActionPreference = 'Stop'

$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$Package = 'com.vladimir.messenger'

. (Join-Path $PSScriptRoot 'lib-instrumented-tests.ps1')

if (-not (Test-Path -LiteralPath $Adb)) {
    Write-Output "FATAL: adb not found at $Adb"
    exit 1
}

Write-Output '===== connected devices ====='
& $Adb devices

function Show-Phone {
    param([string]$Serial, [string]$Role)

    Write-Output ''
    Write-Output "===== $Role : $Serial ====="

    if (-not (Invoke-AdbRaw $Serial @('get-state') $Adb | Select-String -Pattern 'device' -SimpleMatch)) {
        Write-Output 'FATAL for this phone: it is not connected, nothing to read.'
        return
    }

    $Dump = Invoke-AdbRaw $Serial @('shell', "dumpsys package $Package") $Adb
    if ("$Dump" -notmatch 'versionName=') {
        Write-Output 'the app is not installed on this phone'
        return
    }
    "$Dump" -split "`n" |
        Select-String -Pattern 'versionName|lastUpdateTime' |
        ForEach-Object { '    ' + $_.ToString().Trim() }

    $Files = @(
        @{ Name = 'real qualified count: apu_referral_qualification.xml'; Path = 'shared_prefs/apu_referral_qualification.xml' },
        @{ Name = 'debug override, must be absent: apu_test_entitlements.xml'; Path = 'shared_prefs/apu_test_entitlements.xml' },
        @{ Name = 'attribution state: apu_referral_attribution.xml'; Path = 'shared_prefs/apu_referral_attribution.xml' }
    )

    foreach ($Item in $Files) {
        Write-Output ('--- ' + $Item.Name)
        $Text = Invoke-AdbRaw $Serial @('shell', "run-as $Package cat " + $Item.Path) $Adb
        $Rows = "$Text" -split "`n" | Where-Object { $_ -match '<' }
        if ($Rows) {
            $Rows | ForEach-Object { '    ' + $_.Trim() }
        }
        else {
            Write-Output '    (file absent - nothing has been written yet)'
        }
    }

    Write-Output '--- last recorded rejection (last_rejection_v1)'
    $Attr = Invoke-AdbRaw $Serial @('shell', "run-as $Package cat shared_prefs/apu_referral_attribution.xml") $Adb
    $Rejection = "$Attr" -split "`n" | Where-Object { $_ -match 'last_rejection_v1' } | Select-Object -First 1
    if ($Rejection) {
        $Value = ($Rejection -replace '.*value="', '') -replace '".*', ''
        Write-Output ('    ' + $Value.Trim())
        $Reason = ($Value -split '\|')[1]
        switch ($Reason) {
            'invitee identity is not new' {
                Write-Output '    -> the invitee identity existed before the link was created.'
                Write-Output '       Newcomer rule. Clear app data on the invitee phone and retry.'
            }
            'unsigned envelope is not credited' {
                Write-Output '    -> a v1 packet arrived. Both phones must run the signed build.'
            }
            'signature or token verification failed' {
                Write-Output '    -> the token is damaged, expired, or signed by another identity.'
            }
            'already credited' {
                Write-Output '    -> this identity was counted before. Reinstall gives a new node id.'
            }
            'self referral' {
                Write-Output '    -> the phone credited itself, one phone cannot be both sides.'
            }
            default {
                Write-Output '    -> see data/referral/ReferralCreditPolicy.kt for the full list.'
            }
        }
    }
    else {
        Write-Output '    (none - no packet was refused on this phone yet)'
    }

    Write-Output '--- referral lines in the log buffer'
    $Log = Invoke-AdbRaw $Serial @('logcat', '-d', '-s', 'ReferralRouter:V', 'ReferralAttribution:V') $Adb
    $LogRows = "$Log" -split "`n" | Where-Object { $_ -match 'Referral' } | Select-Object -Last 12
    if ($LogRows) {
        $LogRows | ForEach-Object { '    ' + $_.Trim() }
    }
    else {
        Write-Output '    (no referral lines yet - the log buffer rotates, this is not a verdict)'
    }
}

Show-Phone $InviterSerial 'inviter'
Show-Phone $InviteeSerial 'invitee'

Write-Output ''
Write-Output 'RESULT: read-only report. A credited invite looks like this:'
Write-Output '  inviter : apu_referral_qualification.xml has qualified_direct_count_v1 value="1"'
Write-Output '  inviter : apu_referral_attribution.xml lists the invitee in credited_invitees_v1'
Write-Output '  inviter : last_rejection_v1 is absent or older than the credited invite'
Write-Output '  invitee : apu_referral_attribution.xml has inviter_for_<node>, token_for_<node>'
Write-Output '            and attributed_contacts_v1'
Write-Output '  both    : apu_test_entitlements.xml is absent, otherwise the debug override'
Write-Output '            is what the rank screen shows and the real counter stays invisible'
Write-Output ''
Write-Output 'NOT CREDITED: read last_rejection_v1 on the inviter phone first. It is the only'
Write-Output 'record that survives logcat rotation, and it names the exact reason.'
