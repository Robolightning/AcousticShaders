#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"
VT="$ROOT/tools/verification/1.12.2/scripts/dev-verification-tool.sh"
for script in \
  tools/verification/1.12.2/scripts/dev-rfg-buildenv-pack.sh \
  tools/verification/1.12.2/scripts/dev-rfg-buildenv-import.sh \
  "$VT"; do
  bash -n "$script"
done
[[ -s tools/verification/1.12.2/dev-tools/VerificationTools.java ]] || { echo 'ERROR: Java verification utility missing' >&2; exit 1; }
for script in tools/verification/1.12.2/scripts/dev-rfg-buildenv-pack.sh tools/verification/1.12.2/scripts/dev-rfg-buildenv-import.sh; do
  grep -F '94702da47e2c0d626986a42bd8124c63e52afc2a' "$script" >/dev/null
  grep -F 'bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531' "$script" >/dev/null
done
grep -F 'caches/modules-2' tools/verification/1.12.2/scripts/dev-rfg-buildenv-pack.sh >/dev/null
grep -F 'caches/retro_futura_gradle' tools/verification/1.12.2/scripts/dev-rfg-buildenv-pack.sh >/dev/null
grep -F 'GRADLE_USER_HOME/jdks' tools/verification/1.12.2/scripts/dev-rfg-buildenv-pack.sh >/dev/null
grep -F -- '--preflight-only' tools/verification/1.12.2/scripts/dev-rfg-buildenv-import.sh >/dev/null
TMP="$(mktemp -d "${TMPDIR:-/tmp}/acoustic-rfg-buildenv-contract.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/payload/cache" "$TMP/payload/jdk/bin"
printf 'alpha\n' > "$TMP/payload/cache/a.txt"
printf 'java\n' > "$TMP/payload/jdk/bin/java"
SYMLINK_FIXTURE=0
if ln -s '../cache/a.txt' "$TMP/payload/link-to-a" 2>/dev/null; then
  SYMLINK_FIXTURE=1
else
  printf '%s\n' '[INFO] host cannot create symlinks; using synthetic manifest parser fixture'
fi
"$VT" manifest-create "$TMP/payload" "$TMP/MANIFEST.tsv" >/dev/null
if [[ "$SYMLINK_FIXTURE" == 1 ]]; then
  grep -F $'L\t14\t' "$TMP/MANIFEST.tsv" | grep -F $'link-to-a\t../cache/a.txt' >/dev/null
else
  target='../cache/a.txt'
  digest="$(printf '%s' "$target" | sha256sum | awk '{print $1}')"
  printf 'ACOUSTIC-RFG-BUILDENV-MANIFEST-V1\nL\t%s\t%s\tlink-to-a\t%s\n' \
    "${#target}" "$digest" "$target" > "$TMP/synthetic-link-manifest.tsv"
  "$VT" manifest-parse "$TMP/synthetic-link-manifest.tsv" >/dev/null
  grep -Fx $'L\t14\t'"$digest"$'\tlink-to-a\t../cache/a.txt' "$TMP/synthetic-link-manifest.tsv" >/dev/null
fi
"$VT" manifest-verify "$TMP/payload" "$TMP/MANIFEST.tsv" >/dev/null
# Prove the exporter pattern can bundle an exact detached commit without mutating
# the authoritative worktree: fetch it into a temporary named ref, bundle, clone, verify.
mkdir -p "$TMP/git-work"
git -C "$TMP/git-work" init -q -b main
git -C "$TMP/git-work" config user.name 'RFG Contract'
git -C "$TMP/git-work" config user.email 'rfg-contract@example.invalid'
printf 'rfg\n' > "$TMP/git-work/file.txt"
git -C "$TMP/git-work" add file.txt
git -C "$TMP/git-work" commit -q -m fixture
FIXTURE_COMMIT="$(git -C "$TMP/git-work" rev-parse HEAD)"
git init -q --bare "$TMP/git-bare"
git -C "$TMP/git-bare" fetch -q "$TMP/git-work" "$FIXTURE_COMMIT:refs/heads/rfg-1.4.9"
git -C "$TMP/git-bare" bundle create "$TMP/rfg.bundle" refs/heads/rfg-1.4.9
git bundle verify "$TMP/rfg.bundle" >/dev/null
git clone -q -b rfg-1.4.9 "$TMP/rfg.bundle" "$TMP/git-clone"
[[ "$(git -C "$TMP/git-clone" rev-parse HEAD)" == "$FIXTURE_COMMIT" ]] || { echo 'ERROR: synthetic RFG Git bundle roundtrip changed commit' >&2; exit 1; }
printf 'tamper\n' >> "$TMP/payload/cache/a.txt"
if "$VT" manifest-verify "$TMP/payload" "$TMP/MANIFEST.tsv" >/dev/null 2>&1; then
  echo 'ERROR: RFG buildenv manifest accepted tampered payload' >&2
  exit 1
fi
printf '%s\n' '[PASS] exact offline RFG buildenv pack/import + manifest contract'
