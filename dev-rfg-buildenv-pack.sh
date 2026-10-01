#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

RFG_COMMIT='94702da47e2c0d626986a42bd8124c63e52afc2a'
GRADLE_VERSION='8.14.3'
GRADLE_SHA256='bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531'
FORGE_TOOLING='1.12.2-14.23.5.2847'

WORK="${ACOUSTIC_RFG_BUILDENV_WORK:-$HOME/acoustic-rfg-1.12.2}"
GRADLE_ZIP="${ACOUSTIC_GRADLE_8143_ZIP:-$WORK/gradle-8.14.3-bin.zip}"
RFG_SOURCE="${ACOUSTIC_RFG_SOURCE:-$WORK/RetroFuturaGradle-1.4.9}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
OUT="${ACOUSTIC_RFG_BUILDENV_BUNDLE:-$WORK/AcousticShaders-RFG-OFFLINE-BUILDENV.tar.zst}"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }
for cmd in git python3 sha256sum tar zstd; do command -v "$cmd" >/dev/null 2>&1 || fail "required command missing: $cmd"; done
[[ -f "$GRADLE_ZIP" ]] || fail "Gradle $GRADLE_VERSION distribution missing: $GRADLE_ZIP"
[[ "$(sha256sum "$GRADLE_ZIP" | awk '{print $1}')" == "$GRADLE_SHA256" ]] || fail "Gradle $GRADLE_VERSION ZIP SHA-256 mismatch"
[[ -d "$RFG_SOURCE/.git" ]] || fail "RFG source checkout missing: $RFG_SOURCE"
[[ "$(git -C "$RFG_SOURCE" rev-parse HEAD)" == "$RFG_COMMIT" ]] || fail 'RFG source commit mismatch'
[[ -z "$(git -C "$RFG_SOURCE" status --porcelain)" ]] || fail 'RFG source checkout must be clean'
grep -F 'case "1.12.2" -> "1.12.2-14.23.5.2847";' \
  "$RFG_SOURCE/plugin/src/main/java/com/gtnewhorizons/retrofuturagradle/IMinecraftyExtension.java" >/dev/null \
  || fail "RFG source lost expected Forge tooling default $FORGE_TOOLING"

for rel in caches/modules-2 caches/retro_futura_gradle jdks; do
  [[ -d "$GRADLE_USER_HOME/$rel" ]] || fail "required offline Gradle payload missing: $GRADLE_USER_HOME/$rel"
done
JAVA8_RELEASE=''
while IFS= read -r release_file; do
  if grep -Eq 'JAVA_VERSION="(1\.8|8)([._"]|$)' "$release_file"; then
    JAVA8_RELEASE="$release_file"
    break
  fi
done < <(find "$GRADLE_USER_HOME/jdks" -type f -name release -print 2>/dev/null | sort)
[[ -n "$JAVA8_RELEASE" ]] || fail "Gradle toolchain cache does not contain a detectable Java 8 JDK under jdks/"

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/acoustic-rfg-buildenv-pack.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
PAYLOAD="$STAGE/payload"
mkdir -p "$PAYLOAD/gradle-user-home/caches" "$PAYLOAD/gradle-user-home"
cp -a "$GRADLE_ZIP" "$PAYLOAD/gradle-8.14.3-bin.zip"
RFG_BUNDLE_REPO="$STAGE/rfg-bundle-source.git"
git init -q --bare "$RFG_BUNDLE_REPO"
git -C "$RFG_BUNDLE_REPO" fetch -q "$RFG_SOURCE" "$RFG_COMMIT:refs/heads/rfg-1.4.9"
git -C "$RFG_BUNDLE_REPO" bundle create "$PAYLOAD/RetroFuturaGradle-1.4.9.bundle" refs/heads/rfg-1.4.9
git bundle verify "$PAYLOAD/RetroFuturaGradle-1.4.9.bundle" >/dev/null
cp -a "$GRADLE_USER_HOME/caches/modules-2" "$PAYLOAD/gradle-user-home/caches/"
cp -a "$GRADLE_USER_HOME/caches/retro_futura_gradle" "$PAYLOAD/gradle-user-home/caches/"
cp -a "$GRADLE_USER_HOME/jdks" "$PAYLOAD/gradle-user-home/"
cat > "$PAYLOAD/BUILDENV.txt" <<EOF
format=AcousticShaders-RFG-Offline-BuildEnv-v1
gradle.version=$GRADLE_VERSION
gradle.zip.sha256=$GRADLE_SHA256
rfg.version=1.4.9
rfg.commit=$RFG_COMMIT
forge.tooling.userdev=$FORGE_TOOLING
forge.runtime.universal=1.12.2-14.23.5.2864
requires.kotlin=2.4.0
requires.bootstrap.java=17
requires.minecraft.java=8
EOF
python3 "$ROOT/tools/verification/1.12.2/dev-tools/rfg_buildenv_manifest.py" create "$PAYLOAD" "$STAGE/MANIFEST.tsv"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
# Normalized archive metadata makes repeated exports from byte-identical payloads reproducible.
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -C "$STAGE" -cf - MANIFEST.tsv payload \
  | zstd -q -T0 -10 -o "$OUT"
zstd -q -t "$OUT"
VERIFY="$(mktemp -d "${TMPDIR:-/tmp}/acoustic-rfg-buildenv-verify.XXXXXX")"
trap 'rm -rf "$STAGE" "$VERIFY"' EXIT
zstd -q -dc "$OUT" | tar -xf - -C "$VERIFY"
python3 "$ROOT/tools/verification/1.12.2/dev-tools/rfg_buildenv_manifest.py" verify "$VERIFY/payload" "$VERIFY/MANIFEST.tsv"
printf '[PASS] exact offline RFG buildenv bundle created: %s\n' "$OUT"
printf 'SHA-256: %s\n' "$(sha256sum "$OUT" | awk '{print $1}')"
