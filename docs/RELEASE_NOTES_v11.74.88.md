# APU v11.74.88 — каталог гиф переживает недоступный сервер

## Что изменилось (раунд 192)

Владелец (после диагноза р191 - лимит Cloudflare FREE у relay-воркера):
«давай делай первый шаг чтобы меньше зависить от лимита». Шаг 1 плана -
кэш каталога гиф.

### Новое: GifSearchCache (data/gif/GifSearchCache.kt)

Локальный файл noBackupFilesDir/gif/search_cache.v1 (MAGIC APUGIFSRCH1):
по записи-строке JSON на запрос {q, at, next, items[{id,preview,gif}]},
до 24 запросов x 24 гифки, атомарная запись tmp+rename. save() - при
успехе первой страницы поиска; load() - при недоступном сервере.
org.json (без зависимостей), Dispatchers.IO, ошибки молча в лог.

### Проводка (3 экрана: личка, группы, «Избранное»)

- ChatDetail/GroupChat/Saved ViewModel: searchGifs - успех !more в кэш;
  при result == null: cached -> показать сохранённое с мягкой плашкой
  gifNotice «Сервер перегружен - показываю сохранённые гифки. Скачать и
  отправить можно как обычно» (gifNext=""); more-фейл -> «Ещё пока
  недоступно» с сохранением сетки; иначе прежняя ошибка. Успех сбрасывает
  notice; closeGifCatalog сбрасывает.
- UiState +gifNotice; GifCatalogDialog/GifCatalogBody/InputPanelDialog
  +param notice (default null); плашка - onSurfaceVariant над сеткой.
- Превью/скачивание байт идут напрямую на CDN Giphy (мимо воркера) -
  поиск, просмотр, скачивание и отправка работают при упёртом лимите.

НЕ тронуто: ядро, протокол (новых конвертов нет), MQTT, тайминги.

## Данные

- check145 success (12m13s), пробник удалён. v11.74.88 = 099cadc,
  run 36341494806, Latest, APK 40 598 678 Б, sha256 f7ba8d9b…9e5.
- Грабли: глитчи записи edit_file (правка «success», но не в файле) -
  ловить grep-маркером ПОСЛЕ каждой правки; python-патчи надёжнее на
  GifCatalogDialog (edit_file трижды «Context not found» на существующем
  анкоре).
