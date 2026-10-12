# Защита линии релизов и проверка восстановления

## Что произошло

Тег v11.74.329 продолжал стабильную линию. v11.74.330 ответвился от v11.74.206 и пропустил
предыдущую стабильную историю: v206→v329 содержит 176 коммитов, 276 путей, +12 919/-2 823;
старый head PR #43 относительно v206 имел 13 путей и +1 186/-4. Сборка могла быть зелёной, но
содержала не ту линию продукта.

Исправленная линия восстановлена и опубликована в v11.74.337. Tag v337 указывает на `b8e6dcf`;
в fixed Arena-ветке после этого есть отдельная незрелая работа для будущего релиза (`c0e67e0`).
`main` намеренно не использовался как источник релиза и всё ещё указывает на v11.74.206.

## Автоматический блокирующий контроль

В `.github/workflows/build-release.yml` добавлена проверка для tag-triggered релизов:

1. CI получает полный Git history (`fetch-depth: 0`).
2. Через GitHub Releases API определяет последний опубликованный stable release, а не draft,
   prerelease или `main`.
3. Требует, чтобы tag-кандидат был потомком commit последнего stable tag. Иначе workflow падает
   до установки SDK/компиляции и не создаёт release.
4. В GitHub Actions summary выводит baseline, diff-stat и список изменённых путей, чтобы большие
   удаления или пропавшие подсистемы были видны до публикации.
5. Обычный `workflow_dispatch` остаётся диагностическим запуском; он не публикует release и
   пропускает проверку tag ancestry.

Этот gate добавлен **после** публикации v11.74.337 и проверен реальными tag-run для v11.74.338
и v11.74.339: обе ancestry-проверки прошли относительно v11.74.337. Перед каждым кандидатом всё
равно нужно проверять summary; ожидаемый baseline для v11.74.340 — v11.74.337.

## Правила перед каждым выпуском

- Сначала выяснить текущий GitHub Latest stable tag. Не считать `main` надёжным baseline: в этом
  репозитории он отстаёт от опубликованной линии.
- Создавать release tag только на commit, который есть в фиксированной release-ветке и проходит
  обязательную ancestry-проверку относительно Latest.
- Проверять summary diff: изменённые/удалённые пути, source diff-stat и ожидаемые продуктовые
  разделы. Любое необычно крупное удаление требует объяснения и отдельного review; зелёная
  компиляция сама по себе не доказывает, что нужные функции остались в APK.
- Tag CI должен собрать именно tagged tree, пройти Rust/helper и Android/JVM regression suites,
  подписать APK и сформировать checksum. Публиковать draft только после успешного workflow;
  переводить его в public Latest после проверки body и assets.
- Не переносить, не удалять и не переписывать уже опубликованные tags/releases/assets. Исправление
  выпускается новым номером, как v11.74.337.

## Рекомендуемые настройки GitHub (ещё не включены)

Это рекомендации владельцу, не утверждение о текущих настройках. Агент не менял branch/ruleset,
Actions, Environment или release permissions. После изменения подтвердить каждую настройку в
GitHub UI. `main` всё ещё на v11.74.206 и **не является release baseline**; защитить его полезно,
но защита сама по себе не восстанавливает историю и не разрешает выпускать от него.

### Ruleset A — `protect-main`

Target: `refs/heads/main` (default branch), даже пока он старый.

- Block force pushes and branch deletion.
- Require pull requests before merge; require the checks that are actually configured for PRs.
  Сейчас Rust PR workflow `Core compile check / cargo check + uniffi contract` покрывает только
  соответствующие Rust/tooling paths; не объявлять его Android/полной release-проверкой.
- Не добавлять обход для обычных Actions/GitHub Apps. Если solo-owner нужен обход, оставить его
  только владельцу/администратору и использовать явно, не service token.
- Не продвигать `main` в release source, пока владелец отдельно не согласует восстановление/merge
  истории и не проверит diff относительно v11.74.337.

### Ruleset B — `protect-release-line`

Target: долговечная release-ветка, которую владелец назначит после выбора процесса выпуска; она
должна быть основана на v11.74.337 и включать последующие проверенные изменения. На время Arena-сессии
изменения идут только в `arena/4f0b31c5-apumir`; это не повод создавать/переключать ветку или
считать временную Arena-ветку постоянной. Для назначенной release-ветки включить PR requirement,
обязательные build/test checks, запрет force-push/delete и review большого diff. Require linear
history — опционально, если обычный release-процесс не требует merge commits.

### Ruleset C — `protect-version-tags`

Target: tag name pattern `v*`.

- Restrict tag creation to release maintainers/owner.
- Restrict tag updates and deletions; не переносить уже опубликованные теги/assets.
- Оставить узкий admin bypass только для аварийной процедуры, документировать любое применение.
- Правило на теги дополняет, а не заменяет workflow ancestry gate: оно не проверяет исходный
  commit, тесты или содержание APK.

### Actions и release permissions

В **Settings → Actions → General** установить default `GITHUB_TOKEN` workflow permissions в
`Read repository contents`; выключить `Allow GitHub Actions to create and approve pull requests`,
если конкретный workflow действительно не требует этого. В **Workflow permissions** запретить
workflow просить write-права без необходимости. Для выпуска:

- build/test jobs — `contents: read`; отдельный release/publish job — только `contents: write`;
  не оставлять write permission на весь `build-release.yml`/`workflow_dispatch`.
- Сейчас `.github/workflows/build-release.yml` имеет top-level `contents: write`; ручной
  `workflow_dispatch` не создаёт Release, но запускается с теми же широкими workflow permissions
  и может использовать release-signing secrets. Это ещё не сведено к least privilege.
- Создать Environment `public-release` с required reviewer = владелец, разрешить deployment только
  для version tags `v*`, держать release-signing secrets только в этом Environment и запрашивать
  его только в tag-triggered publish job. Fork PR и ручной diagnostic build не должны видеть ключ.
  Сохранить создание hidden draft/prerelease; promotion в public Latest делать после ручной проверки.
- В **Actions → General → Actions permissions** выбрать allowlist конкретных reviewed actions;
  pin third-party actions к full commit SHA (tag — только обновляемый указатель). Не выдавать
  `id-token: write`, `pull-requests: write` или другие права release job, если шаг их не использует.

В отдельной копии хранить Git refs/tags и перечень release assets/checksums. Перед публичной
публикацией проверять source ancestry/diff, статус всего tag CI, release-notes body, APK assets и
checksum. Ни один из этих GitHub UI controls агентом не изменён на 2026-10-12.

## Неудачные сборки тегов v11.74.338 и v11.74.339 (2026-10-12)

Тег `v11.74.338` указывает на commit `8bf1736` и прошёл ancestry gate относительно `v11.74.337`,
но tag CI `38259661223` остановился в helper-routing тестах до сборки APK: две проверки `starts_with`
передавали `[u8; 4]` без ссылки. Исправление ссылки вошло в commit `97a1b48`, на который указывает
`v11.74.339`.

Tag CI `38260169011` для `.339` также прошёл ancestry gate и source guards, затем успешно выполнил
helper-routing tests, native build для всех ABI и генерацию UniFFI bindings. Он упал в
`:app:compileReleaseUnitTestKotlin`: test-fixture helper `DiagnosticsReportTest.facts()` не принимал
поля `onlineHelperParticipationEnabled` и `helperRoutes`, используемые новым regression test. Исправление
добавляет эти аргументы и передаёт их в `DiagnosticsFacts`. Для `.338` и `.339` Release/APK assets
не создавались; оба тега оставлены неизменными. Следующий кандидат — `v11.74.340`, который нужно снова
проверить относительно последнего опубликованного stable `v11.74.337`.

## Оставшиеся проверки

Ancestry check защищает от повторного ответвления от старого предка, но не от осознанного revert
или нарушения release-process. Поэтому остаются обязательны source diff review, feature checklist,
tag CI и реальные phone checks. Последние нужны для NAT, helper fallback, фотоисторий, батареи и
нагрузки; опубликованный APK и зелёный CI не являются доказательством этих физических сценариев.
