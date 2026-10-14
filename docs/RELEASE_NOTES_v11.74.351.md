# APU v11.74.351 — звонок будит телефон, плюс сообществ не падает

Входящий звонок зажигает экран даже на блокировке. Плюс на «Сообществах» больше не роняет приложение.

- Входящий звонок: экран загорается, окно поверх ключа, с уведомления можно принять или отклонить.
- Ядро не засыпает через 10 минут: связь держится, пока служба работает.
- Плюс создания группы или канала открывает окно, а не закрывает приложение.

**Ограничение:** если сеть режет брокеры, а мост MQTT отвечает ошибкой, офер звонка до спящего телефона не дойдёт, пока не задеплоен воркер. Правка моста в этом выпуске — в файле воркера; её нужно вставить в Cloudflare отдельно.

## English

**APU v11.74.351 — incoming call wakes the phone; communities plus no longer crashes**

An incoming call turns the screen on even when locked. The plus on Communities no longer closes the app.

- Incoming call: the screen wakes, the UI shows over the lock, and the notification can answer or decline.
- The core no longer drops after ten minutes: the link stays up while the service runs.
- The plus to create a group or channel opens the sheet instead of crashing.

**Limitation:** if the network blocks public brokers and the MQTT bridge returns an error, a call offer will not reach a sleeping phone until the worker is redeployed. The bridge fix in this release lives in the worker file and must be pasted into Cloudflare separately.

[Скачать APK](https://github.com/vzhem/APUMIR/releases/download/v11.74.351/app-release.apk) · [SHA-256](https://github.com/vzhem/APUMIR/releases/download/v11.74.351/SHA256SUMS.txt)
