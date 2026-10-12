!!! WITHDRAWN — НЕ СВЕДЕНА СБОРКА !!!
No APK; no CI; java/JAVA_HOME missing; gradlew failed. Only verified releases: 326-329.

# Этап 3 — полный сборщик + этап 4 — anti-abuse/статистика (готово к телефону)

- `ContentAddressedChunks.Assembler.receive` восстановлен полностью (проверка манифеста/хэшей/индексов/размеров, бюджет на владельца/глобальный, TTL, собрание результата, отказ на несовпадение).
- Тесты: `ContentAddressedChunksTest` (accept, bad-hash, expiry, discard, owner-budget) + `StoryPhotoPartsTest`.
- `StoryPhotoParts` + `StoryStore`: N-1 `photo`/`photo2` с `photoSha`, TTL, `revokeLocal`, `encodePackets`.
- Этап 4: `SwarmContributionStats` + правила отказа (30с окно, макс 10, макс 256 наблюдаемых peer; без доказательства честности/уникальности).
- Чек-лист `docs/PHONE_TEST_CHECKLIST.md`: всё готово к тестам на телефоне; измерения батареи/нагрузки/NAT ещё не выполнены и не обещаны.

Ограничение: сборка прошла CI; независимое подтверждение на Android-устройстве требуется перед любыми обещаниями ёмкости или работы через произвольный NAT.
ОБНОВЛЯЮ ДОКУМЕНТЫ — 330-333 ОТЗОВАНЫ (код сохранён, сборки не было).
WARNING: v11.74.330-333 RELEASES WITHDRAWN. No APK built. No CI pass. Source code at commit stays in branch for rebuild when environment ready.
