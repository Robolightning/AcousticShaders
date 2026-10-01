#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
python3 - <<'PY'
from pathlib import Path
import hashlib

root = Path.cwd()
inputs = [
    Path('acoustic-api/src/main'),
    Path('acoustic-platform-api/src/main'),
    Path('acoustic-core/src/main'),
    Path('minecraft-1.12.2/src/main'),
    Path('minecraft-1.12.2/src/forge'),
    Path('acoustic-tools/src/main'),
    Path('acoustic-testkit/src/main'),
    Path('examples/reference-pack'),
    Path('examples/material-resource-pack'),
    Path('tools/verification/1.12.2/windows'),
]
files = []
for base in inputs:
    if not base.exists():
        raise SystemExit(f'missing release input path: {base}')
    files.extend(p for p in base.rglob('*') if p.is_file())
# Generated ZIPs are ignored and are never authoritative release inputs.
files = sorted(set(files), key=lambda p: p.as_posix())
h = hashlib.sha256()
for p in files:
    rel = p.as_posix().encode('utf-8')
    data = (root / p).read_bytes()
    h.update(len(rel).to_bytes(8, 'big'))
    h.update(rel)
    h.update(len(data).to_bytes(8, 'big'))
    h.update(data)
print(h.hexdigest())
PY
