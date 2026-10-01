#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
: "${ACOUSTIC_MC_1122_CLIENT:?set ACOUSTIC_MC_1122_CLIENT}"
: "${ACOUSTIC_MCP_CONFIG_1122:?set ACOUSTIC_MCP_CONFIG_1122}"
: "${ACOUSTIC_FORGE_1122_UNIVERSAL:?set ACOUSTIC_FORGE_1122_UNIVERSAL}"
CLASSES="${1:-$ROOT/out/forge-classes}"
[[ -d "$CLASSES" ]] || { echo "ERROR: production classes missing: $CLASSES" >&2; exit 1; }
"$ROOT/dev-prepare-real-srg.sh"
OUT="$ROOT/out/real-srg-bytecode-audit"
rm -rf "$OUT"; mkdir -p "$OUT/tool-classes"
javac \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  -d "$OUT/tool-classes" \
  "$ROOT/tools/verification/1.12.2/dev-tools/RealSrgBytecodeAudit.java"
java \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  -cp "$OUT/tool-classes" \
  RealSrgBytecodeAudit \
  "$CLASSES" \
  "$ROOT/out/real-srg/minecraft-client-srg.jar" \
  "$ROOT/out/real-srg/forge-srg.jar"
