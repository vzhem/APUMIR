# APU v11.74.353 — проверяем режим «рой прежде внешних ресурсов»

Общедоступный выпуск для проверки прямой связи и экономии внешнего трафика на реальных устройствах.

- Приложение ищет подтверждённых доступных соседей прежде резервных сетевых каналов; после потери прямой связи может снова обратиться к ним.
- Зашифрованная резервная копия адресной книги запрашивается у соседей и передаётся им с проверкой записи.
- Обновления и короткие ссылки в поддерживаемых сценариях сначала проверяются через прямую связь.
- Появилась история подтверждённых адресов соседей для повторного поиска после смены сети.

**Важно: это экспериментальный режим, не проверенный на нескольких реальных устройствах.** При живом соседе внешний канал может быть закрыт, даже если сосед не знает нужного: возможны пропущенные звонки и сообщения. Зеркало устройств, загрузка APK и часть других запросов пока не гарантируют строгое правило «рой прежде внешнего ресурса». Обновление серверной части устанавливается отдельно и этим APK не выполняется. Не считайте этот выпуск подтверждением нулевого внешнего трафика или гарантированной доставки.

## English

**APU v11.74.353 — trying swarm-first networking**

A public release for testing direct connections and external network usage on real devices.

- The app looks for authenticated reachable peers before opening fallback network channels; it may return to them after a direct connection is lost.
- An encrypted address book backup can be requested from peers and shared with a write acknowledgement.
- Updates and short links are checked over direct connections first where supported.
- Confirmed peer addresses are kept for rediscovery after network changes.

**Important: this experimental mode has not been verified across multiple real devices.** When a peer is connected, external channels may stay closed even if that peer lacks the needed data; calls or messages may be missed. Device mirroring, APK downloads and some other requests do not yet guarantee strict swarm-first behaviour. Server-side updates are deployed separately, not by this APK. Do not assume zero external traffic or guaranteed delivery from this release.

[Скачать APK / Download APK](https://github.com/vzhem/APUMIR/releases/download/v11.74.353/app-release.apk) · [SHA-256](https://github.com/vzhem/APUMIR/releases/download/v11.74.353/SHA256SUMS.txt)
