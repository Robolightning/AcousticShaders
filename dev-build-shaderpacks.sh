#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/out/shaderpacks"
rm -rf "$OUT"
mkdir -p "$OUT"
python3 - "$ROOT" "$OUT" <<'PY'
from pathlib import Path
import sys, zipfile
root=Path(sys.argv[1]); out=Path(sys.argv[2])
src=root/'examples/reference-pack'; dst=out/'AcousticShaders-Reference-Hybrid.zip'
with zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p in sorted(x for x in src.rglob('*') if x.is_file()):
        info=zipfile.ZipInfo(p.relative_to(src).as_posix(),(2026,8,22,0,0,0))
        info.compress_type=zipfile.ZIP_DEFLATED; info.external_attr=0o644<<16
        z.writestr(info,p.read_bytes())
print('[PASS] shaderpack',dst.name,dst.stat().st_size,'bytes', flush=True)
# Some constrained CI/container Python runtimes have exhibited a post-script shutdown spin.
# All file handles are closed at this point; exit immediately and deterministically.
import os
os._exit(0)
PY
mkdir -p "$ROOT/minecraft-1.12.2/src/forge/resources/assets/acousticshaders/shaderpacks"
rm -f "$ROOT/minecraft-1.12.2/src/forge/resources/assets/acousticshaders/shaderpacks/"*.zip
cp "$OUT/AcousticShaders-Reference-Hybrid.zip" "$ROOT/minecraft-1.12.2/src/forge/resources/assets/acousticshaders/shaderpacks/AcousticShaders-Reference-Hybrid.zip"
