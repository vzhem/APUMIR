# APU v11.74.354 — устойчивее очередь при плохой связи

Общедоступное обновление для проверки доставки сообщений и работы роя на реальных устройствах.

- Новые личные сообщения не уходят через резервный канал открытым текстом, если ключ получателя недоступен: отправка откладывается до восстановления ключа.
- Диагностика отдельно показывает подготовку ключей до запуска ядра и число подтверждённых прямых соседей, не смешивая их с обычным сетевым подключением.
- Раздача APK ждёт прямого канала вместо накопления частей файла в очереди сообщений; повторы обычных файловых фрагментов не создают новые записи с разными ID.
- Файловые и служебные пакеты в очереди отделены от переписки и имеют короткий срок ожидания.

**Известное ограничение:** успешная доставка на разных устройствах и сетях пока не подтверждена. При живом соседе внешний канал может оставаться закрытым, даже если доставить сообщение через него не удалось. Старые записи не превращаются автоматически в новые отправки. Серверная часть обновляется отдельно.

## English

**APU v11.74.354 — a more resilient queue on unreliable connections**

A public update for testing message delivery and swarm routing on real devices.

- New private messages wait for the recipient's encryption key rather than being sent unencrypted over a fallback route.
- Diagnostics distinguish keys available before core startup from later checks and distinguish authenticated direct peers from ordinary network connections.
- APK seeding waits for a direct path rather than accumulating file parts in the message queue; retries of ordinary file fragments reuse stable IDs.
- File and control packets have a separate, short-lived queue allowance instead of displacing correspondence.

**Known limitation:** delivery across different devices and networks has not yet been confirmed. A live peer can keep external fallback closed even when delivery fails. Old queued items are not automatically converted into new sends. Server-side changes are deployed separately.

[Скачать APK / Download APK](https://github.com/vzhem/APUMIR/releases/download/v11.74.354/app-release.apk) · [SHA-256](https://github.com/vzhem/APUMIR/releases/download/v11.74.354/SHA256SUMS.txt)
