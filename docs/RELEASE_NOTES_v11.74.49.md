# APU v11.74.49 — «Отправить» загорается сразу

## Что изменилось

Активная «Отправить» (есть текст или файл) - золотая заливка
colorScheme.primary и белая жирная надпись; неактивная - прежний
полупрозрачный пузырь с серым текстом. Раньше менялся только оттенок
текста (primary на серый) - владелец не видел, что сообщение уже
можно отправить («кнопка долго не появляется»).

В личке найдена и настоящая задержка активации: enabled читал text
из родителя (круг: inputState -> snapshotFlow -> onTextChange ->
recomposition). Теперь canSend = inputState.text.isNotBlank()
напрямую - мгновенно.

## Технические детали

- GroupChatScreen: val canSend = (draft.isNotBlank() || stagedFile !=
  null) && !sending && !isPreparingFile; background/border/color по
  canSend; FontWeight.Bold. Тот же стиль в ChatDetailScreen.
- check83 зелёный. v11.74.49 = 4d129f8, run 35990034737 SUCCESS,
  latest, APK 40 500 694 Б, sha256 69d5e96a…b69.
