#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

KOTLIN_ZIP="${ACOUSTIC_KOTLIN_1350_ZIP:-}"
EXPECTED_KOTLIN_SHA256='69424091a6b7f52d93eed8bba2ace921b02b113dbb71388d704f8180a6bdc6ec'
BOOT='minecraft-1.13.2/src/main/kotlin/dev/acoustic/mc1132/forge/AcousticShadersForgeMod.kt'
STUB_ROOT='tools/verification/1.13.2/compile-stubs/src/main/java'
WORK="$ROOT/out/1.13.2-javafml-bootstrap"

[[ -f "$KOTLIN_ZIP" ]] || { echo 'ERROR: set ACOUSTIC_KOTLIN_1350_ZIP to kotlin-compiler-1.3.50.zip' >&2; exit 2; }
[[ -s "$BOOT" ]] || { echo "ERROR: missing bootstrap source: $BOOT" >&2; exit 2; }
for cmd in javac javap sha256sum unzip; do command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: required command missing: $cmd" >&2; exit 2; }; done
actual_sha="$(sha256sum "$KOTLIN_ZIP" | awk '{print $1}')"
[[ "$actual_sha" == "$EXPECTED_KOTLIN_SHA256" ]] || { echo "ERROR: Kotlin 1.3.50 compiler SHA-256 mismatch: $actual_sha" >&2; exit 2; }

rm -rf "$WORK"
mkdir -p "$WORK/stubs" "$WORK/classes" "$WORK/toolchain"
find "$STUB_ROOT" -type f -name '*.java' -print | LC_ALL=C sort > "$WORK/stubs.txt"
javac --release 8 -Xlint:all,-options -Werror -d "$WORK/stubs" @"$WORK/stubs.txt"
unzip -q "$KOTLIN_ZIP" -d "$WORK/toolchain"
KOTLINC="$WORK/toolchain/kotlinc/bin/kotlinc"
version="$(bash "$KOTLINC" -version 2>&1)"
grep -F 'kotlinc-jvm 1.3.50' <<<"$version" >/dev/null || { echo "ERROR: unexpected Kotlin compiler: $version" >&2; exit 2; }
bash "$KOTLINC" -jvm-target 1.8 -Werror -classpath "$WORK/stubs" -d "$WORK/classes" "$BOOT"

class_count=0
max_major=0
while IFS= read -r -d '' class_file; do
  class_count=$((class_count + 1))
  major="$(javap -verbose "$class_file" | awk '/major version:/ {print $3; exit}')"
  [[ "$major" =~ ^[0-9]+$ ]] || { echo "ERROR: cannot read class major: $class_file" >&2; exit 1; }
  (( major > max_major )) && max_major="$major"
  (( major <= 52 )) || { echo "ERROR: non-Java-8 bootstrap classfile major=$major: $class_file" >&2; exit 1; }
done < <(find "$WORK/classes" -type f -name '*.class' -print0)
(( class_count > 0 )) || { echo 'ERROR: bootstrap compile produced no classfiles' >&2; exit 1; }

javap -classpath "$WORK/classes:$WORK/stubs" -c -p dev.acoustic.mc1132.forge.AcousticShadersForgeMod > "$WORK/bootstrap.javap"
grep -F 'FMLJavaModLoadingContext.get' "$WORK/bootstrap.javap" >/dev/null
grep -F 'FMLJavaModLoadingContext.getModEventBus' "$WORK/bootstrap.javap" >/dev/null
grep -F 'IEventBus.addListener' "$WORK/bootstrap.javap" >/dev/null
javap -classpath "$WORK/classes:$WORK/stubs" -p dev.acoustic.mc1132.forge.AcousticShadersForgeMod > "$WORK/bootstrap-signature.javap"
grep -F 'public dev.acoustic.mc1132.forge.AcousticShadersForgeMod();' "$WORK/bootstrap-signature.javap" >/dev/null
printf '[PASS] 1.13.2 JavaFML bootstrap compile contract: Kotlin 1.3.50, public no-arg @Mod, Java-8 bytecode, %d classfiles, max major=%d\n' "$class_count" "$max_major"
