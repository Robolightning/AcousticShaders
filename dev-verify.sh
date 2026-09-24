#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Production implementation is Kotlin-only. Java remains only in test/contract stubs that
# intentionally model Minecraft/Forge/LWJGL/Paulscode Java APIs.
PRODUCTION_ROOTS=(
  acoustic-api/src/main acoustic-platform-api/src/main acoustic-core/src/main
  minecraft-1.12.2/src/main minecraft-1.12.2/src/forge
  acoustic-tools/src/main acoustic-testkit/src/main
)
JAVA_PRODUCTION=()
for root in "${PRODUCTION_ROOTS[@]}"; do
  while IFS= read -r f; do JAVA_PRODUCTION+=("$f"); done < <(find "$root" -type f -name '*.java' 2>/dev/null | sort)
done
if [[ ${#JAVA_PRODUCTION[@]} -ne 0 ]]; then
  printf '%s\n' 'ERROR: own production Java files remain:' >&2
  printf '  %s\n' "${JAVA_PRODUCTION[@]}" >&2
  exit 1
fi
printf '%s\n' '[PASS] own production tree contains 0 Java files'
if ! grep -F -- '-Xjdk-release=8' "$PWD/dev-compile-jvm8.sh" >/dev/null; then
  echo 'ERROR: Kotlin production compiler lost the Java-8 JDK API fence (-Xjdk-release=8)' >&2
  exit 1
fi
if grep -F -- 'KARGS=(-jvm-target 1.8 -Xjdk-release=8' "$PWD/dev-compile-jvm8.sh" >/dev/null; then
  echo 'ERROR: Kotlin production compiler combines conflicting -jvm-target and -Xjdk-release flags' >&2
  exit 1
fi
printf '%s\n' '[PASS] Kotlin compiler uses -Xjdk-release=8 as the Java-8 bytecode/API fence'
./dev-rfg-workspace-contract.sh
./dev-rfg-local-runner-contract.sh
./dev-rfg-buildenv-contract.sh
./dev-forge1122-client-tools-contract.sh
if [[ -n "${ACOUSTIC_FORGE1122_BUNDLE:-}" ]]; then
  ./dev-forge1122-client-preflight.sh
fi

# When official 1.12.2 artifacts are supplied, run the binary-backed ABI gates before the
# deterministic stub/runtime harness. This keeps the default developer gate offline-friendly,
# while release/handoff environments can prove compatibility against the real Forge/Minecraft ABI.
if [[ -n "${ACOUSTIC_FORGE_1122_UNIVERSAL:-}" ]]; then
  ./dev-real-forge-abi.sh
fi
if [[ -n "${ACOUSTIC_FORGELIN_JAR:-}" ]]; then
  ./dev-real-forgelin-contract.sh
fi
if [[ -n "${ACOUSTIC_MC_1122_CLIENT:-}" && -n "${ACOUSTIC_MCP_CONFIG_1122:-}" && -n "${ACOUSTIC_FORGE_1122_UNIVERSAL:-}" ]]; then
  # Prepare/remap official binaries before the runtime smoke. The later source compile reuses
  # exact-toolchain portable classes from dev-legacy-contract instead of recompiling Core.
  ./dev-prepare-real-srg.sh
  ./dev-real-srg-reflection-audit.sh
  ./dev-real-liquid-height-contract.sh
fi

LEGACY_RECEIPT="$PWD/out/legacy-contract.receipt"
LEGACY_REUSE="${ACOUSTIC_VERIFY_REUSE_LEGACY:-0}"
case "$LEGACY_REUSE" in 0|1) ;; *) echo "ERROR: ACOUSTIC_VERIFY_REUSE_LEGACY must be 0 or 1" >&2; exit 1;; esac
LEGACY_HEAD="$(git rev-parse HEAD)"
LEGACY_INPUTS="$(./release-input-fingerprint.sh)"
LEGACY_KEY="head=$LEGACY_HEAD inputs=$LEGACY_INPUTS"
LEGACY_REUSED=0
if [[ "$LEGACY_REUSE" == 1 && -z "$(git status --porcelain --untracked-files=all)" && -s "$LEGACY_RECEIPT" && -d "$PWD/out/forge-classes" ]]; then
  if [[ "$(cat "$LEGACY_RECEIPT")" == "$LEGACY_KEY" ]]; then
    LEGACY_REUSED=1
    printf '%s\n' '[PASS] reused legacy contract receipt bound to current clean HEAD + release-input fingerprint'
  fi
fi
if [[ "$LEGACY_REUSED" == 0 ]]; then
  ./dev-legacy-contract.sh
  mkdir -p "$PWD/out"
  printf '%s\n' "$LEGACY_KEY" > "$LEGACY_RECEIPT"
  printf '%s\n' '[PASS] wrote legacy contract receipt bound to current HEAD + release-input fingerprint'
fi
if [[ -n "${ACOUSTIC_MC_1122_CLIENT:-}" && -n "${ACOUSTIC_MCP_CONFIG_1122:-}" && -n "${ACOUSTIC_FORGE_1122_UNIVERSAL:-}" ]]; then
  ACOUSTIC_REAL_SRG_PORTABLE_CLASSES="$PWD/out/forge-classes" ./dev-real-srg-contract.sh
  ./dev-physical-projectile-gameplay-probe.sh
  ./dev-physical-liquid-tnt-gameplay-probe.sh
  ./dev-real-srg-bytecode-audit.sh "$PWD/out/forge-classes"
  ./dev-rfg-reobf-equivalent-contract.sh
fi
if [[ -n "${ACOUSTIC_WINLAB_ROOT:-}" ]]; then
  ./dev-winlab-contract.sh
fi
if [[ "${ACOUSTIC_FORGE1122_CLIENT_GATE:-0}" == 1 ]]; then
  ACOUSTIC_CLIENT_BOOT_LEVEL="${ACOUSTIC_CLIENT_BOOT_LEVEL:-full}" ./dev-forge1122-client-launch.sh
fi
ACOUSTIC_TEST_REUSE_PRODUCTION_CLASSES=1 ./dev-test.sh
rm -rf out/conformance-release
mkdir -p out/conformance-release/reference
for profile in POTATO LOW MEDIUM HIGH ULTRA MAXIMUM; do
  timeout 60s env \
    ACOUSTIC_CONFORMANCE_PROFILE="$profile" \
    ACOUSTIC_CONFORMANCE_REUSE_CLASSES=1 \
    ACOUSTIC_CONFORMANCE_CLASSPATH="$PWD/out/classes:$PWD/out/forge-classes" \
    ./dev-conformance.sh examples/reference-pack out/conformance-release/reference
  test -s "out/conformance-release/reference/${profile}-rir.wav"
  test -s "out/conformance-release/reference/${profile}-timings.txt"
  test -s "out/conformance-release/reference/${profile}-pipeline.dot"
done
printf '%s\n' '[PASS] isolated six-profile conformance gate'
# The production OpenCL kernels are embedded in Kotlin so the mod has no runtime file dependency.
# Syntax-check the exact embedded sources against OpenCL 1.0 whenever clang is available.
if command -v clang >/dev/null 2>&1; then
  python3 - <<'PYCL'
from pathlib import Path
import ast,re

def extract(path):
    source=Path(path).read_text()
    marker='private const val KERNEL_SOURCE ='
    start=source.index(marker)+len(marker)
    tail=source[start:]
    raw=re.match(r'\s*"""(.*?)"""',tail,re.S)
    if raw:
        return raw.group(1)
    # Concatenated escaped Kotlin strings use the same escape spellings as Java here.
    end=tail.index('\n\n        private fun ',0) if '\n\n        private fun ' in tail else len(tail)
    parts=re.findall(r'"(?:\\.|[^"\\])*"',tail[:end])
    if not parts:
        raise SystemExit('OpenCL kernel source extraction failed: '+path)
    return ''.join(ast.literal_eval(part) for part in parts)

for source_name,out_name in [
    ('minecraft-1.12.2/src/forge/kotlin/dev/acoustic/mc1122/forge/OpenClFdtdBackend.kt','out/acoustic-fdtd.cl'),
    ('minecraft-1.12.2/src/forge/kotlin/dev/acoustic/mc1122/forge/OpenClGeometricBackend.kt','out/acoustic-rays.cl')
]:
    Path(out_name).write_text(extract(source_name))
PYCL
  clang -x cl -cl-std=CL1.0 -fsyntax-only out/acoustic-fdtd.cl
  clang -x cl -cl-std=CL1.0 -fsyntax-only out/acoustic-rays.cl
  printf '%s\n' '[PASS] embedded OpenCL C 1.0 FDTD + geometric-ray kernel syntax'
fi
# CUDA is compiled by NVRTC on the user's NVIDIA driver. The container has no CUDA
# device/NVRTC, so perform a strict host-C++ syntax pass over the exact shipped CUDA C
# resources in addition to runtime self-tests on real hardware. The shim only models
# CUDA keywords/builtin dim3 variables; it does not pretend to validate device execution.
if command -v clang++ >/dev/null 2>&1; then
  cat > out/cuda-syntax-shim.h <<'EOF_CUDA_SHIM'
#define __global__
#define __device__
#define __forceinline__ inline
#include <cmath>
struct acoustic_dim3 { unsigned int x,y,z; };
static acoustic_dim3 blockIdx={0,0,0},blockDim={1,1,1},threadIdx={0,0,0};
EOF_CUDA_SHIM
  clang++ -std=c++11 -x c++ -include out/cuda-syntax-shim.h -Wno-unknown-pragmas -fsyntax-only minecraft-1.12.2/src/forge/resources/assets/acousticshaders/cuda/fdtd.cu
  clang++ -std=c++11 -x c++ -include out/cuda-syntax-shim.h -Wno-unknown-pragmas -fsyntax-only minecraft-1.12.2/src/forge/resources/assets/acousticshaders/cuda/rays.cu
  grep -F 'extern "C" __global__ void acoustic_fdtd' minecraft-1.12.2/src/forge/resources/assets/acousticshaders/cuda/fdtd.cu >/dev/null
  grep -F 'extern "C" __global__ void acoustic_rays' minecraft-1.12.2/src/forge/resources/assets/acousticshaders/cuda/rays.cu >/dev/null
  printf '%s\n' '[PASS] shipped CUDA C FDTD + geometric-ray kernel host syntax/entry points'
fi
./dev-build-shaderpacks.sh >/dev/null
ACOUSTIC_PORTABLE_REUSE_PRODUCTION_CLASSES=1 ./dev-build-portable.sh >/dev/null
python3 - <<'PY'
from pathlib import Path
import struct, zipfile
jar=Path('dist/acoustic-shaders-portable-0.3.jar')
if not jar.is_file(): raise SystemExit('portable jar missing')
with zipfile.ZipFile(jar) as z:
    classes=[n for n in z.namelist() if n.endswith('.class')]
    if not classes: raise SystemExit('portable jar contains no classes')
    for n in classes:
        data=z.read(n)
        if data[:4]!=b'\xca\xfe\xba\xbe': raise SystemExit('bad class '+n)
        major=struct.unpack('>H',data[6:8])[0]
        if major>52: raise SystemExit(f'non-Java8 class {n}: {major}')
print('[PASS] portable Java 8 runtime jar')
PY
KOTLIN_VERSION_FILE="$PWD/out/kotlin-compiler.version"
test -s "$KOTLIN_VERSION_FILE" || { echo "ERROR: Kotlin compiler version stamp missing" >&2; exit 1; }
KOTLIN_VERSION="$(cat "$KOTLIN_VERSION_FILE")"
GIT_HEAD="$(git rev-parse HEAD)"
if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  echo 'ERROR: working tree is not fully clean after verification; refusing verification stamp' >&2
  git status --short --untracked-files=all >&2
  exit 1
fi
INPUT_FINGERPRINT="$(./release-input-fingerprint.sh)"
printf 'head=%s\nkotlin=%s\ninputs=%s\n' "$GIT_HEAD" "$KOTLIN_VERSION" "$INPUT_FINGERPRINT" > "$PWD/out/release-verification.stamp"
printf '%s\n' '[PASS] unified local verification gate'
