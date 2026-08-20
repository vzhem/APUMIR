# Политика публичного описания релизов APU

Публичное описание каждого GitHub Release должно быть коротким, понятным пользователю и очищенным
от конфиденциальной/внутренней информации. Публикация release/tag/PR выполняется только после нового
явного разрешения владельца проекта.

## Формат

1. Одно короткое предложение: что улучшилось для пользователя.
2. От трёх до шести главных пунктов.
3. При необходимости — одно короткое «Известное ограничение» без внутренних подробностей.
4. Ссылка на APK и отдельный checksum asset. Полный SHA-256 допустим в checksum asset; в основном
   тексте он нужен только когда это помогает безопасной ручной установке.

## Никогда не публиковать

- private endpoints/domains до отдельного разрешения;
- credentials, tokens, passwords, signing/recovery material и внутренние URL;
- телефонные serial/UID/PID, имена владельцев тестовых устройств и точную test topology;
- полные node IDs, message IDs, referral tokens, identity bindings, private DB paths/dumps;
- `%TEMP%` paths, локальные Windows usernames/workspace paths;
- branch/session names, commit recovery mechanics, harness internals и evidence filenames;
- внутренние state/log hashes, если они не являются публичным APK checksum;
- подробности защит, которые дают готовую инструкцию обхода до исправления;
- длинный инженерный журнал, stack traces и не относящиеся к пользователю эксперименты.

## Разделение материалов

- **Public release notes:** только главная пользовательская польза и существенные исправления.
- **Internal engineering evidence:** тестовые состояния, phone gates, hashes, recovery и ограничения;
  не копировать автоматически в GitHub Release.
- **Security advisory:** отдельный controlled disclosure, если действительно требуется.

Перед публикацией выполнить отдельный redaction review по этому списку и ещё раз показать владельцу
точный будущий публичный текст. По умолчанию лучше написать меньше, чем раскрыть лишнее.
