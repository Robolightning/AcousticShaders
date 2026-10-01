#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"
SCRIPT='tools/verification/1.12.2/scripts/dev-rfg-local-runner.sh'
bash -n "$SCRIPT"
grep -F "RFG_COMMIT='94702da47e2c0d626986a42bd8124c63e52afc2a'" "$SCRIPT" >/dev/null
grep -F "GRADLE_VERSION='8.14.3'" "$SCRIPT" >/dev/null
grep -F "GRADLE_SHA256='bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531'" "$SCRIPT" >/dev/null
grep -F "FORGE_TOOLING='1.12.2-14.23.5.2847'" "$SCRIPT" >/dev/null
grep -F "FORGE_RUNTIME='1.12.2-14.23.5.2864'" "$SCRIPT" >/dev/null
grep -F -- '--offline' "$SCRIPT" >/dev/null 2>&1 || { echo 'ERROR: local RFG runner must be offline' >&2; exit 1; }
grep -F -- '--preflight-only' "$SCRIPT" >/dev/null || { echo 'ERROR: local RFG runner lost preflight-only mode' >&2; exit 1; }
grep -F 'bootstrap Java 17 required' "$SCRIPT" >/dev/null || { echo 'ERROR: local RFG runner lost Java 17 bootstrap pin' >&2; exit 1; }
grep -F 'Gradle Java toolchain cache missing' "$SCRIPT" >/dev/null || { echo 'ERROR: local RFG runner lost Java 8 toolchain-cache preflight' >&2; exit 1; }
if grep -Eq 'curl |wget |https?://' "$SCRIPT"; then
  echo 'ERROR: local RFG runner must not download dependencies' >&2
  exit 1
fi
grep -F "0f275bc1547d01fa5f56ba34bdc87d981ee12daf" "$SCRIPT" >/dev/null
grep -F "e2ddd3a7bb65618ad0de1d8fc0334527e5c346180300d94bfc7d3727b1976b42" "$SCRIPT" >/dev/null
grep -F "d0ab8e116da0e50c6e6099791f97772a08469626" "$SCRIPT" >/dev/null
echo '[PASS] local offline RFG runner contract'
