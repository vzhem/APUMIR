# APU v11.74.47 — лента доезжает до отправленного; бейдж непрочитанных личных чатов

## Что изменилось

### Автопрокрут при отправке (темы + комментарии каналов)

В ленте тем/каналов появился эффект: если последнее сообщение - своё,
лента доехала до него (animateScrollToItem с offset=1 при moreComments>0).
Раньше отправленное оставалось за полупрозрачным оверлеем поля (р.147) -
владелец прислал скрин с «ку» за полем.

### Бейдж непрочитанных личных чатов

Причина: saveIncomingMessage обновлял только lastMessage - счётчик
не считался (у групп/каналов считался - их путём). Фикс:
- ChatDao.incrementUnread: UPDATE chats SET unreadCount = unreadCount + 1;
- ChatRepository.saveIncomingMessage: + runCatching { incrementUnread };
- insertReceivedGifRefMessage: тоже (гиф-карточки = сообщения).
Открытый экран всё равно обнуляет (markAsRead) - конфликта нет.

## Технические детали

- GroupChatScreen: LaunchedEffect(size, last.id) после feedRemaining
  (на уровне функции - BoxScope не нужен, вызов до root Box).
- Грабли (9-й откат): python-фикс упал на якоре (в файле литеральные
  Kotlin-эскейпы \ud83d\uddbc, python-строка не совпала), но НИЖЕ по
  цепочке struct_check/git прошлись (между heredoc-блоками не было
  &&) - коммит +579 лёг на устаревшую базу, push отклонён. УРОК:
  после каждого heredoc-питона - явная проверка exit-кода, цепочку
  не продолжать при падении. Recovery: /tmp-копии, fetch+reset,
  перекоммит 447d625 (+17). Гиф-якорь найден через find+repr.
- check81 зелёный. v11.74.47 = 447d625, run 35978017655 SUCCESS,
  latest, APK 40 500 694 Б, sha256 fadfd7a1…c73.
