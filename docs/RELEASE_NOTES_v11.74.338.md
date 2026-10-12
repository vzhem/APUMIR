# APU v11.74.338 — диагностика маршрута через телефоны-помощники

Отчёт «Логи» дополнен обезличенной сводкой маршрута обычных сообщений через online-helper.

- Видны число выбранных helper-слотов, запуск primary и reserve, результат записи в helper-канал и причина старта резерва.
- Отображаются подписанные отказы, выпуск и проверка квитанции получателя, локальный return slot и средняя задержка до проверенной квитанции.
- Согласие телефона принимать transit показывается отдельно от факта использования helper отправителем.
- Диагностика не включает текст сообщений, ключи, адреса или идентификаторы узлов и пакетов.

**Ограничения:** helper-события основаны на ограниченной недавней выборке logcat и относятся к обычным сообщениям; file chunks в эти счётчики не входят. Квитанция подтверждает принятие адресатом, но не устанавливает личность помощника. Реальные тесты на телефонах и разных NAT не проведены; доступность helper-маршрута зависит от сети и доступных устройств.

## English

**APU v11.74.338 — online-helper route diagnostics**

The Logs report adds privacy-safe summaries for ordinary-message online-helper routing.

- It shows the number of selected helper slots, primary and reserve starts, helper-channel write results, and why the reserve started.
- It reports signed declines, recipient-receipt issuance and verification, the local return-leg slot, and average time to a verified receipt.
- Local consent to relay traffic is shown separately from whether a sender used a helper.
- Diagnostics exclude message text, keys, network addresses, and node or packet identifiers.

**Limitations:** helper events come from a bounded recent logcat sample and cover ordinary messages; file chunks are not counted here. A receipt confirms recipient acceptance but does not identify the helper. Real-phone and varied-NAT tests have not been performed; helper availability depends on network conditions and reachable devices.
