# APU v11.74.41 — вход в тему на первом непрочитанном

## Что изменилось

- Открытие темы (в том числе из уведомления) показывает ПЕРВОЕ
  непрочитанное сообщение, ниже - остальные непрочитанные. Раньше лента
  всегда открывалась в верхней части переписки.
- Пузырёк «↓ Ещё N сообщений» под лентой: сколько осталось до конца;
  нажатие прокручивает вниз. Дочитали - исчез.
- Нет непрочитанных - тема открывается снизу, на свежих сообщениях.
- Работает в темах групп и комментариях каналов; личные чаты - без
  изменений.

## Технические детали

- GroupRepository.peekTopicUnread(topicId) - счётчик непрочитанных ДО
  сброса (markRead).
- GroupChatViewModel: FeedJump(topicId, index, unread) + feedJump
  StateFlow + consumeFeedJump(); в observeMessages - однократный захват
  на первом выпуске ленты (jumpCaptured), index = size - unread
  (clamped), сброс jump-состояния при каждой смене темы.
- GroupChatScreen: feedListState у ленты; LaunchedEffect(selectedTopicId,
  messages.size) - scrollToItem(offset + index); пилюля feedRemaining
  (derivedStateOf по layoutInfo ленты, keys по messages.size/moreComments),
  тап - animateScrollToItem(lastIndex); offset = 1 при «Показать ещё N»
  (more-comments item) учтён в обоих индексах.
- Шестой откат базы (HEAD->860b47b): /tmp-копии, fetch refspec, reset
  --hard origin/ветка (61862cd), перекоммит bbf2050 (+85/-0 ровно).
  check73 зелёный.
- v11.74.41 = bbf2050, run 35955092063 SUCCESS, latest,
  APK 40 500 046 Б, sha256 6a3f2707…e05.
