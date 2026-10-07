# APU — статистика глобальных версий

> **Обязательное правило для следующего ИИ:** обновлять этот файл для каждой крупной пользовательской
> версии APU после code freeze и проверок, но до/сразу после публикации release. Не заменять старые
> записи: добавлять новую секцию сверху и считать разницу с предыдущей зафиксированной версией.

## Исторический backfill (обновлено 2026-10-03)

Старые подробные записи в этом документе заканчиваются v11.17.1/v11.16.23 и не были последовательно
дозаполнены. Добавлена актуальная запись v11.74.168; **статистические записи для промежуточных
версий v11.18.0…v11.74.167 всё ещё неполны**. Не считать нижние старые строки текущим Latest.
Подробности отдельных релизов искать в `docs/RELEASE_NOTES_v*.md` и в журнале collaboration.

Для v11.74.168 APK CDN не удалось скачать в песочницу для независимого локального хэширования.
Размер и SHA-256 сверены с GitHub Release API, а опубликованный отдельный checksum-файл в теге
совпал с API digest. Файл суммы не прикреплён как GitHub Release asset.

## Что считать глобальной версией

Глобальная версия — опубликованный stable release или явно обозначенный test/prerelease milestone,
который пользователь сохраняет на флешку и/или распространяет другим людям. Внутренние harness,
исправление документации и не собранный commit отдельной глобальной версией не являются.

## Что записывать для каждой версии

1. versionName/versionCode, тип release и дата;
2. commit проверенного кода, documentation commit и release/tag target;
3. строки и файлы Rust, handwritten Kotlin, UDL, Android XML, generated bindings и tests;
4. изменение строк относительно предыдущей глобальной версии;
5. APK bytes, SHA-256 и signer certificate SHA-256;
6. основные добавленные возможности — 3–7 коротких пунктов;
7. результаты сборки, установки и runtime-проверок;
8. известные ограничения и следующий приоритет;
9. release URL и состояние portable backup previous/latest.

## Единый метод подсчёта

- Считать физические строки (`splitlines`) и отдельно непустые строки.
- Основной код: `rust-core/src/**/*.rs`, `rust-core/src/lib.udl`, handwritten Kotlin под
  `android-app/app/src/main/java/com/vladimir/messenger/**` и Android manifest/XML resources.
- Generated UniFFI Kotlin считать отдельно.
- Android tests считать отдельно; Rust unit tests внутри `.rs` пока входят в Rust total.
- Документацию, логи, APK/`.so`, изображения, `.git`, build outputs, `.gradle`, `target`, SDK/JDK,
  caches и `%TEMP%` не считать кодом.
- Test/release automation (`scripts/**/*.ps1`, `*.py`, `*.sh`) показывать отдельно.
- Всегда указывать commit, на котором выполнен подсчёт. Не сравнивать цифры, полученные разными
  методами, без явной пометки.

## Сводная таблица

| Версия | Тип | Дата | Основной код | С generated | Automation | APK bytes | Статус |
|---|---|---|---:|---:|---:|---:|---|
| v11.74.190 | stable / Latest | 2026-10-07 | 156 920 | 160 464 | 31 519 | APK_BYTES_190 | опубликован |
| v11.74.189 | stable (был Latest) | 2026-10-07 | 156 377 | 159 921 | 31 366 | 43 823 404 | опубликован |
| v11.74.188 | stable (был Latest) | 2026-10-06 | 155 967 | 159 511 | 31 256 | 43 823 404 | опубликован |
| v11.74.187 | stable (был Latest) | 2026-10-06 | 155 952 | 159 496 | 31 247 | 43 823 404 | опубликован |
| v11.74.186 | stable (был Latest) | 2026-10-06 | 155 718 | 159 262 | 31 215 | 43 782 444 | опубликован |
| v11.74.185 | stable (был Latest) | 2026-10-06 | 155 539 | 159 083 | 31 189 | 43 753 772 | опубликован |
| v11.74.184 | stable (был Latest) | 2026-10-06 | 155 482 | 159 026 | 31 184 | 43 753 772 | опубликован |
| v11.74.183 | stable (был Latest) | 2026-10-06 | 155 319 | 158 863 | 31 141 | 43 753 772 | опубликован |
| v11.74.168 | stable (был Latest) | 2026-10-03 | 143 096 | 146 640 | 30 795 | 43 077 363 | опубликован |
| v11.16.23 | stable (исторический срез) | 2026-08-19 | 35 639 | 38 314 | 19 383 | 22 796 416 | опубликован |
| v11.16.16 | исторический prerelease checkpoint | 2026-08-15 | 31 645 | 34 155 | 17 117 | 22 664 712 | prerelease |

---

## v11.74.190 — передача ранга: знак VIP у имён собеседников

- Дата публикации: 2026-10-07; полный релиз (Latest), тег `v11.74.190`.
- versionName/versionCode: `v11.74.190` / `11074190`.
- Тег `v11.74.190` (annotated) → commit `COMMIT_190` (вершина ветки
  `arena/62ecd7b4-apumir`); сборка тега — прогон `RUN_190`.
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.190>.
- Задача владельца 2026-10-07: «Да, сделай передачу ранга» — знак VIP должен
  стоять и у имён собеседников, а не только у своего звания.

### Строки кода (физические / непустые; подсчёт на ветке перед тегом)

| Категория | Файлов | Строк | Непустых | Дельта к v11.74.189 |
|---|---:|---:|---:|---:|
| Rust (`rust-core/src/**/*.rs`) | 86 | 52 693 | 47 609 | 0 |
| UDL (`rust-core/src/lib.udl`) | 1 | 291 | 252 | 0 |
| Kotlin (handwritten) | 362 | 103 622 | 96 651 | +4 файла / +543 / +496 |
| Android XML | 9 | 314 | 287 | 0 |
| **Основной код** | **458** | **156 920** | **144 799** | **+543 / +496** |
| Generated UniFFI Kotlin | 1 | 3 544 | 2 901 | 0 |
| **С генератором** | **459** | **160 464** | **147 700** | **+543 / +496** |
| Тесты (androidTest + test) | 115 | 15 110 | 13 448 | +1 файл / +103 / +92 |
| Automation (`scripts/**`) | 178 | 31 519 | 28 667 | +1 файл / +153 / +137 |

### Изменения именно v11.74.190

- Конверт `APURANK1|1|<узел>|<ранг>|<время>` — новый `data/rank/RankWire.kt`:
  строгий разбор (ровно пять частей, своя версия, ранг 0..1000, метка времени не
  из будущего дальше суток), форма узла — общая с транспортом (`pk_` + 7..128
  букв/цифр). Ошибка «проверяли всю строку, включая подчёркивание в `pk_`»,
  из-за которой не принимался ни один живой узел, поймана `RankWireTest` на CI.
- Приём — `data/rank/PeerRankRouter.kt` в `CoreServerService` в двух путях приёма,
  ДО авто-создания контакта: конверт поглощается (в историю чата не попадает
  даже битым), заявленный узел сверяется с отправителем.
- Хранение — `contacts.peerRankQualified` (`-1` = «ещё не сообщал») и
  `peerRankUpdatedAtMs`; база 27 + аддитивная `MIGRATION_26_27`; запись только
  «вперёд» (`peerRankUpdatedAtMs < :updatedAtMs` в самом запросе). Экраны узнают
  об изменении сразу — `PeerRankStore.changes`.
- Рассылка — `data/rank/PeerRankBroadcaster.kt` + `RankBroadcastPrefs` (при смене
  ранга или раз в неделю, адрес из чата; отметка ставится и при нуле доставок —
  офлайн-очередь ядра донесёт). Запуск: при старте приложения (`MainActivity`) и на
  экране списка чатов, когда ранг вырос.
- Вид — `ApuVipBadge(compact = true)` рядом с именем: `ContactCard` (список чатов,
  `peerVip`) и шапка переписки (`ChatDetailUiState.peerVip`); порог один —
  `FileTransferRankPolicy.vipMinimumReferrals` (`= 20`).
- Контракты — новый `scripts/ci/check-peer-rank.py` (5 тестов) в `ci-core-check.sh`;
  в `check-android-compile.sh` добавлен узкий прогон `RankWireTest` (5 тестов) и
  печать JUnit-XML в лог (причина падения теперь попадает в комментарий к PR).
- Долг тестового источника: `FakeFileTransferDao` не реализовывал сводку передач
  (`transferStateCounts` и ещё два запроса) — вскрылось, когда тесты впервые
  собрались в CI; добавлено.
- Проверка кода до тега: `37569188503` (Core compile check: компиляция Kotlin,
  контракты, `RankWireTest`) — success. По пути исправлены: `SectionPage` не видит
  состояние экрана (`30842a5`), фейк DAO (`691214c`), канонизация узла (`7e267c6`),
  плавающая проверка даты в тесте (`57273e9`).

### Ограничения v11.74.190

- Знак VIP у собеседника появляется, только если собеседник обновился и его ранг
  уже выше десятого; до первого конверта значение `-1` и знака нет.
- Приём конверта проверяет только совпадение заявленного узла с отправителем
  (транспорт 1:1 аутентифицирован). Число приглашений узел сообщает о себе сам:
  проверяемого доказательства в конверте нет — полный список приглашённых это
  чужие идентификаторы, и рассылать его нельзя. Знак VIP — признание, а не
  пропуск к возможностям.
- Вид на телефоне ещё не проверялся (в песочнице нет JDK/Gradle и Android).
- APK: APK_BYTES_190 байт, SHA-256 `APK_SHA_190` (полностью —
  `docs/RELEASE_NOTES_v11.74.190.md` и `release-upload/app-release.apk.sha256`).

## v11.74.189 — писать в «Избранное» как в чате и знак VIP у элиты

- Дата публикации: 2026-10-07; полный релиз (Latest), тег `v11.74.189`.
- versionName/versionCode: `v11.74.189` / `11074189`.
- Тег `v11.74.189` (annotated) → commit `5966f74` (вершина ветки
  `arena/62ecd7b4-apumir`); сборка тега — прогон `37563934188` (success).
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.189>.

### Строки кода (физические / непустые; подсчёт на теге v11.74.189)

| Категория | Файлов | Строк | Непустых | Δ к v11.74.188 |
|---|---:|---:|---:|---:|
| Rust (`rust-core/src/**/*.rs`) | 86 | 52 693 | 47 609 | 0 |
| UDL (`rust-core/src/lib.udl`) | 1 | 291 | 252 | 0 |
| Kotlin (handwritten) | 358 | 103 079 | 96 155 | +410 / +393 |
| Android XML | 9 | 314 | 287 | 0 |
| **Основной код** | **454** | **156 377** | **144 303** | **+410 / +393** |
| Generated UniFFI Kotlin | 1 | 3 544 | 2 901 | 0 |
| **С generated** | **455** | **159 921** | **147 204** | **+410 / +393** |
| Тесты (androidTest + test) | 114 | 15 007 | 13 356 | +43 / +40 |
| Automation (`scripts/**`) | 177 | 31 366 | 28 530 | +110 / +91 |

### Изменения именно v11.74.189

- «Избранное»: нижняя панель ввода `SavedInputBar` как в чате — `BasicTextField(state)`
  в пузыре APU, кнопка «Отправить» во всю ширину (активная — золото, неактивная —
  светлая с серым текстом), подсказка «Заметка или сообщение себе...». Сохранение
  идёт существующим путём (`SavedViewModel.addNote` → `saveText`), пустое поле
  ничего не пишет.
- Клавиатура: `.imePadding()` на `Scaffold` и на панели, панель разделов уходит на
  время набора (`if (!inputFocused) bottomBar()`).
- VIP: `FileTransferRankPolicy.VIP_MINIMUM_QUALIFIED_REFERRALS = 20` («выше 10 ранга»
  = от «Организатора» и дальше), `Entitlement.isVip`, `regularTiers`, `vipTiers`,
  `isVip(...)`, `referralsToVip(...)`.
- Вид элиты: новый `ApuVipBadge` (золотая плашка со звездой) и `RankMedal(vip = true)`
  (фиолетово-золотая лента, кольцо элиты); экран рангов разделён на «Ранги» и
  «VIP — элита APU», карточка ступени вынесена в `RankTierCard`; на главном экране
  у своего звания — VIP-медаль и знак (`ChatListUiState.rankVip`).
- Контракты: новый `scripts/ci/check-saved-input.py` (4 теста) в `ci-core-check.sh`;
  VIP-тест в `check-settings-style.py`; три JVM-теста в `FileTransferRankPolicyTest`.
- Проверка кода до тега: `37560986364` (Core compile check, включая полную
  компиляцию Kotlin приложения) — success.

### Ограничения v11.74.189

- Знак VIP виден только у своего звания: ранги собеседников по сети не передаются.
  Передача ранга — отдельная задача (ядро + формат данных), заказана владельцем.
- Проверка вида и клавиатуры — только на телефоне владельца (в песочнице нет
  JDK/Gradle и Android).
- APK: 43 823 404 байта, SHA-256 `0e156ce6…6edbd` (полностью —
  `docs/RELEASE_NOTES_v11.74.189.md` и `release-upload/app-release.apk.sha256`).
  Отпечаток сертификата подписи не снимался.

## v11.74.188 — кнопка «Применить» видна над клавиатурой

- Дата публикации: 2026-10-06; полный релиз (Latest), тег `v11.74.188`.
- versionName/versionCode: `v11.74.188` / `11074188`.
- Тег `v11.74.188` (annotated) → commit `1c1e93b` (вершина ветки
  `arena/62ecd7b4-apumir`); сборка тега — прогон `37514563392` (success).
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.188>.
- Причина: скриншот владельца v11.74.187 (21:37) — поле промокода над клавиатурой
  видно, кнопка «Применить» под ней. `bringIntoViewRequester` стоял на поле, а не на
  блоке «поле + кнопка».

### Строки кода (физические / непустые; подсчёт на теге v11.74.188)

| Категория | Файлов | Строк | Непустых | Δ к v11.74.187 |
|---|---:|---:|---:|---:|
| Rust (`rust-core/src/**/*.rs`) | 86 | 52 693 | 47 609 | 0 |
| UDL (`rust-core/src/lib.udl`) | 1 | 291 | 252 | 0 |
| Kotlin (handwritten) | 357 | 102 669 | 95 762 | +15 / +15 |
| Android XML | 9 | 314 | 287 | 0 |
| **Основной код** | **453** | **155 967** | **143 910** | **+15 / +15** |
| Generated UniFFI Kotlin | 1 | 3 544 | 2 901 | 0 |
| **С generated** | **454** | **159 511** | **146 811** | **+15 / +15** |
| Тесты (androidTest + test) | 114 | 14 964 | 13 316 | 0 |
| Automation (`scripts/**`) | 176 | 31 256 | 28 439 | +9 / +9 |

### Изменения именно v11.74.188

- Поле промокода и кнопка «Применить» объединены в один блок (`Column`), и
  `bringIntoViewRequester` стоит на блоке — при фокусе список подводит оба элемента,
  кнопка тоже видна над клавиатурой.
- `bringIntoView()` вызывается дважды (250 мс и ещё через 400 мс): анимация
  клавиатуры занимает около трети секунды.
- Контракт `scripts/ci/check-settings-style.py` расширен: требует
  `bringIntoViewRequester(promoBlock)`, наличие поля и кнопки «Применить» после этой
  строки и не менее двух вызовов `promoBlock.bringIntoView()`.
- Логика промокодов и рангов не менялась.

### Ограничения v11.74.188

- Проверка поведения клавиатуры — только на телефоне владельца (в песочнице нет
  JDK/Gradle и Android).
- APK: 43 823 404 байта, SHA-256 `7b32ad42…0317a` (полностью —
  `docs/RELEASE_NOTES_v11.74.188.md` и `release-upload/app-release.apk.sha256`).
  Отпечаток сертификата подписи не снимался.

## v11.74.187 — ранги и промокод: фирменный вид и поле над клавиатурой

- Дата публикации: 2026-10-06; полный релиз (Latest), тег `v11.74.187`.
- versionName/versionCode: `v11.74.187` / `11074187`.
- Тег `v11.74.187` (annotated) → commit `be140b6` (вершина ветки
  `arena/62ecd7b4-apumir`); сборка тега — прогон `37509198935` (success).
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.187>.

### Строки кода (физические / непустые; подсчёт на теге v11.74.187)

| Категория | Файлов | Строк | Непустых | Δ к v11.74.186 |
|---|---:|---:|---:|---:|
| Rust (`rust-core/src/**/*.rs`) | 86 | 52 693 | 47 609 | 0 |
| UDL (`rust-core/src/lib.udl`) | 1 | 291 | 252 | 0 |
| Kotlin (handwritten) | 357 | 102 654 | 95 747 | +234 / +229 |
| Android XML | 9 | 314 | 287 | 0 |
| **Основной код** | **453** | **155 952** | **143 895** | **+234 / +229** |
| Generated UniFFI Kotlin | 1 | 3 544 | 2 901 | 0 |
| **С generated** | **454** | **159 496** | **146 796** | **+234 / +229** |
| Тесты (androidTest + test) | 114 | 14 964 | 13 316 | 0 |
| Automation (`scripts/**`) | 176 | 31 247 | 28 430 | +32 / +30 |

### Изменения именно v11.74.187

- Экран «Ранги и возможности» переведён на фирменные элементы: возможности —
  строками с галочкой (открыто) и замком (закрыто) вместо маркеров «•»; текущий
  ранг — подсвеченной карточкой с медалью, плашкой «ваш ранг» и полоской прогресса
  до следующего ранга; у каждого ранга статус («ваш ранг» / «достигнут» / «Нужно
  приглашений: N»); пояснение про размер файлов — в пузыре-подсказке.
- Промокод: фирменное поле `ApuFormTextField` вместо stock `OutlinedTextField`;
  при появлении клавиатуры список поднимается, и поле остаётся видимым
  (`imePadding` + `bringIntoViewRequester` по фокусу).
- Общие house-элементы в `ApuSettingsUi.kt`: `ApuSettingsFeatureRow`,
  `ApuSettingsChip`, `ApuSettingsProgress`.
- Логику промокодов и рангов не меняли (проверка на телефоне, повторное
  применение кода невозможно).
- Source-контракт в CI: `scripts/ci/check-settings-style.py`, тест
  `test_rank_screen_uses_house_rows_and_keeps_promo_field_above_keyboard`.
- Проверки кода до тега: `37505535712` (Core compile check, включая полную
  компиляцию Kotlin приложения) — success.

### Ограничения v11.74.187

- Проверка вида и клавиатуры — только на телефоне владельца (в песочнице нет
  JDK/Gradle и Android); это оформление, поведение сети/передачи файлов не
  затрагивалось.
- APK: 43 823 404 байт, SHA-256 `c6739ad1…c327f` (полностью —
  `docs/RELEASE_NOTES_v11.74.187.md` и `release-upload/app-release.apk.sha256`).
  Отпечаток сертификата подписи не снимался.

## v11.74.186 — WSS-мост (MQTT) и переполнение relay-очереди

### Идентификация

- Дата публикации: 2026-10-06; полный релиз (`/releases/latest` → v11.74.186,
  проверено через API и по HTTP-редиректу `releases/latest/download/app-release.apk`).
- versionName/versionCode: `v11.74.186` / `11074186`.
- Release tag target: `0785043` — вершина ветки `arena/62ecd7b4-apumir`
  (PR `vzhem/APUMIR#41` не смержен, main код релиза не содержит).
- Сборка APK: прогон `37474105063` — success. Проверка кода до тега: `37467389230`
  и `37468531277` — success (та же линия фич, что в релизе).
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.186>.

### Строки кода (физические / непустые; подсчёт на теге v11.74.186)

| Категория | Файлов | Всего строк | Непустых строк | Δ к v11.74.185 |
|---|---:|---:|---:|---:|
| Rust core `.rs` | 86 | 52 693 | 47 609 | +179 |
| Rust UniFFI UDL | 1 | 291 | 252 | 0 |
| Handwritten Android Kotlin | 357 | 102 420 | 95 518 | 0 |
| Android manifest/XML resources | 9 | 314 | 287 | 0 |
| **Основной код APU** | **453** | **155 718** | **143 666** | **+179** |
| Generated UniFFI Kotlin | 1 | 3 544 | 2 901 | 0 |
| **Основной код + generated** | **454** | **159 262** | **146 567** | **+179** |
| Android unit/instrumented tests | 114 | 14 964 | 13 316 | 0 |
| Test/release scripts | 176 | 31 215 | 28 400 | +26 |

Воркер Cloudflare (`tools/worker/*.js`) и его тест (`*.mjs`) в эти категории не
входят — как и в прошлых записях.

### Изменения именно v11.74.186

- Воркер: разбор длины MQTT-пакета (varint 1..4 байта). Раньше тело КАЖДОГО пакета
  теряло последний байт: точный CONNECT ядра (118 байт, с LastWill) ломался с
  RangeError, `catch` молча снимал клиента, сокет оставался открытым и тихим — мост
  не отвечал ConnAck (в отчёте `Network timeout` каждые ~5 с, затем «наш брокер не
  ответил ConnAck за 20 с» и час на публичных брокерах).
- Воркер: отказ MQTT 5.0 корректным CONNACK 0x84, `/mqtt/health` (версия моста +
  счётчики connects/connacks/subscribes/publishes_in/publishes_out/parse_errors/
  drops/last_error), громкая ошибка разбора кадра (лог + счётчик + закрытие 1002).
- Ядро: возврат на наш брокер — 15 минут вместо часа; для сессии через мост
  `NetworkOptions::set_connection_timeout(10 с)` (дефолт rumqttc 5 с не вмещает
  TLS+WebSocket+CONNECT+CONNACK через мобильную сеть); расшифровка ошибок rumqttc
  в логе (`mqtt_error_hint`).
- Очередь: `RelayQueue::enqueue_first` для восстановления (место освобождается только
  за счёт просроченных записей, лимит получателя строгий); `restore_relay_custody`
  больше не обрывается на переполнении у одного получателя; просроченное убирается
  по минутному таймеру, а не только на чужой gossip-сводке; одна итоговая строка со
  счётчиками вместо потока warn на каждую запись.
- Тест моста подключён в `Core compile check` (без новых прогонов): 12/12 на
  исправленном воркере, 1/12 на старом.

### Результаты и ограничения

- Тег собран, APK подписан, релиз опубликован и промоутнут в Latest (published
  2026-10-06T14:07:36Z); APK `43 782 444` байта, SHA-256
  `383b3cbfead6fe0eacce23d2343cc1aea54f115abfc85f834bc47a3ebe2c9596`.
- **Правка моста начинает действовать только после передеплоя воркера** владельцем:
  `tools/worker/deploy-mqtt-bridge.ps1 -Ref v11.74.186`. Проверка с телефона —
  <https://p2p-relay.1985vzhem.workers.dev/mqtt/health> → `ok:true`,
  `bridge_version:2`.
- Не проверено: живой телефон (ни мост, ни очередь) и «жёсткая» сеть; отпечаток
  сертификата подписи в этой сессии не снимался.

## v11.74.185 — доработка отчёта по второму боевому отчёту владельца

### Идентификация

- Дата публикации: 2026-10-06; полный релиз (`/releases/latest` → v11.74.185,
  проверено через API и по HTTP-редиректу `releases/latest/download/app-release.apk`).
- versionName/versionCode: `v11.74.185` / `11074185`.
- Release tag target: `181535f` — вершина ветки `arena/62ecd7b4-apumir`
  (PR `vzhem/APUMIR#41` не смержен, main код релиза не содержит).
- Сборка APK: прогон `37462952211` — success. Проверка кода до тега:
  `37461237417` — success (перед ним `37460120574` упал на забытом параметре
  тест-хелпера — CI поймал до тега).
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.185>.

### Строки кода (физические / непустые; подсчёт на теге v11.74.185)

| Категория | Файлов | Всего строк | Непустых строк | Δ к v11.74.184 |
|---|---:|---:|---:|---:|
| Rust core `.rs` | 86 | 52 514 | 47 443 | 0 |
| Rust UniFFI UDL | 1 | 291 | 252 | 0 |
| Handwritten Android Kotlin | 357 | 102 420 | 95 518 | +57 |
| Android manifest/XML resources | 9 | 314 | 287 | 0 |
| **Основной код APU** | **453** | **155 539** | **143 500** | **+57** |
| Generated UniFFI Kotlin | 1 | 3 544 | 2 901 | 0 |
| **Основной код + generated** | **454** | **159 083** | **146 401** | **+57** |
| Android unit/instrumented tests | 114 | 14 964 | 13 316 | +31 |
| Test/release scripts | 176 | 31 189 | 28 375 | +5 |

### APK и подпись

- Release asset: `app-release.apk`, `43 753 772` байта.
- SHA-256 (GitHub API asset digest):
  `1ae8d1079a4d71095e586f8e4a5c56653a2b14f81940e71d070f100b8f351dfd`.
- Отдельный asset `SHA256SUMS.txt`; `release-upload/app-release.apk.sha256`
  приведён к тому же digest. APK подписан ротированным ключом (шаг
  `Re-sign with the rotated release key` — success).

### Изменения именно v11.74.185

- Задвоение «1 мин назад назад» в сводке брокера.
- `payload=[скрыто]` вместо `<скрыто>` (угловые скобки портились при вставке).
- Строка-счётчик шума: срезается служебная шапка logcat, правильные падежи
  («… ещё 31 строка: MQTT FANOUT QUEUED: …»).
- Причины ошибок передач объясняются словами (`TransferErrorText`).
- `stage=готово (ядро поднято)` и `network=подключается` — без противоречий.
- «событий сети (появилась/пропала)» вместо «смен сети».

### Результаты и ограничения

- Тег собран, APK подписан, релиз опубликован и промоутнут в Latest.
- В отчёте владельца из v11.74.184 видно: 14 «ошибок» — старые
  (`RESTORED_ELSEWHERE`, 2026-10-01): передачи продолжились на другом
  устройстве после восстановления профиля.
- Не проверено на телефоне: сам v11.74.185.
- Отдельные задачи: ядро сообщает о переполнении relay-очереди получателя
  (максимум 500); наш WSS-мост не отвечает 20 с, и ядро уходит на публичные
  брокеры.

## v11.74.184 — исправления отчёта «Логи» по боевому отчёту владельца

### Идентификация

- Дата публикации: 2026-10-06; полный релиз (`draft=false`,
  `prerelease=false`, `/releases/latest` → v11.74.184; проверено через GitHub
  API и по HTTP-редиректу `releases/latest/download/app-release.apk`).
- versionName/versionCode: `v11.74.184` / `11074184`.
- Release tag target: `360e9fbb1a91966f229891e27df95be46e624f9b` — вершина
  ветки `arena/62ecd7b4-apumir` (PR `vzhem/APUMIR#41` по-прежнему не смержен).
- Сборка APK: прогон `37456090168` — success (все шаги, включая
  `Re-sign with the rotated release key`). Проверка кода до тега: CI-прогоны
  `37454478913` (правки) и `37455394401` (докоммит) — success.
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.184>.

### Строки кода (физические / непустые; подсчёт на теге v11.74.184)

| Категория | Файлов | Всего строк | Непустых строк | Δ к v11.74.183 |
|---|---:|---:|---:|---:|
| Rust core `.rs` | 86 | 52 514 | 47 443 | 0 |
| Rust UniFFI UDL | 1 | 291 | 252 | 0 |
| Handwritten Android Kotlin | 357 | 102 363 | 95 466 | +163 |
| Android manifest/XML resources | 9 | 314 | 287 | 0 |
| **Основной код APU** | **453** | **155 482** | **143 448** | **+163** |
| Generated UniFFI Kotlin | 1 | 3 544 | 2 901 | 0 |
| **Основной код + generated** | **454** | **159 026** | **146 349** | **+163** |
| Android unit/instrumented tests | 114 | 14 933 | 13 287 | +116 |
| Test/release scripts | 176 | 31 184 | 28 370 | +43 |

### APK и подпись

- Release asset: `app-release.apk`, `43 753 772` байта (совпадение размера с
  v11.74.183 — из-за выравнивания при подписи; SHA-256 другой, файлы разные).
- SHA-256 (GitHub API asset digest):
  `a00173ae9443e76517789a5093088235cd1293de57c687de98754768178493da`.
- Контрольная сумма публикуется отдельным asset `SHA256SUMS.txt`; файл
  `release-upload/app-release.apk.sha256` в ветке приведён к этому же digest.
- APK подписан ротированным ключом (шаг `Re-sign with the rotated release key`
  — success); отпечаток сертификата в этой сессии не снимался (лог прогона и
  CDN APK из песочницы недоступны).

### Изменения именно v11.74.184

- Исправлено правило IPv6: время в строках системного журнала больше не
  превращается в `[ipv6]`, а имена модулей ядра (`p2p_core::engine::core`) не
  считаются адресами.
- Тела MQTT-сообщений (`payload=`) не попадают в отчёт; остаются топик и
  `payload_len`.
- `SEEDING` показывается как «раздаётся», а не «в работе»: файл 41.7 МиБ,
  отправленный рою, больше не выглядит как незавершённая передача.
- Свежая ошибка брокера (≤5 мин) видна прямо в сводке с уровнем «!».
- В разделе передач — коды ошибок из базы и время последней ошибки
  (`ошибка.VERIFY_FAILED=12`, `last_failure_ago=…`).
- Строки `[лог процесса]` сворачиваются по форме («… ещё N строк»), приоритет
  у свежих строк; добавить шума, который вытесняет полезное, больше нельзя.

### Результаты и ограничения

- Тег собран, APK подписан, релиз опубликован и промоутнут в Latest.
- Новые JVM-тесты отчёта (шесть штук) прошли на runner в блокирующем шаге.
- На телефонах не проверялось: ни исправленный отчёт, ни то, что в нём теперь
  видны коды ошибок 14 старых неудачных передач.
- Бытовые предупреждения из отчёта (переполнение relay-очереди получателя;
  отказ нашего WSS-моста при старте) — не Kotlin, отдельные задачи ядра/воркера.

## v11.74.183 — «Логи» в фирменном стиле и расширенный отчёт

### Идентификация

- Дата публикации: 2026-10-06; полный релиз (`draft=false`,
  `prerelease=false`, `/releases/latest` → v11.74.183; проверено через GitHub
  API и по HTTP-редиректу `releases/latest/download/app-release.apk`).
- versionName/versionCode: `v11.74.183` / `11074183`.
- Release tag target: `70980c8ff4bc5168cdfeef38d715e5c02fa89dad` — вершина
  ветки `arena/62ecd7b4-apumir`. PR `vzhem/APUMIR#41` намеренно **не смержен**
  («пока не сливай, скорее всего нужно будет доработать»), поэтому main пока не
  содержит код этого релиза.
- Документационный коммит: `0fdd99f9bc29af11cedd427c7c427c1dff1ad162` (обновление доков и checksum-файла в
  ветке `arena/62ecd7b4-apumir` сразу после релиза).
- Сборка APK: GitHub Actions прогон `37451134858` — success, все шаги, включая
  `Re-sign with the rotated release key`. Проверка кода до тега: CI-прогон
  `37447886348` — success.
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.183>.

### Строки кода (физические / непустые; подсчёт на теге v11.74.183)

| Категория | Файлов | Всего строк | Непустых строк | Δ к v11.74.168 |
|---|---:|---:|---:|---:|
| Rust core `.rs` | 86 | 52 514 | 47 443 | +975 |
| Rust UniFFI UDL | 1 | 291 | 252 | 0 |
| Handwritten Android Kotlin | 357 | 102 200 | 95 314 | +11 248 |
| Android manifest/XML resources | 9 | 314 | 287 | 0 |
| **Основной код APU** | **453** | **155 319** | **143 296** | **+12 223** |
| Generated UniFFI Kotlin | 1 | 3 544 | 2 901 | 0 |
| **Основной код + generated** | **454** | **158 863** | **146 197** | **+12 223** |
| Android unit/instrumented tests | 114 | 14 817 | 13 181 | +1 154 |
| Test/release scripts | 176 | 31 141 | 28 329 | +346 |

Последняя зафиксированная в этом файле версия — v11.74.168, поэтому дельта
включает релизы v11.74.169–v11.74.182 (F4-прямые сессии, ответы/опросы/медленный
режим, размеры текста и другое) плюс сам v11.74.183. Метод тот же
(`git archive <tag>` + `splitlines`), числа v11.74.168 сошлись с таблицей выше.

### APK и подпись

- Release asset: `app-release.apk`, `43 753 772` байта.
- SHA-256 (GitHub API asset digest):
  `cb3f9749c285cda6a40af477ced51b4b79192d67a15b52d3e8e892183d78885e`.
- Контрольная сумма публикуется отдельным asset `SHA256SUMS.txt`; файл
  `release-upload/app-release.apk.sha256` в ветке приведён к этому же digest.
- APK подписан ротированным ключом (шаг `Re-sign with the rotated release key`
  — success). Точный отпечаток сертификата в этой сессии не снимался: лог
  прогона и CDN APK из песочницы недоступны.

### Изменения именно v11.74.183

- Окно «Настройки → Поддержка → Логи» переведено в фирменный стиль: карточка
  «Что происходит сейчас» (сеть, брокер, ядро, передачи, прямой канал, батарея).
- Отчёт `apu-diag/2`: разделы `[сводка]`, `[окружение]`, `[сеть]`, `[ядро]`,
  `[mqtt]`, `[передачи]`, `[сессия]`, `[журнал]`, `[лог процесса]`; счётчики
  сессии, очередь передач из Room, кнопка «Отправить» (системное меню Android).
- Приватность отчёта сохранена: чаты, имена файлов, ключи, шифротекст, адреса и
  contact ID в отчёт не попадают.

### Результаты и ограничения

- Тег собран, APK подписан, релиз опубликован и промоутнут в Latest; телефоны
  увидят уведомление об обновлении (UpdateChecker → `/releases/latest`).
- На телефонах не проверялось ничего: ни окно, ни наполнение журнала.
- Следующий шаг: правки по итогам проверки владельцем, затем merge PR #41 и
  следующая версия (main пока не содержит код v11.74.183).

## v11.74.168 — stable adaptive profile/settings UI

### Идентификация

- Дата публикации: 2026-10-03; stable GitHub release, `draft=false`, `prerelease=false`, `/releases/latest` → v11.74.168.
- versionName/versionCode: `v11.74.168` / `11074168`.
- Application build source: `720b8b3655db2a3ad07e923ab6ecb424437fc2f9`.
- Release tag target / documentation-and-checksum commit: `bba5dcb7485468dede1f67fc795c18d05bf82717`.
- Successful APK workflow: `37103048304`; final tag re-trigger `37104703311` skipped the build because the Release already existed.
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.74.168>.

### Строки кода (физические / непустые; подсчёт на теге v11.74.168)

| Категория | Файлов | Всего строк | Непустых строк | Δ к v11.74.167 |
|---|---:|---:|---:|---:|
| Rust core `.rs` | 84 | 51 539 | 46 531 | 0 |
| Rust UniFFI UDL | 1 | 291 | 252 | 0 |
| Handwritten Android Kotlin | 332 | 90 952 | 84 700 | +123 |
| Android manifest/XML resources | 9 | 314 | 287 | 0 |
| **Основной код APU** | **426** | **143 096** | **131 770** | **+123** |
| Generated UniFFI Kotlin | 1 | 3 544 | 2 901 | 0 |
| **Основной код + generated** | **427** | **146 640** | **134 671** | **+123** |
| Android unit/instrumented tests | 106 | 13 663 | 12 153 | +47 |
| Test/release scripts (`scripts/**/*.ps1`, `*.py`, `*.sh`) | 174 | 30 795 | 28 011 | +122 |

Сравнение выполнено тем же `git show <tag>:<path>` + `splitlines` методом на тегах v11.74.167 и
v11.74.168. Rust и UDL не изменились; +123 handwritten Kotlin строк, один test-файл и automation.

### APK и подпись

- Release asset: `app-release.apk`, `43 077 363` байта.
- GitHub API digest / SHA-256: `6497205c2911a1a8be16626d770da6ebc62391fa2563040878a7a479dc1f1b42`.
- Отдельный файл суммы: `release-upload/app-release.apk.sha256` в теге; публичный raw URL:
  <https://raw.githubusercontent.com/vzhem/APUMIR/v11.74.168/release-upload/app-release.apk.sha256>.
  Он связан из заметок релиза, но **не является прикреплённым Release asset**.
- Android signer certificate fingerprint в этой сессии не снимался с APK: asset CDN скачивался с EOF/SSL-ошибкой. Workflow использовал старый signing key, так как секретов `APU_RELEASE_*` не было; владелец явно принял риск для v168. Ротация остаётся обязательной задачей до следующего выпуска.

### Изменения именно v11.74.168

- Профиль и настройки приведены к единому адаптивному фирменному стилю.
- Быстрые действия профиля собраны вместе: QR-код, ссылка, никнейм и аватар.
- Обновлены карточки, заголовки, поля и диалоги; раскладка учитывает узкий экран и крупный шрифт.
- Единые компоненты применены также к переносу профиля, резервным копиям, рейтингу, прокси и поддержке.
- Добавлены `ApuSettingsLayoutTest` и `scripts/ci/check-settings-style.py`.
- r248 лимиты закрепов (личка, темы групп и лента канала; `pinnedBy`/DAO) присутствуют в v168, но впервые вошли в stable v11.74.167 и не являются изменением v168.

### Что проверено для v168

- APK assembly: GitHub Actions run `37103048304` — SUCCESS для source commit `720b8b3…`.
- Финальный tag run `37104703311` пропустил повторную сборку, поскольку Release уже существовал.
- `python3 scripts/ci/check-settings-style.py` — 8/8; `git diff --check` — PASS.
- Телефонная установка/runtime, mirror checklist и pin-cap acceptance в этой сессии не выполнялись. Java 17+, Android SDK и ADB в песочнице отсутствовали; не переносить сюда старую приёмку v11.16.16.
- API digest и отдельный `.sha256` в теге совпали; APK bytes не скачаны локально из-за EOF/SSL CDN-сбоев. Сертификат подписи из APK в этой сессии не снимался.

### Portable backup

- Перенос v11.74.168 в USB-бэкап владельцем не подтверждён. Старые нижеописанные manifest/restore цифры относились к v11.16.16 и удалены из карточки v168; актуальный чек-лист — `docs/START_HERE.md` §10.

### Известные открытые проверки

- До следующего выпуска выполнить подготовленную ротацию подписи: v168 был собран старым ключом, поскольку secrets `APU_RELEASE_*` отсутствовали; владелец явно принял риск именно для v168.
- Подтвердить deployment Cloudflare Worker `/mirror` и пройти двухтелефонный checklist v168 из `docs/MIRROR_SYNC.md` §7; отдельно проверить r248 лимиты. Эти проверки не заменяются зелёной сборкой.
- Текущие проектные задачи и ограничения брать из `docs/START_HERE.md`, `docs/AI_HANDOFF.md` и тематических handoff-документов, а не из старой v11.16.16 секции про M8 relay custody.

## v11.16.23 — stable durable relay (историческая запись)

### Идентификация

- Дата: 2026-08-19.
- versionName/versionCode: `v11.16.23` / `11016023`.
- Тип: stable GitHub release, Latest на дату публикации (явный выбор владельца проекта).
- Tested application commit: `bd7c1e3d603b39737641802fd9ff3d4ab8da481b`.
- Release build source HEAD: `4dfc3a0d600edbe7e999789a9bc94f1ca31ea22f`.
- Commit подсчёта строк: documentation/release commit, содержащий эту секцию.
- Release/tag target: `09f7f52fe3822c154b4dcfb7cbdf524d529e17b6`.
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.16.23> (stable release, Latest на дату публикации).

### Строки кода

| Категория | Файлов | Всего строк | Непустых строк | Δ строк к v11.16.16 |
|---|---:|---:|---:|---:|
| Rust core `.rs` | 61 | 25 092 | 21 766 | +3 435 |
| Rust UniFFI UDL | 1 | 123 | 101 | +15 |
| Handwritten Android Kotlin | 80 | 10 021 | 9 023 | +526 |
| Android manifest/XML resources | 12 | 403 | 386 | +18 |
| **Основной код APU** | **154** | **35 639** | **31 276** | **+3 994** |
| Generated UniFFI Kotlin | 1 | 2 675 | 2 198 | +165 |
| **Основной код + generated** | **155** | **38 314** | **33 474** | **+4 159** |
| Android unit/instrumented tests | 5 | 241 | 195 | 0 |
| Test/release scripts | 79 | 19 383 | 17 587 | +2 266 |

Подсчёт выполнен тем же методом, что v11.16.16. Рост automation включает одноразовые проверяемые
Windows build/phone/evidence gates, добавленные во время M8 acceptance и восстановления PC.

### APK и подпись

- APK: `APU-v11.16.23.apk`.
- Размер: `22 796 416` байт.
- SHA-256: `85480D5CAF57B9318986BA9E61F9A2A68B38DDD814C683B5949D8E22E7EA9A68`.
- Android package: `com.vladimir.messenger`.
- V2 signer certificate SHA-256:
  `F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7`.
- Build state SHA-256: `8981A23C7781683381054CF9E0FF4972D37D274D44586EC72199C3075ABBF759`.
- GitHub server APK digest: `sha256:85480d5caf57b9318986ba9e61f9a2a68b38ddd814c683b5949d8e22e7ea9a68` — совпадает.
- Checksum asset: 86 B, server digest `sha256:c83d9d3825c125baac6471141b0819f7813487519283bd6a1870bfe09ba2800c`.
- Release published `2026-08-18T21:23:39Z`; draft=false, prerelease=false, Latest на дату публикации.

### Основные изменения

- Encrypted persistent relay custody и restore после Android process death.
- Absolute TTL, bounded restore, durable tombstone и exactly-once UI delivery.
- Bounded WorkManager wake без exact alarm.
- Exact relay DB path и запрет небезопасного Auto Backup/device transfer.
- Device-local identity marker с безопасной миграцией существующих Keystore identities.
- Receipt cleanup fanout для немедленного удаления custody на online relay nodes.

### Что проверено

- Rust Android + generated UniFFI + debug/release Gradle builds: PASS.
- Identity-preserving replace update v11.16.23: PASS 3/3.
- Process death/cold restart, durable-encrypted, exact DB path, quarantine0: PASS 3/3.
- Anna→offline Stas через Zhenya: store → process death → restore → exactly one delivery →
  intermediate RAM+SQLite cleanup → eventual origin DELIVERED: PASS.
- Финальный DB state: target=0, tombstone=1, quarantine=0 на Анне/Жене/Стасе.

### Известные ограничения и backup

- Delayed relay D+reboot, mixed N↔N-1 и отдельный security audit остаются post-release gates.
- Реальный WorkManager wake при остановленном foreground service требует отдельного OEM-теста.
- Verified external backup PASS: `E:\APU_BACKUP_v11.16.23_20260819`.
- 95 файлов проверены по SHA-256; 76 evidence items; Git bundle verify + restore rehearsal PASS.
- Backup state SHA-256: `73633F88E59596F9F6486031D935F6747095945F77877FFE34A0F45243965F5F`.
- Manifest SHA-256: `6AC2CEBD3DCFE1FEFA3F696BB7744BEE61CFE20E1313110C914464724C9C17B5`.
- Previous backup `E:\APU_RECOVERY_20260818_M8C3` сохранён без изменений; диск не форматировался.
- USB `APU_RECOVERY`: NTFS, Healthy/OK, dirty=false после записи.

---

## v11.16.16 — test checkpoint (историческая prerelease-запись)

### Идентификация

- Дата: 2026-08-15.
- versionName/versionCode: `v11.16.16` / `11016016`.
- Тип: GitHub prerelease, не stable release.
- Tested application commit: `61e1580ff85aa1cfaed1f9e7a7522f1cd8e5d602` + exact Windows
  Kotlin/Rust working overlays, сохранённые в portable source/patches.
- Release preparation/tag target: `85aecb0fa9893184e357b6c565869d0f1ebd69b7`.
- Commit подсчёта строк: `03c9768` (ветка `arena/01a000bc-apumir`; docs не входят в LOC).
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.16.16>.

### Строки кода

| Категория | Файлов | Всего строк | Непустых строк |
|---|---:|---:|---:|
| Rust core `.rs` | 59 | 21 657 | 18 683 |
| Rust UniFFI UDL | 1 | 108 | 88 |
| Handwritten Android Kotlin | 77 | 9 495 | 8 545 |
| Android manifest/XML resources | 12 | 385 | 369 |
| **Основной код APU** | **149** | **31 645** | **27 685** |
| Generated UniFFI Kotlin | 1 | 2 510 | 2 065 |
| **Основной код + generated** | **150** | **34 155** | **29 750** |
| Android unit/instrumented tests | 5 | 241 | 195 |
| Test/release scripts | 47 | 17 117 | 15 555 |
| Весь tracked code/config/automation без docs/logs | 220 | 53 173 | 46 898 |

Разница с предыдущей глобальной версией: **нет сопоставимого baseline тем же методом**. Начиная со
следующей версии обязательно показать delta по каждой основной категории.

### APK и подпись

- APK: `APU-v11.16.16.apk`.
- Размер: `22 664 712` байт.
- SHA-256: `446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D`.
- Android package: `com.vladimir.messenger`.
- V2 signer certificate SHA-256:
  `F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7`.
- GitHub server-side asset digest совпал с APK SHA-256.
- Tag workflow: release-exists check PASS, build job skipped; повторной сборки/замены APK не было.

### Основные изменения

- Automatic origin relay для недоступного получателя.
- Bounded gossip/summary и missing-relay forwarding между совместимыми телефонами.
- TTL 7 дней, максимум 8 переходов, dedup и ограничения relay queues/traffic.
- Совместимый relay wire format для соседних версий.
- Bounded dual-broker transport и production dedup.
- Честный `QUEUED_OFFLINE` вместо ложного обещания немедленной доставки.

### Что проверено

- Signed Rust + Kotlin APK build: PASS.
- Data-preserving install v11.16.16 на Анну, Женю и Стаса: PASS 3/3.
- Controlled launch/readiness: PASS 3/3; primary/secondary READY, stable processes, no crash/ANR.
- Пользователь вручную наблюдал успешную offline UI-доставку через третий телефон.
- Отдельный post-capture не содержал exact message/protocol markers, поэтому full message-ID chain,
  receipt cleanup и eventual origin `DELIVERED` ещё не доказаны строгим runtime acceptance.

### Portable backup

- Путь: `F:\APU_PORTABLE`.
- Manifest: 278 файлов, все SHA-256 PASS.
- previous: v11.16.15.
- latest: v11.16.16.
- Git bundle verify, forbidden scan и restore rehearsal: PASS.
- Backup state SHA-256:
  `A96500612DD1AC80D908F1F49ADE9536931E512D387C2FD0EDA8CB82772D2483`.
- Флешку больше никогда не форматировать; только verified previous/latest rotation.

### Известное ограничение и следующий приоритет

**M8 persistent relay custody ещё не реализована.** RelayQueue хранится в памяти процесса, поэтому
Android process death/reboot до handoff может потерять чужое сообщение. Сценарий «Женя сохранил,
уснул, через сутки проснулся, запросил новые relay items и продолжил передачу» пока best-effort.

Следующий глобальный этап:

1. encrypted persistent RelayQueue;
2. absolute expiry без сброса TTL после restart;
3. recovery после process death/reboot;
4. bounded sleep/wake/background relay cycle;
5. durable receipt/tombstone cleanup и exactly-once UI delivery;
6. delayed Anna→Zhenya→relay D→Stas acceptance через несовпадающие online-окна.

## v11.17.1 (2026-08-21, stable опубликован на дату записи; исторический срез)

- Первая публичная версия с передачей файлов (фото/видео/документы) в личных чатах:
  E2E-шифрование (XChaCha20-Poly1305, подписанный конверт ключа, TOFU-пин контакта),
  durable-доставка (TTL 7 дней), окно ≤120 пакетов, чанки 9KiB-фрагментами, прогресс и статусы,
  «Сохранить в папку» (SAF) + галерея.
- File-HELLO handshake (автозакрепление ключей), mDNS re-publish 60с (прямой LAN-путь),
  фикс чат-роутинга входящих, 30с re-pump.
- CI release-workflow теперь собирает Rust из исходников (NDK 28.2 + cargo-ndk, 3 ABI) —
  устранён класс «stale .so в релизном APK».
- LOC-дельта к v11.16.16: ~+3900 (Kotlin+Rust+tests+CI; точный подсчёт по тегу).
- Статус на дату записи: stable release опубликован (CI run 32473695695, 11m13s; APK 35,285,255 B;
  checksum asset добавляется владельцем локально). Это не текущий Latest.
