#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"

WINLAB_ROOT="${ACOUSTIC_WINLAB_ROOT:-}"
LWJGL_JAR="${ACOUSTIC_LWJGL2_JAR:-}"
KOTLINC_BIN="${ACOUSTIC_KOTLINC:-$(command -v kotlinc || true)}"
NATIVE_LIST="${ACOUSTIC_WINLAB_NATIVE_ARCHIVES:-$ROOT/out/forge1122-client-preflight/windows-native-archives.txt}"
OUT="${ACOUSTIC_REAL_OPENAL_WET_OUT:-$ROOT/out/winlab-real-openal-wet}"

[[ -n "$WINLAB_ROOT" ]] || { echo 'ERROR: ACOUSTIC_WINLAB_ROOT is required' >&2; exit 2; }
WINLAB_ROOT="$(cd "$WINLAB_ROOT" 2>/dev/null && pwd)" || { echo "ERROR: WinLab root does not exist: $WINLAB_ROOT" >&2; exit 1; }
if [[ ! -x "$WINLAB_ROOT/run" ]]; then
  mapfile -t kids < <(find "$WINLAB_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'winlab*' -print | sort)
  [[ ${#kids[@]} -eq 1 && -x "${kids[0]}/run" ]] || { echo "ERROR: WinLab run launcher missing under $WINLAB_ROOT" >&2; exit 1; }
  WINLAB_ROOT="${kids[0]}"
fi
RUN="$WINLAB_ROOT/run"

WINDOWS_JAVA="${ACOUSTIC_WINDOWS_JAVA8:-}"
if [[ -z "$WINDOWS_JAVA" && -n "${ACOUSTIC_WINLAB_JAVA:-}" ]]; then
  WINDOWS_JAVA="${ACOUSTIC_WINLAB_JAVA%/}/bin/java.exe"
fi
[[ -f "$WINDOWS_JAVA" ]] || { echo 'ERROR: set ACOUSTIC_WINDOWS_JAVA8 to Windows Java 8 java.exe (or ACOUSTIC_WINLAB_JAVA to its home)' >&2; exit 2; }
[[ -f "$LWJGL_JAR" ]] || { echo 'ERROR: ACOUSTIC_LWJGL2_JAR must point to the real Minecraft 1.12.2 LWJGL2 JAR' >&2; exit 2; }
[[ -x "$KOTLINC_BIN" ]] || { echo 'ERROR: exact Kotlin compiler is required through ACOUSTIC_KOTLINC/PATH' >&2; exit 2; }
[[ -s "$NATIVE_LIST" ]] || { echo "ERROR: Windows native archive list missing: $NATIVE_LIST (run tools/verification/1.12.2/scripts/dev-forge1122-client-preflight.sh first)" >&2; exit 2; }
[[ -d "$ROOT/out/forge-classes/dev/acoustic" ]] || { echo 'ERROR: out/forge-classes missing; run tools/verification/1.12.2/scripts/dev-legacy-contract.sh first' >&2; exit 2; }

for cmd in javac python3 unzip timeout grep; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: required command missing: $cmd" >&2; exit 1; }
done

KOTLIN_VERSION="$($KOTLINC_BIN -version 2>&1 | sed -n 's/.*kotlinc-jvm \([^ ]*\).*/\1/p' | head -1)"
[[ "$KOTLIN_VERSION" == '2.4.0' ]] || { echo "ERROR: exact Kotlin 2.4.0 required, got ${KOTLIN_VERSION:-<unknown>}" >&2; exit 1; }
KOTLIN_HOME="$(cd "$(dirname "$KOTLINC_BIN")/.." && pwd)"
KOTLIN_JARS=(
  "$KOTLIN_HOME/lib/kotlin-stdlib.jar"
  "$KOTLIN_HOME/lib/kotlin-stdlib-jdk7.jar"
  "$KOTLIN_HOME/lib/kotlin-stdlib-jdk8.jar"
)
for jar_file in "${KOTLIN_JARS[@]}"; do
  [[ -f "$jar_file" ]] || { echo "ERROR: Kotlin runtime JAR missing: $jar_file" >&2; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/natives"
while IFS= read -r archive; do
  [[ -f "$archive" ]] || { echo "ERROR: Windows native archive missing: $archive" >&2; exit 1; }
  unzip -qo "$archive" -d "$OUT/natives"
done < "$NATIVE_LIST"
[[ -f "$OUT/natives/OpenAL32.dll" || -f "$OUT/natives/OpenAL64.dll" ]] || { echo 'ERROR: extracted native set does not contain OpenAL' >&2; exit 1; }

HOST_CP="$ROOT/out/forge-classes:$LWJGL_JAR"
for jar_file in "${KOTLIN_JARS[@]}"; do HOST_CP="$HOST_CP:$jar_file"; done
javac --release 8 -Xlint:all,-options -Werror \
  -cp "$HOST_CP" \
  -d "$OUT/classes" \
  "$ROOT/tools/verification/1.12.2/dev-tools/RealOpenALWetProbe.java"

wine_z_path() {
  python3 - "$1" <<'PY'
from pathlib import Path
import sys
print('Z:' + str(Path(sys.argv[1]).resolve()).replace('/', '\\'))
PY
}

WINDOWS_CP="$(wine_z_path "$OUT/classes");$(wine_z_path "$ROOT/out/forge-classes");$(wine_z_path "$LWJGL_JAR")"
for jar_file in "${KOTLIN_JARS[@]}"; do WINDOWS_CP="$WINDOWS_CP;$(wine_z_path "$jar_file")"; done
WINDOWS_NATIVES="$(wine_z_path "$OUT/natives")"
LOG="$OUT/real-openal-wet.log"

set +e
TERM=xterm ALSOFT_DRIVERS=null timeout --signal=TERM --kill-after=5s 90s \
  "$RUN" wine "$WINDOWS_JAVA" -ea \
  "-Djava.library.path=$WINDOWS_NATIVES" \
  -cp "$WINDOWS_CP" \
  RealOpenALWetProbe >"$LOG" 2>&1
rc=$?
set -e
cat "$LOG"
if [[ $rc -ne 0 ]]; then
  echo "ERROR: real Windows Java 8/LWJGL2 software-wet OpenAL probe failed rc=$rc" >&2
  exit "$rc"
fi
grep -F 'ACOUSTIC-REAL-OPENAL-WET-OK applied=1 fallback=0' "$LOG" >/dev/null || {
  echo 'ERROR: real OpenAL wet probe did not report a successful production wet voice' >&2
  exit 1
}
printf '%s\n' '[PASS] real Windows Java 8 + LWJGL2/OpenAL software-wet backend (OpenAL Soft null device)'
