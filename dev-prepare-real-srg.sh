#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
: "${ACOUSTIC_MC_1122_CLIENT:?set ACOUSTIC_MC_1122_CLIENT to the official 1.12.2 client.jar}"
: "${ACOUSTIC_MCP_CONFIG_1122:?set ACOUSTIC_MCP_CONFIG_1122 to mcp_config-1.12.2-20200226.224830.zip}"
: "${ACOUSTIC_FORGE_1122_UNIVERSAL:?set ACOUSTIC_FORGE_1122_UNIVERSAL to Forge 14.23.5.2864 universal.jar}"
for f in "$ACOUSTIC_MC_1122_CLIENT" "$ACOUSTIC_MCP_CONFIG_1122" "$ACOUSTIC_FORGE_1122_UNIVERSAL"; do [[ -f "$f" ]] || { echo "ERROR: missing $f" >&2; exit 1; }; done
OUT="$ROOT/out/real-srg"; mkdir -p "$OUT"
CLIENT_SHA1=$(sha1sum "$ACOUSTIC_MC_1122_CLIENT"|awk '{print $1}')
MCP_SHA1=$(sha1sum "$ACOUSTIC_MCP_CONFIG_1122"|awk '{print $1}')
FORGE_SHA1=$(sha1sum "$ACOUSTIC_FORGE_1122_UNIVERSAL"|awk '{print $1}')
[[ "$CLIENT_SHA1" == 0f275bc1547d01fa5f56ba34bdc87d981ee12daf ]] || { echo "ERROR: unexpected Minecraft client SHA-1 $CLIENT_SHA1" >&2; exit 1; }
[[ "$MCP_SHA1" == 72e1b936f56e0dd394c64caf9c86af01f64dc979 ]] || { echo "ERROR: unexpected MCPConfig SHA-1 $MCP_SHA1" >&2; exit 1; }
[[ "$FORGE_SHA1" == d0ab8e116da0e50c6e6099791f97772a08469626 ]] || { echo "ERROR: unexpected Forge universal SHA-1 $FORGE_SHA1" >&2; exit 1; }
unzip -p "$ACOUSTIC_MCP_CONFIG_1122" config/joined.tsrg > "$OUT/joined.tsrg"
TOOL_HASH=$(sha256sum "$ROOT/dev-tools/RealSrgRemapper.java"|awk '{print $1}')
KEY=$(printf '%s\n%s\n%s\n%s\n' "$CLIENT_SHA1" "$MCP_SHA1" "$FORGE_SHA1" "$TOOL_HASH" | sha256sum | awk '{print $1}')
if [[ -f "$OUT/cache.key" && "$(cat "$OUT/cache.key")" == "$KEY" && -s "$OUT/minecraft-client-srg.jar" && -s "$OUT/forge-srg.jar" ]]; then
  echo '[PASS] reusable real SRG remap cache'
  exit 0
fi
rm -rf "$OUT/tool-classes"; mkdir -p "$OUT/tool-classes"
javac --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED --add-exports java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED -d "$OUT/tool-classes" "$ROOT/dev-tools/RealSrgRemapper.java"
JAVA_REMAP=(java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED --add-exports java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED -cp "$OUT/tool-classes" RealSrgRemapper "$OUT/joined.tsrg")
"${JAVA_REMAP[@]}" "$ACOUSTIC_MC_1122_CLIENT" "$OUT/minecraft-client-srg.jar"
"${JAVA_REMAP[@]}" "$ACOUSTIC_FORGE_1122_UNIVERSAL" "$OUT/forge-srg.jar"
printf '%s' "$KEY" > "$OUT/cache.key"
javap -classpath "$OUT/minecraft-client-srg.jar" -p net.minecraft.client.Minecraft | grep -F 'func_71410_x' >/dev/null
javap -classpath "$OUT/forge-srg.jar:$OUT/minecraft-client-srg.jar" -p 'net.minecraftforge.client.event.GuiScreenEvent$ActionPerformedEvent$Pre' | grep -F 'net.minecraft.client.gui.GuiScreen' >/dev/null
echo '[PASS] official Minecraft/Forge binaries remapped to SRG names'
