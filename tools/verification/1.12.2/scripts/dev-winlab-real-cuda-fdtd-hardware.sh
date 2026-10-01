#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"

# This is an external hardware gate. It intentionally cannot pass in the normal
# development container, which exposes no NVIDIA CUDA device/NVRTC to the JVM.
# Rays are forced to CPU_PARALLEL so previously-proven CUDA ray activity can
# never be mistaken for CUDA FDTD execution.
OUT="${ACOUSTIC_REAL_CUDA_FDTD_OUT:-$ROOT/out/real-cuda-fdtd-hardware}"
rm -rf "$OUT"

ACOUSTIC_REAL_MINECRAFT_GAMEPLAY_SOUND_EVENT_OUT="$OUT" \
ACOUSTIC_CLIENT_PROFILE=MAXIMUM \
ACOUSTIC_CLIENT_COMPUTE_BACKEND=CUDA \
ACOUSTIC_CLIENT_RAY_COMPUTE_BACKEND=CPU_PARALLEL \
ACOUSTIC_CLIENT_REQUIRE_CUDA_FDTD=1 \
ACOUSTIC_CLIENT_DEBUG=1 \
ACOUSTIC_CLIENT_BOOT_TIMEOUT="${ACOUSTIC_CLIENT_BOOT_TIMEOUT:-360}" \
  "$ROOT/tools/verification/1.12.2/scripts/dev-winlab-real-minecraft-gameplay-sound-event.sh"

LOG="$OUT/client/logs/client-console.log"
grep -F 'ACOUSTIC-REAL-CUDA-FDTD-OK backend=cuda' "$LOG" >/dev/null || {
  echo 'ERROR: real CUDA FDTD proof marker missing' >&2
  exit 1
}
grep -E 'ACOUSTIC-REAL-CUDA-FDTD-OK backend=cuda solves=[1-9][0-9]* failures=0 .*self-test=pass' "$LOG" >/dev/null || {
  echo 'ERROR: CUDA FDTD marker lacks positive solves / zero failures / self-test=pass' >&2
  exit 1
}
printf '%s\n' '[PASS] real NVIDIA CUDA FDTD: MAXIMUM loaded-world vanilla TNT path, validated solve>0, failures=0, self-test=pass; rays isolated on CPU_PARALLEL'
