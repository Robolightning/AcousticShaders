#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
OUT="$ROOT/out/portable-classes"
DIST="$ROOT/dist"
rm -rf "$OUT"
mkdir -p "$OUT" "$DIST"
if [[ "${ACOUSTIC_PORTABLE_REUSE_PRODUCTION_CLASSES:-0}" == "1" ]]; then
  test -d "$ROOT/out/forge-classes" || { echo "ERROR: reusable production classes missing: $ROOT/out/forge-classes" >&2; exit 1; }
  python3 - "$ROOT/out/forge-classes" "$OUT" <<'PYCOPY'
from pathlib import Path
import shutil,sys
src=Path(sys.argv[1]); dst=Path(sys.argv[2])
for p in src.rglob('*.class'):
    rel=p.relative_to(src)
    posix=rel.as_posix()
    allowed=(
        posix.startswith('dev/acoustic/api/') or
        posix.startswith('dev/acoustic/platform/') or
        posix.startswith('dev/acoustic/core/') or
        (posix.startswith('dev/acoustic/mc1122/') and
         not posix.startswith('dev/acoustic/mc1122/forge/') and
         not posix.startswith('dev/acoustic/mc1122/mixin/'))
    )
    if allowed:
        target=dst/rel; target.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(p,target)
PYCOPY
else
  "$ROOT/tools/verification/1.12.2/scripts/dev-compile-jvm8.sh" "$OUT" "" \
    "$ROOT/acoustic-api/src/main/java" "$ROOT/acoustic-api/src/main/kotlin" \
    "$ROOT/acoustic-platform-api/src/main/java" "$ROOT/acoustic-platform-api/src/main/kotlin" \
    "$ROOT/acoustic-core/src/main/java" "$ROOT/acoustic-core/src/main/kotlin" \
    "$ROOT/minecraft-1.12.2/src/main/java" "$ROOT/minecraft-1.12.2/src/main/kotlin"
fi
python3 - "$OUT" "$DIST/acoustic-shaders-portable-0.3.jar" <<'PY'
from pathlib import Path
import sys,zipfile
base=Path(sys.argv[1]); out=Path(sys.argv[2])
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p in sorted(x for x in base.rglob('*.class') if x.is_file()):
        i=zipfile.ZipInfo(p.relative_to(base).as_posix(),(2026,8,22,0,0,0));i.compress_type=zipfile.ZIP_DEFLATED;i.external_attr=0o644<<16;z.writestr(i,p.read_bytes())
print('[PASS] portable Java 8 jar',out.name)
PY
