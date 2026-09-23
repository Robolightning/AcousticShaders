#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
MC="${ACOUSTIC_REAL_SRG_MC_JAR:-$ROOT/out/real-srg/minecraft-client-srg.jar}"
FORGE="${ACOUSTIC_REAL_SRG_FORGE_JAR:-$ROOT/out/real-srg/forge-srg.jar}"
SRC="$ROOT/dev-tools/RealMinecraftProjectileGameplayProbeMod.java"
OUT="${ACOUSTIC_PROJECTILE_GAMEPLAY_PROBE_OUT:-$ROOT/out/physical-projectile-gameplay-probe}"
for f in "$MC" "$FORGE" "$SRC"; do [[ -f "$f" ]] || { echo "ERROR: projectile gameplay probe dependency missing: $f" >&2; exit 2; }; done
rm -rf "$OUT"; mkdir -p "$OUT/classes"
javac --release 8 -Xlint:all,-options,-path -Werror -cp "$MC:$FORGE" -d "$OUT/classes" "$SRC"
printf '%s\n' '[PASS] physical 1.12.2 SRG projectile gameplay probe compiles with -Werror'
