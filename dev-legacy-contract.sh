#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/out"
cd "$ROOT"
KOTLIN_RUNNER="${ACOUSTIC_KOTLIN:-$(command -v kotlin)}"
[[ -x "$KOTLIN_RUNNER" ]] || { echo "ERROR: kotlin runner not executable: $KOTLIN_RUNNER" >&2; exit 1; }
MOD_SOURCE="$ROOT/minecraft-1.12.2/src/forge/kotlin/dev/acoustic/mc1122/forge/AcousticShadersForgeMod.kt"
[[ -f "$MOD_SOURCE" ]] || MOD_SOURCE="$ROOT/minecraft-1.12.2/src/forge/java/dev/acoustic/mc1122/forge/AcousticShadersForgeMod.java"
python3 - "$ROOT/minecraft-1.12.2/src/forge/resources/mcmod.info" "$MOD_SOURCE" <<'PYMETA'
import json,re,sys
info=json.load(open(sys.argv[1],encoding='utf-8'))
mods=info[0].get('requiredMods',[])
for required in ('mixinbooter','forgelin_continuous'):
    if required not in mods:
        raise SystemExit('ERROR: mcmod.info missing required dependency '+required)
s=open(sys.argv[2],encoding='utf-8').read()
if 'required-after:forgelin_continuous@[2.4.0.0,);' not in s:
    raise SystemExit('ERROR: @Mod does not require Forgelin-Continuous >= 2.4.0.0')
print('[PASS] Forgelin-Continuous 2.4.0.0+ is a mandatory 1.12.2 dependency')
PYMETA
./dev-build-shaderpacks.sh >/dev/null
rm -rf "$OUT/forge-stubs" "$OUT/forge-classes" "$OUT/runtime-smoke" "$OUT/srg-gui-smoke" "$OUT/legacy-smoke-config"
mkdir -p "$OUT/forge-stubs" "$OUT/forge-classes" "$OUT/runtime-smoke" "$OUT/srg-gui-smoke"
KOTLINC_BIN="${ACOUSTIC_KOTLINC:-$(command -v kotlinc)}"
[[ -x "$KOTLINC_BIN" ]] || { echo "ERROR: kotlinc not executable: $KOTLINC_BIN" >&2; exit 1; }
KOTLIN_HOME_DIR="$(cd "$(dirname "$KOTLINC_BIN")/.." && pwd)"
KOTLIN_LIB="$KOTLIN_HOME_DIR/lib"
KOTLIN_CP="$KOTLIN_LIB/kotlin-stdlib.jar:$KOTLIN_LIB/kotlin-stdlib-jdk7.jar:$KOTLIN_LIB/kotlin-stdlib-jdk8.jar"
find "$ROOT/minecraft-1.12.2/compile-stubs/src/main/java" -name '*.java' | sort > "$OUT/forge-stubs.txt"
javac --release 8 -Xlint:all,-options -Werror -d "$OUT/forge-stubs" @"$OUT/forge-stubs.txt"
"$ROOT/dev-compile-jvm8.sh" "$OUT/forge-classes" "$OUT/forge-stubs" \
  "$ROOT/acoustic-api/src/main/java" "$ROOT/acoustic-api/src/main/kotlin" \
  "$ROOT/acoustic-platform-api/src/main/java" "$ROOT/acoustic-platform-api/src/main/kotlin" \
  "$ROOT/acoustic-core/src/main/java" "$ROOT/acoustic-core/src/main/kotlin" \
  "$ROOT/minecraft-1.12.2/src/main/java" "$ROOT/minecraft-1.12.2/src/main/kotlin" \
  "$ROOT/minecraft-1.12.2/src/forge/java" "$ROOT/minecraft-1.12.2/src/forge/kotlin"
JAVA8_NIO_JAVAP="$OUT/java8-nio-linkage-javap.txt"
javap -classpath "$OUT/forge-classes" -c -s -p dev.acoustic.mc1122.forge.LegacySoftwareWetBackend > "$JAVA8_NIO_JAVAP"
if grep -F 'java/nio/ByteBuffer.flip:()Ljava/nio/ByteBuffer;' "$JAVA8_NIO_JAVAP" >/dev/null; then
  echo 'ERROR: JDK 9+ covariant ByteBuffer.flip descriptor leaked into Java-8 release bytecode' >&2
  exit 1
fi
grep -F 'java/nio/ByteBuffer.flip:()Ljava/nio/Buffer;' "$JAVA8_NIO_JAVAP" >/dev/null || {
  echo 'ERROR: expected Java-8 ByteBuffer.flip():Buffer linkage missing' >&2
  exit 1
}
echo '[PASS] Kotlin bytecode uses Java-8 NIO descriptors (no JDK9+ covariant-return leak)'
GUI_LINK_JAVAP="$OUT/gui-linkage-javap.txt"
: > "$GUI_LINK_JAVAP"
for c in dev.acoustic.mc1122.forge.AcousticShadersForgeMod dev.acoustic.mc1122.forge.GuiAcousticShaders dev.acoustic.mc1122.forge.GuiAcousticShaderOptions; do
  javap -classpath "$OUT/forge-classes" -v "$c" >> "$GUI_LINK_JAVAP"
done
python3 - "$GUI_LINK_JAVAP" <<'PYGUI'
from pathlib import Path
import re,sys
s=Path(sys.argv[1]).read_text(errors='replace')
forbidden=[
    r'Fieldref\s+.*net/minecraft/client/gui/GuiButton\.(?:id|x|y|width|height|displayString|enabled|visible):',
    r'Fieldref\s+.*net/minecraft/client/gui/GuiScreen\.(?:width|height|buttonList|fontRenderer|mc):',
    r'Methodref\s+.*net/minecraft/client/Minecraft\.(?:getMinecraft|displayGuiScreen|getSoundHandler):',
    r'Methodref\s+.*net/minecraft/client/gui/FontRenderer\.(?:getStringWidth|trimStringToWidth):',
    r'Methodref\s+.*net/minecraft/client/gui/GuiScreen\.(?:drawScreen|mouseClicked|mouseReleased|addButton):',
]
for pat in forbidden:
    m=re.search(pat,s)
    if m: raise SystemExit('ERROR: direct MCP GUI linkage remains in release classes: '+m.group(0))
print('[PASS] no direct MCP GUI member linkage in release classes')
PYGUI
# Sponge Mixin reads @Mixin from RuntimeInvisibleAnnotations because @Mixin has CLASS retention.
# A compile stub with RUNTIME retention compiles cleanly but crashes in the real client as "missing an @Mixin annotation".
for MIXIN_NAME in MixinSourceLWJGLOpenAL MixinSoundSystem MixinSourceLifecycle; do
  MIXIN_CLASS="$OUT/forge-classes/dev/acoustic/mc1122/mixin/${MIXIN_NAME}.class"
  MIXIN_JAVAP="$OUT/mixin-${MIXIN_NAME}-javap.txt"
  javap -v "$MIXIN_CLASS" > "$MIXIN_JAVAP"
  python3 - "$MIXIN_JAVAP" "$MIXIN_NAME" <<'PYMIX'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text(errors='replace');name=sys.argv[2]
marker='RuntimeInvisibleAnnotations:';idx=s.rfind(marker)
if idx < 0 or 'org.spongepowered.asm.mixin.Mixin(' not in s[idx:]:
    raise SystemExit('ERROR: '+name+' @Mixin is not RuntimeInvisibleAnnotations (RetentionPolicy.CLASS)')
PYMIX
done
echo '[PASS] all production Mixins use CLASS-retention bytecode contract'
find "$ROOT/minecraft-1.12.2/runtime-smoke/src/main/java" -name '*.java' | sort > "$OUT/runtime-smoke-sources.txt"
javac --release 8 -Xlint:all,-options -Werror -cp "$OUT/forge-stubs:$OUT/forge-classes:$KOTLIN_CP" -d "$OUT/runtime-smoke" @"$OUT/runtime-smoke-sources.txt"
"$KOTLIN_RUNNER" -J-ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.ForgeRegistryFallbackSmokeTest
"$KOTLIN_RUNNER" -J-ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.LegacyForgeSmokeTest
"$KOTLIN_RUNNER" -J-ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.ProjectileEmitterSmokeTest
"$KOTLIN_RUNNER" -J-ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.EffectsDisableProjectileSmokeTest
"$KOTLIN_RUNNER" -J-ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.EffectsDisableAudioStateSmokeTest
"$KOTLIN_RUNNER" -J-ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.SoftwareWetToggleSmokeTest
"$KOTLIN_RUNNER" -J-ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.DopplerWithoutEfxSmokeTest
"$KOTLIN_RUNNER" -J-ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.SourceIdReuseSmokeTest
"$KOTLIN_RUNNER" -J-ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.DirectPathDiagnosticSmokeTest
find "$ROOT/minecraft-1.12.2/srg-gui-smoke/src/main/java" -name '*.java' | sort > "$OUT/srg-gui-smoke-sources.txt"
javac --release 8 -Xlint:all,-options -Werror -cp "$OUT/forge-stubs:$OUT/forge-classes:$KOTLIN_CP" -d "$OUT/srg-gui-smoke" @"$OUT/srg-gui-smoke-sources.txt"
rm -rf "$OUT/srg-gui-smoke-work"
mkdir -p "$OUT/srg-gui-smoke-work"
( cd "$OUT/srg-gui-smoke-work" && "$KOTLIN_RUNNER" -J-ea -cp "$OUT/srg-gui-smoke:$OUT/forge-stubs:$OUT/forge-classes:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.SrgGuiRuntimeSmokeTest )
# Contract guard: only the dedicated Forge source tree may import external runtime APIs.
if grep -R -nE '^import (net\.minecraft|net\.minecraftforge|org\.spongepowered|paulscode|org\.lwjgl)' "$ROOT/minecraft-1.12.2/src/main/java" "$ROOT/minecraft-1.12.2/src/main/kotlin" 2>/dev/null; then
  echo 'ERROR: external API import leaked into Forge-free legacy sources' >&2; exit 1
fi
echo '[PASS] legacy Forge/Mixin/Paulscode external contract compile'
