#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"; cd "$ROOT"
: "${ACOUSTIC_MC_1122_CLIENT:?set ACOUSTIC_MC_1122_CLIENT}"
: "${ACOUSTIC_MCP_CONFIG_1122:?set ACOUSTIC_MCP_CONFIG_1122}"
: "${ACOUSTIC_FORGE_1122_UNIVERSAL:?set ACOUSTIC_FORGE_1122_UNIVERSAL}"

REAL="$ROOT/out/real-srg"
PORTABLE="$REAL/portable-classes"
FORGE="$REAL/forge-classes"
[[ -d "$PORTABLE/dev/acoustic" && -d "$FORGE/dev/acoustic" ]] || {
  echo 'ERROR: real-SRG class outputs missing; run tools/verification/1.12.2/scripts/dev-real-srg-contract.sh first' >&2
  exit 1
}

OUT="$ROOT/out/rfg-reobf-equivalent"
STAGE="$OUT/stage"
JAR="$OUT/AcousticShaders-RFG-REOBF-EQUIVALENT.jar"
rm -rf "$OUT"; mkdir -p "$STAGE" "$OUT/tool-classes"

# This is deliberately an audit artifact, not a substitute for Gradle/RFG provenance.
# Build it from the exact real-SRG class outputs plus the production resource tree.
cp -a "$ROOT/minecraft-1.12.2/src/forge/resources/." "$STAGE/"
cp -a "$PORTABLE/." "$STAGE/"
cp -a "$FORGE/." "$STAGE/"
# Prefer the module metadata from the unified exact-Kotlin compile when available, because real-SRG
# compiles portable and Forge-facing sources separately for API isolation.
if [[ -f "$ROOT/out/forge-classes/META-INF/main.kotlin_module" ]]; then
  mkdir -p "$STAGE/META-INF"
  cp "$ROOT/out/forge-classes/META-INF/main.kotlin_module" "$STAGE/META-INF/main.kotlin_module"
fi

javac \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  -d "$OUT/tool-classes" \
  "$ROOT/tools/verification/1.12.2/dev-tools/StripMcpGuiAliases.java"
java \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  -cp "$OUT/tool-classes" \
  StripMcpGuiAliases "$STAGE"

python3 - "$STAGE" "$JAR" <<'PYJAR'
from pathlib import Path
import sys, zipfile
root=Path(sys.argv[1]); dest=Path(sys.argv[2])
with zipfile.ZipFile(dest,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p in sorted(x for x in root.rglob('*') if x.is_file()):
        rel=p.relative_to(root).as_posix()
        zi=zipfile.ZipInfo(rel,(1980,1,1,0,0,0))
        zi.compress_type=zipfile.ZIP_DEFLATED
        zi.external_attr=0o100644 << 16
        z.writestr(zi,p.read_bytes())
PYJAR

python3 - "$JAR" "$ROOT/out/forge-classes" <<'PYSTRUCT'
from pathlib import Path
import struct,sys,zipfile
jar=Path(sys.argv[1]); verified=Path(sys.argv[2])
required={
    'dev/acoustic/mc1122/forge/AcousticShadersForgeMod.class',
    'dev/acoustic/mc1122/forge/GuiAcousticShaders.class',
    'dev/acoustic/mc1122/forge/LegacyProjectileEmitterManager.class',
    'dev/acoustic/mc1122/forge/LegacyDirectPathDiagnostic.class',
    'dev/acoustic/mc1122/forge/LegacySoundEvents.class',
    'dev/acoustic/mc1122/mixin/MixinSourceLWJGLOpenAL.class',
    'assets/acousticshaders/sounds.json',
    'assets/acousticshaders/sounds/projectile/flight.ogg',
    'mixins.acousticshaders.json',
    'mcmod.info',
}
expected={p.relative_to(verified).as_posix() for p in verified.rglob('*.class')}
if not expected:
    raise SystemExit('ERROR: verified production class set missing: '+str(verified))
with zipfile.ZipFile(jar) as z:
    names=set(z.namelist())
    missing=required-names
    if missing:
        raise SystemExit('ERROR: reobf-equivalent JAR missing '+','.join(sorted(missing)))
    if any(n.startswith('kotlin/') and n.endswith('.class') for n in names):
        raise SystemExit('ERROR: Kotlin runtime leaked into reobf-equivalent JAR')
    manifest=z.read('META-INF/MANIFEST.MF').decode('utf-8','replace')
    if 'MixinConfigs: mixins.acousticshaders.json' not in manifest:
        raise SystemExit('ERROR: reobf-equivalent JAR lost MixinConfigs manifest attribute')
    classes={n for n in names if n.endswith('.class')}
    if classes != expected:
        missing_classes=sorted(expected-classes)
        extra_classes=sorted(classes-expected)
        raise SystemExit('ERROR: production class-set mismatch missing=%s extra=%s' % (missing_classes[:20],extra_classes[:20]))
    for name in classes:
        data=z.read(name)
        if data[:4] != b'\xca\xfe\xba\xbe':
            raise SystemExit('ERROR: invalid classfile '+name)
        major=struct.unpack('>H',data[6:8])[0]
        if major != 52:
            raise SystemExit(f'ERROR: non-Java8 class {name}: major={major}')
print(f'[PASS] reobf-equivalent JAR: exact {len(expected)}-class verified production set, Java 8, resources/manifest present, no shaded Kotlin runtime')
PYSTRUCT

python3 "$ROOT/tools/verification/1.12.2/dev-tools/verify-rfg-reobf-jar.py" "$JAR"
"$ROOT/tools/verification/1.12.2/scripts/dev-real-srg-bytecode-audit.sh" "$STAGE"

SHA=$(sha256sum "$JAR" | awk '{print $1}')
echo "[PASS] local RFG-reobf-equivalent bytecode/content contract: $SHA"
echo '[INFO] This proves reobf-equivalent bytecode/content only; it does NOT prove Gradle/RFG task provenance.'
