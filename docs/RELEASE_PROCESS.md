# Процесс релиза APUMIR

> Проверенный APK собирается **локально** (Windows), потому что Rust-ядро
> пересобирается отдельно и новые `.so` кладутся в `jniLibs`. GitHub Actions
> собирает только Kotlin/Gradle часть и не пересобирает Rust — его APK нельзя
> считать релизным (см. предупреждение в `.github/workflows/build-release.yml`).

## 0. Выбрать версию

Формат: `vMAJOR.MINOR.PATCH`. VersionCode вычисляется как
`MAJOR*1_000_000 + MINOR*1_000 + PATCH`.

| Версия | VersionCode |
|---|---|
| v11.16.5 | 11_016_005 |
| v11.16.16 (test checkpoint) | 11_016_016 |
| **v11.17.0 (предложение)** | **11_017_000** |

**Важно:** на тестовых телефонах стоит `v11.16.16` (versionCode `11_016_016`).
Новая версия должна иметь versionCode **больше** `11_016_016`, иначе Android
отклонит установку как downgrade. `v11.16.17` и выше тоже подходят.

## 1. Пересобрать Rust-ядро

Обязательно при любом изменении в `rust-core/` (в том числе M8), иначе APK
соберётся со старыми `.so` и упадёт при старте (нет символа
`create_engine_with_custody`).

```
# Windows, корень репозитория
cd rust-core
build-rust.bat            # либо: powershell -File ..\build-rust.ps1 -Arch arm64-v8a,armeabi-v7a,x86_64
```

Скрипт скомпилирует `.so` для `arm64-v8a`, `armeabi-v7a`, `x86_64` и скопирует
их в `android-app/app/src/main/jniLibs`.

> Заметка: `arm64-v8a`/`armeabi-v7a` — отслеживаемые файлы, `x86_64` — в
> `.gitignore`. Для релиза это не важно (публикуется APK, а не `.so`). Если
> хочется, чтобы свежий clone собирался без локальной пересборки — обновить
> отслеживаемые `.so` через `git add -f`.

## 2. Собрать APK

```
cd android-app
gradlew.bat assembleRelease -PreleaseVersionName=v11.17.0
```

Итог: `android-app/app/build/outputs/apk/release/app-release.apk`.

## 3. Переименовать и посчитать SHA-256

```
cd android-app\app\build\outputs\apk\release
copy app-release.apk P2P-Messenger-v11.17.0.apk
certutil -hashfile P2P-Messenger-v11.17.0.apk SHA256
```

Каноничное имя актива: `P2P-Messenger-v11.17.0.apk` (его ищет UpdateChecker).

## 4. Проверить на телефонах

Сценарии — в `docs/M8_PHONE_TESTING.md` (M8: relay custody) + общие
регрессионные проверки. Минимум: 3 телефона, data-preserving установка,
сценарий «отправитель → relay → офлайн-получатель» с убийством процесса relay.

## 5. Опубликовать релиз

Релиз должен быть **полноценным** (не pre-release), иначе `GET /releases/latest`
не вернёт его и приложение не предложит обновление. Публиковать только после
успешной проверки на телефонах.

### Вариант A — `gh` (рекомендуется)

```
gh release create v11.17.0 ^
  "P2P-Messenger-v11.17.0.apk" ^
  "P2P-Messenger-v11.17.0.apk.sha256" ^
  --title "P2P Messenger v11.17.0" ^
  --notes "M8: постоянное зашифрованное хранение relay-custody..." 
```

(`gh` по умолчанию создаёт обычный релиз; pre-release — только с флагом `--prerelease`.)

### Вариант B — веб-интерфейс GitHub

1. Releases → Draft a new release → тег `v11.17.0`.
2. Убедиться, что **«Set as a pre-release» не отмечено**.
3. Прикрепить **только** `P2P-Messenger-v11.17.0.apk` (+ `.sha256`).
4. **Не** прикреплять `app-release.apk` — UpdateChecker предпочитает его
   каноничному имени и может скачать не ту сборку.
5. Publish release.

## 6. Проверить обновление у пользователя

На телефоне с установленной `v11.16.5` (или ниже) открыть приложение → должно
появиться предложение обновления `v11.17.0`. Скачать → установить (данные
сохранятся) → проверить, что профиль и чаты на месте.

## Чеклист перед публикацией

- [ ] Rust пересобран (`build-rust.bat`), новые `.so` в `jniLibs`.
- [ ] APK собран с `-PreleaseVersionName=v11.17.0` (versionCode `11_017_000`).
- [ ] SHA-256 посчитан и приложен к релизу.
- [ ] 3 телефона прошли data-preserving установку без crash/ANR.
- [ ] Сценарий M8 (relay custody переживает kill/reboot) — PASS.
- [ ] Общие регрессии (прямая доставка, MQTT, без дублей чатов) — PASS.
- [ ] В релизе только `P2P-Messenger-v11.17.0.apk` (+ `.sha256`), без `app-release.apk`.
- [ ] Релиз не pre-release (станет `latest` для UpdateChecker).

## Откат (rollback plan)

Если релиз оказался сломанным:

1. Открыть релиз → Edit → отметить **«Set as a pre-release»** (уйдёт из
   `/releases/latest`, приложение перестанет его предлагать).
2. При необходимости удалить assets (Edit → удалить APK).
3. Для пользователей, уже поставивших плохую версию, выпустить следующий
   релиз с versionCode больше (например `v11.17.1`), который чинит проблему.
