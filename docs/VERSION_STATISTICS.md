# APU — статистика глобальных версий

> **Обязательное правило для следующего ИИ:** обновлять этот файл для каждой крупной пользовательской
> версии APU после code freeze и проверок, но до/сразу после публикации release. Не заменять старые
> записи: добавлять новую секцию сверху и считать разницу с предыдущей зафиксированной версией.

## Что считать глобальной версией

Глобальная версия — опубликованный stable release или явно обозначенный test/prerelease milestone,
который пользователь сохраняет на флешку и/или распространяет другим людям. Внутренние harness,
исправление документации и не собранный commit отдельной глобальной версией не являются.

## Что записывать для каждой версии

1. versionName/versionCode, тип release и дата;
2. commit проверенного кода, documentation commit и release/tag target;
3. строки и файлы Rust, handwritten Kotlin, UDL, Android XML, generated bindings и tests;
4. изменение строк относительно предыдущей глобальной версии;
5. APK bytes, SHA-256 и signer certificate SHA-256;
6. основные добавленные возможности — 3–7 коротких пунктов;
7. результаты сборки, установки и runtime-проверок;
8. известные ограничения и следующий приоритет;
9. release URL и состояние portable backup previous/latest.

## Единый метод подсчёта

- Считать физические строки (`splitlines`) и отдельно непустые строки.
- Основной код: `rust-core/src/**/*.rs`, `rust-core/src/lib.udl`, handwritten Kotlin под
  `android-app/app/src/main/java/com/vladimir/messenger/**` и Android manifest/XML resources.
- Generated UniFFI Kotlin считать отдельно.
- Android tests считать отдельно; Rust unit tests внутри `.rs` пока входят в Rust total.
- Документацию, логи, APK/`.so`, изображения, `.git`, build outputs, `.gradle`, `target`, SDK/JDK,
  caches и `%TEMP%` не считать кодом.
- Test/release automation (`scripts/*.ps1`, Python/shell) показывать отдельно.
- Всегда указывать commit, на котором выполнен подсчёт. Не сравнивать цифры, полученные разными
  методами, без явной пометки.

## Сводная таблица

| Версия | Тип | Дата | Основной код | С generated | Automation | APK bytes | Статус |
|---|---|---|---:|---:|---:|---:|---|
| v11.16.16 | prerelease checkpoint | 2026-08-15 | 31 645 | 34 155 | 17 117 | 22 664 712 | опубликован |

---

## v11.16.16 — test checkpoint

### Идентификация

- Дата: 2026-08-15.
- versionName/versionCode: `v11.16.16` / `11016016`.
- Тип: GitHub prerelease, не stable release.
- Tested application commit: `61e1580ff85aa1cfaed1f9e7a7522f1cd8e5d602` + exact Windows
  Kotlin/Rust working overlays, сохранённые в portable source/patches.
- Release preparation/tag target: `85aecb0fa9893184e357b6c565869d0f1ebd69b7`.
- Commit подсчёта строк: `03c9768` (ветка `arena/01a000bc-apumir`; docs не входят в LOC).
- Release: <https://github.com/vzhem/APUMIR/releases/tag/v11.16.16>.

### Строки кода

| Категория | Файлов | Всего строк | Непустых строк |
|---|---:|---:|---:|
| Rust core `.rs` | 59 | 21 657 | 18 683 |
| Rust UniFFI UDL | 1 | 108 | 88 |
| Handwritten Android Kotlin | 77 | 9 495 | 8 545 |
| Android manifest/XML resources | 12 | 385 | 369 |
| **Основной код APU** | **149** | **31 645** | **27 685** |
| Generated UniFFI Kotlin | 1 | 2 510 | 2 065 |
| **Основной код + generated** | **150** | **34 155** | **29 750** |
| Android unit/instrumented tests | 5 | 241 | 195 |
| Test/release scripts | 47 | 17 117 | 15 555 |
| Весь tracked code/config/automation без docs/logs | 220 | 53 173 | 46 898 |

Разница с предыдущей глобальной версией: **нет сопоставимого baseline тем же методом**. Начиная со
следующей версии обязательно показать delta по каждой основной категории.

### APK и подпись

- APK: `APU-v11.16.16.apk`.
- Размер: `22 664 712` байт.
- SHA-256: `446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D`.
- Android package: `com.vladimir.messenger`.
- V2 signer certificate SHA-256:
  `F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7`.
- GitHub server-side asset digest совпал с APK SHA-256.
- Tag workflow: release-exists check PASS, build job skipped; повторной сборки/замены APK не было.

### Основные изменения

- Automatic origin relay для недоступного получателя.
- Bounded gossip/summary и missing-relay forwarding между совместимыми телефонами.
- TTL 7 дней, максимум 8 переходов, dedup и ограничения relay queues/traffic.
- Совместимый relay wire format для соседних версий.
- Bounded dual-broker transport и production dedup.
- Честный `QUEUED_OFFLINE` вместо ложного обещания немедленной доставки.

### Что проверено

- Signed Rust + Kotlin APK build: PASS.
- Data-preserving install v11.16.16 на Анну, Женю и Стаса: PASS 3/3.
- Controlled launch/readiness: PASS 3/3; primary/secondary READY, stable processes, no crash/ANR.
- Пользователь вручную наблюдал успешную offline UI-доставку через третий телефон.
- Отдельный post-capture не содержал exact message/protocol markers, поэтому full message-ID chain,
  receipt cleanup и eventual origin `DELIVERED` ещё не доказаны строгим runtime acceptance.

### Portable backup

- Путь: `F:\APU_PORTABLE`.
- Manifest: 278 файлов, все SHA-256 PASS.
- previous: v11.16.15.
- latest: v11.16.16.
- Git bundle verify, forbidden scan и restore rehearsal: PASS.
- Backup state SHA-256:
  `A96500612DD1AC80D908F1F49ADE9536931E512D387C2FD0EDA8CB82772D2483`.
- Флешку больше никогда не форматировать; только verified previous/latest rotation.

### Известное ограничение и следующий приоритет

**M8 persistent relay custody ещё не реализована.** RelayQueue хранится в памяти процесса, поэтому
Android process death/reboot до handoff может потерять чужое сообщение. Сценарий «Женя сохранил,
уснул, через сутки проснулся, запросил новые relay items и продолжил передачу» пока best-effort.

Следующий глобальный этап:

1. encrypted persistent RelayQueue;
2. absolute expiry без сброса TTL после restart;
3. recovery после process death/reboot;
4. bounded sleep/wake/background relay cycle;
5. durable receipt/tombstone cleanup и exactly-once UI delivery;
6. delayed Anna→Zhenya→relay D→Stas acceptance через несовпадающие online-окна.
