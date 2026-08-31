#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; OUT="$ROOT/out"; cd "$ROOT"
KOTLIN_RUNNER="${ACOUSTIC_KOTLIN:-$(command -v kotlin)}"
[[ -x "$KOTLIN_RUNNER" ]] || { echo "ERROR: kotlin runner not executable: $KOTLIN_RUNNER" >&2; exit 1; }
if [[ "${ACOUSTIC_TEST_REUSE_PRODUCTION_CLASSES:-0}" == "1" ]]; then
  test -d "$OUT/forge-classes" || { echo "ERROR: reusable production classes missing: $OUT/forge-classes" >&2; exit 1; }
  rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
  "$ROOT/dev-compile-jvm8.sh" "$OUT/classes" "$OUT/forge-classes" \
    "$ROOT/acoustic-testkit/src/main/java" "$ROOT/acoustic-testkit/src/main/kotlin" \
    "$ROOT/acoustic-tools/src/main/java" "$ROOT/acoustic-tools/src/main/kotlin" \
    "$ROOT/acoustic-tests/src/test/java" "$ROOT/acoustic-tests/src/test/kotlin"
  TEST_CP="$OUT/classes:$OUT/forge-classes"
else
  rm -rf "$OUT"; mkdir -p "$OUT/classes"
  "$ROOT/dev-compile-jvm8.sh" "$OUT/classes" "" \
    "$ROOT/acoustic-api/src/main/java" "$ROOT/acoustic-api/src/main/kotlin" \
    "$ROOT/acoustic-platform-api/src/main/java" "$ROOT/acoustic-platform-api/src/main/kotlin" \
    "$ROOT/acoustic-core/src/main/java" "$ROOT/acoustic-core/src/main/kotlin" \
    "$ROOT/acoustic-testkit/src/main/java" "$ROOT/acoustic-testkit/src/main/kotlin" \
    "$ROOT/minecraft-1.12.2/src/main/java" "$ROOT/minecraft-1.12.2/src/main/kotlin" \
    "$ROOT/acoustic-tools/src/main/java" "$ROOT/acoustic-tools/src/main/kotlin" \
    "$ROOT/acoustic-tests/src/test/java" "$ROOT/acoustic-tests/src/test/kotlin"
  TEST_CP="$OUT/classes"
fi
python3 - <<'PYZIP'
import os,zipfile
root='examples/reference-pack';out='examples/reference-pack.zip'
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
    for base,_,files in os.walk(root):
        for f in files:
            p=os.path.join(base,f);z.write(p,os.path.relpath(p,root))
PYZIP
"$KOTLIN_RUNNER" -J-ea -cp "$TEST_CP" dev.acoustic.tests.HeadlessTestSuite
"$KOTLIN_RUNNER" -J-ea -cp "$TEST_CP" dev.acoustic.tests.AdvancedReleaseTestSuite
ROOTS=("$ROOT/acoustic-api/src/main/java" "$ROOT/acoustic-api/src/main/kotlin" "$ROOT/acoustic-platform-api/src/main/java" "$ROOT/acoustic-platform-api/src/main/kotlin" "$ROOT/acoustic-core/src/main/java" "$ROOT/acoustic-core/src/main/kotlin" "$ROOT/acoustic-testkit/src/main/java" "$ROOT/acoustic-testkit/src/main/kotlin")
EX=();for p in "${ROOTS[@]}";do [[ -d "$p" ]]&&EX+=("$p");done
if grep -R -nE '^import (net\.minecraft|net\.minecraftforge|org\.spongepowered|optifine|zone\.rong)' "${EX[@]}";then echo 'ERROR: platform-specific import leaked into portable modules' >&2;exit 1;fi
echo '[PASS] architecture import boundary'
