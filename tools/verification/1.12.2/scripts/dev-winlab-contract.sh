#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"

WINLAB_ROOT="${ACOUSTIC_WINLAB_ROOT:-}"
[[ -n "$WINLAB_ROOT" ]] || { echo 'ERROR: ACOUSTIC_WINLAB_ROOT is required' >&2; exit 2; }
WINLAB_ROOT="$(cd "$WINLAB_ROOT" 2>/dev/null && pwd)" || { echo "ERROR: WinLab root does not exist: $WINLAB_ROOT" >&2; exit 1; }
if [[ ! -x "$WINLAB_ROOT/run" ]]; then
  mapfile -t WINLAB_CHILDREN < <(find "$WINLAB_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'winlab*' -print | sort)
  if [[ ${#WINLAB_CHILDREN[@]} -eq 1 && -x "${WINLAB_CHILDREN[0]}/run" ]]; then
    WINLAB_ROOT="${WINLAB_CHILDREN[0]}"
  else
    echo "ERROR: WinLab run launcher missing under: $WINLAB_ROOT" >&2
    exit 1
  fi
fi
RUN="$WINLAB_ROOT/run"
OUT="$ROOT/out/winlab-contract"
rm -rf "$OUT"
mkdir -p "$OUT"

for cmd in timeout javac jar python3 grep; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: required command missing: $cmd" >&2; exit 1; }
done

cleanup() {
  set +e
  timeout 10s "$RUN" wineserver -k >/dev/null 2>&1
  timeout 15s "$RUN" wineserver32 -k >/dev/null 2>&1
  set -e
}
trap cleanup EXIT

run_probe() {
  local label="$1" seconds="$2" log="$3"
  shift 3
  set +e
  timeout --signal=TERM --kill-after=5s "${seconds}s" "$@" >"$log" 2>&1
  local rc=$?
  set -e
  cat "$log"
  if [[ $rc -ne 0 ]]; then
    if grep -F 'is not owned by you' "$log" >/dev/null 2>&1; then
      echo "ERROR: $label failed because a preinitialized WinLab prefix is owned by another user." >&2
      echo 'Use a local extracted WinLab copy owned by the account running this gate; do not chown a shared/original archive in place.' >&2
    elif [[ $rc -eq 124 || $rc -eq 137 ]]; then
      echo "ERROR: $label timed out after ${seconds}s" >&2
    else
      echo "ERROR: $label failed with rc=$rc" >&2
    fi
    exit "$rc"
  fi
}

printf '%s\n' '[WinLab] bundled PowerShell availability'
run_probe 'WinLab PowerShell' 25 "$OUT/powershell-version.log" \
  "$RUN" pwsh -NoLogo -NoProfile -NonInteractive -Command '$PSVersionTable.PSVersion.ToString()'

INSTALLER="$ROOT/tools/verification/1.12.2/windows/Run-Forge1122-Validation.ps1"
[[ -s "$INSTALLER" ]] || { echo "ERROR: installer missing: $INSTALLER" >&2; exit 1; }
if grep -F '$ForgelinContinuousVersion:' "$INSTALLER" >/dev/null; then
  echo 'ERROR: ambiguous PowerShell variable interpolation returned: use ${ForgelinContinuousVersion} before a colon' >&2
  exit 1
fi
printf '%s\n' '[WinLab] PowerShell AST parse of Windows validation harness'
run_probe 'PowerShell AST parser' 30 "$OUT/powershell-ast.log" \
  env ACOUSTIC_INSTALLER_PATH="$INSTALLER" \
  "$RUN" pwsh -NoLogo -NoProfile -NonInteractive -Command \
  '$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile($env:ACOUSTIC_INSTALLER_PATH,[ref]$tokens,[ref]$errors) | Out-Null; if ($errors.Count -ne 0) { $errors | ForEach-Object { [Console]::Error.WriteLine($_.ToString()) }; exit 1 }; Write-Output "POWERSHELL-AST-OK"'
grep -F 'POWERSHELL-AST-OK' "$OUT/powershell-ast.log" >/dev/null
for token in \
  'CudaFdtdHardwareGate' \
  'profile=$runtimeProfile' \
  'option.COMPUTE_BACKEND=CUDA' \
  'option.RAY_COMPUTE_BACKEND=CPU_PARALLEL' \
  'waveCompute=\{([^}]*)\}' \
  'CudaFdtdHardwareGatePassed='; do
  grep -F "$token" "$INSTALLER" >/dev/null || {
    echo "ERROR: Windows CUDA FDTD hardware-gate contract missing: $token" >&2
    exit 1
  }
done
printf '%s\n' '[PASS] Windows validation harness exposes strict CUDA FDTD hardware mode without CUDA-ray false positives'

RFG_WINDOWS_LAUNCHER="$ROOT/tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1"
[[ -s "$RFG_WINDOWS_LAUNCHER" ]] || { echo "ERROR: Windows RFG launcher missing: $RFG_WINDOWS_LAUNCHER" >&2; exit 1; }
printf '%s\n' '[WinLab] PowerShell AST parse of Windows/WSL RFG launcher'
run_probe 'RFG Windows launcher AST parser' 30 "$OUT/rfg-windows-launcher-ast.log" \
  env ACOUSTIC_RFG_WINDOWS_LAUNCHER="$RFG_WINDOWS_LAUNCHER" \
  "$RUN" pwsh -NoLogo -NoProfile -NonInteractive -Command \
  '$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile($env:ACOUSTIC_RFG_WINDOWS_LAUNCHER,[ref]$tokens,[ref]$errors) | Out-Null; if ($errors.Count -ne 0) { $errors | ForEach-Object { [Console]::Error.WriteLine($_.ToString()) }; exit 1 }; Write-Output "RFG-WINDOWS-LAUNCHER-AST-OK"'
grep -F 'RFG-WINDOWS-LAUNCHER-AST-OK' "$OUT/rfg-windows-launcher-ast.log" >/dev/null

printf '%s\n' '[WinLab] Wine x64 Windows process'
run_probe 'Wine x64 process' 35 "$OUT/wine-x64.log" \
  "$RUN" wine cmd.exe /d /c ver
grep -F 'Microsoft Windows' "$OUT/wine-x64.log" >/dev/null || { echo 'ERROR: Wine x64 probe did not report a Windows version' >&2; exit 1; }

printf '%s\n' '[WinLab] forced x86 userspace process + nested child'
run_probe 'forced x86 process' 60 "$OUT/wine-x86.log" \
  env WINLAB_X86_MODE=emu WINLAB_X86_DIAGNOSTICS=1 \
  "$RUN" x86 cmd.exe /d /c 'echo ACOUSTIC-X86-OK && echo ARCH=%PROCESSOR_ARCHITECTURE% && cmd.exe /d /c echo ACOUSTIC-X86-CHILD-OK'
grep -F 'ACOUSTIC-X86-OK' "$OUT/wine-x86.log" >/dev/null
grep -F 'ARCH=x86' "$OUT/wine-x86.log" >/dev/null
grep -F 'ACOUSTIC-X86-CHILD-OK' "$OUT/wine-x86.log" >/dev/null

FORGE_CLASSES="$ROOT/out/forge-classes"
[[ -d "$FORGE_CLASSES/dev/acoustic" ]] || {
  echo 'ERROR: out/forge-classes is missing; run tools/verification/1.12.2/scripts/dev-legacy-contract.sh before the WinLab gate' >&2
  exit 1
}
CURRENT_BYTECODE_JAR="$OUT/current-compiled-mod-bytecode.jar"
jar cf "$CURRENT_BYTECODE_JAR" -C "$FORGE_CLASSES" .

wine_z_path() {
  python3 - "$1" <<'PY'
from pathlib import Path
import sys
p=str(Path(sys.argv[1]).resolve())
print('Z:' + p.replace('/', '\\'))
PY
}

VISIBLE_JAR="${ACOUSTIC_WINLAB_JAR:-$CURRENT_BYTECODE_JAR}"
[[ -f "$VISIBLE_JAR" ]] || { echo "ERROR: WinLab visibility JAR missing: $VISIBLE_JAR" >&2; exit 1; }
VISIBLE_JAR_WIN="$(wine_z_path "$VISIBLE_JAR")"
printf '%s\n' '[WinLab] current/baseline JAR visibility through Wine Z: mapping'
run_probe 'Wine JAR visibility' 30 "$OUT/jar-visibility.log" \
  "$RUN" wine cmd.exe /d /c if exist "$VISIBLE_JAR_WIN" echo ACOUSTIC-JAR-VISIBLE
grep -F 'ACOUSTIC-JAR-VISIBLE' "$OUT/jar-visibility.log" >/dev/null

WINDOWS_JAVA_HOME="${ACOUSTIC_WINLAB_JAVA:-}"
if [[ -n "$WINDOWS_JAVA_HOME" ]]; then
  WINDOWS_JAVA_HOME="$(cd "$WINDOWS_JAVA_HOME" 2>/dev/null && pwd)" || { echo "ERROR: Windows Java home does not exist: $WINDOWS_JAVA_HOME" >&2; exit 1; }
  WINDOWS_JAVA="$WINDOWS_JAVA_HOME/bin/java.exe"
  [[ -f "$WINDOWS_JAVA" ]] || { echo "ERROR: Windows Java executable missing: $WINDOWS_JAVA" >&2; exit 1; }

  KOTLINC_BIN="${ACOUSTIC_KOTLINC:-$(command -v kotlinc)}"
  [[ -x "$KOTLINC_BIN" ]] || { echo "ERROR: kotlinc not executable: $KOTLINC_BIN" >&2; exit 1; }
  KOTLIN_VERSION="$($KOTLINC_BIN -version 2>&1 | sed -n 's/.*kotlinc-jvm \([^ ]*\).*/\1/p' | head -1)"
  [[ "$KOTLIN_VERSION" == '2.4.0' ]] || { echo "ERROR: WinLab release DSP gate requires exact Kotlin 2.4.0 classes, found $KOTLIN_VERSION" >&2; exit 1; }
  KOTLIN_HOME="$(cd "$(dirname "$KOTLINC_BIN")/.." && pwd)"
  KOTLIN_CP="$KOTLIN_HOME/lib/kotlin-stdlib.jar:$KOTLIN_HOME/lib/kotlin-stdlib-jdk7.jar:$KOTLIN_HOME/lib/kotlin-stdlib-jdk8.jar"
  for kjar in "$KOTLIN_HOME/lib/kotlin-stdlib.jar" "$KOTLIN_HOME/lib/kotlin-stdlib-jdk7.jar" "$KOTLIN_HOME/lib/kotlin-stdlib-jdk8.jar"; do
    [[ -f "$kjar" ]] || { echo "ERROR: Kotlin runtime jar missing: $kjar" >&2; exit 1; }
  done

  printf '%s\n' '[WinLab] Windows Java 8 runtime'
  run_probe 'Windows Java 8 version' 45 "$OUT/windows-java-version.log" \
    "$RUN" wine "$WINDOWS_JAVA" -version
  grep -F 'java version "1.8.' "$OUT/windows-java-version.log" >/dev/null || { echo 'ERROR: WinLab Java is not Java 8' >&2; exit 1; }

  cat > "$OUT/AcousticWinLabDspSmoke.java" <<'JAVA'
import dev.acoustic.api.math.Vec3;
import dev.acoustic.core.dsp.PartitionedConvolver;
import dev.acoustic.core.dsp.PcmCodec;
import dev.acoustic.core.dsp.Radix2Fft;

public final class AcousticWinLabDspSmoke {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, double epsilon, String message) {
        if (Math.abs(actual - expected) > epsilon) {
            throw new AssertionError(message + ": got=" + actual + " expected=" + expected);
        }
    }

    public static void main(String[] args) {
        String os = System.getProperty("os.name", "");
        require(os.startsWith("Windows"), "Windows JVM expected, got " + os);

        byte[] pcm16le = new byte[] { 0, 0, (byte)0xff, 0x7f, 0, (byte)0x80 };
        float[] decoded = PcmCodec.decodeMono(pcm16le, 16, true, false);
        require(decoded.length == 3, "PCM decode length");
        near(decoded[0], 0.0, 1.0e-6, "PCM zero");
        require(decoded[1] > 0.99f, "PCM positive peak");
        near(decoded[2], -1.0, 1.0e-6, "PCM negative peak");

        double[] re = new double[] { 1.0, 2.0, 3.0, 4.0, 0.0, 0.0, 0.0, 0.0 };
        double[] original = re.clone();
        double[] im = new double[re.length];
        Radix2Fft.transform(re, im, false);
        Radix2Fft.transform(re, im, true);
        for (int i = 0; i < re.length; i++) near(re[i], original[i], 1.0e-9, "FFT round-trip " + i);

        float[] impulse = new float[] { 1.0f, 0.5f, -0.25f };
        PartitionedConvolver convolver = new PartitionedConvolver(impulse, 16);
        float[] input = new float[16];
        input[0] = 1.0f;
        float[] output = convolver.process(input);
        near(output[0], 1.0, 1.0e-5, "convolver direct");
        near(output[1], 0.5, 1.0e-5, "convolver tap 1");
        near(output[2], -0.25, 1.0e-5, "convolver tap 2");

        Vec3 v = new Vec3(3.0, 4.0, 0.0);
        near(v.length(), 5.0, 1.0e-12, "Vec3 length");
        near(v.normalize().length(), 1.0, 1.0e-12, "Vec3 normalize");

        System.out.println("ACOUSTIC-WINLAB-DSP-OK os=" + os + " java=" + System.getProperty("java.version"));
    }
}
JAVA
  mkdir -p "$OUT/smoke-classes"
  javac --release 8 -Xlint:all,-options -Werror \
    -cp "$FORGE_CLASSES:$KOTLIN_CP" \
    -d "$OUT/smoke-classes" \
    "$OUT/AcousticWinLabDspSmoke.java"

  SMOKE_CP_WIN="$(wine_z_path "$OUT/smoke-classes");$(wine_z_path "$FORGE_CLASSES");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib.jar");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib-jdk7.jar");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib-jdk8.jar")"
  printf '%s\n' '[WinLab] actual RC19 JVM8 DSP bytecode on Windows Java 8'
  run_probe 'Windows Java 8 RC19 DSP smoke' 60 "$OUT/windows-java-dsp.log" \
    "$RUN" wine "$WINDOWS_JAVA" -ea -cp "$SMOKE_CP_WIN" AcousticWinLabDspSmoke
  grep -F 'ACOUSTIC-WINLAB-DSP-OK' "$OUT/windows-java-dsp.log" >/dev/null

  RUNTIME_SMOKE="$ROOT/out/runtime-smoke"
  SRG_GUI_SMOKE="$ROOT/out/srg-gui-smoke"
  FORGE_STUBS="$ROOT/out/forge-stubs"
  [[ -d "$RUNTIME_SMOKE" && -d "$SRG_GUI_SMOKE" && -d "$FORGE_STUBS" ]] || {
    echo 'ERROR: legacy runtime/SRG smoke classes are missing; run tools/verification/1.12.2/scripts/dev-legacy-contract.sh before the WinLab gate' >&2
    exit 1
  }
  LEGACY_CP_WIN="$(wine_z_path "$RUNTIME_SMOKE");$(wine_z_path "$FORGE_STUBS");$(wine_z_path "$FORGE_CLASSES");$(wine_z_path "$ROOT/minecraft-1.12.2/src/forge/resources");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib.jar");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib-jdk7.jar");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib-jdk8.jar")"

  printf '%s\n' '[WinLab] legacy Forge/software-wet runtime smoke on Windows Java 8'
  run_probe 'Windows Java 8 legacy Forge/software-wet runtime smoke' 180 "$OUT/windows-java-legacy-forge.log" \
    "$RUN" wine "$WINDOWS_JAVA" -ea -cp "$LEGACY_CP_WIN" dev.acoustic.mc1122.forge.LegacyForgeSmokeTest
  grep -F 'PASS: legacy Forge runtime + fast EFX + async full shader + software-wet lifecycle' "$OUT/windows-java-legacy-forge.log" >/dev/null

  if [[ -n "${ACOUSTIC_LWJGL2_JAR:-}" && -s "$ROOT/out/forge1122-client-preflight/windows-native-archives.txt" ]]; then
    printf '%s\n' '[WinLab] production software-wet backend against real LWJGL2/OpenAL'
    ACOUSTIC_WINDOWS_JAVA8="$WINDOWS_JAVA" "$ROOT/tools/verification/1.12.2/scripts/dev-winlab-real-openal-wet.sh"
    if [[ -n "${ACOUSTIC_MC1122_HOME:-}" ]]; then
      printf '%s\n' '[WinLab] production LegacySoundHook against real Minecraft Paulscode/LWJGL2 source lifecycle'
      ACOUSTIC_WINDOWS_JAVA8="$WINDOWS_JAVA" "$ROOT/tools/verification/1.12.2/scripts/dev-winlab-real-paulscode-lifecycle.sh"
    else
      printf '%s\n' '[SKIP] real Paulscode lifecycle gate requires ACOUSTIC_MC1122_HOME'
    fi
  else
    printf '%s\n' '[SKIP] real OpenAL/Paulscode gates require ACOUSTIC_LWJGL2_JAR + Forge client preflight natives'
  fi

  SRG_GUI_CP_WIN="$(wine_z_path "$SRG_GUI_SMOKE");$(wine_z_path "$FORGE_STUBS");$(wine_z_path "$FORGE_CLASSES");$(wine_z_path "$ROOT/minecraft-1.12.2/src/forge/resources");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib.jar");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib-jdk7.jar");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib-jdk8.jar")"
  WINDOWS_SRG_WORK="$OUT/windows-srg-gui-work"
  rm -rf "$WINDOWS_SRG_WORK"
  mkdir -p "$WINDOWS_SRG_WORK"
  printf '%s\n' '[WinLab] SRG-only GUI runtime smoke on Windows Java 8'
  (
    cd "$WINDOWS_SRG_WORK"
    run_probe 'Windows Java 8 SRG GUI smoke' 120 "$OUT/windows-java-srg-gui.log" \
      "$RUN" wine "$WINDOWS_JAVA" -ea -cp "$SRG_GUI_CP_WIN" dev.acoustic.mc1122.forge.SrgGuiRuntimeSmokeTest
  )
  grep -F '[PASS] SRG-only 427x240 Music & Sounds layout + selector/options GUI runtime smoke' "$OUT/windows-java-srg-gui.log" >/dev/null

  # Execute the complete portable release suites under the real Windows Java 8 runtime too.
  # Reuse the exact production bytecode already compiled by tools/verification/1.12.2/scripts/dev-legacy-contract.sh so this
  # remains a platform/runtime check rather than a second independent production build.
  WINDOWS_TEST_CLASSES="$OUT/windows-portable-test-classes"
  rm -rf "$WINDOWS_TEST_CLASSES"
  mkdir -p "$WINDOWS_TEST_CLASSES"
  "$ROOT/tools/verification/1.12.2/scripts/dev-compile-jvm8.sh" "$WINDOWS_TEST_CLASSES" "$FORGE_CLASSES" \
    "$ROOT/acoustic-testkit/src/main/java" "$ROOT/acoustic-testkit/src/main/kotlin" \
    "$ROOT/acoustic-tools/src/main/java" "$ROOT/acoustic-tools/src/main/kotlin" \
    "$ROOT/acoustic-tests/src/test/java" "$ROOT/acoustic-tests/src/test/kotlin"

  python3 - "$ROOT/examples/reference-pack" "$ROOT/examples/reference-pack.zip" <<'PYZIP'
import os,sys,zipfile
root,out=sys.argv[1:3]
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
    for base,_,files in os.walk(root):
        for f in files:
            p=os.path.join(base,f)
            z.write(p,os.path.relpath(p,root))
PYZIP

  WINDOWS_TEST_CP="$(wine_z_path "$WINDOWS_TEST_CLASSES");$(wine_z_path "$FORGE_CLASSES");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib.jar");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib-jdk7.jar");$(wine_z_path "$KOTLIN_HOME/lib/kotlin-stdlib-jdk8.jar")"

  printf '%s\n' '[WinLab] complete 54-test portable suite on Windows Java 8'
  run_probe 'Windows Java 8 54-test portable suite' 180 "$OUT/windows-java-headless.log" \
    "$RUN" wine "$WINDOWS_JAVA" -ea -cp "$WINDOWS_TEST_CP" dev.acoustic.tests.HeadlessTestSuite
  grep -F 'PASS: 54 headless tests' "$OUT/windows-java-headless.log" >/dev/null

  printf '%s\n' '[WinLab] complete 39-test advanced release suite on Windows Java 8'
  run_probe 'Windows Java 8 39-test advanced release suite' 180 "$OUT/windows-java-advanced.log" \
    "$RUN" wine "$WINDOWS_JAVA" -ea -cp "$WINDOWS_TEST_CP" dev.acoustic.tests.AdvancedReleaseTestSuite
  grep -F 'PASS: 39 advanced release tests' "$OUT/windows-java-advanced.log" >/dev/null
else
  printf '%s\n' '[SKIP] ACOUSTIC_WINLAB_JAVA is unset; Windows Java 8 DSP execution was not requested'
fi

printf '%s\n' '[PASS] focused WinLab Windows-compatibility contract'
