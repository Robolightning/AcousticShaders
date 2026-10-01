#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

MOD_JAR="${ACOUSTIC_MOD_JAR:-$ROOT/dist/acoustic-shaders-mc1122-0.3.0.jar}"
MC_SRG_JAR="${ACOUSTIC_REAL_SRG_MC_JAR:-$ROOT/out/real-srg/minecraft-client-srg.jar}"
FORGE_SRG_JAR="${ACOUSTIC_REAL_SRG_FORGE_JAR:-$ROOT/out/real-srg/forge-srg.jar}"
OUT="${ACOUSTIC_REAL_MINECRAFT_LIQUID_TNT_GAMEPLAY_OUT:-$ROOT/out/winlab-real-minecraft-liquid-tnt-gameplay}"
PROBE_SRC="$ROOT/tools/verification/1.12.2/dev-tools/RealMinecraftLiquidTntGameplayProbeMod.java"
PROBE_JAR="$OUT/AcousticShaders-Real-Minecraft-Liquid-TNT-Gameplay-Probe.jar"
CLIENT_OUT="$OUT/client"
SUCCESS='ACOUSTIC-REAL-MINECRAFT-LIQUID-TNT-GAMEPLAY-OK'

for f in "$MOD_JAR" "$MC_SRG_JAR" "$FORGE_SRG_JAR" "$PROBE_SRC"; do
  [[ -f "$f" ]] || { echo "ERROR: liquid/TNT gameplay dependency missing: $f" >&2; exit 2; }
done
for cmd in javac python3; do command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: required command missing: $cmd" >&2; exit 1; }; done

rm -rf "$OUT"; mkdir -p "$OUT/classes"
javac --release 8 -Xlint:all,-options,-path -Werror -cp "$MC_SRG_JAR:$FORGE_SRG_JAR" -d "$OUT/classes" "$PROBE_SRC"
python3 - "$OUT/classes" "$PROBE_JAR" <<'PY'
from pathlib import Path
import sys,zipfile
classes=Path(sys.argv[1]); out=Path(sys.argv[2])
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    info=zipfile.ZipInfo('META-INF/MANIFEST.MF',(2026,8,31,20,0,0)); info.compress_type=zipfile.ZIP_DEFLATED; info.external_attr=0o644<<16
    z.writestr(info,b'Manifest-Version: 1.0\r\n\r\n')
    for p in sorted(x for x in classes.rglob('*') if x.is_file()):
        info=zipfile.ZipInfo(p.relative_to(classes).as_posix(),(2026,8,31,20,0,0)); info.compress_type=zipfile.ZIP_DEFLATED; info.external_attr=0o644<<16
        z.writestr(info,p.read_bytes())
PY

ACOUSTIC_MOD_JAR="$MOD_JAR" \
ACOUSTIC_CLIENT_SOUND_EVENT_PROBE_JAR="$PROBE_JAR" \
ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE=1 \
ACOUSTIC_CLIENT_PROBE_SUCCESS_MARKER="$SUCCESS" \
ACOUSTIC_CLIENT_EXPECTED_MOD_COUNT=9 \
ACOUSTIC_CLIENT_OUT="$CLIENT_OUT" \
ACOUSTIC_CLIENT_BOOT_LEVEL=full \
ACOUSTIC_CLIENT_BOOT_TIMEOUT="${ACOUSTIC_CLIENT_BOOT_TIMEOUT:-420}" \
ACOUSTIC_CLIENT_NULL_AUDIO=1 \
ACOUSTIC_CLIENT_MIXIN_EXPORT=1 \
"$ROOT/dev-forge1122-client-launch.sh"

LOG="$CLIENT_OUT/logs/client-console.log"
grep -F "$SUCCESS" "$LOG" >/dev/null
grep -F 'scenarios=5 waterToAir=1 airToWater=1 waterWater=1 airWaterAir=1 airLavaAir=1 bands=8 cleanup=1' "$LOG" >/dev/null || {
  echo 'ERROR: liquid/TNT gameplay proof marker incomplete' >&2; exit 1;
}
for scenario in water-source-air-listener air-source-water-listener water-water air-water-air air-lava-air; do
  grep -F "ACOUSTIC-LIQUID-TNT-SCENARIO-OK scenario=$scenario" "$LOG" >/dev/null || {
    echo "ERROR: liquid/TNT scenario missing: $scenario" >&2; exit 1;
  }
done
grep -F 'Starting integrated minecraft server version 1.12.2' "$LOG" >/dev/null || { echo 'ERROR: integrated server did not start' >&2; exit 1; }
grep -F 'joined the game' "$LOG" >/dev/null || { echo 'ERROR: local player did not join integrated server' >&2; exit 1; }
printf '%s\n' '[PASS] vanilla TNT x AIR/WATER/LAVA production direct-path gameplay matrix'
