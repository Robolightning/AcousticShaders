#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
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

# Do not ship unresolved marker comments in production. Match explicit marker syntax only, not words
# such as "temporary" used as ordinary variable names.
if rg -n --glob '*.kt' --glob '*.kts' --glob '*.java' --glob '*.cu' --glob '*.cl' \
  '(?i)(//|/\*|\*)[[:space:]]*(TODO|FIXME|HACK|XXX)([(:[:space:]]|$)' "${PRODUCTION_ROOTS[@]}"; then
  echo 'ERROR: unresolved TODO/FIXME/HACK/XXX marker in production' >&2
  exit 1
fi

# No editor/merge/temporary artefacts may be tracked.
if git ls-files | grep -E '(^|/)(\.DS_Store|Thumbs\.db)$|(~|\.bak|\.orig|\.rej|\.tmp)$|(^|/)(out|dist|build|\.gradle|\.idea|\.vscode)/' >/tmp/acoustic-hygiene-junk.$$; then
  echo 'ERROR: generated/editor junk is tracked:' >&2
  cat /tmp/acoustic-hygiene-junk.$$ >&2
  rm -f /tmp/acoustic-hygiene-junk.$$
  exit 1
fi
rm -f /tmp/acoustic-hygiene-junk.$$

# Public tooling must not embed the maintainer's private machine paths or ChatGPT workspace paths.
if rg -n 'C:\\Users\\Robolightning\\|/mnt/data/|ChatGPT-AcousticShaders' \
  --glob '!LICENSE' --glob '!README.md' --glob '!README.ru.md' --glob '!CHANGELOG.md' \
  --glob '!minecraft-1.12.2/src/forge/resources/mcmod.info' --glob '!dev-release-hygiene.sh' .; then
  echo 'ERROR: machine-local development path leaked into tracked public tooling/docs' >&2
  exit 1
fi

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
 'rfg build.gradle': one(r"^version\s*=\s*'([^']+)'$",Path('rfg-1.12.2/build.gradle').read_text(),'rfg build.gradle'),
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
