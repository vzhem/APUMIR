#!/usr/bin/env python3
"""Контракт приглашения: простое, как у топовых мессенджеров, и без личного в ссылке.

Владелец 2026-10-30 (скрин приглашения в Telegram): «Сделай проще приглашение,
как у топовых мессенджеров» и «`vzhem` и `1985` — личная информация, в видимом
тексте их быть не должно».

Что проверяем:

1. Текст приглашения живёт одной чистой функцией (`util/InviteText.kt`) и это
   одно предложение плюс ссылка отдельной строкой: без «вставь ссылку»,
   без «Скачать APU», без установочной ссылки в тексте (файл приложен ниже).
2. В адресе сервиса нет личных данных: хост берётся из `data.link.ServiceHost`
   и равен `p2p-relay.apuchat.workers.dev`.
3. Хост совпадает везде, где Android и Rust не умеют ссылаться на Kotlin:
   `AndroidManifest.xml` (intent-filter) и `rust-core` (мост MQTT).
4. Старого поддомена (`1985vzhem.workers.dev`) не осталось ни в одном месте.
5. Правило проверено JVM-тестом `InviteTextTest`.
6. Контракт включён в сборку релиза.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
NEW_HOST = "p2p-relay.apuchat.workers.dev"
OLD_HOST_PATTERN = re.compile(r"1985vzhem\.workers\.dev")


def read(path):
    return path.read_text(encoding="utf-8")


def code_only(source):
    """Без комментариев: в пояснениях слова прошлых версий остаются нарочно."""
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    return "\n".join(line.split("//")[0] for line in source.splitlines())


class InviteTextContractTest(unittest.TestCase):
    def setUp(self):
        self.text = code_only(read(MAIN / "util/InviteText.kt"))
        self.app_share = code_only(read(MAIN / "util/AppShare.kt"))
        self.host = read(MAIN / "data/link/ServiceHost.kt")

    def test_the_invite_is_one_sentence_and_one_link(self):
        self.assertIn('"Привет! Это $who. Я в APU — давай общаться.\\n\\n"', self.text)
        self.assertIn("fun personal(", self.text)

    def test_the_invite_carries_no_instructions_and_no_install_line(self):
        for forbidden in ("вставь", "вставьте", "Добавь меня в контакты", "Скачать APU", "скачай"):
            self.assertNotIn(forbidden, self.text, "в тексте приглашения лишняя инструкция")
        # Установочной ссылки в тексте нет: APK приложен к сообщению ниже.
        self.assertNotIn("/update/apk", self.text)

    def test_app_share_uses_the_shared_text(self):
        self.assertIn("InviteText.personal(displayName, contactLink)", self.app_share)
        self.assertIn("InviteText.community(", self.app_share)
        self.assertNotIn("Добавь меня в контакты", self.app_share)

    def test_the_service_host_has_no_personal_data(self):
        host_value = re.search(r'const val HOST = "([^"]+)"', self.host)
        self.assertIsNotNone(host_value)
        self.assertEqual(NEW_HOST, host_value.group(1))
        self.assertNotIn("vzhem", host_value.group(1))
        self.assertNotIn("1985", host_value.group(1))

    def test_every_place_that_cannot_read_kotlin_uses_the_same_host(self):
        manifest = read(ROOT / "android-app/app/src/main/AndroidManifest.xml")
        self.assertIn('android:host="' + NEW_HOST + '"', manifest)
        rust = read(ROOT / "rust-core/src/network/mqtt_transport.rs")
        self.assertIn("wss://" + NEW_HOST + "/mqtt", rust)

    def test_the_old_personal_subdomain_is_gone(self):
        offenders = []
        for base in (
            ROOT / "android-app/app/src/main",
            ROOT / "android-app/app/src/test",
            ROOT / "rust-core/src",
            ROOT / "tools/worker",
        ):
            for path in base.rglob("*"):
                if path.suffix not in {".kt", ".xml", ".rs", ".py", ".ps1", ".js", ".mjs"}:
                    continue
                # UpdateLinksTest держит адрес прежнего домена нарочно: это
                # пример ответа, который приложение обязано отвергнуть.
                if path.name == "UpdateLinksTest.kt":
                    continue
                if OLD_HOST_PATTERN.search(read(path)):
                    offenders.append(str(path.relative_to(ROOT)))
        self.assertEqual([], offenders, "старый личный поддомен остался в коде")

    def test_the_service_never_hands_out_a_link_on_a_previous_domain(self):
        # Владелец 2026-10-30: ответ сервиса принёс apk_url прежнего домена —
        # адрес лежал в кэше вместе с версией. Теперь адрес собирается от
        # домена текущего запроса, а в кэш не попадает вовсе.
        worker = read(ROOT / "tools/worker/p2p_relay_worker.js")
        self.assertIn("function withCurrentApkUrl(cached, request)", worker)
        self.assertIn('payload.apk_url = new URL("/update/apk", request.url).toString();', worker)
        self.assertIn("delete stored.apk_url;", worker)
        # Один вход в кэш — последняя надежда после двух сетевых попыток.
        self.assertEqual(1, len(re.findall(r"await serveLatestCache\(request, env, ", worker)))

    def test_a_stale_version_cannot_stick_because_of_github_limits(self):
        # Владелец 2026-10-30: сервис отдавал версию недельной давности, хотя
        # релизы выходили каждый день. Лимит GitHub API не должен замораживать
        # номер версии: настоящий тег берём редиректом /releases/latest.
        worker = read(ROOT / "tools/worker/p2p_relay_worker.js")
        self.assertIn("async function latestTagFromRedirect()", worker)
        self.assertIn('redirect: "manual"', worker)
        self.assertIn("const tag = await latestTagFromRedirect();", worker)
        self.assertIn('"X-Apu-Cache": "redirect"', worker)

    def test_the_app_downloads_only_from_our_host(self):
        checker = read(MAIN / "service/UpdateChecker.kt")
        self.assertIn("UpdateLinks.apkDownloadUrl(", checker)
        links = read(MAIN / "service/UpdateLinks.kt")
        self.assertIn("if (!parsed.host.equals(ServiceHost.HOST, ignoreCase = true)) return fallback",
                      links)
        self.assertIn("if (!parsed.scheme.equals(\"https\", ignoreCase = true)) return fallback", links)

    def test_the_worker_serves_readable_russian(self):
        # 2026-10-30: воркер задеплоили, вставив файл через буфер обмена Windows
        # PowerShell 5.1. Get-Content без -Encoding прочитал UTF-8 как ANSI, и
        # страницы приглашений показывали «РћС‚РєСЂС‹С‚СЊ РІ APU». Файл в
        # репозитории обязан оставаться чистым UTF-8, а JSON - с charset.
        worker_path = ROOT / "tools/worker/p2p_relay_worker.js"
        raw = worker_path.read_bytes()
        self.assertFalse(raw.startswith(b"\xef\xbb\xbf"), "BOM ломает вставку в дашборд")
        text = raw.decode("utf-8")  # иначе исключение: файл перекодирован
        for marker in ("Открыть в APU", "счётчики сбрасываются при спячке объекта"):
            self.assertIn(marker, text)
        self.assertNotIn("\ufffd", text, "в воркере битые символы")
        self.assertNotIn("РЎ", text, "в воркере мойибаке вместо русского текста")
        self.assertIn('"Content-Type": "application/json; charset=utf-8"', text)

    def test_the_rule_has_a_unit_test(self):
        test = read(
            ROOT / "android-app/app/src/test/java/com/vladimir/messenger/util/InviteTextTest.kt"
        )
        self.assertIn("`the invite carries no instructions and no install line`", test)
        self.assertIn("`the invite shows no personal data in the visible text`", test)
        links_test = read(
            ROOT / "android-app/app/src/test/java/com/vladimir/messenger/service/UpdateLinksTest.kt"
        )
        self.assertIn("`a link from the previous domain is replaced`", links_test)

    def test_contract_is_wired_into_release_build(self):
        workflow = read(ROOT / ".github/workflows/build-release.yml")
        self.assertIn("python3 scripts/ci/check-invite-text.py", workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)
