#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"

PRODUCTION_ROOTS=(
  acoustic-api/src/main acoustic-platform-api/src/main acoustic-core/src/main
  minecraft-1.12.2/src/main minecraft-1.12.2/src/forge
  acoustic-tools/src/main acoustic-testkit/src/main
)

# Release production remains Kotlin-only; Java is allowed only in external API test/contract stubs.
if find "${PRODUCTION_ROOTS[@]}" -type f -name '*.java' -print -quit | grep -q .; then
  echo 'ERROR: own production Java file found' >&2
  find "${PRODUCTION_ROOTS[@]}" -type f -name '*.java' -print >&2
  exit 1
fi

# Do not ship unresolved marker comments in production. Use Python rather than ripgrep so this gate
# runs unchanged in stock Git Bash, CI and the exact Windows release environment.
python3 - "${PRODUCTION_ROOTS[@]}" <<'PYMARKERS'
from pathlib import Path
import re,sys
pattern=re.compile(r'(//|/\*|\*)\s*(TODO|FIXME|HACK|XXX)([(:\s]|$)',re.I)
allowed={'.kt','.kts','.java','.cu','.cl'}
found=[]
for root in map(Path,sys.argv[1:]):
    if not root.exists():
        continue
    for path in root.rglob('*'):
        if not path.is_file() or path.suffix.lower() not in allowed:
            continue
        for lineno,line in enumerate(path.read_text(encoding='utf-8').splitlines(),1):
            if pattern.search(line):
                found.append(f'{path.as_posix()}:{lineno}:{line.strip()}')
if found:
    print('\n'.join(found))
    raise SystemExit('ERROR: unresolved TODO/FIXME/HACK/XXX marker in production')
PYMARKERS

# No editor/merge/temporary artefacts may be tracked.
if git ls-files | grep -E '(^|/)(\.DS_Store|Thumbs\.db)$|(~|\.bak|\.orig|\.rej|\.tmp)$|(^|/)(out|dist|build|\.gradle|\.idea|\.vscode)/' >/tmp/acoustic-hygiene-junk.$$; then
  echo 'ERROR: generated/editor junk is tracked:' >&2
  cat /tmp/acoustic-hygiene-junk.$$ >&2
  rm -f /tmp/acoustic-hygiene-junk.$$
  exit 1
fi
rm -f /tmp/acoustic-hygiene-junk.$$

# Public tooling must not embed the maintainer's private machine paths or ChatGPT workspace paths.
python3 - <<'PYPATHS'
from pathlib import Path
import subprocess
excluded={
    'LICENSE','README.md','README.ru.md','CHANGELOG.md','tools/verification/1.12.2/scripts/dev-release-hygiene.sh',
    'minecraft-1.12.2/src/forge/resources/mcmod.info',
}
needles=('C:\\Users\\Robolightning\\','/mnt/data/','ChatGPT-AcousticShaders')
found=[]
for name in subprocess.check_output(['git','ls-files'],text=True).splitlines():
    if name in excluded:
        continue
    p=Path(name)
    if not p.is_file():
        continue
    data=p.read_bytes()
    if b'\0' in data:
        continue
    try:
        text=data.decode('utf-8')
    except UnicodeDecodeError:
        continue
    for lineno,line in enumerate(text.splitlines(),1):
        if any(needle in line for needle in needles):
            found.append(f'{name}:{lineno}:{line.strip()}')
if found:
    print('\n'.join(found))
    raise SystemExit('ERROR: machine-local development path leaked into tracked public tooling/docs')
PYPATHS

# Enforce cross-platform path/text invariants before public release.
python3 - <<'PYREPO'
from pathlib import Path
import subprocess
files=subprocess.check_output(['git','ls-files'],text=True).splitlines()
seen={}
binary_suffixes={'.png','.ogg','.zip','.jar','.gz','.zst','.xz'}
for name in files:
    key=name.casefold()
    other=seen.get(key)
    if other is not None and other != name:
        raise SystemExit(f'ERROR: case-insensitive path collision: {other} <-> {name}')
    seen[key]=name
    p=Path(name)
    if not p.is_file() or p.suffix.lower() in binary_suffixes:
        continue
    data=p.read_bytes()
    if b'\0' in data:
        continue
    try:
        data.decode('utf-8')
    except UnicodeDecodeError as exc:
        raise SystemExit(f'ERROR: tracked text is not UTF-8: {name}: {exc}')
    if b'\r\n' in data or b'\r' in data:
        raise SystemExit(f'ERROR: tracked text is not canonical LF: {name}')
print(f'[PASS] cross-platform path/UTF-8/LF invariants: {len(files)} tracked paths')
PYREPO

# Shell entry points are expected to preserve their executable bit in Git.
while IFS=$'\t' read -r mode path; do
  [[ "$mode" == "100755" ]] || { echo "ERROR: shell script is not executable in Git: $path ($mode)" >&2; exit 1; }
done < <(git ls-files -s '*.sh' | awk '{print $1 "\t" $4}')

# Parse every tracked JSON document outside generated directories.
python3 - <<'PY'
from pathlib import Path
import json, subprocess
files=subprocess.check_output(['git','ls-files','*.json'], text=True).splitlines()
for name in files:
    p=Path(name)
    try:
        json.loads(p.read_text(encoding='utf-8'))
    except Exception as exc:
        raise SystemExit(f'ERROR: invalid JSON {name}: {exc}')
print(f'[PASS] tracked JSON parse: {len(files)} files')
PY

# Embedded first-party shaderpack must be a valid ZIP with unique entry names.
python3 - <<'PY'
from pathlib import Path
import zipfile
p=Path('minecraft-1.12.2/src/forge/resources/assets/acousticshaders/shaderpacks/AcousticShaders-Reference-Hybrid.zip')
with zipfile.ZipFile(p) as z:
    bad=z.testzip()
    if bad: raise SystemExit(f'ERROR: embedded shaderpack corrupt at {bad}')
    names=z.namelist()
    if len(names) != len(set(names)): raise SystemExit('ERROR: embedded shaderpack contains duplicate ZIP entries')
print('[PASS] embedded Reference shaderpack ZIP integrity/uniqueness')
PY

# Keep version metadata synchronized. This is intentionally a check rather than another build-time
# generation layer, so Forge's compile-time const version remains simple and auditable.
python3 - <<'PY'
from pathlib import Path
import json,re

def one(pattern, text, label):
    m=re.search(pattern,text,re.M)
    if not m: raise SystemExit(f'ERROR: cannot read version from {label}')
    return m.group(1)

expected=one(r'^VERSION=\x27([^\x27]+)\x27$',Path('package-local-release.sh').read_text(),'package-local-release.sh')
values={
 'build.gradle.kts': one(r'version\s*=\s*"([^"]+)"',Path('build.gradle.kts').read_text(),'build.gradle.kts'),
 'mcmod.info': json.loads(Path('minecraft-1.12.2/src/forge/resources/mcmod.info').read_text())[0]['version'],
 'MANIFEST.MF': one(r'^Implementation-Version:\s*(\S+)$',Path('minecraft-1.12.2/src/forge/resources/META-INF/MANIFEST.MF').read_text(),'MANIFEST.MF'),
 'AcousticShadersForgeMod.kt': one(r'const val VERSION\s*=\s*"([^"]+)"',Path('minecraft-1.12.2/src/forge/kotlin/dev/acoustic/mc1122/forge/AcousticShadersForgeMod.kt').read_text(),'AcousticShadersForgeMod.kt'),
 'rfg build.gradle': one(r"^version\s*=\s*'([^']+)'$",Path('tools/verification/1.12.2/rfg/build.gradle').read_text(),'rfg build.gradle'),
}
bad={k:v for k,v in values.items() if v!=expected}
if bad:
    raise SystemExit(f'ERROR: release version mismatch; expected {expected}, got {bad}')
print(f'[PASS] release version metadata synchronized: {expected}')
PY

# Shell files remain parseable and diffs whitespace-clean.
while IFS= read -r f; do [[ -f "$f" ]] && bash -n "$f"; done < <(git ls-files '*.sh')
git diff --check

printf '%s\n' '[PASS] release repository hygiene gate'
