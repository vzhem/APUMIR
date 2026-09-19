# APU — правила владельца, тестовые телефоны и незакрытые задачи

**Одна тема на документ.** Точка входа в проект, текущее состояние, релизы и
картотека документов — в `docs/START_HERE.md`. Резервная копия и новый ПК — в
`docs/BACKUP_AND_NEW_PC.md`. Здесь только то, чего нет больше нигде: правила
работы от владельца, телефоны с серийниками, список незакрытых задач и грабли.

Прежние разделы «Проект», «Где что», «Текущее состояние» и «Историческая
справка» отсюда удалены как дубликаты `START_HERE.md`; их текст сохранён в
истории git.

## Незакрытые задачи (по приоритету)

0. **Свежее, сентябрь (раунды 79-96).** v11.69.3 проверена владельцем:
   отступ под «+» и кликабельная ссылка работают. Осталось:
   - worker с `/i` и `/.well-known/assetlinks.json` **опубликован владельцем
     2026-09-07**, проверен живьём. Следующий APK (с `autoVerify="true"`)
     Android сверит с этим файлом при установке;
   - не проверено на телефонах (нужен релиз из ветки): ссылка открывается в
     APU без вопроса «чем открыть», вставка всего пересланного сообщения в
     «Войти по ссылке», нажатие на нашу ссылку внутри чата APU;
   - описание v11.69.3 заменено, пустые релизы v11.69.1/v11.69.2 удалены
     (2026-09-07, с разрешения владельца);
   - **ключ подписи раскрыт**: `android-app/app/p2p-release.jks` и пароль
     лежат в публичном репозитории. Владелец сказал «сделай безопасно».
     Подготовлено (не выполнено): ротация ключа через signing lineage -
     `docs/SIGNING_KEY_ROTATION.md` (порядок из 5 шагов),
     `scripts/rotate-signing-key.ps1` (новый ключ в `C:\APU-KEYS`, lineage,
     секреты GitHub), `scripts/ci/build-release.yml` (workflow с шагом
     `Re-sign…`; владелец копирует его в `.github/workflows` сам - сделано
     2026-09-09, коммиты `2909051` и `ae9076d`; шаг без секретов лишь
     предупреждает; 2026-09-15 владелец скопировал ещё раз - `abbb5c4`,
     `packages: platform-tools`, см. грабли «CI падает до сборки»). Телефоны
     обновятся поверх - переустановка не нужна. Старый ключ из репо НЕ удалять:
     он подписывает v1/v2 для Android 8 и нужен для lineage.
   Отложено по решению владельца: отчёт «прочитано» в ГРУППАХ (лавина пакетов),
   общесетевой счётчик сердечек и просмотров (нужен справочник на сервере),
   поиск людей по никнейму `apu://u/имя` (нужен справочник имён; сейчас
   ссылка разбирается, но никого не находит - НЕ включать без справочника).

0j. **Звонки через мобильную сеть — мост через брокер (v11.70.14, выпущен
   2026-09-12, apk sha256 `d26719b2…da3e`) и прямой UDP + STUN (v11.70.15,
   выпущен 2026-09-12, apk sha256 `bf0724c2…758b`, CI с первого раза);
   на телефонах НЕ проверено.** Жалоба владельца: в одной Wi-Fi
   звонки работают, через мобильную сеть «трудно соединиться, разговор
   ненормальный, иногда доходят и срываются». Диагноз по коду: (1) LAN-адрес
   за NAT оператора бесполезен; (2) прямой QUIC без hole punching через
   мобильную сеть не поднимается (новый endpoint на каждую отправку,
   рубильник 45 с); (3) голосовой фолбэк гнал сырой PCM 32 КБ/с через
   `sendMessageMqtt`, который открывает НОВОЕ соединение с брокером на каждую
   публикацию (~1 с), против троттлинга публичных брокеров ~1 КБ/с и предела
   10 КиБ на пакет — голос не доезжал вовсе; (4) приёмник видел пакеты брокера
   только при опросе раз в 5 с → сигналы ехали до 5 с на шаг, машина хоронила
   звонок по «голоданию» (`bye|failed`). Сделано: `CallBrokerLink` (одно
   постоянное MQTT-соединение на звонок, чистый Kotlin, тема `apucall1/…` вне
   `p2pm2/#`), `MqttPacket`, `CallLinkWire`, `AdpcmCodec` (164 Б/20 мс),
   `cap`-рукопожатие и `ac`-бандаж в `CallWire`, порядок транспорта
   LAN → мост → QUIC → текст, джиттер-буфер под интернет, переезд LAN → мост
   посреди разговора, статус «Через интернет — сжатый звук, возможна
   задержка». Карта — `CALLS_BOOTSTRAP.md` §8.2/§8.4. Честно: ADPCM ≈ 8 КБ/с
   всё ещё выше троттлинга публичных брокеров — мост best-effort.
   **v11.70.15 (владелец 2026-09-12: «внешние ресурсы подключать можно и
   нужно», но при их блокировке приложение обязано работать):** прямой UDP
   между телефонами — `CallUdpChannel` (сокет на звонок, кандидаты: STUN
   `stun.l.google.com`/`stun.cloudflare.com` с таймаутами DNS 1,5 с + ответ
   1,5 с, неудача не фатальна; + IPv6/LAN интерфейсов), `StunCodec` (RFC 5389,
   тест `StunCodecTest` по векторам RFC 5769), пакеты `cand`/`probe` в
   `CallWire`, пробивание NAT пробами раз в 200 мс внутри шифрованного
   `CallLinkWire`-control, фиксация адреса только по `seen=1`/подлинному
   пакету, keep-alive 4 с, смерть 8 с → обратно на мост; порядок
   LAN → UDP → мост → QUIC → текст; статус «Через интернет — прямое
   соединение». Компилятора в песочнице нет — первый компилятор = CI тега.
   Проверка на телефонах: два телефона в разных сетях (Wi-Fi + мобильная,
   две мобильные), лог `CallManager: media channel: direct UDP (...)` или
   `broker link`, статус на экране, разговор ≥ 1 мин, смена сети посреди
   разговора (UDP → мост). Симметричный NAT с обеих сторон = UDP не пробьётся
   (ожидаемо) — остаётся мост; следующий шаг для этого случая — свой relay
   (Cloudflare Worker / WebSocket), разрешён владельцем. Старые сборки
   (< v11.70.14) `cap`/`ac`/`cand`/мост/UDP не знают — с ними всё как раньше
   (PCM, текстовый фолбэк).

0u. **v11.70.25 - ядро, этап K2: файл группы полосами от нескольких сидов
   (выпущен 2026-09-17, Latest; размер и sha256 - `START_HERE.md` §3; карта
   протокола и проверка на телефонах - `CHANNEL_SWARM_DESIGN.md` §9.4 «Этап
   K2»; план - `CORE_ROADMAP.md` K2).** CI собрал с первого раза (прогон
   35174302441): Rust, перегенерация моста (`createGroupFileManifest`
   появилась в `p2p_core.kt`, коммит CI `472f42a` в `main`) и Gradle. На
   телефонах НЕ проверен. Что где:
   - Rust: `crypto/file_transfer.rs` - `FILE_TRANSFER_VERSION_V3_GROUP`=3,
     `GROUP_SCOPE_PREFIX`/`MAX_GROUP_SCOPE_BYTES`, `is_group()`,
     `is_group_scope()`; `validate()` для V3 требует отправителя `pk_` и
     получателя `grp_…`, для V1/V2 - как раньше (метка группы там =
     `InvalidPeer`); `from_canonical_bytes` принимает 3. Шифрование кусков
     не менялось - совместимость V1/V2 полная. `lib.rs` -
     `create_group_file_manifest` (копия `create_file_transfer_manifest` с
     версией 3) + тест `group_file_manifest_ffi_round_trip`; `lib.udl` -
     объявление перед `parse_file_transfer_manifest`.
   - Kotlin, провод: `FileTransferPacketCodec.Type.WANT(9)` (payload =
     `FileCustodyPdu.encodeWant`, itemIndex = мой непрерывный префикс);
     `FileTransferWire.groupAckMessageId` (`f<tid>g<seedTag>a<n>`) и
     `groupWantMessageId` (`f<tid>g<meTag>w<seedTag>s<seq>`);
     `GroupWire.FILE_WANT_GROUP_MARK`=`#g1` в хвосте id сообщения `fwant`
     → `Packet.FileWant.groupCapable`; `GroupFileMarker.SCOPE_PREFIX/scope()/
     isScope()`; `FileTransferChatRouting.GROUP_SCOPE_PREFIX/isGroupScope()/
     groupScope()`; `FileTransferDao.getSeeding()` (+ `FakeFileTransferDao`).
   - Автор: `OutgoingFilePreparationService.prepareGroupCopy(file, name,
     mime, messageId, groupId, sha256)` - строка `OUTGOING`, состояние
     `PREPARING` → сразу `SEEDING` (минуя `PREPARED`, чтобы
     `FileTransferSender.getActiveOutgoing` её не подхватил), `peerNodeId`
     пустой, готовая копия переиспользуется (ключ, манифест и все куски на
     месте), недоделанная - стирается и делается заново; возвращает null,
     если ядро без функции. `wrapGroupKey(transferId, requester)` - конверт
     под просителя (бросает «binding is not pinned», если его ключ не
     закреплён).
   - Сид: `data/file/GroupFileSeeder.kt` - `offer(row, requester) →
     SENT/NO_KEY/UNREACHABLE/BUSY`, `onAck/onWant/onCancel/pump/forget`,
     `canSeed(row, keyReady)`, `isGroupManifest(bytes)` (разбор
     канонических байт без ядра), `MAX_LEGS`=6, `OFFER_TIMEOUT_MS` 2 мин,
     `STALL_TIMEOUT_MS` 10 мин, `mayServe` (рой: участник, не забанен) для
     плеч, поднятых по ACK/WANT после перезапуска. Создаётся в
     `FileTransferRouter` (новый параметр конструктора
     `preparation: Provider<OutgoingFilePreparationService>` - цикл Dagger
     через Provider, как у роя), качается в `pumpOutgoing`.
   - Приёмник (`FileTransferReceiver`): `GroupSeedHooks(onAck, onWant,
     onCancel, onOfferAccepted)`; `groupSeeds` (память); `isSeedRow` =
     `OUTGOING/SEEDING` или `INCOMING/COMPLETE` - ACK/WANT/CANCEL по таким
     строкам уходят сидеру; `handleOffer`: манифест с меткой группы
     принимается от любого сида, если `routeOffer` не `Unknown`, метка ==
     `groupScope(chatId)`, тот же transferId = ещё один сид (до
     `MAX_GROUP_SEEDS`=4, лишним CANCEL), конверт хранится первый, ключ
     импортируется один раз; `handleChunk`: кусок общей копии только от
     известного сида и только после `verifyGroupChunk` (расшифровка
     ключом файла; провал = сид вычёркивается); `sendFileAck` →
     `sendGroupAcks` (ACK тому, кто прислал кусок; всем - при инвентаре и
     по завершении) и `sendGroupInventory` (`FileCustodyPdu.assign`,
     `HOLDER_STALE_MS` для живости, `INVENTORY_INTERVAL_MS` 10 с);
     `finalizeTransfer` - итоговый ACK каждому сиду; `declineTransfer` -
     CANCEL каждому сиду.
   - Рой (`GroupFileSwarm`): `ask` шлёт `fwant#g1` одному сиду (как
     раньше); `onSeedJoined` (из приёмника, только по предложению с общим
     манифестом) зовёт следующего, пока сидов < `STRIPE_SEEDS`=3
     (`Pending.striping` - кто уже шлёт полосы; при простое ≥ `STALL_MS`
     очищается, чтобы переспросить);
     `onFileWant` при `groupCapable` → `serveShared` (`sharedSource`:
     готовая общая строка или `prepareGroupCopy` под `prepLocks`; `BUSY` →
     `waiting` с `shared = true`, `serveWaiting` считает такие по плечам
     сидера, личные - по передачам); `onServed` → `rememberSeed` +
     `servedCounts` (StateFlow) → `GroupChatUiState/ChannelUiState.
     servedFiles` → `FileCardState.of(servedCount)`; `mayServeFile`;
     `onGroupGone` стирает общие копии группы.
   - Совместимость: старый сид `#g1` не понимает (хвост остаётся в его
     `messageId` исходящей строки - безвредно) и отдаёт личную копию -
     приёмник берёт её как раньше; старый проситель метку не шлёт - новый
     сид идёт по старому пути (`serveShared` не зовётся). `Type.WANT` и
     манифест V3 старые телефоны молча отбрасывают, но им их никто не шлёт.
   - Не сделано в K2: инвентарь идёт через обычный транспорт (LAN/брокер) -
     как у хранителей; личные файлы остаются V2; ретрансляция общей копии
     хранителями (этап 7) с V3 не проверялась (общий манифест хранитель
     переслать не сможет - `handleForwardedOffer` требует отправителя =
     origin; для файлов групп хранители и не используются).

0v. **K3 - ядро: куски личных файлов бинарными APUF-кадрами по прямому
   QUIC (выпущен тегом v11.70.28, 2026-09-18; до этого - ветка
   `arena/01a0af3e-apumir`, PR #6). Первый тег v11.70.27 не собрался:
   `sendFcap` вызывала suspend-метод `transport.send` из обычной функции
   (лечение - `private suspend fun sendFcap`). На телефонах НЕ проверено,
   сборка - CI тега, как всегда. Что где:**
   - Rust, провод: `file_wire.rs` (кодек F4-B1, магик `APUF`, НЕ менялся —
     подключён): кадр = `magic[4]|ver|type|flags|len|payload`; ChunkData =
     `transfer_id[16]|chunk_index:u64|chunk_offset:u32|cipher_len:u32|
     data_len:u32|диапазон`; предел 256 КиБ; range-кадры (диапазоны куска)
     с первого дня. `quic_client.rs`: `send_file_data` (тот же стрим
     «длина + payload», но `prioritize_file_data_stream` = приоритет -10).
     `direct_transport.rs`: `JobKind::{Interactive,FileData}`,
     `send_file_blocking`, `send_one` шлёт кадр нужным стримом; пул,
     усыновление, бюджеты — прежние.
   - Rust, ядро: `engine/core.rs` — `handle_direct_frame` первым делом
     пробует `FileFrameV1::decode` при магике `APUF`: ChunkData → событие
     `FileChunkReceived` (отправителя в кадре нет → `None`, усыновления
     нет); Capabilities — тихо; ошибка — warn+`None`. `send_file_chunk`
     (FFI) собирает кадр из аргументов и шлёт `send_file_via_quic`.
     `events.rs`: вариант + тип `file_chunk_received`.
   - Мост: `lib.udl` — `send_file_chunk(recipient, transferIdHex,
     chunkIndex, offset, cipherLen, range)` и +5 полей `CoreEventFfi`
     (transferId, chunkIndex, chunkOffset, ciphertextChunkLen, payload).
     `p2p_core.kt` (git-копия) дополнен ВРУЧНУЮ под новый UDL: новые
     поля/метод/конвертеры `FfiConverterOptionalUInt`/`OptionalByteArray`;
     checksum для нового метода в git-копий НЕТ (константу считает только
     bindgen) — на релизе CI перегенерирует файл, и это не важно; если CI
     перегенерацию не сделает — проверить, что git-копия работает
     (вызовы идут, событие читается).
   - Kotlin, провод: `FileTransferPacketCodec.Type.FCAP(10)` (payload 4 Б
     BE max_frame_payload; пустой = дефолт 256 КиБ),
     `FileTransferWire.fcapMessageId` (`f<tid>cap`), константы
     `BINARY_MAX_FRAME_PAYLOAD=256*1024`, `BINARY_CHUNK_PREFIX_BYTES=36`.
   - Kotlin, отправитель: `FileTransferSender.binaryTransport` (
     (peer, tid, chunkIndex, offset, cipherLen, range) -> bool),
     `markBinaryCapable` (по FCAP), окно = `BINARY_INFLIGHT_BYTES`
     (2 МиБ) / размер куска; кусок делится на диапазоны ≤ кадр, каждый —
     вызов `RustBridge.sendFileChunk`; любой `false` →
     `RecipientOfflineException` (перерыв, повтор с префикса ACK;
     принятые диапазоны идемпотентны). БЕЗ FCAP — прежний текстовый путь.
   - Kotlin, приёмник: `FileTransferReceiver.onBinaryChunk` —
     `BinaryChunkAssembler` (буфер expectedLen, пересечения = брак, дубль
     точный = ок), при сборке — обычный `handleChunk` (AEAD, диск, ACK);
     до оффера — `bufferOrDropChunk`; CUSTODY/OUTGOING/групповые — drop;
     FCAP: `handleFcap` (только от адресата нашей OUTGOING) →
     `onFcap` → `sender.markBinaryCapable`; `sendFcap` — в конце
     принятого ЛИЧНОГО оффера (групповым не шлём).
   - Kotlin, сервис: `CoreServerService` — ветка `file_chunk_received`
     (UInt→Int, null-полей нет → drop); поллинг: пока очередь не пуста или
     партия ≥ 8 — шаг 100 мс, иначе 5 с (как было).
   - Совместимость N↔N-1: старые телефоны APUF-стрим принимают как
     «сообщение», первое поле не `pk_…` → отбрасывают (страж
     от призраков); FCAP (`Type.FCAP`) — неизвестный тип → молча drop.
     Новые без FCAP-ответа партнёра — текстовый путь, как было. Формат
     старых кадров не тронут.
   - Тесты: Rust (quic_client ×2, direct_transport ×1, core: hex-пары +
     APUF-входящий ×4), JVM (Sender ×4, Receiver ×7, Codec ×1, Wire ×1).
     Локальной компиляции нет (в песочнице ни cargo, ни Gradle): первый
     компилятор — CI тега; JVM-тесты уходят в `groups-build-gate.ps1`
     на машине владельца (`testDebugUnitTest`).

0x. **Рой APK: обновление APU раздаётся телефонами роем, без сервера
   (задача владельца 2026-09-18; карта — `docs/UPDATE_SEEDING.md`).**
   Сделано на ветке `arena/01a0b3cb-apumir`, БЕЗ РЕЛИЗА (релиз — только с
   разрешения владельца). Куски APK идут по тому же пути, что файлы групп
   (K2): виртуальное сообщество `apkseed` → метка `grp_apkseed`
   (`is_group_scope` в ядре такую метку принимает), один общий манифест,
   конверт ключа — каждому просителю свой. Файловая машина
   (манифест/конверты/куски/ACK) НЕ трогалась. Провод — ЧЕТЫРЕ НОВЫХ вида
   пакетов (старые телефоны молча отбрасывают, форматы старых не менялись):
   `upk|ver|sha256|size|atMs` (раздаю), `upwant|ver|sha256|b64(привязка)`
   (пришли мне), `upnone|ver|sha256` (нет, спроси другого), `upask|версия`
   (у тебя есть что-нибудь новее, чем у меня? — шлёт кнопка «Проверить
   новую версию»; сид отвечает одним `upk` лично спросившему, только если
   версия новее).
   Что где:
   - `data/group/GroupWire.kt` — виды `upk`/`upwant`/`upnone`, пакеты
     `UpdatePack/UpdateWant/UpdateNone`, билдеры и разбор; версия на проводе
     — строгое числовое 1–4 компонента по ≤4 знака (≤23 знака), размер ≤4 ГБ.
   - `data/update/ApkUpdate.kt` (новый) — `CHAT_ID="apkseed"`,
     `DIR_NAME="apk_seed/v1"` (в `noBackupFilesDir`), разбор/сравнение версий
     (`v`-префикс и хвосты `-beta`/`+abc` прощаются локально, на проводе —
     чистое число), `looksLikeApk` (ZipFile: AndroidManifest.xml +
     classes*.dex), sha256, размер.
   - `data/update/ApkUpdateStore.kt` (новый) — на диске в том же каталоге:
     моя помеченная версия `APUSEED1|…`, объявления соседей `APUOFFER1|…`,
     моя незавершённая просьба `APUREQ1|…`; tmp+ATOMIC_MOVE, битый файл →
     null/пусто, узлы только `pk_`.
   - `data/update/ApkSeeder.kt` (новый, сердце) — обе роли: СИД
     (`markAsSeed` — проверил «это APK и версия новее», скопировал файл,
     `prepareGroupCopy` → строка `OUTGOING/SEEDING`, объявил `upk`;
     `markReceivedApkAsSeed` — уже принятый APK раздаётся строкой
     `INCOMING/COMPLETE` без перешифровки; `onUpdateWant` — закрепляет ключ
     просителя как у `fwant`, предлагает `GroupFileSeeder.offer`, все
     отказы — `upnone`); проситель (`onUpdatePack` — держит только версии
     НОВЕЕ своей, по узлу лучшее объявление; `requestUpdate`/`pump`/
     `onUpdateNone` — круг просьб по сидам, порядок —
     `SwarmPeerDirectory.order`; `routeApkOffer` — чужое предложение
     принимается только если я его запрашивал; `onFileReceived` — «получено»
     + СРАЗУ авто-раздача скачанного; `maybeAutoReseed` — после установки и
     перезапуска раздача продолжается той же строкой; `installReady` —
     FileProvider, как у UpdateChecker). Помпа — в `filePumpJob`
     (CoreServerService), «сид появился» — в `peer_discovered`.
   - `data/group/GroupRepository.kt` — три хука `onUpdatePack/Want/None`
     (по умолчанию no-op, JVM-тесты живут), ветки разбора.
   - `data/file/FileTransferRouter.kt` — `Provider<ApkSeeder>`; `routeOffer`:
     сначала рой групп, не знает — сидер APK; завершение передачи: сначала
     сидер APK (не пишет строку в «чат apkseed»), потом рой групп;
     `GroupFileSeeder.mayServe` для `apkseed`: не членство, а любой узел
     `pk_`, не я (правило «только младшим» держит проситель — старшие сами
     не просят).
   - `ui/screens/settings/*` — раздел «Обновления»: моя раздача («получили:
     N» + «Остановить»), предложение соседа (только новое, «Скачать v…» —
     версия на кнопке), приём с прогрессом и «Остановить», «Обновить до vX»
     (скачано — установка + раздача уже идут), «Скачать официальный v…»
     (релиз GitHub, если проверка нашла), «Раздать полученный APK»
     (сценарий: владелец переслал APK с ПК — отметить файл и
     ввести/подтвердить версию), «Отметить APK как обновление» (SAF-выбор
     файла, версия в диалоге), «Проверить новую версию» (кнопка: `upask`
     всем соседям + GitHub + перечитать принятые APK).
   - Тесты: `GroupWireUpdateTest` (виды, разбор, границы), `ApkUpdateTest`
     (версии, `looksLikeApk`, sha256-векторы, хранилище: round-trip,
     повреждённые файлы).
   Проверено: только в песочнице — main()-прогон по чистому JVM-коду
   (56 проверок: провод, версии, хранилище) и диагностика компиляции
   (новые ошибки — только «нет библиотек Android/Hilt/kotlinx» того же
   вида, что в baseline). **Первый настоящий компилятор — CI тега; на
   телефонах НЕ проверено ничего.** Для проверки на телефонах: два
   телефона в одной Wi-Fi, один с новым APK отмечает его в
   «Обновлениях» (версия новее версии второго), на втором появляется
   «Новая версия v…» → «Скачать» → прогресс → «Установить»; лог:
   `ApkSeeder: seeding update`, `ApkSeeder: update want`,
   `GroupFileSeeder`-строки кусков. Грабли: `main` обязан догнать
   рабочую ветку (fast-forward после пуша); CI-лог из песочницы не
   читается — ошибки CI смотреть владельцу.

   **Продолжение (та же ветка задач, 2026-09-18, вторая сессия, БЕЗ
   релиза) — автоматизация раздела «Обновления» по просьбе владельца:**
   (1) скачанный с сайта APK сам встаёт в раздел — `UpdateChecker.
   completedApkDownloads()` (успешные загрузки DownloadManager) +
   `ApkSeeder.adoptDownloadedApk/adoptCompletedDownloads`: версия читается
   ИЗ САМОГО APK (`getPackageArchiveInfo`, fallback — числа из имени),
   файл проверяется, ставится в раздачу (autoReseed) и поднимает карточку
   «Обновить до vX» (у `Ready` новое поле `sourcePath` — установка по
   локальному файлу, когда строки передачи ещё нет; при перезапуске
   карточка восстанавливается из записи о раздаче); триггеры: BroadcastReceiver
   на `ACTION_DOWNLOAD_COMPLETE` (живёт в ApkSeeder, регистрируется в
   init, NOT_EXPORTED на API 33+), догон при старте и при открытии
   настроек и раз в 5 минут в помпе; взятые/окончательно отклонённые
   файлы помнятся в `adopted.v1` (`APUADOPT1|path|b64(name)|sha|size|at`,
   потолок 32) — большой файл не пересчитывается зря. (2) Имя и версия
   из файла при SAF-выборе: `SettingsViewModel.ApkPickUi` — имя из
   `OpenableColumns.DISPLAY_NAME`, версия из архива (временная копия в
   кэше), диалог показывает имя/размер и подставляет версию сам
   (`markAsSeed` получил параметр `displayName`). (3) Кнопка «Проверить
   новую версию»: если нашла И официальный релиз, И соседей — карточка
   показывает ДВЕ кнопки («С официального сайта v…» / «По сети v…,
   кусками от N сосед(ей)»). (4) Куски со ВСЕХ сидов: `onOfferAccepted`
   маршрутизатора зовёт и `ApkSeeder.onSeedJoined` — пока сидов меньше
   `STRIPE_SEEDS` (3), просим следующего известного сида той же версии
   (`upwant`); приёмник и так принимает до 4 сидов одного файла
   (MAX_GROUP_SEEDS). Версия в списке принятых APK — тоже из самого
   файла. Тесты: `ApkUpdateTest` + `versionFromName`, adopted round-trip/
   вытеснение/битый файл. struct_check по 9 файлам — чисто; настоящий
   компилятор — только CI владельца.

   **Выпущено: v11.74.0 (2026-09-18, Latest, APK 39 053 574 байт;
   sha256 — на странице релиза; на телефонах НЕ проверено).** Порядок
   выпуска, отработавший здесь: (1) пробный тег `v0.0.0-sandbox-checkN`
   на том же коммите — CI реально компилирует, релиз-prerelease телефоны
   не видят; после проверки release и тег удалить; (2) настоящий тег
   `v11.74.0` на том же коммите — зелёный гарантирован; (3) promote:
   `gh release edit --notes-file docs/RELEASE_NOTES_v11.74.0.md`,
   `--prerelease=false`, затем `--draft=true` → 10 с → `--draft=false`
   (иначе /releases/latest не смещается — грабля v11.20.0), проверка
   `/releases/latest`. **Выпущен фикс v11.74.1 (2026-09-18, Latest, APK 39 053 602 байт, с
   явного разрешения владельца):** жалоба «кнопка „Обновить до vX" не
   реагирует» — причина: принятый по сети APK лежит в
   `noBackupFilesDir/file_received/…`, которого НЕ БЫЛО в
   `res/xml/file_paths.xml` (покрыты только external/cache/files) →
   `FileProvider.getUriForFile` бросал «Failed to find configured root»
   ВНЕ runCatching → корутина молча умирала. Фикс: `<root-path name="root"
   path="."/>` в file_paths.xml + `installReady` возвращает текст ошибки
   (тост в настройках; молчаливых отказов больше нет) + регресс-тест
   `fileProviderPathsCoverInternalStorage` (JVM: читает file_paths.xml от
   working dir модуля). Ещё грабля между сессиями: платформа пересобирает
   песочницу — история ветки схлопывается в один коммит поверх базового,
   а на origin остаётся прежняя цепочка; пуш тогда отклоняется — лечится
   `git reset --hard origin/<ветка>` + перенос файлов фикса
   (`git checkout <бэкап> -- файлы`). ГРАБЛЯ №1 (ловится только настоящей компиляцией):
   sandbox-проверки структуру ловят, но не «Unresolved reference» — в
   первом прогоне пало `DownloadManager.COLUMN_MIME_TYPE` (нет такого
   константа; правильно `COLUMN_MEDIA_TYPE`). ГРАБЛЯ №2/открытие:
   журнал Actions из песочницы не читается (results-receiver и
   release-assets закрыты), НО аннотации check-run читаются:
   `gh api repos/vzhem/APUMIR/actions/runs/<id>/jobs` → job id →
   `gh api repos/vzhem/APUMIR/check-runs/<job_id>/annotations` — там
   строки `e: файл:строка: Unresolved reference …`. Смарт-каст-грабля:
   условие в локальной переменной (`val both = a != null && b != null`)
   НЕ даёт smart cast внутри `if (both)` — делегированные
   collectAsState-свойства тоже; нужны локальные копии. Тег стоял на
   ветке arena (не на main) — сборке это не мешает (v11.73.0 так же);
   догнать main — sync-main.ps1 владельца.

   **Дополнение (2026-09-18, вечер): обновление в «жёсткой» мобильной
   сети.** Жалоба владельца: «на телефонах нет объявления об обновлении
   11.74.1» — на мобильном интернете сеть пускает только белый список
   хостов (наш relay `p2p-relay.1985vzhem.workers.dev` там есть, GitHub
   нет), а объявление проверялось ОДИН раз при холодном старте и молча
   (`catch` → null). На Wi-Fi объявление появилось — диагноз подтверждён.
   Сделано: (1) `MainActivity` — повторная проверка при каждом onResume
   (не чаще раза в 5 минут, лимит GitHub 60/час; «Позже» запоминает
   версию в сессии — более новую покажет); (2) `UpdateChecker` — при
   недоступности GitHub запасной путь через relay: worker сам ходит на
   GitHub (`/update/latest` → tag_name/notes/published_at/apk_url) и
   отдаёт APK со своего домена (`/update/apk`, поток, Content-Length,
   репозиторий и имя файла зашиты — открытого прокси нет); (3)
   `tools/worker/p2p_relay_worker.js` — новые маршруты. ВАЖНО: worker
   ОБЯЗАН быть передеплоен владельцем (Cloudflare → Workers → p2p-relay →
   Edit code → заменить содержимым tools/worker/p2p_relay_worker.js →
   Deploy) — со старым worker приложение просто не найдёт запасной путь
   (404 → null, как и раньше, не хуже). Рой тут не спасает: куски APK
   идут только по прямому LAN/QUIC, в жёсткой мобильной сети его нет.
0y. **Рой и «торрент» через мобильную связь (задача владельца
   2026-09-18; карта — `docs/SWARM_MOBILE.md`).** Сделано на ветке
   `arena/01a0b3cb-apumir`, БЕЗ РЕЛИЗА. Почему раньше не работало:
   куски и сигналы файлов ходили только прямым (LAN в одной Wi-Fi или
   QUIC, а QUIC за CGNAT мобильной не поднимается), иначе передача
   «засыпала»; рой при этом видел соседа по сигналам брокера. Решение —
   Kotlin только, Rust НЕ тронут: UDP-канал для файловых пакетов с
   пробиванием NAT (тот же движок, что у звонков: STUN + пробы каждые
   200 мс + `seen=1` + keep-alive 4 с + смерть 8 с), кандидаты меняются
   сигналами `APUUDP1|ufseek|b64(привязка)|адресы|b64(sessionTag)` и
   `ufcand` через durable-брокера (по мобильной доходит), данные — те
   же тексты `apu-file1|…`, что по LAN (режутся на части ≤1160 Б,
   датаграмма ≤1231 Б, сборка по fragId, TTL 10 с). Потерянные
   датаграммы добираются WANT/ACK (протокол идемпотентен по
   (transferId, chunkIndex)).
   Что где:
   - `data/file/FileUdpWire.kt` (новый, чистый Kotlin) — формат:
     датаграммы (проба/данные, магия `APUUDP01`), сигналы, сборщик
     (LRU 64 + TTL), `isSplittableText` (только ASCII и не пусто).
   - `data/file/FileUdpChannel.kt` (новый) — канал на собеседника
     (сокет, STUN через `StunCodec`, пробы, lock по `seen=1`, keep-alive,
     смерть по тишине 8 с, закрытие по простоя 60 с) и менеджер
     `FileUdpChannels` (один канал на узел, мёртвый заменяется,
     `ufseek` троттлинг 30 с, проверка подписанной привязки в сигналах
     И пробах — чужие пробы отбрасываются).
   - `data/file/FileTransferRouter.kt` — `udpChannels` (сигналы через
     `RustBridge.sendMessage`, привязки через `uniffi.p2p_core`);
     цепочка `directSend`: LAN → UDP (`ensureRequested`+`trySend`) →
     QUIC → брокер (только если собеседник в сети по presence, иначе
     пауза как раньше); режим на узле (`lan|udp|quic|broker`) хранится
     в `lastDirectMode`; `routeIncoming` — ветка `isUdpSignalText` (до
     HELLO); `onFcap` не включает APUF при режиме UDP; `binarySend` —
     понижение в текст через `sender.demoteBinary`.
   - `data/file/FileTransferSender.kt` — `demoteBinary(transferIdHex)`.
   - `data/RustBridge.kt` — `sealOutgoing`: `APUUDP1` не запечатывается
     (как `APULAN1`: адрес + публичная привязка, секрета нет).
   АPUF (K3) остаётся только за QUIC: по UDP ходит текстовый путь.
   Рой групп и APK-сидер от этого получают мобильную автоматически —
   они шлют через тот же `directSend`.
   Проверено: в песочнице JVM-прогон — формат/сборка (41 проверка:
   round-trip проб/датаграмм/сигналов, отбраковка битых, сборка
   вне порядка, дубли, TTL, IPv6-литералы) и loopback-прогон канала —
   handshake `seen=1` через сигналы менеджера, доставка фрагмента
   5.6 КиБ в обе стороны, чужой `sessionTag` не доставляется, чужие
   пробы (подпись не узла) канал не ломают. Диагностика компиляции:
   новых ошибок нет (только «нет Android/kotlinx» того же вида, что в
   baseline; `when`-exhaustiveness — тот же артефакт, что у
   `CallUdpChannel`, в CI 2.0.21 не падает). **Первый настоящий
   компилятор — CI; на телефонах НЕ проверено.** Для проверки: два
   телефона на МОБИЛЬНОЙ (разные операторы — лучший вариант), один
   раздает файл группы или APK, на втором — прогресс; лог:
   `FileUdpChannels: ufseek sent to …`, `FileUdpChannel: udp
   candidates …`, `FileUdpChannel: udp punched for …`. Симметричный NAT
   с двух концов — штатно скатится в брокер (медленно).
0z. **Азбука адресов — постоянный файл + посев «своих» (задача
   владельца 2026-09-18; карта — `docs/ADDRESS_BOOK.md`).** Сделано на
   ветке `arena/01a0b3cb-apumir`, БЕЗ РЕЛИЗА. Суть: ядро само ведёт
   файл `<filesDir>/apu_peer_addresses.json` (рядом с
   `apu_relay.sqlite`, путь уже есть у движка — FFI не добавлялось):
   JSON v1 `{"v":1,"entries":[{id,addr,seen}]}`, запись tmp+rename,
   TTL 30 дней, потолок 1000 записей (самые свежие), save не чаще
   раза в 15 с + flush при `stop()`; при `start()` файл грузится и
   засевает `peer_addrs`. Пишут 7 точек: DHT-ответ (цель + ≤3
   ближайших), mDNS (LAN / alias / публичный), брокер-presence,
   invite-привязка и НОВОЕ — входящее QUIC-соединение (хук `on_inbound`
   при усыновлении: реальный адрес соединения раньше хранился только
   в пуле и терялся). Kotlin одно: после `Engine OK`
   `CoreServerService` засевает список «своих» СУЩЕСТВУЮЩИМ методом
   `set_presence_audience` (был в UDL, 0 вызовов) — контакты ∪
   проверенные обмены ∪ владельцы/админы групп (`SwarmPeerDirectory.
   audienceIds()`); личное presence в ≤60 с стучит сохранённым адресам,
   онлайн-соседи отвечают presence со СВЕЖИМ адресом — файл
   обновляется. Новых кадров и новых FFI-функций НЕТ. Где:
   - `rust-core/src/resilience/address_book.rs` (новый) — модуль
     `AddressBook` (open/seed/record/flush, prune, атомарный save,
     unit-тесты).
   - `rust-core/src/engine/core.rs` — поле `address_book`, seed в
     `start()`, flush в `stop()`, `record()` в 6 точках, `on_inbound`
     при старте `DirectTransport`.
   - `rust-core/src/network/direct_transport.rs` — `on_inbound`
     (необязательный колбэк, по умолчанию нет).
   - `data/RustBridge.kt` — `setPresenceAudience(ids)` (try/catch+лог).
   - `data/swarm/SwarmPeerDirectory.kt` — `audienceIds()`.
   - `service/CoreServerService.kt` — посев после старта движка.
   **ВАЖНО, перед первой сборкой (разовый шаг на ПК владельца):**
   `set_presence_audience` давно в `lib.udl` (K4-1), но закоммиченный
   сгенерированный `android-app/.../uniffi/p2p_core/p2p_core.kt` НЕ
   перегенерировали с тех пор — в нём нет `setPresenceAudience`, и
   приложение не скомпилируется, пока не перегенерировать:
   `cargo run --manifest-path ..\tools\uniffi-bindgen\Cargo.toml -- generate src/lib.udl --language kotlin --config uniffi.toml --out-dir ..\android-app\app\src\main\java`
   (из каталога rust-core; то, что делали раньше). Ручную правку
   сгенерированного файла НЕ делать — ABI должна совпасть с .so.
   Проверено в песочнице: JVM-диагностика Kotlin-части — ровно 1 новая
   строка `unresolved reference 'setPresenceAudience'` (исчезает после
   перегенерации выше) + 1 новый FP того же класса «suspend … can only
   be called from a coroutine», что 185 уже в baseline (вызов внутри
   `serviceScope.launch`); Rust — БЕЗ компилятора (нет cargo),
   unit-тесты модуля будут ходить в CI. **Части 1-2 (файл + посев)
   собраны в v11.71.0 (CI-зелёное), на телефонах НЕ проверены.**
   **Часть 3 (задача владельца 2026-09-18, коммит `ac242bc`, ЛОКАЛЬНО,
   не собрана — сессия закрылась до пуша): срочное рассылание при смене
   СОБСТВЕННОГО адреса.** STUN при смене своего адреса (и первом
   узнавании) ставит флаг `own_addr_changed`; presence-поток на тике
   (5 с) снимает флаг и СРАЗУ, минуя 60-с цикл и 32-пакетный круг,
   рассылает personal presence со свежим адресом ВСЕМ «своим»
   (`PresenceScope::all_own()` — новый метод). КРИТИЧЕСКОЕ НАХОЖДЕНИЕ:
   ppres-хендлер до `ac242bc` НЕ записывал адрес отправителя из кадра
   (только лог) — теперь записывает в `peer_addrs` + азбуку (самозаявка,
   авторитет как у брокер-presence). Без этой правки срочная рассылка
   не закрепляла бы адрес у получателей. Лог: `STUN: my external
   address changed = …`, `PRESENCE K4: own address changed — immediate
   personal presence sent to X/Y own peer(s)`, на получателе `PRESENCE
   K4: fresh addr from <nodeId> = …`. Проверка: Стас перезагружается в
   мобильной сети → у Ани в logcat за 5-15 с `fresh addr from <Стас>`.
   Дальше: новый coding-session → пуш → PR/CI → релиз по разрешению
   владельца. Для проверки части 1-2: два телефона, один перезапустить
   (или убить/установить заново), через минуту —
   `ADDRESS BOOK: loaded N entries`, `PRESENCE K4: personal presence
   sent to X/Y own peer(s)` с X>0 (было 0 у чистого старта), файл
   `files/apu_peer_addresses.json` растёт и обновляет `seen`; лог
   записи — `ADDRESS BOOK`.
0w. **K4 - ядро: presence «своим», поиск адреса без брокера, свой брокер
   из настроек (K4-1, K4-2 и кодовая часть K4-3 готовы 2026-09-17 на ветке
   `arena/01a0b097-apumir`; РЕЛИЗА НЕТ - ждёт явного разрешения владельца;
   ПРОГОНА CI ПОКА НЕ БЫЛО - локально Rust собрать негде). Что где:**
   - `rust-core/src/network/presence_scope.rs` (новый): список «своих»,
     расписание (`BEACON_INTERVAL_MS` = 10 мин на общий топик,
     `OWN_INTERVAL_MS` = 1 мин лично «своим»), порог `SCALING_MIN_PEERS`
     = 50 узлов, формат личного кадра `ppres|node|имя|адрес|relay|client|
     версия|время` и его разбор. Тесты модуля - в нём же.
   - `rust-core/src/engine/core.rs`: поле `presence_scope`; в цикле брокера
     объявление в общий топик уходит раз в 10 минут (и сразу, если нас в
     общем списке нет), в маленькой сети - как раньше раз в минуту;
     отдельный поток `apu-own-presence` шлёт личный presence «своим»
     по QUIC (`DirectTransport::send_blocking`, рукоятка клонируется -
     замок `self.direct` на время отправки не держится);
     `handle_direct_frame` узнаёт `ppres|…` до K3-кадров и регистрирует
     отправителя в списке узлов (усыновление входящего соединения - по
     возвращённому node_id); «свои» наполняются сами из переписки
     (`send_message`, `send_message_mqtt`, входящее сообщение) и вызовом
     моста.
   - Мост: `lib.udl` -> `set_presence_audience(sequence<string> ids) -> u32`
     (никто из Kotlin пока не вызывает: список наполняется из переписки).
     CI перегенерирует мост при теге; в git-копии `p2p_core.kt` функции
     НЕТ нарочно - иначе локальная сборка владельца получила бы новый мост
     со старым `.so` (контрольные суммы uniffi).
   - Совместимость N↔N-1: личный кадр начинается не с `pk_…`, поэтому
     старые сборки отбрасывают его тем же стражем, что и любой чужой кадр;
     старый отправитель шлёт presence в общий топик, как раньше. Порог
     50 узлов означает, что на телефонах владельца поведение не меняется
     вовсе - K4-1 виден только в большой сети (это и в логах: строка
     `PRESENCE K4:`).
   - Проверка компиляции: `scripts/ci/ci-core-check.yml` - рабочий файл;
     в `.github/workflows/` его ставит владелец скриптом
     `scripts/install-ci-core-check.ps1` (у GitHub-приложения из Arena нет
     права `workflows`, файл не пушится агентом - как когда-то с
     `build-release.yml`). Что делает: `cargo check --release
     --features mqtt-dual-broker` и проверку генерации моста из `lib.udl`
     на pull request (и по кнопке). Релизов не публикует; первый
     компилятор ядра - этот workflow.
   - **K4-2 - поиск адреса по nodeId без брокера (DHT наружу).** Модуль
     `rust-core/src/network/address_lookup.rs` (новый): кадры `dhtq|кто_
     спрашивает|кого_ищем` и `dhtr|кто_ответил|кого_искали|адрес|id@адрес|…`,
     сортировка ближайших по XOR-расстоянию из `dht.rs`, кулдаун 5 минут на
     цель, очередь готовых ответов. В `core.rs`: приёмник кадров узнаёт эти
     кадры до K3-кадров и только кладёт ответ в очередь; отправляет ответы и
     задаёт вопросы поток presence (раз в минуту, до трёх соседей, спрашивает
     только «своих», у кого нет адреса). Известные адреса (presence, mDNS)
     чужим ответом не перетираются. Фолбэк - маяк K4-1 и mDNS.
   - **K4-3 - свой MQTT-брокер из настроек, строгий таймаут, фолбэк.**
     `multi_broker.rs`: `parse_broker_endpoint` (`mqtt://host:1883`,
     `host:1883`, `host`), `probe_broker` со строгим таймаутом
     `BROKER_PROBE_TIMEOUT` = 6 с, порядок перебора «свой первым, публичные
     за ним». `mqtt_transport.rs` выбирает первого ответившего (не больше
     трёх кандидатов), никто не ответил - публичный по умолчанию (дальше
     переподключается rumqttc, как раньше). Настройка: `EngineConfig
     .own_broker`, мост `set_own_broker("host:port")` (до `start()`), пустая
     строка - выключить; на компьютере - переменная `APU_MQTT_BROKER`.
     Осталось серверное: свой MQTT на Cloudflare Worker требует WebSocket и
     Durable Object (`tools/worker/p2p_relay_worker.js` пока только реестр
     узлов `/register`, `/lookup` и хранилище личности).
   - **K5-1 - кастодия у соседей (копия сообщения у двух соседей).** Модуль
     `rust-core/src/network/custody_relay.rs` (новый): кадры прямого канала
     `cust|offer|<конверт>`, `cust|ack|<msg_id>|ok|full|refused`,
     `cust|deliver|<конверт>`, `cust|drop|<msg_id>`; конверт - тот же
     `relay|…` (`network::wire`), формат не менялся (правило N ↔ N-1).
     В `core.rs`: приёмник кадров принимает предложение (копию держим,
     автору уходит `ack`), отдаёт получателю доставленное, убирает копию по
     `drop`; поток presence отправляет `ack`/`deliver`, раз в минуту
     предлагает свои офлайн-сообщения «своим» соседям (двое подтвердили -
     хватит), отдаёт копии тем получателям, кто появился в сети, и сообщает
     автору `drop`. Ограничения: 64 копии всего, 16 на получателя, срок из
     конверта, 512 идентификаторов «уже отдано» (двое хранителей не покажут
     одно сообщение дважды). `set_custody_enabled(boolean)` - согласие
     владельца держать чужое (по умолчанию выключено). Файловая кастодия
     (`file_custody*.rs` с подписанными квитанциями) - следующий подэтап
     K5-2, к движку пока не подключена.
   - **K6 - тесты ядра блокируют проверку и релиз (код готов).** На pull
     request `cargo test --lib` уже идёт в проверке ядра; блокирующим он стал
     файлом-маркером `scripts/ci/tests-blocking` (лежит в репозитории после
     первого зелёного прогона 27b96c6 - убрать файл = снова не блокирует).
     Для релиза в копии `scripts/ci/build-release.yml` есть шаг
     `Core tests (K6)`, он зовёт `scripts/ci/release-tests.sh` до сборки под
     Android: падение тестов = нет APK и нет релиза. Ставится в
     `.github/workflows` один раз скриптом
     `scripts/install-release-workflow.ps1` (владельцем, у приложения Arena
     нет права `workflows`); после этого правила тестов меняются коммитом.
     Проверка самих воркфлоу - `scripts/ci/check-workflow-yaml.py`.
   - **K5-2 - файловая кастодия в движке и мосте (код готов).** Куски файлов
     у соседей: кадры `fcust|offer|ack|deliver|drop` в
     `rust-core/src/network/file_custody_relay.rs`, склад - существующий
     `network::file_custody` (подписанные квитанции, квоты, TTL) в режиме
     `ContactsOnly`, открывается лениво. Роли те же, что у сообщений K5-1:
     автор предлагает «своим», хранитель подтверждает квитанцией и отдаёт
     получателю, когда тот появится, потом говорит автору `drop`. Мост:
     `set_file_custody_enabled(enabled, db_path)`, `file_custody_usage_bytes()`,
     `offer_file_chunk_for_custody(...)` (аргументы как у `send_file_chunk`).
     По умолчанию выключено; приложение пока ходит своим путём (Kotlin
     `FileCustodySender`), чтобы включить новый - надо позвать эти три
     функции. Два разных имени в кадре: сетевое имя автора (для «свой ли» и
     адреса ответа) и ключ кастодии (личность подписи, попадает в квитанцию).
   - Ждут своей очереди: план репликации (`file_custody_replication`) под
     политику «сколько копий держим» и переход приложения на новый путь
     кастодии. План - `CORE_ROADMAP.md`, этап K5.

0t. **v11.70.24 - ядро, этап K1: один QUIC-endpoint на движок, пул
   соединений, keep-alive, STUN с порта 7777 (выпущен 2026-09-16, Latest;
   тег/прогон/sha256 - `START_HERE.md` §3). Первая компиляция кода K1
   была в CI: один прогон упал (E0308 - `Arc::clone(&shared)` не делает
   `Arc<SharedUdpSocket>` → `Arc<dyn AsyncUdpSocket>`, нужна отдельная
   переменная с аннотацией типа), правка `02cfe3f`, второй прогон
   прошёл.** Только Rust, мост (`lib.udl`) не
   менялся - контракт 26 тот же, Kotlin не трогали. Что было: `send_via_quic`
   на КАЖДОЕ сообщение создавал `QuicClient::new(0.0.0.0:0)` (сокет +
   самоподписанный сертификат + рукопожатие TLS) под `rt.block_on` в потоке
   вызывающего до 10 с; слушатель 7777 был отдельным endpoint'ом; STUN
   спрашивался с третьего, временного сокета (адрес в presence - порт, за
   которым никто не слушал); `generate_invite` подставлял TCP 7778;
   `ConnectionPool` создавался и не использовался. Что стало:
   - `network/direct_transport.rs` (новый): `DirectTransport::start(bind,
     on_frame)` внутри runtime → `QuicClient::new_with_side_channel` (один
     endpoint для входящих и исходящих), `run_accept_loop` (рукопожатие
     каждого входящего в своей задаче с таймаутом 10 с - раньше `break` на
     первой ошибке глушил приём), `read_loop` на КАЖДОМ соединении
     (входящем и исходящем; ошибка одного стрима не рвёт соединение),
     цикл команд → «полоса» (mpsc 64) на узел: кадры одному узлу по порядку,
     разные узлы параллельно; `send_one`: соединение из пула → `send_message`
     с `STREAM_TIMEOUT` 5 с; при ошибке на соединении из пула - закрыть,
     `remove_if_same(stable_id)` и одна повторная попытка по новому
     (`CONNECT_TIMEOUT` 4 с); `FAIL_FAST_WINDOW` 15 с - повторные кадры на
     только что недоступный адрес получают `false` сразу; кадр, простоявший
     в полосе дольше бюджета, не отправляется (вызывающий уже ушёл на
     брокер). **Усыновление входящих:** первый разобранный кадр от `pk_X`
     кладёт входящее соединение в пул под ключом `X` - ответ `X` идёт по
     нему, адрес не нужен (`send(..., addr: None, ...)`); для узла за
     симметричным NAT это единственный прямой путь. `send_blocking` (поток
     Kotlin): `try_send` команды + `std::sync::mpsc::recv_timeout`
     (`DIRECT_SEND_BUDGET` 10 с), никакого `block_on`. Семантика `bool` =
     получатель подтвердил приём стрима (`stopped()`), как раньше.
   - `quic_client.rs`: `SharedUdpSocket` (`AsyncUdpSocket` поверх сокета
     quinn: датаграммы с сигнатурой STUN - первые два бита 0 и magic cookie
     `21 12 A4 42` - уходят в боковой канал, quinn видит `len = 0`),
     `UdpSideChannel::{send_to (ждёт writable через UdpPoller), recv, drain}`,
     `QuicClient::new_with_side_channel` (`Endpoint::new_with_abstract_socket`
     + `quinn::default_runtime()` - только внутри tokio-контекста),
     `accept_pending()` (→ `Connecting` без ожидания рукопожатия),
     `close_now()`, `QuicConnection::stable_id()`. Транспорт:
     `MAX_IDLE_TIMEOUT_SECS` 60 (было 300), `keep_alive_interval` 20 с у
     клиента (инициатор шлёт PING, сервер нет - один PING на пару).
   - `ice.rs`: `encode_binding_request()`, `decode_binding_response()`,
     `looks_like_stun()`, `STUN_MAGIC_COOKIE`; `StunClient::get_external_address`
     переписан через них (поведение прежнее).
   - `connection_pool.rs`: `remove_if_same(key, stable_id)`.
   - `engine/core.rs`: поле `direct: Arc<Mutex<Option<DirectTransport>>>`
     вместо `connection_pool`; в `start_async_runtime` endpoint поднимается
     синхронно (`std::thread::scope` + `handle.block_on(open_direct_transport)`
     - в отдельном потоке, потому что `block_on` изнутри чужого runtime
     паникует в тестах): 5 попыток порта 7777 с паузой 200 мс, затем любой
     порт (`advertised_port` уходит в mDNS); `run_quic_listener` заменён на
     `handle_direct_frame(events, network, payload) -> Option<sender>` (тот
     же разбор `sender|msgId|chatId|text` со стражем `pk_`);
     `run_stun_discovery(public_addr, Option<UdpSideChannel>)` - с боковым
     каналом через `stun_via_side_channel` (сервер за сервером,
     `STUN_TIMEOUT` 5 с каждый, `drain` перед запросом), период 55 с при
     успехе (заодно греет NAT); без канала - старый путь; mDNS больше не
     делает свой STUN, берёт `public_addr` (ждёт до 6 с) и перечитывает при
     каждой ре-публикации; повтор из `MessageQueue` при появлении mDNS-соседа
     - через `DirectTransport::send`; `send_message`/`send_direct_payload`
     вызывают `send_via_quic(peer_id, Option<addr>, payload)` даже без адреса
     (пул); `stop()` закрывает endpoint до `shutdown_background`;
     `generate_invite` отдаёт STUN-адрес как есть (порт QUIC), без 7778.
   - Тесты (запускаются только на ПК с cargo / в CI после K6):
     `direct_transport::tests` (пул переиспользуется; ответ по усыновлённому
     входящему без адреса; без адреса и соединения - `false` сразу;
     недоступный узел - `false` в бюджет; блокирующая отправка с чужого
     потока; быстрый повторный отказ; `forget`), `quic_client::tests::
     test_shared_socket_routes_stun_aside_and_quic_through`, `ice::tests`
     (сигнатура STUN, мусор). Компилятора в песочнице нет - первый
     компилятор = CI тега; ошибки Rust смотреть по шагу «Build native core».
   - Совместимость: кадры и ALPN `p2p-msg-v1` те же; старые телефоны видят
     обычного QUIC-клиента, который не закрывает соединение; их 300-секундный
     idle против наших 60 с - соединение закроет наша сторона, старая молча
     переоткроет при следующем сообщении. Keep-alive от старых телефонов нет,
     поэтому усыновлённое соединение от старой версии живёт до 60 с тишины.
   - Что проверить на телефонах: logcat `DIRECT: shared QUIC endpoint on
     0.0.0.0:7777`, `STUN(7777): … sees us as <ip:7777-ish>`, `DIRECT: sent …
     (pooled)` на втором сообщении, `DIRECT: adopted inbound connection from
     pk_…`; сообщения через интернет между двумя мобильными сетями (раньше
     почти всегда шли через брокер); файл в группе - скорость и отсутствие
     «Приложение не отвечает».

0s. **v11.70.23 - первая функция ядра через перегенерированный мост
   (выпущен 2026-09-15: тег на `ca29530`, прогон 35005340748 с первого
   раза; шаг генерации мостa прошёл, CI закоммитил мост и ядро в main -
   `551f0d4`; sha256 и размер - `START_HERE.md` §3).** Владелец:
   «давай сделаем чтобы у нас было ядро самое лучшее… если что-то нужно
   установить на ПК - давай ставить». Ответ: на ПК ставить ничего не надо -
   Rust собирает CI; нужен только новый workflow. Правки: `rust-core/src/
   lib.udl` - `string core_build_info();` в namespace; `rust-core/src/lib.rs`
   - `pub fn core_build_info()` (`APP_VERSION`, `option_env!("GITHUB_REF_NAME")`,
   `cfg!(feature = "mqtt-dual-broker")`); `RustBridge.coreBuildInfo()`
   (вызов `uniffi.p2p_core.coreBuildInfo()` полным именем, `Throwable` →
   «недоступно»); `SettingsViewModel.rustCoreVersion` = эта строка;
   `SettingsScreen` - строка «Ядро» (`Icons.Default.Memory`, extended icons
   есть) под «Версия». Если на телефоне в «О приложении» видно
   «p2p_core 0.1.0 · сборка v11.70.23 · 2 брокера» - мост перегенерирован
   и ядро больше не заморожено; правило «uniffi заморожен» ниже тогда
   снимается. **Порядок выпуска:** (1) владелец `git pull` + `Copy-Item`
   workflow + commit + push в main; (2) бот `git fetch origin main && git
   merge --ff-only FETCH_HEAD`; (3) тег на этом коммите. Тег на коммите со
   старым workflow упадёт на Kotlin («Unresolved reference: coreBuildInfo»)
   - это ожидаемо и безопасно (релиза не будет), но бесполезно. Дальнейший
   план ядра - `docs/CORE_ROADMAP.md`.

0r. **v11.70.22 - системный жест «Назад» внутри темы (выпущен 2026-09-15:
   тег на `8c2dbc5`, прогон 34993655917 с первого раза; sha256 и размер -
   `START_HERE.md` §3).** Владелец после v11.70.21 (заголовок
   темы и закреп файла подтверждены): «в теме смахиваешь справа налево -
   переходит сразу в список групп, минуя список тем». Причина: кнопка
   «Назад» наверху и `Modifier.swipeBack` (только слева направо) уже
   возвращали к списку тем, а системный жест Android (от края, в т.ч. справа
   налево, и аппаратная кнопка) никем не перехватывался и делал
   `navController.popBackStack()` - закрывал весь `GroupChatScreen`. Правка:
   `BackHandler(enabled = hasTopics && showFeed) { showFeed = false }` в
   `GroupChatScreen` (`androidx.activity.compose.BackHandler`, зависимость
   `activity-compose` уже была). В списке тем и в группе без тем перехватчик
   выключен - жест закрывает экран, как прежде. Других экранов с
   «внутренним» уровнем без своего маршрута нет (комментарии канала и
   управление - отдельные маршруты навигации). Не проверено на телефоне.
   **Отдельно, не в APK:** в `scripts/ci/build-release.yml` добавлены шаги
   «Regenerate uniffi Kotlin bindings from lib.udl» (собирает
   `tools/uniffi-bindgen`, uniffi =0.28.3, и переписывает
   `uniffi/p2p_core/p2p_core.kt` из `rust-core/src/lib.udl` перед сборкой
   APK; при неудаче - `git checkout` файла и прежняя сборка), «Upload core
   bindings» (артефакт `core-bindings`, 30 дней) и «Push regenerated
   bindings and core back to main» (после публикации релиза, только если
   тег = вершина main; `continue-on-error`). Это снимает заморозку ядра:
   новая функция в `lib.udl` + Rust станет видна Kotlin в том же прогоне.
   **Вступит в силу только после того, как владелец скопирует файл в
   `.github/workflows/` (бот туда писать не может), и первый прогон с
   изменённым `lib.udl` докажет, что генератор работает** - до этого
   правило «uniffi заморожен» ниже остаётся в силе. Следствие для порядка
   релиза: если прогон запушил в main коммит «ci: uniffi bindings + core
   rebuilt for vX», то перед следующим `PATCH git/refs/heads/main` ветку
   бота надо дотянуть: `git fetch origin main && git merge --ff-only
   FETCH_HEAD` (иначе PATCH с `force=false` откажет как non-fast-forward).
   Порядок работы с ядром после этого: правка `rust-core/src/lib.udl` +
   Rust → релиз-тег → CI собирает ядро, переписывает мост, собирает APK;
   ошибки компиляции Rust видны только по упавшему прогону (как и Kotlin).

0q. **v11.70.21 - замечания с телефона + рой, этап 11 (выпущен 2026-09-15:
   тег на `843aabe`, прогон 34970665553 с первого раза; sha256 и размер -
   `START_HERE.md` §3).** Владелец после
   v11.70.20 («пока всё работает», скриншот группы «тест общение» с
   принятым файлом APU v11.70.19 35.8 МБ): (а) внутри темы наверху не видно,
   в какую тему зашёл → `GroupChatScreen`: `topicHeader` - значок и имя темы
   крупно, подзаголовок «Тема · группа», аватар группы в теме не рисуется;
   (б) файл нельзя закрепить → пузырь `MessageBubble` в `Box(weight(1f,
   fill = false))`, чтобы кнопка «Закрепить» справа не выдавливалась
   карточкой файла на 300 dp, + пункт «Закрепить/Открепить» в меню долгого
   нажатия, в списке закреплённых - значок по типу файла и подпись
   «📎 имя (размер)» (`fileIconFor`, `GroupFileMarker.caption`); (в) резервная
   копия обновляется автоматически - подтверждено владельцем. Рой, этап 11:
   `GroupWire` вид `fnone|groupId|sha256` (`Packet.FileNone`,
   `buildFileNone`), `GroupRepository` параметры `onFileNone`/`onGroupGone`
   (+ ветка `when` в `handleIncoming`, вызовы из `leaveGroup` и
   `deleteGroupLocally`), `GroupsModule` подключает к `GroupFileSwarm.
   onFileNone/onGroupGone`, `GroupFileStore.deleteGroup`,
   `StorageSettings.Usage.groupFileBytes` + строка в настройках. Карта -
   `CHANNEL_SWARM_DESIGN.md` §9.4 «Этап 11». На телефонах НЕ проверено.

0p. **v11.70.20 - рой, этап 10: остатки без ядра (выпущен 2026-09-15: тег на
   `d3f61bc`, прогон 34958884011 с первого раза; sha256 и размер -
   `START_HERE.md` §3).** Просьба владельца
   «доделывай все что можно и выпускай релиз. потом все будем проверять на
   телефонах» → закрыты границы этапа 9 (см. 0o): (1) просьбы `fwant` на
   диске - `data/group/GroupFileRequestStore.kt` (`requests.v1`, JVM-тест
   `GroupFileRequestStoreTest`), `GroupFileSwarm.restoreIfNeeded/warmUp/
   persistPending`; (2) запасной сид через 30 с (`REASK_FAST_MS`,
   `FAST_ATTEMPTS = 3`, `PREP_BYTES_PER_MS`) при ≥ 2 известных сидах;
   (3) отказ от лишнего предложения: `OfferRouting.Duplicate(chatId)` →
   `CANCEL` с меткой `CANCEL_DECLINED = [2]` (`FileTransferWire.
   cancelMessageId` = `f<id>x`), приёмник помнит отказанные (`declined`,
   куски не буферизуются), `handleCancel` у сида - только от адресата
   исходящей строки → `CANCELLED`/`DECLINED` + куски удалены;
   `GroupFileSwarm.cancelExtraOffers` после COMPLETE,
   `FileTransferRouter.declineIncoming`; (4) открытые сообщества:
   незнакомый отправитель `fwant`/`fhave`/предложения допускается при
   `group.isPublic` (как `preq`), `sharesGroupWith` считает соседом любой
   известный сид; (5) карточка файла вынесена в
   `ui/components/GroupFileCard.kt` (`FileCardState.of`), лента канала
   рисует её под постом (`ChannelPost.file`, `PostCard(fileCard)`), «Новый
   пост» - «Прикрепить файл» (`ChannelViewModel.onFileSelected/
   clearStagedFile`, `createPost` → `GroupFileMarker.compose`); (6) `peers`
   при N > 100 уже был - записано в `CHANNEL_SWARM_DESIGN.md` §9.3.
   **Не сделано и без ядра не делается**: полосы одного файла от нескольких
   сидов (конверт ключа привязан к получателю -
   `rust-core/src/crypto/file_key_envelope.rs`), подписанные квитанции
   хранения, FFI подписи/QUIC-куски (uniffi заморожен). Схема БД прежняя
   (20), `FakeFileTransferDao` не менялся. На телефонах НЕ проверено -
   владелец проверяет всё скопом после этого релиза; сценарии -
   `CHANNEL_SWARM_DESIGN.md` §9.4 «Этап 10» (и «Этап 9»), звонки -
   `CALLS_BOOTSTRAP.md`, резервная копия - `RELEASE_NOTES_v11.70.17.md`.

0o. **v11.70.19 - рой, этап 9: файлы в группах (выпущен 2026-09-15: тег на
   `abbb5c4`, прогон 34921724763, sha256 и размер - `START_HERE.md` §3;
   первая компиляция этапа прошла с первого раза).** Просьба владельца «доделывай систему
   роя» → `CHANNEL_SWARM_DESIGN.md` §9.3 п.6 «раздача файла сидами группы».
   Что сделано: (1) `util/GroupFileMarker.kt` - визитка файла последней
   строкой текста сообщения `APUFILE1:<sha256>:<байт>:<b64url(тип)>:
   <b64url(имя)>` + подпись «📎 имя (размер)» словами для старых версий
   (`compose/parse/stripCaption/key`); `InlineImage.isServiceLine` знает
   визитку (не слова; при правке сохраняется). (2) `GroupWire`: виды
   `fwant` (`|groupId|sha256|b64(messageId)|b64url(binding)`) и `fhave`
   (`|groupId|sha256|b64(messageId)`), `Packet.FileWant/FileHave`,
   `isSha256`. (3) `data/group/GroupFileSwarm.kt` (@Singleton) - три роли:
   автор (`stage` - копия в `noBackupFilesDir/group_files/v1`,
   `GroupFileStore`), проситель (`onCardSeen` → `shouldAutoFetch` /
   `request`, повторы и смена сида в `pump`, `onPeerOnline`), сид
   (`onFileWant` → проверка членства, закрепление ключа просителя из пакета,
   очередь до 3 параллельных → `OutgoingFilePreparationService.prepareFromFile`
   лично просителю; `releaseServed` удаляет куски после COMPLETE;
   `onFileReceived` → `fhave` шести соседям). Подключение: `GroupRepository`
   параметры `onFileWant/onFileHave/onFileCard` (`GroupsModule` через
   `Provider<GroupFileSwarm>` - кольцо зависимостей), `sendMessage` держит
   визитку в конце `body`, `handleIncoming` зовёт `onFileCard` на чужое
   сообщение с визиткой; `CoreServerService` зовёт `groupFiles.pump()` в
   файловом насосе (20 с) и `onPeerOnline` на пульсе. (4) Приём:
   `FileTransferReceiver.routeOffer` (`OfferRouting.Chat/Duplicate/Unknown`) -
   чат для предложения по хэшу файла; `FileTransferRouter.routeIncoming`
   пропускает `direct`-кадр без чата, если отправитель - сосед по группе
   (`GroupDao.countSharedGroups`); `FileChatNotifier` для чата-группы не
   пишет строку в личный чат; `FileCustodySender.custodyAllowed` - файлы
   группы на хранение не идут; `FileTransferRouter.releaseOutgoingChunks/
   dropTransfer`; `FileTransferDao.getForFile(chatId, sha256)` (без миграции).
   (5) UI: `GroupChatScreen` - скрепка, карточка над полем ввода, карточка
   файла в пузыре (`FileCardState`/`GroupFileCard`: «Скачать», ход приёма,
   «Спросить у другого», «Сохранить в папку», «Поделиться», у автора
   «Получили: N»); `GroupChatViewModel` - `onFileSelected/clearStagedFile/
   requestFile/...`, права `canAttach` (ранг + SEND_MEDIA). Тесты:
   `GroupFileMarkerTest`, `GroupFileStoreTest`, `GroupWireTest` (fwant/fhave),
   `FileTransferReceiverTest` (4 теста маршрута). На телефонах НЕ проверено;
   что смотреть - `CHANNEL_SWARM_DESIGN.md` §9.4 «Этап 9». Известные
   границы этапа 9 (просьбы только в памяти; один сид на просьбу без
   быстрого запасного; лента канала без карточки файла) закрыты в
   v11.70.20 - см. 0p; полос между сидами по-прежнему нет (ядро).

0n. **v11.70.18 выпущен 2026-09-13 (Latest, тег `e7aec2a`, прогон
   34766279060 с первого раза): контакт-призрак «Contact APUCALL1».**
   Скриншот владельца: в списке чатов личный чат «Contact APUCALL1» с
   подписью `8|715|14300|MqzW…`, появился «когда делали звонки». Причина
   (по коду, не по памяти): голосовой текстовый фолбэк звонка
   (`CallManager.startFramesPump`, последняя ступень) слал строку
   `APUCALL1|ab|<callId>|<n>|<seq>|<ts>|<b64>…` через
   `RustBridge.sendMessageMqtt` КАК ЕСТЬ, а приёмник ядра (`core.rs`,
   MQTT-путь `p2pm2/msg/<я>`, и такие же TCP/QUIC-приёмники) режет строку
   `splitn(4,'|')` в `sender|messageId|chatId|text` без проверки первого
   поля → `sender_id = "APUCALL1"`, `text = "<n>|<seq>|<ts>|<b64>…"`;
   `CoreServerService` не узнал пакет звонка (текст уже без префикса),
   завёл контакт `"Contact " + senderId.takeLast(8)` = «Contact APUCALL1»
   и сохранил обрывок как сообщение. Голос по этому пути, соответственно,
   тоже не доходил никогда. Что сделано: (1) `util/NodeIds.kt` -
   `isNodeId` (`pk_` + 7..128 букв/цифр), `autoName`, `isStrayAutoContact`
   (id не узел + имя = заглушка от этого id); (2) `CoreServerService`
   отбрасывает `message_received` с отправителем не-узлом ДО всех
   разборщиков (и то же на CF-relay пути); (3) `ContactRepository.
   reconcileChats` → `removeStrayAutoContacts()` разово при старте удаляет
   призраков с чатами, `ChatRepository.deleteStrayChats()` - осиротевшие
   чаты; (4) фолбэк в `CallManager` теперь заворачивает пакет в конверт
   `<мой pk_>|<audioBatchMessageId>|direct|<APUCALL1…>` - как
   `send_direct_payload`; (5) в `core.rs` на трёх приёмниках добавлен
   `parts[0].starts_with("pk_")` (попадёт на телефон только со сборкой CI -
   ядро собирается из исходников на релизе). Тест `NodeIdsTest`. Правило на
   будущее: всё, что уходит через `sendMessageMqtt`, кроме `ack|…`, обязано
   быть конвертом `sender|msgId|chatId|text`.

0m. **v11.70.17 выпущен 2026-09-13 (Latest, тег `b7490ab`, прогон
   34754528870 с первого раза; рой, этап 8, в одном выпуске с 0l): два
   хранителя на файл, инвентарь недостающего, отпускание копий.** Просьба владельца 2026-09-13
   «Систему рой доделывай и выпускай релиз для всех». Что сделано (Android,
   без FFI, схема БД 20 не менялась): отправитель раздаёт копии до
   `FileCustodySender.TARGET_HOLDERS` = 2 хранителям по одному за раз
   (`pumpOrigin` обходит и `CUSTODIED` строки - `FileTransferDao.getCustodied`;
   список в `custodianNodeId` через запятую, `FileCustodyPdu.holders`);
   получатель принимает предложения от каждого (`handleForwardedOffer`, до
   `MAX_HOLDERS` = 4), куски от любого из списка (`handleCustodyChunk`),
   подтверждает тому, от кого кусок (`sendCustodyAck(..., holderTag)`, id
   `f<id>k<я>h<он>a<n>`), при ≥ 2 хранителях шлёт инвентарь
   `CUSTODY_WANT` (тип 8; `FileCustodyPdu.encodeWant/decodeWant`, делёж
   `assign` полосами по 8 по номеру куска, живость по `holderSeenAt` /
   `holderAskedAt`, не чаще `INVENTORY_INTERVAL_MS` = 10 с); хранитель
   по инвентарю шлёт только своё (`Leg.wanted`, `onRecipientWant`,
   `sendWindow(wanted=)`), без инвентаря - от префикса, как раньше.
   Отпускание: итоговое подтверждение получателя уходит всем хранителям;
   отправитель на итоговый `ACK` шлёт хранителям `CUSTODY_ACK` со статусом
   `ACK_RELEASE` = 4 (`handleAck`), хранитель принимает его только от
   `originNodeId` (`onOriginRelease`). Пузырь: «На хранении у N
   телефонов…». Карта и как проверить на четырёх телефонах -
   `CHANNEL_SWARM_DESIGN.md` §9.4 «Этап 8»; тесты `FileCustodyPduTest`,
   `FileCustodyFlowTest` (в CI не гоняются - `gradlew :app:testDebugUnitTest
   --tests '*Custody*'`). Совместимость: телефон < v11.70.17 на месте
   получателя инвентарь отбрасывает - оба хранителя шлют от префикса
   (дубли, но файл собирается); на месте хранителя `ACK_RELEASE`
   отбрасывает как мусор - копия живёт до срока, как раньше. На телефонах
   НЕ проверено. Дальше по рою - раздача сидами по `transferId`.

0l. **v11.70.17 выпущен 2026-09-13 (тот же релиз, что 0m): автообновление
   файла резервной копии по расписанию.**
   Просьба владельца 2026-09-13: «если сделал одну резервную копию, то
   можно было бы выбрать автоматическое обновление этого файла раз в
   неделю». Сделано: после сохранения копии на экране «Резервная копия»
   появляется карточка «Обновлять копию автоматически» (день / неделя /
   месяц, по умолчанию неделя). `data/backup/BackupSchedule.kt` -
   prefs `apu_backup_schedule` (в копию не входят): `enable` берёт
   постоянный SAF-доступ на чтение/запись к только что записанному файлу
   (`takePersistableUriPermission`; если провайдер не даёт - честный отказ
   `NoPersistentAccess`), заворачивает пароль ключом Android Keystore
   (`apu_backup_password_wrap_v1`, AES/GCM с AAD) и привязывает к
   текущему `node_id`; периодическая задача WorkManager
   `profile_backup_auto` (`PeriodicWorkRequest` с окном гибкости, батарея
   и хранилище не на нуле, откат 30 мин); `runNow` - одноразовая
   `profile_backup_now`; `disable` отпускает разрешение и стирает prefs.
   `worker/AutoBackupWorker.kt` (обычный `CoroutineWorker`, зависимости
   через `@EntryPoint AutoBackupEntryPoint`): проверяет включено / файл /
   профиль тот же / доступ / пароль, иначе `disableWithError` +
   уведомление (id 4101) с причиной; пишет копию в две фазы -
   `noBackupFilesDir/backup_auto/pending.apubak` (`ProfileBackup.createFile`,
   готовый снимок переиспользуется 2 ч), затем `copyFileTo` в SAF-файл;
   при нехватке места (`estimateBytes` + 64 МиБ > свободного) - retry;
   успех/ошибка - `recordSuccess/recordFailure`, до 3 попыток. `ProfileBackup`:
   `create` идёт под `createLock`; `applyStagedIfAny` после восстановления
   зовёт `BackupSchedule.resetAfterRestore` (расписание смотрело в файл
   прежнего профиля). UI - `ProfileBackupScreen.kt` (карточка,
   `PeriodChooser`, `LifecycleEventEffect(ON_RESUME)` обновляет состояние),
   `ProfileBackupViewModel.kt` (`enableAutoUpdate/setAutoPeriod/
   disableAutoUpdate/runAutoNow`). В JVM не тестируется (Android-классы).
   **Что проверить на телефонах:** (1) сделать копию → включить «неделя» →
   «Обновить сейчас» → файл в «Файлах» обновился (дата, размер), карточка
   показывает «Последнее обновление»; (2) удалить файл → «Обновить сейчас»
   → уведомление «доступ к файлу копии потерян», карточка с причиной;
   (3) Google Drive / Яндекс.Диск как место - даёт ли провайдер постоянный
   доступ (иначе честный отказ при включении); (4) оставить на сутки с
   периодом «день» - обновилось ли само (WorkManager не обещает точного
   часа).

0k. **v11.70.16 выпущен 2026-09-13 (тег `3871c33`; владелец подтвердил:
   «Резервная копия работает хорошо»): резервная копия профиля целиком и
   восстановление из файла.** Просьба владельца
   2026-09-12: после удаления и установки заново вход по логину/паролю
   возвращал только личность (`IdentityBackup.restore` - `node_id`,
   `existing_private_key`, `display_name`, `my_username`), а чаты,
   контакты, сообщества, привязки шифрования, база, файлы и настройки
   терялись. Теперь: Настройки → Безопасность → «Резервная копия»
   (`Screen.ProfileBackup`, `ui/screens/settings/ProfileBackupScreen.kt` +
   `ProfileBackupViewModel.kt`), тот же экран открывается с первого экрана
   в режиме «Войти» кнопкой «Восстановить из файла резервной копии»
   (`OnboardingScreen.onRestoreFromFile`). Код - `data/backup/`:
   `BackupCipher` (файл `APUBAK` v1: PBKDF2-SHA256 210k итераций, домен
   `apu-profile-backup-v1`, AES-GCM порциями по 1 МиБ, заголовок и флаг
   «последняя» в AAD, счётчик в nonce - подмена/выкидывание/обрезка порций
   ловятся), `BackupLayout` (ZIP внутри шифра: `manifest.txt` первым,
   `prefs/<name>.txt` для 9 файлов prefs минус Keystore-обёрнутые ключи,
   `secrets/identity_signing_seed` и `secrets/file_exchange_x25519` в
   открытом виде ТОЛЬКО внутри шифра, `db/messenger_database` (+`-wal` на
   Android 8–9), `avatar/`, `preview/`, по желанию `received/<id>/<имя>`),
   `BackupManifest` (format=1, `db_version` = `APP_DATABASE_VERSION` из
   `AppDatabase.kt`, сейчас 20), `PrefsCodec`, `ProfileBackup`
   (`create` - снимок базы `VACUUM INTO` на Android 10+, иначе checkpoint +
   копия под транзакцией; `stage` - расшифровать и разложить в
   `noBackupFilesDir/restore_pending`, отказ при чужом файле / неверном
   пароле / более новом формате или схеме / обрыве; `confirmStaged` пишет
   маркер `.ready`; `applyStagedIfAny` зовётся из `MessengerApplication.onCreate`
   ДО `DeviceIdentityMarker.discardIfRestored`: подменяет базу, prefs
   (`commit`), чистит `apu_relay_at_rest` и `apu_relay.sqlite*`, заворачивает
   секреты ключом Keystore этого телефона через новые
   `FileExchangeKeyStore.importSecret` / `IdentitySigningKeyStore.importSeed`,
   помечает незавершённые `file_transfers` как `FAILED/RESTORED_ELSEWHERE`,
   ставит `DeviceIdentityMarker.create`). Подтверждение восстановления
   закрывает приложение (`finishAndRemoveTask` + `killProcess`), человек
   открывает его сам - Android 12+ не даёт приложению перезапустить себя
   из фона. JVM-тесты `app/src/test/.../data/backup/*Test.kt` (шифр, кодек,
   раскладка, манифест) - в CI не гоняются, прогнать локально
   `gradlew :app:testDebugUnitTest --tests '*backup*'`.
   **Что проверить на телефонах:** (1) сделать копию с файлами и без,
   размер и время; (2) удалить приложение, поставить, «Войти» →
   «Восстановить из файла…» → пароль → файл → карточка «Копия готова» →
   «Восстановить и закрыть» → открыть снова: чаты, контакты, сообщества,
   аватар, ранг на месте, собеседник пишет без «новый ключ»; (3) на втором
   телефоне восстановиться из той же копии - старый телефон после этого
   с тем же адресом (два устройства с одним ключом - ожидаемо конфликт
   присутствия, честно не решено); (4) неверный пароль, чужой файл,
   обрезанный файл - понятные тексты, ничего не сломано. Честно не
   восстанавливаются: исходящие передачи в пути (`file_transfers/v1`,
   пофайловые ключи в Keystore), чужие файлы на хранении, очередь ядра
   `apu_relay.sqlite`.

0i. **v11.70.13 выпущен 2026-09-12 (Latest, тег `cb9b54d`): рой, этап 7 -
   хранение файла у третьего телефона.** Получатель не в сети
   дольше 2 мин → отправитель отдаёт файл лучшему контакту в сети
   (`CUSTODY_OFFER` → `CUSTODY_ACK` → `CUSTODY_CHUNK` по прямому каналу),
   хранитель держит его в своём «Месте под пересылку» и отдаёт получателю
   при появлении; отправитель может выйти из сети. Карта кода и как
   проверить на трёх телефонах - `docs/CHANNEL_SWARM_DESIGN.md` §9.2
   «Хранение у третьего телефона» и §9.4 «Этап 7»; что сделано/не сделано
   из §D - `docs/SECURE_FILE_TRANSFER.md` после §D. Схема Room 19 → 20
   (`file_transfers` + `originNodeId`, `custodianNodeId`, миграция
   `MIGRATION_19_20`). Честно: один хранитель на файл; квитанций хранения
   и инвентаря нескольких держателей нет; хранитель проверяет только
   геометрию кусков (Merkle нет); на телефонах НЕ проверено, JVM-тесты
   `FileCustodyFlowTest`/`FileCustodyPduTest` в CI не гоняются (workflow
   без unit-тестов) - при первом же удобном случае прогнать локально
   `gradlew :app:testDebugUnitTest --tests '*Custody*'`.

0h. **v11.70.12 выпущен 2026-09-11 (Latest, тег `bb5fdb1`): рой, этап 6 - место под пересылку.**
   Требование владельца: ползунок «сколько телефон может хранить для
   пересылки», 100 МБ…100 ГБ, больше места - выше рейтинг сервера. Карта
   кода - `docs/CHANNEL_SWARM_DESIGN.md` §9.2 «Место под пересылку» и §9.4
   «Этап 6» (там же - как проверить). Что проверить на телефонах:
   (1) «Настройки → Раздача» - ползунок, строка «Занято сейчас…» и
   «Свободно на телефоне»; (2) сдвинуть ползунок - у контакта в логе
   `capabilities from=… offered=…`, в его «Узлах сети» у вас «Место под
   пересылку: N ГБ (+x.x)»; (3) поставить 100 МБ и принять файл крупнее -
   пузырь «Нет места под пересылку», лог `rejected by store … не хватает
   места`; вернуть ползунок - приём продолжается сам (≤ 20 с). Честно:
   квота пока ограничивает только куски файлов (свои передачи); очередь
   сообщений ядра ограничена числом, не байтами (FFI не менялся); чужие
   файлы на хранении - этап 7 (0i).

0g. **v11.70.11 выпущен 2026-09-11 (Latest, тег `cd0fbc3`): рой, этап 5 -
   сообщения больших групп (> 30 участников) через манифест.** Карта кода - `docs/CHANNEL_SWARM_DESIGN.md` §9.4
   «Этап 5». Что проверить на телефонах: (1) группа > 30 участников,
   сообщение с фото - у автора `group message … manifest=true`, при
   умеющих рой > 20 ещё и `swarmRest=N`; у получателя вне первой волны
   `manifest accepted … from=<не автор>`, `pwant sent … post=…`, фото
   собралось; (2) закрыть приложение, пропустить несколько сообщений,
   открыть тему - `group messages requested … peers=K`, у соседа `group
   messages served … messages=N`, у себя досланные сообщения с временем
   отправки, а не приёма; (3) группа ≤ 30 - `manifest=false`, всё как
   раньше; каналы не изменились. Известно: прошлые версии в большой группе
   получают сообщения целиком от автора (как старые телефоны), но сами
   пропущенное не добирают и `mwant`/`mreq` не отвечают.

0f. **v11.70.10 выпущен 2026-09-11 (Latest, тег `f3d730d`): рой, этап 4 -
   комментарии больших каналов через сборщиков.** Карта
   кода - `docs/CHANNEL_SWARM_DESIGN.md` §9.4 «Этап 4». Что проверить на
   телефонах: (1) канал > 20 подписчиков, комментарий с обычного телефона -
   у автора в логе `group message … viaHubs=true`, у владельца
   `comment relayed … to=K` (если кто-то держит ветку открытой); (2)
   читатель открывает комментарии - у него `comments served`-ответ в виде
   строк, в логе владельца `comments served … sent=N of=M`, у читателя
   `comment ids applied … missing=…`; последние 20 комментариев на месте,
   кнопка «Показать ещё N» тянет более ранние; (3) число комментариев под
   постом в ленте канала совпадает с владельцем; (4) на канале ≤ 20 и в
   группах комментарии как раньше (`viaHubs=false`). Известно: телефоны
   прошлых версий на большом канале видят ветку неполной (они не понимают
   `creq`/`cids`/`cinf` и не верят комментариям от администратора).

0e. **v11.70.9 выпущен 2026-09-10 (Latest, тег `274b0b0`): короткие ссылки
   (просьба владельца: «чтобы в ссылках не было видно информацию… сделай
   короткие ссылки»).** Код в `data/link/*`, карта - `START_HERE.md`
   «Ссылка пересылки поста». Worker опубликован владельцем в тот же вечер
   (проверен живьём). Что проверить на телефонах:
   (1) репост поста → в Telegram приходит `https://…/s/<10 знаков>` без
   `slug=`/`pk_`; тап по ней на телефоне с APU открывает «Открыть пост?» и
   доводит до поста; на телефоне без APU - страница «Запись в APU» с
   кнопкой установки; (2) «Поделиться» каналом/группой из «Сообществ» и
   приглашение из админки - тоже короткие, вход/подписка работают; (3)
   «Пригласить друга» из контактов → короткая ссылка, у получателя
   открывается «Добавить контакт?»; (4) вставка короткой ссылки в «Войти по
   ссылке» и сканирование её как QR; (5) старая длинная ссылка `/i?slug=…`
   по-прежнему открывается. В логе: `LinkShortener: short link service
   unavailable` = worker не опубликован или нет сети. Домен `apumir.app`
   по-прежнему не куплен - ссылка показывает хост `…1985vzhem.workers.dev`.

0d. **v11.70.8 выпущен 2026-09-10 (Latest, тег `88766d4`): рой, этап 3
   (Kotlin-часть: длинный текст кусками, счётчики через владельца при
   > 20 подписчиков, эстафета умеющим рой) + запас под «+» в
   избранном/чатах/прокси.** Карта кода - `docs/CHANNEL_SWARM_DESIGN.md`
   §9.4 «Этап 3». Что проверить на телефонах: (1) пост на 5 000+ знаков
   доходит целиком через мобильный интернет (не по Wi-Fi) - у автора в логе
   `parts sent … text=N`, у подписчика пост без многоточия; правка такого
   поста доходит (`message edit applied`, старые куски исчезли); (2) на
   канале ≤ 20 подписчиков просмотры/реакции как раньше; на канале > 20 у
   читателя `counters requested … hub=`, у владельца `counters served`, у
   читателя `counters applied` и числа сходятся с владельцем; (3) в
   «Избранном», чатах и прокси «+» не перекрывает последнюю строку. Открытый
   вопрос владельца (2026-09-10): в ссылках на посты виден хост
   `p2p-relay.1985vzhem.workers.dev` - нужен нейтральный домен (см. ответ
   бота в чате; сам домен покупает/подключает владелец).

0c. **v11.70.7 выпущен 2026-09-09 (Latest, тег `71d3f7c`): рой, этап 2
   (первая волна, эстафета, полосы, `peers`) + фото у отправителя в личке
   целиком.** Карта кода -
   `docs/CHANNEL_SWARM_DESIGN.md` §9.4 «Этап 2». Что проверить на
   телефонах: (1) подписчик после вступления/первого манифеста шлёт свой
   ключ - у владельца в логе нет ошибок, у подписчика `manifest accepted`;
   (2) на канале > 20 умеющих рой у автора в логе `swarmRest=N` > 0, у
   подписчиков не из волны - `pwant sent … seeds=`, потом `pwant served`
   у сидов и `manifest relayed … to=`; (3) на маленьком канале (≤ 20)
   поведение как в v11.70.6 - никаких `pwant` в логах при живом веере (только
   если пост не доехал за 90 с). Если у сидов сыплется `pwant throttled` -
   пределы `SERVE_PARALLEL`/`PIECES_PER_MINUTE` малы для канала.

0b. **v11.70.6 выпущен 2026-09-09 (Latest, тег `5f10e13`): рой, этап 1.**
   Подписанные манифесты постов `pman`, ключи `pkeys`/`pkreq`, `preq`
   любому участнику, просьба трём соседям, схема БД 19. Карта кода -
   `docs/CHANNEL_SWARM_DESIGN.md` §9.4. Что проверить на двух телефонах:
   (1) владелец публикует пост → у подписчика в логе `manifest accepted`;
   (2) третий телефон вступает при выключенном владельце → `posts requested
   … peers=` и посты приходят от подписчика (`posts backfilled … owner=false`);
   (3) правка поста автором → у подписчика `manifest accepted … rev=1`.
   Если `PostSigner` пишет `eddsa public key differs from core key` - подпись
   выключена, посты идут по-старому; это надо чинить, а не игнорировать.
   Предыдущий релиз v11.70.5 (тег `c535800`) владелец проверил: «Репост
   лёгкий и с фото и с текстом и ссылкой. Всё переходит и работает.»

0a. **Раздача роем (главная задача после v11.70.4).** Владелец 2026-09-09:
   «всё, что роем можно сделать, - всю систему настраивать на ройную»;
   раздавать и отдавать «сначала своим, проверенным, популярным, стабильным,
   потом всем подряд» с настраиваемым пределом, чтобы телефон не
   перегружался. Проект - `docs/CHANNEL_SWARM_DESIGN.md` (разделы 7 -
   этапы, 8 - решения владельца с принятыми по умолчанию ответами, 9 -
   приоритеты и пределы). Кусок 0 (ярусы/пределы) - v11.70.4, этап 1
   (манифесты) - v11.70.6, этап 2 (первая волна K, `pwant` полосами,
   `peers` при N > 100) - v11.70.7, этап 3 Kotlin-часть (длинный текст,
   счётчики через владельца, эстафета умеющим рой) - v11.70.8; ядру остались
   подпись и куски по QUIC (нужна перегенерация uniffi). Ничего из роя на
   телефонах не проверено.

1. Контрольный замер скорости ПОСЛЕ конвейера (деплой + отправка 64 МБ +
   `-CollectLogs`, скорость считать по логам `lan-frames`, они пишутся каждые
   512 кадров с таймстампами). Цель владельца: ≥10 Мбит/с.
2. Приёмка «≥1 ГиБ, ≥10 Мбит/с, реальный документ через чат» — не доказана.
3. Троттлинг `lan-seek` для мёртвых нод (сейчас спам каждые 30 с, переполняет
   relay-очередь 500).
4. Mesh-кастодиальная offline-доставка через третьи телефоны — wiring написан,
   но НЕ подключён к движку/FFI/Android.
5. LAN-aware размер кадра (сейчас лимит 4 КиБ подобран под живой MQTT-путь,
   поднимать глобально нельзя — 33КБ фрагменты терялись брокером).
6. Раздел ГРУППЫ — выпущен в v11.19.0 (схема БД v8, темы, заявки, приглашения
   с QR, поиск участников, публичная/частная, статистика, разрешения, закрепы).
   Гейт на Windows был зелёный на `7170c53`; код приложения в `f6d7502`
   совпадает с ним байт в байт. После выпуска владелец прислал 9 замечаний
   (сканер не читал QR, закреп был общий на группу, не было вкладки
   «Администраторы», пустой обзор, нет удаления группы и отозванных ссылок,
   ссылки нельзя копировать и переслать, в статистике вместо имён тем были
   идентификаторы) — всё исправлено в ветке, но **эти правки ни разу не
   компилировались**: в песочнице нет ни cargo, ни JDK/Gradle.
   Первый шаг — прогнать `scripts/groups-build-gate.ps1` на Windows, затем
   деплой debug-сборки и чек-лист из 9 пунктов на телефоне.
   Архитектура: Rust-ядро не менялось, доставка идёт фанаутом 1:1 за
   интерфейсом `GroupDelivery`; для 1000+ участников нужен gossip-фанаут в
   ядре отдельным шагом.

7. Уведомление об удалении группы для тех, кто был офлайн. Сейчас пакет
   `APUGRP1|grpdel|<groupId>` уходит только тем, кто был в списке участников на
   момент удаления; выключенный телефон свою копию группы не стирает. Нужен
   отдельный вид отложенной доставки (по образцу mesh-кастодии из пункта 4) либо
   сверка состава группы при следующем обмене roster. Владелец отложил:
   «можешь записать в план и сделаем позже» (2026-08-27).

8. Гифки и стикеры с клавиатуры. Приложение не принимает `commitContent`:
   `commitContent`/`ReceiveContentListener`/`InputContentInfo` — 0 вхождений в
   `app/src/main`, поэтому Gboard не может вставить ни гифку, ни стикер
   (сообщение «приложение не поддерживает вставку» показывает сам Android).
   В Compose это делается только через новый `BasicTextField(state = TextFieldState)`
   и модификатор приёма контента; проект на Compose BOM 2024.12.01
   (foundation 1.7.6), где API уже стабильный, но точное имя модификатора
   (`receiveContent` против `contentReceiver`) надо подтвердить компилятором —
   вслепую переписывать поле ввода чата нельзя. Это рефактор поля ввода в двух
   экранах плюс отправка полученного URI через существующую подготовку файла.
   Также в приложении нет ни одного `ACTION_SEND` фильтра, то есть «Поделиться»
   из галереи в APUMIR не работает вовсе — это отдельная небольшая задача.
9. Файлы, гифки и стикеры в группах на любое число участников.
   Потолка участников в коде НЕТ и не нужен: владелец 2026-08-27 подтвердил
   «ограничения на количество человек в группе не должно быть». В коде группы
   нет ни одной константы-лимита; `maxConcurrent = 8` в GroupDelivery - это лишь
   параллельность веера отправки, не членство. UI про лимиты тоже молчит.
   Текущая передача файла строго 1:1 (свой key envelope и binding), поэтому
   «файл в группу» сегодня = веер отдельных передач. Владелец описал желаемую
   схему словами торрента: один отправил нескольким, те ищут следующих
   онлайн-участников и пересылают дальше, и чем больше получили, тем шире
   раздача. Это ровно эпидемический gossip поверх существующего интерфейса
   GroupDelivery (вторая реализация, репозиторий и UI не меняются).
   Практически это кастодия из пункта 4: отправитель кладёт файл один раз,
   в группу уходит короткий пакет «файл доступен» с SHA-256 (влезает в 16 КиБ),
   участник тянет байты у ближайшего хранителя/свида или у отправителя по LAN,
   дедупликация по SHA-256. Сначала подключить mesh-кастодию
   (`rust-core/src/file_custody.rs` написан, к движку/FFI/Android не подключён).
   До этого вложения в группах не включаем, а приём стикеров с клавиатуры
   (пункт 8) ведём через личные чаты, где доставка уже есть.

10. **Осиротевшие группы и каналы — СДЕЛАНО И ВЫПУЩЕНО в v11.73.0
    (2026-09-18, Latest, на телефонах не проверено).** Удаление старых аккаунтов
    уже есть, а что делать с группой или каналом, чей владелец удалился, -
    было пусто. Теперь наследование и передача работают. Как устроено:
    - **Провод.** Новый пакет `own` в `GroupWire`
      (`APUGRP1|own|groupId|b64(новый)|b64(прежний)|время|give|take`):
      `give` - добровольная передача от нынешнего владельца, `take` -
      наследование. Старые телефоны вид не знают и молча отбрасывают -
      остаются с прежним владельцем, ничего не ломается.
    - **Правила - `GroupOwnership.kt`** (чистые функции, тест
      `GroupOwnershipTest`): администратор может забрать владение, если
      владелец молчал дольше 90 дней; если администраторов нет вовсе -
      любой участник, но после 180 дней. Молчание - по наблюдениям ЭТОГО
      телефона (`PeerRatingStore.lastSeenMs` через лямбду
      `peerLastSeenMs` в `GroupRepository`); владельца, которого телефон
      ни разу не видел, молчавшим не считаем (вступивший по ссылке не
      должен уносить права активного канала). Спор двух наследников
      решает меньший nodeId - все телефоны сходятся на одном владельце.
    - **Репозиторий:** `transferOwnership` (владелец передаёт права
      администратору; бывший владелец остаётся админом со всеми правами),
      `claimOwnership` (наследование по срокам молчания), приём пакета
      `own` в `handleIncoming`: `give` верим только от своего владельца,
      `take` - только наследнику о себе, с проверкой его роли и молчания
      по СВОЕЙ копии состава и СВОИМ часам; принятую заявку телефон
      передаёт дальше один раз (гаснет само). Смена владельца - UPDATE
      строки группы (не перезапись), роли обновляются рассылкой `roster`.
    - **UI (`GroupAdminScreen`, вкладка «Администраторы»):** у владельца на
      каждой карточке администратора кнопка «Передать владение» (с
      подтверждением); когда срок молчания прошёл, вверху появляется
      карточка «Стать владельцем» - у администратора или (без админов) у
      участника.
    - Проверка: чистые файлы (`GroupOwnership`, `GroupWire`,
      `GroupPermissions`) и тесты прогнаны локальным компилятором
      Kotlin в песочнице (80 тестов, 0 провалов); полная компиляция
      дерева не даёт новых диагностика против базового коммита. На
      телефонах НЕ проверено.
    - Из наброска осталось верным: подписанные манифесты постов прежнего
      владельца остаются действительными; для ключей (`pkeys`) ничего
      менять не стали - проверка роя доверяет самоподтверждённым ключам
      (`post_signers`), а новый владелец при первом же ответе на его пакеты
      получает чужие ключи как раньше. На телефонах не проверено.

## Тестовые телефоны (не трогать без предупреждения!)

- Стас = TECNO LI6, serial `11567254BK001192`, node `pk_40d2401a3ac3029c88cc3d1d3bc62a95`
- Аня = MTN NX1, serial `AUYF6R5923006121`, node `pk_dee60d8c7064cb49b390f7ee4b22aebd`
- adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- Прежде чем предлагать деплой или прогон на телефоне, спросить: владелец не
  всегда у компьютера и не всегда с телефонами. Дата в старых записях журнала
  этого не гарантирует — спрашивать словами.

## Правила работы (от владельца, соблюдать строго)

- Один шаг → проверка → следующий шаг. Не начинать проект заново.
- По-русски, простыми словами. Перед код-блоками объяснять, какие этапы
  «молчат» и как проверить живость.
- Команды владельцу — только совместимые с PowerShell 5.1: **без `&&`**
  (его PS его не понимает, ошибка «Лексема "&&" не является допустимым
  разделителем»), разделитель `;` или отдельные команды; одна команда в
  отдельном блоке, начинать с `cd C:\APU-M8`.
- Телефоны — только с явным предупреждением и поимённо.
- Не предлагать правки настроек роутера/системы: если LAN не работает —
  чинить кодом приложения.
- Обычные правки/коммиты/пуш своей arena-ветки разрешения не требуют.
  Релиз/тег/публикация — только с явного разрешения владельца.
- **Работа живёт в Git, а не в чате (правило от 2026-09-17).** Рабочее
  пространство Arena-сессии исчезает вместе с сессией: 2026-09-17 сессия,
  где был готов K4 (17 файлов, +1339/−56), перестала отвечать до пуша, и
  работу не удалось достать ни из GitHub, ни из песочницы, ни с ПК —
  её пришлось переделывать заново. Поэтому законченный шаг сразу коммитится
  и пушится в свою ветку `arena/<id>-apumir`: не держать готовый код только
  «в сессии» в ожидании разрешения на PR. Разрешение владельца нужно на
  релиз, тег и публикацию — но не на пуш рабочей ветки.
- **Структура репозитория (правило от 2026-08-29, подробности в
  `docs/START_HERE.md`, раздел 9):** `main` — единственная ветка правды и она
  обязана совпадать с рабочим кончиком; релизы — только теги `vX.Y.Z`;
  рабочая ветка сессии одна и она потомок `main`; ветки «на память» не держать.
  В конце сессии `main` догоняется скриптом `scripts/sync-main.ps1`
  (fast-forward, без тегов и без релизов).
- ЕДИНЫЙ СТИЛЬ APU для каждого нового раздела/экрана (требование владельца
  от 2026-08-29): подложка ChatWallpaper на весь экран, в том числе под
  верхней панелью (Scaffold containerColor = Transparent + прозрачный
  TopAppBar); скруглённые кнопки (AppShapes), золотые акценты темы;
  весь текст по-русски; @никнейм пишется именно так (не «@имя»). Новый
  экран без подложки и не по-русски — не принимать.
- Перед КАЖДЫМ commit'ом проверять `git log --oneline -1`: песочница может
  быть пересоздана (локальный HEAD висит на базовом коммите `bdc69ae…`).
  Лечение: `git fetch origin <ветка>` → `git reset --soft <origin-tip>` →
  recommit → push. Проверять, что diff старого и нового коммита пуст.
- Push при отклонении: повторить fetch и сверить; git без пайплайнов/2>$null.
- Юнит-тесты: не смешивать с assemble в одном вызове `--tests`; зелёный
  фильтрованный прогон ≠ гейт. Деплой-скрипт сам гоняет тесты перед сборкой.
- Грубый brace-чекер по кавычкам врёт (апострофы в KDoc) — использовать
  лексер с учётом строк/комментариев (есть примеры в notes).
- Смотреть свежесть кода на телефоне по `dumpsys lastUpdateTime` (маркеры в
  logcat ротируются шумом).

## Типичные грабли (уже наступлены, не повторять)

- **Двойной `Default` в новом типе (2026-09-17).** `#[derive(Default)]` на
  структуре вместе с ручной `impl Default for` - это не «одно перекроет
  другое», а ошибка компиляции E0119 (конфликт реализаций). Стоило одного
  прогона проверки; нашлось по комментарию к pull request, потому что
  `cargo check` в песочнице запустить негде.
- **Русские буквы в байтовом литерале (2026-09-17).** `b"...|привет"` в Rust
  запрещено (байтовая строка обязана быть ASCII) - это была вторая поломка
  K3 в `main`, из-за которой код не собирался вовсе. Русский текст в тестах
  писать обычной строкой и брать `.as_bytes()`.
- **Отчёт проверки ядра: каталог для лога создавать заранее (2026-09-17).**
  В `ci-core-check.yml` шаг писал `cargo check ... | tee ../target/ci-check.log`,
  а каталога `target` в корне клона нет - `tee` падал сразу, `set -o pipefail`
  ронял шаг (exit 1 вместо 101), и вместо ошибки компилятора в отчёте было
  «log file not found». Теперь каталог создаётся отдельным шагом, путь
  абсолютный (`$GITHUB_WORKSPACE/target/ci-check.log`), полный лог уезжает
  артефактом, а в комментарий к pull request попадают строки с `^error` и
  хвост вывода. Логи прогона из песочницы Arena не скачиваются
  (`results-receiver.actions.githubusercontent.com` недоступен, страница
  требует входа) - комментарий к PR остаётся единственным каналом, поэтому
  он должен содержать саму ошибку.
- **Двоеточие в имени шага воркфлоу (2026-09-17).** Файл проверки ядра
  поставили в `.github/workflows`, а прогон не появился: GitHub показал
  «run likely failed because of a workflow file issue», название
  воркфлоу вместо имени - путь файла, логов нет. Причина - строка
  `- name: uniffi: lib.udl ...`: в YAML двоеточие с пробелом внутри
  значения без кавычек читается как вложенное отображение, и файл не
  разбирается целиком. Правило: в `name:` шагов не ставить `: ` без
  кавычек (и вообще держать имена шагов латиницей). Проверка перед
  установкой встроена в `scripts/install-ci-core-check.ps1`.
  Заодно грабля: у GitHub-приложения из Arena нет права `workflows`,
  поэтому файл в `.github/workflows` агентом не пушится (`git push` и
  `gh api PUT` дают «refusing to allow ... without workflows
  permission» / «Resource not accessible by integration»); ставит
  владелец скриптом `scripts/install-ci-core-check.ps1`, причём
  скачивать его надо через `git show origin/<ветка>:scripts/...` -
  `gh` на ПК владельца не всегда достаёт `api.github.com`.

- **Тег на отставшем `main` (2026-09-08, v11.70.0 первый раз).** `sync-main.ps1`
  упал («local branch … does not exist» - на ПК владельца ветки сессии нет,
  она только в песочнице), `main` остался на `b7f7971` от 5 сентября, а
  `make-release.ps1` честно написал `tag to create: v11.70.0 on b7f7971` и
  собрал релиз **на 19 коммитов старше v11.69.3** со схемой БД 15 вместо 17.
  При `fallbackToDestructiveMigration()` откат схемы = **стёртая переписка у
  каждого, кто обновится**. Спасло только то, что релиз висел prerelease.
  Релиз и тег удалены. Теперь `sync-main` берёт кончик из `FETCH_HEAD`, а
  `make-release` отказывается ставить тег, если HEAD не содержит предыдущий
  релизный тег и `origin/main`. Правило для человека: перед `make-release`
  прочитать строку `tag to create:` - hash обязан совпадать с кончиком ветки.

- **Тестовый slug не из алфавита** (v11.69.x). В `GroupInviteLinksTest` стоял
  slug `"abcd1234"`, а алфавит `newSlug` без `0 1 I O l`; `isValidSlug` его
  тихо отбраковывал, `parseTarget` давал null, и три теста на ссылку поста не
  могли пройти - но CI при релизе юнит-тесты не гоняет, а гейт на Windows никто
  не запустил. В тестах ссылок брать `GroupInviteLinks.newSlug()` или
  `"Abcdefghijkmnopq"`. Проверка без JVM: перенести `parseTarget` на Python
  один в один и прогнать те же случаи (так и был найден дефект).
- **Новая форма ссылки - и в регулярку поиска.** `parseTarget` разбирает не
  только чистый адрес, но и ссылку внутри текста (`LINK_PATTERN`): в MAX и
  подобных копируется всё сообщение. Добавили https-форму в `parseClean`, а в
  `LINK_PATTERN` забыли - вставка всего сообщения перестала работать, хотя
  прямой тест ссылки был зелёный. Любая новая форма - в обе точки и тест на
  «ссылка внутри текста».
- **https-ссылка без assetlinks открывается в браузере.** `autoVerify` без
  опубликованного `/.well-known/assetlinks.json` на хосте ничего не даёт: на
  Android 12+ ссылка уходит в браузер, «перехват» не происходит. Порядок -
  сначала файл на хосте, потом установка APK.
- **Бот создал релиз раньше CI** (v11.69.1, v11.69.2): workflow увидел готовый
  релиз и пропустил сборку - получились теги без APK. Релиз создаёт только
  Actions по тегу; вручную релиз не заводить, только `promote-release.ps1`.
- **CI падает до сборки: `Failed to find package 'tools'` (2026-09-15,
  v11.70.19, прогоны 34913838206 и 34914685178).** Шаг
  `android-actions/setup-android@v3` без `with: packages:` ставит по
  умолчанию `tools platform-tools`; пакет `tools` (старые SDK Tools) Google
  из репозитория убрал 2026-09-14, `sdkmanager tools` выходит с кодом 1, и
  шаг валится - до Rust, Gradle и нашего кода. Лечение - явное
  `packages: platform-tools` (лежит в `scripts/ci/build-release.yml`).
  Правило: у любого шага с `sdkmanager` пакеты перечислять явно и только
  нужные. Из песочницы workflow не поправить: `git push` с изменением
  `.github/workflows/` отвергается («without `workflows` permission»),
  Contents API и Git Data API отвечают 403, `rerun-failed-jobs` - 403;
  копирует владелец (сделано 2026-09-15, `abbb5c4`, после чего прогон
  34921724763 собрал v11.70.19). Журнал упавшего прогона читать через ссылку из
  `gh api repos/…/actions/jobs/<id>/logs` (в ответе «EOF» - открыть URL
  из сообщения об ошибке через fetch_page; ошибка в предпоследнем куске).

### Самое дорогое (сентябрь, стоило суток тестов)

- **Собранное ядро лежит в git и НЕ пересобирается локальной сборкой.**
  `android-app/app/src/main/jniLibs/*/libp2p_core.so` - готовый артефакт.
  Гейт и `assembleDebug` кладут его как есть; Rust компилирует только CI на
  релизе. Любая правка в `rust-core/` НЕ попадёт на телефон до релиза.
  В гейт добавлен шаг `step 0c`: ищет строку-маркер из исходников внутри `.so`
  и печатает предупреждение. **Не обещать владельцу проверку правки ядра
  локальной сборкой.**
- **Uniffi-привязки (`android-app/.../uniffi/p2p_core/p2p_core.kt`) с
  v11.70.23 ПЕРЕГЕНЕРИРУЮТСЯ в CI из `rust-core/src/lib.udl`** (шаг
  «Regenerate uniffi Kotlin bindings from lib.udl» в
  `.github/workflows/build-release.yml`; доказано прогоном 35005340748:
  `coreBuildInfo` появился в мосте, APK собрался, CI сам закоммитил мост и
  `.so` в main - `551f0d4`). Новую функцию ядра добавлять так: `lib.udl` +
  Rust + вызов из Kotlin полным именем `uniffi.p2p_core.xxx()` в
  `try/catch (Throwable)`; в песочнице мост по-прежнему старый до релиза -
  править `p2p_core.kt` руками НЕЛЬЗЯ (контрольные суммы), после релиза
  делать `git fetch origin main && git merge --ff-only FETCH_HEAD`, чтобы
  забрать коммит CI. Порядок и план работ по ядру - `docs/CORE_ROADMAP.md`.
- **Компилятора Kotlin в песочнице нет, логи CI из песочницы не скачиваются.**
  Две сборки подряд упали на пропущенных импортах. Перед коммитом проверять
  вручную: для каждого `Icons.Default.X` и `Modifier.width/size/...` искать
  импорт в ЭТОМ файле (в части файлов импорты поимённо, без звёздочки).
- **Room: проекция `COUNT(*) AS count` в свой data class уронила сборку.**
  В проекте такой приём больше нигде не используется. Отдавать сущности и
  считать в Kotlin.

### Протоколы и формат на проводе

- **`RustBridge.sendMessage` - блокирующий вызов, звать только с `Dispatchers.IO`.**
  Прямой QUIC внутри ядра ждёт до 5 с на соединение и до 5 с на запись, и
  делает это через `block_on` в потоке вызывающего. С главного потока это
  «Приложение не отвечает»: так было с ACK'ами (`5f713d7`) и с веером
  постов в канал (2026-09-08). Веер групп уже уводит в IO в
  `di/GroupsModule.kt`; новый прямой вызов из репозитория или ViewModel -
  оборачивать в `withContext(Dispatchers.IO)`. Сторож: на главном потоке
  `sendMessage` пишет в logcat `sendMessage on the main thread` с трассой.
- **Что разбирается ДО расшифровки - шифровать нельзя.** `apu-file-hello1`
  (несёт сам ключ) и `APULAN1` (адрес для прямого канала) уходят открытыми,
  проверка стоит в `RustBridge.sendOutgoing`. Иначе прямой канал не поднимется
  и связь пойдёт в одну сторону.
- **Тело обычного MQTT-сообщения - РОВНО 4 поля** `senderId|messageId|chatId|text`.
  Приёмник когда-то ждал 5 и резал текст по `|`; из-за этого пропадали и
  сообщения, и обмен ключами. Адресат берётся ИЗ ТЕМЫ `p2pm2/msg/<кому>`.
  Подписка идёт на `p2pm2/#`, поэтому проверка адресата обязательна и для ACK -
  без неё узел засчитывал СВОЙ же ACK.
- **Идентификатор узла бывает 32 и 64 hex-знака** (`is_legacy_routing_node_id`
  допускает оба). Не привязывать к нему криптографию: манифест конверта
  сообщения привязан только к отпечатку тела.
- **Идентификатор чата у собеседников РАЗНЫЙ.** Личные чаты заводятся на каждом
  телефоне отдельно. Всё, что приходит с чужим `chatId` (реакции, отчёты),
  переводить в свой чат по отправителю.
- **Счётчики (сердечки, просмотры) хранить как строки «кто», а не число.**
  Иначе повтор пакета накрутит. Голосующего брать из отправителя, не из тела.

### Прочее

- В `powershell -Command "..."` переменные `$x` подставляет ВНЕШНЯЯ оболочка -
  команда разваливается. Тело JSON собирать прямо в параметре.
  PowerShell 5.1 требует `[Net.ServicePointManager]::SecurityProtocol='Tls12'`
  для Cloudflare.
- `-ApkPath ''` в install-debug-on-phones.ps1 передавать НЕ надо: пустые
  кавычки съедаются, скрипт сам подставляет путь. Скрипт падает на ПЕРВОМ
  телефоне и до второго не доходит.
- Консоль PowerShell после ответов `y/n` может начать съедать заглавные буквы -
  открыть новое окно.
- MSVC на машине владельца НЕ ставить (доп.201/202: BSOD, сбойный диск).

- `git pull` на Windows может падать по сети → деплой соберёт СТАРЫЙ код.
  Всегда сверять строку «Repo HEAD» с ожидаемым хешем.
- Kotlin: `"%02x".format(byte)` требует `and 0xff`; `InetSocketAddress.hostAddress`
  нет (→ `address?.hostAddress ?: hostString`); suspend-лямбда из блокирующего
  потока — только через `runBlocking`.
- Установка debug-APK: всегда `adb install -r -t -d`.
- Release-APK подписан другим ключом — поверх debug не ставится.
- Публичные MQTT-брокеры: потолок ~999 Б/с, иногда лежат целиком; mesh —
  только сигналинг/отложенная доставка, никогда как доказательство скорости.
