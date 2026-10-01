#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/out"
DIST="$ROOT/dist"
VERSION='0.3.0'
JAR="$DIST/acoustic-shaders-mc1122-$VERSION.jar"
STAMP="$OUT/release-verification.stamp"
cd "$ROOT"

test -s "$STAMP" || { echo "ERROR: verification stamp missing; run ./verify.sh first" >&2; exit 1; }
STAMP_HEAD="$(sed -n 's/^head=//p' "$STAMP")"
STAMP_KOTLIN="$(sed -n 's/^kotlin=//p' "$STAMP")"
STAMP_INPUTS="$(sed -n 's/^inputs=//p' "$STAMP")"
CURRENT_HEAD="$(git rev-parse HEAD)"
[[ "$STAMP_HEAD" == "$CURRENT_HEAD" ]] || { echo "ERROR: verified HEAD $STAMP_HEAD != current HEAD $CURRENT_HEAD" >&2; exit 1; }
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || { echo "ERROR: working tree is not fully clean; refusing stale-bytecode packaging" >&2; git status --short --untracked-files=all >&2; exit 1; }
CURRENT_INPUTS="$(./release-input-fingerprint.sh)"
[[ -n "$STAMP_INPUTS" ]] || { echo "ERROR: verification stamp has no release-input fingerprint" >&2; exit 1; }
[[ "$STAMP_INPUTS" == "$CURRENT_INPUTS" ]] || { echo "ERROR: verified release inputs $STAMP_INPUTS != current $CURRENT_INPUTS" >&2; exit 1; }
if [[ -n "${ACOUSTIC_REQUIRE_KOTLIN_VERSION:-}" && "$STAMP_KOTLIN" != "$ACOUSTIC_REQUIRE_KOTLIN_VERSION" ]]; then
  echo "ERROR: verified Kotlin $STAMP_KOTLIN != required $ACOUSTIC_REQUIRE_KOTLIN_VERSION" >&2
  exit 1
fi
KOTLIN_HOME_DIR="$(cat "$OUT/kotlin-home.path")"
KOTLIN_LIB="$KOTLIN_HOME_DIR/lib"
KOTLIN_CP="$KOTLIN_LIB/kotlin-stdlib.jar:$KOTLIN_LIB/kotlin-stdlib-jdk7.jar:$KOTLIN_LIB/kotlin-stdlib-jdk8.jar"
for lib in kotlin-stdlib.jar kotlin-stdlib-jdk7.jar kotlin-stdlib-jdk8.jar; do test -f "$KOTLIN_LIB/$lib" || { echo "ERROR: verified Kotlin runtime missing: $KOTLIN_LIB/$lib" >&2; exit 1; }; done

test -d "$OUT/forge-classes" || { echo "ERROR: verified production classes missing" >&2; exit 1; }
test -d "$OUT/forge-stubs" || { echo "ERROR: verified Forge stubs missing" >&2; exit 1; }
test -f "$OUT/shaderpacks/AcousticShaders-Reference-Hybrid.zip" || ./tools/verification/1.12.2/scripts/dev-build-shaderpacks.sh >/dev/null
rm -rf "$DIST" "$OUT/release-resources" "$OUT/runtime-smoke" "$OUT/srg-gui-smoke" "$OUT/srg-gui-smoke-work"
mkdir -p "$DIST" "$OUT/release-resources" "$OUT/runtime-smoke" "$OUT/srg-gui-smoke" "$OUT/srg-gui-smoke-work"
cp -a "$ROOT/minecraft-1.12.2/src/forge/resources/." "$OUT/release-resources/"

python3 - "$OUT/forge-classes" "$OUT/release-resources" "$JAR" <<'PYJAR'
from pathlib import Path
import sys,zipfile
classes=Path(sys.argv[1]); resources=Path(sys.argv[2]); jar=Path(sys.argv[3])
seen=set()
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    def put(name,data):
        if name in seen:return
        seen.add(name); info=zipfile.ZipInfo(name,(2026,8,31,20,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o644<<16;z.writestr(info,data)
    manifest=(resources/'META-INF/MANIFEST.MF').read_bytes(); put('META-INF/MANIFEST.MF',manifest)
    for base in (classes,resources):
        for p in sorted(x for x in base.rglob('*') if x.is_file()):
            name=p.relative_to(base).as_posix()
            if name=='META-INF/MANIFEST.MF':continue
            put(name,p.read_bytes())
PYJAR

cp "$OUT/shaderpacks/AcousticShaders-Reference-Hybrid.zip" "$DIST/"
python3 - "$ROOT/examples/material-resource-pack" "$DIST/AcousticData-Example-ResourcePack.zip" <<'PYDATA'
from pathlib import Path
import sys,zipfile
root=Path(sys.argv[1]);out=Path(sys.argv[2])
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p in sorted(x for x in root.rglob('*') if x.is_file()):
        i=zipfile.ZipInfo(p.relative_to(root).as_posix(),(2026,8,31,20,0,0));i.compress_type=zipfile.ZIP_DEFLATED;i.external_attr=0o644<<16;z.writestr(i,p.read_bytes())
PYDATA
unzip -t "$JAR" >/dev/null
for entry in \
  dev/acoustic/mc1122/forge/AcousticShadersForgeMod.class \
  dev/acoustic/mc1122/forge/AcousticGuiFactory.class \
  dev/acoustic/mc1122/forge/GuiAcousticShaders.class \
  dev/acoustic/mc1122/forge/GuiAcousticShaderOptions.class \
  dev/acoustic/mc1122/mixin/MixinSourceLWJGLOpenAL.class \
  dev/acoustic/mc1122/mixin/MixinSoundSystem.class \
  dev/acoustic/mc1122/mixin/MixinSourceLifecycle.class \
  dev/acoustic/mc1122/forge/OpenClGeometricBackend.class \
  dev/acoustic/mc1122/forge/OpenClFdtdBackend.class \
  dev/acoustic/mc1122/forge/CudaSupport.class \
  dev/acoustic/mc1122/forge/CudaFdtdBackend.class \
  dev/acoustic/mc1122/forge/CudaGeometricBackend.class \
  dev/acoustic/mc1122/forge/LegacyProjectileEmitterManager.class \
  dev/acoustic/mc1122/forge/LegacyDirectPathDiagnostic.class \
  dev/acoustic/mc1122/forge/LegacySoundEvents.class \
  assets/acousticshaders/sounds.json assets/acousticshaders/sounds/projectile/flight.ogg \
  assets/acousticshaders/cuda/fdtd.cu assets/acousticshaders/cuda/rays.cu \
  mixins.acousticshaders.json mcmod.info assets/acousticshaders/shaderpacks/AcousticShaders-Reference-Hybrid.zip; do
  unzip -Z1 "$JAR" | grep -Fx "$entry" >/dev/null || { echo "ERROR: missing JAR entry: $entry" >&2; exit 1; }
done
if unzip -Z1 "$JAR" | grep -E '^(net/minecraftforge|org/spongepowered|paulscode|org/lwjgl|com/sun/jna|kotlin)/.*\.class$' >/dev/null; then
  echo 'ERROR: compile stubs or Kotlin runtime leaked into release JAR' >&2; exit 1
fi
python3 - "$JAR" <<'PYVERIFY'
import sys,zipfile,struct
p=sys.argv[1];count=0
with zipfile.ZipFile(p) as z:
    for n in z.namelist():
        if n.endswith('.class'):
            b=z.read(n)
            if b[:4]!=b'\xca\xfe\xba\xbe':raise SystemExit('bad class '+n)
            major=struct.unpack('>H',b[6:8])[0]
            if major!=52:raise SystemExit(f'non-Java8 class {n}: major={major}')
            count+=1
print(f'[PASS] release JAR structural verification: {count} Java-8 classes')
PYVERIFY

for MIXIN_NAME in MixinSourceLWJGLOpenAL MixinSoundSystem MixinSourceLifecycle; do
  javap -classpath "$JAR" -v "dev.acoustic.mc1122.mixin.${MIXIN_NAME}" > "$OUT/release-${MIXIN_NAME}-javap.txt"
  python3 - "$OUT/release-${MIXIN_NAME}-javap.txt" "$MIXIN_NAME" <<'PYMIX'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text(errors='replace');name=sys.argv[2];idx=s.rfind('RuntimeInvisibleAnnotations:')
if idx<0 or 'org.spongepowered.asm.mixin.Mixin(' not in s[idx:]:raise SystemExit('ERROR: packaged '+name+' @Mixin retention mismatch')
PYMIX
done
echo '[PASS] packaged production Mixins CLASS-retention verification'

find minecraft-1.12.2/runtime-smoke/src/main/java -name '*.java' | sort > "$OUT/runtime-smoke-sources.txt"
javac --release 8 -Xlint:all,-options -Werror -cp "$OUT/forge-stubs:$JAR:$KOTLIN_CP" -d "$OUT/runtime-smoke" @"$OUT/runtime-smoke-sources.txt"
PROJECTILE_LINK_JAVAP="$OUT/release-projectile-moving-sound-linkage-javap.txt"
javap -classpath "$JAR" -v 'dev.acoustic.mc1122.forge.LegacyProjectileEmitterManager$ProjectileFlightSound' > "$PROJECTILE_LINK_JAVAP"
./tools/verification/1.12.2/scripts/dev-verification-tool.sh verify-projectile-linkage "$PROJECTILE_LINK_JAVAP"
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.LegacyForgeSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.ProjectileEmitterSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.EffectsDisableProjectileSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.EffectsDisableAudioStateSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.SoftwareWetToggleSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.SoftwareWetActivationSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.PropagationDelaySmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.DopplerWithoutEfxSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.SourceIdReuseSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.AudioContextReplacementSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.WorldSessionReplacementSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.RoomWorkerWorldBoundarySmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.NativeApplyWorldBoundarySmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.NativeWetApplyWorldBoundarySmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.NativeApplyCloseBoundarySmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.RuntimeRebindSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.RuntimeActivationGenerationSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.RuntimeCloseRaceSmokeTest
java -ea -cp "$OUT/runtime-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.DirectPathDiagnosticSmokeTest
find minecraft-1.12.2/srg-gui-smoke/src/main/java -name '*.java' | sort > "$OUT/srg-gui-smoke-sources.txt"
javac --release 8 -Xlint:all,-options -Werror -cp "$OUT/forge-stubs:$JAR:$KOTLIN_CP" -d "$OUT/srg-gui-smoke" @"$OUT/srg-gui-smoke-sources.txt"
( cd "$OUT/srg-gui-smoke-work" && java -ea -cp "$OUT/srg-gui-smoke:$OUT/forge-stubs:$JAR:$KOTLIN_CP:$ROOT/minecraft-1.12.2/src/forge/resources" dev.acoustic.mc1122.forge.SrgGuiRuntimeSmokeTest )

(
  cd "$DIST"
  sha256sum "acoustic-shaders-mc1122-$VERSION.jar" AcousticShaders-Reference-Hybrid.zip AcousticData-Example-ResourcePack.zip > SHA256SUMS.txt
)
SOURCE="$DIST/acoustic-shaders-source-$VERSION.tar.gz"
tar --sort=name --mtime='2026-08-31 20:00:00 UTC' --owner=0 --group=0 --numeric-owner -czf "$SOURCE" \
  --exclude='./.git' --exclude='./out' --exclude='./dist' --exclude='./examples/reference-pack.zip' .
( cd "$DIST" && sha256sum "$(basename "$SOURCE")" >> SHA256SUMS.txt )
python3 - "$DIST" "$VERSION" <<'PYBUNDLE'
from pathlib import Path
import sys,zipfile
root=Path(sys.argv[1]);v=sys.argv[2];out=root/f'AcousticShaders-mc1122-{v}-ALL-IN-ONE.zip'
names=[f'acoustic-shaders-mc1122-{v}.jar','AcousticShaders-Reference-Hybrid.zip','AcousticData-Example-ResourcePack.zip','SHA256SUMS.txt',f'acoustic-shaders-source-{v}.tar.gz']
project=root.parent
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    def put(name,data):
        i=zipfile.ZipInfo(name,(2026,8,31,20,0,0));i.compress_type=zipfile.ZIP_DEFLATED;i.external_attr=0o644<<16;z.writestr(i,data)
    for n in names:put(n,(root/n).read_bytes())
    for public_name in ('README.md','README.ru.md','LICENSE','CHANGELOG.md','CONTRIBUTING.md','SECURITY.md'):
        p=project/public_name
        if p.is_file():put('Documentation/'+public_name,p.read_bytes())
    for base_name in ('docs','spec'):
        base=project/base_name
        for p in sorted(x for x in base.rglob('*') if x.is_file()):put('Documentation/'+p.relative_to(project).as_posix(),p.read_bytes())
print('[PASS] all-in-one bundle',out.name)
PYBUNDLE
unzip -t "$DIST/AcousticShaders-mc1122-$VERSION-ALL-IN-ONE.zip" >/dev/null
printf '[PASS] packaged verified HEAD=%s Kotlin=%s\n' "$STAMP_HEAD" "$STAMP_KOTLIN"
sha256sum "$JAR" "$DIST/acoustic-shaders-source-$VERSION.tar.gz" "$DIST/AcousticShaders-mc1122-$VERSION-ALL-IN-ONE.zip"
