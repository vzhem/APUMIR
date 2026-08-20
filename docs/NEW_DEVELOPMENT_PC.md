# Новый компьютер для разработки APU

Актуально для текущего стека APU: Windows, Android Studio/Gradle/Kotlin, Rust/UniFFI, Android NDK,
несколько физических Android-телефонов и локальные debug/release сборки.

## Короткая рекомендация

Для APU разумный основной вариант — **обычный x86-64 настольный ПК** с современным 12–16-ядерным
процессором, 64 ГБ RAM и качественным TLC NVMe SSD 2 ТБ. Дискретная видеокарта для сборки Android/Rust
не обязательна. Важнее процессор, память, SSD, охлаждение, надёжный блок питания и резервное копирование.

Не покупать Windows-on-ARM как единственную машину разработки: совместимость MSVC, Android NDK,
USB-драйверов, Rust host tools и вспомогательных программ сложнее. Рабочий проект, pagefile и build
cache нельзя размещать на старом или подозрительном HDD.

## Характеристики

| Компонент | Минимум — работать без мучений | Рекомендуемый основной ПК | Разумный максимум |
|---|---|---|---|
| CPU | Современный x86-64, 8 ядер / 16 потоков | 12–16 производительных ядер, 20–32 потока | 16–24 мощных ядра; workstation выше этого для APU обычно не окупается |
| Примеры класса CPU | Ryzen 7 / Core Ultra 7 или сопоставимый | Ryzen 9 / Core Ultra 9 или сопоставимый | Старший Ryzen 9/Core Ultra 9; Threadripper только для параллельных CI/VM |
| RAM | 32 ГБ, желательно 2 модуля и возможность расширения | 64 ГБ | 128 ГБ |
| Системный/рабочий SSD | NVMe 1 ТБ, TLC, DRAM или качественный HMB; не менее 300–500 ГБ свободно | NVMe TLC 2 ТБ | NVMe TLC 4 ТБ |
| Второй накопитель | Внешний SSD 1–2 ТБ для backup | Второй внутренний NVMe 2 ТБ + внешний SSD | Второй NVMe 4 ТБ + отдельный внешний backup 4–8 ТБ |
| GPU | Встроенной графики достаточно | Встроенная или недорогая дискретная | GPU 12–16 ГБ VRAM только если нужны локальные AI-модели/графика; сборки APU почти не ускорит |
| Экран | 1920×1080, 24″ | 2560×1440, 27″, лучше два экрана | 4K 32″ + второй экран |
| Сеть | Gigabit Ethernet + Wi-Fi | 2.5 GbE + Wi-Fi 6/6E | 10 GbE только при наличии NAS/соответствующей сети |
| USB | Минимум 4 USB-A и USB-C, без дешёвого хаба для ADB | Много задних USB + питаемый качественный USB-хаб | Отдельный контроллер/хаб для одновременных телефонов |
| ОС | Windows 11 x64 с актуальной поддержкой | Windows 11 Pro x64 | Windows 11 Pro x64; Linux/WSL2 — дополнение, не замена Windows gates |
| Питание | Качественный БП и сетевой фильтр | 80+ Gold БП + line-interactive UPS | UPS с USB-мониторингом и запасом 10–20 минут |

### Если нужен ноутбук

- минимум 32 ГБ RAM, предпочтительно 64 ГБ;
- RAM/SSD должны расширяться, если это возможно; избегать 16 ГБ распаянной памяти без расширения;
- NVMe 1–2 ТБ и полноценное охлаждение важнее тонкого корпуса;
- процессор x86-64, минимум 8 производительных ядер;
- минимум два независимых USB-порта для телефонов плюс питание;
- для длительных Rust/Gradle сборок нужна модель без сильного thermal throttling;
- внешний монитор, питаемый USB-хаб и внешний SSD всё равно потребуются.

## Что проверить до покупки

1. Два доступных слота RAM или подтверждённая поддержка 64/128 ГБ.
2. Минимум два M.2 NVMe слота.
3. SSD именно NVMe TLC от нормального производителя, а не безымянный QLC.
4. Достаточное охлаждение CPU и airflow корпуса.
5. Несколько USB-контроллеров/портов и стабильная работа ADB.
6. Возможность включить аппаратную виртуализацию в UEFI.
7. Гарантия и доступность сервиса; накопители должны иметь SMART/health diagnostics.
8. Отдельный внешний backup: второй внутренний диск не заменяет резервную копию.

## Обязательные программы

Устанавливать только x64-версии с официальных сайтов/официального Microsoft Store или `winget`.

### Базовые

1. **Windows 11 Pro x64**, все стабильные обновления и драйверы chipset/network/USB.
2. **Git for Windows x64** — Git, Git Credential Manager и поддержка long paths.
3. **GitHub CLI (`gh`)** — работа с ветками, checks, issues и будущими pull requests.
4. **PowerShell 7 x64** — новые automation scripts; Windows PowerShell 5.1 оставить для совместимости.
5. **Python 3 x64** с launcher `py` — нормализация generated files и вспомогательные gates.
6. **7-Zip x64** — проверяемая работа с архивами и recovery packages.

### Android/Kotlin

7. **Android Studio Stable x64** с bundled JetBrains Runtime. Текущие scripts ожидают JDK 21 из
   `C:\Program Files\Android\Android Studio\jbr`.
8. Через Android SDK Manager:
   - Android SDK Platform 35;
   - Android SDK Build-Tools 35.x;
   - Android SDK Platform-Tools (`adb`);
   - Android SDK Command-line Tools (latest stable);
   - NDK (Side by side), совместимый с `cargo-ndk`;
   - CMake — полезен для диагностики native projects;
   - Android Emulator и один x86_64 image — желательны, но физические телефоны остаются обязательными
     для Keystore/background/network gates.
9. **OEM USB/ADB drivers** для используемых телефонов. Проверить режим USB debugging и authorization.

### Rust и обязательный Windows linker

10. **Visual Studio 2022 Build Tools x64** с workload **Desktop development with C++**:
    - MSVC x64/x86 build tools;
    - Windows 11 SDK;
    - C++ CMake tools (желательно).

    Это обязательный пункт: `cl.exe` и `link.exe` нужны для host Rust tests/proc-macro/build tools.
11. **Rustup x64 для MSVC**, stable toolchain `x86_64-pc-windows-msvc`.
12. Rust Android targets как минимум:
    - `aarch64-linux-android`;
    - `armv7-linux-androideabi`;
    - `x86_64-linux-android`.
13. **cargo-ndk** — production Android Rust `.so` builds.

### Обязательная эксплуатационная часть

14. Надёжный браузер с отдельной авторизованной вкладкой GitHub.
15. Средство проверки SMART/здоровья SSD от производителя или известная диагностическая утилита.
16. Backup-программа с проверкой хешей либо существующие APU backup scripts + внешний SSD.
17. Антивирус Microsoft Defender оставить включённым; точечные исключения build cache добавлять только
    после проверки, не исключать весь профиль пользователя или папки с ключами.

## Необязательные, но полезные

- WSL2 + Ubuntu для Linux Rust tests и shell tooling — после полной готовности Windows toolchain;
- Docker Desktop — только когда появятся локальные relay/registry integration environments;
- Wireshark — network diagnostics без расшифровки содержимого сообщений;
- `ktlint` — форматирование generated Kotlin без предупреждений;
- VS Code — быстрые правки; Android Studio остаётся основной IDE;
- NAS — дополнительная backup-копия, но не единственная.

Node.js, тяжёлая дискретная GPU, платная Visual Studio IDE и Docker сейчас не обязательны для сборки APU.

## Первичная настройка

- В UEFI включить CPU virtualization; BitLocker recovery key сохранить отдельно.
- Pagefile оставить system-managed только на исправном NVMe C:.
- Канонический workspace можно сохранить как `C:\APU-M8`; не размещать его в OneDrive.
- Настроить Git long paths и понятное имя/почту автора без сохранения секретов в репозитории.
- Подключить GitHub через Git Credential Manager/`gh`; токены не копировать в чаты и scripts.
- Release keystore и recovery material переносить только из проверенного зашифрованного backup;
  сделать минимум две offline-копии. Не публиковать passwords/private keys.
- После установки выполнить отдельный environment inventory gate: Java, SDK 35, adb, NDK, MSVC
  `cl.exe`/`link.exe`, Rust host toolchain, Android targets, cargo-ndk, Gradle и один clean test build.
- Затем проверить host `cargo test`, чтобы закрыть нынешний blocker отсутствующего MSVC linker.
