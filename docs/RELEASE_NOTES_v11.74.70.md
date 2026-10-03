# APU v11.74.70 — закрепы повсюду: личные чаты и каналы

## Что изменилось

Владелец: «сделай чтобы закрепы были везде: личные сообщения, избранное,
группы, каналы» (раунд 172 дал переход по закрепу в группах).

1. MessageDao: observePinnedChatMessages (без тем - личка) и
   observePinnedChannelPosts (все пиннутые чата - посты канала живут
   в своих темах).
2. ChatRepository: setMessagePinned + observePinnedChatMessages
   (маппинг в домен).
3. domain Message: +isPinned; MessageEntity.toDomain пробрасывает.
4. Личка: ChatDetailViewModel.observePinned/togglePin; ChatDetailScreen:
   карточка закрепов над лентой (тап - animateScrollToItem по индексу
   chatRows, крестик - открепить), пункт «Закрепить/Открепить» в меню
   сообщения.
5. Каналы: ChannelViewModel.observePinned/togglePostPin
   (ChannelPost.isPinned из entity); ChannelScreen: карточка закреплённых
   постов над лентой (тап - переход), иконка-булавка на каждом посте
   (золотая = закреплён).
6. Избранное: ОТЛОЖЕНО - требуется миграция БД (SavedItemEntity без
   isPinned); при fallbackToDestructiveMigration спешка опасна для
   данных телефонов - отдельный раунд.

## Технические детали

- check123: Message.isPinned не было в домене, PushPin/Close без
  импортов, viewModel вне PostCard - колбэк onTogglePin; фикс 62b84d2;
  check124 success.
- v11.74.70 = 62b84d2, run 36234387005 SUCCESS, latest, APK
  40 549 522 Б.
