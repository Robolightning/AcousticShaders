#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
for f in dev-forge1122-client-preflight.sh dev-forge1122-client-launch.sh dev-vanilla1122-winlab-client.sh dev-winlab-real-minecraft-sound-event.sh dev-winlab-real-minecraft-world-sound-event.sh dev-winlab-real-minecraft-gameplay-sound-event.sh dev-winlab-real-minecraft-projectile-gameplay.sh dev-winlab-real-minecraft-liquid-tnt-gameplay.sh dev-winlab-real-cuda-fdtd-hardware.sh; do
  bash -n "$f"
done
python3 - <<'PY'
import pathlib,re
for name in ('dev-forge1122-client-preflight.sh','dev-forge1122-client-launch.sh','dev-vanilla1122-winlab-client.sh','dev-winlab-real-minecraft-sound-event.sh','dev-winlab-real-minecraft-world-sound-event.sh','dev-winlab-real-minecraft-gameplay-sound-event.sh','dev-winlab-real-minecraft-projectile-gameplay.sh','dev-winlab-real-minecraft-liquid-tnt-gameplay.sh'):
    s=pathlib.Path(name).read_text()
    blocks=re.findall(r"<<'PY'\n(.*?)\nPY(?:\n|$)",s,re.S)
    if not blocks:
        raise SystemExit(f'ERROR: no embedded Python blocks found in {name}')
    for i,b in enumerate(blocks,1):
        compile(b,f'{name}:embedded-python-{i}','exec')
launch=pathlib.Path('dev-forge1122-client-launch.sh').read_text()
for token in (
    'ACOUSTIC_CLIENT_BOOT_LEVEL', 'ACOUSTIC_CLIENT_NULL_AUDIO', 'ACOUSTIC_CLIENT_REUSE_GAME', 'ACOUSTIC_CLIENT_MIXIN_EXPORT',
    'ACOUSTIC_CLIENT_SOUND_EVENT_PROBE_JAR', 'ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE', 'ACOUSTIC_CLIENT_EXPECTED_MOD_COUNT', 'ACOUSTIC_CLIENT_PROBE_SUCCESS_MARKER',
    'ACOUSTIC_CLIENT_PROFILE', 'ACOUSTIC_CLIENT_COMPUTE_BACKEND', 'ACOUSTIC_CLIENT_RAY_COMPUTE_BACKEND', 'ACOUSTIC_CLIENT_REQUIRE_CUDA_FDTD',
    'option.COMPUTE_BACKEND=%s', 'option.RAY_COMPUTE_BACKEND=%s', 'acousticshaders.probe.requireCudaFdtd=true',
    'Xvfb', 'MinecraftForge v14.23.5.2864 Initialized',
    'expected_mod_count', 'probe_success_marker', 'ACOUSTIC-REAL-MINECRAFT-SOUND-EVENT-OK',
    'default acoustic database (?:rebuilt|cache hit)', '[AcousticShaders] initialized for Minecraft 1.12.2',
    'OpenAL initialized.', 'ALSOFT_DRIVERS', 'mixin.debug.export=true', 'SourceLWJGLOpenAL.transformed.javap.txt', 'LegacySoundHook.${hook}', 'start_new_session=True', 'time.monotonic()', 'SUPERVISOR_WALL_TIMEOUT', 'timeout --signal=TERM --kill-after=2s', 'CLIENT_PID_FILE', 'cwd=str(game_dir)', 'kill -TERM -- \"-$CLIENT_SESSION_PID\"'):
    if token not in launch:
        raise SystemExit(f'ERROR: Forge client launcher contract missing {token!r}')
for token in ('subprocess.Popen(', 'pid_path.write_text(str(proc.pid)'):
    if token not in launch:
        raise SystemExit(f'ERROR: Python client supervisor contract missing {token!r}')

probe=pathlib.Path('tools/verification/1.12.2/dev-tools/RealMinecraftSoundEventProbeMod.java').read_text()
for token in (
    'required-after:acousticshaders', 'clientSideOnly = true', 'block.note.harp', 'AttenuationType',
    'func_147682_a', 'func_147683_b', 'ACOUSTIC-REAL-MINECRAFT-SOUND-EVENT-OK',
    'LegacySoundHook', 'activeSources', 'setPosition'):
    if token not in probe:
        raise SystemExit(f'ERROR: real Minecraft sound-event probe contract missing {token!r}')
post=pathlib.Path('dev-winlab-real-minecraft-sound-event.sh').read_text()
for token in ('--release 8', '-Werror', 'ACOUSTIC_CLIENT_EXPECTED_MOD_COUNT=9',
              'ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE=1', 'ACOUSTIC_CLIENT_MIXIN_EXPORT=1',
              'ACOUSTIC-REAL-MINECRAFT-SOUND-EVENT-OK'):
    if token not in post:
        raise SystemExit(f'ERROR: post-package real Minecraft sound-event gate missing {token!r}')

world_probe=pathlib.Path('tools/verification/1.12.2/dev-tools/RealMinecraftWorldSoundEventProbeMod.java').read_text()
for token in (
    'required-after:acousticshaders', 'clientSideOnly = true', 'func_71371_a', 'func_71401_C',
    'func_152344_a', 'func_184148_a', 'field_73010_i', 'Server thread', 'block.note.harp',
    'LegacySoundHook', 'activeSources', 'ACOUSTIC-REAL-MINECRAFT-WORLD-SOUND-EVENT-OK'):
    if token not in world_probe:
        raise SystemExit(f'ERROR: integrated-world sound-event probe contract missing {token!r}')
world_post=pathlib.Path('dev-winlab-real-minecraft-world-sound-event.sh').read_text()
for token in ('--release 8', '-Werror', 'ACOUSTIC_CLIENT_EXPECTED_MOD_COUNT=9',
              'ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE=1', 'ACOUSTIC_CLIENT_MIXIN_EXPORT=1',
              'ACOUSTIC_CLIENT_BOOT_TIMEOUT', 'ACOUSTIC-REAL-MINECRAFT-WORLD-SOUND-EVENT-OK',
              'Starting integrated minecraft server version 1.12.2', 'joined the game'):
    if token not in world_post:
        raise SystemExit(f'ERROR: post-package integrated-world sound-event gate missing {token!r}')


gameplay_probe=pathlib.Path('tools/verification/1.12.2/dev-tools/RealMinecraftGameplaySoundEventProbeMod.java').read_text()
for token in (
    'required-after:acousticshaders', 'clientSideOnly = true', 'EntityTNTPrimed', 'func_184534_a(2)',
    'func_72838_d(tnt)', 'Server thread', 'explode', 'LegacySoundHook', 'activeSources',
    'ACOUSTIC-REAL-MINECRAFT-GAMEPLAY-SOUND-EVENT-OK', 'vanillaTntEntity=1', 'vanillaExplosion=1',
    'acousticshaders.probe.requireCudaFdtd', 'FdtdBackendRegistry', 'solveCount', 'failureCount',
    'self-test=pass', 'ACOUSTIC-REAL-CUDA-FDTD-OK'):
    if token not in gameplay_probe:
        raise SystemExit(f'ERROR: vanilla gameplay sound-event probe contract missing {token!r}')
gameplay_post=pathlib.Path('dev-winlab-real-minecraft-gameplay-sound-event.sh').read_text()
for token in ('--release 8', '-Werror', 'ACOUSTIC_CLIENT_EXPECTED_MOD_COUNT=9',
              'ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE=1', 'ACOUSTIC_CLIENT_MIXIN_EXPORT=1',
              'ACOUSTIC_CLIENT_BOOT_TIMEOUT', 'ACOUSTIC-REAL-MINECRAFT-GAMEPLAY-SOUND-EVENT-OK',
              'vanillaTntEntity=1 vanillaExplosion=1 play=1 cleanup=1'):
    if token not in gameplay_post:
        raise SystemExit(f'ERROR: post-package vanilla gameplay sound-event gate missing {token!r}')

projectile_probe=pathlib.Path('tools/verification/1.12.2/dev-tools/RealMinecraftProjectileGameplayProbeMod.java').read_text()
for token in (
    'required-after:acousticshaders', 'clientSideOnly = true', 'EntityTippedArrow', 'projectile.flight',
    'acousticshaders.probe.directPath', 'LegacyDirectPathDiagnostic', 'activeSources',
    'ACOUSTIC-REAL-MINECRAFT-PROJECTILE-GAMEPLAY-OK',
    'vanillaEntityTippedArrow=1 flightSource=1 movement=1 directPath=1 bands=8 cleanup=1 diagnosticCleanup=1'):
    if token not in projectile_probe:
        raise SystemExit(f'ERROR: projectile gameplay probe contract missing {token!r}')
projectile_post=pathlib.Path('dev-winlab-real-minecraft-projectile-gameplay.sh').read_text()
for token in ('--release 8', '-Werror', 'ACOUSTIC_CLIENT_EXPECTED_MOD_COUNT=9',
              'ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE=1', 'ACOUSTIC_CLIENT_PROBE_SUCCESS_MARKER="$SUCCESS"',
              'ACOUSTIC_CLIENT_MIXIN_EXPORT=1', 'ACOUSTIC-REAL-MINECRAFT-PROJECTILE-GAMEPLAY-OK'):
    if token not in projectile_post:
        raise SystemExit(f'ERROR: post-package projectile gameplay gate missing {token!r}')

liquid_probe=pathlib.Path('tools/verification/1.12.2/dev-tools/RealMinecraftLiquidTntGameplayProbeMod.java').read_text()
for token in (
    'required-after:acousticshaders', 'clientSideOnly = true', 'EntityTNTPrimed',
    'acousticshaders.probe.directPath', 'LegacyDirectPathDiagnostic', 'field_150355_j', 'field_150353_l',
    'water-source-air-listener', 'air-source-water-listener', 'water-water', 'air-water-air', 'air-lava-air',
    'ACOUSTIC-REAL-MINECRAFT-LIQUID-TNT-GAMEPLAY-OK', 'scenarios=5'):
    if token not in liquid_probe:
        raise SystemExit(f'ERROR: liquid/TNT gameplay probe contract missing {token!r}')
liquid_post=pathlib.Path('dev-winlab-real-minecraft-liquid-tnt-gameplay.sh').read_text()
for token in ('--release 8', '-Werror', 'ACOUSTIC_CLIENT_EXPECTED_MOD_COUNT=9',
              'ACOUSTIC_CLIENT_REQUIRE_SOUND_EVENT_PROBE=1', 'ACOUSTIC_CLIENT_PROBE_SUCCESS_MARKER="$SUCCESS"',
              'ACOUSTIC_CLIENT_MIXIN_EXPORT=1', 'ACOUSTIC-REAL-MINECRAFT-LIQUID-TNT-GAMEPLAY-OK'):
    if token not in liquid_post:
        raise SystemExit(f'ERROR: post-package liquid/TNT gameplay gate missing {token!r}')

cuda_fdtd=pathlib.Path('dev-winlab-real-cuda-fdtd-hardware.sh').read_text()
for token in ('ACOUSTIC_CLIENT_PROFILE=MAXIMUM', 'ACOUSTIC_CLIENT_COMPUTE_BACKEND=CUDA',
              'ACOUSTIC_CLIENT_RAY_COMPUTE_BACKEND=CPU_PARALLEL', 'ACOUSTIC_CLIENT_REQUIRE_CUDA_FDTD=1',
              'ACOUSTIC-REAL-CUDA-FDTD-OK backend=cuda', 'solves=[1-9][0-9]* failures=0', 'self-test=pass'):
    if token not in cuda_fdtd:
        raise SystemExit(f'ERROR: strict CUDA FDTD hardware gate missing {token!r}')

print('[PASS] Forge/vanilla WinLab client tooling syntax, level, teardown, and audio-marker contract')
PY
