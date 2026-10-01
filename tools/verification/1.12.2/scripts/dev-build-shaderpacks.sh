#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
OUT="$ROOT/out/shaderpacks"
TRACKED="$ROOT/minecraft-1.12.2/src/forge/resources/assets/acousticshaders/shaderpacks/AcousticShaders-Reference-Hybrid.zip"
rm -rf "$OUT"
mkdir -p "$OUT"
python3 - "$ROOT" "$OUT" <<'PY'
from pathlib import Path
import sys, zipfile
root=Path(sys.argv[1]); out=Path(sys.argv[2])
src=root/'examples/reference-pack'; dst=out/'AcousticShaders-Reference-Hybrid.zip'
text_suffixes={'.json','.properties','.txt','.md','.toml','.yaml','.yml'}
def canonical_bytes(path):
    data=path.read_bytes()
    if path.suffix.lower() in text_suffixes:
        data=data.replace(b'\r\n',b'\n').replace(b'\r',b'\n')
    return data
with zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p in sorted(x for x in src.rglob('*') if x.is_file()):
        info=zipfile.ZipInfo(p.relative_to(src).as_posix(),(2026,8,22,0,0,0))
        info.compress_type=zipfile.ZIP_DEFLATED; info.external_attr=0o644<<16
        z.writestr(info,canonical_bytes(p))
print('[PASS] shaderpack',dst.name,dst.stat().st_size,'bytes', flush=True)
# Some constrained CI/container Python runtimes have exhibited a post-script shutdown spin.
# All file handles are closed at this point; exit immediately and deterministically.
import os
os._exit(0)
PY
mkdir -p "$(dirname "$TRACKED")"
if [[ "${ACOUSTIC_UPDATE_TRACKED_SHADERPACK:-0}" == 1 ]]; then
  cp "$OUT/AcousticShaders-Reference-Hybrid.zip" "$TRACKED"
  echo '[PASS] explicitly refreshed tracked Reference-Hybrid shaderpack bytes'
else
  [[ -f "$TRACKED" ]] || {
    echo 'ERROR: tracked Reference-Hybrid shaderpack missing; rerun with ACOUSTIC_UPDATE_TRACKED_SHADERPACK=1 to create it' >&2
    exit 1
  }
  python3 - "$OUT/AcousticShaders-Reference-Hybrid.zip" "$TRACKED" <<'PYVERIFY'
from pathlib import Path
import sys,zipfile
generated,tracked=map(Path,sys.argv[1:])

def contents(path):
    with zipfile.ZipFile(path,'r') as z:
        names=sorted(n for n in z.namelist() if not n.endswith('/'))
        return {name:z.read(name) for name in names}

g=contents(generated); t=contents(tracked)
if g.keys()!=t.keys():
    missing=sorted(g.keys()-t.keys())
    extra=sorted(t.keys()-g.keys())
    raise SystemExit('ERROR: tracked Reference-Hybrid shaderpack member set is stale; missing=%s extra=%s; rerun with ACOUSTIC_UPDATE_TRACKED_SHADERPACK=1 and commit the result' % (missing,extra))
for name in g:
    if g[name]!=t[name]:
        raise SystemExit('ERROR: tracked Reference-Hybrid shaderpack content is stale at %s; rerun with ACOUSTIC_UPDATE_TRACKED_SHADERPACK=1 and commit the result' % name)
print('[PASS] tracked Reference-Hybrid shaderpack semantically matches generated pack without rewriting tracked bytes')
PYVERIFY
fi
