# Чек-лист перед тестами на телефоне (этап 3/4 завершён в коде)

Готово без ложных измерений:
- [x] ContentAddressedChunks.Assembler.receive — полный (проверка манифеста, хэшей, бюджета, изоляция владельца, сборка, отказ по истечению).
- [x] StoryPhotoParts.receiveContentAddressed / receiveLegacy + StoryStore (N-1, `photoSha`, `expiresAt`, revokeLocal).
- [x] SwarmContributionStats + anti-abuse (delivered/refused/peer counts, refusal window 30s, max 10/window, max observed peers 256; без обещаний честности/уникальности).
- [x] Тесты: ContentAddressedChunksTest (5 тестов на полный сборщик) + StoryPhotoPartsTest.

Не выполнено (требует реального телефона/устройства):
- [ ] Device verification `gradlew :app:testReleaseUnitTest` с Android SDK / эмулятором / телефоном.
- [ ] Phone battery / load / NAT-стабильность измерения.
- [ ] Подписанные capability cards (нужны измерения ёмкости).
- [ ] Групповые/канальные content-addressed chunks (только личные истории сейчас).

Ограничения (не изменять):
- Нет обещаний ёмкости/работы через произвольный NAT.
- Signed ACK не доказывает уникальных людей/честность вклада.
- Assembler.receive готов, но требует device confirmation перед гарантией.
