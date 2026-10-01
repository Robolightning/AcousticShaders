#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
SRC="$ROOT/tools/verification/1.12.2/dev-tools/VerificationTools.java"
OUT="$ROOT/out/verification-tools"
CLASS="$OUT/dev/acoustic/verification/VerificationTools.class"
command -v javac >/dev/null 2>&1 || { echo 'ERROR: javac is required for verification tooling' >&2; exit 2; }
command -v java >/dev/null 2>&1 || { echo 'ERROR: java is required for verification tooling' >&2; exit 2; }
if [[ ! -f "$CLASS" || "$SRC" -nt "$CLASS" ]]; then
  rm -rf "$OUT"
  mkdir -p "$OUT"
  javac -source 8 -target 8 -encoding UTF-8 -Xlint:all,-options -Werror -d "$OUT" "$SRC"
fi
exec java -cp "$OUT" dev.acoustic.verification.VerificationTools "$@"
