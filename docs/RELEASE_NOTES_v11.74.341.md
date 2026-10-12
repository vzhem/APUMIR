# APU v11.74.341 — яснее причины проблем связи и обновления

Отчёт «Логи» показывает больше причин сбоев, сохраняя приватность переписки.

- Для online-helper отдельно видны причины недоступности маршрута: не готово ядро, не найден локальный ключ узла или ключ получателя. Отсутствие подходящих помощников считается отдельно.
- Для очереди своих сообщений показывается накопленное с запуска ядра число вытеснений из ограниченного RAM-кэша. Вместо отдельного предупреждения на каждое вытеснение пишется сводка; само вытеснение не доказывает потерю сообщения.
- Диагностика обновлений показывает источник и повод проверки, ответы GitHub/relay и HTTP-статусы, а также результат последней попытки уведомления и разрешения Android/канала. Достоверный ответ «обновлений нет» завершает проверку без второго запроса; relay остаётся fallback при ошибке, некорректном ответе или отсутствии поддерживаемого APK. Ссылки на загрузку и текст релиза в этот отчёт не включаются.
- В ограниченной выборке журнала процесса для ошибок и фатальных ошибок резервируется место, чтобы их не вытесняли повторяющиеся предупреждения. Перед выводом скрываются тела payload, идентификаторы, IP-адреса, абсолютные пути и значения полей file/URI.

**Ограничения:** helper-счётчики покрывают только обычные сообщения в ограниченной недавней выборке; file chunks считаются отдельно, а вытеснение из RAM само по себе не доказывает потерю. Временные отказы STUN/MQTT и особенности NAT могут по-прежнему мешать связи; реальные тесты на телефонах не проводились.

## English

**APU v11.74.341 — clearer connection and update diagnostics**

The Logs report now gives more useful failure context while protecting message privacy.

- Online-helper diagnostics distinguish an unavailable core runtime, missing local identity, and missing recipient identity. Having no eligible helper is counted separately.
- The report shows a cumulative count of own-message evictions from the bounded RAM cache since the core started. These are summarized instead of logging one warning per eviction; an eviction alone does not prove message loss.
- Update diagnostics show the check source and trigger, GitHub/relay outcomes and HTTP statuses, plus the latest notification attempt and Android permission/channel state. An authoritative “up to date” response ends the check without a second request; relay remains the fallback when GitHub fails, returns malformed data, or has no supported APK asset. Download URLs and release notes are not included in this report.
- A bounded process-log sample reserves space for error and fatal lines so repeated warnings cannot crowd them out. Payload bodies, identifiers, IP addresses, absolute paths, and values in file/URI fields are redacted before display.

**Limitations:** helper counts cover ordinary messages in a bounded recent log sample; file chunks are counted separately, and RAM eviction alone does not prove data loss. Temporary STUN/MQTT refusals and NAT conditions can still affect connectivity; real-phone testing has not been performed.
