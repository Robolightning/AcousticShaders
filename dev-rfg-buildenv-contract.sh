#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
for script in dev-rfg-buildenv-pack.sh dev-rfg-buildenv-import.sh; do bash -n "$script"; done
python3 - <<'PYCODE'
from pathlib import Path
p=Path('tools/verification/1.12.2/dev-tools/rfg_buildenv_manifest.py')
compile(p.read_text(encoding='utf-8'), str(p), 'exec')
PYCODE
for script in dev-rfg-buildenv-pack.sh dev-rfg-buildenv-import.sh; do
  grep -F '94702da47e2c0d626986a42bd8124c63e52afc2a' "$script" >/dev/null
  grep -F 'bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531' "$script" >/dev/null
done
grep -F 'caches/modules-2' dev-rfg-buildenv-pack.sh >/dev/null
grep -F 'caches/retro_futura_gradle' dev-rfg-buildenv-pack.sh >/dev/null
grep -F 'GRADLE_USER_HOME/jdks' dev-rfg-buildenv-pack.sh >/dev/null
grep -F -- '--preflight-only' dev-rfg-buildenv-import.sh >/dev/null
TMP="$(mktemp -d "${TMPDIR:-/tmp}/acoustic-rfg-buildenv-contract.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/payload/cache" "$TMP/payload/jdk/bin"
printf 'alpha\n' > "$TMP/payload/cache/a.txt"
printf 'java\n' > "$TMP/payload/jdk/bin/java"
SYMLINK_FIXTURE=0
if python3 - "$TMP/payload/link-to-a" <<'PYCODE'
import os
from pathlib import Path
import sys
path = Path(sys.argv[1])
try:
    os.symlink('../cache/a.txt', path)
except (OSError, NotImplementedError):
    raise SystemExit(1)
PYCODE
then
  SYMLINK_FIXTURE=1
else
  printf '%s\n' '[INFO] host cannot create symlinks; skipping filesystem symlink fixture'
fi
python3 tools/verification/1.12.2/dev-tools/rfg_buildenv_manifest.py create "$TMP/payload" "$TMP/MANIFEST.tsv" >/dev/null
if [[ "$SYMLINK_FIXTURE" == 1 ]]; then
  grep -F $'L\t14\t' "$TMP/MANIFEST.tsv" | grep -F $'link-to-a\t../cache/a.txt' >/dev/null
else
  python3 - "$TMP/synthetic-link-manifest.tsv" <<'PYCODE'
import hashlib
import importlib.util
from pathlib import Path
import sys
manifest = Path(sys.argv[1])
target = '../cache/a.txt'
digest = hashlib.sha256(target.encode('utf-8')).hexdigest()
manifest.write_text(
    'ACOUSTIC-RFG-BUILDENV-MANIFEST-V1\n'
    f'L\t{len(target.encode("utf-8"))}\t{digest}\tlink-to-a\t{target}\n',
    encoding='utf-8',
)
spec = importlib.util.spec_from_file_location('rfg_buildenv_manifest', 'tools/verification/1.12.2/dev-tools/rfg_buildenv_manifest.py')
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)
parsed = module.parse_manifest(manifest)
assert parsed['link-to-a'] == ('L', len(target.encode('utf-8')), digest, target)
PYCODE
fi
python3 tools/verification/1.12.2/dev-tools/rfg_buildenv_manifest.py verify "$TMP/payload" "$TMP/MANIFEST.tsv" >/dev/null
# Prove the exporter pattern can bundle an exact detached commit without mutating
# the authoritative worktree: fetch it into a temporary named ref, bundle, clone, verify.
mkdir -p "$TMP/git-work"
git -C "$TMP/git-work" init -q -b main
git -C "$TMP/git-work" config user.name 'RFG Contract'
git -C "$TMP/git-work" config user.email 'rfg-contract@example.invalid'
printf 'rfg\n' > "$TMP/git-work/file.txt"
git -C "$TMP/git-work" add file.txt
git -C "$TMP/git-work" commit -q -m fixture
FIXTURE_COMMIT="$(git -C "$TMP/git-work" rev-parse HEAD)"
git init -q --bare "$TMP/git-bare"
git -C "$TMP/git-bare" fetch -q "$TMP/git-work" "$FIXTURE_COMMIT:refs/heads/rfg-1.4.9"
git -C "$TMP/git-bare" bundle create "$TMP/rfg.bundle" refs/heads/rfg-1.4.9
git bundle verify "$TMP/rfg.bundle" >/dev/null
git clone -q -b rfg-1.4.9 "$TMP/rfg.bundle" "$TMP/git-clone"
[[ "$(git -C "$TMP/git-clone" rev-parse HEAD)" == "$FIXTURE_COMMIT" ]] || { echo 'ERROR: synthetic RFG Git bundle roundtrip changed commit' >&2; exit 1; }
printf 'tamper\n' >> "$TMP/payload/cache/a.txt"
if python3 tools/verification/1.12.2/dev-tools/rfg_buildenv_manifest.py verify "$TMP/payload" "$TMP/MANIFEST.tsv" >/dev/null 2>&1; then
  echo 'ERROR: RFG buildenv manifest accepted tampered payload' >&2
  exit 1
fi
printf '%s\n' '[PASS] exact offline RFG buildenv pack/import + manifest contract'
