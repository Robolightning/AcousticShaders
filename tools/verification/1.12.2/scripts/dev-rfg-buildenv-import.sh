#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"

RFG_COMMIT='94702da47e2c0d626986a42bd8124c63e52afc2a'
GRADLE_SHA256='bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531'
BUNDLE="${1:-${ACOUSTIC_RFG_BUILDENV_BUNDLE:-}}"
[[ -n "$BUNDLE" ]] || { echo 'usage: tools/verification/1.12.2/scripts/dev-rfg-buildenv-import.sh <AcousticShaders-RFG-OFFLINE-BUILDENV.tar.zst>' >&2; exit 2; }
BUNDLE="$(readlink -f "$BUNDLE")"
[[ -f "$BUNDLE" ]] || { echo "ERROR: RFG buildenv bundle missing: $BUNDLE" >&2; exit 2; }
for cmd in git python3 sha256sum tar zstd unzip; do command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: required command missing: $cmd" >&2; exit 2; }; done

WORK="${ACOUSTIC_RFG_LOCAL_ROOT:-$ROOT/out/rfg-local}"
GRADLE_HOME="$WORK/gradle-8.14.3"
RFG_SOURCE="$WORK/RetroFuturaGradle-1.4.9"
GRADLE_USER_HOME="$WORK/gradle-user-home"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/acoustic-rfg-buildenv-import.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
zstd -q -t "$BUNDLE"
zstd -q -dc "$BUNDLE" | tar -xf - -C "$TMP"
[[ -f "$TMP/MANIFEST.tsv" && -d "$TMP/payload" ]] || { echo 'ERROR: invalid RFG buildenv bundle layout' >&2; exit 2; }
python3 "$ROOT/tools/verification/1.12.2/dev-tools/rfg_buildenv_manifest.py" verify "$TMP/payload" "$TMP/MANIFEST.tsv"
META="$TMP/payload/BUILDENV.txt"
for required in \
  'format=AcousticShaders-RFG-Offline-BuildEnv-v1' \
  'gradle.version=8.14.3' \
  "gradle.zip.sha256=$GRADLE_SHA256" \
  'rfg.version=1.4.9' \
  "rfg.commit=$RFG_COMMIT" \
  'forge.tooling.userdev=1.12.2-14.23.5.2847' \
  'forge.runtime.universal=1.12.2-14.23.5.2864' \
  'requires.kotlin=2.4.0' \
  'requires.bootstrap.java=17' \
  'requires.minecraft.java=8'; do
  grep -Fx "$required" "$META" >/dev/null || { echo "ERROR: buildenv metadata mismatch: $required" >&2; exit 2; }
done
GRADLE_ZIP="$TMP/payload/gradle-8.14.3-bin.zip"
[[ "$(sha256sum "$GRADLE_ZIP" | awk '{print $1}')" == "$GRADLE_SHA256" ]] || { echo 'ERROR: imported Gradle ZIP SHA-256 mismatch' >&2; exit 2; }
RFG_BUNDLE="$TMP/payload/RetroFuturaGradle-1.4.9.bundle"
git bundle verify "$RFG_BUNDLE" >/dev/null

mkdir -p "$WORK"
rm -rf "$GRADLE_HOME" "$RFG_SOURCE" "$GRADLE_USER_HOME"
cp -a "$GRADLE_ZIP" "$WORK/gradle-8.14.3-bin.zip"
unzip -q "$WORK/gradle-8.14.3-bin.zip" -d "$WORK"
git clone -q -b rfg-1.4.9 "$RFG_BUNDLE" "$RFG_SOURCE"
git -C "$RFG_SOURCE" checkout -q --detach "$RFG_COMMIT"
mkdir -p "$GRADLE_USER_HOME"
cp -a "$TMP/payload/gradle-user-home/." "$GRADLE_USER_HOME/"

cat > "$WORK/BUILDENV-IMPORT.txt" <<EOF
bundle=$BUNDLE
bundle.sha256=$(sha256sum "$BUNDLE" | awk '{print $1}')
manifest.sha256=$(sha256sum "$TMP/MANIFEST.tsv" | awk '{print $1}')
rfg.commit=$RFG_COMMIT
gradle.version=8.14.3
EOF

export ACOUSTIC_RFG_LOCAL_ROOT="$WORK"
export ACOUSTIC_GRADLE_8143_HOME="$GRADLE_HOME"
export ACOUSTIC_GRADLE_8143_ZIP="$WORK/gradle-8.14.3-bin.zip"
export ACOUSTIC_RFG_SOURCE="$RFG_SOURCE"
export GRADLE_USER_HOME
"$ROOT/tools/verification/1.12.2/scripts/dev-rfg-local-runner.sh" --preflight-only
printf '[PASS] imported exact offline RFG buildenv: %s\n' "$WORK"
printf '%s\n' 'Next: tools/verification/1.12.2/scripts/dev-rfg-local-runner.sh'
