# APU v11.74.59 — приглашение в канал честно пишет «канал»

## Что изменилось

Владелец (скрин): «приглашение в канал пишет что в группу - нужно
чтобы текст был про канал».

- AppShare.groupInviteText(groupTitle, link, isChannel=false):
  «Присоединяйся к каналу «X» в APU.» / без названия -
  «к моему каналу»; shareGroupInvite(..., isChannel=false) -
  заголовок шера «Пригласить в канал».
- Вызовы передают isChannel:
  - ChatListScreen «Отправить ссылку» (chosen.isChannel);
  - GroupsScreen «Отправить ссылку» (chosen.isChannel);
  - GroupAdminScreen InvitesTab -> InviteCard (новый параметр
    isChannel, кнопка «Поделиться»).
- Путь «Отправить в APU» (р158) уже писал what=канал - без изменений.

## Технические детали

- check99 FAILURE: isChannel использован в InviteCard, а параметр
  добавлен только в InvitesTab (кнопка «Поделиться» внутри InviteCard) -
  добавить в сигнатуру InviteCard + прокинуть из InvitesTab;
  check100 success. УРОК: параметр в Composable-иерархию - на КАЖДОМ
  уровне вызова.
- 15-й откат посреди раунда (4 файла /tmp + reset + возврат).
- v11.74.59 = cdad91a, run 36118920608 SUCCESS, latest, APK
  40 517 078 Б, sha256 6522bca2…a10.
