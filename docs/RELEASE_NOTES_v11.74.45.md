# APU v11.74.45 — единый ввод в личке; список тем без поля ввода

## Что изменилось

### Личные чаты

MessageInputBar перестроен в формат тем (р.145-146):
- при фокусе скрепка и GIF - в строку НАД полем (Modifier.onFocusChanged
  на BasicTextField, inputState/decorator/contentReceiver не тронуты -
  раунд 42 в целости);
- BasicTextField на всю ширину (weight(1f) убран);
- «Отправить» - TextButton-пузырь fillMaxWidth (clip 18, белый 0.85,
  золотая рамка, padding vertical 8) ПОД полем; серый/золотой по
  состоянию. Старая круглая кнопка-стрелка убрана.

### Список тем группы

Оверлей поля ввода (р.147) обёрнут в `if (!showTopicsList)` - на
экране списка тем поля ввода, «Отправить» и пилюля «↓ Ещё N сообщений»
не показываются: только внутри темы или в группе без тем.

## Технические детали

- ChatDetailScreen: Surface → Column (navigationBarsPadding) с
  if(inputFocused) Row {скрепка; GIF}; BasicTextField
  fillMaxWidth+onFocusChanged; Spacer(6) + send-пузырь.
  Импорт androidx.compose.ui.focus.onFocusChanged.
- GroupChatScreen: обёртка оверлея в if (!showTopicsList) - переменная
  доступна на уровне функции (showTopicsList = hasTopics && !showFeed).
- Грабли: якорь send-блока дважды не сошёлся - в CircularProgressIndicator
  была строка `color = ...`, не попавшая в окно awk; УРОК: при сборке
  якоря из awk-вывода выводить ВЕСЬ блок с запасом (или брать его
  python-ом тем же скриптом). Восьмой откат базы (коммит на 860b47b,
  +730) - тег снят до создания релиза, /tmp-копии, reset, перекоммит
  812ef05 (+42/-41).
- check79 зелёный. v11.74.45 = 812ef05, run 35969113796 SUCCESS,
  latest, APK 40 500 694 Б, sha256 08fc62bd…1ed.
