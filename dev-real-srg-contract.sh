#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
: "${ACOUSTIC_MC_1122_CLIENT:?set ACOUSTIC_MC_1122_CLIENT}"
: "${ACOUSTIC_MCP_CONFIG_1122:?set ACOUSTIC_MCP_CONFIG_1122}"
: "${ACOUSTIC_FORGE_1122_UNIVERSAL:?set ACOUSTIC_FORGE_1122_UNIVERSAL}"
KOTLINC_BIN="${ACOUSTIC_KOTLINC:-$(command -v kotlinc)}"
[[ -x "$KOTLINC_BIN" ]] || { echo 'ERROR: kotlinc missing' >&2; exit 1; }
"$ROOT/dev-prepare-real-srg.sh"
"$ROOT/dev-real-srg-reflection-audit.sh"
OUT="$ROOT/out/real-srg"
MC="$OUT/minecraft-client-srg.jar"; FORGE="$OUT/forge-srg.jar"
# Compile only external API stubs that are not supplied as real binaries. Minecraft and Forge
# themselves must come exclusively from the remapped official binaries in this gate. When the
# exact launcher LWJGL2 and MixinBooter jars are supplied, use their real APIs too; this catches
# Kotlin-signature mismatches that permissive compile stubs can otherwise hide.
rm -rf "$OUT/external-stubs"; mkdir -p "$OUT/external-stubs"
REAL_EXTERNAL_CP=''
if [[ -n "${ACOUSTIC_LWJGL2_JAR:-}" || -n "${ACOUSTIC_MIXINBOOTER_JAR:-}" ]]; then
  [[ -n "${ACOUSTIC_LWJGL2_JAR:-}" && -n "${ACOUSTIC_MIXINBOOTER_JAR:-}" ]] || {
    echo 'ERROR: set both ACOUSTIC_LWJGL2_JAR and ACOUSTIC_MIXINBOOTER_JAR, or neither' >&2
    exit 1
  }
  [[ -f "$ACOUSTIC_LWJGL2_JAR" && -f "$ACOUSTIC_MIXINBOOTER_JAR" ]] || {
    echo 'ERROR: real external API jar missing' >&2
    exit 1
  }
  LWJGL_SHA1=$(sha1sum "$ACOUSTIC_LWJGL2_JAR" | awk '{print $1}')
  MIXIN_SHA256=$(sha256sum "$ACOUSTIC_MIXINBOOTER_JAR" | awk '{print $1}')
  [[ "$LWJGL_SHA1" == '697517568c68e78ae0b4544145af031c81082dfe' ]] || {
    echo "ERROR: unexpected Minecraft 1.12.2 LWJGL2 jar SHA-1: $LWJGL_SHA1" >&2
    exit 1
  }
  [[ "$MIXIN_SHA256" == '71d5f742414bce4b4d570c0a7f1267173555ed1d8a548d40540013c2495cf605' ]] || {
    echo "ERROR: unexpected MixinBooter 11.15 SHA-256: $MIXIN_SHA256" >&2
    exit 1
  }
  find minecraft-1.12.2/compile-stubs/src/main/java -name '*.java' \
    ! -path '*/net/minecraft/*' ! -path '*/net/minecraftforge/*' \
    ! -path '*/org/lwjgl/*' ! -path '*/org/spongepowered/*' | sort > "$OUT/external-stubs.txt"
  MIXIN_COMPILE_JAR="$OUT/mixinbooter-11.15-compile-api.jar"
  python3 - "$ACOUSTIC_MIXINBOOTER_JAR" "$MIXIN_COMPILE_JAR" <<'PYMIXINJAR'
import sys,zipfile
src,dst=sys.argv[1:]
skip='META-INF/services/javax.annotation.processing.Processor'
with zipfile.ZipFile(src,'r') as zin, zipfile.ZipFile(dst,'w') as zout:
    for info in zin.infolist():
        if info.filename == skip:
            continue
        zout.writestr(info, zin.read(info.filename))
with zipfile.ZipFile(dst,'r') as z:
    if skip in z.namelist():
        raise SystemExit('ERROR: MixinBooter compile view still exposes annotation processor service')
    required=[
        'org/spongepowered/asm/mixin/Mixin.class',
        'org/spongepowered/asm/mixin/injection/Inject.class',
        'org/spongepowered/asm/mixin/injection/At.class',
    ]
    missing=[n for n in required if n not in z.namelist()]
    if missing:
        raise SystemExit('ERROR: MixinBooter compile view lost real Mixin API classes: '+','.join(missing))
print('[PASS] MixinBooter compile view keeps real API classes and disables only annotation-processor autoload')
PYMIXINJAR
  REAL_EXTERNAL_CP="$ACOUSTIC_LWJGL2_JAR:$MIXIN_COMPILE_JAR"
  echo '[PASS] real LWJGL2 2.9.4 + MixinBooter 11.15 APIs selected for real-SRG compile'
else
  find minecraft-1.12.2/compile-stubs/src/main/java -name '*.java' \
    ! -path '*/net/minecraft/*' ! -path '*/net/minecraftforge/*' | sort > "$OUT/external-stubs.txt"
fi
if [[ -s "$OUT/external-stubs.txt" ]]; then javac --release 8 -Xlint:all,-options -Werror -d "$OUT/external-stubs" @"$OUT/external-stubs.txt"; fi
# Portable/core classes contain no Minecraft/Forge imports. The unified gate has already compiled
# them with the same exact Kotlin toolchain in dev-legacy-contract.sh, so reuse that verified
# bytecode when ACOUSTIC_REAL_SRG_PORTABLE_CLASSES is supplied. Copy only platform-neutral
# packages; never place the stub-compiled Forge/Mixin classes on the real-SRG compile classpath.
rm -rf "$OUT/portable-classes"; mkdir -p "$OUT/portable-classes"
if [[ -n "${ACOUSTIC_REAL_SRG_PORTABLE_CLASSES:-}" ]]; then
  SRC_PORTABLE="$ACOUSTIC_REAL_SRG_PORTABLE_CLASSES"
  [[ -d "$SRC_PORTABLE/dev/acoustic" ]] || { echo "ERROR: reusable portable classes missing: $SRC_PORTABLE" >&2; exit 1; }
  python3 - "$SRC_PORTABLE" "$OUT/portable-classes" <<'PYCOPY'
from pathlib import Path
import shutil,sys
src=Path(sys.argv[1]);dst=Path(sys.argv[2])
count=0
for p in src.rglob('*.class'):
    rel=p.relative_to(src)
    posix=rel.as_posix()
    if posix.startswith('dev/acoustic/mc1122/forge/') or posix.startswith('dev/acoustic/mc1122/mixin/'):
        continue
    if not posix.startswith('dev/acoustic/'):
        continue
    out=dst/rel;out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,out);count+=1
if count < 200:
    raise SystemExit(f'ERROR: suspiciously small reusable portable class set: {count}')
print(f'[PASS] reused {count} exact-toolchain portable classfiles for real-SRG compile')
PYCOPY
else
  "$ROOT/dev-compile-jvm8.sh" "$OUT/portable-classes" "" \
    "$ROOT/acoustic-api/src/main/kotlin" \
    "$ROOT/acoustic-platform-api/src/main/kotlin" \
    "$ROOT/acoustic-core/src/main/kotlin" \
    "$ROOT/minecraft-1.12.2/src/main/kotlin"
fi
# Production GUI deliberately ships MCP aliases plus real SRG overrides. Against an SRG-only
# superclass the MCP aliases are ordinary compatibility methods, not overrides. Build a temporary
# source view that removes only the Kotlin `override` marker from those MCP-named aliases.
rm -rf "$OUT/forge-source" "$OUT/forge-classes"; mkdir -p "$OUT/forge-source" "$OUT/forge-classes"
cp -R minecraft-1.12.2/src/forge/kotlin/. "$OUT/forge-source/"
python3 - "$OUT/forge-source" <<'PY'
from pathlib import Path
import re,sys
root=Path(sys.argv[1])
# Only vanilla MCP names paired with explicit func_* methods in the same GUI classes.
names='initGui|actionPerformed|drawScreen|mouseClicked|mouseClickMove|mouseReleased'
pat=re.compile(r'(?m)^(\s*)override fun ('+names+r')\(')
changed=0
for p in root.rglob('*.kt'):
    s=p.read_text()
    n=pat.sub(r'\1fun \2(',s)
    if n!=s:
        p.write_text(n);changed+=1
if changed!=3:
    raise SystemExit(f'ERROR: expected to normalize 3 dual-name GUI sources, changed {changed}')
projectile=root/'dev/acoustic/mc1122/forge/LegacyProjectileEmitterManager.kt'
ps=projectile.read_text()
ps,n_update=re.subn(r'(?m)^(\s*)override fun update\(\)',r'\1fun update()',ps,count=1)
ps,n_srg=re.subn(r'(?m)^(\s*)fun func_73660_a\(\)',r'\1override fun func_73660_a()',ps,count=1)
if n_update!=1 or n_srg!=1:
    raise SystemExit(f'ERROR: projectile tick SRG normalization mismatch update={n_update} srg={n_srg}')
# MovingSound/PositionedSound protected fields are MCP names in the production source and are
# reobfuscated by the real Forge/RFG build. This independent SRG-only compile starts from an
# already-remapped Mojang client, so normalize only the inherited field accesses in this temporary
# source view. Counts are intentionally strict so a future source/mapping drift fails closed.
field_rewrites=[
    (r'\brepeat\b(?=\s*=)', 'field_147659_g', 1),
    (r'\brepeatDelay\b(?=\s*=)', 'field_147665_h', 1),
    (r'\battenuationType\b(?=\s*=)', 'field_147666_i', 1),
    (r'\bdonePlaying\b(?=\s*=)', 'field_147668_j', 2),
    (r'\bxPosF\b(?=\s*=)', 'field_147660_d', 1),
    (r'\byPosF\b(?=\s*=)', 'field_147661_e', 1),
    (r'\bzPosF\b(?=\s*=)', 'field_147658_f', 1),
    (r'\bvolume\b(?=\s*=)', 'field_147662_b', 1),
    (r'\bpitch\b(?=\s*=)', 'field_147663_c', 1),
]
field_counts={}
for pattern,replacement,expected in field_rewrites:
    ps,count=re.subn(pattern,replacement,ps)
    field_counts[replacement]=count
    if count!=expected:
        raise SystemExit(f'ERROR: projectile SRG field normalization mismatch {replacement}={count}, expected {expected}')
projectile.write_text(ps)
print('[PASS] projectile SRG source view normalized tick override + 11 inherited field accesses')
PY
find "${OUT#"$ROOT/"}/forge-source" -name '*.kt' ! -name 'LegacySoundEvents.kt' | sort > "$OUT/forge-kotlin-sources.txt"
KOTLIN_HOME_DIR="$(cd "$(dirname "$KOTLINC_BIN")/.." && pwd)"
KOTLIN_CP="$KOTLIN_HOME_DIR/lib/kotlin-stdlib.jar:$KOTLIN_HOME_DIR/lib/kotlin-stdlib-jdk7.jar:$KOTLIN_HOME_DIR/lib/kotlin-stdlib-jdk8.jar"
REAL_CP="$OUT/portable-classes:$OUT/external-stubs:$MC:$FORGE${REAL_EXTERNAL_CP:+:$REAL_EXTERNAL_CP}"
"$KOTLINC_BIN" -J-Xms128m -J-Xmx1536m -Xjdk-release=8 -jvm-default=no-compatibility -Werror -classpath "$REAL_CP" -d "$OUT/forge-classes" @"$OUT/forge-kotlin-sources.txt"
# Forge patches vanilla SoundEvent to IForgeRegistryEntry in its real MCP/RFG environment, but the
# independent binary gate deliberately starts from Mojang's unpatched client.jar. Reuse only this
# one already exact-toolchain class from the unified compile, then audit its bytecode/Forge ABI
# explicitly. Never add a fake patched Minecraft class to REAL_CP.
UNIFIED_SOUND_EVENTS="$ROOT/out/forge-classes/dev/acoustic/mc1122/forge/LegacySoundEvents.class"
[[ -f "$UNIFIED_SOUND_EVENTS" ]] || { echo 'ERROR: verified unified LegacySoundEvents.class missing' >&2; exit 1; }
mkdir -p "$OUT/forge-classes/dev/acoustic/mc1122/forge"
cp "$UNIFIED_SOUND_EVENTS" "$OUT/forge-classes/dev/acoustic/mc1122/forge/LegacySoundEvents.class"
SOUND_EVENTS_JAVAP="$OUT/LegacySoundEvents-real-forge-abi.javap"
javap -classpath "$OUT/forge-classes:$REAL_CP" -v -p dev.acoustic.mc1122.forge.LegacySoundEvents > "$SOUND_EVENTS_JAVAP"
python3 - "$SOUND_EVENTS_JAVAP" <<'PYSOUNDABI'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text(errors='replace')
checks={
    'static registry handler':'public static final void registerSounds(net.minecraftforge.event.RegistryEvent$Register<net.minecraft.util.SoundEvent>)',
    'SoundEvent generic signature':'RegistryEvent$Register<Lnet/minecraft/util/SoundEvent;>',
    'real Forge registry descriptor':'IForgeRegistry.register:(Lnet/minecraftforge/registries/IForgeRegistryEntry;)V',
    'event-bus subscriber annotation':'net.minecraftforge.fml.common.Mod$EventBusSubscriber(',
    'client-only subscriber':'value=[Lnet/minecraftforge/fml/relauncher/Side;.CLIENT]',
    'mod id':'modid="acousticshaders"',
    'subscribe annotation':'net.minecraftforge.fml.common.eventhandler.SubscribeEvent',
}
for label,needle in checks.items():
    if needle not in s:
        raise SystemExit('ERROR: LegacySoundEvents Forge-patch ABI mismatch: '+label)
print('[PASS] Forge-patched SoundEvent registration class carries exact generic/event/registry ABI without a fake Minecraft patch stub')
PYSOUNDABI
if [[ -n "$REAL_EXTERNAL_CP" ]]; then
  for c in dev.acoustic.mc1122.forge.OpenClFdtdBackend dev.acoustic.mc1122.forge.OpenClGeometricBackend; do
    f="$OUT/${c##*.}-external.javap"
    javap -classpath "$OUT/forge-classes:$REAL_CP" -c -s -p "$c" > "$f"
    if grep -F 'Lorg/lwjgl/opencl/CLObject;' "$f" >/dev/null; then
      echo "ERROR: package-private LWJGL2 CLObject leaked into production bytecode: $c" >&2
      exit 1
    fi
    grep -F '(Lorg/lwjgl/opencl/CLKernel;ILjava/nio/ByteBuffer;)I' "$f" >/dev/null || {
      echo "ERROR: pointer-sized ByteBuffer OpenCL kernel-arg linkage missing: $c" >&2
      exit 1
    }
  done
  for c in dev.acoustic.mc1122.mixin.MixinSoundSystem dev.acoustic.mc1122.mixin.MixinSourceLWJGLOpenAL dev.acoustic.mc1122.mixin.MixinSourceLifecycle; do
    f="$OUT/${c##*.}-inject.javap"
    javap -classpath "$OUT/forge-classes:$REAL_CP" -v -p "$c" > "$f"
    grep -F 'at=[@org.spongepowered.asm.mixin.injection.At(' "$f" >/dev/null || {
      echo "ERROR: real Mixin Inject.at annotation array missing: $c" >&2
      exit 1
    }
  done
  echo '[PASS] real LWJGL2 kernel-arg bytecode + Mixin Inject.at[] bytecode contract'
fi
# Bytecode must contain the actual SRG GUI override entry points used by production runtime.
for c in dev.acoustic.mc1122.forge.GuiAcousticShaders dev.acoustic.mc1122.forge.GuiAcousticShaderOptions dev.acoustic.mc1122.forge.GuiRuntimeAudio; do
  javap -classpath "$OUT/forge-classes:$REAL_CP" -p "$c" > "$OUT/${c##*.}.javap"
  grep -F 'func_73866_w_' "$OUT/${c##*.}.javap" >/dev/null
  grep -F 'func_146284_a' "$OUT/${c##*.}.javap" >/dev/null
  grep -F 'func_73863_a' "$OUT/${c##*.}.javap" >/dev/null
done
# JVM linkage smoke: load production entry point/GUI classes without Minecraft or Forge stubs.
find "$OUT/forge-classes" -name '*.class' | sort | sed "s#^$OUT/forge-classes/##;s#/#.#g;s#\.class\$##" > "$OUT/forge-class-names.txt"
cat > "$OUT/RealSrgLinkageSmoke.java" <<'JAVA'
import java.nio.file.*;
import java.util.*;
public final class RealSrgLinkageSmoke {
  public static void main(String[] args) throws Exception {
    ClassLoader cl=Thread.currentThread().getContextClassLoader();
    List<String> names=Files.readAllLines(Paths.get(args[0]));
    for(String n:names) if(!n.isEmpty()) Class.forName(n,false,cl);
    Class<?> gui=Class.forName("dev.acoustic.mc1122.forge.GuiAcousticShaders",false,cl);
    if(!"net.minecraft.client.gui.GuiScreen".equals(gui.getSuperclass().getName())) throw new AssertionError(gui.getSuperclass());
    Class<?> button=Class.forName("net.minecraft.client.gui.GuiButton",false,cl);
    gui.getDeclaredMethod("func_73866_w_");
    gui.getDeclaredMethod("func_146284_a",button);
    gui.getDeclaredMethod("func_73863_a",int.class,int.class,float.class);
    System.out.println("[PASS] all real-SRG Forge/Mixin classfiles link without Minecraft/Forge stubs");
  }
}
JAVA
rm -rf "$OUT/linkage"; mkdir -p "$OUT/linkage"
javac --release 8 -Xlint:all,-options,-path -Werror -classpath "$OUT/forge-classes:$REAL_CP:$KOTLIN_CP" -d "$OUT/linkage" "$OUT/RealSrgLinkageSmoke.java"
java -Xverify:all -cp "$OUT/linkage:$OUT/forge-classes:$REAL_CP:$KOTLIN_CP" RealSrgLinkageSmoke "$OUT/forge-class-names.txt"
COUNT=$(find "$OUT/forge-classes" -name '*.class' | wc -l | tr -d ' ')
python3 - "$OUT/forge-classes" <<'PYCLASS'
from pathlib import Path
import struct,sys
classes=list(Path(sys.argv[1]).rglob('*.class'))
if not classes: raise SystemExit('ERROR: real-SRG compile produced no classfiles')
for p in classes:
    b=p.read_bytes()
    if b[:4]!=b'\xca\xfe\xba\xbe': raise SystemExit('ERROR: invalid class '+str(p))
    major=struct.unpack('>H',b[6:8])[0]
    if major!=52: raise SystemExit(f'ERROR: non-Java8 real-SRG class {p}: major={major}')
print(f'[PASS] {len(classes)} real-SRG classfiles are Java 8 bytecode')
PYCLASS
echo "[PASS] real Minecraft 1.12.2 + Forge 14.23.5.2864 SRG compile ($COUNT Forge/Mixin-facing classfiles)"
