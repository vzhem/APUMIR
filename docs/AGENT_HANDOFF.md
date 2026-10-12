=== РУЧНОЙ ПЕРЕХОД ДЛЯ СЛЕДУЮЩЕГО АГЕНТА ===
Сессионная ветка: arena/4f0b31c5-apumir (не переключать ветку).
Тег v11.74.335 не перемещался; public Release ещё не создан.
Исходный unresolved reference исправлен fail-closed validator-ом; также исправлена Long-проверка chunk capacity, добавлены регрессионные тесты. Release workflow запускает целевые JVM suites до APK и сохраняет APK artifact после build/sign/checksum.
Последний успешный CI: run #790, ID 38238290496, commit a26bd06, workflow_dispatch. Targeted `ContentAddressedChunksTest` + `StoryPhotoPartsTest`, `:app:assembleRelease`, rotated-key re-signing, checksum и upload APK artifact завершились success. APK artifact `app-release.apk`, metadata size 30,078,909 bytes. Artifact download из sandbox завершился EOF на Azure artifact storage; локальная проверка APK не выполнена.
Владелец одобрил точные публичные заметки v11.74.336; файл `docs/RELEASE_NOTES_v11.74.336.md` должен быть включён в tagged commit. Следующий шаг: commit/push на эту ветку, создать tag v11.74.336 на свежем branch HEAD, дождаться tag workflow/draft, проверить assets/checksum и опубликовать. Не перемещать v11.74.335.
`gh workflow run` API отвечает 403, но ручной запуск из браузера доступен. Java локально нет. Нет проверки физического телефона, app/UI integration photo stories, battery/load/NAT/capacity. Не делать таких claims.
