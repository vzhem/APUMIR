$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$Binding = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$Native = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$Apk = "android-app/app/build/outputs/apk/debug/app-debug.apk"
$State = Join-Path $env:TEMP "apu-file-key-envelope-build-state.json"
$RemoteRef = "refs/heads/arena/01a0149e-apumir"
$ExpectedState = "31ED6BB04E1F6108757CFDF01F4810E58614C63001F7F9C174CBF79AB6FEAAC9"
$ExpectedNative = "1096A43AAEC377D33BDAEA4CD887658CC809FED5C5C49C21AB24FD695FD5B259"
$ExpectedBinding = "734B1F690812C3C41AFDAD065757821C6C6B280345AD49A5C57A2472DFAD56DC"
$ExpectedApk = "6F23AF5F49A908CCA231169CA8C21F846F6DEAC6ED06877BD77D132A48AEB0CE"

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
    "createFileKeyEnvelope",
    "openFileKeyEnvelope"
)) {
    if ($Text -notmatch $Marker) { throw "Generated binding marker missing: $Marker" }
}
& git diff --check -- $Binding
if ($LASTEXITCODE -ne 0) { throw "Binding whitespace failure" }
& git add -- $Binding
$Cached = @(& git diff --cached --name-only)
if ($Cached.Count -ne 1 -or $Cached[0] -ne $Binding) { throw "Only binding may be committed" }
& git commit -m "chore: regenerate file key envelope binding"
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
Write-Host "FILE KEY ENVELOPE ACCEPT PASS"
