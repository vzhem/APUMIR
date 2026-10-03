# APU v11.74.46 — клавиатура не прячет ввод в личных сообщениях

## Что изменилось

Панель набора личных чатов поднимается над клавиатурой (imePadding):
поле ввода, пузырь «Отправить» и пилюля «↓ Ещё N сообщений» всегда
видны при наборе. Причина: приложение работает edge-to-edge
(enableEdgeToEdge) - adjustResize при этом окна не сжимает, нужен
явный imePadding (темам сделан в р.147, личке - теперь).

## Технические детали

- ChatDetailScreen.MessageInputBar: Surface modifier
  `modifier.fillMaxWidth().imePadding()`; импорт не нужен -
  foundation.layout импортирован wildcard.
- check80 зелёный. v11.74.46 = 09168ae, run 35971276501 SUCCESS,
  latest, APK 40 500 694 Б, sha256 abd274dc…64e.
