#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

TOOLCHAIN="${ACOUSTIC_FORGE113_TOOLCHAIN:-}"
WINLAB_ROOT="${ACOUSTIC_WINLAB_ROOT:-}"
MOD_JAR="${ACOUSTIC_FORGE113_MOD_JAR:-$ROOT/dist/acoustic-shaders-mc1122-0.3.0-rc19.jar}"
OUT="$ROOT/out/forge113-adapter-probe"

[[ -n "$TOOLCHAIN" ]] || { echo 'ERROR: ACOUSTIC_FORGE113_TOOLCHAIN is required' >&2; exit 2; }
[[ -n "$WINLAB_ROOT" ]] || { echo 'ERROR: ACOUSTIC_WINLAB_ROOT is required' >&2; exit 2; }
TOOLCHAIN="$(cd "$TOOLCHAIN" && pwd)"
WINLAB_ROOT="$(cd "$WINLAB_ROOT" && pwd)"

if [[ ! -x "$WINLAB_ROOT/run" ]]; then
  mapfile -t children < <(find "$WINLAB_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'winlab*' -print | sort)
  if [[ ${#children[@]} -eq 1 && -x "${children[0]}/run" ]]; then
    WINLAB_ROOT="${children[0]}"
  else
    echo "ERROR: WinLab run launcher missing under: $WINLAB_ROOT" >&2
    exit 1
  fi
fi
RUN="$WINLAB_ROOT/run"

VALIDATION="$TOOLCHAIN/metadata/VALIDATION.txt"
ADAPTER="$TOOLCHAIN/server/mods/forge-legacy-adapter-0.13.0.jar"
WINDOWS_JAVA="$TOOLCHAIN/java/bin/java.exe"
SERVER_JAR="$TOOLCHAIN/server/forge-1.13.2-25.0.223.jar"

for f in "$VALIDATION" "$ADAPTER" "$WINDOWS_JAVA" "$SERVER_JAR" "$MOD_JAR"; do
  [[ -f "$f" ]] || { echo "ERROR: required file missing: $f" >&2; exit 1; }
done

grep -F 'target=Minecraft 1.13.2 / Forge 25.0.223' "$VALIDATION" >/dev/null
grep -F 'serverOnly=true' "$VALIDATION" >/dev/null
grep -F 'clientAssetsRequired=false' "$VALIDATION" >/dev/null
grep -F 'eulaPreaccepted=false' "$VALIDATION" >/dev/null
EXPECTED_ADAPTER_SHA="$(sed -n 's/^adapterSha256=//p' "$VALIDATION" | tr -d '\r' | head -1)"
ACTUAL_ADAPTER_SHA="$(sha256sum "$ADAPTER" | awk '{print $1}')"
[[ -n "$EXPECTED_ADAPTER_SHA" && "$ACTUAL_ADAPTER_SHA" == "$EXPECTED_ADAPTER_SHA" ]] || {
  echo 'ERROR: Forge legacy adapter SHA-256 mismatch' >&2
  echo "expected=$EXPECTED_ADAPTER_SHA" >&2
  echo "actual=$ACTUAL_ADAPTER_SHA" >&2
  exit 1
}
printf '%s\n' '[PASS] Forge 1.13.2 / 25.0.223 server-only toolchain metadata + adapter SHA-256'

rm -rf "$OUT"
mkdir -p "$OUT"

cleanup() {
  set +e
  timeout 10s "$RUN" wineserver -k >/dev/null 2>&1
  set -e
}
trap cleanup EXIT

run_probe() {
  local label="$1" seconds="$2" log="$3"
  shift 3
  set +e
  timeout --signal=TERM --kill-after=5s "${seconds}s" "$@" >"$log" 2>&1
  local rc=$?
  set -e
  cat "$log"
  if [[ $rc -ne 0 ]]; then
    echo "ERROR: $label failed with rc=$rc" >&2
    exit "$rc"
  fi
}

printf '%s\n' '[Forge113] Windows Java 8 through WinLab'
run_probe 'Forge toolchain Windows Java 8' 45 "$OUT/windows-java-version.log" \
  "$RUN" wine "$WINDOWS_JAVA" -version
grep -F 'java version "1.8.' "$OUT/windows-java-version.log" >/dev/null || {
  echo 'ERROR: Forge toolchain Windows Java is not Java 8' >&2
  exit 1
}

printf '%s\n' '[Forge113] legacy adapter scans the freshly packaged Acoustic Shaders JAR'
run_probe 'Forge legacy adapter scan' 90 "$OUT/scan.log" \
  "$RUN" wine "$WINDOWS_JAVA" -cp "$ADAPTER" dev.legacyadapter.cli.Main scan "$MOD_JAR"
grep -F 'legacyMods=1' "$OUT/scan.log" >/dev/null
grep -F 'mod.modid=acousticshaders' "$OUT/scan.log" >/dev/null
grep -F 'mod.class=dev.acoustic.mc1122.forge.AcousticShadersForgeMod' "$OUT/scan.log" >/dev/null
grep -F 'mod.lifecycleHandlers=3' "$OUT/scan.log" >/dev/null
printf '%s\n' '[PASS] Forge legacy adapter recognizes Acoustic Shaders metadata/lifecycle'

printf '%s\n' '[Forge113] server-only adapter boundary audit'
run_probe 'Forge legacy adapter audit' 90 "$OUT/audit.log" \
  "$RUN" wine "$WINDOWS_JAVA" -cp "$ADAPTER" dev.legacyadapter.cli.Main audit "$MOD_JAR"
grep -F 'UNSUPPORTED minecraft-class net.minecraft.client.Minecraft' "$OUT/audit.log" >/dev/null
grep -F 'UNSUPPORTED forge-class net.minecraftforge.fml.client.IModGuiFactory' "$OUT/audit.log" >/dev/null
UNSUPPORTED_COUNT="$(sed -n 's/^audit.unsupported=//p' "$OUT/audit.log" | tr -d '\r' | tail -1)"
[[ "$UNSUPPORTED_COUNT" =~ ^[0-9]+$ && "$UNSUPPORTED_COUNT" -gt 0 ]] || {
  echo 'ERROR: expected the server-only adapter to expose the client-only compatibility boundary' >&2
  exit 1
}
printf '[PASS] server-only adapter correctly exposes client-only API boundary (unsupported=%s)\n' "$UNSUPPORTED_COUNT"

ADAPTED_JAR="$OUT/acoustic-shaders-rc19-forge113-adapted.jar"
printf '%s\n' '[Forge113] structural transform of the packaged legacy JAR'
run_probe 'Forge legacy adapter transform' 120 "$OUT/transform.log" \
  "$RUN" wine "$WINDOWS_JAVA" -cp "$ADAPTER" dev.legacyadapter.cli.Main transform "$MOD_JAR" "$ADAPTED_JAR"
[[ -s "$ADAPTED_JAR" ]] || { echo 'ERROR: adapter transform produced no output JAR' >&2; exit 1; }
grep -F 'classesVisited=' "$OUT/transform.log" >/dev/null
python3 - "$ADAPTED_JAR" <<'PY'
import struct, sys, zipfile
p=sys.argv[1]
with zipfile.ZipFile(p) as z:
    classes=[n for n in z.namelist() if n.endswith('.class')]
    if not classes:
        raise SystemExit('adapted JAR contains no classes')
    for n in classes:
        data=z.read(n)
        if data[:4] != b'\xca\xfe\xba\xbe':
            raise SystemExit('bad classfile: '+n)
        major=struct.unpack('>H', data[6:8])[0]
        if major > 52:
            raise SystemExit(f'non-Java8 class after adapter transform: {n}: {major}')
print(f'[PASS] transformed JAR remains Java-8 bytecode ({len(classes)} classes)')
PY

# Real Forge/ModLauncher baseline: deliberately keep the EULA false. This proves discovery and
# bootstrap without accepting a legal agreement or creating a playable server/world.
BASE="$OUT/server-baseline"
mkdir -p "$BASE"
cp -a "$TOOLCHAIN/server/." "$BASE/"
printf 'eula=false\n' > "$BASE/eula.txt"
rm -rf "$BASE/logs"
mkdir -p "$BASE/logs"
printf '%s\n' '[Forge113] real Forge/ModLauncher pre-EULA bootstrap under Windows Java 8'
(
  cd "$BASE"
  run_probe 'Forge 25.0.223 pre-EULA bootstrap' 120 "$OUT/server-console.log" \
    "$RUN" wine "$WINDOWS_JAVA" -Xms256M -Xmx1024M -Dfile.encoding=UTF-8 -jar forge-1.13.2-25.0.223.jar nogui
)
grep -F 'eula=false' "$BASE/eula.txt" >/dev/null || {
  echo 'ERROR: EULA state changed unexpectedly; this diagnostic must never accept it' >&2
  exit 1
}
grep -F 'You need to agree to the EULA' "$OUT/server-console.log" >/dev/null || {
  echo 'ERROR: expected pre-EULA Forge/Minecraft stop was not observed' >&2
  exit 1
}
grep -F 'Parsing mod file candidate' "$BASE/logs/debug.log" | grep -F 'forge-legacy-adapter-0.13.0.jar' >/dev/null || {
  echo 'ERROR: real Forge discovery did not parse the legacy adapter mod' >&2
  exit 1
}
printf '%s\n' '[PASS] real Forge 25.0.223 ModLauncher discovers legacy adapter and stops with EULA=false'
printf '%s\n' '[INFO] This is a diagnostic boundary probe, NOT a compatibility/release gate for the client-only Minecraft 1.12.2 mod.'
