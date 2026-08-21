$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$Binding = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$Native = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$Apk = "android-app/app/build/outputs/apk/debug/app-debug.apk"
$State = Join-Path $env:TEMP "apu-file-exchange-binding-build-state.json"
$RemoteRef = "refs/heads/arena/01a0149e-apumir"
$ExpectedState = "886738235D42B5FD475E004D00F58D02919311EAEEFF0D4C1F5D4704240F7A6E"
$ExpectedNative = "81384E504737D9531ABC6C81D70F3225B2D30574C23E00587EBD5772779AD790"
$ExpectedBinding = "8C0BB329466B44ECDE6F5DFFD8212EC7CBF69C1BC0696AFB752BE87551AD9304"
$ExpectedApk = "5B570E4A7A3808D7F34A352E0D8A2B2D3DEFE984061FE153E388160B8A839A42"

if ((Get-FileHash $State -Algorithm SHA256).Hash -ne $ExpectedState -or
    (Get-FileHash $Native -Algorithm SHA256).Hash -ne $ExpectedNative -or
    (Get-FileHash $Binding -Algorithm SHA256).Hash -ne $ExpectedBinding -or
    (Get-FileHash $Apk -Algorithm SHA256).Hash -ne $ExpectedApk) {
    throw "Evidence/artifact mismatch"
}
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 2 -or $Status[0] -ne " M $Binding" -or $Status[1] -ne " M $Native") {
    throw "Unexpected worktree"
}
$Text = Get-Content $Binding -Raw
foreach ($Marker in @(
    "createFileExchangeBinding",
    "verifyFileExchangeBinding",
    "fileExchangeBindingNodeId",
    "fileExchangeBindingPublicKey"
)) {
    if ($Text -notmatch $Marker) { throw "Generated binding marker missing: $Marker" }
}
& git diff --check -- $Binding
if ($LASTEXITCODE -ne 0) { throw "Binding whitespace failure" }
& git add -- $Binding
$Cached = @(& git diff --cached --name-only)
if ($Cached.Count -ne 1 -or $Cached[0] -ne $Binding) { throw "Only binding may be committed" }
& git commit -m "chore: regenerate file exchange UniFFI binding"
if ($LASTEXITCODE -ne 0) { throw "Binding commit failed" }

$Head = ((& git rev-parse HEAD) -join "").Trim()
$Parent = ((& git rev-parse "HEAD^") -join "").Trim()
$OldPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$RemoteLine = git ls-remote origin $RemoteRef
$QueryCode = $LASTEXITCODE
$ErrorActionPreference = $OldPreference
if ($QueryCode -ne 0 -or [string]::IsNullOrWhiteSpace($RemoteLine)) {
    throw "GitHub unavailable; local binding commit remains safe"
}
$RemoteBefore = ($RemoteLine.Trim() -split "\s+")[0]
if ($RemoteBefore -ne $Parent) { throw "Remote advanced; push not attempted" }
$OldPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& git push origin "HEAD:$RemoteRef"
$PushCode = $LASTEXITCODE
$ErrorActionPreference = $OldPreference
if ($PushCode -ne 0) { throw "Push failed; local binding commit remains safe" }
$RemoteAfter = ((git ls-remote origin $RemoteRef).Trim() -split "\s+")[0]
if ($RemoteAfter -ne $Head) { throw "Remote verification failed" }

Write-Host "Remote binding commit: $Head"
Write-Host "Binding: $ExpectedBinding"
Write-Host "Native remains uncommitted: $ExpectedNative"
Write-Host "FILE EXCHANGE BINDING ACCEPT PASS"
