#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
: "${ACOUSTIC_FORGE1122_BUNDLE:?set ACOUSTIC_FORGE1122_BUNDLE}"
: "${ACOUSTIC_MC1122_HOME:?set ACOUSTIC_MC1122_HOME}"
: "${ACOUSTIC_WINLAB_ROOT:?set ACOUSTIC_WINLAB_ROOT}"
: "${ACOUSTIC_WINDOWS_JAVA8:?set ACOUSTIC_WINDOWS_JAVA8}"
: "${ACOUSTIC_MIXINBOOTER_JAR:?set ACOUSTIC_MIXINBOOTER_JAR}"
: "${ACOUSTIC_FORGELIN_CONTINUOUS_JAR:?set ACOUSTIC_FORGELIN_CONTINUOUS_JAR}"
MOD_JAR="${ACOUSTIC_MOD_JAR:-$ROOT/dist/acoustic-shaders-mc1122-0.3.0.jar}"
OUT="${ACOUSTIC_CLIENT_OUT:-$ROOT/out/forge1122-client-launch}"
BOOT_LEVEL="${ACOUSTIC_CLIENT_BOOT_LEVEL:-init}"
BOOT_TIMEOUT="${ACOUSTIC_CLIENT_BOOT_TIMEOUT:-120}"
NULL_AUDIO="${ACOUSTIC_CLIENT_NULL_AUDIO:-0}"
REUSE_GAME="${ACOUSTIC_CLIENT_REUSE_GAME:-0}"
DEBUG_RUNTIME="${ACOUSTIC_CLIENT_DEBUG:-0}"
MIXIN_EXPORT="${ACOUSTIC_CLIENT_MIXIN_EXPORT:-0}"
SOUND_EVENT_PROBE_JAR="${ACOUSTIC_CLIENT_SOUND_EVENT_PROBE_JAR:-}"
REQUIRE_SOUND_EVENT_PROBE="${ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE:-0}"
EXPECTED_MOD_COUNT="${ACOUSTIC_CLIENT_EXPECTED_MOD_COUNT:-8}"
PROBE_SUCCESS_MARKER="${ACOUSTIC_CLIENT_PROBE_SUCCESS_MARKER:-ACOUSTIC-REAL-MINECRAFT-SOUND-EVENT-OK}"
CLIENT_PROFILE="${ACOUSTIC_CLIENT_PROFILE:-}"
CLIENT_COMPUTE_BACKEND="${ACOUSTIC_CLIENT_COMPUTE_BACKEND:-}"
CLIENT_RAY_COMPUTE_BACKEND="${ACOUSTIC_CLIENT_RAY_COMPUTE_BACKEND:-}"
REQUIRE_CUDA_FDTD="${ACOUSTIC_CLIENT_REQUIRE_CUDA_FDTD:-0}"

case "$BOOT_LEVEL" in init|full) ;; *) echo "ERROR: ACOUSTIC_CLIENT_BOOT_LEVEL must be init or full" >&2; exit 1;; esac
case "$MIXIN_EXPORT" in 0|1) ;; *) echo "ERROR: ACOUSTIC_CLIENT_MIXIN_EXPORT must be 0 or 1" >&2; exit 1;; esac
case "$REQUIRE_SOUND_EVENT_PROBE" in 0|1) ;; *) echo "ERROR: ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE must be 0 or 1" >&2; exit 1;; esac
case "$REQUIRE_CUDA_FDTD" in 0|1) ;; *) echo "ERROR: ACOUSTIC_CLIENT_REQUIRE_CUDA_FDTD must be 0 or 1" >&2; exit 1;; esac
[[ "$EXPECTED_MOD_COUNT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: ACOUSTIC_CLIENT_EXPECTED_MOD_COUNT must be a positive integer" >&2; exit 1; }
[[ "$PROBE_SUCCESS_MARKER" != *$'\n'* && -n "$PROBE_SUCCESS_MARKER" ]] || { echo "ERROR: ACOUSTIC_CLIENT_PROBE_SUCCESS_MARKER must be one non-empty line" >&2; exit 1; }
[[ "$BOOT_TIMEOUT" =~ ^[0-9]+$ ]] && (( BOOT_TIMEOUT > 0 )) || { echo 'ERROR: ACOUSTIC_CLIENT_BOOT_TIMEOUT must be a positive integer' >&2; exit 1; }
if [[ -n "$CLIENT_PROFILE" && ! "$CLIENT_PROFILE" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "ERROR: unsafe ACOUSTIC_CLIENT_PROFILE: $CLIENT_PROFILE" >&2; exit 1
fi
case "$CLIENT_COMPUTE_BACKEND" in ''|AUTO|CUDA|OPENCL|CPU_PARALLEL|CPU_SCALAR) ;; *) echo "ERROR: unsupported ACOUSTIC_CLIENT_COMPUTE_BACKEND: $CLIENT_COMPUTE_BACKEND" >&2; exit 1;; esac
case "$CLIENT_RAY_COMPUTE_BACKEND" in ''|AUTO|CUDA|OPENCL|CPU_PARALLEL) ;; *) echo "ERROR: unsupported ACOUSTIC_CLIENT_RAY_COMPUTE_BACKEND: $CLIENT_RAY_COMPUTE_BACKEND" >&2; exit 1;; esac
if [[ "$REQUIRE_CUDA_FDTD" == 1 ]]; then
  [[ "$REQUIRE_SOUND_EVENT_PROBE" == 1 ]] || { echo 'ERROR: CUDA FDTD proof requires the loaded-world sound-event probe' >&2; exit 1; }
  [[ "$CLIENT_PROFILE" == MAXIMUM ]] || { echo 'ERROR: CUDA FDTD proof requires ACOUSTIC_CLIENT_PROFILE=MAXIMUM' >&2; exit 1; }
  [[ "$CLIENT_COMPUTE_BACKEND" == CUDA ]] || { echo 'ERROR: CUDA FDTD proof requires ACOUSTIC_CLIENT_COMPUTE_BACKEND=CUDA' >&2; exit 1; }
  [[ "$CLIENT_RAY_COMPUTE_BACKEND" == CPU_PARALLEL ]] || { echo 'ERROR: CUDA FDTD proof requires ACOUSTIC_CLIENT_RAY_COMPUTE_BACKEND=CPU_PARALLEL so CUDA ray activity cannot satisfy the gate' >&2; exit 1; }
fi

ACOUSTIC_FORGE1122_BUNDLE="$ACOUSTIC_FORGE1122_BUNDLE" \
ACOUSTIC_MC1122_HOME="$ACOUSTIC_MC1122_HOME" \
  "$ROOT/dev-forge1122-client-preflight.sh"

for f in "$ACOUSTIC_WINDOWS_JAVA8" "$ACOUSTIC_MIXINBOOTER_JAR" "$ACOUSTIC_FORGELIN_CONTINUOUS_JAR" "$MOD_JAR"; do
  [[ -f "$f" ]] || { echo "ERROR: client launch dependency missing: $f" >&2; exit 1; }
done
if [[ -n "$SOUND_EVENT_PROBE_JAR" ]]; then
  [[ -f "$SOUND_EVENT_PROBE_JAR" ]] || { echo "ERROR: sound-event probe coremod missing: $SOUND_EVENT_PROBE_JAR" >&2; exit 1; }
fi
if [[ "$REQUIRE_SOUND_EVENT_PROBE" == 1 && -z "$SOUND_EVENT_PROBE_JAR" ]]; then
  echo "ERROR: ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE=1 requires ACOUSTIC_CLIENT_SOUND_EVENT_PROBE_JAR" >&2
  exit 1
fi
WINLAB_ROOT="$(cd "$ACOUSTIC_WINLAB_ROOT" && pwd)"
if [[ ! -x "$WINLAB_ROOT/run" ]]; then
  mapfile -t kids < <(find "$WINLAB_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'winlab*' -print | sort)
  [[ ${#kids[@]} -eq 1 && -x "${kids[0]}/run" ]] || { echo "ERROR: WinLab run launcher missing under $WINLAB_ROOT" >&2; exit 1; }
  WINLAB_ROOT="${kids[0]}"
fi
RUN="$WINLAB_ROOT/run"

if [[ "$REUSE_GAME" == 1 ]]; then
  mkdir -p "$OUT/game"; rm -rf "$OUT/game/mods" "$OUT/logs" "$OUT/natives"
else
  rm -rf "$OUT"
fi
mkdir -p "$OUT/game/mods" "$OUT/game/config/acousticshaders" "$OUT/natives" "$OUT/logs"
cat > "$OUT/game/config/forge.cfg" <<'FORGE_CFG'
# Acoustic Shaders WinLab-only Forge client smoke configuration.
general {
    B:disableVersionCheck=true
}
FORGE_CFG
cat > "$OUT/game/config/splash.properties" <<'SPLASH_CFG'
enabled=false
SPLASH_CFG
{
  printf 'debug=%s\n' "$([[ "$DEBUG_RUNTIME" == 1 || "$REQUIRE_CUDA_FDTD" == 1 ]] && echo true || echo false)"
  [[ -n "$CLIENT_PROFILE" ]] && printf 'profile=%s\n' "$CLIENT_PROFILE"
  [[ -n "$CLIENT_COMPUTE_BACKEND" ]] && printf 'option.COMPUTE_BACKEND=%s\n' "$CLIENT_COMPUTE_BACKEND"
  [[ -n "$CLIENT_RAY_COMPUTE_BACKEND" ]] && printf 'option.RAY_COMPUTE_BACKEND=%s\n' "$CLIENT_RAY_COMPUTE_BACKEND"
} > "$OUT/game/config/acousticshaders/runtime.properties"
cp "$MOD_JAR" "$OUT/game/mods/"
cp "$ACOUSTIC_MIXINBOOTER_JAR" "$OUT/game/mods/"
cp "$ACOUSTIC_FORGELIN_CONTINUOUS_JAR" "$OUT/game/mods/"
if [[ -n "$SOUND_EVENT_PROBE_JAR" ]]; then cp "$SOUND_EVENT_PROBE_JAR" "$OUT/game/mods/AcousticShaders-SoundEvent-Probe.jar"; fi

python3 - "$ROOT/out/forge1122-client-preflight/windows-native-archives.txt" "$OUT/natives" <<'PY'
import pathlib,sys,zipfile
lst=pathlib.Path(sys.argv[1]); out=pathlib.Path(sys.argv[2])
for line in lst.read_text().splitlines():
    if not line: continue
    with zipfile.ZipFile(line) as z:
        for n in z.namelist():
            if n.endswith('/') or n.startswith('META-INF/'): continue
            z.extract(n,out)
print('[PASS] Windows LWJGL/jinput/text2speech natives extracted')
PY

to_z() { local p; p="$(readlink -f "$1")"; printf 'Z:%s' "${p//\//\\}"; }
JAVA_Z="$(to_z "$ACOUSTIC_WINDOWS_JAVA8")"
NATIVES_Z="$(to_z "$OUT/natives")"
GAME_Z="$(to_z "$OUT/game")"
ASSETS_Z="$(to_z "$ACOUSTIC_MC1122_HOME/assets")"
mapfile -t CP_FILES < "$ROOT/out/forge1122-client-preflight/client-classpath.txt"
CP_Z=''
for p in "${CP_FILES[@]}"; do
  [[ -n "$p" ]] || continue
  z="$(to_z "$p")"; [[ -z "$CP_Z" ]] && CP_Z="$z" || CP_Z="$CP_Z;$z"
done

LOG="$OUT/logs/client-console.log"
XVFB_LOG="$OUT/logs/xvfb.log"
printf '[Forge1122] starting real Windows Forge 1.12.2 client level=%s nullAudio=%s reuseGame=%s mixinExport=%s soundEventProbe=%s expectedMods=%s probeMarker=%s profile=%s wave=%s rays=%s requireCudaFdtd=%s\n' \
  "$BOOT_LEVEL" "$NULL_AUDIO" "$REUSE_GAME" "$MIXIN_EXPORT" "$REQUIRE_SOUND_EVENT_PROBE" "$EXPECTED_MOD_COUNT" "$PROBE_SUCCESS_MARKER" \
  "${CLIENT_PROFILE:-default}" "${CLIENT_COMPUTE_BACKEND:-default}" "${CLIENT_RAY_COMPUTE_BACKEND:-default}" "$REQUIRE_CUDA_FDTD"

MIXIN_JVM_ARGS=()
if [[ "$MIXIN_EXPORT" == 1 ]]; then
  MIXIN_JVM_ARGS+=(
    -Dmixin.debug.export=true
    -Dmixin.debug.export.filter=paulscode.sound.libraries.SourceLWJGLOpenAL
  )
fi

PROBE_JVM_ARGS=()
if [[ "$REQUIRE_CUDA_FDTD" == 1 ]]; then
  PROBE_JVM_ARGS+=( -Dacousticshaders.probe.requireCudaFdtd=true )
fi

CMD=(
  "$RUN" wine "$JAVA_Z"
  -Xms256M -Xmx1024M -Dfile.encoding=UTF-8
  "${MIXIN_JVM_ARGS[@]}"
  "${PROBE_JVM_ARGS[@]}"
  "-Djava.library.path=$NATIVES_Z"
  -cp "$CP_Z"
  net.minecraft.launchwrapper.Launch
  --username AcousticShadersWinLab
  --version 1.12.2-forge-14.23.5.2864
  --gameDir "$GAME_Z"
  --assetsDir "$ASSETS_Z"
  --assetIndex 1.12
  --uuid 00000000000000000000000000000001
  --accessToken 0
  --userType legacy
  --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker
  --versionType Forge
)

XVFB_PID=''
stop_xvfb() {
  set +e
  if [[ -n "$XVFB_PID" ]]; then
    kill -TERM "$XVFB_PID" 2>/dev/null || true
    sleep 0.1
    kill -KILL "$XVFB_PID" 2>/dev/null || true
  fi
  set -e
}
trap stop_xvfb EXIT INT TERM

if command -v Xvfb >/dev/null 2>&1; then
  DISPLAY_NUM=''
  for n in $(seq 100 199); do
    [[ ! -S "/tmp/.X11-unix/X$n" ]] && { DISPLAY_NUM="$n"; break; }
  done
  [[ -n "$DISPLAY_NUM" ]] || { echo 'ERROR: no free Xvfb display in :100-:199' >&2; exit 1; }
  Xvfb ":$DISPLAY_NUM" -screen 0 1280x1024x24 -nolisten tcp -ac >"$XVFB_LOG" 2>&1 &
  XVFB_PID=$!; export DISPLAY=":$DISPLAY_NUM"
  for _ in $(seq 1 50); do
    [[ -S "/tmp/.X11-unix/X$DISPLAY_NUM" ]] && break
    kill -0 "$XVFB_PID" 2>/dev/null || { cat "$XVFB_LOG" >&2; echo 'ERROR: Xvfb exited during startup' >&2; exit 1; }
    sleep 0.1
  done
  [[ -S "/tmp/.X11-unix/X$DISPLAY_NUM" ]] || { echo 'ERROR: Xvfb display socket did not appear' >&2; exit 1; }
fi

: > "$LOG"
CLIENT_PID_FILE="$OUT/logs/client.pid"
rm -f "$CLIENT_PID_FILE"
SUPERVISOR_WALL_TIMEOUT=$((BOOT_TIMEOUT + 5))
set +e
timeout --signal=TERM --kill-after=2s "${SUPERVISOR_WALL_TIMEOUT}s" \
  python3 - "$LOG" "$CLIENT_PID_FILE" "$OUT/game" "$BOOT_LEVEL" "$BOOT_TIMEOUT" "$NULL_AUDIO" "$REQUIRE_SOUND_EVENT_PROBE" "$EXPECTED_MOD_COUNT" "$PROBE_SUCCESS_MARKER" "${CMD[@]}" <<'PY'
import os
import pathlib
import re
import subprocess
import sys
import time

log_path = pathlib.Path(sys.argv[1])
pid_path = pathlib.Path(sys.argv[2])
game_dir = pathlib.Path(sys.argv[3])
level = sys.argv[4]
timeout_s = int(sys.argv[5])
null_audio = sys.argv[6] == '1'
sound_event_probe_required = sys.argv[7] == '1'
expected_mod_count = int(sys.argv[8])
probe_success_marker = sys.argv[9]
cmd = sys.argv[10:]

env = os.environ.copy()
if null_audio:
    env['ALSOFT_DRIVERS'] = 'null'

fatal_re = re.compile(
    r'NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|UnsupportedClassVersionError|'
    r'MixinApplyError|MixinTransformerError|LWJGLException: Failed to create window|'
    r'Exception in thread "(?:main|Client thread)"|Game crashed!|Minecraft has crashed|'
    r'ACOUSTIC-[A-Z0-9-]*PROBE-FAIL'
)
init_markers = (
    'MinecraftForge v14.23.5.2864 Initialized',
    f'Forge Mod Loader has identified {expected_mod_count} mods to load',
    'Added acoustic-shaders-mc1122-0.3.0.jar to the classloader',
    'Acoustic Shaders Default Materials',
    'textures-atlas',
    '[AcousticShaders] initialized for Minecraft 1.12.2',
)
full_db_re = re.compile(r'\[AcousticShaders\] default acoustic database (?:rebuilt|cache hit): states=[1-9][0-9]*')
full_marker = f'Forge Mod Loader has successfully loaded {expected_mod_count} mods'
audio_markers = ('OpenAL initialized.', 'Sound engine started')

def read_log():
    try:
        return log_path.read_text(encoding='utf-8', errors='replace')
    except FileNotFoundError:
        return ''

def init_seen(text):
    return all(marker in text for marker in init_markers)

def full_seen(text):
    return init_seen(text) and full_db_re.search(text) is not None and full_marker in text

def audio_seen(text):
    return all(marker in text for marker in audio_markers)

with log_path.open('ab', buffering=0) as stream:
    proc = subprocess.Popen(
        cmd,
        stdout=stream,
        stderr=subprocess.STDOUT,
        env=env,
        cwd=str(game_dir),
        start_new_session=True,
    )
    pid_path.write_text(str(proc.pid) + '\n', encoding='ascii')
    deadline = time.monotonic() + timeout_s
    result = 3
    while time.monotonic() < deadline:
        text = read_log()
        if fatal_re.search(text):
            result = 2
            break
        reached = full_seen(text) if level == 'full' else init_seen(text)
        if sound_event_probe_required:
            reached = reached and probe_success_marker in text
        if reached:
            if null_audio and not audio_seen(text):
                time.sleep(0.25)
                text = read_log()
                if not audio_seen(text):
                    time.sleep(0.75)
                    text = read_log()
            if null_audio and not audio_seen(text):
                result = 4
            else:
                result = 0
            break
        time.sleep(0.5)

sys.exit(result)
PY
SUPERVISOR_RC=$?
set -e

# The Python supervisor never signals Wine.  Once it has returned (or the outer timeout has killed it),
# close the private X server first, then terminate the detached client session from this parent shell.
stop_xvfb
XVFB_PID=''
trap - EXIT INT TERM
if [[ -s "$CLIENT_PID_FILE" ]]; then
  read -r CLIENT_SESSION_PID < "$CLIENT_PID_FILE" || true
  if [[ "${CLIENT_SESSION_PID:-}" =~ ^[0-9]+$ && "$CLIENT_SESSION_PID" != "$$" ]]; then
    kill -TERM -- "-$CLIENT_SESSION_PID" 2>/dev/null || true
    sleep 0.25
    kill -KILL -- "-$CLIENT_SESSION_PID" 2>/dev/null || true
  fi
fi
if [[ "$SUPERVISOR_RC" == 124 || "$SUPERVISOR_RC" == 137 || "$SUPERVISOR_RC" == 143 ]]; then
  SUPERVISOR_RC=3
fi

if [[ "$SUPERVISOR_RC" == 0 && "$REQUIRE_SOUND_EVENT_PROBE" == 1 ]]; then
  grep -F -- "$PROBE_SUCCESS_MARKER" "$LOG" >/dev/null || {
    echo "ERROR: required real Minecraft probe marker missing: $PROBE_SUCCESS_MARKER" >&2
    exit 1
  }
  printf '[PASS] real Minecraft client probe completed: %s\n' "$PROBE_SUCCESS_MARKER"
fi

if [[ "$SUPERVISOR_RC" == 0 && "$MIXIN_EXPORT" == 1 ]]; then
  MIXIN_CLASS="$OUT/game/.mixin.out/class/paulscode/sound/libraries/SourceLWJGLOpenAL.class"
  MIXIN_JAVAP="$OUT/logs/SourceLWJGLOpenAL.transformed.javap.txt"
  [[ -f "$MIXIN_CLASS" ]] || {
    echo "ERROR: Mixin debug export did not produce real SourceLWJGLOpenAL: $MIXIN_CLASS" >&2
    exit 1
  }
  javap -c -p "$MIXIN_CLASS" > "$MIXIN_JAVAP"
  for hook in onSourcePlay onSourcePositionChanged onSourceCleanup; do
    grep -F "LegacySoundHook.${hook}:(Ljava/lang/Object;)V" "$MIXIN_JAVAP" >/dev/null || {
      echo "ERROR: transformed real SourceLWJGLOpenAL is missing LegacySoundHook.${hook}" >&2
      exit 1
    }
  done
  printf '%s\n' '[PASS] real Mixin transformation injects LegacySoundHook play/move/cleanup callbacks into SourceLWJGLOpenAL'
fi

cat "$LOG"
case "$SUPERVISOR_RC" in
  0)
    printf '[PASS] real Windows Forge 1.12.2 client level=%s loaded Forge + MixinBooter + Forgelin + Acoustic Shaders\n' "$BOOT_LEVEL"
    if [[ "$NULL_AUDIO" == 1 ]]; then
      printf '%s\n' '[PASS] WinLab OpenAL Soft null backend initialized the real Minecraft sound engine'
    elif grep -F 'Switching to No Sound' "$LOG" >/dev/null; then
      printf '%s\n' '[INFO] WinLab host exposes no usable playback device; Minecraft correctly fell back to No Sound.'
    fi
    ;;
  2)
    echo 'ERROR: real Forge 1.12.2 client bootstrap hit a linkage/runtime failure' >&2
    exit 1
    ;;
  3)
    if [[ "$BOOT_LEVEL" == full ]] && grep -F '[AcousticShaders] initialized for Minecraft 1.12.2' "$LOG" >/dev/null; then
      echo 'ERROR: Forge/Acoustic init passed, but full postInit/material-database/FML-complete markers were not reached before timeout' >&2
    else
      echo "ERROR: client boot did not reach required $BOOT_LEVEL markers before timeout" >&2
    fi
    exit 1
    ;;
  4)
    echo 'ERROR: null-audio mode reached client markers but did not initialize real OpenAL/SoundSystem' >&2
    exit 1
    ;;
  *)
    echo "ERROR: unexpected client supervisor exit code: $SUPERVISOR_RC" >&2
    exit 1
    ;;
esac
exit 0
