# Рой и телефоны-помощники: утверждённый план

Владелец одобрил реализацию и необходимые публичные релизы 2026-10-11.
Не поднимать предельную нагрузку только на основании ёмкости пула соединений.

## Этап 1 — устойчивые онлайн-маршруты

Реализовано и опубликовано для всех в v11.74.326.
Проверки 2026-10-11: релизная сборка 38158716996 — success; полный core check
38158719209 — success (блокирующие тесты ядра). Android APK 95 037 303 bytes,
APK SHA-256 по GitHub asset digest:
`cfc05bc2990beadb129f893a8275c0b97abb811c2f8267c5ae03e60d4f548dc0`.
Отдельное скачивание APK/checksum в песочницу недоступно из-за сетевого ограничения;
независимое пересчитывание суммы не выполнено. Устройств Android в проверке нет.
Кандидаты 323–325 остановлены тестовым барьером до публикации. Четырёхузловой QUIC
тест проходит в релизном барьере; также прошли все 7 виртуальных race tests,
8 assistance, 14 exchange и 9 direct transport tests.

Изменения:
- Прямой маршрут остаётся первым, затем защищённый online helper, далее существующие
  очередь/кастодия/MQTT-механизмы вызывающего кода. Не переписываем условия согласия на кастодию.
- Из ограниченного круга 32 проверенных соседей выбираем до двух с живым соединением.
- Второй helper стартует через 1500 ms или сразу после отказа первого. Оба несут один sealed packet.
- Общая helper-попытка ограничена 9500 ms; синхронный FFI waiter — 10000 ms.
- Только проверенная подпись получателя завершает попытку успешно. RAII освобождает pending slot
  и при timeout/abort. Запись в QUIC сама по себе не является подтверждением приложения.
- Фоновый Connect/Introduction запускается и при уже известном ключе получателя. Существующие
  ограничения частоты и authenticated probe остаются. После успешного знакомства следующие
  отправки могут использовать обычный прямой пул. Не гоняем текущий payload повторно через direct.
- Dedup cache и relay routes привязаны к origin + packet ID. У принятого пакета сохраняем digest
  и подписанную квитанцию. Точный повтор через другого helper получает квитанцию без новой выдачи,
  в том числе при полном cache. Повторный трафик тоже учитывается в admission.
- APUA1/APUX1 и поля подписанных claims не менялись. Более старый получатель сохраняет собственные
  правила dedup; исправление полного cache требует обновления получателя. Нет гарантии достижимости
  через произвольный NAT, кэши ограничены, отказ по лимитам остаётся возможным.

Проверки:
- 7 детерминированных тестов race с виртуальным временем;
- two-helper ACK/dedup при полном cache, конфликт digest/ID, разделение origin,
  forged helper receipt, освобождение pending admission;
- 4 настоящих QUIC-endpoint без внешних сервисов: primary передаёт plaintext, но теряет ACK;
  reserve возвращает подписанный ACK; приложение получателя выдаёт payload один раз;
- существующие peer exchange и direct transport регрессии.
- Релизная сборка запускает эти suites до сборки Android native ABIs.

Не подтверждено этим этапом: реальные Android/NAT/батарея/мобильная нагрузка,
одновременная direct+helper доставка одного сообщения, единый новый offline dispatcher.
После общего deadline уже запущенные bounded transport writes могут завершиться позднее:
packet dedup работает для этого sealed packet, но не обещает глобальную exactly-once семантику
между всеми существующими протоколами приложения.

## Этап 2 — выбор и участие помощников

Подэтап 2a — опубликован v11.74.327:
Release 38161602513 и Core 38161603906 — success. Все helper suites, 3 native ABI,
UniFFI, APK и выбранные Android tests прошли. APK 95 070 071 bytes, GitHub digest
`1573dbf55b69680ffaf9549d85dba64992266b524fd4c1f46fee64a0f60a5ffe`.
Независимый локальный пересчёт скачанного APK и phone tests не выполнены.
- bounded RAM history: 512 пар recipient/helper, monotonic TTL 30 минут;
- EWMA latency только после проверки подписи получателя и только для локально
  запущенной authenticated return leg. Это не публичная репутация и не доказательство
  пути: квитанция получателя сама по себе не содержит helper ID;
- сначала прямое соединение; среди первых 32 authenticated соседей собираем живые,
  стабильно сортируем по локальным наблюдениям, выбираем до двух;
- проигравшие успешную race legs не штрафуются. Transport write failure кратко
  демотирует только эту пару; отсутствующий ACK демотирует лишь записанную leg,
  если ДРУГОЙ путь к тому же получателю недавно подтвердил доставку;
- cooldown до 60 s — предпочтение, не исключение; единственный helper сохраняется.
  По истечении cooldown приоритет ошибок сбрасывается, success обнуляет backoff;
- canceled/unpolled/late legs не записывают фиктивные результаты; RAII освобождает
  pending и при внешнем abort; atomically consumed attempt не штрафуется повторно;
- 7 новых unit tests качества + integration timeout/abort + attribution в forged,
  two-helper и настоящем QUIC тестах; протокол APUA1/APUX1 и UDL не изменены.
Проверки: 7 quality + 7 race + 9 assistance + 14 exchange + 9 direct transport tests
в release gate прошли; локально нет Rust toolchain/Android device.

Подэтап 2b — опубликован для всех v11.74.328 (Latest):
Release 38162612464 и Core 38162636775 — success. 1 policy + 7 quality + 7 race +
11 assistance + 14 exchange + 9 direct transport tests в release gate; сборка трёх ABI,
генерация UniFFI, APK и выбранные Android tests (включая OnlineHelperPolicyTest) прошли.
Generated Kotlin SHA-256 prefix: `990d41b7651a9e6a`. APK 95 090 551 bytes,
GitHub asset digest `a53b22ea07ed4550a82d2cf7c62f4aa5dfdc6848a5d14d8d63520d690f0c7197`.
Локально APK не скачивался для независимого пересчёта; phone/battery tests не проводились.
- отдельное сохранённое разрешение online_helper_enabled (on для совместимости
  с предыдущим автоматическим участием), RU/EN переключатель в «Раздаче»;
- Android применяет policy через UniFFI ДО start(), включая worker wake, обновляет
  настройки на lifecycle executor без блокировки UI, окружение — каждые 30 s в service.
  Уже выполняющийся FFI вызов может задержать применение: мгновенная остановка не обещана;
- Normal: 8 routes / 4 MiB / 120 relays в минуту; Economy: 4 / 2 MiB / 60;
  мобильная сеть и заряд <20% без зарядки делят на 2 каждый. Unlimited: 32 / 4 MiB / 240
  без автоматического снижения (семантика существующего режима сохранена);
- native ceilings нельзя повысить FFI аргументами. Новые Connect/Relay выключаются,
  уже принятые routes/receipts дренируются, собственный Deliver/Receipt остаётся;
- для transit максимум половина aggregate byte/frame admission; Connect использует
  не более половины control slots, Receipt проверяется по одноразовому pending/route
  и не блокируется заполненным replay cache. Это резерв admission, НЕ полный
  приоритет планирования на CPU/QUIC и НЕ измеренная ёмкость телефона;
- toggles не сбрасывают расход за минуту; no disk changes, ServerMode и custody consent
  не меняются. Подписанные cards ещё не объявляются; старый отправитель может пробовать
  opt-out helper и получить отсутствие ACK, но helper не принимает новый transit;
- исправлены RU-only mode.title на экране EN, гонка init/set prefs, устаревший environment
  cache при переводе системных часов назад и опасный fallback на старый UniFFI bridge
  при сбое генератора (теперь release fails closed).
Проверки: source guards (3 online helper, 15 settings, 7 startup) и unresolved symbols
522 Kotlin files прошли локально; native/Android/JUnit — GitHub CI success, как указано выше.

Далее: отдельные подписанные capability cards с совместимостью старых клиентов;
новые объявления никогда не отменяют локальный admission. Полный приоритет своих
сообщений и справедливость источников остаются открытыми поверх резерва admission. Не добавлять поля в deny_unknown_fields claims
без отдельного совместимого протокола/negotiation.

## Этап 3 — общая раздача содержимого

Одна шифрованная копия контента, content-addressed chunks и выдача ключей только авторизованным
участникам. Истории, группы с темами/без тем и каналы. Expiry/revocation, custody consent,
квоты, persistence и миграция предыдущей доставки. Не считать текущие base64 story packets
общей swarm-раздачей. Сначала проверить существующие file who-has / have semantics.

## Этап 4 — вклад и измерения

Статистика для владельца, anti-abuse перед учётом вклада, нагрузочные и phone tests.
Подписанная квитанция доказывает факт acceptance конкретного packet, но сама по себе не
доказывает уникальных людей или честную полезность вклада. Настройка нагрузочных лимитов
только после измерений, не обещать 256 активных пользователей на любом телефоне.

## Сборка после изменения UDL

Релиз и Android compile CI регенерируют Kotlin bridge из того же UDL до Kotlin build;
ошибка генератора блокирует релиз. Tracked bridge/native snapshots не являются текущим
SDK: не редактировать generated binding вручную и не смешивать с новыми native APIs.
Для локальной сборки Windows: `./build-rust.ps1` (все три ABI по умолчанию), затем Gradle;
Linux/macOS: `bash build-rust.sh` (все три ABI). Оба скрипта регенерируют мост из UDL.
При `-Arch` только одного ABI остальные tracked .so остаются старыми — такой APK не
распространять. Bash script: mock 3 ABI + UDL generation + failure propagation — PASS.
PowerShell проверен статически; локального NDK/PowerShell нет.

## Историческая заметка о переходе после v328 (на момент записи ещё не входили в APK)

- TCP diagnostics больше не режет UTF-8 по произвольному byte offset и не пишет
  содержимое сообщений в лог (057fabb). Старый `&text[..text.len().min(100)]`
  мог паниковать на кириллице/эмодзи. Source regression guard проходит; проверка
  ядра запущена для ветки, выпуск с этим исправлением — следующий номер 329.
- Локальные native build scripts теперь синхронно генерируют Kotlin bridge и
  по умолчанию обновляют все три ABI (a9f1aeb); приложение 328 собрано отдельным
  release pipeline и от этих локальных скриптов не зависит.

Этап 2 закрыт в смысле локального admission/ranking/consent/signed-decline в v11.74.329.
Signed capability cards, измеренная ёмкость и полная fairness/own-message scheduling намеренно
отложены: это будущая работа, а не заявленная возможность и не доказательство capacity.

## Подэтап 2c — опубликован v11.74.329

Вместо немедленного расширения подписанных identity/exchange claims добавлен совместимый с N-1
сигнал `Decline` в уже подписанном assistance frame. Старый узел не разберёт новый вариант и
проигнорирует кадр, поэтому старая схема 1500 ms hedge остаётся. Новый helper при выключенной
онлайн-помощи, заполненных routes или лимите байтов отвечает подписанным отказом без
проверки/открытия пакета. Отправитель принимает такой отказ только от того helper, которому он
уже записал текущий pending packet; отказ запускает резерв сразу и понижает этого helper
на 60 секунд для всех получателей, но не исключает единственного helper и не повышает
приоритет. Отказы ограничены 30/min на helper и 512 записями у отправителя.
Capability cards как публичные объявления намеренно не добавлены: они не доказывают
ёмкость и потребовали бы нового wire/negotiation; локальный admission остаётся источником правды.

Release 38166568533 и Core 38166569693 — success; helper gate включает 2 новых теста
quality и signed-decline integration. Независимый пересчёт APK и phone tests не выполнялись.
Этап 2 закрыт как локальный admission/ranking/consent/decline. Подписанные capability
cards НЕ реализованы сознательно (см. выше); возвращаться к ним только вместе с
измеренной ёмкостью этапа 4.


## Сверка восстановленной линии и точка отсчёта (2026-10-12)

- Причина пропажи изменений установлена по Git ancestry: старый head PR #43 (`8f6ce09`, линия
  `arena/7ab0e8d6-apumir`) ответвился от v11.74.206. Сравнение v206 → v329 показывает 276 путей,
  12 919 добавлений и 2 823 удаления; старая линия PR #43 относительно v206 содержала 13 путей,
  1 186 добавлений и 4 удаления. Это подтверждает потерю примерно 13k строк изменений, но не
  означает, что весь репозиторий состоял из 13k строк.
- Полная линия v11.74.329 теперь является предком fixed Arena-ветки; улучшения интерфейса,
  RU/EN-ресурсы и уже выпущенные helper stages 1–2 восстановлены. Опубликованные теги и assets
  не перемещались.
- .330–.336 сохранены в истории/дереве fixed-ветки. Новый tag CI `38246638460` для v11.74.337
  прошёл на объединённом дереве: helper/core suites, 3 native ABI, UniFFI, Android targeted tests,
  assemble/sign/checksum и загрузка assets. v11.74.337 опубликован как Latest; старые теги/assets
  не перемещались. CI не заменяет тест на физических устройствах.

## Этап 3 — фотоистории с content-addressed чанками (частично реализовано)

Что есть в исходниках:
- `ContentAddressedChunks.Assembler.receive()` теперь реализует сборку с проверкой хэша,
  манифеста, размера частей, владельца, TTL и ограничений pending. Ошибка v11.74.335
  (`isValidManifest` отсутствовал) исправлена в `732de6e`; targeted JVM suites и APK сборка
  проходили в workflow #790 на старой линии до восстановления v329.
- Восстановленная линия v329 возвращает в дерево пользовательский путь историй: публикация из
  `ChatListViewModel` → `StoryStore.encodePackets` → `ChatRepository.sendStoryPacket`; входящий
  путь `saveIncomingMessage` → `StoryStore.receive`; экраны читают `StoryStore.active()`.
  Это подтверждение source-level wiring, а не ручная проверка UI.
- Старый формат фото-пакетов сохранён для N-1-совместимости.

Что остаётся до объявления этапа законченным:
- Release CI для объединённого дерева уже прошёл в run `38246638460`; APK собран и опубликован.
  Это подтверждает сборку/целевые тесты, но не пользовательский сценарий на телефоне.
- Проверить отправку/приём фотоисторий на физических устройствах: большие фото, пропуски и
  повторы частей, офлайн-получатель, expiry/revoke и отсутствие показа частичной истории.
- Текущий путь относится к личным фотоисториям; это не общая swarm-доставка для групп, тем
  или каналов, не общий кэш и не обещание повторного использования контента другими участниками.
- Для общей раздачи ещё нужны ключи только авторизованным участникам, expiry/revocation,
  custody consent, quotas, persistence/migration и проверка семантики существующих file who-has/have.

## Этап 4 — вклад, anti-abuse и измерения (не реализован в рабочем контуре)

- После выпуска `.337` ограничен сам in-memory scaffold в `SwarmContributionStats`: peer map
  ограничен 256 LRU-записями, пустые/длинные IDs отклоняются, а refusal window хранит максимум
  10 разрешённых событий за rolling 30 s и зажимает перевод часов назад. Добавлен
  `SwarmContributionStatsTest` (границы, LRU, rollover, reset, конкурентный доступ), а release
  workflow включает этот тест для следующего tag build. Для этих изменений ещё не запускался CI.
- Объект всё ещё не имеет call sites в Android/Rust main-коде. Значит счётчики не собирают реальные
  события доставки/отказа, результат rate guard никем не применяется, пользовательской статистики
  и работающего anti-abuse пока нет. Это подготовленный ограниченный scaffold, не завершённый этап.
- Нет нагрузочных/батарейных измерений и реальных Android/NAT-тестов для helper stages; нет
  доказанной capacity. Подписанные capability cards остаются отложенными до измерений.
- Следующая инженерная работа: определить точные события, подключить счётчики только к
  криптографически проверенным локальным delivery/refusal paths, исключить двойной учёт, добавить
  Rust integration/concurrency tests и только затем проектировать отображение/anti-abuse.
  Сначала instrumentation и измерения, потом — изменение admission limits; не объявлять лимиты
  ёмкостью телефона.

## CI incident: v11.74.335 Kotlin build (2026-10-12)

The tag build failed at `:app:compileReleaseKotlin` because `Assembler.receive()` called
`isValidManifest()` without any implementation. Rust and UniFFI steps passed. The repair adds
strict manifest checks, fixes an `Int` multiplication overflow in the maximum-chunk capacity
check, removes obsolete test code that sat outside `ContentAddressedChunksTest`, and adds
focused regressions. The release workflow now runs the story-photo/chunk JVM suites before APK
assembly. The APK artifact upload was moved after signing/checksum; its previous pre-build position
could never capture the APK. API dispatch from the Arena token returned HTTP 403, so the owner manually started run
38232530545 on `arena/4f0b31c5-apumir`. Native core and UniFFI passed, then `Sync versionName with
tag` failed before unit tests/APK because it assumed every GITHUB_REF was a tag. The workflow fix
was pushed as `47bfbec`: only tag refs rewrite versionName; branch dispatches keep Gradle's
diagnostic version. Manual run 38234091924 then passed version sync but failed in the targeted Gradle
tests before APK assembly. Run 38236089509 did the same; its 4 KiB failure annotation contained only
Kotlin warnings. Source review found a test fixture that encoded 4,001 bytes as 5,336 Base64 chars
(three 2,400-char chunks) but asserted completion after only two chunks; the test now supplies the
third chunk. Failure-tail reporting now filters warnings and stays under the annotation limit; both changes were
pushed in `3889a3d`. API dispatch still returns 403, so the owner must launch from the Actions UI on
the new branch head. Local Gradle is unavailable
because Java is absent; no phone, battery, load, or NAT verification has been performed. Do not
claim device behavior or measured swarm capacity from this repair.

## CI verification run #790 (2026-10-12)

GitHub Actions run `38238290496` passed both targeted JVM suites, `:app:assembleRelease`, release-key
re-signing, and checksum generation. The `app-release.apk` Actions artifact is present (30,078,909
bytes). This was `workflow_dispatch`, so it deliberately did not create a public Release or tag.
The owner approved `docs/RELEASE_NOTES_v11.74.336.md`; tag workflow `38240296358` passed, its APK
and `SHA256SUMS.txt` assets were verified via the GitHub API, and `v11.74.336` is public Latest.
No APK was downloaded locally (sandbox artifact storage returned EOF), and no phone, UI-integration,
battery, load, NAT, or capacity verification has been done.
