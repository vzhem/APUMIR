# APU v11.74.85 — приглашения в группы/каналы карточкой

## Что изменилось (раунд 189)

Владелец: «для групп и каналов такие же приглашения как для друзей...
жирным название, ниже описание, ниже пузырь "Вступить"/"Подписаться";
рассылать через список до 100 человек».

1. util/GroupInviteCardSender: build (заголовок/описание/ссылка) +
   parseCard. Понимает: новый формат («Группа «X» в APU.»),
   AppShare-текст («Присоединяйся к группе/каналу «X» в APU.» - им идут
   приглашения из «Контактов») и старый («Приглашение в группу «X»:»,
   как на скрине владельца) - старые сообщения тоже рисуются карточкой.
2. ChatListViewModel.sendGroupInviteToChats: текст карточкой, описание
   из GroupEntity.about (groupDao уже в конструкторе).
3. MessageBubble: onGroupInvite + GroupInviteCardView - название
   (titleMedium SemiBold), описание (bodySmall 0.75), золотой пузырь
   «Вступить»/«Подписаться» (PersonAdd, shape 14dp).
4. ChatDetailViewModel: +GroupRepository (DI), joinByInviteLink -
   expandLink -> joinByLink (та же механика сообществ: публичная - вход,
   частная - заявка); inviteStatus -> Toast в ChatDetailScreen.

## Данные

- check142 success. v11.74.85 = acb712d, run 36324681892, Latest,
  APK 40 582 290 Б, sha256 f73bfd97…a5c.
