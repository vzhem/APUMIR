# M8 — Persistent relay custody (зашифрованное постоянное хранилище relay-очереди)

> Контекст: релиз `v11.16.16` («test checkpoint») зафиксировал, что relay-очередь
> (custody — сообщения, которые наш телефон хранит для офлайн-получателей) жила
> только в памяти процесса. При убийстве процесса Android или перезагрузке
> телефона посредник терял недоставленные сообщения.

## Что требовалось (из release notes v11.16.16)

> Следующий обязательный этап M8 должен зашифрованно сохранять custody перед сном,
> восстанавливать её после process death/reboot, после пробуждения запрашивать
> новые допустимые relay items, пытаться передать старые и новые сообщения и снова
> сохранять всё недоставленное **без сброса TTL**.

## Что сделано

### 1. Персистентные снапшоты очереди (`rust-core/src/network/message_queue.rs`)

- Добавлен `QueuedMessageSnapshot` (serde): `msg_id`, `recipient`, `payload`,
  `queued_at_ms`, `expires_at_ms`, `retry_count`.
- `QueuedMessage::to_snapshot()` / `QueuedMessage::from_snapshot()` переводят
  `Instant` ↔ абсолютное wall-clock время (Unix ms). Благодаря этому **TTL не
  сбрасывается** при сохранении/восстановлении: `expires_at_ms` — абсолютный
  deadline, а не «сейчас + 7 дней».
- `MessageQueue::export_snapshots()` — выгрузить всю очередь для сохранения.
- `MessageQueue::restore(snapshots)` — восстановить очередь после перезапуска;
  просроченные сообщения отбрасываются, `retry_count` сохраняется, лимиты
  (`max_per_recipient`, `max_total`) соблюдаются.

### 2. Зашифрованное хранилище custody (`rust-core/src/network/custody.rs`)

- Формат файла: `[P2RC magic (4)][version (1)][ChaCha20-Poly1305 (nonce + ct)]`,
  где полезная нагрузка — `bincode(Vec<QueuedMessageSnapshot>)`, а magic+version
  добавляются как AAD (защита от подделки заголовка).
- Ключ выводится детерминированно через HKDF из секретного ключа узла
  (`derive_custody_key`), поэтому он одинаков между перезапусками одного
  устройства, но разный у разных узлов. Секрет не пишется в файл custody.
- `save_custody()` — атомарная запись (временный файл + `rename`).
- `load_custody()` — чтение + расшифровка; отсутствующий файл → пустая очередь
  (чистый запуск); неверный ключ/подделка → ошибка.

### 3. Интеграция в движок (`rust-core/src/engine/core.rs`)

- `EngineConfig.custody_path` + `.with_custody(path)`.
- `restore_custody()` вызывается в `start()` сразу после создания очереди —
  восстановление после process death/reboot.
- `persist_custody()` вызывается в `stop()` — сохранение перед «сном».
- Фоновое автосохранение каждые 30 секунд в async-рантайме — защита от
  внезапного убийства процесса без graceful shutdown.
- Ключ: `CryptoManager::private_key()` (добавлен getter в `ffi/crypto_ffi.rs`).

### 4. FFI + Android

- Новый конструктор `create_engine_with_custody(display_name, custody_path,
  public_key, private_key)` (UDL `lib.udl`, `lib.rs`, сгенерированный
  `uniffi/p2p_core/p2p_core.kt`).
- `RustBridge.initialize(..., custodyPath = null)` — при указанном пути создаёт
  движок с постоянным хранилищем custody.
- `CoreServerService` передаёт `filesDir/relay_custody.bin` — на реальном
  устройстве custody переживает перезапуск/перезагрузку.

## Тесты

- `message_queue.rs`: roundtrip snapshot-ов сохраняет TTL; `restore` отбрасывает
  просроченное и сохраняет `retry_count`.
- `custody.rs`: roundtrip сохранение/загрузка; пустой список; отсутствующий файл;
  неверный ключ; подделка файла; неверный magic; усечённый файл; детерминизм ключа.

Запуск (на машине с Rust): `cargo test --lib message_queue custody`

## Что осталось (следующие шаги)

1. **«Запрашивать новые relay items после пробуждения».** Сейчас транспорт —
   MQTT public broker (fire-and-forget) без серверного inbox, поэтому «запросить
   недополученное» пока нечего. Когда появится серверный store-and-forward inbox
   (например, Cloudflare Worker из `create_store_forward.py`), нужно добавить
   после `restore_custody()` pull новых relay-items и их слияние с очередью.
2. **Приёмка на 3 телефонах**: воспроизвести сценарий «Женя уснул → телефон убит →
   через сутки проснулся → сообщение доставлено». Проверить, что custody-файл
   зашифрован и не содержит plaintext.
3. **TTL-тест на устройстве**: убедиться, что после перезапуска оставшийся TTL
   сохранён, а не сброшен до 7 дней.
4. **Ключ custody в Android Keystore** (Фаза 1.7): сейчас ключ выводится из
   секрета узла, который хранится в SharedPreferences — перенести в Keystore/TEE.
5. **Гонки записи**: автосохранение и `stop()` могут писать одновременно —
   добавить примитив сериализации записи (сейчас используется атомарный rename,
   но последний писатель побеждает).

## Связанные записи

- Release `v11.16.16`: https://github.com/vzhem/APUMIR/releases/tag/v11.16.16
- `scripts/setup_offline_delivery.ps1` → «Next Steps» → п. 4 (persistence layer).
- `rust-core/src/network/message_queue.rs` → раздел «Что НЕ делает (будет в Фазе 1.6)».
