#!/usr/bin/env python3
"""Offline tests for the release verifier using fake GitHub/site/Android tools."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('verify-published-release.sh').resolve()
TAG = 'v1.2.3'
PREVIOUS = 'v1.2.2'
NOTES = 'Reviewed release notes.\n'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64')
PACKS = ('translate', 'voice-text', 'face-effects')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def asset(path):
    data = path.read_bytes()
    return {'name': path.name, 'size': len(data), 'state': 'uploaded', 'digest': 'sha256:' + sha(data)}


class VerifyReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.mock = self.root / 'mock'
        self.current = self.mock / 'current'
        self.previous = self.mock / 'previous'
        self.site = self.mock / 'site'
        self.bin = self.mock / 'bin'
        self.tools = self.mock / 'sdk/build-tools/35.0.0'
        for directory in (self.current, self.previous, self.site, self.bin, self.tools, self.root / 'docs'):
            directory.mkdir(parents=True)
        (self.root / 'docs' / f'RELEASE_NOTES_{TAG}.md').write_text(NOTES)
        for path, content in ((self.current / 'app-release.apk', 'light'),
                              (self.current / 'app-full.apk', 'full'),
                              (self.previous / 'app-release.apk', 'previous')):
            with zipfile.ZipFile(path, 'w') as archive:
                for abi in ABIS:
                    archive.writestr(f'lib/{abi}/libp2p_core.so', abi + content)
        entries = []
        assets = []
        for pack in PACKS:
            for abi in ABIS:
                name = f'feature-pack-{pack}-{abi}.zip'
                data = (pack + abi).encode()
                (self.current / name).write_bytes(data)
                (self.site / name).write_bytes(data)
                entries.append({'id': pack, 'abi': abi, 'file': name, 'size': len(data), 'sha256': sha(data)})
                assets.append(asset(self.current / name))
        manifest = json.dumps({'version': TAG, 'packs': entries}, indent=2) + '\n'
        (self.current / 'feature-packs.json').write_text(manifest)
        (self.site / 'feature-packs.json').write_text(manifest)
        light = (self.current / 'app-release.apk').read_bytes()
        full = (self.current / 'app-full.apk').read_bytes()
        (self.current / 'SHA256SUMS.txt').write_text(
            f'{sha(light)}  app-release.apk\n{sha(full)}  app-full.apk\n')
        (self.site / 'app-release.apk').write_bytes(light)
        (self.site / 'latest.json').write_text(json.dumps({'version': TAG, 'sha256': sha(light)}))
        for name in ('app-release.apk', 'app-full.apk', 'feature-packs.json', 'SHA256SUMS.txt'):
            assets.append(asset(self.current / name))
        self.candidate = {'tag_name': TAG, 'published_at': '2026-01-02T00:00:00Z',
                          'draft': False, 'prerelease': False, 'body': NOTES, 'assets': assets}
        self.old = {'tag_name': PREVIOUS, 'published_at': '2026-01-01T00:00:00Z',
                    'draft': False, 'prerelease': False}
        self.metadata(self.candidate, self.old)
        self.command(self.bin / 'git', '[[ ${MOCK_BAD_ANCESTRY:-} != yes ]]\n')
        self.command(self.bin / 'gh', '''
if [[ "$1" = api ]]; then
  if [[ "$2" = */releases/latest ]]; then
    if [[ "${3:-}" = --jq ]]; then python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["tag_name"])' "$MOCK_ROOT/latest.json"
    else cat "$MOCK_ROOT/latest.json"; fi
  else cat "$MOCK_ROOT/releases.json"; fi
elif [[ "$1" = release && "$2" = download ]]; then
  tag="$3"; shift 3; dest=""; pats=()
  while (($#)); do case "$1" in --dir) dest="$2";shift 2;; --pattern) pats+=("$2");shift 2;; --repo) shift 2;; *) exit 2;; esac; done
  src="$MOCK_ROOT/current"; [[ "$tag" != v1.2.2 ]] || src="$MOCK_ROOT/previous"
  for name in "${pats[@]}"; do cp "$src/$name" "$dest/$name"; done
else exit 2; fi
''')
        self.command(self.bin / 'curl', '''
url=""; out=""
while (($#)); do case "$1" in --retry|--connect-timeout|--max-time|--max-filesize) shift 2;; -o) out="$2";shift 2;; https://*) url="$1";shift;; *) shift;; esac; done
cp "$MOCK_ROOT/site/${url##*/}" "$out"
''')
        self.command(self.tools / 'aapt', "echo \"package: name='com.vladimir.messenger' versionCode='1002003' versionName='v1.2.3'\"\n")
        self.command(self.tools / 'apksigner', '''
if [[ "${MOCK_BAD_SIGNER:-}" = yes && "${*: -1}" = */current/* ]]; then
 printf 'Signer #1 certificate SHA-256 digest: %064d\\n' 1
else printf 'Signer #1 certificate SHA-256 digest: %064d\\n' 0; fi
''')
        self.env = os.environ.copy()
        self.env.update(MOCK_ROOT=str(self.mock), ANDROID_HOME=str(self.mock / 'sdk'),
                        GITHUB_REPOSITORY='example/repo', APU_RELEASE_TAG=TAG,
                        PATH=str(self.bin) + os.pathsep + self.env.get('PATH', ''))

    def command(self, path, body):
        path.write_text('#!/usr/bin/env bash\nset -eu\n' + body)
        path.chmod(0o755)

    def metadata(self, latest, other):
        (self.mock / 'latest.json').write_text(json.dumps(latest))
        (self.mock / 'releases.json').write_text(json.dumps([self.candidate, self.old]))

    def run_check(self, **extra):
        return subprocess.run(['bash', str(SCRIPT)], cwd=self.root,
                              env={**self.env, **extra}, text=True, capture_output=True, timeout=30)

    def test_public_release_and_site(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('Public v1.2.3 APKs and site files verified', result.stdout)
        self.assertEqual(result.stdout.count('::notice title=Site file verified::'), 10)

    def test_draft_skips_site_and_never_publishes(self):
        self.candidate.update(draft=True, prerelease=True, published_at=None)
        self.metadata(self.old, self.candidate)
        (self.site / 'latest.json').unlink()  # draft verification must not read the site
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('not published by this check', result.stdout)

    def test_signer_mismatch_rejected(self):
        self.assertNotEqual(self.run_check(MOCK_BAD_SIGNER='yes').returncode, 0)

    def test_same_size_site_corruption_rejected(self):
        apk = self.site / 'app-release.apk'
        content = bytearray(apk.read_bytes()); content[30] ^= 1; apk.write_bytes(content)
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_checksum_asset_corruption_rejected(self):
        path = self.current / 'SHA256SUMS.txt'
        path.write_text(path.read_text().replace('app-full.apk', 'app-fool.apk'))
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_changed_reviewed_notes_rejected(self):
        (self.root / 'docs' / f'RELEASE_NOTES_{TAG}.md').write_text('Different notes\n')
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_missing_emulator_abi_rejected(self):
        path = self.current / 'app-release.apk'
        with zipfile.ZipFile(path, 'w') as archive:
            for abi in ('arm64-v8a', 'armeabi-v7a'):
                archive.writestr(f'lib/{abi}/libp2p_core.so', 'native')
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_unrelated_history_rejected(self):
        self.assertNotEqual(self.run_check(MOCK_BAD_ANCESTRY='yes').returncode, 0)

    def test_duplicate_asset_names_rejected(self):
        self.candidate['assets'].append(self.candidate['assets'][0].copy())
        self.metadata(self.candidate, self.old)
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_stale_site_metadata_rejected(self):
        (self.site / 'latest.json').write_text(json.dumps({'version': PREVIOUS, 'sha256': '0' * 64}))
        self.assertNotEqual(self.run_check().returncode, 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
