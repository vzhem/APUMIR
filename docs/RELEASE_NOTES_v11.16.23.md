# APU v11.16.23 — стабильный релиз

## Главное

APU теперь сохраняет relay custody в зашифрованной SQLite на телефоне и продолжает доставку после уничтожения Android-процесса. Сообщения могут пережить несовпадающие окна доступности отправителя, посредника и получателя.

## Что добавлено и исправлено

- Encrypted durable relay custody с ключом Android Keystore.
- Восстановление relay-очереди после смерти процесса без продления TTL.
- Exactly-once UI delivery через durable tombstone.
- Receipt cleanup для исходного телефона и online relay-посредников.
- Bounded WorkManager wake: ограниченное 25-секундное receive-only окно без exact alarms.
- Правильный exact path `apu_relay.sqlite`; backup/transfer device-bound данных запрещён.
- Device-local identity marker защищает от некорректного восстановления старого профиля без Keystore-ключа.
- Исправлен cleanup fanout: посредник сразу удаляет доставленное сообщение из RAM и encrypted SQLite.

## Проверено на телефонах

Тестовая цепочка на Анне, Жене и Стасе:

1. Стас offline.
2. Анна отправляет сообщение.
3. Женя сохраняет encrypted durable custody.
4. Процесс Жени уничтожается и запускается заново.
5. Женя восстанавливает relay из SQLite.
6. Стас возвращается online и получает сообщение ровно один раз.
7. Receipt удаляет custody у Жени из RAM и SQLite.
8. Анна возвращается online и получает две галочки.

Финальная проверка exact message ID:

- `targetMessageCount = 0` на всех трёх;
- `targetTombstoneCount = 1` на всех трёх;
- `quarantineCount = 0` на всех трёх;
- дублей и fatal crash нет.

## APK

- Файл: `APU-v11.16.23.apk`
- Размер: `22 796 416` байт
- SHA-256: `85480D5CAF57B9318986BA9E61F9A2A68B38DDD814C683B5949D8E22E7EA9A68`
- Package: `com.vladimir.messenger`
- versionCode: `11016023`
- V2 signer certificate SHA-256: `F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7`

Проверяйте SHA-256 перед установкой. Обновление поверх публичных release-версий с тем же сертификатом сохраняет данные.

## Известные ограничения

- Полный delayed-тест через четвёртый relay-телефон D с физической перезагрузкой ещё не выполнялся.
- Mixed-version N↔N-1 и отдельный security release audit остаются последующими проверками.
- Фактическое выполнение WorkManager wake при полностью остановленном foreground service требует отдельного OEM/runtime теста.

Релиз опубликован как stable/Latest по явному решению владельца проекта после успешного основного трёхтелефонного durable delivery gate.
