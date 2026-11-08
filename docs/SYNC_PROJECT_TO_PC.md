# Синхронизация проекта с компьютером

Запрос владельца (2026-11-08): «мне нужно весь проект синхронизировать с компьютером.
чтобы на компьютере было всё».

Источник истины — GitHub-репозиторий `vzhem/APUMIR`. Всё, что сделано в любой сессии
(ветки `arena/*`, теги выпусков, документы, скрипты, ключ подписи), уже лежит там;
на компьютер это переносится **клоном**, а не копированием папок. Скрипты ниже делают
это одной командой и печатают отчёт.

## Что значит «всё»

| Что | Откуда | Где на компьютере | Размер |
|---|---|---|---|
| Код (`android-app`, `rust-core`, `website`, `tools`, `scripts`) | git | `<цель>` (рабочая копия) | ~70 МБ в рабочем виде |
| Вся история и **все ветви** (≈92 ветки: `main`, `arena/*`, `archive/*`) | git | `refs/remotes/origin/*` в клоне | входит в `.git` |
| **Все теги** (658: `v11.74.x`, `v11.16.x` …) | git | `git tag` в клоне | входит в `.git` |
| Ключ подписи релиза `android-app/app/p2p-release.jks` | git (в репозитории) | `android-app/app/` | 2,7 КБ |
| Документы (`docs/`, включая заметки выпусков) | git | `docs/` | ~6 МБ |
| Релизные APK и `SHA256SUMS.txt` | GitHub Releases | `releases/<тег>/` (по желанию) | ~77 МБ за выпуск |
| Зеркало всех ссылок (bare) | git | `<зеркало>` (по желанию) | ≈ размер `.git` |

**Не переносится и не должно** (это артефакты сборки, они создаются заново):
`android-app/app/build`, `android-app/.gradle`, `android-app/.kotlin`, `rust-core/target`,
`tools/uniffi-bindgen/target`, `.idea`, `android-app/local.properties`.
Журналы прошлых сборок (`android-app/build_log.txt`, `hs_err_pid*.log`) в репозиторий
не входят по тем же причинам.

> **Безопасность.** В репозитории лежит ключ подписи и параметры подписи проекта.
> Компьютер с клоном — это компьютер с ключом подписи: не выкладывайте папку проекта в
> облачные диски с общим доступом и не передавайте её третьим лицам.

## Способ 1: скрипт (рекомендую)

Windows (PowerShell):

```powershell
# одна рабочая копия со всей историей, ветвями и тегами
powershell -ExecutionPolicy Bypass -File scripts\sync-project-to-pc.ps1 -Target D:\APUMIR

# то же + зеркало всех ссылок + последний релизный APK с суммами
powershell -ExecutionPolicy Bypass -File scripts\sync-project-to-pc.ps1 `
    -Target D:\APUMIR -Mirror D:\APUMIR-mirror.git -WithReleases latest

# все ветви ещё и локальными ветвями (по умолчанию локально только main)
powershell -ExecutionPolicy Bypass -File scripts\sync-project-to-pc.ps1 -Target D:\APUMIR -TrackAllBranches

# только посмотреть, что будет сделано
powershell -ExecutionPolicy Bypass -File scripts\sync-project-to-pc.ps1 -Target D:\APUMIR -DryRun
```

Linux / macOS / WSL:

```bash
bash scripts/sync-project-to-pc.sh --target ~/APUMIR
bash scripts/sync-project-to-pc.sh --target ~/APUMIR --mirror ~/APUMIR-mirror.git --releases latest
bash scripts/sync-project-to-pc.sh --target ~/APUMIR --track-all-branches
```

Скрипт идемпотентен: первый запуск клонирует, следующие — обновляют
(`fetch --all --tags --prune`, `main` fast-forward) и **не трогают** локальные изменения:
если в рабочей копии есть незакоммиченные правки, обновление пропускается с
предупреждением. В конце печатается сводка и пишется `SYNC_REPORT.txt`:
число ветвей, тегов, коммитов, текущий коммит и размер `.git`.

**Сколько это занимает.** Полный клон репозитория — около 160 МБ на диске
(`.git` ≈ 93 МБ, рабочее дерево ≈ 70 МБ) и меньше минуты на нормальном интернете
(последняя проверка: 30 секунд целиком). Последний шаг — `git fsck`: он молча
проверяет все объекты и на слабой машине может идти несколько минут, это нормально,
прервать его не надо. Если времени нет — добавьте `--skip-fsck` (`-SkipFsck`),
синхронизация от этого не меняется.
Фактические числа на момент проверки: 92 ветви, 658 тегов, 2700 коммитов.

Параметры (одинаковые у обоих скриптов):

| Параметр (bash) | Параметр (PowerShell) | Что делает |
|---|---|---|
| `--target <путь>` | `-Target <путь>` | куда класть рабочую копию (обязательно) |
| `--mirror <путь>` | `-Mirror <путь>` | bare-зеркало всех ветвей и тегов |
| `--releases latest\|N\|all` | `-WithReleases latest\|N\|all` | скачать релизные APK (`gh` + `gh auth login`) |
| `--release-dir <путь>` | `-ReleaseDir <путь>` | куда складывать APK (по умолчанию `<цель>/releases`) |
| `--track-all-branches` | `-TrackAllBranches` | создать локальные ветви для всех ветвей GitHub |
| `--shallow` | `-Shallow` | неполная история (быстро, но без старых коммитов) |
| `--skip-fsck` | `-SkipFsck` | не проверять целостность в конце |
| `--dry-run` | `-DryRun` | только напечатать команды |

## Способ 2: вручную, без скриптов

```bash
git clone --no-single-branch https://github.com/vzhem/APUMIR.git APUMIR
cd APUMIR
git fetch --tags --prune          # все теги
git branch -r                     # все ветви (≈92)
git tag --list | tail -5          # все теги (658)
```

Точная копия всех ссылок (как на GitHub, без рабочего дерева):

```bash
git clone --mirror https://github.com/vzhem/APUMIR.git APUMIR-mirror.git
```

Скачивание релизов (нужен [GitHub CLI](https://cli.github.com/)):

```bash
gh auth login
gh release download v11.74.496 --repo vzhem/APUMIR --dir releases/v11.74.496 \
   --pattern '*.apk' --pattern 'SHA256SUMS.txt'
```

ZIP-архив с GitHub («Code → Download ZIP») **не годится**, если нужно «всё»: в нём
только один снимок ветки без истории, ветвей и тегов.

## Способ 3: `git bundle` (если на компьютере нет доступа к GitHub)

Один файл со всей историей и всеми ссылками, переносится флешкой:

```bash
# на машине, где проект уже есть
git bundle create apumir-full.bundle --all

# на компьютере
git clone apumir-full.bundle APUMIR
git remote set-url origin https://github.com/vzhem/APUMIR.git
git fetch --all --tags        # дальше — обычная синхронизация с GitHub
```

## После синхронизации

1. Открыть в Android Studio папку `<цель>/android-app` (не корень репозитория).
2. Собрать: `gradlew.bat assembleRelease` или задача `:app:assembleRelease`; Rust-ядро
   собирается скриптами `build-rust.sh` / `build-rust.ps1`.
3. Проверить, что видно всё: `git -C <цель> branch -r | wc -l` (≈92),
   `git -C <цель> tag | wc -l` (658), `git -C <цель> log --oneline | head` (свежий коммит `main`).
4. Дальше обновление — тем же скриптом (он делает `fetch` + fast-forward, ничего не теряя).

## Если имелось в виду другое «всё»

- **Данные приложения с телефона** (переписки, ключи, резервные копии) — это не git:
  см. [`BACKUP_AND_NEW_PC.md`](BACKUP_AND_NEW_PC.md), [`FLASH_BACKUP_RUNBOOK.md`](FLASH_BACKUP_RUNBOOK.md),
  [`RESTORE_ON_NEW_PC.md`](RESTORE_ON_NEW_PC.md).
- **Перенос самой разработки на другой ПК** (окружение, JDK, NDK, Rust, телефоны) —
  [`NEW_DEVELOPMENT_PC.md`](NEW_DEVELOPMENT_PC.md) и [`PC_TRANSFER.md`](PC_TRANSFER.md).
- **Релизные файлы для раздачи** (APK, пакеты функций) — `-WithReleases`/`--releases`
  либо страница Releases на GitHub.
