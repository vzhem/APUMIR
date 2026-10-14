# Проверка выпуска v11.74.354 (2026-10-14)

## Почему выпуск отсутствовал

На начало проверки последний опубликованный релиз — v11.74.353. Тег
v11.74.354 отсутствовал, запусков Build Release APK для него не было.
Заметки выпуска сами по себе не запускают публикацию: workflow реагирует
на создание тега и после успешной сборки создаёт скрытый draft/prerelease,
который затем требуется отдельно опубликовать.

Изменения 354 уже объединены PR #45. Дерево merge-коммита
`3092462ffc4447edcfacbd5e059eb8ff31e464e4` полностью совпадает с деревом
`d3be0bdc789e95f6e2aac018e3eb63f982b48a34`, проверенным успешным Core compile
check https://github.com/vzhem/APUMIR/actions/runs/38506337086.
Кандидат является потомком v11.74.353 (проверено git merge-base).

Раннее падение Core compile check 38429592913 относилось к тесту
`newest_verified_route_survives_when_four_old_routes_have_more_hits`:
847 passed, 1 failed, 5 ignored, ожидалось 4 маршрута, осталось 2.
Причина — немонотонные временные метки в тесте. Исправление `b5d3eda8`
уже входит в v11.74.353. Это не неудачная релизная сборка 354.
Сведения о падении получены из комментария CI в PR #45; скачивание полных
логов Actions в этой среде недоступно.

## Проверки в текущей сессии

- check-online-helper.py: 4 PASS.
- check-diagnostics-report.py: 21 PASS.
- check-chat-startup.py: 7 PASS.
- check-pin-notice.py: 8 PASS.
- check-splash-scene.py: 8 PASS.
- check-settings-style.py: 15 PASS.
- check-saved-input.py: 4 PASS.
- check-peer-rank.py: 7 PASS.
- tools/worker/test-mqtt-bridge.mjs: 18/18 PASS.
- check-workflow-yaml.py: оба установленных workflow PASS.

Новая полная сборка: https://github.com/vzhem/APUMIR/actions/runs/38510925728.
Тег создан на указанном merge-коммите после разрешения владельца выпустить
релиз при отсутствии блокирующих ошибок. Публичные заметки показаны
владельцу до запуска и взяты из RELEASE_NOTES_v11.74.354.md.

## Границы проверки

Успех CI не подтверждает доставку между реальными устройствами и сетями.
Ограничения резервного маршрута, старых записей очереди и отдельного
обновления серверной части сохранены в публичных заметках. Изменений
серверного развёртывания в этой сессии нет.

## Итог публикации

- Build Release APK 38510925728: **SUCCESS**, build job 19m27s.
- Успешны Rust helper/direct transport tests, native core (all ABIs),
  генерация UniFFI, Android release unit tests, assembleRelease,
  story/community regression tests, переподпись и apksigner verify.
- APK: 96 545 665 байт.
- SHA-256 APK:
  `7196702c5020c50ca9aa1b03af46ebdd2bd23d8c7bb97f647c94b768777d6443`.
- SHA256SUMS.txt: 82 байта, SHA-256 самого файла:
  `de035880ecaad8b34e95a0b444d130f3982511a222bd3eba9959890f2d6a2335`.
- Проверка вложений без локального скачивания: из GitHub API взят digest
  APK; SHA-256 строки `<digest>  app-release.apk\n` точно равен digest
  checksum-вложения из того же API. Скачивание release-assets в этой среде
  недоступно; локальную установку/проверку APK на телефоне не проводили.
- Текст draft побайтно после trim совпал с согласованным файлом заметок.
- Опубликован 2026-10-14 04:41:12 UTC как **Latest**, draft=false,
  prerelease=false. Статус повторно проверен через releases/latest.
- URL: https://github.com/vzhem/APUMIR/releases/tag/v11.74.354

Правки прикладного кода для выпуска не потребовались. Отдельно остаются
неблокирующие предупреждения CI о старых версиях actions/setup-java и
Node.js в actions; они не привели к ошибке сборки.
