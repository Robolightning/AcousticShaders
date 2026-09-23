#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
MC="${ACOUSTIC_REAL_SRG_MC_JAR:-$ROOT/out/real-srg/minecraft-client-srg.jar}"
FORGE="${ACOUSTIC_REAL_SRG_FORGE_JAR:-$ROOT/out/real-srg/forge-srg.jar}"
SRC="$ROOT/dev-tools/RealMinecraftLiquidTntGameplayProbeMod.java"
OUT="${ACOUSTIC_LIQUID_TNT_GAMEPLAY_PROBE_OUT:-$ROOT/out/physical-liquid-tnt-gameplay-probe}"
for f in "$MC" "$FORGE" "$SRC"; do [[ -f "$f" ]] || { echo "ERROR: liquid/TNT gameplay probe dependency missing: $f" >&2; exit 2; }; done
rm -rf "$OUT"; mkdir -p "$OUT/classes"
javac --release 8 -Xlint:all,-options,-path -Werror -cp "$MC:$FORGE" -d "$OUT/classes" "$SRC"
printf '%s\n' '[PASS] physical 1.12.2 SRG WATER/LAVA TNT gameplay probe compiles with -Werror'
