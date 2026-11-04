#!/usr/bin/env python3
"""REST-передача больших файлов кусками не больше 100 МБ с паузой 5 минут.

Зачем: владелец потребовал готовую систему на случай, если на официальный
сайт (или другой REST-адрес) понадобится передать файл, который целиком
не проходит (лимит размера, тайм-ауты, перегрузка). Сейчас таких файлов
у нас нет — все пакеты функций меньше 40 МБ, — но система обязана быть.

Как устроено:

  1. Файл режется на куски по `--chunk-mb` мегабайт (по умолчанию 100,
     больше ставить нельзя — это потолок системы).
  2. Каждый кусок уходит отдельной REST-передачей: `PUT {base}/{имя.кусок}`
     с заголовком `Authorization: Bearer …` (токен — только из окружения,
     в репозиторий и в чат не попадает).
  3. Между кусками — пауза `--delay-seconds` (по умолчанию 300 секунд =
     5 минут), чтобы не перегружать приёмник. Паузы нет перед первым
     куском и после последнего.
  4. Рядом пишется манифест `{имя}.parts.json`: размер и SHA-256 каждого
     куска и файла целиком. Манифест уходит последним: его появление на
     приёмнике означает «файл передан полностью».
  5. Повторный запуск не передаёт куски заново: перед отправкой кусок
     проверяется `HEAD`-запросом (размер совпал — пропуск).

Сборка обратно (проверка целостности):

    python3 scripts/release/chunked_upload.py --reassemble <каталог-кусков> <файл>

Обычная передача:

    APU_UPLOAD_TOKEN=… python3 scripts/release/chunked_upload.py \
        --base https://example.org/upload app-release.apk

`--dry-run` печатает план (куски, паузы, адреса) без сети и без ожидания.
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

MAX_CHUNK_MB = 100
DEFAULT_DELAY_SECONDS = 300
MB = 1024 * 1024


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(4 * MB), b""):
            digest.update(block)
    return digest.hexdigest()


def part_name(file_name: str, index: int, total: int) -> str:
    return f"{file_name}.part{index + 1:03d}of{total:03d}"


def split_file(source: Path, out_dir: Path, chunk_mb: int) -> dict:
    """Нарезать файл на куски и вернуть манифест (словарь)."""
    if chunk_mb < 1 or chunk_mb > MAX_CHUNK_MB:
        raise ValueError(f"chunk size must be 1..{MAX_CHUNK_MB} MB, got {chunk_mb}")
    chunk_bytes = chunk_mb * MB
    out_dir.mkdir(parents=True, exist_ok=True)
    size = source.stat().st_size
    total = max(1, (size + chunk_bytes - 1) // chunk_bytes)
    parts = []
    with source.open("rb") as handle:
        for index in range(total):
            data = handle.read(chunk_bytes)
            name = part_name(source.name, index, total)
            (out_dir / name).write_bytes(data)
            parts.append(
                {"name": name, "index": index, "size": len(data), "sha256": sha256_bytes(data)}
            )
    manifest = {
        "file": source.name,
        "size": size,
        "sha256": sha256_file(source),
        "chunk_mb": chunk_mb,
        "parts": parts,
    }
    (out_dir / f"{source.name}.parts.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def reassemble(parts_dir: Path, target: Path) -> dict:
    """Собрать файл из кусков по манифесту и сверить SHA-256 целиком."""
    manifests = sorted(parts_dir.glob("*.parts.json"))
    if len(manifests) != 1:
        raise SystemExit(f"нужен ровно один *.parts.json в {parts_dir}, найдено {len(manifests)}")
    manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
    with target.open("wb") as out:
        for part in manifest["parts"]:
            blob = (parts_dir / part["name"]).read_bytes()
            if len(blob) != part["size"] or sha256_bytes(blob) != part["sha256"]:
                raise SystemExit(f"кусок {part['name']} не совпал с манифестом")
            out.write(blob)
    if target.stat().st_size != manifest["size"] or sha256_file(target) != manifest["sha256"]:
        raise SystemExit("собранный файл не совпал с манифестом целиком")
    return manifest


class HttpTransport:
    """REST-приёмник: PUT куска, HEAD для продолжения. Токен — из окружения."""

    def __init__(self, base: str, token: str = ""):
        self.base = base.rstrip("/")
        self.token = token

    def _request(self, method: str, name: str, data: bytes | None = None) -> tuple[int, dict]:
        request = urllib.request.Request(
            f"{self.base}/{name}", data=data, method=method
        )
        request.add_header("Content-Type", "application/octet-stream")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.status, dict(response.headers.items())
        except urllib.error.HTTPError as error:
            return error.code, {}

    def exists_with_size(self, name: str, size: int) -> bool:
        status, headers = self._request("HEAD", name)
        if status != 200:
            return False
        length = headers.get("Content-Length") or headers.get("content-length")
        return length is None or int(length) == size

    def put(self, name: str, data: bytes) -> int:
        status, _ = self._request("PUT", name, data)
        return status


def upload_parts(
    manifest: dict,
    parts_dir: Path,
    transport,
    delay_seconds: int = DEFAULT_DELAY_SECONDS,
    sleeper=time.sleep,
    log=print,
) -> list[str]:
    """Передать куски по одному с паузой между ними. Возвращает отправленные имена."""
    if delay_seconds < 0:
        raise ValueError("delay must be >= 0")
    sent: list[str] = []
    parts = manifest["parts"]
    for position, part in enumerate(parts):
        name = part["name"]
        if transport.exists_with_size(name, part["size"]):
            log(f"[{position + 1}/{len(parts)}] {name}: уже на приёмнике, пропуск")
            continue
        if sent:
            log(f"пауза {delay_seconds} с перед следующим куском…")
            sleeper(delay_seconds)
        data = (parts_dir / name).read_bytes()
        if sha256_bytes(data) != part["sha256"]:
            raise SystemExit(f"кусок {name} повреждён до отправки")
        status = transport.put(name, data)
        if status not in (200, 201, 204):
            raise SystemExit(f"приёмник ответил {status} на {name}")
        sent.append(name)
        log(f"[{position + 1}/{len(parts)}] {name}: передан ({part['size']} Б)")
    manifest_name = manifest["file"] + ".parts.json"
    manifest_blob = (parts_dir / manifest_name).read_bytes()
    if sent:
        log(f"пауза {delay_seconds} с перед манифестом…")
        sleeper(delay_seconds)
    status = transport.put(manifest_name, manifest_blob)
    if status not in (200, 201, 204):
        raise SystemExit(f"приёмник ответил {status} на манифест")
    log(f"манифест {manifest_name}: передан, файл на приёмнике целиком")
    return sent


def plan_lines(manifest: dict, base: str, delay_seconds: int) -> list[str]:
    lines = [
        f"файл {manifest['file']}: {manifest['size']} Б, sha256={manifest['sha256'][:16]}…",
        f"кусков: {len(manifest['parts'])} по не больше {manifest['chunk_mb']} МБ, "
        f"пауза между передачами {delay_seconds} с",
    ]
    for part in manifest["parts"]:
        lines.append(f"  PUT {base}/{part['name']} ({part['size']} Б)")
    lines.append(f"  PUT {base}/{manifest['file']}.parts.json (манифест, последним)")
    return lines


def self_test() -> int:
    """Проверка системы передач на локальном приёмнике: файл, куски, паузы,
    продолжение, сборка обратно. Сети наружу нет, ожидания нет (пауза 0)."""
    import http.server
    import tempfile
    import threading

    store: dict[str, bytes] = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_PUT(self):  # noqa: N802 - имя задаёт http.server
            length = int(self.headers.get("Content-Length") or 0)
            store[self.path.lstrip("/")] = self.rfile.read(length)
            self.send_response(200)
            self.end_headers()

        def do_HEAD(self):  # noqa: N802
            name = self.path.lstrip("/")
            if name in store:
                self.send_response(200)
                self.send_header("Content-Length", str(len(store[name])))
            else:
                self.send_response(404)
            self.end_headers()

        def log_message(self, *args):  # тишина в логе самопроверки
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "selftest.bin"
            source.write_bytes(os.urandom(2_600_000))
            parts_dir = root / "parts"
            manifest = split_file(source, parts_dir, chunk_mb=1)
            assert len(manifest["parts"]) == 3, manifest["parts"]
            base = f"http://127.0.0.1:{server.server_port}"
            sleeps: list[int] = []
            transport = HttpTransport(base)
            # Первый кусок «уже на приёмнике» — проверяем продолжение.
            transport.put(manifest["parts"][0]["name"], (parts_dir / manifest["parts"][0]["name"]).read_bytes())
            sent = upload_parts(manifest, parts_dir, transport, delay_seconds=300,
                                 sleeper=sleeps.append, log=lambda *a: None)
            assert len(sent) == 2 and sleeps == [300, 300], (sent, sleeps)
            # Собираем обратно из того, что принял «сервер».
            received = root / "received"
            received.mkdir()
            for name, blob in store.items():
                (received / name).write_bytes(blob)
            target = root / "joined.bin"
            reassemble(received, target)
            assert target.read_bytes() == source.read_bytes()
        print("self-test OK: нарезка ≤100 МБ, паузы между кусками, продолжение и сборка работают")
        return 0
    finally:
        server.shutdown()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", nargs="?", help="файл для передачи")
    parser.add_argument("--base", default=os.environ.get("APU_UPLOAD_REST_BASE", ""),
                        help="базовый REST-адрес приёмника")
    parser.add_argument("--chunk-mb", type=int, default=MAX_CHUNK_MB,
                        help=f"размер куска в МБ (потолок {MAX_CHUNK_MB})")
    parser.add_argument("--delay-seconds", type=int,
                        default=int(os.environ.get("APU_UPLOAD_DELAY_SECONDS", DEFAULT_DELAY_SECONDS)),
                        help="пауза между передачами кусков, секунд")
    parser.add_argument("--out-dir", default="", help="каталог кусков (по умолчанию рядом с файлом)")
    parser.add_argument("--dry-run", action="store_true", help="только план, без сети и ожидания")
    parser.add_argument("--reassemble", nargs=2, metavar=("PARTS_DIR", "TARGET"),
                        help="собрать файл из кусков и сверить SHA-256")
    parser.add_argument("--self-test", action="store_true",
                        help="проверить систему передач на локальном приёмнике")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    if args.reassemble:
        manifest = reassemble(Path(args.reassemble[0]), Path(args.reassemble[1]))
        print(f"собрано: {manifest['file']} ({manifest['size']} Б), SHA-256 совпал")
        return 0

    if not args.file:
        parser.error("нужен файл (или --reassemble)")
    source = Path(args.file)
    if not source.is_file():
        print(f"file not found: {source}")
        return 2
    out_dir = Path(args.out_dir) if args.out_dir else source.parent / (source.name + ".parts")
    manifest = split_file(source, out_dir, args.chunk_mb)
    base = args.base.rstrip("/")
    for line in plan_lines(manifest, base or "{APU_UPLOAD_REST_BASE}", args.delay_seconds):
        print(line)
    if args.dry_run:
        print("dry-run: сеть и паузы пропущены")
        return 0
    if not base:
        print("не задан REST-адрес: --base или APU_UPLOAD_REST_BASE")
        return 2
    token = os.environ.get("APU_UPLOAD_TOKEN", "")
    upload_parts(manifest, out_dir, HttpTransport(base, token), args.delay_seconds)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
