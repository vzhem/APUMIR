# APU v11.74.62 — свои стикеры в панели всех чатов; честные подписи стикеров

## Что изменилось

Владелец (2 скрина): «добавить .zip и другие форматы - в личных чатах,
группах и каналах через кнопку гиф и дальше по меню; стикеры в группе
и каналах отображаются как ссылки, в уведомлениях тоже ссылка».

1. InputPanelDialog (панель Эмодзи/Гиф/Стикеры - общая для лички,
   групп и комментариев каналов): StickerSection + вторая строка
   «+ Добавить альбом стикеров (.zip)» (pickStickerZip, GetContent */*)
   рядом с прежней «Добавить стикер из телефона (картинка)»;
   новый колбэк onAddStickerZip -> вызовы в ChatDetailScreen/
   GroupChatScreen.
2. ChatDetailViewModel.addStickerZip / GroupChatViewModel.addStickerZip:
   StickerLibrary.addZip(uri) + refreshStickers (сетка обновится).
3. StickerLibrary.addZip: общая логика импорта архива (webp/png/jpg/
   jpeg/gif/bmp, webm скип, дедуп по sha; добавлено/всего) - перенесена
   из SavedViewModel (теперь делегирует).
4. GroupChatViewModel.sendSticker: визитка с displayName «Стикер.webp»
   (info.copy) - карточка в ленте и подпись «📎 Стикер.webp (N КБ)»
   вместо sha-строки (screеn владельца с «edee151f…»).
5. ChatPreviews.human: визитка с именем «Стикер…» -> «🖼 Стикер» -
   уведомления (CoreServerService) и все списки.

## Технические детали

- check105 зелёный с первого прогона. APK подрос (zip-логика в библиотеке).
- v11.74.62 = a4fc3de, run 36135176330 SUCCESS, latest, APK
  40 533 590 Б, sha256 8ed9a0aa…31a.
