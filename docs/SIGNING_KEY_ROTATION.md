# Смена ключа подписи APK (без переустановки на телефонах)

## В чём опасность

Файл `android-app/app/p2p-release.jks` и пароль к нему (`p2p2026release`,
`build.gradle.kts`) лежат в **публичном** репозитории с коммита `37e681b`.
Этим ключом подписан каждый релиз APU. Последствия:

- любой человек может собрать свой APK с `applicationId = com.vladimir.messenger`,
  подписать его этим ключом и с более высоким `versionCode` - и телефон **примет
  его как обновление APU** поверх настоящего, со всей перепиской и ключами;
- «удалить файл из репозитория» не помогает: он остаётся в истории git и в
  каждом форке/клоне. Ключ считается раскрытым навсегда.

Единственное лечение - **новый ключ**. Но просто заменить ключ нельзя: Android
не ставит обновление с другой подписью, все телефоны получат «Не удалось
установить» и людям придётся удалять приложение вместе с перепиской.

## Как это делается правильно: родословная подписи (Android 9+)

Android 9 (API 28) умеет **ротацию ключа**: старый ключ один раз подписывает
заявление «мой законный преемник - вот этот новый ключ». Это заявление
(*signing lineage*, файл `android-app/app/apu-signing-lineage`) кладётся в
каждый APK. Телефон с прежней версией видит: обновление подписано новым
ключом, но старый ключ (которому телефон уже доверяет) за него поручился -
и ставит обновление. Дальше телефон доверяет **только новому** ключу.

Минимальная версия у нас Android 8 (`minSdk 26`). Для Android 8 подпись v1/v2
остаётся старым ключом (там ротации нет), для Android 9+ действует новый
ключ через v3-блок. Раскрытый ключ по-прежнему может обновлять приложение
**только на Android 8** - таких телефонов почти нет, и это принимаемый риск.

Файл `apu-signing-lineage` **публичный по замыслу** - он есть в каждом APK и
не содержит закрытых ключей. Его коммитят.

## Порядок действий (один раз, на Windows-клоне владельца)

Все шаги молчаливые, кроме печати результата. Телефоны не трогаются.

### Шаг 1. Создать новый ключ, родословную и секреты

Скрипт сам находит `keytool` (Android Studio → `jbr`) и `apksigner`
(`build-tools`), создаёт `C:\APU-KEYS\apu-release-2026.jks` со случайным
паролем, пишет родословную и кладёт три секрета в GitHub Actions
(`APU_RELEASE_KEYSTORE_B64`, `APU_RELEASE_KEYSTORE_PASSWORD`,
`APU_RELEASE_KEY_ALIAS`). Сначала пробный прогон:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\rotate-signing-key.ps1 -DryRun
```

Затем по-настоящему:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\rotate-signing-key.ps1
```

В конце скрипт печатает строку `SHA256:` нового сертификата - она нужна для
шага 2. Папка `C:\APU-KEYS` **не в репозитории**; её надо скопировать на
флешку резервной копии (`backup-to-usb.ps1` делает это сам, если папка есть).
Потеря этой папки = ни одного нового релиза подписать нельзя.

### Шаг 2. Worker: добавить новый отпечаток

В `tools/worker/p2p_relay_worker.js` в массив `RELEASE_CERT_SHA256S` добавить
**вторым** элементом строку `SHA256:` из шага 1 (старый отпечаток оставить).
Опубликовать worker (Cloudflare → Edit code → вставить целиком → Deploy).
Без этого ссылки `https://…/i?slug=…` перестанут открываться в APU без вопроса
на телефонах с обновлённым приложением.

### Шаг 3. Workflow: подпись новым ключом в CI

Бот из песочницы не может менять `.github/workflows` (нет права `workflows`),
поэтому готовый файл лежит рядом: `scripts/ci/build-release.yml`. Скопировать
его на место и закоммитить вместе с родословной:

```powershell
powershell -NoProfile -Command "Copy-Item C:\APU-M8\scripts\ci\build-release.yml C:\APU-M8\.github\workflows\build-release.yml -Force"
```

```powershell
powershell -NoProfile -Command "git -C C:\APU-M8 add .github/workflows/build-release.yml android-app/app/apu-signing-lineage; git -C C:\APU-M8 commit -m 'release: sign with the rotated key'; git -C C:\APU-M8 push"
```

Что делает новый шаг workflow `Re-sign with the rotated release key`: после
`assembleRelease` вызывает `apksigner sign` со старым ключом (v1/v2, Android 8)
и новым ключом из секретов (v3 с родословной, Android 9+,
`--rotation-min-sdk-version 28`), проверяет подпись `apksigner verify` и
подменяет `app-release.apk`. Защита от полу-состояний:

- секреты есть, родословной в репозитории нет → сборка **падает** (иначе
  вышел бы APK новым ключом без поручительства - телефоны бы его отвергли);
- родословная есть, секретов нет → сборка **падает** (иначе вышел бы APK
  старым ключом, и телефоны, уже перешедшие на новый, отвергли бы его);
- ни того ни другого → предупреждение и APK старым ключом, как сейчас.

### Шаг 4. Релиз и проверка

Обычный релиз (`make-release.ps1` → `promote-release.ps1`). Проверка на
телефоне, где стоит предыдущая версия: обновление **ставится поверх**, чаты на
месте. Проверка подписи с ПК:

```powershell
powershell -NoProfile -Command "& (Get-ChildItem \"$env:LOCALAPPDATA\Android\Sdk\build-tools\*\apksigner.bat\" | Sort-Object FullName -Descending | Select-Object -First 1).FullName verify --print-certs -v C:\Users\User\Downloads\app-release.apk"
```

В выводе должны быть два подписанта: старый (`CN=Vladimir`, для v1/v2) и
новый (`CN=APU`, v3 + lineage).

### Шаг 5. После первого релиза новым ключом

- `START_HERE.md`: записать дату ротации и версию, с которой действует новый
  ключ; телефоны, пропустившие все версии до неё, обновятся всё равно
  (родословная в каждом следующем APK).
- Debug-сборки для телефонов (`install-debug-on-phones.ps1`) как подписывались
  debug-ключом Android Studio, так и подписываются - их это не касается.
- Старый `p2p-release.jks` из репозитория **не удалять**: он нужен в CI для
  подписи v1/v2 (Android 8) и для родословной. Он больше ничего не защищает.

## Что НЕ решает ротация

- Кто-то может подписать старым ключом APK для телефонов на Android 8 (см.
  выше) - принимаемый риск.
- Приложение не проверяет собственную подпись во время работы; это отдельная
  мера и от подмены APK она защищает слабо.
- Секреты GitHub Actions доступны любому workflow в репозитории. Пуш в
  `.github/workflows` есть только у владельца (боту GitHub отказывает) - так и
  оставить.

## Если что-то пошло не так

- `apksigner rotate` ругается на пароль старого ключа → пароль `p2p2026release`,
  alias `p2p`; хранилище PKCS12, `keytool -list -keystore p2p-release.jks`.
- CI упал на `Re-sign…` с «lineage is committed but secrets are missing» →
  `gh secret list --repo vzhem/APUMIR` должен показать три `APU_RELEASE_*`;
  если их нет - шаг 1 не дошёл до конца, повторить только `gh secret set`
  (значения - файл `C:\APU-KEYS\apu-release-2026.jks` в base64 и пароль из
  `apu-release-2026.password.txt`).
- Телефон пишет «Не удалось установить» → сверить `apksigner verify
  --print-certs`: в APK должен быть подписант со старым сертификатом
  `F8:43:CB:E7…A5:F7` в v1/v2 и lineage в v3.
