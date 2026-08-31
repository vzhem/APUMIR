# Перенос работы на другой ПК

Резервная копия = папка APUMIR (репозиторий со всей историей, ветками, тегами
и ключом подписания android-app/app/p2p-release.jks) + файл apumir-full.bundle
(тот же репозиторий одним файлом, для надёжности).

## Что НЕ входит и почему
- Папки build/, .gradle/, .idea/, target/ — создаются заново при первой сборке.
- Чаты и данные приложения живут НА ТЕЛЕФОНАХ в приложении, а не на ПК.
  При смене ПК телефоны ничего не теряют.
- GitHub-аккаунт и доступ к нему оформляются заново (пароли в копию не кладутся).

## На новом ПК установить
1. Git for Windows: https://git-scm.com/download/win
2. Android Studio (внутри JDK 17+ и Android SDK; лицензи принять):
   https://developer.android.com/studio
   Проект требует compileSdk из gradle/libs.versions.toml и jvmTarget 17 —
   Android Studio докатит SDK сама при первом открытии проекта.
3. PowerShell 5.1 уже есть в Windows.
4. GitHub CLI (gh) + вход: https://cli.github.com затем `gh auth login`
   (нужно для promote-release и правок релизов; для сборки и гейта не нужен).

Релизные APK собирает GitHub Actions по тегу — локально Rust/NDK не нужны.
Локально нужны только git + JDK/SDK для гейта scripts\groups-build-gate.ps1.

## Вариант А: работать из скопированной папки
1. Скопируйте папку APUMIR с внешнего диска, например в C:\APU-M8.
2. Откройте PowerShell в этой папке и проверьте: `git log --oneline -1`,
   `git status` (сеть с GitHub может потребовать вход: git спросит логин/токен
   один раз; либо работайте офлайн — вся история уже в папке).
3. Дальше обычный порядок: гейт, установка на телефоны по командам из журнала
   docs/AI_COLLABORATION_NOTES.md.

## Вариант Б: восстановить из bundle (если папка повреждена)

Из старого бандла `APU-BACKUP\apumir-full.bundle`:

```
git clone E:\APU-BACKUP\apumir-full.bundle C:\APU-M8
cd C:\APU-M8
git checkout main
git remote set-url origin https://github.com/vzhem/APUMIR.git
git fetch --tags origin
```

Из нового бандла `APUMIR-backup-<дата-время>\repo\apumir-all.bundle` — то же,
но ветка после клона не создаётся сама (у клона бандла нет HEAD), поэтому:

```
git clone --no-checkout F:\APUMIR-backup-<дата-время>\repo\apumir-all.bundle C:\APU-M8
cd C:\APU-M8
git checkout -B main origin/main
git remote set-url origin https://github.com/vzhem/APUMIR.git
git fetch --tags origin
```

`git remote set-url` обязателен: иначе `origin` останется флешкой, и первый же
`git push` запишет обратно на неё. Теги и все ветки внутри бандла есть.

Проще всего — не делать это руками: в корне копии
`APUMIR-backup-<дата-время>` лежит `restore-from-usb.ps1`, который сверяет
sha256 каждого файла с `MANIFEST.txt`, клонирует, перенаправляет `origin`,
создаёт ветки и проверяет, что ключ подписи на месте. Подробности в
`docs\RESTORE_ON_NEW_PC.md`.

## Первый прогон на новом ПК
```
powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\groups-build-gate.ps1
```
Первая сборка качает зависимости из интернета и может идти 10-20 минут.

## Телефоны
Ничего не меняют: они общаются с сетью напрямую. После переезда ПК команды
adb работают так же, как раньше (adb идёт из Android SDK нового ПК).

## Регулярное обновление копии (белая флешка)

Копия живёт на белой флешке с меткой APU_BACKUP (буква может меняться — оба
скрипта ищут по метке). После каждого большого обновления агент предлагает:
«вставьте белую флешку и выполните одну команду».

Полная копия — перед поездкой и перед сменой ПК:

```
powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\backup-to-usb.ps1
```

Кладёт `APUMIR-backup-<дата-время>` с зеркалом git, бандлом, `MANIFEST.txt`
(sha256 каждого файла), файлами, которых нет в git, незакоммиченными правками,
скриптом восстановления и этой инструкцией. Ничего не перезаписывает — каждая
копия в своей папке, старая остаётся точкой отката. В конце сам проверяет, что
всё легло на диск. С флагом `-IncludeToolchainCaches` дополнительно копирует
дистрибутив Gradle и adb (~1,4 ГБ), чтобы новый ПК собирал без интернета.

Быстрая копия рабочего дерева:

```
powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\backup-to-flash.ps1
```

Обновляет `APU-BACKUP\APUMIR` (зеркало без build-папок) и
`APU-BACKUP\apumir-full.bundle`. Если флешка не вставлена — напишет
напоминание и ничего не тронет.

## Чего в копии нет и что это значит

Ключ подписи релиза `android-app/app/p2p-release.jks` **лежит в git**, поэтому
едет внутри истории автоматически. Потерять флешку не страшно — ключ есть на
GitHub. Но на флешке он оказывается в распакованном виде, так что дать ей уйти
не туда — страшно.

`versionName` на телефоне не говорит, какая сборка установлена: он берётся из
`git describe` по локальным тегам. Признак свежести — только `built:` у APK
против `lastUpdateTime` у пакета
(`adb -s <serial> shell dumpsys package com.vladimir.messenger`).
