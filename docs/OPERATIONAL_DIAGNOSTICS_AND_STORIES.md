# Операционные метрики и восстановимая досылка историй

Изменения после v11.74.356; новый APK этим изменением не публикуется автоматически.

## Что показал присланный отчёт

Сеть VALIDATED, 0 overdue/late UI-проб, но signing/file-exchange unavailable и
19 неоткрытых конвертов. Это не доказательство смены ключей собеседников.
Максимумы core_events/file_pump около 4 секунд включают ожидание IO и не доказывают
блокировку главного потока. Хранилище current: 44 058 encrypted rows, 259 926
tombstones; в выборке все 512 строк просрочены. Нельзя экстраполировать это на
всю таблицу. custody=ram-only означает, что работа durable cleanup в этой сессии
не подтверждена; прежние файлы SQLite при этом могут оставаться на диске.
Ни ключи, ни существующие relay-данные этим изменением не очищаются.

## Четыре реализованных направления

1. **Сообщения:** Room persistence → transport attempts → first transport accept →
   recipient delivery ACK. Только реальные outgoing persistence hooks и ACK,
   изменившие статус строки. До 256 process-local корреляций; IDs не выводятся и
   не сохраняются в диагностике. Завершения после рестарта/вытеснения считаются
   ACK_UNTRACKED без выдуманной latency. Повторы/отложенные попытки отдельно.
   p50/p95 — верхние границы фиксированных корзин, не точные квантили; последняя
   корзина >1ч открыта. Это не метрики всех групп/служебных/зеркальных маршрутов.
   Удаление: requests, retry opportunities, ACK matched to pending peer. Счётчик
   не меняет существующие правила авторизации/удаления и не является новым ACK-протоколом.
2. **Хранение:** полный COUNT просроченных по открытой expiry и MIN(expiry),
   отдельно от ограниченной payload-size выборки. Все SQL используют прежний
   общий CancellationSignal/2с budget; поля неизвестны при timeout. Никакого
   чтения/decrypt BLOB. Снимок не транзакционный; concurrent counts могут расходиться.
   Physical delta SQLite/WAL/SHM и live/expired row delta сравниваются с прошлым
   успешным отчётом, numeric baseline переживает restart. Это не суточная скорость.
   Старый baseline не заменяется частичным audit. Очистка/ACK/eviction: прежние
   RAM-eviction поля + numeric durable purge/quarantine/failure counters из
   ограниченного logcat + последний RAM ACK cleanup count, без повторного накопления
   при Refresh. Квота остаётся файловой: добавлен счётчик реальных quota rejections,
   она не превращается в лимит SQLite. Cleanup политики и VACUUM не менялись.
3. **Передачи:** повторные pump attempts, waiting-recipient attempts, surfaced typed
   timeouts, успешные/неуспешные LAN/UDP/QUIC/broker/binary route attempts и смена
   успешного маршрута (bounded per-peer correlation, без peer IDs в отчёте).
   Подтверждённые полезные байты и end-to-end goodput только после final receiver
   ACK или целостной проверенной записи входящего файла. Это время от создания,
   включая ожидание, не speed test. Wall-clock может меняться. F4 ciphertext bytes
   остаются отдельным прежним счётчиком. Не все внутренние native таймауты доступны
   Kotlin: false transport result не называется таймаутом автоматически.
4. **Запуск/фон:** реальные service creates/start commands/destroys, service→ready,
   foreground→ready, foreground/background uptime. Ready включает ограниченный/
   зеркальный режим. Wakelock: успешные timed acquire и release/timeout ядра дают
   верхнюю оценку удержания; это не BatteryStats, не mAh и не все locks приложения.
   Сон исключён из uptime; wakelock расчёт использует elapsedRealtime.

Дополнительно: **AtomicFile-журнал 64 фиксированных фаз** (процесс/служба/ready/
foreground/background), до 32 КиБ, local-only; coalesced writer не копит задачи
по одному на событие. В файл идут только enum/timestamp/numeric baselines, не IDs,
тексты, имена, трассы или ключи. Последняя запись при kill может не сохраниться.
Export только по кнопке «Логи», серверной телеметрии нет.

Keystore mode() теперь сообщает безопасную категорию этапа/исхода:
missing_wrap_key / keystore_error / unwrap_failed / binding_invalid /
incomplete_record / absent / ready, без исключений и секретов. Диагностика всё
так же не создаёт ключи. UNWRAP разворачивает только существующий локальный
seed/secret для проверки; временный секрет обнуляется, переписка не расшифровывается.

Redaction дополнительно скрывает короткие hex sender/peer fields, route-bearing
MQTT topics (включая нестандартные имена узлов) и фактические PID/TID в threadtime.
Время/уровень остаются доступны для приоритизации ошибок.

## Истории: подтверждённая проблема кода и исправление

Раньше sendStoryPacket не имел phone-owned outbox; текст отправлялся один раз,
фото — ограниченными волнами. Fail-closed sealing при отсутствии keys/binding
возвращает false ДО native transport. Такой пакет не попадал даже в relay, и
после окончания волн приложение его больше не досылало. Это проверяемый дефект
пути повторов, но не доказательство всех причин исчезновения историй на телефонах.

Теперь:
- перед enqueue подтверждается AtomicFile-сохранение StoryStore (IO, wait до 2с);
- отдельная AtomicFile-очередь содержит только локальные owner/story/peer/cursor/time
  metadata; максимум 256 recipient jobs / 256 КиБ, без копирования тела истории;
- service pump делает максимум 16 пакетов за цикл, fair rotation; failure остаётся
  pending, retry через 60с, TTL — сутки от создания истории;
- sender restart восстанавливает очередь; wrong-owner записи не отправляются;
- полный transport-accepted wave удаляет recipient job, но НЕ называется
  подтверждением сохранения/показа у друга; нового app-level story ACK нет;
- revoke заменяет pending publish; stale result не может удалить новую revoke
  generation. Transport UUID отзыва отдельный от part0 публикации: иначе native
  dedup мог бы принять отзыв за уже виденную публикацию;
- переполнение/ошибка сохранения/no audience видны в diagnostics/UI. Очередь не
  вытесняет старые публикации молча; при нехватке места для revoke старое publish
  отменяется, частичное покрытие адресатов явно возвращает ошибку;
- received-visible update / incomplete photo / invalid / expiry / revoke / attempt /
  defer / accepted / transport-round-complete показываются отдельно.

Без внешнего HTTP и без открытого fallback: старое требование private payload
allowlist сохраняется. Если локальный wrapping key действительно отсутствует,
досылка НЕ чинит его и не делает расшифровку возможной. Нужны отчёты категорий с
обоих устройств и отдельное решение о безопасном восстановлении личности, не reset.

**Ограничения:** после simultaneous sender/receiver restart недособранные части
фото у получателя ещё не имеют durable assembler; transport success не является
application story receipt. Остальные контакты вне текущего audience не появляются
автоматически. TTL/планирование зависят от часов и возможности Android запустить
службу. Нельзя обещать гарантированную доставку/удаление без связи.

## Проверка

Pure JVM: OperationalHealthTest (9), StoryDeliveryQueueTest (7), новый revoke UUID
тест StoryOutboxTest, новые privacy/report tests. Подключены standalone compiler и
Android CI filter. Android relay fixtures дополнены full-vs-sample expiry проверкой;
их компиляция не является запуском на устройстве. Source guards проверяют bounds,
wiring, read-only SQL и отсутствие HTTP/key reset. Физические телефоны здесь не
доступны; надо отдельно проверить stories text/photo/revoke при потере ключей/сети,
sender restart и обмен только через собственный подписанный direct swarm.
