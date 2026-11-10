#!/usr/bin/env bash
# Independently check a draft or published release and, once public, the official site.
# Run on a GitHub-hosted runner with gh, Python, Android build-tools and network access.
set -euo pipefail
export PYTHONOPTIMIZE=0

: "${GITHUB_REPOSITORY:?missing repository}"
: "${ANDROID_HOME:?missing Android SDK}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
test -x "$BUILD_TOOLS/apksigner"
test -x "$BUILD_TOOLS/aapt"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/current" "$WORK/previous" "$WORK/site"
TAG="${APU_RELEASE_TAG:-$(git describe --tags --abbrev=0 HEAD)}"
export APU_RELEASE_TAG="$TAG"
gh api "repos/$GITHUB_REPOSITORY/releases/latest" > "$WORK/latest.json"
gh api "repos/$GITHUB_REPOSITORY/releases?per_page=100" > "$WORK/releases.json"
python3 - "$WORK" <<'PYCODE'
import json,os,re,sys
from pathlib import Path
w=Path(sys.argv[1]); latest=json.loads((w/'latest.json').read_text()); releases=json.loads((w/'releases.json').read_text())
tag=os.environ['APU_RELEASE_TAG']; candidates=[r for r in releases if r['tag_name']==tag]
assert len(candidates)==1, f'Expected one candidate release for {tag}'
current=candidates[0]
match=re.fullmatch(r'v([0-9]+)\.([0-9]+)\.([0-9]+)',tag); assert match, f'Unexpected tag: {tag}'
major,minor,patch=map(int,match.groups()); assert minor<=999 and patch<=999
code=major*1_000_000+minor*1_000+patch
if current['draft']:
 assert current['prerelease'] and not latest['draft'] and not latest['prerelease'] and latest['tag_name']!=tag
 previous=latest['tag_name']; stage='draft'
else:
 assert not current['prerelease'] and latest['tag_name']==tag, 'Candidate is not stable Latest'
 older=[r for r in releases if not r['draft'] and not r['prerelease'] and r['tag_name']!=tag
        and r.get('published_at') and r['published_at']<=current['published_at']]
 assert older, 'No previous stable release to compare signatures with'
 previous=max(older,key=lambda r:r['published_at'])['tag_name']; stage='public'
expected_stage=os.environ.get('APU_VERIFY_STAGE', '')
assert not expected_stage or expected_stage==stage, f'Expected {expected_stage} release, found {stage}'
notes=Path(f'docs/RELEASE_NOTES_{tag}.md').read_text(encoding='utf-8')
assert current['body']==notes, 'Release notes differ from the reviewed source'
assets={a['name']:a for a in current['assets']}; assert len(assets)==len(current['assets'])
for name in ['app-release.apk','app-full.apk','SHA256SUMS.txt','feature-packs.json']:
 a=assets[name]; assert a['state']=='uploaded' and 0<a['size']<=512*1024*1024
 assert re.fullmatch(r'sha256:[0-9a-f]{64}',a['digest'] or '')
(w/'candidate.json').write_text(json.dumps(current)); (w/'stage').write_text(stage)
(w/'tag').write_text(tag); (w/'previous-tag').write_text(previous); (w/'version-code').write_text(str(code))
print(f'Checking {stage} {tag} against previous stable {previous}')
PYCODE
TAG="$(cat "$WORK/tag")"
PREVIOUS="$(cat "$WORK/previous-tag")"
VERSION_CODE="$(cat "$WORK/version-code")"
git merge-base --is-ancestor "$PREVIOUS" "$TAG"
git merge-base --is-ancestor "$TAG" HEAD
gh release download "$TAG" --repo "$GITHUB_REPOSITORY" --dir "$WORK/current" \
  --pattern app-release.apk --pattern app-full.apk --pattern SHA256SUMS.txt --pattern feature-packs.json
gh release download "$PREVIOUS" --repo "$GITHUB_REPOSITORY" --dir "$WORK/previous" --pattern app-release.apk

# A successful checksum match alone is not enough: validate the signed APK's
# identity, version and the native core for each supported architecture.
for apk in app-release.apk app-full.apk; do
  "$BUILD_TOOLS/aapt" dump badging "$WORK/current/$apk" > "$WORK/badging.txt"
  python3 - "$WORK/badging.txt" "$WORK/current/$apk" "$TAG" "$VERSION_CODE" <<'PY'
import re,sys,zipfile
from pathlib import Path
badging=Path(sys.argv[1]).read_text().splitlines()[0]
fields=dict(re.findall(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'",badging))
assert fields.get('name')=='com.vladimir.messenger', badging
assert fields.get('versionName')==sys.argv[3], badging
assert fields.get('versionCode')==sys.argv[4], badging
with zipfile.ZipFile(sys.argv[2]) as apk:
 for abi in ('arm64-v8a','armeabi-v7a','x86_64'):
  assert apk.getinfo(f'lib/{abi}/libp2p_core.so').file_size>0, abi
print(Path(sys.argv[2]).name+': version and native architectures OK')
PY
done

# Verify Android 8's old signer and Android 9+'s rotated signer separately.
# Identical signer digests are a conservative update-compatibility check. An
# intentional future key rotation needs an explicit lineage review instead.
for range in 26-27 28-35; do
  minimum="${range%-*}"; maximum="${range#*-}"
  for which in previous light full; do
    case "$which" in
      previous) apk="$WORK/previous/app-release.apk" ;;
      light) apk="$WORK/current/app-release.apk" ;;
      full) apk="$WORK/current/app-full.apk" ;;
    esac
    "$BUILD_TOOLS/apksigner" verify --verbose --print-certs \
      --min-sdk-version "$minimum" --max-sdk-version "$maximum" "$apk" > "$WORK/signer-$which-$range.txt"
    sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: ([[:xdigit:]]{64})$/\1/p' \
      "$WORK/signer-$which-$range.txt" | LC_ALL=C sort -u > "$WORK/digests-$which-$range.txt"
    test -s "$WORK/digests-$which-$range.txt"
  done
  cmp "$WORK/digests-previous-$range.txt" "$WORK/digests-light-$range.txt"
  cmp "$WORK/digests-light-$range.txt" "$WORK/digests-full-$range.txt"
  echo "::notice title=APK signature compatibility::Previous release and both candidate APKs verify with matching signers for Android API $range"
done

python3 - "$WORK" <<'PY'
import hashlib,json,re,sys
from pathlib import Path
w=Path(sys.argv[1]); meta=json.loads((w/'candidate.json').read_text()); tag=(w/'tag').read_text()
assets={a['name']:a for a in meta['assets']}
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as src:
  for chunk in iter(lambda:src.read(1024*1024),b''): h.update(chunk)
 return h.hexdigest()
for name in ('app-release.apk','app-full.apk','SHA256SUMS.txt','feature-packs.json'):
 path=w/'current'/name; asset=assets[name]
 assert path.stat().st_size==asset['size'] and 'sha256:'+digest(path)==asset['digest'], name
light=digest(w/'current/app-release.apk'); full=digest(w/'current/app-full.apk')
expected=f'{light}  app-release.apk\n{full}  app-full.apk\n'
assert (w/'current/SHA256SUMS.txt').read_text()==expected, 'APK checksum file does not match both APKs'
manifest=json.loads((w/'current/feature-packs.json').read_text()); assert manifest['version']==tag
expected_pairs={(p,a) for p in ('translate','voice-text','face-effects') for a in ('arm64-v8a','armeabi-v7a','x86_64')}
seen=set(); lines=[]
for pack in manifest['packs']:
 pair=(pack['id'],pack['abi']); assert pair in expected_pairs and pair not in seen; seen.add(pair)
 name=f"feature-pack-{pair[0]}-{pair[1]}.zip"; assert pack['file']==name
 asset=assets[name]; sha=pack['sha256']; size=pack['size']
 assert asset['state']=='uploaded' and asset['size']==size and 0<size<=100*1024*1024
 assert re.fullmatch(r'[0-9a-f]{64}',sha) and asset['digest']=='sha256:'+sha
 lines.append(f'{name}\t{size}\t{sha}\n')
assert seen==expected_pairs
(w/'site-files.tsv').write_text(f'app-release.apk\t{assets["app-release.apk"]["size"]}\t{light}\n'+''.join(lines))
print('::notice title=Candidate files::GitHub APKs, checksum asset and nine package digests match release metadata')
PY

if [ "$(cat "$WORK/stage")" = draft ]; then
  printf '::notice title=Draft verified::%s: APKs, signing compatibility and assets verified; not published by this check\n' "$TAG"
  exit 0
fi

SITE=https://apu.clan.su/downloads
curl --fail --silent --show-error --location --retry 2 --connect-timeout 20 --max-time 60 \
  --max-filesize 1048576 "$SITE/latest.json" -o "$WORK/site/latest.json"
curl --fail --silent --show-error --location --retry 2 --connect-timeout 20 --max-time 60 \
  --max-filesize 1048576 "$SITE/feature-packs.json" -o "$WORK/site/feature-packs.json"
python3 - "$WORK" <<'PY'
import json,sys
from pathlib import Path
w=Path(sys.argv[1]); site=json.loads((w/'site/latest.json').read_text()); tag=(w/'tag').read_text()
light=next(line.split('  ')[0] for line in (w/'current/SHA256SUMS.txt').read_text().splitlines() if line.endswith('  app-release.apk'))
assert site['version']==tag and site['sha256']==light, 'Site latest metadata is stale or mismatched'
assert (w/'current/feature-packs.json').read_bytes()==(w/'site/feature-packs.json').read_bytes(), 'Site package manifest differs'
print('::notice title=Site metadata::Version, checksum and feature-pack manifest match GitHub')
PY

# Hash the bytes actually served by the site, not just its self-reported metadata.
while IFS=$'\t' read -r name size sha; do
  curl --fail --silent --show-error --location --retry 2 --connect-timeout 20 --max-time 300 \
    --max-filesize "$size" "$SITE/$name" -o "$WORK/site/$name"
  test "$(stat -c %s "$WORK/site/$name")" = "$size"
  printf '%s  %s\n' "$sha" "$WORK/site/$name" | sha256sum --check --status
  echo "::notice title=Site file verified::$name ($size bytes), SHA-256 $sha"
done < "$WORK/site-files.tsv"
[ "$(gh api "repos/$GITHUB_REPOSITORY/releases/latest" --jq .tag_name)" = "$TAG" ] || { echo "Latest changed during verification" >&2; exit 1; }
printf '::notice::Public %s APKs and site files verified against GitHub, previous signatures and checksums\n' "$TAG"
