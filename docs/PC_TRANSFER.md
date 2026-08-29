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
```
git clone E:\APU-BACKUP\apumir-full.bundle C:\APU-M8
cd C:\APU-M8
git checkout arena/01a03c3d-apumir
git remote set-url origin https://github.com/vzhem/APUMIR.git
git fetch --tags origin
```
Теги (включая v11.23.0) и все ветки внутри bundle есть.

## Первый прогон на новом ПК
```
powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\groups-build-gate.ps1
```
Первая сборка качает зависимости из интернета и может идти 10-20 минут.

## Телефоны
Ничего не меняют: они общаются с сетью напрямую. После переезда ПК команды
adb работают так же, как раньше (adb идёт из Android SDK нового ПК).
