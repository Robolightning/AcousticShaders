#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

KOTLIN_ZIP="${ACOUSTIC_KOTLIN_1350_ZIP:-}"
JDK8="${ACOUSTIC_JDK8_HOME:-}"
EXPECTED_KOTLIN_SHA256='69424091a6b7f52d93eed8bba2ace921b02b113dbb71388d704f8180a6bdc6ec'

[[ -f "$KOTLIN_ZIP" ]] || { echo 'ERROR: set ACOUSTIC_KOTLIN_1350_ZIP to kotlin-compiler-1.3.50.zip' >&2; exit 2; }
[[ -x "$JDK8/bin/java" && -x "$JDK8/bin/javap" ]] || { echo 'ERROR: set ACOUSTIC_JDK8_HOME to a JDK 8 home' >&2; exit 2; }
command -v sha256sum >/dev/null 2>&1 || { echo 'ERROR: sha256sum is required' >&2; exit 2; }
command -v unzip >/dev/null 2>&1 || { echo 'ERROR: unzip is required' >&2; exit 2; }

actual_sha="$(sha256sum "$KOTLIN_ZIP" | awk '{print $1}')"
[[ "$actual_sha" == "$EXPECTED_KOTLIN_SHA256" ]] || { echo "ERROR: Kotlin 1.3.50 compiler SHA-256 mismatch: $actual_sha" >&2; exit 2; }
"$JDK8/bin/java" -version 2>&1 | grep -E 'version "1\.8\.' >/dev/null || { echo 'ERROR: ACOUSTIC_JDK8_HOME is not Java 8' >&2; exit 2; }

WORK="$ROOT/out/1.13.2-shared"
TOOLCHAIN="$WORK/toolchain"
CLASSES="$WORK/classes"
SOURCES="$WORK/sources.txt"
rm -rf "$WORK"
mkdir -p "$TOOLCHAIN" "$CLASSES"
unzip -q "$KOTLIN_ZIP" -d "$TOOLCHAIN"
KOTLINC="$TOOLCHAIN/kotlinc/bin/kotlinc"
[[ -f "$KOTLINC" ]] || { echo 'ERROR: Kotlin compiler launcher missing after extraction' >&2; exit 2; }
version="$(JAVA_HOME="$JDK8" bash "$KOTLINC" -version 2>&1)"
grep -F 'kotlinc-jvm 1.3.50' <<<"$version" >/dev/null || { echo "ERROR: unexpected Kotlin compiler: $version" >&2; exit 2; }

find \
  acoustic-api/src/main/kotlin \
  acoustic-platform-api/src/main/kotlin \
  acoustic-core/src/main/kotlin \
  -type f -name '*.kt' -print | LC_ALL=C sort > "$SOURCES"
[[ -s "$SOURCES" ]] || { echo 'ERROR: shared Kotlin source list is empty' >&2; exit 2; }

JAVA_HOME="$JDK8" bash "$KOTLINC" \
  -jdk-home "$JDK8" \
  -jvm-target 1.8 \
  -Werror \
  -d "$CLASSES" \
  "@$SOURCES"

class_count=0
max_major=0
while IFS= read -r -d '' class_file; do
  class_count=$((class_count + 1))
  major="$("$JDK8/bin/javap" -verbose "$class_file" | awk '/major version:/ {print $3; exit}')"
  [[ "$major" =~ ^[0-9]+$ ]] || { echo "ERROR: cannot read class major: $class_file" >&2; exit 1; }
  (( major > max_major )) && max_major="$major"
  (( major <= 52 )) || { echo "ERROR: non-Java-8 classfile major=$major: $class_file" >&2; exit 1; }
done < <(find "$CLASSES" -type f -name '*.class' -print0)
(( class_count > 0 )) || { echo 'ERROR: shared compile produced no classfiles' >&2; exit 1; }

printf '[PASS] 1.13.2 shared compile: Kotlin 1.3.50, JDK 8 API/runtime, %d classfiles, max major=%d\n' "$class_count" "$max_major"
