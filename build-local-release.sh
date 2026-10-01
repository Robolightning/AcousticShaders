#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
export ACOUSTIC_REQUIRE_KOTLIN_VERSION="${ACOUSTIC_REQUIRE_KOTLIN_VERSION:-2.4.0}"
if [[ -n "${ACOUSTIC_KOTLINC:-}" && -z "${ACOUSTIC_KOTLIN:-}" ]]; then
  export ACOUSTIC_KOTLIN="$(dirname "$ACOUSTIC_KOTLINC")/kotlin"
fi
printf 'Release gate requires Kotlin %s\n' "$ACOUSTIC_REQUIRE_KOTLIN_VERSION"
./verify.sh
./package-local-release.sh
