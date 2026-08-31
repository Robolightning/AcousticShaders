#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${ACOUSTIC_RFG_BOOTSTRAP_ROOT:-$HOME/acoustic-rfg-1.12.2}"
RFG="$WORK/RetroFuturaGradle-1.4.9"
LEGACY_RFG="$WORK/RetroFuturaGradle-1.4.9-forge2864"
GRADLE_ZIP="$WORK/gradle-8.14.3-bin.zip"
GRADLE_HOME="$WORK/gradle-8.14.3"
RFG_COMMIT='94702da47e2c0d626986a42bd8124c63e52afc2a'
mkdir -p "$WORK"

for cmd in git curl unzip python3 java sha256sum awk sed tee df du find sort; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: required command missing: $cmd" >&2; exit 1; }
done

# Real RFG 1.12.2 setup can temporarily consume several GiB while patched
# Minecraft is decompiled/recompiled. Preserve reusable dependency caches and
# toolchains, but reclaim only Acoustic/RFG-owned generated state when WSL is
# critically low on disk. This prevents Gradle from corrupting executionHistory
# or failing halfway through injectTags with ENOSPC.
MIN_FREE_MIB="${ACOUSTIC_RFG_MIN_FREE_MIB:-8192}"
case "$MIN_FREE_MIB" in
  ''|*[!0-9]*) echo "ERROR: ACOUSTIC_RFG_MIN_FREE_MIB must be an integer MiB value" >&2; exit 1 ;;
esac

disk_free_mib() {
  df -Pm "$HOME" | awk 'NR==2 {print $4}'
}

remove_generated_path() {
  local path="$1" label="$2"
  [[ -e "$path" ]] || return 0
  local size_mib
  size_mib="$(du -sm "$path" 2>/dev/null | awk '{print $1}' || true)"
  echo "Reclaiming generated RFG state: $label (${size_mib:-?} MiB)"
  rm -rf -- "$path"
}

ensure_rfg_disk_space() {
  local before after
  before="$(disk_free_mib)"
  echo "RFG disk preflight: ${before} MiB free; minimum ${MIN_FREE_MIB} MiB"
  if (( before >= MIN_FREE_MIB )); then
    return 0
  fi

  echo "WARNING: low WSL disk space; removing only regenerable Acoustic/RFG build state." >&2
  remove_generated_path "$ROOT/rfg-1.12.2/build" 'current RFG project build/'
  remove_generated_path "$ROOT/rfg-1.12.2/.gradle" 'current RFG project .gradle/'
  remove_generated_path "$WORK/result" 'previous result staging'
  remove_generated_path "$WORK/reobf-audit-classes" 'previous reobf audit classes'
  rm -f -- "$WORK/rfg-release-gate.log" "$WORK/AcousticShaders-RFG-1.12.2-Result.zip" 2>/dev/null || true

  # Once the corrected pinned RFG checkout exists, the old patched 2847->2864
  # checkout is no longer needed even as a local Git-object source. Delete only
  # when the replacement checkout is proven exact and clean.
  if [[ -d "$RFG/.git" ]] \
      && [[ "$(git -C "$RFG" rev-parse HEAD 2>/dev/null || true)" == "$RFG_COMMIT" ]] \
      && [[ -z "$(git -C "$RFG" status --porcelain 2>/dev/null || true)" ]]; then
    remove_generated_path "$LEGACY_RFG" 'obsolete patched RFG checkout'
  fi

  # RFG source build outputs and its project-local Gradle execution state are
  # fully regenerable. Keep ~/.gradle/caches (including Minecraft/Fernflower),
  # the Gradle distribution, and Kotlin 2.4.0 untouched.
  if [[ -d "$RFG/.git" ]]; then
    while IFS= read -r -d '' build_dir; do
      remove_generated_path "$build_dir" 'pinned RFG build/'
    done < <(find "$RFG" -type d -name build -prune -print0 2>/dev/null)
    remove_generated_path "$RFG/.gradle" 'pinned RFG project .gradle/'
  fi

  # Keep the newest Fernflower result (the hot path for this exact 1.12.2
  # workspace) and prune only older redundant decompile outputs if present.
  local ff_cache="$HOME/.gradle/caches/retro_futura_gradle/fernflower-cache"
  if [[ -d "$ff_cache" ]]; then
    mapfile -t old_ff < <(find "$ff_cache" -maxdepth 1 -type f -printf '%T@ %p\n' 2>/dev/null | sort -nr | awk 'NR>1 {sub(/^[^ ]+ /, ""); print}')
    if (( ${#old_ff[@]} > 0 )); then
      echo "Pruning ${#old_ff[@]} older regenerable Fernflower cache file(s); newest entry preserved."
      rm -f -- "${old_ff[@]}"
    fi
  fi

  after="$(disk_free_mib)"
  echo "RFG disk preflight after safe cleanup: ${after} MiB free"
  if (( after < MIN_FREE_MIB )); then
    echo "ERROR: insufficient WSL disk space after safe Acoustic/RFG cleanup." >&2
    echo "Need at least ${MIN_FREE_MIB} MiB, have ${after} MiB." >&2
    echo "Preserved ~/.gradle dependency/Minecraft caches, Gradle distribution, Kotlin toolchain, and unrelated user data." >&2
    echo "Known RFG disk usage (MiB):" >&2
    du -sm "$WORK" "$HOME/.gradle/caches/retro_futura_gradle" 2>/dev/null >&2 || true
    df -h "$HOME" >&2 || true
    exit 75
  fi
}

ensure_rfg_disk_space

if [[ ! -d "$RFG/.git" ]]; then
  rm -rf "$RFG"
  # Previous Acoustic Shaders bundles used a patched checkout named
  # RetroFuturaGradle-1.4.9-forge2864. Reuse its Git object database when
  # available, but clone from HEAD into a fresh worktree so the old uncommitted
  # 2847 -> 2864 patch can never leak into the corrected tooling checkout.
  if [[ -d "$LEGACY_RFG/.git" ]] && git -C "$LEGACY_RFG" cat-file -e "$RFG_COMMIT^{commit}" 2>/dev/null; then
    echo "Reusing cached RFG Git objects from: $LEGACY_RFG"
    git clone --local --no-hardlinks "$LEGACY_RFG" "$RFG"
    git -C "$RFG" reset --hard "$RFG_COMMIT"
  else
    git clone --branch 1.4.9 --depth 1 https://github.com/GTNewHorizons/RetroFuturaGradle.git "$RFG"
  fi
fi
actual_rfg="$(git -C "$RFG" rev-parse HEAD)"
[[ "$actual_rfg" == "$RFG_COMMIT" ]] || { echo "ERROR: RFG tag mismatch: $actual_rfg" >&2; exit 1; }
[[ -z "$(git -C "$RFG" status --porcelain)" ]] || {
  echo "ERROR: pinned RFG checkout must be clean; do not patch its 1.12.2 tooling version" >&2
  git -C "$RFG" status --short >&2
  exit 1
}
grep -F 'case "1.12.2" -> "1.12.2-14.23.5.2847";' \
  "$RFG/plugin/src/main/java/com/gtnewhorizons/retrofuturagradle/IMinecraftyExtension.java" >/dev/null || {
  echo 'ERROR: pinned RFG 1.4.9 no longer exposes the expected Forge 2847 tooling/userdev default' >&2
  exit 1
}

if [[ ! -f "$GRADLE_ZIP" ]]; then
  curl -fL --retry 5 --retry-delay 2 \
    https://services.gradle.org/distributions/gradle-8.14.3-bin.zip \
    -o "$GRADLE_ZIP"
fi
curl -fsSL --retry 5 --retry-delay 2 \
  https://services.gradle.org/distributions/gradle-8.14.3-bin.zip.sha256 \
  -o "$WORK/gradle-8.14.3-bin.zip.sha256"
expected_gradle="$(tr -d '[:space:]' < "$WORK/gradle-8.14.3-bin.zip.sha256")"
actual_gradle="$(sha256sum "$GRADLE_ZIP" | awk '{print $1}')"
[[ "$actual_gradle" == "$expected_gradle" ]] || { echo 'ERROR: Gradle 8.14.3 SHA-256 mismatch' >&2; exit 1; }
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  rm -rf "$GRADLE_HOME"
  unzip -q "$GRADLE_ZIP" -d "$WORK"
fi

KOTLIN_HOME="${ACOUSTIC_KOTLIN_HOME:-$HOME/acoustic-kotlin-2.4/extracted/kotlinc}"
[[ -x "$KOTLIN_HOME/bin/kotlinc" ]] || { echo "ERROR: exact Kotlin home missing: $KOTLIN_HOME" >&2; exit 1; }
"$KOTLIN_HOME/bin/kotlinc" -version 2>&1 | grep -F 'kotlinc-jvm 2.4.0' >/dev/null || { echo 'ERROR: Kotlin 2.4.0 required' >&2; exit 1; }

export ACOUSTIC_RFG_SOURCE="$RFG"
export ACOUSTIC_KOTLIN_HOME="$KOTLIN_HOME"

# RFG 1.4.9 depends on Fabric Mercury/mapping-io. When RFG is consumed as an included
# plugin build, those runtime dependencies are resolved by the consumer's pluginManagement
# repositories. Probe the critical Fabric Maven coordinate up front so a repository/DNS
# problem is reported clearly instead of as a long Gradle classpath stack trace.
MERCURY_POM='https://maven.fabricmc.net/net/fabricmc/mercury/0.6.0/mercury-0.6.0.pom'
if ! curl -fsSL --retry 3 --retry-delay 2 --output /dev/null "$MERCURY_POM"; then
  echo "ERROR: cannot reach required RFG dependency: $MERCURY_POM" >&2
  exit 1
fi

GRADLE=("$GRADLE_HOME/bin/gradle" --no-daemon --stacktrace -p "$ROOT/rfg-1.12.2")
LOG="$WORK/rfg-release-gate.log"
RESULT_DIR="$WORK/result"
RESULT_ZIP="$WORK/AcousticShaders-RFG-1.12.2-Result.zip"
rm -rf "$RESULT_DIR" "$RESULT_ZIP"
mkdir -p "$RESULT_DIR"

WINDOWS_DOWNLOADS="${ACOUSTIC_WINDOWS_DOWNLOADS:-}"
if [[ -z "$WINDOWS_DOWNLOADS" ]] && command -v powershell.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
  win_profile="$(powershell.exe -NoProfile -Command '[Console]::Write($env:USERPROFILE)' 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$win_profile" ]]; then
    wsl_profile="$(wslpath "$win_profile" 2>/dev/null || true)"
    [[ -d "$wsl_profile/Downloads" ]] && WINDOWS_DOWNLOADS="$wsl_profile/Downloads"
  fi
fi

set +e
"${GRADLE[@]}" clean rfgReleaseGate 2>&1 | tee "$LOG"
gradle_rc=${PIPESTATUS[0]}
set -e
if [[ $gradle_rc -ne 0 ]]; then
  echo
  echo "ERROR: RFG release gate failed with rc=$gradle_rc" >&2
  echo "Full log: $LOG" >&2
  if [[ -n "$WINDOWS_DOWNLOADS" && -d "$WINDOWS_DOWNLOADS" ]]; then
    cp "$LOG" "$WINDOWS_DOWNLOADS/AcousticShaders-RFG-FAILED.log"
    echo "Failure log copied to: $WINDOWS_DOWNLOADS/AcousticShaders-RFG-FAILED.log" >&2
  fi
  exit "$gradle_rc"
fi

REOBF_JAR="$ROOT/rfg-1.12.2/build/libs/acoustic-shaders-mc1122-0.3.0-rc19.jar"
[[ -f "$REOBF_JAR" ]] || { echo "ERROR: expected RFG output missing: $REOBF_JAR" >&2; exit 1; }
cp "$REOBF_JAR" "$RESULT_DIR/"
cp "$LOG" "$RESULT_DIR/rfg-release-gate.log"

# If the user already has the official 1.12.2 artifacts from the stronger local gate,
# audit the *reobfuscated RFG JAR itself* against those real SRG binaries as a final independent check.
MC_CLIENT="${ACOUSTIC_MC_1122_CLIENT:-}"
MCP_CONFIG="${ACOUSTIC_MCP_CONFIG_1122:-}"
FORGE_UNIVERSAL="${ACOUSTIC_FORGE_1122_UNIVERSAL:-}"
FORGE_INSTALLER="${ACOUSTIC_FORGE_1122_INSTALLER:-}"
if [[ -n "$WINDOWS_DOWNLOADS" && -d "$WINDOWS_DOWNLOADS" ]]; then
  [[ -n "$MC_CLIENT" ]] || MC_CLIENT="$WINDOWS_DOWNLOADS/minecraft-1.12.2-client.jar"
  [[ -n "$MCP_CONFIG" ]] || MCP_CONFIG="$WINDOWS_DOWNLOADS/mcp_config-1.12.2-20200226.224830.zip"
  [[ -n "$FORGE_UNIVERSAL" ]] || FORGE_UNIVERSAL="$WINDOWS_DOWNLOADS/forge-1.12.2-14.23.5.2864-universal.jar"
  [[ -n "$FORGE_INSTALLER" ]] || FORGE_INSTALLER="$WINDOWS_DOWNLOADS/forge-1.12.2-14.23.5.2864-installer.jar"
fi

if [[ -f "$MC_CLIENT" && -f "$MCP_CONFIG" && -f "$FORGE_UNIVERSAL" ]]; then
  [[ "$(sha1sum "$MC_CLIENT" | awk '{print $1}')" == '0f275bc1547d01fa5f56ba34bdc87d981ee12daf' ]] || { echo 'ERROR: unexpected Minecraft 1.12.2 client SHA-1' >&2; exit 1; }
  [[ "$(sha1sum "$MCP_CONFIG" | awk '{print $1}')" == '72e1b936f56e0dd394c64caf9c86af01f64dc979' ]] || { echo 'ERROR: unexpected MCPConfig 1.12.2 SHA-1' >&2; exit 1; }
  [[ "$(sha1sum "$FORGE_UNIVERSAL" | awk '{print $1}')" == 'd0ab8e116da0e50c6e6099791f97772a08469626' ]] || { echo 'ERROR: unexpected Forge 14.23.5.2864 universal SHA-1' >&2; exit 1; }
  if [[ -f "$FORGE_INSTALLER" ]]; then
    [[ "$(sha1sum "$FORGE_INSTALLER" | awk '{print $1}')" == 'b5ec0016c292830f2325e39417d1ec4d9e166cab' ]] || { echo 'ERROR: unexpected Forge 14.23.5.2864 installer SHA-1' >&2; exit 1; }
  fi

  AUDIT_CLASSES="$WORK/reobf-audit-classes"
  rm -rf "$AUDIT_CLASSES"
  mkdir -p "$AUDIT_CLASSES"
  (cd "$AUDIT_CLASSES" && unzip -q "$REOBF_JAR" '*.class')
  {
    echo '=== Official-binary audit of the RFG reobfuscated JAR ==='
    env \
      ACOUSTIC_MC_1122_CLIENT="$MC_CLIENT" \
      ACOUSTIC_MCP_CONFIG_1122="$MCP_CONFIG" \
      ACOUSTIC_FORGE_1122_UNIVERSAL="$FORGE_UNIVERSAL" \
      ACOUSTIC_FORGE_1122_INSTALLER="$FORGE_INSTALLER" \
      "$ROOT/dev-real-srg-bytecode-audit.sh" "$AUDIT_CLASSES"
  } 2>&1 | tee "$RESULT_DIR/real-srg-rfg-bytecode-audit.log"
else
  echo 'Official local Minecraft/MCP/Forge artifacts not found; RFG binary-reference cross-audit skipped.' \
    > "$RESULT_DIR/real-srg-rfg-bytecode-audit.log"
fi
{
  echo "Acoustic Shaders RFG integration result"
  echo "Project HEAD: $(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo source-archive)"
  echo "RFG HEAD: $(git -C "$RFG" rev-parse HEAD)"
  echo "RFG expected HEAD: $RFG_COMMIT"
  echo "RFG tooling/userdev Forge: 1.12.2-14.23.5.2847"
  echo "Acoustic runtime/universal Forge: 1.12.2-14.23.5.2864"
  echo "Reobf JAR SHA256: $(sha256sum "$REOBF_JAR" | awk '{print $1}')"
  echo
  echo "Gradle:"
  "$GRADLE_HOME/bin/gradle" --version | sed -n '1,12p'
  echo
  echo "Kotlin:"
  "$KOTLIN_HOME/bin/kotlinc" -version 2>&1
  echo
  echo "Bootstrap JVM:"
  java -version 2>&1
} > "$RESULT_DIR/RESULT.txt"
python3 - "$RESULT_DIR" "$RESULT_ZIP" <<'PYZIP'
from pathlib import Path
import sys,zipfile
root=Path(sys.argv[1]);out=Path(sys.argv[2])
with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED) as z:
    for p in sorted(root.rglob('*')):
        if p.is_file(): z.write(p,p.relative_to(root))
PYZIP

echo
printf '[PASS] Acoustic Shaders RFG release gate completed\n'
printf 'RFG source: %s\n' "$RFG"
printf 'Gradle: %s\n' "$GRADLE_HOME"
printf 'Kotlin: %s\n' "$KOTLIN_HOME"
printf 'Reobf JAR: %s\n' "$REOBF_JAR"
printf 'Result bundle: %s\n' "$RESULT_ZIP"
if [[ -n "$WINDOWS_DOWNLOADS" && -d "$WINDOWS_DOWNLOADS" ]]; then
  cp "$RESULT_ZIP" "$WINDOWS_DOWNLOADS/"
  echo "Copied result to: $WINDOWS_DOWNLOADS/$(basename "$RESULT_ZIP")"
fi
