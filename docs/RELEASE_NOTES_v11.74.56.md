# APU v11.74.56 — «Кому отправить»: участники группы видны и не выбираются

## Что изменилось

Владелец (скрин диалога «Кому отправить» р.158): «нужно чтобы те кто
уже в группе они были серые и отмечены что данный абонент уже в группе».

- ChatListViewModel.groupMemberIdsOnce(groupId): Set<String> nodeId
  участников (groupDao.getMembers).
- ChatListScreen диалог: строка адресата с contactId в participant set:
  галочка ОТОБРАЖАЕТСЯ включённой и disabled (Checkbox enabled=false),
  имя серым (0xFF9AA3AF), справа пометка «уже в группе» (labelSmall);
  Row clickable(enabled=false) - тап не выбирает; счётчик «Выбрано:
  N из 100» и рассылка учитывают только новых.

## Технические детали

- Сопоставление: Chat.contactId == GroupMemberEntity.nodeId.
- 13-й откат посреди раунда (правка VM потеряна, р158 в git цел) -
  fetch+reset, повторное применение обоих патчей. УРОК подтверждён.
- check94 зелёный. v11.74.56 = 5b81df4, run 36097356780 SUCCESS,
  latest, APK 40 517 078 Б, sha256 1d60c41b…4bd.
