#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
exec "$ROOT/tools/verification/1.12.2/scripts/dev-verify.sh" "$@"
