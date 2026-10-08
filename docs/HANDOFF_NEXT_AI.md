# Передача контекста следующему ИИ — APU

**Срез состояния:** 2026-10-08, после публикации v11.74.206.

**Это текущая точка входа:** сначала прочитать этот файл, затем только документы из раздела «Куда смотреть». Старые записи ниже по `START_HERE.md` и `AI_HANDOFF.md` — архив; их старые даты Latest и формулировки «осталось» не считать текущим состоянием.

---

## 1. Самое важное за минуту

- Текущий опубликованный stable Latest — **v11.74.206**: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.206>.
- Релиз посвящён ручному повтору недоставленных исходящих сообщений и файлов. Публичные заметки согласованы владельцем до публикации и лежат в `docs/RELEASE_NOTES_v11.74.206.md`.
- Android Release APK и native core успешно собраны GitHub Actions; подпись/re-sign и создание checksum прошли. После публикации API подтвердил `draft=false`, `prerelease=false`, `releases/latest = v11.74.206`.
- **Не запускать следующий релиз автоматически.** Для каждого следующего публичного релиза сначала показать владельцу точный текст заметок и получить новое одобрение; перед продвижением draft в stable требуется успешная сборка и проверка Latest. См. `docs/RELEASE_PUBLICATION_POLICY.md`.
- В рабочем дереве есть отдельная незавершённая работа по прогрессу и скорости раздачи/приёма APK у соседей. Она **не вошла** в v11.74.206 и пока не прошла Android-сборку/проверку на телефонах. Не включать её случайно в следующий release.
- Главная ловушка для нового чата: опубликованный v11.74.206 и его исходники **ещё не на `main`**. GitHub `main` всё ещё указывает на `v11.74.182` (`7524e51c…`). Чтобы новая сессия, открытая на `main`, видела v11.74.206 и эту передачу контекста, нужна синхронизация/PR и его merge. В этой сессии ветку переключать нельзя; текущая рабочая ветка фиксирована.

## 2. Где лежат актуальные исходники и что увидит новый чат

- В этой сессии работа велась на ветке `arena/8aa2ecbd-apumir`.
- Release-statistics checkpoint в Arena-ветке — `9ac7ffa` (`docs(release): record v11.74.206 stable release`); этот handoff и исправления навигационных документов сохранены отдельным documentation-only commit поверх него. Для актуального branch tip всегда смотреть `git log`/`git ls-remote`, а не считать `9ac7ffa` последним commit.
- Тег `v11.74.206` указывает на commit `69e9632f378ab2f81873fbaaadd856687850f5b0` — именно этот исправленный commit прошёл финальную Android Release-сборку.
- Ветка `main` на сервере и `origin/main` в этом checkout: `7524e51c46b9e5cc73fe2bb5808f3f3ce536b95c`, исторический `v11.74.182`. Релиз `v11.74.205` и новый `v11.74.206` были собраны из более поздних тегов/коммитов, но не синхронизированы обратно в `main`.
- PR из Arena-ветки в `main` **не создан**. Не считать, что `main` уже содержит v11.74.206. Перед выбором `main` в новом чате владелец должен либо принять/объединить PR из Arena-ветки, либо явно выбрать другую исходную ветку. Текущая сессия не переключалась на `main` и не пушила туда.
- Удалённый Arena-branch содержит v11.74.206 и статистику релиза; локальное рабочее дерево дополнительно содержит незакоммиченные изменения из §6. Эти локальные APK-speed изменения на удалённую ветку не попали.

## 3. Что завершено и выпущено в v11.74.206

### Повтор сообщения

- Добавлен отдельный путь **ручного** повтора исходящего текстового сообщения с сохранением исходного `messageId`.
- Ручной retry готовит/запечатывает свежий payload и обновляет срок хранения в relay-очереди от момента действия пользователя: до 7 дней.
- Для обычных автоматических повторов сохранена first-wins дедупликация: дубликат не заменяет первую запись и не продлевает TTL.
- Durable encrypted store обновляется при явном retry; если запись уже удалена уборщиком, повтор может добавить её снова.
- Для зеркального телефона в `MirrorRow` передаётся `manualRetry`; активное устройство обрабатывает его именно как явный retry, а не как обычную идемпотентную повторную отправку.
- Полученное подтверждение доставки не должно быть понижено результатом запоздавшего retry; подтверждённая/прочитанная запись не должна показывать ручной retry.

### Повтор файла

- Ручной retry доступен только для исходящего файла, который ещё не подтверждён или истёк; подтверждённую доставку нельзя повторить через эту кнопку.
- Для каждого retry системный Android picker открывается заново. URI и путь исходника не сохраняются.
- Новый transfer создаётся с прежним ID сообщения, новым ID передачи и новым подписанным манифестом. Это позволяет записать новый срок хранения в подпись.
- Для тяжёлого файла свежий срок — до 24 часов; текст/малые передачи следуют существующему правилу до 7 дней.
- Перед подготовкой новой передачи старые encrypted chunks, ключ старой передачи и preview очищаются. Повторные попытки сериализуются с file pump.
- Реальное истечение файла переводит его в `FILE_EXPIRED`, чтобы UI мог предложить новый выбор исходника. Пользовательская отмена stalled transfer не должна маскироваться под истечение.

### Основные места реализации

- Rust retention/очередь: `rust-core/src/engine/core.rs`, `rust-core/src/network/relay_queue.rs`, `rust-core/src/storage/relay_store.rs`.
- UniFFI: `rust-core/src/lib.udl`, `rust-core/src/lib.rs`; Kotlin bridge: `RustBridge.kt`, `uniffi/p2p_core/p2p_core.kt`.
- Retry, mirror и DAO: `ChatRepository.kt`, `MirrorSync.kt`, `CoreServerService.kt`, `MessageDao.kt`, `FileTransferDao.kt`, `FileTransferKeyVault.kt`.
- Файловый pump/retry: `FileTransferRouter.kt`; UI/picker: `FileTransferBubble.kt`, `MessageBubble.kt`, `ChatDetailScreen.kt`, `ChatDetailViewModel.kt`.
- Rust-регрессии: тесты в `relay_queue.rs` и `relay_store.rs`; `FakeFileTransferDao.kt` обновлён.
- **Граница подтверждения:** релизные заметки описывают повторы из переписки, но отдельная проверка поведения в группах/каналах и на физических устройствах не записана. Не обещать поддержку группового retry без проверки кода и сценария.

## 4. Итог релиза и доказательства

- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.206>.
- Публичные заметки: `docs/RELEASE_NOTES_v11.74.206.md`; точный текст был одобрен владельцем до публикации.
- Финальный workflow: [37760727087](https://github.com/vzhem/APUMIR/actions/runs/37760727087), success; финальный `headSha` — `69e9632f378ab2f81873fbaaadd856687850f5b0`.
- В финальном прогоне прошли: native core для трёх Android ABI, генерация UniFFI, `:app:assembleRelease`, re-sign и генерация SHA-256.
- APK: 44 036 396 байт; GitHub Release Asset API digest `sha256:220f6eb5c4e7abab6f50990e8e4c5f2f774852ca5642287e9a30beff2a4653f7`.
- `SHA256SUMS.txt`: 82 байта; digest самого checksum asset `sha256:e2852f9e9e0e3292f2cb9662b69e115673fe64741ddd7e54a55a9ad5b08bbd8e`.
- После публикации отдельно проверен API `releases/latest`: `v11.74.206`, `draft=false`, `prerelease=false`.
- Повторный локальный `tools/sandbox/check_unresolved_symbols.py` на `main/java`, `test/java` и `androidTest/java` прошёл: 491 Kotlin-файл, 0 подозрительных имён. `git diff --check` прошёл.
- После релиза повторно запущены все 10 `scripts/ci/check-*.py`: 9 прошли, `check-chat-style.py` упал только на уже существующем `TextButton` в `ui/components/MessageBubble.kt:297` («Повторить отправку»). Этот файл не менялся в текущей документационной задаче; failure — style contract, не Release APK compile. Не писать, что все 10 source checks сейчас зелёные; решить отдельно, надо ли стилизовать кнопку и обновить контракт.
- Rust/Android unit-тесты и установка/runtime-проверка на физических телефонах в этой сессии **не выполнялись**. Release workflow компилировал и паковал APK, но не запускал эти тесты.
- Первый tag build `37758911217` упал на Kotlin compile из-за APK speed-hook, который попал в release-коммит вместе с retry-кодом. Hook удалён из release source отдельным fix-коммитом; tag `v11.74.206` перенаправлен на `69e9632…`; повторный финальный workflow `37760727087` прошёл. Незавершённый APK-speed hook затем восстановлен только в локальном рабочем дереве, но не в release tag.
- CI сгенерировал Kotlin UniFFI binding, отличный от файла, закоммиченного вручную (prefix SHA-256 `f49fb52155a7b6c3`); APK собран с regenerated binding. GitHub artifact `core-bindings` и release-assets недоступны для скачивания из этой песочницы. **Перед следующей локальной сборкой** нужно синхронизировать generated binding с `lib.udl` через генератор/CI artifact. Не переиздавать v11.74.206 ради этого без отдельного анализа.
- Подробная статистика и release fingerprints записаны в `docs/VERSION_STATISTICS.md`.

## 5. Что ещё нужно сделать

### P0 — чтобы новый чат на `main` не стартовал с устаревшего кода

1. Сначала проверить `git status`, `main`, тег `v11.74.206` и текущий Latest; не доверять старым статичным заметкам без проверки.
2. Синхронизировать коммиты стабильных релизов и v11.74.206 с `main` через PR/merge. PR не создан. Владелец должен явно разрешить PR/merge; до этого `main` остаётся v11.74.182.
3. Не переключать/не сбрасывать ветку с незакоммиченным локальным APK-speed кодом до его сохранения в отдельный commit/patch или до осознанного решения владельца. Сейчас он есть только в рабочем дереве.

### P1 — отдельная незавершённая задача: скорость и прогресс раздачи/приёма APK

Рабочий код уже находится локально, но **не входит в v11.74.206**, не запушен и не прошёл Android-сборку:

- `android-app/app/src/main/java/com/vladimir/messenger/data/file/FileTransferReceiver.kt`
- `android-app/app/src/main/java/com/vladimir/messenger/data/file/FileTransferRouter.kt` (в этом файле одновременно есть уже выпущенный retry и незакоммиченный APK-speed hook; при staging целого файла легко случайно смешать задачи)
- `android-app/app/src/main/java/com/vladimir/messenger/data/update/ApkSeeder.kt`
- `android-app/app/src/main/java/com/vladimir/messenger/data/update/ApkUpdate.kt`
- новое `android-app/app/src/main/java/com/vladimir/messenger/data/update/DownloadSpeedEstimator.kt`
- `android-app/app/src/main/java/com/vladimir/messenger/ui/screens/settings/SettingsScreen.kt`
- `android-app/app/src/main/java/com/vladimir/messenger/ui/screens/settings/SettingsViewModel.kt`
- `android-app/app/src/test/java/com/vladimir/messenger/data/file/FileTransferReceiverTest.kt`
- `android-app/app/src/test/java/com/vladimir/messenger/data/update/ApkUpdateTest.kt`
- описание текущего статуса и исторические оговорки сохранены в `docs/HANDOFF_NEXT_AI.md`, `docs/AI_HANDOFF.md`, `docs/START_HERE.md` и `docs/UPDATE_SEEDING.md`; они входят в отдельный documentation-only commit, а не в release tag.

Заявленная цель этой отдельной задачи: показывать downloaded/total, остаток, progress bar, скорость и число источников для полного APK и компактного patch; проверить лимит параллельных источников (базово было 3, общий приёмник допускает до 4). Код оценивает скорость по новым fragment payload, UI обновляет её раз в секунду; детали и ограничения — `docs/UPDATE_SEEDING.md` и текущий верхний раздел `docs/AI_HANDOFF.md`.

Перед выпуском этой функции нужно: восстановить/сохранить её отдельным осмысленным commit, выполнить source checks, Rust/Android compile и unit tests, проверить корректность transfer ID/получателя и multi-source поведения, а при наличии устройств — прогнать phone gate. Для неё нужны **новые точные публичные заметки и новое одобрение владельца**. Не считать текущую незакоммиченную работу проверенной только потому, что v11.74.206 собрался: в tag эти APK-speed hunks не включены.

### P1 — прочие задачи из предыдущего handoff, статус перепроверить

- Локализация приложения: предыдущая передача контекста помечала её незавершённой; в этой сессии она не проверялась и не менялась.
- Отдельная проверка transfer ID в потоке загрузки/раздачи APK: предыдущая передача контекста помечала её открытой; в этой сессии она не проверялась. Не объявлять её закрытой без теста по коду/устройствам.
- Проверка v11.74.206 на телефонах отсутствует. Если владелец захочет runtime gate, отдельно согласовать устройства/условия; не публиковать серийные номера, полные node IDs и другую test topology.

## 6. Текущее рабочее дерево

На момент этой записи:

- Ветка: `arena/8aa2ecbd-apumir`; удалённая ветка содержит release code, statistics и отдельный documentation-only commit с этим handoff.
- Release tag: `v11.74.206` → `69e9632…`; statistics checkpoint — `9ac7ffa`; handoff/docs commit идёт после него, не меняя release tag.
- Documentation-only commit включает `HANDOFF_NEXT_AI.md`, `START_HERE.md`, `AI_HANDOFF.md`, `UPDATE_SEEDING.md`, `NEXT_AI_CHAT_BOOTSTRAP.md` и обновлённый вводный/пост-релизный блок `VERSION_STATISTICS.md`; approved release notes не изменены.
- Незакоммиченные файлы — только APK-speed исходники и тесты из §5 P1; они намеренно не включены в documentation-only commit, чтобы не смешивать задачу с опубликованным retry-релизом.
- Рабочая среда не содержит `cargo`, `rustc`, `rustfmt`, `java`, `kotlinc` и исполняемого `gradlew`; релиз собирался на GitHub Actions.
- Не делать `git reset --hard`, `git clean -fd`, checkout другой ветки или force-push ветки. При дальнейшей работе сначала сохранить текущий незакоммиченный APK-speed diff.

## 7. Куда смотреть

1. `docs/HANDOFF_NEXT_AI.md` — этот актуальный handoff.
2. `docs/RELEASE_PUBLICATION_POLICY.md` — обязательные правила публичных notes/release.
3. `docs/RELEASE_NOTES_v11.74.206.md` — опубликованный текст v11.74.206.
4. `docs/VERSION_STATISTICS.md` — актуальный Latest, сборка, asset digests и статистика.
5. `docs/UPDATE_SEEDING.md` — архитектура и текущий статус APK seeding.
6. Верх `docs/AI_HANDOFF.md` — незавершённая APK-speed задача; более низкие разделы — исторические записи.
7. Верх `docs/START_HERE.md` — краткий обзор; большие нижние разделы содержат старые срезы и архивные состояния.

## 8. Обязательные правила для следующего ИИ

- Если новая сессия начинается на `main`, сразу проверить SHA `main` и Latest. На дату среза `main` отстаёт до v11.74.182 и не содержит v11.74.206.
- Не переносить в публичные notes internal logs, branch/session names, IDs тестовых устройств, private endpoints или test topology.
- Для следующего релиза сначала показывать владельцу exact release notes и ждать одобрения. Не создавать tag/release и не промотировать draft до прохождения успешной Android Release-сборки.
- Системные Android picker, клавиатура, permissions и share sheets оставлять системными. Компактные toolbar icons не заменять массово крупными золотыми кнопками.
- Папки `.git` и корень репозитория не удалять/не перемещать; незакоммиченные пользовательские изменения не сбрасывать.
