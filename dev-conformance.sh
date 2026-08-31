#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
KOTLIN_RUNNER="${ACOUSTIC_KOTLIN:-$(command -v kotlin)}"
[[ -x "$KOTLIN_RUNNER" ]] || { echo "ERROR: kotlin runner not executable: $KOTLIN_RUNNER" >&2; exit 1; }
PACK="${1:-$ROOT/examples/reference-pack}"
OUT="${2:-$ROOT/out/conformance}"
if [[ "${ACOUSTIC_CONFORMANCE_REUSE_CLASSES:-0}" != "1" ]]; then
  "$ROOT/dev-test.sh" >/dev/null
else
  test -d "$ROOT/out/classes" || { echo "ERROR: ACOUSTIC_CONFORMANCE_REUSE_CLASSES=1 but out/classes is missing" >&2; exit 1; }
fi
CONFORMANCE_CP="${ACOUSTIC_CONFORMANCE_CLASSPATH:-$ROOT/out/classes}"
"$KOTLIN_RUNNER" -J-ea -cp "$CONFORMANCE_CP" dev.acoustic.tools.PackConformanceCli "$PACK" "$OUT"
