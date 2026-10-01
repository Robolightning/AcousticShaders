#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"
./tools/verification/1.12.2/scripts/dev-test.sh >/dev/null
java -cp "$ROOT/out/classes" dev.acoustic.tests.RayBenchmark "${1:-32768}"
