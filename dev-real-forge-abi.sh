#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
: "${ACOUSTIC_FORGE_1122_UNIVERSAL:?set ACOUSTIC_FORGE_1122_UNIVERSAL}"
[[ -f "$ACOUSTIC_FORGE_1122_UNIVERSAL" ]] || { echo "ERROR: missing Forge universal" >&2; exit 1; }
FORGE_SHA1=$(sha1sum "$ACOUSTIC_FORGE_1122_UNIVERSAL"|awk '{print $1}')
[[ "$FORGE_SHA1" == d0ab8e116da0e50c6e6099791f97772a08469626 ]] || { echo "ERROR: unexpected Forge universal SHA-1 $FORGE_SHA1" >&2; exit 1; }
if [[ -n "${ACOUSTIC_FORGE_1122_INSTALLER:-}" ]]; then
  [[ -f "$ACOUSTIC_FORGE_1122_INSTALLER" ]] || { echo "ERROR: missing Forge installer" >&2; exit 1; }
  python3 - "$ACOUSTIC_FORGE_1122_INSTALLER" "$FORGE_SHA1" <<'PY'
import json,sys,zipfile,hashlib
installer,actual=sys.argv[1:]
with zipfile.ZipFile(installer) as z:
    profile=json.loads(z.read('install_profile.json'))
    libs=profile.get('libraries',[])
    artifact=None
    for lib in libs:
        if lib.get('name')=='net.minecraftforge:forge:1.12.2-14.23.5.2864':
            artifact=lib['downloads']['artifact'];break
    if artifact is None: raise SystemExit('ERROR: installer has no expected Forge artifact metadata')
    if artifact.get('sha1')!=actual: raise SystemExit('ERROR: universal SHA-1 disagrees with installer metadata')
    path='maven/'+artifact['path']
    embedded=z.read(path)
    if hashlib.sha1(embedded).hexdigest()!=actual: raise SystemExit('ERROR: embedded Forge JAR disagrees with supplied universal')
print('[PASS] Forge universal matches installer metadata and embedded JAR')
PY
fi
OUT="$ROOT/out/real-forge-abi"; rm -rf "$OUT"; mkdir -p "$OUT"
for c in \
  net.minecraftforge.fml.common.gameevent.TickEvent \
  'net.minecraftforge.fml.common.gameevent.TickEvent$ClientTickEvent' \
  'net.minecraftforge.client.event.GuiScreenEvent$ActionPerformedEvent' \
  'net.minecraftforge.client.event.GuiScreenEvent$ActionPerformedEvent$Pre' \
  net.minecraftforge.fml.common.eventhandler.SubscribeEvent; do
  javap -classpath "$ACOUSTIC_FORGE_1122_UNIVERSAL" -p "$c" >> "$OUT/real.txt"
done
python3 - "$OUT/real.txt" <<'PY'
from pathlib import Path
s=Path(__import__('sys').argv[1]).read_text()
checks={
 'TickEvent extends Event':'public class net.minecraftforge.fml.common.gameevent.TickEvent extends net.minecraftforge.fml.common.eventhandler.Event',
 'ClientTickEvent phase constructor':'TickEvent$ClientTickEvent(net.minecraftforge.fml.common.gameevent.TickEvent$Phase)',
 'ActionPerformed has button list':'java.util.List<bja> getButtonList()',
 'ActionPerformed.Pre 3 args':'ActionPerformedEvent$Pre(blk, bja, java.util.List<bja>)',
 'SubscribeEvent priority':'EventPriority priority()',
 'SubscribeEvent receiveCanceled':'boolean receiveCanceled()'
}
for label,needle in checks.items():
    if needle not in s: raise SystemExit('ERROR: real Forge ABI mismatch: '+label)
print('[PASS] real Forge 14.23.5.2864 event/GUI ABI signatures')
PY
# Compile the deterministic stubs and compare the same ABI surface at classfile level.
rm -rf "$OUT/stubs"; mkdir -p "$OUT/stubs"
find minecraft-1.12.2/compile-stubs/src/main/java -name '*.java' | sort > "$OUT/stub-sources.txt"
javac --release 8 -Xlint:all,-options -Werror -d "$OUT/stubs" @"$OUT/stub-sources.txt"
: > "$OUT/stub.txt"
for c in \
  net.minecraftforge.fml.common.gameevent.TickEvent \
  'net.minecraftforge.fml.common.gameevent.TickEvent$ClientTickEvent' \
  'net.minecraftforge.client.event.GuiScreenEvent$ActionPerformedEvent' \
  'net.minecraftforge.client.event.GuiScreenEvent$ActionPerformedEvent$Pre' \
  net.minecraftforge.fml.common.eventhandler.SubscribeEvent; do
  javap -classpath "$OUT/stubs" -p "$c" >> "$OUT/stub.txt"
done
python3 - "$OUT/stub.txt" <<'PY_STUB'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
checks={
 'TickEvent extends Event':'public class net.minecraftforge.fml.common.gameevent.TickEvent extends net.minecraftforge.fml.common.eventhandler.Event',
 'ClientTickEvent phase constructor':'TickEvent$ClientTickEvent(net.minecraftforge.fml.common.gameevent.TickEvent$Phase)',
 'ActionPerformed button list getter':'java.util.List<net.minecraft.client.gui.GuiButton> getButtonList()',
 'ActionPerformed.Pre 3 args':'ActionPerformedEvent$Pre(net.minecraft.client.gui.GuiScreen, net.minecraft.client.gui.GuiButton, java.util.List<net.minecraft.client.gui.GuiButton>)',
 'SubscribeEvent priority':'EventPriority priority()',
 'SubscribeEvent receiveCanceled':'boolean receiveCanceled()'
}
for label,needle in checks.items():
    if needle not in s: raise SystemExit('ERROR: compile stub ABI mismatch: '+label)
print('[PASS] production-facing Forge stubs track audited real ABI')
PY_STUB
