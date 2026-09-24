# APU v11.74.50 — фикс групп без тем; перевод в группу с темами

## Что изменилось

### Группа без тем не писала (владелец: «не отправляется ничего»)

Цепочка причин: у группы с topicsEnabled=false строк тем в базе НЕТ
(createGroup их не создаёт) ->
1) VM: observeTopics прилетает пустым -> selectedTopicId=null ->
   observeMessages не стартует (лента пуста);
2) send(): `selectedTopicId ?: return` - молча, без ошибки;
3) репозиторий: resolveTopic -> getGeneralTopic ?: firstOrNull() ->
   null -> «Тема не найдена».

Фикс: GroupRepository.ensureFlatTopic(groupId) - материализует General
с ДЕТЕРМИНИРОВАННЫМ id (`<groupId>:general`): каждый телефон создаёт
одинаковую строку у себя - без рассылки и расхождений.
GroupChatViewModel.init: ждёт загрузки group (до 10x300мс), если
!topicsEnabled - ensure + selectTopic(id) (лента+закрепы стартуют).
resolveTopic теперь находит General -> отправка работает у всех.
Мессджи конвертации идут через обычный путь sendMessage (фанаут, счётчики).

### Перевод «без тем» -> «с темами» (настройки группы)

GroupRepository.enableTopics: право canChangeInfo; setTopicsEnabled
(DAO UPDATE groups SET topicsEnabled=1); если тем нет - General
(случайный id) + broadcast buildTopicCreated (как createTopic); если
General уже есть (плоский) - просто флаг, история остаётся.
UI: GroupAdminScreen OverviewTab - пузырь-переключатель «Темы в группе»
(!isChannel && !topicsEnabled), односторонний. У участников: прилетает
TopicCreated -> темы [General] -> авто-выбор -> пишут; их UI останется
«плоским» до синка метаданных (функционально не мешает).

## Технические детали

- GroupDao.setTopicsEnabled; GroupAdminViewModel.enableTopics (notice
  «Темы включены»).
- check84 зелёный. v11.74.50 = 8cddbba, run 35994856497 SUCCESS,
  latest, APK 40 500 690 Б, sha256 9c721d11…e7a.
