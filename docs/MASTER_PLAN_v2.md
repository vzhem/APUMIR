# 📋 МАСТЕР-ПЛАН v2.0
# APUMIR / P2P Messenger — Telegram-like UX без зависимости от центральных серверов

---

## 🤖 ДЛЯ СЛЕДУЮЩЕЙ СЕССИИ ИИ — ПРОЧТИ ПЕРВЫМ

> Полный живой документ (его поддерживает сам ИИ): [`AI_COLLABORATION_NOTES.md`](AI_COLLABORATION_NOTES.md).
> Ниже — только критичные правила. **ИИ обязан читать и поддерживать оба места без напоминания пользователя**, дописывая новое после каждой сессии.

- Пошагово, маленькими шагами: шаг → проверка → следующий шаг. Без подтверждения — никаких больших пачек задач.
- Команды для Windows-машины — Windows PowerShell, каждый блок начинается с `Set-Location`. Python — `py -3`.
- Путь к локальному клону репозитория заранее НЕ известен — подтверждать у пользователя, не предполагать `C:\APUMIR\android-app`.
- Перед публикацией/тегами/релизом — всегда отдельно спрашивать подтверждение. PR не мержить без явного разрешения.
- Правки одного файла — строго по одной с проверкой (параллельные правки одного файла ломают файл).
- Android/Rust в sandbox агента не собираются — код пишется диффами, сборка/тест на машине пользователя.
- После каждой существенно важной проверенной сборки ИИ сам предлагает milestone-backup APU
  на внешний носитель с Git history, APK, hashes и clean-PC recovery guide; общая процедура:
  [`BACKUP_AND_CLEAN_PC_RECOVERY.md`](BACKUP_AND_CLEAN_PC_RECOVERY.md). Для флешки authoritative
  hard procedure — [`FLASH_BACKUP_RUNBOOK.md`](FLASH_BACKUP_RUNBOOK.md): compact portable allowlist,
  previous+latest; принятую APU-флешку больше никогда не форматировать; никогда не blind-copy
  caches/full workspace/evidence.
- Spam/DoS/replay и другие defensive tests повторять по важным сборкам; high load только на
  собственной локальной инфраструктуре: [`SECURITY_RESILIENCE_TEST_PLAN.md`](SECURITY_RESILIENCE_TEST_PLAN.md).
- Каждую ошибку инструмента и безопасный workaround сразу записывать в collaboration notes и
  тематическую инструкцию: симптом, причина, точный обход, проверка и запреты; не оставлять
  полезное решение только в чате.
- Для каждой крупной published/prerelease версии обновлять короткий отчёт и LOC delta в
  [`VERSION_STATISTICS.md`](VERSION_STATISTICS.md); старые записи не переписывать.
- При передаче работы в новый чат использовать готовый authoritative prompt
  [`NEXT_AI_CHAT_BOOTSTRAP.md`](NEXT_AI_CHAT_BOOTSTRAP.md); пользователь не должен заново объяснять
  историю, запреты и следующий M8-шаг.

---

## 0. Назначение документа

Этот документ фиксирует новый практический roadmap APUMIR после публикации `v11.16.3`.

Старый мастер-план v1.0 остаётся архитектурной базой: Rust core, Kotlin/Compose, QUIC, DHT, mDNS, ICE/STUN, relay tiers, E2E encryption, store-and-forward, CRDT.

v2.0 добавляет практический путь развития приложения до уровня мессенджера, близкого по удобству к Telegram, но с главным отличием:

> **APUMIR должен уметь работать без внешних серверов и ресурсов.**
>
> Внешние ресурсы допускаются только как вспомогательные bootstrap/fallback-каналы: помочь найти абонента, передать invite, узнать адрес, ускорить доставку или обновление. Они не должны быть единственной точкой отказа и не должны иметь доступа к содержимому сообщений.

---

## 1. Текущая точка проекта

### Последний опубликованный релиз

- Version: `v11.16.4`
- Source commit: `991da05ec13eab11867f2b201b5c063966a61bbf`
- Release: `https://github.com/vzhem/APUMIR/releases/tag/v11.16.4`
- Проверенный APK: `P2P-Messenger-v11.16.4.apk`
- APK SHA-256: `444C255A14DEC2DACF3701BE624465BC358ABE231076B21E8E9A1EE26F3F4F55`
- Checksum (`.apk.sha256`) SHA-256: `6D62E247F8A133B152B1A4E5FB1A3796131D50420EE0D0FF5D9044202FAAE70F`
- Provenance JSON SHA-256: `0D90A8DA5D93B4F7C50D7CDDF9C17F54F4ED46747403D2013437AF3FB8C7A567`
- Установлен поверх `v11.16.3` через `adb install -r` на всех трёх тестовых устройствах.

### Проверенные устройства

- `11567254BK001192`
- `3B665800EES00000`
- `AUYF6R5923006121`

---

## 2. Главная цель продукта

Создать Android-мессенджер мирового уровня со структурой и удобством, близкими к Telegram:

- личные чаты;
- контакты;
- группы;
- темы внутри групп;
- каналы;
- голосовые сообщения;
- звонки;
- видеозвонки;
- файлы и медиа;
- реакции;
- ответы;
- редактирование;
- закрепы;
- поиск;
- уведомления;
- простая отправка приглашения другу;
- простая установка приложения по ссылке;
- простое добавление контакта после установки.

Но архитектурно APUMIR должен оставаться независимым:

- сообщения и ключи хранятся на устройствах пользователей;
- содержимое сообщений E2E-зашифровано;
- relay/bootstrap-сервисы не читают сообщения;
- приложение должно иметь fallback-сценарии без Cloudflare, Telegram, GitHub, MQTT и других внешних ресурсов;
- внешний ресурс — помощник, а не хозяин сети.

---

## 3. Принципы независимости

### 3.1. Основной режим

Основной режим должен работать через собственную P2P-сеть:

1. mDNS / LAN discovery.
2. Прямое QUIC-соединение.
3. ICE/STUN NAT traversal.
4. DHT Kademlia для поиска узлов.
5. Seed nodes для bootstrap.
6. Tier 1 / Tier 2 relay nodes.
7. Store-and-forward через доверенные P2P relay nodes.

### 3.2. Вспомогательные внешние ресурсы

Допускаются как временные или резервные каналы:

- Cloudflare Worker registry/relay;
- MQTT public brokers;
- Telegram bot/deep links;
- GitHub Releases для обновлений;
- web landing page для установки;
- STUN-серверы;
- в будущем: Nostr/Matrix/ActivityPub relays как encrypted envelope transport.

Ограничения:

- не хранить plaintext сообщений;
- не хранить приватные ключи;
- не становиться единственным способом работы;
- иметь P2P-замену в roadmap;
- быть отключаемыми.

---

# 🔴 ПРИОРИТЕТ 0 — Release, документация, безопасность процесса

## Фаза 0.1 — Release process hardening

Проблема v11.16.3: создание GitHub Release создало tag и запустило workflow, который пересобрал APK и временно добавил лишний asset `app-release.apk`.

Задачи:

- [ ] Переделать `.github/workflows/build-release.yml`, чтобы он не перезаписывал уже созданный Release.
- [ ] Добавить manual release mode.
- [ ] Добавить публикацию checksum и provenance.
- [ ] Привести пользовательское имя APK к формату `APU-vX.Y.Z.apk`.
- [ ] Создать `docs/RELEASE_PROCESS.md`.
- [ ] Создать checklist ручной проверки перед релизом.
- [ ] Создать rollback plan.
- [ ] На флешке/внешнем backup всегда держать минимум две проверенные APK: `previous` и `latest`.
  `LATEST.txt`/`LATEST.json` однозначно называют последнюю версию, содержат размеры, SHA-256 и
  отпечаток Android signer certificate; APK обеих версий сохраняют проверенную цифровую подпись.
- [ ] Ввести security/resilience gate по
  [`SECURITY_RESILIENCE_TEST_PLAN.md`](SECURITY_RESILIENCE_TEST_PLAN.md): короткий abuse smoke
  для каждой важной APK, полный controlled regression/local load для release candidate.
- [ ] Архивировать результаты: version/commit/devices, counters, resource baseline, найденные
  дефекты и успешный retest; после нагрузки обязательно доставить обычное control message.

Критерий:

- Следующий релиз публикуется без cleanup и без риска подмены проверенного APK.
- Нет известных непроверенных blocker по spam/DoS/replay; нагрузочные тесты не проводятся на
  публичных/чужих сервисах, а только в своей локальной лаборатории.

## Фаза 0.2 — Документация текущей архитектуры

Задачи:

- [ ] `docs/CURRENT_ARCHITECTURE.md`
- [ ] `docs/TESTING_MATRIX.md`
- [ ] `docs/TELEGRAM_AND_REGISTRY.md`
- [ ] `docs/THREAT_MODEL.md`
- [ ] `docs/NETWORK_CASCADE.md`

## Фаза 0.3 — Единый бренд APU, логотип и современное оформление

**Решение пользователя:** во всём пользовательском интерфейсе продукт называется только
**APU**. `APUMIR`, `P2P Messenger` и похожие имена — исторические/технические названия папок,
репозитория, package/classes и legacy links; они не должны показываться пользователю. Не
переименовывать технические пути одним большим опасным изменением.

- [ ] Провести аудит всех видимых строк: launcher label, onboarding, профиль/share, settings,
  notifications, dialogs, APK/update texts, invite pages и accessibility descriptions.
- [ ] Ввести единый строковый ресурс имени `APU`, исключить hardcoded старые названия из UI.
- [ ] Исправить регистрацию/onboarding: убрать персональный пример `Например: Владимир`;
  нейтральная подпись поля — `Имя или имя и фамилия`, placeholder — `Как к вам обращаться`.
  Пояснить, что это публичное неуникальное display name, а уникальный `@username` задаётся отдельно.
- [ ] Новые APK/releases называть `APU-vX.Y.Z`; GitHub repo/локальные папки могут быть APUMIR.
- [ ] Для новых links/сайта использовать бренд APU, сохранив чтение старых `p2pmessenger://`,
  `.apumir` и legacy invite links ради обратной совместимости.
- [ ] Разработать оригинальный современный логотип APU, отражающий P2P, приватность и связь,
  но не копирующий Telegram или другие продукты.
- [ ] Подготовить vector source (SVG), Android adaptive icon foreground/background, monochrome
  themed icon, launcher icon, notification icon, splash и безопасные поля для малых размеров.
  Каноническое место исходника/экспортов: `design/branding/app-icon/README.md`; пользователь выбрал
  neon speech-bubble + P2P mesh artwork 2026-08-15. Original Windows-verified: 1664×928,
  1,980,451 B, SHA-256 `F2638C88…83ACA9`; binary Git transfer pending, provenance записан. Затем
  square master/adaptive/round/monochrome exports. Не менять текущие
  Android mipmaps посреди r4.4 mixed acceptance: интеграция иконки требует новой APK и полного
  artifact/upgrade/launcher regression gate.
- [ ] Проверить узнаваемость на 16/24/48 px, светлом/тёмном фоне и в grayscale; закрепить права
  и исходники, чтобы логотип можно было законно использовать в релизах.
- [ ] Создать UI design system: цветовые tokens, typography, spacing, shapes, icons, motion,
  light/dark/high-contrast themes и требования accessibility.
- [ ] Последовательно оформить onboarding, список чатов, чат, contacts/search, profile,
  settings, media/stories и системные состояния loading/empty/error/offline.
- [ ] Сделать кликабельный макет и проверить основные сценарии на телефонах до массового
  переписывания Compose UI.

Критерий: пользователь везде видит только APU; приложение имеет единый оригинальный adaptive
logo и согласованный современный интерфейс в light/dark themes, а старые технические имена
остаются только внутри совместимых путей/кода и не требуют опасного переименования репозитория.

## Фаза 0.4 — Периодические defensive abuse-тесты

- [ ] Поддерживать матрицу spam/DoS/replay/parser/Sybil/Android/media/privacy/update tests.
- [ ] Делить тесты на: unit/fuzz без сети; короткий 3-phone smoke; высокий load/soak только с
  локальным broker/mock и stop conditions; независимый review перед production security claim.
- [ ] Не атаковать публичный HiveMQ, GitHub, Cloudflare или чужие endpoints: там допустимы только
  малые функциональные пробы своими test IDs.
- [ ] Измерять crash/ANR/PID, duplicate UI, queues/caches, CPU/RAM/network/disk/battery/temperature,
  reconnect/backoff и обязательное восстановление обычной доставки после теста.
- [ ] Каждый найденный дефект закреплять regression test, исправлять и повторно проверять на той
  же APK-матрице. Полная процедура — `docs/SECURITY_RESILIENCE_TEST_PLAN.md`.

Критерий: важная сборка не только выполняет новый сценарий, но и сохраняет hard limits,
приватность и нормальную доставку после согласованной безопасной нагрузки.

## Фаза 0.5 — Совместимость разных версий APU и постепенное обновление сети

**Обязательное решение пользователя (2026-08-15):** APU должен продолжать работать, когда у
пользователей установлены разные версии. Нельзя предполагать, что все телефоны обновятся
одновременно; rolling update (постепенное обновление) не должен разделять mesh или молча терять
сообщения.

- [ ] Каждый новый wire/envelope/storage-facing формат получает явную protocol/schema version и
  documented compatibility window; неизвестные поля безопасно игнорируются, неизвестный
  обязательный type/version отклоняется явно без panic, enqueue, receipt или UI-события.
- [ ] До отправки нового несовместимого формата использовать capability negotiation либо
  backward-compatible/dual encoding; один новый телефон не должен заставлять старые узлы хранить
  или пересылать непонятный payload как успешную доставку.
- [ ] Сохранять совместимость ключевых сценариев между текущей и предыдущей поддерживаемой
  стабильной версией: discovery/presence, direct message, relay/gossip, receipt/cleanup,
  dedup/replay protection и reconnect. Более широкое окно N-2 задавать отдельно после измерений,
  а не обещать без теста.
- [ ] Для каждого protocol/MQTT/mesh change выполнять mixed-version matrix минимум на трёх
  телефонах: N→N, N→N-1, N-1→N и relay третьим телефоном другой версии; проверять ровно одно UI
  message, receipt cleanup, отсутствие storm/split-brain и сохранность bounded limits.
- [ ] Старую небезопасную версию не поддерживать бесконечно: minimum supported version и причина
  прекращения поддержки должны быть явными; несовместимый клиент получает понятное
  `upgrade required`, а не ложное SENT/DELIVERED или тихую потерю.
- [ ] Android upgrade сохраняет user data/identity/keys через настоящие migrations; destructive
  migration и uninstall запрещены. Downgrade, если он небезопасен для новой схемы, блокируется
  явно и документируется.
- [ ] UpdateChecker regression: установленная старая stable-версия получает уведомление о новом
  GitHub Latest, корректно сравнивает `versionCode/versionName`, показывает release notes/download,
  не молчит из-за draft/prerelease/cache/network error и не предлагает update debug-сборке с более
  высоким внутренним versionCode. Проверять на реальном опубликованном N-1 APK.

Критерий: во время постепенного обновления поддерживаемые разные версии остаются в одной сети и
доставляют сообщения/receipts без дублей и потери cleanup; несовместимость определяется явно и
безопасно, а mixed-version test matrix входит в release gate.

## Фаза 0.6 — Глобальный инвариант доступности: соединение при NAT, блокировках и белых списках

**Обязательное решение пользователя (2026-08-15):** когда абоненты пытаются связаться напрямую,
APU должен автоматически использовать все доступные, законные и явно разрешённые пользователем
способы установления канала. Жёсткий NAT, блокировка UDP/TCP/DNS, мобильная фильтрация, captive
portal и сеть «разрешены только белые домены» не должны приводить к молчаливой потере сообщений.
Содержимое всегда E2E-зашифровано; промежуточный proxy/VPN/relay не становится хранилищем.

**Честное техническое ограничение:** если сеть не разрешает вообще ни одного общего адреса/канала,
создать интернет-соединение физически невозможно. Для strict whitelist нужен хотя бы один заранее
разрешённый endpoint: пользовательский/корпоративный proxy или VPN, APU bridge/relay на разрешённом
домене, либо локальный Wi-Fi Direct/Bluetooth путь. В этом случае APU показывает `restricted/no
reachable transport`, сохраняет Outbox и продолжает bounded retry — никогда не сообщает ложный
`SENT`/`DELIVERED`.

Обязательная transport matrix, управляемая единым `TransportManager`:

1. **Прямые пути:** QUIC/UDP; TCP; TLS 1.3 на 443; WebSocket Secure; HTTP/2 и HTTP/3; IPv4/IPv6,
   NAT64 и Happy Eyeballs; ICE/STUN, WebRTC data channel и TURN только как realtime transit.
2. **NAT traversal:** hole punching, port mapping там, где это безопасно, несколько ICE candidates,
   signed peer endpoints и одновременные bounded попытки без connection storm.
3. **Пользовательские proxy:** SOCKS5, HTTP CONNECT, HTTPS proxy, системный proxy; импорт QR/file и
   подписанные private subscription lists. Не скачивать случайные public proxy pools и не передавать
   им plaintext/ключи.
4. **VPN/tunnel с явным согласием:** уважать уже активный системный VPN; optional Android
   `VpnService` только для APU, WireGuard-compatible tunnel, MASQUE `CONNECT-UDP/CONNECT-IP` и
   split tunneling. Никакого скрытого VPN или обхода системного consent dialog.
5. **APU bridges:** пользовательские seed/relay nodes, собственный домен на 443, CDN/edge или
   enterprise gateway, если правила провайдера это допускают. Bridge передаёт E2E bytes в реальном
   времени и не хранит message inbox; endpoint manifests подписаны и ротируются.
6. **Phone mesh:** друзья/друзья друзей как Tier relay, store-and-forward на телефонах, gossip,
   Wi-Fi Direct, Nearby, Bluetooth, LAN/mDNS; интернет отправителя после handoff не обязателен.
7. **Устойчивый bootstrap:** обычный DNS + DoH/DoT, signed bootstrap IP/domain manifest, QR/invite
   file/NFC/Bluetooth discovery. DNS fallback не отключает проверку подписи и TLS identity.
8. **Pluggable transports:** TLS/WebSocket wrapping, padding и obfs-like framing как отдельные
   opt-in модули. Domain fronting — только там, где это законно и явно поддержано владельцем CDN;
   не полагаться на запрещённое или нестабильное поведение третьих сторон.
9. **Restricted-network policy:** Normal → UDP-blocked → TCP/TLS-only → proxy/VPN/bridge → local
   mesh. Несколько кандидатов можно пробовать параллельно с bounded budgets; запрещено старое
   последовательное переключение всего mesh на независимый broker без общего пересечения.
10. **Совместимость:** transport/capability negotiation versioned; N и N-1 выбирают общий путь.
    Неизвестный обязательный transport format безопасно отклоняется без enqueue/UI/ACK.

Безопасность и UX:

- [ ] E2E до входа в любой transit; proxy/VPN/relay не получает private identity keys.
- [ ] Явные настройки `Авто / Только прямое / Restricted / Offline mesh` и понятный индикатор пути.
- [ ] Пользователь видит причину fallback, текущий endpoint class и честный degraded status, но не
  секреты, полный NodeId, plaintext или proxy credentials в обычных логах.
- [ ] Credentials хранятся в Android Keystore; manifests/updates подписаны; защита от malicious
  proxy, downgrade, MITM, replay, DNS poisoning и captive-portal impersonation.
- [ ] Circuit breaker, jitter, global/per-endpoint rate limits, battery/thermal/data quotas и
  cancellation проигравших Happy-Eyeballs попыток.
- [ ] Ограниченные сети тестируются локально: UDP drop, DNS block, TCP-only, proxy auth, IPv6-only,
  captive portal, whitelist simulator, endpoint outage/recovery. Не атаковать публичные сети.
- [ ] После каждого fallback обычное сообщение, receipt/cleanup и offline relay проходят ровно один
  раз; нет split-brain, duplicate UI, ложного SENT или потери Outbox при смене транспорта.

Критерий: при наличии хотя бы одного разрешённого общего канала APU автоматически находит его и
восстанавливает связь без действий пользователя; при полном отсутствии пути честно остаётся
офлайн, хранит сообщение на телефонах и продолжает безопасный retry.

---

# 🔴 ПРИОРИТЕТ 1 — Лёгкое приглашение друга, установка и добавление контакта

Это ключевой пользовательский сценарий.

## Цель

Пользователь APUMIR должен нажать одну кнопку:

> **Пригласить друга**

И отправить другу ссылку любым способом: Telegram, WhatsApp, SMS, email, QR, Nearby Share, Bluetooth, файл, локальная сеть.

Друг должен:

1. открыть ссылку;
2. если приложение не установлено — легко установить APK;
3. после установки — автоматически открыть invite;
4. увидеть профиль пригласившего;
5. нажать “Добавить контакт”;
6. сразу начать переписку.

## Фаза 1.1 — Invite Kit v1

Создать единый формат invite-пакета.

Invite должен содержать:

- `node_id`;
- `public_key` или fingerprint;
- `display_name`;
- `avatar_hash` или placeholder;
- `created_at`;
- `expires_at` опционально;
- `app_min_version`;
- `recommended_apk_version`;
- `apk_download_urls`;
- `bootstrap_hints`;
- опциональный `referral_token` — подписанный/opaque токен прямого приглашения без списка контактов;
- подпись invite владельцем ключа.

`referral_token` не равен `node_id` и не должен превращаться в постоянный публичный идентификатор
для слежения. Минимальные поля: версия, inviter binding, случайный nonce, created/expiry, scope
`direct_friend`, подпись. Link можно отозвать/перевыпустить; обычный invite продолжает работать без
referral attribution.

Пример логической структуры:

```json
{
  "type": "apumir_invite",
  "version": 1,
  "node_id": "pk_...",
  "display_name": "Vladimir",
  "app_min_version": "v11.16.4",
  "apk": {
    "version": "v11.16.4",
    "sha256": "...",
    "urls": [
      "https://github.com/vzhem/APUMIR/releases/latest",
      "https://p2p-relay.example/install/latest.apk"
    ]
  },
  "bootstrap": {
    "dht": true,
    "mdns": true,
    "registry_url": "https://p2p-relay.1985vzhem.workers.dev"
  },
  "signature": "..."
}
```

## Фаза 1.2 — Invite links

Поддержать несколько типов ссылок:

### 1. Внутренняя ссылка приложения

```text
p2pmessenger://add?node_id=pk_...
```

Назначение: если приложение уже установлено, сразу открыть экран добавления контакта.

### 2. HTTPS app/install link

```text
https://apumir.app/i/<invite_id>
```

или временно:

```text
https://p2p-relay.1985vzhem.workers.dev/i/<invite_id>
```

Назначение:

- если приложение установлено — открыть APUMIR;
- если не установлено — показать страницу установки APK;
- после установки — дать пользователю открыть invite снова.

### 3. Telegram link

```text
https://t.me/p2p_messenger_relay_bot?start=<invite_id_or_node_id>
```

Назначение:

- удобная пересылка через Telegram;
- не основной транспорт;
- не обязательный внешний ресурс;
- fallback для пользователей Telegram.

### 4. QR invite

QR должен кодировать либо короткую ссылку, либо компактный invite bundle.

Назначение:

- добавление контакта без интернета;
- демонстрация QR на одном телефоне и сканирование другим;
- offline bootstrap.

### 5. Offline invite file

Файл:

```text
APUMIR-invite-<name>.apumir
```

Назначение:

- отправить через Bluetooth;
- отправить через Nearby Share;
- отправить через флешку;
- приложить к email;
- передать без серверов.

## Фаза 1.3 — Экран “Пригласить друга”

Добавить полноценный экран:

```text
InviteFriendScreen
```

Функции:

- [ ] Показать имя пользователя и fingerprint.
- [ ] Показать QR-код.
- [ ] Кнопка “Поделиться ссылкой”.
- [ ] Кнопка “Поделиться APK + приглашение”.
- [ ] Кнопка “Скопировать ссылку”.
- [ ] Кнопка “Скопировать Telegram-ссылку”.
- [ ] Кнопка “Сохранить invite-файл”.
- [ ] Кнопка “Показать APK для передачи рядом”.
- [ ] Показать SHA-256 APK.
- [ ] Пояснить другу: “Установи APK, затем открой эту ссылку”.

## Фаза 1.4 — Установка приложения другом

Сценарии:

### Если APUMIR установлен

- Android открывает deep link.
- APUMIR показывает карточку контакта.
- Пользователь нажимает “Добавить”.

### Если APUMIR не установлен и есть интернет

- Открывается install landing page.
- Пользователь скачивает APK.
- APK проверяется по SHA-256.
- После установки пользователь возвращается к invite.

### Если APUMIR не установлен и интернета нет

- Приглашающий отправляет APK напрямую:
  - Nearby Share;
  - Bluetooth;
  - Wi-Fi Direct;
  - USB;
  - локальная сеть;
  - QR-серия для маленьких bootstrap данных.
- После установки друг импортирует invite file или сканирует QR.

## Фаза 1.5 — Исправить текущие Telegram/deep link баги

Текущее несоответствие:

- генератор использует `p2p_messenger_relay_bot`;
- обработчик ждёт `P2PMessengerBot`;
- генератор даёт `start=<nodeId>`;
- обработчик ждёт `start=add_<nodeId>`.

Задачи:

- [ ] Вынести парсер в `InviteLinkParser.kt`.
- [ ] Поддержать `p2p_messenger_relay_bot`.
- [ ] Поддержать legacy `P2PMessengerBot`.
- [ ] Поддержать `start=<nodeId>`.
- [ ] Поддержать `start=add_<nodeId>`.
- [ ] Поддержать `p2pmessenger://add?node_id=...`.
- [ ] Поддержать `p2pmessenger://add?nodeId=...`.
- [ ] Добавить Kotlin unit tests.

## Фаза 1.6 — Registry lookup для красивого добавления контакта

При открытии invite:

- [ ] Сначала читать данные из signed invite bundle.
- [ ] Если данных не хватает — делать lookup в registry.
- [ ] Если registry недоступен — добавлять по nodeId/fingerprint.
- [ ] Показывать имя пригласившего.
- [ ] Проверять подпись invite, если она есть.

## Фаза 1.7 — Уникальный `@username` и глобальный поиск

Отображаемые имена могут совпадать и не являются идентификатором. Для поиска пользователя:

- [ ] Добавить отдельный `@username` только из латинских букв, цифр и `_`.
- [ ] Нормализовать регистр: `@Alice` и `@alice` — одно имя.
- [ ] Гарантировать глобальную уникальность `@username`.
- [ ] Привязать `@username` к неизменяемому `nodeId`/public key (`pk_…`) подписанной записью.
- [ ] Глобальный поиск выполнять по точному `@username`, а не по display name.
- [ ] Display name оставить свободным и неуникальным.
- [ ] Добавить rate limits, opt-in discoverability и защиту от массового перебора.
- [ ] Периодически тестировать enumeration, case variants, rename churn, squatting, forged
  PK binding и Sybil-регистрации в собственной test registry; не сканировать чужие сервисы.
- [ ] Продумать rename/recovery/cooldown и защиту от захвата известных имён.
- [ ] Registry использовать как первый discovery-слой; долгосрочно проверить DHT/распределённый
  механизм уникальности. При недоступности lookup сохранять добавление по signed invite/PK.

Критерий: два пользователя с одинаковым display name не конфликтуют; точный `@username`
однозначно возвращает подписанную привязку к одному `pk_…`, которую клиент проверяет до
добавления контакта.

## Критерий завершения Приоритета 1

На чистом телефоне без APUMIR пользователь может получить приглашение, установить APK, открыть invite, добавить пригласившего и начать чат.

---

# 🔴 ПРИОРИТЕТ 2 — Надёжная доставка сообщений и сетевой каскад

## Фаза 2.1 — Зафиксировать фактический каскад v11.16.x

Текущий практический каскад примерно такой:

1. Rust core send.
2. MQTT fallback.
3. Cloudflare relay fallback.
4. Delayed Cloudflare duplicate, если MQTT не подтверждён.
5. TelegramRelay существует, но не интегрирован end-to-end.

Задачи:

- [ ] Описать текущий state machine доставки.
- [ ] Ввести явные каналы:
  - `DIRECT_QUIC`
  - `LAN_MDNS`
  - `MQTT`
  - `CLOUDFLARE`
  - `TELEGRAM_EXPERIMENTAL`
  - `P2P_RELAY`
  - `STORE_FORWARD`
  - `UNKNOWN`
- [ ] Ввести статусы:
  - `PENDING`
  - `SENT_TO_TRANSPORT`
  - `DELIVERED`
  - `READ`
  - `FAILED`
  - `QUEUED_OFFLINE`
- [ ] Сделать единый envelope сообщений.
- [ ] Сделать единый deduplication по `message_id`.
- [ ] Сделать единый ACK/receipt.
- [ ] Показывать канал доставки в debug mode.

## Фаза 2.2 — Cloudflare relay как временный store-and-forward

- [ ] Worker хранит encrypted payload для offline node.
- [ ] Polling забирает пачку сообщений.
- [ ] Client отправляет ACK.
- [ ] Worker удаляет доставленное.
- [ ] TTL сообщений.
- [ ] Ограничение размера inbox.
- [ ] Rate limit.
- [ ] Abuse protection.

## Фаза 2.3 — P2P store-and-forward

- [x] M3(d) source + APK compile: app offline send → origin RelayQueue → bounded persistent MQTT
  mesh command; backward-compatible N-1 wire, truthful `QUEUED_OFFLINE`, Room retry. Rust feature
  build и Kotlin overlay PASS; signed APK v11.16.16/11016016 собран 2026-08-15: 22,664,712 B,
  SHA-256 `446A1EE9…429DC0D`, embedded arm64 `.so` 7,263,416 B / `27B9D4DC…D1FD26C`, V2 signer
  preserved. Data-preserving install PASS 3/3; controlled launch/readiness PASS 3/3 with stable
  PIDs, primary+secondary READY, dual fanout/dedup, healthy heartbeat and zero crash/stall/restart.
  **Automatic three-phone offline UI delivery/cleanup/DELIVERED acceptance pending**; mixed N↔N-1
  и r4.5 остаются release gates.
- [ ] Перенести store-and-forward на Tier 1/Tier 2 nodes.
- [ ] Relay nodes хранят только E2E encrypted payload.
- [ ] **M8 hard requirement — persistent relay custody:** encrypted RelayQueue переживает Android
  process death, reboot, app update и длительный сон; absolute `created/expires` timestamps, hop,
  origin/recipient/chat scope и dedup tombstones восстанавливаются без продления TTL и дублей.
- [ ] Background relay sleep/wake cycle: перед сном телефон атомарно сохраняет encrypted custody;
  после network/app/periodic wake получает bounded foreground window, сначала восстанавливает старую
  очередь, затем обменивается summary и запрашивает доступные новые relay items в пределах consent,
  TTL/hop/quota/traffic budgets. Недоставленные старые и новые items снова durable сохраняются до
  следующего wake без busy-loop и потери; обычный receive-only mode чужие сообщения не принимает.
- [ ] Обязательный delayed multi-carrier gate: Анна→офлайн-Стас, Женя хранит; все offline; через
  день Женя пересекается online с новым relay D и передаёт ему; Женя снова offline/process killed;
  затем D пересекается со Стасом и доставляет ровно одно UI message. Receipt очищает D/Женю/Анну
  при их следующих появлениях и eventually ставит Анне DELIVERED. Проверить также reboot Жени/D,
  TTL 7 дней, hop≤8, quotas и отсутствие общего одновременного online-окна origin↔recipient.
- [ ] Если ни один custody-holder не пережил процесс/reboot или не было разрешённого общего окна,
  показывать честный Outbox/restricted status; не обещать физически невозможную доставку.
- [ ] Пользователь может разрешить устройству быть relay.
- [ ] Ограничения по батарее, Wi-Fi, зарядке, трафику.
- [ ] Per-origin/per-recipient/global quotas, fair friend-priority queues, bounded replay cache,
  token buckets для relay/receipt/presence/gossip и ранний drop oversized/malformed payload.
- [ ] Regression matrix: duplicate/conflicting origin, replay after cleanup/reconnect, queue caps,
  TTL/hop boundaries, receipt/presence/summary flood и network-flap storm.
- [ ] Высокий load и Sybil simulation выполнять только через локальный broker/mock; после теста
  контрольное обычное сообщение обязано доставиться без restart/очистки данных.

## Фаза 2.4 — Network diagnostics

Экран диагностики должен показывать:

- node id;
- fingerprint;
- current transport;
- connected peers;
- MQTT broker;
- Cloudflare availability;
- Telegram bot/link status;
- DHT status;
- mDNS status;
- NAT type;
- relay candidates;
- последние network events;
- export logs;
- безопасные counters `accepted/dropped/rate-limited` по типу envelope и причине drop;
- текущие/максимальные размеры bounded queues/caches, reconnect/backoff и circuit-breaker state;
- локальный resource baseline CPU/RAM/network/storage/battery/temperature для abuse test без
  plaintext сообщений, точных контактов и private identifiers.

### Удалённая отправка диагностических отчётов

- [ ] Кнопка `Отправить отчёт об ошибке` и отдельный opt-in для автоматических crash reports;
  никаких скрытых uploads по умолчанию.
- [ ] Настраиваемый project-controlled endpoint/provider (например, self-hosted collector или
  выбранный issue/crash tracker); адрес и ключи не hardcode-ить в APK и не отправлять «ИИ» напрямую.
- [ ] В отчёт включать: версию APU, Android/device model, timestamp, stack trace, тип ошибки,
  bounded recent logs и безопасный network state.
- [ ] Перед отправкой автоматически удалять plaintext сообщений, private keys/tokens, точные
  контакты, username, IP/SSID и другие персональные данные; node ID — только salted hash.
- [ ] Показать пользователю preview/redaction и получить явное подтверждение перед manual send.
- [ ] Шифрование отчёта публичным support key + HTTPS, checksum, report ID и статус доставки.
- [ ] Жёсткие лимиты размера/частоты, dedup одинаковых crashes, backoff, Wi-Fi-only option и
  bounded offline queue; после успешной отправки локальный пакет удалять.
- [ ] Локальный export в зашифрованный ZIP/JSON для ручного прикрепления к задаче или передачи
  разработчику; пользователь может удалить все pending/sent reports.
- [ ] Retention policy и удаление на collector; доступ только владельцу проекта. Не включать
  telemetry/analytics сверх необходимой диагностики без отдельного согласия.
- [ ] ИИ анализирует только явно предоставленный пользователем export либо доступный проектный
  issue/report; приложение не должно иметь прямой «секретный канал» к конкретной ИИ-сессии.

Критерий: пользователь может безопасно прислать воспроизводимый crash/error report туда, где
команда проекта сможет его обработать, не раскрывая переписку, ключи и личные данные.

---

# 🚀 ГЛОБАЛЬНЫЙ ЭТАП 2.5 — красивый APU, публичный запуск и быстрый рост сети

**Обязательное решение пользователя (2026-08-15):** в плане должна быть отдельная большая точка,
после которой APU уже красивый, удобный и достаточно надёжный, чтобы его быстро распространять
тысячам и десяткам тысяч людей. Продукт должен давать понятную пользу, вызывать желание остаться и
естественно делиться APU с друзьями. Не ждать завершения всего многолетнего backlog: часть групп,
каналов, stories, звонков, desktop и других расширений можно выпускать после запуска.

Это не обещание автоматически получить десятки тысяч пользователей. Это управляемая стратегия
**product-led growth**: сам хороший продукт, простой invite и полезный сетевой эффект приводят новых
людей, а каждая волна расширяется только после измеримого качества предыдущей.

## Фаза 2.5.1 — Launch Readiness Gate: когда массовое распространение разрешено

Этап запуска расположен после core invite/delivery hardening, но начинается не просто по номеру
версии. До первой широкой волны должны одновременно пройти обязательные ворота:

### Красота и удобство

- [ ] Во всём пользовательском UI название только **APU**, оригинальная иконка и единый modern
  design system; качественные light/dark themes, понятные empty/error/offline states.
- [ ] Onboarding без технического жаргона: имя → `@username`/invite → первый контакт → первое
  сообщение. Новый человек понимает основную пользу APU за первый экран.
- [ ] Базовые сценарии проверены на маленьких/больших экранах, разных Android, плохой сети,
  accessibility font scale и screen reader; нет blocker по crash/ANR/нечитаемому UI.
- [ ] Первое полезное действие занимает минуты, а не часы: получить ссылку/QR, безопасно установить,
  добавить друга и отправить первое сообщение без ручного копирования `pk_…`.

### Польза, ради которой люди остаются

- [ ] Надёжный 1:1 чат, контакты, уведомления и честные статусы Outbox/SENT/DELIVERED.
- [ ] Главная отличительная функция доказана на реальных телефонах: online/offline доставка через
  phone relay, sender может уйти offline, recipient получает ровно одно сообщение, receipt делает
  cleanup; reconnect и rolling N↔N-1 не разделяют сеть.
- [ ] Минимальный удобный media slice для обычного общения: хотя бы фото, файл и voice message либо
  явно выбранный меньший набор без потери/дублей. Полный media backlog можно продолжать после launch.
- [ ] Для волны около 10 000 нужен хотя бы один сильный социальный loop: Groups MVP либо другой
  проверенный сценарий, где существующий пользователь приглашает нескольких друзей ради общей
  пользы, а не только ради рекламной награды.

### Безопасность, данные и обновления — откладывать нельзя

- [ ] Ни MQTT, ни proxy/relay/diagnostics не получают plaintext или private keys; E2E и identity
  verification проходят отдельный audit. Нельзя массово рекламировать приватность до этого gate.
- [ ] `adb install -r`/обычное обновление сохраняет chats, identity и keys; нет destructive Room
  migration, downgrade confusion или silent account reset.
- [ ] Reproducible release procedure: один подписанный APK, version/provenance/SHA-256, проверенный
  update channel, rollback и минимум два доступных download route без подмены signer.
- [ ] Low-volume defensive smoke и local abuse/load matrix закрывают replay/spam/malformed input,
  queue/storage exhaustion, reconnect storm и recovery обычным control message.
- [ ] Privacy policy, permissions explanation, export/delete data и законные правила distribution
  готовы до public listing; никакой скрытой telemetry, VPN или загрузки contacts/messages.

### Масштаб сети — отдельный hard gate

- [ ] Текущий prototype `p2pm2/#` all-to-all presence/wildcard нельзя просто открыть десяткам тысяч
  телефонов. До массовой волны нужны bounded neighbours, own/target topics, sharding/DHT или другая
  измеренная схема, чтобы один пользователь не получал presence/traffic всей сети.
- [ ] На собственной local infrastructure пройти ступенчатые simulation/load tests для 100, 1 000,
  10 000 и целевого диапазона concurrent nodes: messages/receipts/gossip, reconnect wave, offline
  queues, broker/bridge outage и recovery. Не создавать load на public HiveMQ/чужих endpoints.
- [ ] Зафиксировать capacity budget: active sessions, messages/sec, egress, storage, CPU/RAM,
  battery/data на телефоне, p95 delivery latency, duplicate/drop/rate-limit counters и стоимость.
- [ ] Circuit breakers, quotas, backoff+jitter, signed endpoint rotation и аварийный restricted/
  Outbox mode не допускают storm или ложный DELIVERED при перегрузке.

**Решение gate:** массовая кампания начинается только после signed Launch Readiness manifest с
точной APK/commit, пройденными пунктами, known limitations и rollback. Наличие красивой иконки без
надёжности не считается готовностью; наличие transport prototype без понятного UX — тоже.

## Фаза 2.5.2 — Встроенный цикл «пригласил → друг получил пользу → поделился дальше»

- [ ] Одна заметная кнопка `Пригласить друга` в подходящих местах, но без навязчивых popup.
- [ ] Share package одним действием: красивая APU-карточка + HTTPS link + QR + при необходимости
  signed APK/invite file; получателю сразу понятно, зачем APU и как проверить установку.
- [ ] Deep link сохраняет invite через установку/onboarding и открывает нужный контакт/группу после
  первого запуска; не заставляет повторно искать отправителя.
- [ ] Короткий сценарий `приглашение → установка → контакт → первое сообщение` измеряется end-to-end
  на чистом телефоне и работает при обычном интернете, restricted network и nearby/offline transfer.
- [ ] После первого успешного сообщения ненавязчиво показать доступные способы пригласить ещё одного
  друга. Базовые text/receive/security функции не блокировать; outbound media — отдельное явно
  показанное rank entitlement по решению пользователя, без влияния на доставку и безопасность.
- [ ] Referral attribution — только минимальный signed/opaque campaign ID, без contacts, message
  content и скрытого social graph. Награды, если появятся, не должны стимулировать spam/fake installs.
- [ ] Group/community invite становится отдельным viral loop после Groups MVP: человек приходит ради
  конкретной беседы/сообщества, а не ради абстрактной установки приложения.
- [ ] Локализация launch flow минимум для выбранных первых рынков; landing/share text автоматически
  использует язык получателя, а не технические англоязычные сообщения.

## Фаза 2.5.2A — реферальные ссылки, статусы и награды за активных друзей

**Идея пользователя (2026-08-19), обработанная в product/security plan:** у каждого пользователя
есть простая персональная ссылка `Пригласить друга`. Первый реально активированный друг сразу даёт
первый статус; дальнейшие прямые приглашения открывают ступени 3, 10, 20, 30, 50, 100, 200, 300,
500, 700 и 1 000. Система должна поощрять полезное общение и рост живой сети, а не spam, fake
installs или финансовую пирамиду.

### Пользовательский сценарий

1. Владелец нажимает `Пригласить друга` и выбирает share/QR/copy/offline-файл.
2. Друг открывает HTTPS App Link. Если APU нет — видит официальный signed download; pending invite
   переживает установку и onboarding.
3. После создания identity APU открывает профиль пригласившего и предлагает добавить его.
4. Referral засчитывается не за клик/скачивание, а после первого успешного encrypted handshake и
   хотя бы одного `DELIVERED` сообщения между этой парой.
5. При первом qualified друге статус `Первый связной` показывается сразу; progress screen объясняет
   следующую ступень и открываемые outbound-media лимиты без навязчивых popup.
6. Для ступеней 10+ referral окончательно подтверждается после D7 activity либо эквивалентного
   anti-fraud gate; до этого UI может показывать `ожидает подтверждения`.

Формат ссылки (логически, конкретный домен определяется Invite Kit):

```text
https://apu.example/i/<invite_id>?r=<opaque_signed_referral_token>
```

Внутри приложения и offline QR/file используется тот же signed referral token. Параметр `r` нельзя
доверять без проверки подписи, expiry, scope и binding к inviter identity.

### Лестница статусов

Названия рабочие и проверяются UX-тестом/локализацией до code freeze. Порог означает количество
**уникальных qualified direct friends**, а не installs и не друзей друзей.

| Друзей | Статус | Отправка media / лимит файла* | Сообщества и автоматизация |
|---:|---|---|---|
| 0 | Без ранга | отправка media закрыта; получение разрешено | можно вступать в группы/каналы; создание закрыто |
| 1 | **Первый связной** | фото до 5 MiB | вступление |
| 3 | **Круг друзей** | фото и произвольные файлы до 10 MiB | вступление |
| 10 | **Проводник** | фото, файлы и видео до 25 MiB | **создание групп** |
| 20 | **Организатор** | до 50 MiB | создание групп + **автосбор/автовыбор прокси** |
| 30 | **Навигатор** | до 100 MiB | создание групп и **создание каналов** |
| 50 | **Амбассадор** | до 250 MiB | создание групп и каналов |
| 100 | **Строитель сообщества** | до 500 MiB | создание групп и каналов |
| 200 | **Хранитель сети** | до 750 MiB | создание групп и каналов |
| 300 | **Маяк APU** | до 1 GiB | создание групп и каналов |
| 500 | **Лидер сообщества** | до 1.5 GiB | создание групп и каналов |
| 700 | **Легенда APU** | до 2 GiB | создание групп и каналов |
| 1 000 | **Создатель сети** | до 4 GiB | создание групп и каналов |

\* Эффективный лимит всегда `min(rank limit, current technical/transport/storage limit)`. Пока F1
технически ограничен 10 MiB: более высокие лимиты не объявляются доступными в UI до streaming,
resume, quota и transport acceptance. MIME неизвестного типа считается обычным файлом, а не фото.

### Что доступно сразу после установки, без ранга

- личные текстовые сообщения и базовые настройки профиля/контактов;
- получение, скачивание и просмотр присланных фото, видео и файлов;
- вступление по приглашению в существующие группы и каналы, чтение и обычное участие по их правилам;
- безопасность, обновления, блокировка/жалоба, privacy controls и одинаковый delivery priority.

Без qualified referrals нельзя начинать новую outbound media-передачу или создавать сообщество.
Создание группы открывается строго с 10 qualified direct referrals (`Проводник`), автоматический
сбор, health-check, выбор и использование прокси — с 20 (`Организатор`), создание канала — с 30
(`Навигатор`). Ручное добавление/удаление прокси можно оставить базовым диагностическим действием,
но background collector и автоматическое применение ниже 20 не запускаются. Проверка должна быть не
только в UI: worker/service/use case/repository обязаны повторно проверять entitlement, чтобы кнопку
нельзя было обойти deep link, старым клиентом или прямым вызовом API.

Для лабораторных debug APK разрешён отдельный test-only override до 1 000 qualified referrals.
Он хранится в отдельном debug preference, выставляется только instrumentation через ADB и полностью
игнорируется release-сборками. Source/app/test APK build PASS. Подключённые известные тестовые телефоны
можно переводить в ранг `Создатель сети` для проверки всех функций; неизвестный serial останавливает
весь gate до изменений. Override не создаёт referral receipts и не попадает в production-счётчик.
Connected-known-phone gate PASS; Settings rank-information screen build PASS and shows с текущим рангом и полной таблицей
media/group/proxy/channel unlocks, чтобы правила были видны пользователю, а не только в документации.

Правила наград:

- [ ] Каждая ступень обязательно даёт понятный статус; набор косметики можно выпускать постепенно.
- [ ] По последнему product-решению outbound фото/файлы/видео и размер одного файла открываются по
  qualified direct-referral rank. Исключение строго ограничено media-send entitlement: текстовые
  сообщения, получение/скачивание media, шифрование, delivery/relay priority, privacy, moderation,
  update и emergency-функции одинаковы для всех. Снижение/пересмотр ранга не блокирует уже принятую
  передачу и не лишает получателя доступа к ранее доставленному файлу.
- [ ] Никаких автоматических денежных обещаний, токенов, multi-level/downline и процентов с друзей.
  Если когда-либо появится коммерческая ambassador-программа — отдельные legal/anti-fraud rules.
- [ ] Пользователь может скрыть badge/count, но не теряет награды; публичная таблица лидеров только
  отдельным opt-in и без node ID/social graph.
- [ ] Accessibility: статус не кодируется только цветом; анимацию можно отключить.

### Что считается qualified referral

- [ ] Invite token валиден, подписан, не истёк и привязан к прямому inviter.
- [ ] Invitee создал новую identity и явно добавил пригласившего; один invitee может зачесть только
  одного inviter, до qualification выбор можно отменить, после — изменение только через support/
  fraud resolution.
- [ ] Между парой есть успешный verified handshake и минимум одно delivered сообщение; содержимое
  сообщения и точный social graph никогда не отправляются в analytics.
- [ ] Self-referral, повторная установка, data clear, клон одной identity, один и тот же signed
  invite receipt и известные emulator/device-farm patterns не увеличивают счётчик.
- [ ] Для high tiers считать подтверждённых D7 active friends; удалённый/заблокированный контакт не
  обязан мгновенно уменьшать честно заработанный статус, но fraud-revocation возможен с audit trail.
- [ ] На MVP только прямые referrals. Друзья друзей влияют на mesh/network benefit, но не на личный
  счётчик — это предотвращает пирамидальную механику.

### Privacy-preserving архитектура

- [ ] Inviter создаёт random nonce/token и подписывает identity key; invitee после qualification
  возвращает signed referral receipt. Receipt содержит version, token hash, inviter binding,
  invitee pseudonymous binding, qualified timestamp и signature — без contacts/messages/IP.
- [ ] Счётчик и receipts хранятся локально encrypted по умолчанию. Для публично проверяемого badge
  можно opt-in отправлять blinded/hashed receipts в project registry с rate limits и retention.
- [ ] Registry не получает address book и не должен уметь восстановить полный social graph. Для
  aggregate funnel — rotating campaign/installation IDs, k-anonymity/threshold reporting и явное
  analytics consent; без permanent cross-app tracking.
- [ ] Multi-device recovery статуса — encrypted export/backup или signed receipt sync; server не
  становится единственной точкой истины. Offline invite должен засчитаться после следующего общего
  online окна.
- [ ] Token rotate/revoke, expiry и replay cache bounded; malformed/oversized token отклоняется до
  crypto/DB-heavy работы. Один receipt idempotent, merge — set/CRDT по receipt hash.

### Abuse, UX и операционные ограничения

- [ ] Rate limit создания/проверки links и qualification receipts; anomaly flags для резких волн,
  но без автоматического наказания честного пользователя только за популярность.
- [ ] Share UI запрещает contact scraping, auto-DM и отправку без явного выбора пользователя.
- [ ] Status screen показывает `засчитано / ожидает / отклонено` и безопасную причину без раскрытия
  anti-fraud секретов; есть appeal/export журнала своих receipts.
- [ ] Referral link ведёт только на официальный allowlist download route и показывает signer/hash;
  никакого APK от inviter или подмены update channel без проверки подписи.
- [ ] Удаление аккаунта/локальных данных удаляет локальные referral receipts; server-side opt-in
  данные подчиняются privacy retention/delete policy.

### Текущий code audit и обязательный crypto blocker

- Текущий `InviteLinkParser` понимает custom `p2pmessenger://`, Rust legacy `p2pm://` и Telegram,
  но ещё не понимает официальный APU HTTPS App Link и не сохраняет pending invite через установку.
- `ShareProfileScreen` заставляет отдельно пересылать contact link, Telegram fallback и APK URL;
  copy/share flow нужно объединить в одну понятную APU-карточку.
- **Hard blocker:** текущий `ffi::CryptoManager.sign/verify` — prototype (`sig_` + hash данных), а
  verify фактически не связывает подпись с public key. Его нельзя использовать для referral token,
  статуса или security claims. До R1 нужно интегрировать реальный Ed25519 `NodeIdentity`/Keystore
  lifecycle, migration и cross-version tests. Legacy unsigned links можно продолжать принимать для
  добавления контакта, но они никогда не дают referral reward.

### Этапы реализации

1. **R0 — UX/spec + existing-flow audit:** названия, progress screen, единая share-card, privacy text,
   threat model и compatibility policy для legacy links.
2. **R0.5 — real identity signing foundation:** Ed25519 sign/verify, durable device-bound private key,
   public-key↔node-id binding, migration/rotation/recovery и запрет placeholder signature в referral.
   Authoritative migration design: `docs/IDENTITY_SIGNING_MIGRATION.md`.
   - [x] Slice 1: pure canonical/domain-separated referral claims + real `Ed25519KeyPair` sign/verify,
     node binding, nonce/time/lifetime bounds и negative tests; без engine/UniFFI/URL wiring.
   - [x] Slice 2: identity audit + device-bound Kotlin seed envelope/Keystore storage; JVM tests,
     Android Keystore roundtrip/tamper/missing-key/zeroization and profile-preservation PASS.
   - [x] Slice 3: Rust signing registry, UniFFI diagnostics and Kotlin install-before-engine;
     one-phone profile/node preservation + stable sidecar restart PASS, private bytes never return.
   - [x] Slice 3B: canonical self-signed TOFU binding, create-once persistence, local matching and
     Anna↔Stas cross-device valid/foreign/tamper gate PASS without identity reset.
   - [ ] Slice 3C: explicit dual-signed key rotation/recovery (не входит в S3B PASS).
3. **R1 — signed direct token:** versioned canonical payload, parser/expiry/revoke/replay tests;
   официальный HTTPS App Link и pending token через install/onboarding.
   - [x] Slice 1: identity-bound binary envelope
     `[v1,binding_len,binding,nonce,created,expires,signature]`; stable legacy routing ID is checked
     against the verified S3B sidecar binding instead of incorrectly deriving it from the new key.
     Production Android Rust + debug APK compile PASS; source negative tests are present, while host
     runtime execution remains blocked by the missing MSVC linker.
   - [x] Slice 2: narrow UniFFI API creates a random-nonce token only from the installed sidecar and
     persisted matching binding; verifier returns an inviter routing ID only after full binding,
     signature and time-window verification. Native/binding/APK PASS; normalized generated binding
     accepted as commit `95270bc`, while `.so` remained uncommitted.
   - [x] Slice 3: strict official HTTPS token codec (`https://apumir.app/i?r=…`) and Kotlin security
     boundary that attributes only after Rust verification. Parser negative JVM tests + APK PASS.
     Domain ownership/`assetlinks.json` remains a deployment gate before the link may be called an
     Android Verified App Link.
   - [x] Slice 4: verified token persistence across onboarding, re-verification on every read, signed
     referral handling before legacy contact-only links, and redacted deep-link logs. Runtime/JVM/APK,
     isolated persist/load/tamper/expiry instrumentation and data-preserving Stas phone gate PASS;
     production profile/node/signing/pending state preserved, no data clear/force-stop.
   - [x] Slice 5: outgoing share flow creates a seven-day random-nonce signed token only from the
     installed sidecar + verified persisted binding, with legacy contact-only fallback. JVM/APK build
     PASS. Public switch remains deliberately OFF until official DNS/HTTPS landing and
     `assetlinks.json` ownership are deployed and verified.
4. **R2 — local qualification:** handshake+DELIVERED → idempotent signed receipt → уровни 1/3/10.
5. **R3 — все ступени и cosmetics:** таблица до 1 000, localization/accessibility, hide controls.
6. **R4 — optional registry verification:** blinded receipts, abuse/rate limits, recovery/export.
7. **R5 — controlled experiment:** 50–100 users; spam complaints, activation, D7 quality и fraud.
   Высшие tiers/публичные badges открывать только после честных метрик и security review.

### Acceptance gate

- [ ] Clean phone открывает referral link → официальный install → возвращается к inviter → первый
  delivered chat; inviter получает уровень 1 без повторного ручного ввода ссылки.
- [ ] Пороги `1/3/10/20/30/50/100/200/300/500/700/1000` проверены boundary tests; событие/restart/
  multi-broker duplicate не удваивает счётчик.
- [ ] Offline QR/file qualification синхронизируется позже; один и тот же receipt после process
  death/reboot остаётся exactly-once.
- [ ] Self/reinstall/replay/expired/forged token не засчитываются; 1 000 synthetic users тестируются
  только локально, без нагрузки на public infrastructure.
- [ ] В packet/log/analytics audit нет contacts, message content, private keys или полного social
  graph; отказ registry не ломает обычные invite/contact/chat.
- [ ] Награда не меняет transport/security priority и не блокирует функции непригласившим.

## Фаза 2.5.3 — Каналы быстрого и законного распространения

- [ ] Быстрый официальный landing: ценность APU, screenshots/video, privacy explanation, platforms,
  signed download, SHA-256, install/update guide, FAQ и статус известных ограничений.
- [ ] Публикация там, где это законно и соответствует правилам площадки: собственный сайт/mirrors,
  GitHub Releases как один из routes, подходящие Android stores/F-Droid после review требований.
- [ ] Offline distribution: Quick Share/Nearby, Bluetooth, Wi-Fi Direct, QR и передача signed APK +
  invite; подпись/хэш проверяются приложением или понятной инструкцией.
- [ ] Набор честных demo materials: 30–60 секунд до первого сообщения, offline relay в действии,
  privacy model простыми словами, сравнение не через ложные обещания и не через атаки на конкурентов.
- [ ] Первые communities/ambassadors: privacy, travel, слабая связь, локальные сообщества, семьи,
  small teams и emergency/offline use cases. Дать им onboarding kit и прямой feedback route.
- [ ] Product directories, тематические СМИ, creators и open-source communities подключать после
  стабильной pilot wave. Никаких купленных ботов, массового unsolicited DM, contact scraping,
  fake reviews, скрытых installs или вводящей в заблуждение рекламы.
- [ ] Public roadmap и короткие release notes показывают только главную пользовательскую пользу и
  существенные ограничения; обязательный redaction checklist: [`RELEASE_PUBLICATION_POLICY.md`](RELEASE_PUBLICATION_POLICY.md).
  пользователи могут голосовать за следующие функции без раскрытия переписки.

## Фаза 2.5.4 — Волны роста до тысяч и десятков тысяч

Рост идёт ступенчато; install count не равен успешному продукту. Главные показатели — activation,
retention, успешная доставка и добровольные приглашения.

1. **Founding pilot: 50–100 людей.** Разные Android/сети/страны; ручная поддержка, исправление
   blocker onboarding, delivery, battery, data loss и update.
2. **Closed beta: 300–1 000.** Проверить invite funnel, D1/D7 retention, background delivery,
   support load, endpoint capacity и безопасное обновление предыдущей версии.
3. **Open beta: 1 000–5 000.** Landing/stores/community launch, localized onboarding, staged APK
   rollout 5%→25%→100%, публичный status/known issues и быстрый rollback.
4. **Growth wave: 10 000–50 000+.** Только после scale gate и стабильной open beta; несколько
   регионов/communities, Groups/social loop, mirrors/bridges, on-call incident process и capacity
   reserve. Следующую волну не открывать только ради красивого числа installs.

На каждой волне смотреть privacy-preserving aggregate или добровольные diagnostics:

- install → first launch → profile → first contact → first sent → first delivered;
- время до первого полезного сообщения и доля завершивших invite;
- D1/D7/D30 retention, active senders/recipients и invitations per active user;
- доля приглашений, приведших к активному другу, и органический referral coefficient;
- p50/p95 delivery latency, offline success, duplicate UI, Outbox age, reconnect success;
- crash-free/ANR-free sessions, battery/data/storage, support tickets и update success;
- network capacity и cost per active user без message content, точного social graph и permanent
  cross-app tracking IDs.

**Stop conditions:** data/key loss, plaintext leak, signer/update mismatch, duplicate delivery,
unbounded traffic, widespread battery drain, crash/ANR spike, недоступный rollback или перегрузка
support/network. При stop новая волна замораживается; существующим пользователям даётся status,
безопасный fix/rollback и сохранение Outbox — маркетинг не важнее данных и доверия.

## Фаза 2.5.5 — Операционная готовность после публичного запуска

- [ ] Release train с staged rollout, signed manifests, minimum supported version и rollback.
- [ ] Несколько download/bootstrap/bridge routes с health checks; ни GitHub, ни один broker/domain
  не являются единственной точкой отказа.
- [ ] Privacy-safe monitoring только технических counters; opt-in crash reports с redaction,
  support inbox/issue triage, incident severity и публичный status channel.
- [ ] Abuse/report/block tools, rate limits и понятные community rules до появления больших групп.
- [ ] FAQ для установки, restricted networks, смены телефона, backup/recovery и проверки подписи.
- [ ] Маленькая команда/процесс может обработать рост: owner release, Android/Rust, infrastructure,
  support/community и security response; роли могут совмещаться, но ответственность явная.
- [ ] Еженедельный review funnel/retention/reliability и приоритизация реальных причин ухода вместо
  бесконечного добавления функций.

## Что можно оставить после запуска

Допустимо выпускать постепенно, если это честно указано и core gate закрыт:

- advanced group roles/topics и очень большие группы;
- полноценные channels, Stories и creator analytics;
- video/voice calls, group voice chats;
- полный набор stickers/GIF/video circles и сложный media editor;
- desktop/multi-device, LoRa, post-quantum hybrid и advanced onion/traffic padding;
- дополнительные themes, animations и power-user настройки.

**Нельзя оставлять «на потом»:** data/key preservation, E2E для заявленной приватности, безопасные
updates/signing, truthful delivery statuses, bounded queues/traffic, basic accessibility, простой
invite/onboarding, crash/ANR blockers, privacy/legal basics и измеренную способность сети выдержать
текущую rollout wave.

### Критерий завершения глобального этапа

APU имеет signed launch-ready build, красивый и понятный onboarding, доказанную core-доставку,
безопасное обновление и измеренную capacity. Первые пользователи не просто устанавливают APU, а
успешно общаются, возвращаются и добровольно приводят друзей. Рост проходит контролируемые волны
100 → 1 000 → 10 000 → десятки тысяч без потери данных, приватности и качества; необязательные
функции продолжают выходить после запуска по обратной связи.

---

# 🟡 ПРИОРИТЕТ 3 — Группы как в Telegram

Пользователь хочет увидеть группы уже достаточно скоро. Поэтому группы ставятся в ранний roadmap после invite/delivery hardening.

## Фаза 3.1 — Groups MVP: простая группа без тем

Цель: пользователь может создать группу, добавить участников и писать туда сообщения.

### Функции

- [ ] Создать группу.
- [ ] Название группы.
- [ ] Аватар группы или initials.
- [ ] Добавить участников из контактов.
- [ ] Список участников.
- [ ] Отправка сообщения в группу.
- [ ] Доставка group message через fan-out: отправитель отправляет каждому участнику отдельное E2E-сообщение.
- [ ] Локальное хранение group metadata.
- [ ] System messages:
  - создана группа;
  - добавлен участник;
  - удалён участник;
  - изменено название.

### Ограничения MVP

- Нет сложной server-side группы.
- Нет единого сервера группы.
- Отправитель сам рассылает участникам.
- Для offline участников используется store-and-forward.

### Критерий

Три телефона могут состоять в одной группе и обмениваться сообщениями.

## Фаза 3.2 — Group roles

Роли как в Telegram, но P2P:

- Owner.
- Admin.
- Moderator.
- Member.
- Read-only member.

Задачи:

- [ ] Права на добавление участников.
- [ ] Права на удаление участников.
- [ ] Права на изменение названия/аватара.
- [ ] Подписанные group events.
- [ ] Проверка group event signatures.

## Фаза 3.3 — Темы внутри групп

Аналог Telegram topics.

Функции:

- [ ] Включить темы в группе.
- [ ] Создать тему.
- [ ] Название темы.
- [ ] Иконка/emoji темы.
- [ ] Закрыть тему.
- [ ] Закрепить тему.
- [ ] Сообщения внутри темы.
- [ ] Уведомления по темам.
- [ ] Mute отдельной темы.

## Фаза 3.4 — Group CRDT

Чтобы группа работала без центрального сервера:

- [ ] CRDT OR-Set для состава участников.
- [ ] CRDT для topics.
- [ ] CRDT для pinned messages.
- [ ] Vector clocks для group events.
- [ ] Conflict resolution.

## Фаза 3.5 — Большие группы

- [ ] Оптимизация fan-out.
- [ ] Relay-assisted group delivery.
- [ ] Tier 1 group relay.
- [ ] Partial sync.
- [ ] Message history sync.
- [ ] Join by invite link.
- [ ] Invite approval.
- [ ] Public/private groups.

---

# 🟡 ПРИОРИТЕТ 4 — Каналы как в Telegram

## Фаза 4.1 — Channels MVP

Канал — один или несколько админов публикуют, подписчики читают.

Функции:

- [ ] Создать канал.
- [ ] Public/private channel.
- [ ] Название, описание, аватар.
- [ ] Owner/Admin.
- [ ] Публикация постов.
- [ ] Подписка по invite link.
- [ ] Read-only для подписчиков.
- [ ] Forward/share post.

## Фаза 4.2 — Децентрализованная доставка каналов

- [ ] Подписчики получают encrypted channel updates.
- [ ] DHT record для public channel discovery.
- [ ] Signed channel manifest.
- [ ] Relay-assisted distribution.
- [ ] Store-and-forward для offline subscribers.

## Фаза 4.3 — Telegram-like channel features

- [ ] Reactions.
- [ ] Comments через linked group.
- [ ] Scheduled posts.
- [ ] Pinned posts.
- [ ] Search внутри канала.
- [ ] Channel statistics local/approximate.

---

# 🟡 ПРИОРИТЕТ 5 — Чаты как в Telegram

## Фаза 5.1 — Базовые удобства

- [ ] Ответ на сообщение.
- [ ] Пересылка сообщения.
- [ ] Копирование сообщения.
- [ ] Удаление у себя.
- [ ] Удаление у всех, если поддержано протоколом.
- [ ] Редактирование сообщения.
- [ ] Статус редактирования.
- [ ] Закреплённые сообщения.
- [ ] Черновики.
- [ ] Поиск по чату.

## Фаза 5.2 — Реакции и статусы

- [ ] Emoji reactions.
- [ ] Read receipts.
- [ ] Delivered receipts.
- [ ] Typing indicator.
- [ ] Online/last seen, если пользователь разрешил.
- [ ] Mute chat.
- [ ] Archive chat.

## Фаза 5.3 — Медиа и любые файлы

Поддержать отправку из личных чатов, групп и (где разрешено) каналов:

- [ ] Фото: камера/галерея, оригинал или сжатие, несколько фото/альбом, подпись.
- [ ] Видео: запись/галерея, preview, длительность, streaming после первых chunks.
- [ ] Аудиофайлы: музыка/записи, название/исполнитель/обложка, audio player.
- [ ] Voice messages: запись, waveform, pause/resume, скорость воспроизведения.
- [ ] Видеосообщения-кружочки: запись с front/back camera, preview, lock/pause/cancel,
  круглый player, reply/forward и та же E2E chunk-доставка, что у обычного видео.
- [ ] Документы и произвольные файлы любых расширений с безопасным MIME/именем/размером.
- [ ] GIF/анимации, stickers и animated stickers как отдельные media types.
- [ ] Контакт-карточка (vCard) и геолокация — отдельные типы вложений.
- [ ] Preview/thumbnail для изображений, видео, PDF и поддерживаемых документов.
- [ ] Несколько вложений в одном сообщении; reply/forward/copy metadata без потери media.
- [ ] Upload/download progress, pause/cancel/retry и resume interrupted transfer.
- [ ] Передача большими encrypted chunks, checksums каждого chunk и content hash всего файла.
- [ ] Дедуп chunks/content, параллельная загрузка и восстановление только недостающих chunks.
- [ ] E2E encryption ключей/manifest/chunks; relay/MQTT никогда не видит plaintext.
- [ ] Offline store-and-forward media manifest/chunks с TTL, квотами, лимитом места и приоритетом.
- [ ] Настройки: auto-download по Wi-Fi/mobile/roaming, максимальный размер и экономия трафика.
- [ ] Cache/storage manager: просмотр занятого места, выборочная и автоматическая очистка.
- [ ] Defensive media tests: неверный MIME/имя/path traversal, oversized dimensions, corrupted
  manifest/chunks, duplicate/out-of-order chunks, checksum mismatch, zip/decompression bomb,
  disk quota exhaustion и interrupted cleanup — только на собственных тестовых файлах.
- [ ] Не помещать большие файлы в текстовый MQTT relay-envelope: отдельно передавать bounded
  manifest и chunks через P2P/QUIC/доступные relay transports.

Критерий: пользователь может надёжно отправить любой файл, фото, видео, аудио, voice note
или видеокружок; передача переживает разрыв сети, проверяет целостность, не раскрывает relay
содержимое и не переполняет память/диск телефона.

## Фаза 5.4 — Истории / Stories

- [ ] Создание истории из фото, видео, камеры, галереи или текста; подпись и простое оформление.
- [ ] Срок жизни по умолчанию 24 часа с автоматическим удалением из ленты и relay/cache.
- [ ] Аудитория: все контакты, близкие друзья, выбранные контакты, исключения и «только я».
- [ ] Подписанный story manifest (`story_id`, author PK, created/expires, media hash, audience).
- [ ] E2E-encrypted media chunks; relay видит только минимум metadata, нужный для TTL/routing.
- [ ] Просмотры, reactions и reply в личный чат с настройками приватности.
- [ ] Удаление своей истории; локальный архив/«актуальное» — отдельная opt-in функция.
- [ ] Offline/P2P доставка только разрешённой аудитории через friend-priority relay, без
  публичного all-to-all flood; истёкшую историю никогда не пересылать.
- [ ] Rate limits и квоты на число/размер stories, батарею, трафик и storage/cache.
- [ ] Блокировка/жалоба; не обещать абсолютный запрет screenshots на чужом устройстве.
- [ ] Истории групп/каналов — отдельное расширение после личных stories.

Критерий: пользователь публикует фото/видео-историю для выбранной аудитории, она проверяется
по подписи автора, доставляется P2P с ограниченным TTL и исчезает через 24 часа без шторма и
без сохранения plaintext на relay.

---

# 🟢 ПРИОРИТЕТ 6 — Звонки и голосовые чаты

## Фаза 6.1 — Voice calls MVP

- [ ] 1-to-1 voice call.
- [ ] Signaling через P2P/relay/bootstrap.
- [ ] WebRTC или QUIC media path.
- [ ] NAT traversal.
- [ ] Fallback через relay node.
- [ ] Mute microphone.
- [ ] Speaker toggle.
- [ ] Call notification.

## Фаза 6.2 — Video calls

- [ ] 1-to-1 video.
- [ ] Camera toggle.
- [ ] Picture-in-picture.
- [ ] Adaptive bitrate.
- [ ] Relay fallback.

## Фаза 6.3 — Group voice chats

Аналог Telegram voice chats:

- [ ] Голосовой чат внутри группы.
- [ ] Speaker/listener roles.
- [ ] Raise hand.
- [ ] Admin mute.
- [ ] Mesh mode до малого числа участников.
- [ ] Tier 1 SFU-like relay для больших комнат.

---

# 🟢 ПРИОРИТЕТ 7 — Реальная независимая сеть

## Фаза 7.1 — mDNS/LAN без интернета

- [ ] Полная проверка LAN discovery.
- [ ] Обмен сообщениями в одной Wi-Fi сети без интернета.
- [ ] Invite через QR без интернета.
- [ ] Передача APK рядом без интернета.
- [ ] LAN diagnostics.

## Фаза 7.2 — Wi-Fi Direct / Nearby / Bluetooth

- [ ] Nearby Connections API.
- [ ] Wi-Fi Direct transport.
- [ ] Bluetooth transport для коротких сообщений/invites.
- [ ] Offline emergency mode.
- [ ] Автоматическая ретрансляция между рядом находящимися телефонами.

## Фаза 7.3 — DHT и seed nodes

- [ ] Production DHT flow.
- [ ] Signed node records.
- [ ] Bootstrap через seed nodes.
- [ ] User-defined seed nodes.
- [ ] Signed seed manifest.
- [ ] DHT poisoning protection.

## Фаза 7.4 — Tier relay network

- [ ] Node rating.
- [ ] Tier classification.
- [ ] Пользовательское разрешение быть relay.
- [ ] Relay only on charging/Wi-Fi option.
- [ ] Traffic limits.
- [ ] Relay statistics.
- [ ] Local trust graph.

---

# 🟢 ПРИОРИТЕТ 7+ — Автоматический обход ограничений и самовосстановление связи

> **Цель:** приложение само находит рабочий транспорт в условиях блокировок и ограничений и автоматически восстанавливает связь. Внешние ресурсы (прокси, relay, bootstrap) используются только как вспомогательный канал и **никогда не видят plaintext** — только E2E-зашифрованный payload.
>
> Логически продолжает P7 «Реальная независимая сеть» и жёстко связан с P8 «Криптография и приватность»: всё, что проходит через любой транспорт, уже зашифровано end-to-end.

## Фаза 7+.1 — TransportManager (единый транспортный слой)

Единая точка выбора и управления всеми транспортами приложения.

- [ ] `TransportManager` как единственный вход для отправки/приёма.
- [ ] Реестр доступных транспортов с приоритетом и весами.
- [ ] Текущий активный транспорт и журнал переключений.
- [ ] Автоматический выбор лучшего транспорта по health score.
- [ ] Ручное переключение транспорта в debug-режиме.
- [ ] Единый callback статуса транспорта (online / offline / degraded).
- [ ] Метрики каждого транспорта (latency, loss, throughput).
- [ ] Graceful переключение без потери сообщений в полёте (queue + resend).

## Фаза 7+.2 — Автоматический поиск и проверка прокси

- [ ] Обнаружение доступных прокси (SOCKS5, HTTP, MTProto).
- [ ] Поддержка пользовательских прокси-списков.
- [ ] Поддержка подписанных private/user-approved proxy subscriptions; случайные public proxy pools
  не использовать из-за перехвата метаданных, нестабильности и abuse-риска.
- [ ] Автоматическая проверка reachability каждого прокси.
- [ ] Тестовое соединение через прокси (handshake + round-trip).
- [ ] Отбраковка мёртвых / медленных / подозрительных прокси.
- [ ] Кеширование последних рабочих прокси.

## Фаза 7+.3 — Proxy fallback: SOCKS5 / HTTP / MTProto

- [ ] SOCKS5 proxy transport.
- [ ] HTTP CONNECT proxy transport.
- [ ] MTProto-compatible bridge — только если используется собственный/проверенный encapsulation;
  обычный MTProto proxy не считать универсальным транспортом произвольных APU bytes.
- [ ] Каскадный fallback: при сбое одного типа пробуется следующий.
- [ ] Туннелирование QUIC / WebSocket через прокси.
- [ ] Параллельные попытки на нескольких прокси (happy-eyeballs).
- [ ] Цепочки прокси (chain) и multi-hop как будущая идея.

## Фаза 7+.4 — Proxy health score и автоматическая ротация серверов

- [ ] Health score для каждого прокси / relay / bootstrap-сервера.
- [ ] Метрики: latency, success rate, throughput, stability, censorship-resistance.
- [ ] Деградация score при сбоях и таймаутах.
- [ ] Автоматический выбор top-N лучших эндпоинтов.
- [ ] Фоновый периодический health-check (probing).
- [ ] Circuit breaker: временная изоляция плохих эндпоинтов.
- [ ] Автоматическая ротация без участия пользователя.
- [ ] Уведомление пользователя при падении качества связи.

## Фаза 7+.5 — Anti-censorship modes

Режимы работы сети, выбираемые вручную или автоматически:

| Режим | Назначение |
| --- | --- |
| **Normal** | Прямые соединения, минимум прокси. Базовый режим. |
| **Restricted** | Предпочитать прокси, обход базовых блокировок DNS/IP. |
| **Heavy Censorship** | Агрессивный fallback, pluggable transports, обфускация, домино. |
| **Offline Mesh** | Только локальные P2P: LAN/mDNS, Wi-Fi Direct, Bluetooth. Без интернета. |
| **Emergency** | Минимальный bootstrap: QR, invite-файл, передача APK рядом, Bluetooth. |

Задачи:

- [ ] Переключение режимов вручную из настроек / диагностики.
- [ ] Автоопределение режима по доступности сети и блокировкам.
- [ ] Плавная деградация: Normal → Restricted → Heavy → Offline Mesh → Emergency.
- [ ] Индикатор активного режима в UI.
- [ ] Логирование причин переключения режима.

## Фаза 7+.6 — Fallback без серверов

Связь и добавление контактов без интернета и без внешних ресурсов:

- [ ] QR invite (генерация + сканирование).
- [ ] Offline invite-файл (`.apumir`).
- [ ] Передача APK рядом (Nearby Share / Quick Share).
- [ ] Bluetooth transport для коротких сообщений и invites.
- [ ] Wi-Fi Direct transport.
- [ ] LAN / mDNS transport.
- [ ] QR-серия для передачи больших bootstrap-данных.
- [ ] NFC bundle transfer (будущее).

## Фаза 7+.7 — Android VpnService / app-scoped tunnel (будущая идея)

> Идея, требующая аккуратной реализации и явного согласия пользователя (Android VPN consent dialog). Туннель **app-scoped** — только для трафика APUMIR, а не всей системы.

- [ ] App-scoped VPN-туннель только для трафика приложения.
- [ ] Маршрутизация всего трафика APUMIR через прокси / relay.
- [ ] Изоляция от системного VPN и системного DNS.
- [ ] Подмена DNS на DoH / DoT внутри туннеля.
- [ ] Split tunneling.
- [ ] Согласование с пользователем через системный диалог VPN.
- [ ] Постоянное уведомление об активном VPN-подключении.
- [ ] Тест: туннель не должен нарушать приватность или обходить user consent.

## Фаза 7+.8 — Pluggable transports и обфускация (будущее)

- [ ] obfs4-like transport.
- [ ] Domain fronting только при законности и явной поддержке CDN/provider; обязательный fallback,
  потому что большинство крупных CDN это ограничивает.
- [ ] Meek / новые типы pluggable transports.
- [ ] Обфускация формы трафика (padding, dummy traffic).
- [ ] Маскировка под обычный HTTPS / WebSocket.

## Фаза 7+.9 — Безопасность: прокси / relay не видят plaintext

> Жёсткое архитектурное правило: **любой внешний транспорт получает только E2E-зашифрованный envelope.** Прокси / relay / bootstrap — это почтальоны, а не читатели.

- [ ] Весь payload через proxy / relay / bootstrap — E2E encrypted.
- [ ] Прокси видит только encrypted envelope (минимальные метаданные маршрутизации + ciphertext).
- [ ] Отсутствие приватных ключей на стороне любого транспорта.
- [ ] Audit: ни один внешний транспорт не получает plaintext.
- [ ] Минимизация метаданных, передаваемых через прокси.
- [ ] Тест red-team: relay / proxy не может расшифровать даже при полном логировании.
- [ ] Sealed sender / скрытие отправителя там, где это возможно.

## Критерий завершения приоритета 7+

- [ ] В условиях блокировок / ограничений приложение автоматически переходит на рабочий транспорт без ручных действий.
- [ ] При полном отсутствии интернета работают Offline Mesh / Emergency (QR, Bluetooth, LAN, APK рядом).
- [ ] Ни один прокси / relay / bootstrap не может прочитать содержимое сообщений.
- [ ] Пользователь видит активный режим и качество связи.

---

# 🔵 ПРИОРИТЕТ 8 — Криптография и приватность

## Фаза 8.1 — Проверка текущей защиты

- [ ] Audit: отправляется ли plaintext через MQTT/CF/TG.
- [ ] Тест: relay не может прочитать content.
- [ ] Проверка сохранения ключей.
- [ ] Проверка identity после `adb install -r`.

## Фаза 8.2 — Signal-like protocol

- [ ] Полный X3DH.
- [ ] Signed prekeys.
- [ ] One-time prekeys.
- [ ] Full Double Ratchet with DH-ratchet.
- [ ] Safety numbers.
- [ ] QR verification.
- [ ] Key change warning.
- [ ] Sealed sender.

## Фаза 8.3 — Metadata protection

- [ ] Минимизация раскрытия nodeId.
- [ ] Onion routing через relay nodes.
- [ ] Traffic padding.
- [ ] Dummy traffic.
- [ ] Delayed delivery.
- [ ] Контактное обнаружение без раскрытия адресной книги.

## Фаза 8.4 — Abuse resistance: spam, DoS, replay и hostile inputs

- [ ] Parser fuzz/property tests: malformed fields/base64/UTF-8, integer boundaries, unknown tag,
  payload size limit до дорогого decode/allocation и legacy/new format confusion.
- [ ] Spam regression: одинаковый `msg_id`, conflicting origin/payload, notification coalescing,
  duplicate receipt/ACK и replay после cleanup/reconnect/process restart.
- [ ] Resource DoS: bounded event channels/caches/queues, slow consumer, queue/storage saturation,
  reconnect storm, backoff+jitter/circuit breaker и recovery после окончания входа.
- [ ] Broker failover regression: queued `AsyncClient` request не считать соединением; bounded
  ConnAck gate обязателен, но нельзя просто увести один телефон на другой независимый public
  broker — без bridge это разделит mesh. Нужны overlapping multi-broker subscriptions/publish с
  дедупом либо coordinated primary migration, затем circuit breaker/re-subscribe без flood.
  Принятый staged design и local failure matrix: `docs/MQTT_MULTI_BROKER_DESIGN.md`.
- [ ] Identity/Sybil: forged invite/PK/username binding, enumeration, registry poisoning и ложная
  friend-of-friend цепочка; неизвестные peers не вытесняют friend traffic.
- [ ] Android surface: malformed deep links/QR/clipboard, exported components/FileProvider,
  repeated service start/network changes, notification/background wake abuse.
- [ ] Diagnostics/privacy/update: redaction test с test secrets, collector-key failure,
  APK signature/checksum/version/provenance и clean-PC milestone restore.
- [ ] Unit/3-phone/local-load/release-candidate cadence и критерии из
  `docs/SECURITY_RESILIENCE_TEST_PLAN.md`; high load только на собственной инфраструктуре.

Критерий: нет crash/ANR/restart и второго UI event; hard caps соблюдены, секреты не попали в
логи, а после каждого теста обычное control message доставляется и приложение само восстановилось.

---

# 🟣 ПРИОРИТЕТ 9 — Большие идеи и резерв будущего

## 9.1 — Offline disaster mesh

- [ ] Режим связи без SIM и интернета.
- [ ] Wi-Fi Direct mesh.
- [ ] Bluetooth mesh.
- [ ] Автоматическая локальная ретрансляция.
- [ ] Emergency broadcast.

## 9.2 — LoRa / radio bridge

- [ ] LoRa-модуль через USB/Bluetooth.
- [ ] Очень короткие encrypted messages.
- [ ] Long-distance emergency text.
- [ ] Mesh через LoRa nodes.

## 9.3 — Sneakernet transport

- [ ] Encrypted message bundle в файл.
- [ ] QR-серия для передачи сообщения.
- [ ] NFC bundle transfer.
- [ ] USB/flash drive transfer.
- [ ] Courier mode.

## 9.4 — Alternative encrypted transports

- [ ] Nostr relay как encrypted envelope transport.
- [ ] Matrix bridge как encrypted envelope transport.
- [ ] ActivityPub relay compatibility.
- [ ] DNS-over-HTTPS fallback.
- [ ] WebSocket public fallback.
- [ ] Steganographic payload in images/text.

## 9.5 — Post-quantum hybrid

- [ ] Hybrid X25519 + ML-KEM/Kyber.
- [ ] PQ prekey bundle.
- [ ] PQ session upgrade.
- [ ] Migration strategy.

## 9.6 — Multi-device and desktop

- [ ] Несколько устройств одного пользователя.
- [ ] Device linking через QR.
- [ ] Device revocation.
- [ ] Desktop app на Tauri.
- [ ] CLI/headless relay node.
- [ ] Docker image для seed/relay.

---

# 10. Ближайший практический roadmap

## v11.16.4 — Invite/deep link/registry stabilization

Цель: легко пригласить друга и корректно добавить контакт.

- [ ] `InviteLinkParser.kt`.
- [ ] Unit tests для parser.
- [ ] Исправить Telegram bot username mismatch.
- [ ] Исправить `start=<nodeId>` / `start=add_<nodeId>` mismatch.
- [ ] Registry lookup при открытии invite.
- [ ] Улучшить экран “Поделиться профилем”.
- [ ] Добавить текст “если приложение не установлено”.
- [ ] Проверить на 3 телефонах.

## v11.16.5 — Offline delivery + Install/share friend flow

> Подробный дизайн и план офлайн-доставки: [`OFFLINE_DELIVERY.md`](OFFLINE_DELIVERY.md).
> В этой версии офлайн-доставка (store-and-forward + ACK через relay) — **основной фокус**;
> install/share — второй трек.

### Трек A — Offline delivery (основной)

- [ ] D1. Расширить `MessageStatus` (`QUEUED_OFFLINE`, `READ`) + миграция БД.
- [ ] D2. ACK round-trip через CF relay → `DELIVERED` у отправителя (+ unit-тест).
- [ ] D3. OutboxProcessor: гарантированный постинг `PENDING/QUEUED_OFFLINE` в CF relay с retry.
- [ ] D4. Дедуп CF poll по `messageId`.
- [ ] D5. Запуск OutboxProcessor на старте / `onNetworkConnected` / `peer_discovered` / таймер.
- [ ] D6. Smoke-тест на 3 телефонах: доставка офлайн + `DELIVERED`.

### Трек B — Install/share friend flow

Цель: друг без приложения может легко установить APUMIR.

- [ ] Invite text содержит ссылку на APK release.
- [ ] Показывать SHA-256 APK.
- [ ] Кнопка “Поделиться APK”.
- [ ] Кнопка “Поделиться APK + invite”.
- [ ] QR invite.
- [ ] Offline invite file.

## Ближайший новый этап после текущего R1 — Secure File Transfer MVP

> По решению пользователя передача файлов поднята из далёкой Фазы 5.3 в ближайший практический
> roadmap. Порядок: закрыть уже начатый R1 pending-referral gate → сделать минимальную безопасную
> передачу файлов в личном чате → затем продолжить R2 qualification/status и Groups. Фото/video/
> voice и расширенный media editor остаются в Фазе 5.3 и переиспользуют готовый file transport.

Цель MVP: выбрать файл через системный Android picker, надёжно и E2E-зашифрованно передать его
контакту, увидеть progress/result и безопасно сохранить полученный файл без загрузки целиком в RAM.
Authoritative design: [`SECURE_FILE_TRANSFER.md`](SECURE_FILE_TRANSFER.md).

### F0 — формат и threat model

> Canonical manifest + chunk AEAD source готов; production Rust + APK compile PASS. Host tests всё ещё
> заблокированы отсутствующим MSVC `link.exe`. Bounded no-input/no-secret Android runtime diagnostic,
> native, generated binding, app APK и test APK build PASS; normalized binding accepted as commit
> `e36d1ba`; data-preserving Stas runtime roundtrip/tamper/index/manifest gate PASS. Host test
> execution remains pending only because the current PC lacks MSVC `link.exe`.

- [ ] Versioned signed attachment manifest: `transfer_id`, message/chat binding, sender/recipient,
  безопасное display name, declared MIME/size, chunk size/count, whole-file hash, timestamps/TTL.
- [ ] Случайный file key; manifest/key и каждый chunk защищены E2E. Relay/MQTT не видит plaintext,
  исходное имя файла или ключ.
- [ ] Начальный лимит личного чата: 10 MiB; chunk 64–256 KiB после benchmark. Лимиты повышаются
  только после memory/disk/network gates. Не класть файл целиком в MQTT message envelope.
- [ ] SAF (`ACTION_OPEN_DOCUMENT`/`ACTION_CREATE_DOCUMENT`), без broad storage permission и без
  доверия к внешнему path/MIME/имени.

### F1 — core transfer и локальное хранение

> Additive Room v5→v6 transfer/chunk schema, bounded progress DAO and isolated migration/cascade
> app/test APK build PASS. Existing chats/messages are not rewritten. A second instrumentation source
> hashes legacy row IDs before/after real production migration and opens generated Room v6 schema;
> rebuilt test APK PASS. First Stas harness applied additive SQL outside Room, preserving legacy rows but
> leaving DB v6 with old v5 `room_master_table` identity; app data was not deleted. One-time recovery
> Stas Room identity recovered and v6 generated schema validated with legacy state preserved. Bounded
> no-backup encrypted chunk store JVM/APK gate PASS. Streaming SAF source inspector now sanitizes
> provider metadata, enforces 10 MiB/exact declared size and computes SHA-256 with a fixed 64 KiB
> buffer; JVM/APK tests PASS. F1 functional Rust seam source now adds strict canonical manifest decode,
> random transfer ID and bounded per-chunk encrypt/decrypt APIs; native/binding/APK build PASS,
> normalized generated binding accepted as commit `2477417`. Isolated Android functional test now covers
> create/parse/encrypt/store/read/decrypt/idempotent retry/tamper/truncation/cleanup with transient key;
> test APK/device gate pending. File key remains transient-only until a device-bound transfer key vault
> and E2E key envelope are wired. Functional Android pipeline on Stas PASS with production state
> preserved. Device-bound transfer key vault source now wraps per-transfer 32-byte keys with a
> non-exportable Android Keystore AES-GCM key, transfer-ID AAD and atomic no-backup envelope;
> Stas Keystore execution PASS with test alias/root cleanup and production state preserved. Outgoing local
> preparation owner source now connects SAF inspection → canonical manifest → wrapped transfer key →
> streaming chunk encryption → atomic no-backup store → monotonic Room progress, without publishing;
> JVM/APK build PASS. Dependencies are now isolatable and an Android integration test stages a
> two-chunk content:// file through in-memory Room + test vault/store, decrypts exact bytes and cleans
> all test state; app/test APK and data-preserving Stas two-chunk execution PASS.

- [ ] Room-модель transfer/manifest/chunk state; сами большие encrypted bytes — в bounded app-private
  files, не в строках Room и не в Compose state.
- [ ] Streaming encrypt/hash/read и streaming verify/decrypt/write с фиксированным memory ceiling.
- [ ] Chunk index/hash, out-of-order сборка, dedup, retry только отсутствующих chunks, pause/cancel.
- [ ] Atomic finalization: файл становится доступен только после exact size + whole hash + AEAD PASS;
  partial/corrupt state очищается безопасно после TTL.
- [ ] Process death/reboot resume и idempotent receipt; duplicate manifest/chunk не создаёт второй файл.

### F2 — transport и UI

> First controlled cross-phone online harness source is intentionally test-only: a random key is
> passed out-of-band by the local gate, while real manifest and encrypted file/photo packets traverse
> existing addressed MQTT between Anna and Stas. Test APK build PASS. This proves bytes/network routing
> but is not the production E2E key exchange or user UI. Two-phone gate pending; Anna first runs the
> corrected data-preserving Room v5→v6 migration test. First gate timed out because Stas foreground
> service likely drained test events before instrumentation. Migration and sender PASS; recovery source
> cleans only synthetic harness rows, replace-installs both apps to stop competing services, waits on
> receiver-ready marker and retries packets. Public MQTT still delivered no receiver events and shutdown
> blocked. Recovery-02 switches only the controlled harness to an explicit ADB-tunneled direct TCP
> path (Anna localhost → PC tunnel → Stas:7778), retaining real cross-device encrypted bytes while
> avoiding public-broker uncertainty. Engine-based tunnel then hung before sender because receiver
> initialization/listener never became ready. Recovery-03 uses a test-only length-prefixed Java
> ServerSocket/Socket over the same ADB tunnel, with no Rust network engine. Sender reached the tunnel
> but timed out waiting for ACK; this confirms test plumbing is not a product transport. Per user
> direction, further tunnel retries stop here. Production work resumes with a versioned bounded packet
> fragmentation/reassembly layer for encrypted offers/chunks, followed by authenticated key exchange,
> direct QUIC/relay ownership and real chat picker/progress UI. Authenticated exchange source has now
> started: a separate static X25519 public key is signed by the installed Ed25519 sidecar and nested
> verified legacy identity binding; strict wire/tamper/foreign-binding tests are included.

- [ ] Сначала direct QUIC/P2P path; bounded fallback/offline relay только с квотами, TTL и backpressure.
- [ ] Кнопка-скрепка в личном чате, имя/размер, upload/download progress, cancel/retry, понятные ошибки.
- [ ] Получатель явно нажимает «Скачать/Сохранить»; executable/APK не открываются автоматически.
- [ ] Auto-download по умолчанию выключен; позже отдельные лимиты Wi-Fi/mobile/roaming.
- [ ] File transfer не блокирует текстовые сообщения и не получает повышенный transport priority.

### F3 — обязательные acceptance gates

- [ ] Boundary tests: 0 B, 1 B, chunk−1/chunk/chunk+1, 10 MiB, oversize rejection.
- [ ] Filename/path traversal, ложный MIME, corrupt/truncated/reordered/duplicate chunks, wrong key/tag,
  disk full/quota, cancel/restart/process death и sender/recipient mismatch.
- [ ] Два телефона: online transfer с SHA-256 equality; затем interruption/resume без дубля.
- [ ] Три телефона: offline custody с малым тестовым файлом, relay не показывает содержимое, receipt
  очищает chunks exactly-once; текстовая durable delivery остаётся работоспособной.
- [ ] Packet/log/DB audit: нет plaintext файла, полного local path, file key или содержимого в логах.

Расширенная Фаза 5.3 остаётся authoritative backlog для фото, видео, voice, previews, больших файлов,
cache manager и media UX, но её базовый encrypted chunk transport теперь реализуется здесь раньше.

## v11.17.0 — Groups MVP

Цель: первые группы в приложении.

- [ ] Group data model.
- [ ] Create group UI + domain/repository entitlement gate: минимум 10 qualified direct referrals.
- [ ] Add members.
- [ ] Group chat screen.
- [ ] Fan-out delivery каждому участнику.
- [ ] Group messages на 3 телефонах.

## v11.18.0 — Delivery diagnostics, channel transparency and data cleanup

- [ ] Показывать канал доставки в debug mode.
- [ ] Network diagnostics screen.
- [ ] Unified message envelope.
- [ ] Deduplication hardening.
- [ ] ACK hardening.
- [ ] Исправить дублирующие чаты, оставшиеся после старых версий.
- [ ] Добавить миграцию/утилиту слияния дублей чатов по реальному `contactId` / fingerprint.
- [ ] При слиянии сохранять историю сообщений и не терять статусы доставки.
- [ ] Добавить безопасный dry-run режим диагностики дублей перед изменением БД.

## Рабочая станция разработки и восстановление полного toolchain

- [ ] Купить/подготовить новый x86-64 компьютер по [`NEW_DEVELOPMENT_PC.md`](NEW_DEVELOPMENT_PC.md):
  минимум 32 ГБ RAM + NVMe 1 ТБ, рекомендуется 64 ГБ + NVMe TLC 2 ТБ + отдельный backup SSD.
- [ ] Обязательно восстановить MSVC `cl.exe`/`link.exe`, Rust MSVC host toolchain, Android SDK 35/NDK,
  JDK 21, cargo-ndk, Git/GitHub CLI и проверить environment inventory + clean build.
- [ ] Закрыть host `cargo test` blocker после появления исправного MSVC linker; не ослаблять crypto
  tests и не использовать production Android compile как вечную замену host runtime tests.

## Launch-ready APU — публичный запуск и рост 100 → 1 000 → 10 000+

> Номер версии назначается только после прохождения Global Stage 2.5, а не заранее ради рекламы.

- [ ] Завершить core visual/onboarding polish из Фазы 0.3 и минимальный everyday media slice.
- [ ] Закрыть invite/install, offline delivery, background/update/data/security и Groups MVP gates.
- [ ] Закрыть scale-safe routing/capacity simulation: public wildcard prototype не идёт в 10k wave.
- [ ] Выпустить signed Launch Readiness manifest, landing/share kit, support/status и rollback.
- [ ] Запустить direct referral MVP без pay-to-win: signed link/token, qualified friend после
  handshake+DELIVERED, exactly-once receipt, progress/status 1/3/10; остальные пороги до 1 000 —
  после privacy/anti-fraud pilot.
- [ ] Провести founding pilot 50–100 → closed beta 300–1 000 → open beta 1 000–5 000.
- [ ] Только по метрикам качества открыть 10 000–50 000+ growth wave.
- [ ] После запуска продолжить topics/channels/media/calls/desktop по feedback, не задерживая весь
  публичный запуск ради необязательного feature completeness.

## v11.19.0 — Group topics MVP

- [ ] Включение тем в группе.
- [ ] Создание темы.
- [ ] Сообщения внутри темы.
- [ ] Mute topic.
- [ ] Pin topic.

## v11.20.0 — Channels MVP

- [ ] Create channel UI + domain/repository entitlement gate: минимум 30 qualified direct referrals.
- [ ] Subscribe by invite доступен без ранга.
- [ ] Publish posts.
- [ ] Read-only subscribers.

---

# 11. Главный пользовательский сценарий, который должен быть доведён до идеала

```text
Я хочу пригласить друга.

1. Открываю APUMIR.
2. Нажимаю “Пригласить друга”.
3. Выбираю способ отправки:
   - Telegram;
   - SMS;
   - WhatsApp;
   - email;
   - QR;
   - Bluetooth;
   - Nearby Share;
   - APK + invite file.
4. Друг открывает ссылку.
5. Если APUMIR не установлен — он легко ставит APK.
6. После установки открывается invite.
7. Друг видит мой профиль.
8. Нажимает “Добавить”.
9. Я получаю/вижу его контакт.
10. Мы сразу можем писать друг другу.
```

Этот сценарий имеет максимальный приоритет, потому что без него рост сети невозможен.

---

# 12. Главная стратегическая линия

1. Сначала — лёгкое приглашение, установка, добавление контакта.
2. Затем — стабильная online/offline доставка, сохранение данных и безопасные обновления.
3. Довести APU до launch-ready качества: красивый UI/onboarding, everyday media slice, Groups MVP,
   diagnostics/security и измеренная scale-safe сеть.
4. Запустить контролируемые волны 100 → 1 000 → 10 000 → десятки тысяч пользователей; улучшать
   activation, retention и добровольные приглашения, а не гнаться только за installs.
5. После запуска продолжать темы, каналы, Stories, расширенные media, звонки и desktop по feedback.
6. Параллельно — уход от внешних ресурсов к собственной P2P/DHT/relay сети и расширение законных
   restricted-network transports без потери E2E/Outbox.

APU должен стать удобным и желанным для обмена с друзьями, как зрелый массовый messenger, но
архитектурно независимым как настоящая P2P-сеть. Маркетинг никогда не опережает сохранность данных,
приватность, измеренную capacity и честную доставку.

---

# 13. Самый поздний этап — устойчивое финансирование APU

> Этот этап начинается только после launch-ready качества, подтверждённой сохранности данных,
> стабильной доставки, privacy/security audit и появления устойчивой аудитории. Финансирование не
> должно менять приоритет сообщений, ослаблять E2E-защиту или превращать статусы/referrals в
> pay-to-win. Сначала добровольная поддержка; рекламная модель — самый последний пункт roadmap.

## 13.1 — Меню добровольного пожертвования

- [ ] Разработать отдельный понятный пункт меню **«Поддержать развитие APU»**.
- [ ] Объяснить простым языком, на что идут пожертвования: разработка, инфраструктура relay/update,
  тестовые устройства, безопасность, поддержка пользователей и юридические расходы.
- [ ] Показать, что пожертвование полностью добровольно: отсутствие оплаты не ограничивает
  сообщения, безопасность, доставку, referrals или другие базовые функции.
- [ ] Поддержать разовый и, только при явном согласии пользователя, регулярный платёж через
  проверенного платёжного провайдера; до реализации проверить правила магазинов приложений,
  налоги, возвраты, возрастные ограничения и требования страны получателя.
- [ ] Не хранить реквизиты банковских карт в APU. Показывать итоговую сумму, валюту, комиссию,
  получателя и условия до перехода к оплате; выдавать подтверждение платежа.
- [ ] Добавить прозрачный отчёт о развитии проекта без публикации персональных данных доноров.
- [ ] Запретить навязчивые окна, ложную срочность, автоматические галочки и преимущество доноров в
  transport/security priority. Аналитика пожертвований — только агрегированная и opt-in.
- [ ] Провести UX/accessibility, legal, payment-security и failed/refund flow tests до включения.

## 13.2 — Рекламные места и подключение рекламодателей (последний пункт roadmap)

- [ ] Сначала определить ограниченные рекламные места, не вмешивающиеся в личные и групповые чаты:
  например, отдельная витрина/раздел рекомендаций. Не размещать рекламу внутри private message
  history, системных уведомлений о доставке, onboarding безопасности или экстренных сценариев.
- [ ] Разработать собственный privacy-first формат рекламной карточки: явная маркировка
  **«Реклама»**, рекламодатель, причина показа, срок кампании, кнопки скрыть/пожаловаться и настройка
  отключения персонализации. Никакой маскировки под сообщение друга или системное событие.
- [ ] Не читать сообщения, контакты, social graph, clipboard, точную геолокацию или identity keys для
  таргетинга. Не подключать сторонний ad SDK до отдельного privacy/security/supply-chain audit.
- [ ] По умолчанию использовать контекстные или широкие категории без cross-app tracking и
  fingerprinting; чувствительные категории и таргетинг несовершеннолетних запретить.
- [ ] Реализовать consent/withdrawal, frequency cap, age/country restrictions, campaign kill switch,
  advertiser verification, moderation, malware/phishing URL scanning и публичные правила рекламы.
- [ ] Подготовить кабинет/форму рекламодателя: юридические данные, тематика, география, период,
  бюджет, creatives, landing URL, договор, маркировка и отчётность.
- [ ] Организовать **поиск рекламодателей**: медиакит APU, описание аудитории только в агрегированном
  виде, список подходящих отраслей, direct outreach, партнёрства и безопасный входящий канал заявок.
- [ ] Ввести процесс **подключения рекламодателей**: KYC/проверка компании, ручная модерация первой
  кампании, договор и оплата, тестовый малый лимит, контроль жалоб, приостановка и blacklist.
- [ ] Запретить мошеннические инвестиции, нелегальные товары, вредоносные APK, политическую рекламу
  без отдельного законодательства/audit, дискриминационный таргетинг и покупку referral-статусов.
- [ ] Метрики рекламы ограничить privacy-preserving показами/кликами/жалобами; не передавать
  рекламодателю node ID, контакты, IP, историю сообщений или индивидуальный профиль пользователя.
- [ ] Запустить только controlled pilot с несколькими проверенными рекламодателями; масштабировать
  после проверки жалоб, retention, производительности, энергопотребления, безопасности и экономики.
- [ ] Сохранить технический и коммерческий kill switch: реклама отключается без нарушения обычной
  работы мессенджера, а доход и существенные партнёрства отражаются в прозрачной отчётности.
