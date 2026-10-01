#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
for f in tools/verification/1.12.2/rfg/settings.gradle tools/verification/1.12.2/rfg/build.gradle tools/verification/1.12.2/rfg/gradle.properties tools/verification/1.12.2/rfg/bootstrap-wsl.sh tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 tools/verification/1.12.2/dev-tools/prepare-rfg-kotlin-source.py tools/verification/1.12.2/dev-tools/verify-rfg-reobf-jar.py dev-winlab-contract.sh; do
  [[ -s "$f" ]] || { echo "ERROR: missing RFG workspace file $f" >&2; exit 1; }
done
grep -F "RFG_COMMIT='94702da47e2c0d626986a42bd8124c63e52afc2a'" tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'gradle-8.14.3-bin.zip' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'function Convert-WindowsPathToWslMount' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F 'function ConvertTo-Utf8Base64' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F 'function Invoke-WslProbeWithRetry' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F 'function Write-WslFailureDiagnostics' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F -- "-Arguments @('bash', '-n', \$RunnerWsl)" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F -- "-Attempts 3" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F "@{ Label = 'wsl.exe --status'; Args = @('--status') }" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F "@{ Label = 'wsl.exe -l -v'; Args = @('-l', '-v') }" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F "Write-WslFailureDiagnostics -Path \$Failure" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F 'return "/mnt/$drive/$tail"' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F '$Matches[2].Replace([char]92, [char]47)' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F "PathSelfTestInput = 'C:\Users\ExampleUser\Downloads\AcousticShaders-RFG-WSL-RUN.sh'" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F "PathSelfTestExpected = '/mnt/c/Users/ExampleUser/Downloads/AcousticShaders-RFG-WSL-RUN.sh'" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
python3 - <<'PY'
from pathlib import Path
s = Path('tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1').read_text()
if ".Replace('\\\\', '/')" in s:
    raise SystemExit('ERROR: Windows RFG launcher must not use a two-backslash PowerShell replacement literal')
PY
if grep -F 'wslpath -a -u' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null; then
  echo 'ERROR: Windows RFG launcher must not depend on fragile wslpath argument conversion' >&2
  exit 1
fi
if grep -F 'bash -lc $WslScript' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null; then
  echo 'ERROR: Windows RFG launcher must use a temporary .sh file, not multiline bash -lc transport' >&2
  exit 1
fi
grep -F '& wsl.exe bash $RunnerWsl' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F '$Reported = $false' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F "Get-ChildItem -LiteralPath \$Downloads -File -Filter 'AcousticShaders-RFG-WSL-Bundle-*.zip'" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F "\$Bundle.Name -notmatch '^AcousticShaders-RFG-WSL-Bundle-([0-9a-fA-F]{7,40})\.zip\$'" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
grep -F 'case "$ACTUAL_HEAD" in' tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1 >/dev/null
if grep -Eq "ExpectedBundleSha256|ExpectedHead = '[0-9a-f]{7,40}'|BundleName = 'AcousticShaders-RFG-WSL-Bundle-[0-9a-f]+\.zip'" tools/verification/1.12.2/rfg/run-rfg-gate-windows.ps1; then
  echo 'ERROR: Windows RFG launcher must not hard-code a stale bundle SHA/HEAD/name' >&2
  exit 1
fi
grep -F "name = 'Fabric Maven'" tools/verification/1.12.2/rfg/settings.gradle >/dev/null
grep -F "url = uri('https://maven.fabricmc.net/')" tools/verification/1.12.2/rfg/settings.gradle >/dev/null
grep -F "MERCURY_POM='https://maven.fabricmc.net/net/fabricmc/mercury/0.6.0/mercury-0.6.0.pom'" tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'MIN_FREE_MIB="${ACOUSTIC_RFG_MIN_FREE_MIB:-8192}"' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'ensure_rfg_disk_space' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'remove_generated_path "$ROOT/tools/verification/1.12.2/rfg/build"' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'remove_generated_path "$LEGACY_RFG"' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'Preserved ~/.gradle dependency/Minecraft caches' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
if grep -F 'rm -rf "$HOME/.gradle/caches"' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null; then
  echo 'ERROR: RFG disk preflight must not delete the shared Gradle dependency cache' >&2
  exit 1
fi
grep -F "id 'org.gradle.toolchains.foojay-resolver-convention' version '0.7.0'" tools/verification/1.12.2/rfg/settings.gradle >/dev/null
grep -F 'languageVersion = JavaLanguageVersion.of(8)' tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F 'jvmLanguageVersion = JavaLanguageVersion.of(8)' tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F 'case "1.12.2" -> "1.12.2-14.23.5.2847";' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'LEGACY_RFG="$WORK/RetroFuturaGradle-1.4.9-forge2864"' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'git clone --local --no-hardlinks "$LEGACY_RFG" "$RFG"' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F 'git -C "$RFG" reset --hard "$RFG_COMMIT"' tools/verification/1.12.2/rfg/bootstrap-wsl.sh >/dev/null
grep -F "configurations.matching { it.name == 'forgeUniversal' }.configureEach" tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F "details.useVersion('1.12.2-14.23.5.2864')" tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F "id.group == 'net.minecraftforge' && id.name == 'forge'" tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F "def universal = forgeArtifacts.findAll { it.classifier == 'universal' }" tools/verification/1.12.2/rfg/build.gradle >/dev/null
if grep -F "it.moduleVersion.id.group == 'net.minecraftforge' && it.name == 'forge'" tools/verification/1.12.2/rfg/build.gradle >/dev/null; then
  echo 'ERROR: RFG Forge verifier must use moduleVersion.id.name, not ResolvedArtifact.name' >&2
  exit 1
fi
if grep -F '1.12.2-14.23.5.2864-userdev' tools/verification/1.12.2/rfg/bootstrap-wsl.sh tools/verification/1.12.2/rfg/build.gradle >/dev/null; then
  echo 'ERROR: RFG workspace must not request the non-existent Forge 2864 userdev' >&2
  exit 1
fi
if grep -F 'rfg-forge2864.patch' tools/verification/1.12.2/rfg/bootstrap-wsl.sh tools/verification/1.12.2/rfg/README.md >/dev/null; then
  echo 'ERROR: stale evidence of patching RFG tooling to Forge 2864 remains' >&2
  exit 1
fi
grep -F "implementation(mixinBooter) { transitive = false }" tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F "runtimeOnly 'maven.modrinth:1mPcAmuy:jZIkQLdu'" tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F "'-Xjdk-release=8'" tools/verification/1.12.2/rfg/build.gradle >/dev/null
if grep -n -A8 "def args = \[" tools/verification/1.12.2/rfg/build.gradle | grep -F "'-jvm-target'" >/dev/null; then
  echo 'ERROR: RFG Kotlin compile combines -Xjdk-release with conflicting -jvm-target' >&2
  exit 1
fi
grep -F "'-jvm-default=no-compatibility'" tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F "'-Werror'" tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F 'def existingClasspath = rawClasspath.findAll { it.exists() }' tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F 'def missingClasspath = rawClasspath.findAll { !it.exists() }' tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F "'-classpath', files(existingClasspath).asPath" tools/verification/1.12.2/rfg/build.gradle >/dev/null
if grep -F "'-classpath', sourceSets.main.compileClasspath.asPath" tools/verification/1.12.2/rfg/build.gradle >/dev/null; then
  echo 'ERROR: RFG Kotlin compiler must not receive non-existent Gradle output directories' >&2
  exit 1
fi
grep -F "'MixinConfigs': 'mixins.acousticshaders.json'" tools/verification/1.12.2/rfg/build.gradle >/dev/null
grep -F "new File(projectRoot, 'tools/verification/1.12.2/dev-tools/verify-rfg-reobf-jar.py').absolutePath" tools/verification/1.12.2/rfg/build.gradle >/dev/null
# These compile-stub shapes intentionally mirror the exact Minecraft 1.12.2 launcher APIs that
# previously differed from our permissive stubs and failed only inside real RFG.
grep -F 'public static final int CL_MEM_READ_WRITE=1,CL_MEM_WRITE_ONLY=2,CL_MEM_READ_ONLY=4,CL_MEM_COPY_HOST_PTR=32;' minecraft-1.12.2/compile-stubs/src/main/java/org/lwjgl/opencl/CL10.java >/dev/null
grep -F 'public static final int CL_DEVICE_TYPE_GPU=4;' minecraft-1.12.2/compile-stubs/src/main/java/org/lwjgl/opencl/CL10.java >/dev/null
grep -F 'At[] at()' minecraft-1.12.2/compile-stubs/src/main/java/org/spongepowered/asm/mixin/injection/Inject.java >/dev/null
if grep -R -F 'org.lwjgl.opencl.CLObject' minecraft-1.12.2/src/forge/kotlin >/dev/null; then
  echo 'ERROR: production Kotlin must not reference LWJGL2 package-private CLObject' >&2
  exit 1
fi
grep -F 'platform.getDevices(CL10.CL_DEVICE_TYPE_GPU)' minecraft-1.12.2/src/forge/kotlin/dev/acoustic/mc1122/forge/OpenClDeviceSelector.kt >/dev/null
for f in MixinSoundSystem.kt MixinSourceLWJGLOpenAL.kt MixinSourceLifecycle.kt; do
  grep -F 'at = [At(' "minecraft-1.12.2/src/forge/kotlin/dev/acoustic/mc1122/mixin/$f" >/dev/null
done
OUT="$ROOT/out/rfg-source-contract"
python3 tools/verification/1.12.2/dev-tools/prepare-rfg-kotlin-source.py --root "$ROOT" --out "$OUT"
python3 - "$OUT" <<'PY'
from pathlib import Path
import re,sys
root=Path(sys.argv[1])
files=list(root.rglob('*.kt'))
if len(files) < 150: raise SystemExit(f'ERROR: suspiciously small RFG source view: {len(files)}')
for name in ('GuiAcousticShaders.kt','GuiAcousticShaderOptions.kt','GuiRuntimeAudio.kt'):
    p=next(root.rglob(name));s=p.read_text()
    if re.search(r'\boverride\s+fun\s+func_',s): raise SystemExit('ERROR: SRG GUI override survived RFG source view: '+name)
    for m in ('initGui','actionPerformed','drawScreen'):
        if not re.search(rf'\boverride\s+fun\s+{m}\b',s): raise SystemExit(f'ERROR: MCP GUI override missing {name}:{m}')
    if 'super.func_' in s: raise SystemExit('ERROR: direct SRG super-call survived RFG source view: '+name)
print(f'[PASS] RFG workspace source contract: {len(files)} Kotlin production files')
PY
bash -n tools/verification/1.12.2/rfg/bootstrap-wsl.sh
echo '[PASS] pinned RetroFuturaGradle 1.12.2 workspace contract'
