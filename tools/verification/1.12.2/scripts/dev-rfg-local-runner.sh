#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
WORK="${ACOUSTIC_RFG_LOCAL_ROOT:-$ROOT/out/rfg-local}"
GRADLE_HOME="${ACOUSTIC_GRADLE_8143_HOME:-$WORK/gradle-8.14.3}"
RFG_SOURCE="${ACOUSTIC_RFG_SOURCE:-$WORK/RetroFuturaGradle-1.4.9}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$WORK/gradle-user-home}"
KOTLIN_HOME="${ACOUSTIC_KOTLIN_HOME:-$ROOT/../acoustic-kotlin-2.4.0/kotlinc}"
(( $# <= 1 )) || { echo 'usage: tools/verification/1.12.2/scripts/dev-rfg-local-runner.sh [--run|--preflight-only]' >&2; exit 2; }
MODE="${1:---run}"
case "$MODE" in --run|--preflight-only) ;; *) fail_mode=1 ;; esac
if [[ ${fail_mode:-0} == 1 ]]; then
  echo 'usage: tools/verification/1.12.2/scripts/dev-rfg-local-runner.sh [--run|--preflight-only]' >&2
  exit 2
fi

RFG_COMMIT='94702da47e2c0d626986a42bd8124c63e52afc2a'
GRADLE_VERSION='8.14.3'
GRADLE_SHA256='bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531'
GRADLE_ZIP="${ACOUSTIC_GRADLE_8143_ZIP:-$WORK/gradle-8.14.3-bin.zip}"
FORGE_TOOLING='1.12.2-14.23.5.2847'
FORGE_RUNTIME='1.12.2-14.23.5.2864'

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

for cmd in git java sha1sum sha256sum; do
  command -v "$cmd" >/dev/null 2>&1 || fail "required command missing: $cmd"
done

JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version =/ {print $2; exit}')"
[[ "$JAVA_SPEC" == '17' ]] || fail "bootstrap Java 17 required for pinned RFG environment, got ${JAVA_SPEC:-<unknown>}"

[[ -f "$GRADLE_ZIP" ]] || fail "official Gradle 8.14.3 ZIP missing: $GRADLE_ZIP"
[[ "$(sha256sum "$GRADLE_ZIP" | awk '{print $1}')" == "$GRADLE_SHA256" ]] || fail "official Gradle 8.14.3 ZIP SHA-256 mismatch"
[[ -x "$GRADLE_HOME/bin/gradle" ]] || fail "Gradle 8.14.3 home missing: $GRADLE_HOME"
[[ -d "$RFG_SOURCE/.git" ]] || fail "RetroFuturaGradle checkout missing: $RFG_SOURCE"
[[ -x "$KOTLIN_HOME/bin/kotlinc" ]] || fail "Kotlin 2.4.0 home missing: $KOTLIN_HOME"

actual_gradle="$($GRADLE_HOME/bin/gradle --version | awk '/^Gradle / {print $2; exit}')"
[[ "$actual_gradle" == "$GRADLE_VERSION" ]] || fail "Gradle $GRADLE_VERSION required, got ${actual_gradle:-<unknown>}"

actual_rfg="$(git -C "$RFG_SOURCE" rev-parse HEAD)"
[[ "$actual_rfg" == "$RFG_COMMIT" ]] || fail "RFG commit mismatch: $actual_rfg"
[[ -z "$(git -C "$RFG_SOURCE" status --porcelain)" ]] || fail 'RFG checkout must be clean'
grep -F 'case "1.12.2" -> "1.12.2-14.23.5.2847";' \
  "$RFG_SOURCE/plugin/src/main/java/com/gtnewhorizons/retrofuturagradle/IMinecraftyExtension.java" >/dev/null \
  || fail "RFG source does not expose expected Forge tooling default $FORGE_TOOLING"

"$KOTLIN_HOME/bin/kotlinc" -version 2>&1 | grep -F 'kotlinc-jvm 2.4.0' >/dev/null \
  || fail 'exact Kotlin 2.4.0 required'

MC_CLIENT="${ACOUSTIC_MC_1122_CLIENT:-}"
MCP_CONFIG="${ACOUSTIC_MCP_CONFIG_1122:-}"
FORGE_UNIVERSAL="${ACOUSTIC_FORGE_1122_UNIVERSAL:-}"
FORGE_INSTALLER="${ACOUSTIC_FORGE_1122_INSTALLER:-}"

[[ -f "$MC_CLIENT" ]] || fail 'ACOUSTIC_MC_1122_CLIENT must point to official Minecraft 1.12.2 client jar'
[[ -f "$MCP_CONFIG" ]] || fail 'ACOUSTIC_MCP_CONFIG_1122 must point to MCPConfig 1.12.2 zip'
[[ -f "$FORGE_UNIVERSAL" ]] || fail 'ACOUSTIC_FORGE_1122_UNIVERSAL must point to Forge 14.23.5.2864 universal jar'
[[ -f "$FORGE_INSTALLER" ]] || fail 'ACOUSTIC_FORGE_1122_INSTALLER must point to Forge 14.23.5.2864 installer jar'

[[ "$(sha1sum "$MC_CLIENT" | awk '{print $1}')" == '0f275bc1547d01fa5f56ba34bdc87d981ee12daf' ]] \
  || fail 'unexpected Minecraft 1.12.2 client SHA-1'
[[ "$(sha256sum "$MCP_CONFIG" | awk '{print $1}')" == 'e2ddd3a7bb65618ad0de1d8fc0334527e5c346180300d94bfc7d3727b1976b42' ]] \
  || fail 'unexpected MCPConfig SHA-256'
[[ "$(sha1sum "$FORGE_UNIVERSAL" | awk '{print $1}')" == 'd0ab8e116da0e50c6e6099791f97772a08469626' ]] \
  || fail "unexpected Forge $FORGE_RUNTIME universal SHA-1"

MODULES="$GRADLE_USER_HOME/caches/modules-2"
RFG_CACHE="$GRADLE_USER_HOME/caches/retro_futura_gradle"
GRADLE_JDKS="$GRADLE_USER_HOME/jdks"
[[ -d "$MODULES" ]] || fail "Gradle dependency cache missing: $MODULES"
[[ -d "$RFG_CACHE" ]] || fail "RetroFuturaGradle cache missing: $RFG_CACHE"
[[ -d "$GRADLE_JDKS" ]] || fail "Gradle Java toolchain cache missing: $GRADLE_JDKS"
JAVA8_RELEASE=''
while IFS= read -r release_file; do
  if grep -Eq 'JAVA_VERSION="(1\.8|8)([._"]|$)' "$release_file"; then
    JAVA8_RELEASE="$release_file"
    break
  fi
done < <(find "$GRADLE_JDKS" -type f -name release -print 2>/dev/null | sort)
[[ -n "$JAVA8_RELEASE" ]] || fail "Gradle Java toolchain cache does not contain a detectable Java 8 JDK: $GRADLE_JDKS"

# Offline mode is deliberate: the local gate must be self-contained and must not
# silently depend on network availability. If the imported cache is incomplete,
# Gradle will report the exact missing coordinate.
export GRADLE_USER_HOME
export ACOUSTIC_RFG_SOURCE="$RFG_SOURCE"
export ACOUSTIC_KOTLIN_HOME="$KOTLIN_HOME"

printf '[PASS] local RFG prerequisites: Gradle %s, RFG %s, Kotlin 2.4.0, bootstrap Java 17, Minecraft Java 8 toolchain, Forge tooling %s, runtime %s\n' \
  "$GRADLE_VERSION" "${RFG_COMMIT:0:7}" "$FORGE_TOOLING" "$FORGE_RUNTIME"
printf '[INFO] Gradle user home: %s\n' "$GRADLE_USER_HOME"
if [[ "$MODE" == '--preflight-only' ]]; then
  printf '%s\n' '[PASS] local offline RFG preflight only; Gradle task not started'
  exit 0
fi

exec "$GRADLE_HOME/bin/gradle" \
  --offline \
  --no-daemon \
  --stacktrace \
  -p "$ROOT/tools/verification/1.12.2/rfg" \
  clean rfgReleaseGate
