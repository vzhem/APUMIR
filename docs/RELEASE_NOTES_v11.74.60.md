# APU v11.74.60 — «уже в канале»; Избранное: пузыри + стикеры

## Что изменилось

Владелец (2 скрина):
- в «Кому отправить» канала метка «уже в группе» -> «уже в канале»;
- «Добавить в избранное» - красивые пузыри в нашем стиле + добавить
  стикеры.

1. ChatListScreen: метка inGroup = if (grp.isChannel) «уже в канале»
   else «уже в группе» (р159-логика без изменений).
2. SavedScreen меню «Добавить в избранное»: пять TextButton -> золотые
   SavedAddBubble (primary заливка, White Bold, clip 18, vertical 10)
   друг под другом + НОВЫЙ пункт «Стикеры».
3. Стикеры: диалог-сетка LazyVerticalGrid 3 колонки (пузырьки 86dp,
   золотая рамка, AsyncImage файла стикера); тап -> SavedViewModel
   .addSticker: saveLocalFile(fileName «стикер_<sha8>.webp»,
   mediaType image/webp, storageRef «local:stk:<sha>», sourceTitle
   «Стикеры»); localFileOf: ветка local:stk: -> StickerLibrary.fileOf
   (новый метод: File(root, sha + ".img")); stickersOnce для списка
   (all() по свежести). Сообщение-тост через uiState.message.
   Превью/шер работают через существующий localFileOf путь; картинка
   декодится как bitmap (webp - ок).

## Технические детали

- check101 FAILURE: LazyVerticalGrid/GridCells - в пакете
  androidx.compose.foundation.lazy.grid, а не lazy; check102 success.
- v11.74.60 = c7101bd, run 36124738791 SUCCESS, latest, APK
  40 517 206 Б, sha256 9cf79e80…3fe.
