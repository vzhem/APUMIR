# Карта документации APU

Этот файл — точка входа в `docs/`. В каталоге лежит несколько сотен файлов, и без карты
новый человек (или ИИ) читает устаревшую передачу контекста и принимает её за текущее
состояние. Правило простое: **состояние проекта читается из трекера, всё остальное —
справка или история.**

## С чего начинать

| Документ | Что это | Свежесть |
|---|---|---|
| [`APP_DELIVERY_TRACKER.md`](APP_DELIVERY_TRACKER.md) | **Единственный источник истины о состоянии**: что сделано, что ждёт проверки, какой выпуск стабильный | обновляется каждым релизом |
| [`AUDIT_2026-10-26.md`](AUDIT_2026-10-26.md) | Полный аудит кода и приложения: находки P0–P2 и что из них закрыто | 2026-10-26 |
| [`RELEASE_PUBLICATION_POLICY.md`](RELEASE_PUBLICATION_POLICY.md) | Что можно и нельзя писать в публичном описании выпуска | постоянно |
| [`RELEASE_LINEAGE_SAFETY.md`](RELEASE_LINEAGE_SAFETY.md) | Как проверяется линия релизов и восстановление | постоянно |
| [`PHONE_TEST_CHECKLIST.md`](PHONE_TEST_CHECKLIST.md) | Что проверять на реальных телефонах перед выпуском | по мере надобности |

`AGENTS.md` в корне репозитория задаёт правила работы (назначенная ветка, обновление
трекера, запрет публикации без разрешения владельца).

## Как устроен каталог

- **`RELEASE_NOTES_v*.md`** — по файлу на каждый выпуск. Путь `docs/RELEASE_NOTES_<тег>.md`
  зашит в `.github/workflows/build-release.yml` (`body_path`), поэтому файлы **не
  переносятся и не переименовываются**: переезд ломает публикацию.
- **`RELEASE_AUDIT_v*.md`** — разборы отдельных выпусков.
- **`*_HANDOFF.md`, `START_HERE.md`, `NEXT_AI_CHAT_BOOTSTRAP.md`, `AI_COLLABORATION_NOTES.md`** —
  передачи контекста между сессиями. Это **история**: в них зафиксирован срез на дату
  (там свой старый «текущий релиз»). Текущее состояние — только в трекере.
- Остальные файлы — справка по подсистемам (сеть, зеркало, файлы, диагностика, игровые
  механики, работа с ПК).

## Справка по подсистемам

| Документ | Тема |
|---|---|
| [`ADDRESS_BOOK.md`](ADDRESS_BOOK.md) | Азбука адресов |
| [`AUTOMATIC_STORAGE_MAINTENANCE.md`](AUTOMATIC_STORAGE_MAINTENANCE.md) | Автоматическое обслуживание хранилищ |
| [`BACKUP_AND_NEW_PC.md`](BACKUP_AND_NEW_PC.md) | Резервная копия, флешка и новый ПК |
| [`BACKUP_AND_CLEAN_PC_RECOVERY.md`](BACKUP_AND_CLEAN_PC_RECOVERY.md) | Резервная копия и запуск на чистом Windows-ПК |
| [`CALLS_BOOTSTRAP.md`](CALLS_BOOTSTRAP.md) | Звонки между абонентами |
| [`CHANNEL_SWARM_DESIGN.md`](CHANNEL_SWARM_DESIGN.md) | Раздача постов канала роем |
| [`CHAT_OPENING.md`](CHAT_OPENING.md) | Открытие личного чата без ожидания сети |
| [`CONVERSATION_WALLPAPERS.md`](CONVERSATION_WALLPAPERS.md) | Фон отдельного чата |
| [`CORE_ROADMAP.md`](CORE_ROADMAP.md) | План развития ядра |
| [`DUAL_STACK_IPV6.md`](DUAL_STACK_IPV6.md) | Dual-stack: IPv6 и IPv4 в прямом канале |
| [`FLASH_BACKUP_RUNBOOK.md`](FLASH_BACKUP_RUNBOOK.md) | Памятка по копии на флешку |
| [`GROUP_QUIET_HOURS.md`](GROUP_QUIET_HOURS.md) | Расписание запрета сообщений в группе |
| [`HEART_GAME.md`](HEART_GAME.md) | Игровые сердечки рейтинга |
| [`IDENTITY_SIGNING_MIGRATION.md`](IDENTITY_SIGNING_MIGRATION.md) | Переход на подпись Ed25519 |
| [`IDENTITY_VAULT_SETUP.md`](IDENTITY_VAULT_SETUP.md) | Хранилище личности в Cloudflare |
| [`MASTER_PLAN_v2.md`](MASTER_PLAN_v2.md) | Мастер-план проекта |
| [`MESH_DELIVERY.md`](MESH_DELIVERY.md) | Mesh-доставка store-and-forward |
| [`MESSAGE_PIN_LIMIT_NOTICE.md`](MESSAGE_PIN_LIMIT_NOTICE.md) | Предупреждение о лимите закрепов |
| [`MIRROR_FULL.md`](MIRROR_FULL.md) | Полная синхронизация устройств |
| [`MIRROR_SYNC.md`](MIRROR_SYNC.md) | Живое зеркало устройств одной личности |
| [`MQTT_HIBERNATION.md`](MQTT_HIBERNATION.md) | Гибернация MQTT-моста |
| [`MQTT_MULTI_BROKER_DESIGN.md`](MQTT_MULTI_BROKER_DESIGN.md) | Multi-broker MQTT overlay |
| [`MULTI_DEVICE_IDENTITY.md`](MULTI_DEVICE_IDENTITY.md) | Один аккаунт на нескольких устройствах |
| [`OFFLINE_DELIVERY.md`](OFFLINE_DELIVERY.md) | Офлайн-доставка сообщений и файлов |
| [`OPERATIONAL_DIAGNOSTICS_AND_STORIES.md`](OPERATIONAL_DIAGNOSTICS_AND_STORIES.md) | Операционные метрики и досылка историй |
| [`PC_TRANSFER.md`](PC_TRANSFER.md) | Перенос работы на другой ПК |
| [`PEER_EXCHANGE.md`](PEER_EXCHANGE.md) | Прямой поиск через участников |
| [`PROFILE_SETTINGS_STYLE.md`](PROFILE_SETTINGS_STYLE.md) | Оформление профиля и настроек |
| [`RELAY_STORAGE_DIAGNOSTICS.md`](RELAY_STORAGE_DIAGNOSTICS.md) | Диагностика relay-хранилища |
| [`RELAY_STORAGE_FORENSICS.md`](RELAY_STORAGE_FORENSICS.md) | Разбор большого relay-хранилища |
| [`RESTORE_ON_NEW_PC.md`](RESTORE_ON_NEW_PC.md) | Продолжение работы на новом ПК |
| [`RUNTIME_DIAGNOSTICS.md`](RUNTIME_DIAGNOSTICS.md) | Расширенная диагностика |
| [`SCHEDULED_PREDELIVERY.md`](SCHEDULED_PREDELIVERY.md) | Предварительная доставка и отложенный показ |
| [`SCHEDULED_SEND_DATE_TIME.md`](SCHEDULED_SEND_DATE_TIME.md) | Произвольная дата и время отправки |
| [`SECURE_FILE_TRANSFER.md`](SECURE_FILE_TRANSFER.md) | Безопасная передача файлов |
| [`SECURITY_RESILIENCE_TEST_PLAN.md`](SECURITY_RESILIENCE_TEST_PLAN.md) | План проверок против спама и DoS |
| [`SIGNING_KEY_ROTATION.md`](SIGNING_KEY_ROTATION.md) | Смена ключа подписи APK |
| [`SPLASH_DIGITAL_CORE.md`](SPLASH_DIGITAL_CORE.md) | Заставка цифрового ядра |
| [`STORY_EDITOR_MULTILINE.md`](STORY_EDITOR_MULTILINE.md) | Многострочный редактор историй |
| [`STORY_REACTIONS_AND_COMMENTS.md`](STORY_REACTIONS_AND_COMMENTS.md) | Реакции и комментарии на историях |
| [`SWARM_EXTERNAL_FALLBACK.md`](SWARM_EXTERNAL_FALLBACK.md) | Рой прежде внешнего ресурса |
| [`SWARM_HELPER_ROADMAP.md`](SWARM_HELPER_ROADMAP.md) | Телефоны-помощники |
| [`SWARM_MOBILE.md`](SWARM_MOBILE.md) | Рой через мобильную связь |
| [`UPDATE_SEEDING.md`](UPDATE_SEEDING.md) | Раздача обновления роем |
| [`VERSION_STATISTICS.md`](VERSION_STATISTICS.md) | Статистика версий у пользователей |
| [`VIDEO_STORIES_PLAN.md`](VIDEO_STORIES_PLAN.md) | Видеоистории |
| [`WALLPAPER_ICONS.md`](WALLPAPER_ICONS.md) | Значки фирменных обоев |
| [`DIAGNOSTICS_REVIEW_2026-10-16.md`](DIAGNOSTICS_REVIEW_2026-10-16.md) | Разбор отчёта `apu-diag/5` от 16.10.2026 |
| [`M8_E_F_PREP.md`](M8_E_F_PREP.md) | Подготовительный план M8-E / M8-F |
| [`NEW_DEVELOPMENT_PC.md`](NEW_DEVELOPMENT_PC.md) | Настройка нового компьютера для разработки |

## История и передачи контекста

Эти документы **не описывают текущее состояние** — в них срез на дату. Читать только
как хронологию решений.

| Документ | Чем полезен |
|---|---|
| [`AI_HANDOFF.md`](AI_HANDOFF.md) | Правила владельца, тестовая топология, техническая история |
| [`AI_COLLABORATION_NOTES.md`](AI_COLLABORATION_NOTES.md) | Уроки прошлых сессий («гэтчи», на которые уже наступали) |
| [`HANDOFF_NEXT_AI.md`](HANDOFF_NEXT_AI.md) | Передача от 2026-10-08 |
| [`START_HERE.md`](START_HERE.md) | Вход для нового чата, срез 2026-10-08 |
| [`NEXT_AI_CHAT_BOOTSTRAP.md`](NEXT_AI_CHAT_BOOTSTRAP.md) | Короткая заготовка промпта |
| [`AGENT_HANDOFF.md`](AGENT_HANDOFF.md) | Передача по релизу 2026-10-12 |
| [`HANDOFF_LANGUAGE_SECTION.md`](HANDOFF_LANGUAGE_SECTION.md) | Передача по разделу «Язык», 2026-10-10 |

## Правила каталога

1. **Новый документ — строка в этой карте.** Проверяется скриптом
   `scripts/ci/check-docs-index.py` (падение CI, если файл не упомянут).
2. **Выпуски не переезжают.** `docs/RELEASE_NOTES_<тег>.md` — фиксированный путь для
   `build-release.yml`.
3. **Состояние — только в трекере.** Любой документ, кроме `APP_DELIVERY_TRACKER.md`,
   не имеет права называть себя источником текущего статуса.
4. **Ссылки — относительные**, чтобы работать и на GitHub, и в локальном просмотрщике.
