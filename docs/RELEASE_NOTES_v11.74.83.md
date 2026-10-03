# APU v11.74.83 — закрепы в «Избранном» (закрепы везде закрыты)

## Что изменилось (раунд 187, миграция БД v20 → 21)

Владелец: «доделай избранные» - последняя точка из задачи р173
«закрепы везде».

1. SavedItemEntity: +isPinned (Boolean, DEFAULT 0) +pinnedAtMs
   (Long, DEFAULT 0).
2. APP_DATABASE_VERSION 20 -> 21; MIGRATION_20_21 - честный
   ALTER TABLE ADD COLUMN (данные сохраняются); зарегистрирована в
   AppModule ПЕРЕД fallbackToDestructiveMigration - разрушительный
   путь для этой версии не применяется.
3. SavedItemDao.setPinned(id, pinned, atMs); SavedItemsRepository
   обёртка (atMs = now | 0).
4. SavedViewModel: сортировка - закреплённые сверху, среди них по
   pinnedAtMs; togglePin.
5. SavedScreen: булавка на каждой записи (золотая = закреплено);
   карточка «Закреплённое» над списком (SavedPinnedBar): тап -
   animateScrollToItem(индекс+1, карточка занимает строку), крестик -
   открепить.

Итог р172-187: закрепы работают во ВСЕХ четырёх местах: личные чаты,
группы, каналы, «Избранное».

## Данные

- check140 success. v11.74.83 = edb275e, run 36311239915, Latest,
  APK 40 582 290 Б, sha256 17bc449d…d15.
