#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 2 ]]; then echo "usage: $0 OUT_DIR CLASSPATH SOURCE_DIR..." >&2; exit 2; fi
OUT="$1"; CP="$2"; shift 2
DIRS=()
WORK_ROOT="$(pwd -P)"
for p in "$@"; do
  [[ -d "$p" ]] || continue
  # javac/kotlinc do not apply MSYS path conversion to paths stored inside @argfiles.
  # Keep workspace-owned sources relative so the same response files work on Linux and
  # on Windows Git-Bash; leave genuinely external source roots unchanged.
  if [[ "$p" == "$WORK_ROOT/"* ]]; then DIRS+=("${p#"$WORK_ROOT/"}"); else DIRS+=("$p"); fi
done
mkdir -p "$OUT"
LIST_DIR="$(dirname "$OUT")"
BASE="$(basename "$OUT")"
ALL="$LIST_DIR/${BASE}-all-sources.txt"; KT="$LIST_DIR/${BASE}-kotlin-sources.txt"; JAVA="$LIST_DIR/${BASE}-java-sources.txt"
: > "$ALL"; : > "$KT"; : > "$JAVA"
if [[ ${#DIRS[@]} -gt 0 ]]; then
  find "${DIRS[@]}" -type f \( -name '*.kt' -o -name '*.java' \) | sort > "$ALL"
  find "${DIRS[@]}" -type f -name '*.kt' | sort > "$KT"
  find "${DIRS[@]}" -type f -name '*.java' | sort > "$JAVA"
fi
KOTLINC_BIN="${ACOUSTIC_KOTLINC:-$(command -v kotlinc)}"
[[ -x "$KOTLINC_BIN" ]] || { echo "ERROR: kotlinc not executable: $KOTLINC_BIN" >&2; exit 1; }
KOTLIN_VERSION="$($KOTLINC_BIN -version 2>&1 | sed -n 's/.*kotlinc-jvm \([^ ]*\).*/\1/p' | head -1)"
[[ -n "$KOTLIN_VERSION" ]] || { echo "ERROR: unable to determine Kotlin compiler version from $KOTLINC_BIN" >&2; exit 1; }
if [[ -n "${ACOUSTIC_REQUIRE_KOTLIN_VERSION:-}" && "$KOTLIN_VERSION" != "$ACOUSTIC_REQUIRE_KOTLIN_VERSION" ]]; then
  echo "ERROR: Kotlin $ACOUSTIC_REQUIRE_KOTLIN_VERSION required, found $KOTLIN_VERSION at $KOTLINC_BIN" >&2
  exit 1
fi
KOTLIN_HOME_DIR="$(cd "$(dirname "$KOTLINC_BIN")/.." && pwd)"
KOTLIN_LIB="$KOTLIN_HOME_DIR/lib"
KOTLIN_CP="$KOTLIN_LIB/kotlin-stdlib.jar:$KOTLIN_LIB/kotlin-stdlib-jdk7.jar:$KOTLIN_LIB/kotlin-stdlib-jdk8.jar"
printf '%s\n' "$KOTLIN_VERSION" > "$LIST_DIR/kotlin-compiler.version"
printf '%s\n' "$KOTLIN_HOME_DIR" > "$LIST_DIR/kotlin-home.path"
if [[ -s "$KT" ]]; then
  JVM_DEFAULT_ARG="-jvm-default=no-compatibility"
  if [[ "$KOTLIN_VERSION" == 1.* ]]; then
    # Kotlin 1.9 has only the legacy spelling; it is ABI-equivalent to 2.x no-compatibility.
    JVM_DEFAULT_ARG="-Xjvm-default=all"
  fi
  # JVM target alone is insufficient when kotlinc runs on JDK 9+: NIO covariant-return
  # methods such as ByteBuffer.flip():ByteBuffer do not exist on Java 8. Fence both
  # classfile level and visible JDK API/descriptors to release 8.
  # -Xjdk-release=8 is the single source of truth here: Kotlin maps it to JVM target 8
  # and restricts visible JDK APIs/descriptors to Java 8. Passing -jvm-target alongside
  # it is rejected by kotlinc, so do not duplicate the target flag.
  KARGS=(-Xjdk-release=8 "$JVM_DEFAULT_ARG" -Werror -d "$OUT")
  # Kotlin 1.9 needs the experimental 2.0 language switch to exercise K2 locally.
  # Kotlin 2.x is already K2 and should compile with its own default language level.
  if [[ "${ACOUSTIC_LOCAL_K2:-1}" == "1" && "$KOTLIN_VERSION" == 1.* ]]; then
    KARGS=(-language-version 2.0 -Xsuppress-version-warnings "${KARGS[@]}")
  fi
  [[ -n "$CP" ]] && KARGS+=(-classpath "$CP")
  "$KOTLINC_BIN" -J-Xms128m -J-Xmx1024m "${KARGS[@]}" @"$ALL"
fi
if [[ -s "$JAVA" ]]; then
  JCP="$OUT:$KOTLIN_CP"; [[ -n "$CP" ]] && JCP="$JCP:$CP"
  javac --release 8 -Xlint:all,-options -Werror -cp "$JCP" -d "$OUT" @"$JAVA"
fi
