#!/usr/bin/env python3
"""Контракт устойчивости экрана записи видео: «Записать видео» не убивает процесс.

Зачем отдельный контракт. 2026-11-06 владелец прислал: «Нажимаю записать видео
и приложение падает» — экран записи видеоаватара и видеоисторий падал СРАЗУ при
открытии, на всех телефонах (KingKong 9 / Android 14 и другие модели). Первым
на пути нажатия стоял `StoryVideoTracker`-конструктор с инициализацией ML Kit
(`FaceDetection.getClient`), дальше — запрос микрофона и открытие камеры; любой
сбой там уходил в смерть процесса, потому что перехватывался только Exception.
Ни сборка, ни JVM-тесты этого не видят: падение происходит в Compose/ML Kit на
устройстве. Поэтому код обязан деградировать текстом ошибки, и это проверяется
текстом исходников.

Что проверяется:

  1) детектор лица создаётся лениво и под `runCatching` (как клиенты ML Kit в
     `PersonSegmenter`/переводчике), `close()` не создаёт детектор ради закрытия,
     отказ детектора помечает фильтры лица недоступными, а не роняет экран;
  2) открытие камеры, старт записи и обработка снятого файла перехватывают
     `Throwable` (Error уровня инициализации — то, что и убивало процесс) и
     пишут `Log.w`;
  3) запрос микрофона защищён: отказ лаунчера — запись без звука с видимой
     причиной, а не падение;
  4) след последнего падения (`CrashTrace`) установлен в `MessengerApplication`
     и попадает в отчёт «Логи» — иначе повторное падение снова нечем разбирать;
  5) путь к детектору ОДИН и охраняемый пакетным гейтом (`FeaturePackRuntime`):
     в лёгкой базе нет `libface_detector_v2_jni.so`, ML Kit поднимает её в
     `<clinit>` на СВОЁМ пуле, где runCatching вызывающего бессилен (стек
     владельца 2026-11-06: `pool-7-thread-1`, UnsatisfiedLinkError). Поэтому
     ensureLoaded обязан поднять библиотеки пакета и ПРОБОВАТЬ `System.loadLibrary`
     тем же вызовом, что SDK; мимо гейта детектор не создаётся нигде.

Запуск:
    python3 scripts/ci/check-camera-crash.py
Код возврата: 0 — в порядке, 1 — нарушение.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def main() -> int:
    failures = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    tracker = read(JAVA / "data/story/StoryFaceTracker.kt")
    check(
        "private val detector: FaceDetector = newStoryFaceDetector()" not in tracker,
        "StoryFaceTracker: детектор снова создаётся в конструкторе — экран записи "
        "умирает при открытии (нужен ленивый detectorOrLoad с runCatching)",
    )
    for marker in (
        "private fun detectorOrLoad()",
        "runCatching { newStoryFaceDetector() }",
        "val detectorAvailable: Boolean",
        "synchronized(this) { detector.also { detector = null } }",
    ):
        check(marker in tracker, f"StoryFaceTracker: нет обязательного фрагмента {marker!r}")

    recorder = read(JAVA / "data/story/StoryVideoRecorder.kt")
    check(
        "faceTrackingAvailable = tracker.detectorAvailable" in recorder,
        "StoryVideoRecorder: faceTrackingAvailable обязан учитывать доступность "
        "модуля ML Kit (tracker.detectorAvailable)",
    )
    check(
        "faceTrackingAvailable = true" not in recorder,
        "StoryVideoRecorder: фильтры лица не должны включаться «вслепую», "
        "без проверки модуля ML Kit",
    )

    # 5) пакетный гейт: без нативного модуля детектор не создаётся ---------
    factory = re.search(
        r"internal fun newStoryFaceDetectorOrNull\(context: Context\)(.*?)\n\}",
        tracker,
        re.S,
    )
    check(
        factory is not None,
        "StoryFaceTracker: нет охраняемого фабрикатора newStoryFaceDetectorOrNull — "
        "прямой FaceDetection.getClient() без пакета убивает процесс на пуле ML Kit",
    )
    if factory is not None:
        body = factory.group(1)
        gate = body.find("FeaturePackRuntime.ensureLoaded")
        create = body.find("newStoryFaceDetector()")
        check(
            0 <= gate < create,
            "StoryFaceTracker: ensureLoaded обязан стоять ДО создания детектора "
            "(иначе ML Kit сам поднимет библиотеку и уронит процесс)",
        )
    check(
        "detector ?: newStoryFaceDetectorOrNull(context)" in tracker,
        "StoryFaceTracker: detectorOrLoad обязан идти через охраняемый фабрикатор",
    )
    baker = read(JAVA / "data/story/StoryFaceBaker.kt")
    check(
        "newStoryFaceDetectorOrNull(context)" in baker,
        "StoryFaceBaker: анализ записанного файла обязан идти через охраняемый "
        "фабрикатор детектора",
    )
    check(
        "newStoryFaceDetector()" not in baker,
        "StoryFaceBaker: прямой вызов newStoryFaceDetector() мимо пакетного гейта",
    )
    check(
        "suspend fun analyze(context: Context" in baker,
        "StoryFaceBaker: analyze обязан получать context — без него не проверить пакет",
    )
    runtime = read(JAVA / "data/featurepacks/FeaturePackRuntime.kt")
    probe = runtime.find("System.load(File(dir, lib).absolutePath)")
    load_library = runtime.find("System.loadLibrary(lib.removePrefix")
    check(
        0 <= probe < load_library,
        "FeaturePackRuntime: после System.load(путь пакета) обязана идти проба "
        "System.loadLibrary(имя) — тем же вызовом, которым SDK уронит процесс",
    )

    camera = read(JAVA / "ui/components/StoryVideoCamera.kt")
    check(
        len(re.findall(r"catch\s*\(\s*\w+\s*:\s*Throwable\s*\)", camera)) >= 3,
        "StoryVideoCamera: открытие камеры, старт записи и обработка файла "
        "обязаны перехватывать Throwable (ошибки уровня Error убивали процесс)",
    )
    for marker in (
        'Log.w("StoryVideoCamera", "Cannot open the camera"',
        'Log.w("StoryVideoCamera", "Cannot start recording"',
        'Log.w("StoryVideoCamera", "Cannot process the recorded video"',
        'Log.w("StoryVideoCamera", "Cannot request the microphone permission"',
    ):
        check(marker in camera, f"StoryVideoCamera: нет обязательного фрагмента {marker!r}")
    mic_guard = re.search(
        r"audioPermission\.launch\(Manifest\.permission\.RECORD_AUDIO\)(.*?)\}"
        r"\s*catch\s*\(\s*\w+\s*:\s*Throwable\s*\)",
        camera,
        re.S,
    )
    check(
        mic_guard is not None,
        "StoryVideoCamera: запрос микрофона обязан быть внутри try/catch — "
        "его сбой не должен ронять экран записи",
    )

    app = read(JAVA / "MessengerApplication.kt")
    check(
        "CrashTrace.install(this)" in app,
        "MessengerApplication: след падений (CrashTrace.install) не установлен — "
        "повторное падение снова нечем разбирать",
    )

    trace = read(JAVA / "data/diagnostics/CrashTrace.kt")
    check(
        'REPORT_HEADER = "[последнее падение]"' in trace,
        "CrashTrace: отчёт обязан показывать секцию «[последнее падение]»",
    )
    check(
        "catch (" not in trace,
        "CrashTrace: в этом файле только runCatching + onFailure { Log.w } — "
        "сам обработчик падений не имеет права молчать",
    )
    collector = read(JAVA / "data/diagnostics/TransferDiagnostics.kt")
    check(
        "CrashTrace.report(context.applicationContext)" in collector,
        "TransferDiagnostics: след падений обязан попадать в отчёт «Логи»",
    )

    if failures:
        print("check-camera-crash: НАРУШЕНИЯ")
        for item in failures:
            print(f"  ✖ {item}")
        return 1
    print("check-camera-crash: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
