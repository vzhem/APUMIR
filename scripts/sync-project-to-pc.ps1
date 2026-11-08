<#
.SYNOPSIS
    Синхронизация ВСЕГО проекта APUMIR с компьютером (Windows).

.DESCRIPTION
    Кладёт на компьютер полную рабочую копию проекта: ветка main, все ветви и
    теги, вся история. По желанию — bare-зеркало всех ссылок (точная копия
    GitHub) и релизные APK с контрольными суммами.

    Что НЕ переносится (и не должно): android-app\app\build, android-app\.gradle,
    rust-core\target, .idea, android-app\local.properties — это артефакты сборки,
    они создаются заново. Ключ подписи android-app\app\p2p-release.jks лежит в
    репозитории, поэтому клон сразу готов собирать подписанный релизный APK.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\sync-project-to-pc.ps1 -Target D:\APUMIR

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\sync-project-to-pc.ps1 `
        -Target D:\APUMIR -Mirror D:\APUMIR-mirror.git -WithReleases latest

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\sync-project-to-pc.ps1 -Target D:\APUMIR -TrackAllBranches
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [string]$Mirror = "",
    # none | latest | all | число последних релизов
    [string]$WithReleases = "none",
    [string]$ReleaseDir = "",
    [string]$Remote = "https://github.com/vzhem/APUMIR.git",
    [string]$Repo = "vzhem/APUMIR",
    # Неполная история (быстро и меньше диска, но без старых коммитов).
    [switch]$Shallow,
    # Создать локальные ветви для всех ветвей GitHub (по умолчанию только main локально).
    [switch]$TrackAllBranches,
    [switch]$SkipFsck,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$started = Get-Date

function Say([string]$text) { Write-Host $text }
function Warn([string]$text) { Write-Host "ВНИМАНИЕ: $text" -ForegroundColor Yellow }
function Die([string]$text) { Write-Host "ОШИБКА: $text" -ForegroundColor Red; exit 1 }

function Invoke-Step {
    param([string]$FilePath, [string[]]$Arguments)
    if ($DryRun) {
        Say ("  [dry-run] " + $FilePath + " " + ($Arguments -join " "))
        return
    }
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) { Warn ("команда вернула код " + $LASTEXITCODE + ": " + $FilePath + " " + ($Arguments -join " ")) }
}

function Invoke-StepQuiet {
    param([string]$FilePath, [string[]]$Arguments)
    if ($DryRun) {
        Say ("  [dry-run] " + $FilePath + " " + ($Arguments -join " "))
        return
    }
    & $FilePath @Arguments 2>&1 | Out-Null
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Die "не найден git — установите Git for Windows (winget install Git.Git)"
}

switch -Regex ($WithReleases) {
    "^(none|latest|all|[0-9]+)$" { }
    default { Die "-WithReleases принимает none, latest, all или число последних релизов" }
}

Say "APUMIR: синхронизация проекта с компьютером"
Say "  источник:      $Remote"
Say "  рабочая копия: $Target"
if ($Mirror) { Say "  зеркало:       $Mirror" }
if ($Shallow) { Warn "выбран -Shallow: история будет неполной" }
if ($DryRun) { Warn "режим -DryRun: ничего не меняется, только печать команд" }

# ── 1. рабочая копия ─────────────────────────────────────────────────────────
$gitDir = Join-Path $Target ".git"
if ((Test-Path $Target) -and (-not (Test-Path $gitDir)) -and ((Get-ChildItem -Force $Target -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0)) {
    Die "$Target существует, не пуст и не является git-копией — выберите другой путь"
}

Say ""
if (Test-Path $gitDir) {
    Say "[1/5] рабочая копия уже есть — обновляю"
    # SYNC_REPORT.txt от прошлого прогона — служебный файл самого скрипта,
    # он не должен считаться «чужими правками».
    $dirty = (& git -C $Target status --porcelain) | Where-Object { $_ -notmatch "SYNC_REPORT\.txt$" }
    if ($dirty) {
        Warn "в $Target есть незакоммиченные изменения — обновление пропущено, файлы не тронуты"
        Warn "закоммитьте/уберите изменения и запустите снова"
    } else {
        Invoke-Step git @("-C", $Target, "fetch", "--all", "--tags", "--prune")
        $shallowNow = (& git -C $Target rev-parse --is-shallow-repository) -eq "true"
        if ($shallowNow -and (-not $Shallow)) {
            Say "  клон был незавершённым — дотягиваю полную историю"
            Invoke-Step git @("-C", $Target, "fetch", "--unshallow", "--tags")
        }
        Invoke-StepQuiet git @("-C", $Target, "checkout", "main")
        Invoke-StepQuiet git @("-C", $Target, "merge", "--ff-only", "@{u}")
    }
} else {
    Say "[1/5] клонирую проект в $Target"
    if ($Shallow) {
        Invoke-Step git @("clone", "--single-branch", "--depth", "1", "--no-tags", $Remote, $Target)
    } else {
        # --no-single-branch: все ветви приходят как remote-tracking; теги — ниже.
        Invoke-Step git @("clone", "--no-single-branch", $Remote, $Target)
        Invoke-Step git @("-C", $Target, "fetch", "--tags", "--prune")
    }
}

# ── 2. все ветви и теги ──────────────────────────────────────────────────────
Say ""
Say "[2/5] ветви и теги"
if (Test-Path $gitDir) {
    Invoke-StepQuiet git @("-C", $Target, "fetch", "--all", "--tags", "--prune")
    if ($TrackAllBranches -and (-not $DryRun)) {
        Say "  создаю локальные ветви для всех ветвей GitHub (может занять минуту)"
        $refs = & git -C $Target for-each-ref --format="%(refname:short)" refs/remotes/origin
        foreach ($ref in $refs) {
            if ($ref -eq "origin/HEAD") { continue }
            $name = $ref -replace "^origin/", ""
            & git -C $Target show-ref --verify --quiet "refs/heads/$name"
            if ($LASTEXITCODE -ne 0) {
                Invoke-StepQuiet git @("-C", $Target, "branch", "--track", $name, $ref)
            }
        }
    }
}

# ── 3. зеркало всех ссылок ───────────────────────────────────────────────────
Say ""
if ($Mirror) {
    Say "[3/5] зеркало репозитория: $Mirror"
    if (Test-Path $Mirror) {
        Invoke-Step git @("-C", $Mirror, "remote", "update", "--prune")
    } else {
        Invoke-Step git @("clone", "--mirror", $Remote, $Mirror)
    }
} else {
    Say "[3/5] зеркало не запрошено (-Mirror), пропускаю"
}

# ── 4. релизы ────────────────────────────────────────────────────────────────
Say ""
if ($WithReleases -eq "none") {
    Say "[4/5] релизы не запрошены (-WithReleases), пропускаю"
} else {
    Say "[4/5] релизные APK из GitHub Releases ($WithReleases)"
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Warn "не найден gh (GitHub CLI) — релизные файлы пропущены"
        Warn "установите: winget install GitHub.cli, затем gh auth login"
    } else {
        $dest = if ($ReleaseDir) { $ReleaseDir } else { Join-Path $Target "releases" }
        if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $dest | Out-Null }
        $tags = @()
        if ($WithReleases -eq "latest") {
            $one = & gh release view --repo $Repo --json tagName -q ".tagName" 2>$null
            if ($LASTEXITCODE -ne 0 -or -not $one) {
                $one = & gh release list --repo $Repo --limit 1 --json tagName -q ".[0].tagName" 2>$null
            }
            if ($one) { $tags = @($one) }
        } else {
            $limit = if ($WithReleases -eq "all") { 1000 } else { [int]$WithReleases }
            $list = & gh release list --repo $Repo --limit $limit --json tagName -q ".[].tagName" 2>$null
            if ($LASTEXITCODE -eq 0 -and $list) { $tags = @($list) }
        }
        if ($tags.Count -eq 0) {
            Warn "список релизов не получен (gh не авторизован?) — файлы пропущены"
        } else {
            foreach ($tag in $tags) {
                Say "  $tag"
                Invoke-Step gh @("release", "download", $tag, "--repo", $Repo,
                    "--dir", (Join-Path $dest $tag),
                    "--pattern", "*.apk", "--pattern", "SHA256SUMS.txt", "--skip-existing")
            }
        }
        if (-not $DryRun) {
            $sum = (Get-ChildItem -Recurse -File $dest -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
            if (-not $sum) { $sum = 0 }
            Say ("  занято: " + [math]::Round($sum / 1MB, 0) + " МБ")
        }
    }
}

# ── 5. проверка ──────────────────────────────────────────────────────────────
Say ""
Say "[5/5] проверка целостности"
if ((Test-Path $gitDir) -and (-not $DryRun)) {
    if (-not $SkipFsck) { Invoke-Step git @("-C", $Target, "fsck", "--full", "--no-progress") }
    $branches = (& git -C $Target for-each-ref --format="%(refname:short)" refs/remotes/origin |
        Where-Object { $_ -ne "origin/HEAD" } | Measure-Object).Count
    $tags = (& git -C $Target tag --list | Measure-Object).Count
    $commits = (& git -C $Target rev-list --count --all)
    $head = (& git -C $Target rev-parse --short HEAD)
    $gitBytes = (Get-ChildItem -Recurse -File $gitDir | Measure-Object Length -Sum).Sum
    if (-not $gitBytes) { $gitBytes = 0 }
    $gitSize = "{0:N0} МБ" -f ($gitBytes / 1MB)
    Say ""
    Say "ИТОГО"
    Say "  ветвей: $branches, тегов: $tags, коммитов в истории: $commits"
    Say "  текущий коммит: $head, размер .git: $gitSize"
    if ($Mirror -and (Test-Path $Mirror)) {
        $mirrorBytes = (Get-ChildItem -Recurse -File $Mirror | Measure-Object Length -Sum).Sum
        if (-not $mirrorBytes) { $mirrorBytes = 0 }
        Say ("  зеркало: " + [math]::Round($mirrorBytes / 1MB, 0) + " МБ")
    }
    # Отчёт не должен пачкать рабочую копию: убираем его из проверки «грязного» дерева.
    $exclude = Join-Path $gitDir "info\exclude"
    if ((Test-Path (Split-Path $exclude)) -and (-not (Select-String -Path $exclude -Pattern "SYNC_REPORT.txt" -Quiet -ErrorAction SilentlyContinue))) {
        Add-Content -Path $exclude -Value "SYNC_REPORT.txt" -ErrorAction SilentlyContinue
    }
    $report = Join-Path $Target "SYNC_REPORT.txt"
    @(
        "APUMIR sync report",
        ("date: " + (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")),
        ("remote: " + $Remote),
        ("branches: " + $branches),
        ("tags: " + $tags),
        ("commits: " + $commits),
        ("head: " + $head),
        ("git_size: " + $gitSize),
        ("mirror: " + $Mirror),
        ("releases: " + $WithReleases)
    ) | Set-Content -Path $report -Encoding UTF8
    Say "  отчёт: $report"
}

$elapsed = [int]((Get-Date) - $started).TotalSeconds
Say ""
Say "Готово за $elapsed с."
Say "Дальше: откройте $Target в Android Studio (проект android-app) и собирайте как обычно."
