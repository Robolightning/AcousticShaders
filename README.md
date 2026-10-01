# Acoustic Shaders

**Acoustic Shaders** is an open environmental-acoustics framework for Minecraft. It brings a shader-pack-style authoring model to sound: users select an **Acoustic Shader Pack**, resource packs can describe **acoustic materials, volume propagation media, and sound-source profiles**, and the runtime executes the selected acoustic pipeline on CPU, CUDA, OpenCL, or safe fallbacks.

The first release target is **Minecraft 1.12.2 + Forge**. The acoustic API and core are deliberately Minecraft-independent so newer Forge/Fabric/NeoForge adapters can reuse the same shader specification, material/source data, physics and DSP.

**Author:** Robolightning  
**License:** MIT — use, modify, redistribute, fork or integrate it freely. The software is provided without warranty; see [`LICENSE`](LICENSE).

## What it simulates

The first-party **Reference Acoustic Shader** combines:

- frequency-dependent direct transmission and occlusion;
- finite medium-aware propagation time for eligible positional one-shot sounds;
- exact partial-block collision geometry for slabs, stairs, fences, panes, bars, doors and compatible modded shapes;
- diffraction around obstructing geometry;
- multi-bounce geometric ray tracing with material absorption/scattering/transmission;
- source-aware early reflections and late reverberation;
- low-frequency modal or 3-D FDTD wave simulation;
- frequency-aware wave/ray hybridization with a physically bounded crossover;
- source profiles for explosions, projectiles, impacts, footsteps, machines, weather and other categories;
- FOA Ambisonics (ACN/SN3D), HRTF extension boundaries, RIR/DSP primitives, the fast legacy OpenAL EFX path, and an optional bounded software-wet convolution path for safe static mono Minecraft 1.12.2 sounds.

The Reference pack exposes six presets — `POTATO`, `LOW`, `MEDIUM`, `HIGH`, `ULTRA`, `MAXIMUM` — plus individual stage and quality overrides. `ULTRA` and `MAXIMUM` prioritize physical quality and are intended for powerful hardware; lower presets preserve the same architecture with smaller budgets.

## Compute backends

Acoustic Shaders owns its worker pools and keeps shader authors away from unsafe threading primitives. Independent DAG passes may execute concurrently, partitionable CPU passes use a bounded multicore pool, and GPU work can overlap independent CPU work.

The 1.12.2 runtime supports:

- **CUDA**: NVIDIA Driver API + runtime-compiled CUDA C/PTX via NVRTC for geometric rays and FDTD;
- **OpenCL**: GPU ray and FDTD backends, useful on NVIDIA/AMD/Intel implementations that expose OpenCL;
- **multicore CPU** and scalar CPU fallbacks.

`AUTO` prefers `CUDA -> OpenCL -> multicore CPU -> scalar CPU` when a workload is large enough to justify device launch/transfer overhead. CUDA/OpenCL are optional: backend self-tests compare device results with CPU reference calculations and automatically fall back if validation fails.

The 1.12.2 world snapshot path is also frame-budgeted: a new world is captured progressively from the listener outwards instead of scanning the entire ULTRA/MAXIMUM volume in one client tick. `CAPTURE_BUDGET_MS` controls the per-tick main-thread budget, rolling captures reuse overlap, and chunk-local block-state caching avoids repeated world/chunk-provider lookup without reducing the final acoustic geometry.

## Acoustic materials, volume media, and source data

Algorithms and world data are intentionally separate.

- **Acoustic Shader Packs** define the processing DAG and shader options.
- **Minecraft Resource Packs** may provide `assets/<namespace>/acoustic_materials/*.json`, `assets/<namespace>/acoustic_media/*.json`, and `assets/<namespace>/acoustic_sources/*.json`.
- Surface material and volume medium are independent: a glass wall, an air cell, a half-filled water voxel, and a modded oil volume have different responsibilities. Volume media may define density, sound speed and eight-band bulk attenuation; platform-inferred AIR/WATER/LAVA remain deterministic fallbacks. Connected partial fluids are reconciled at scene level and simple flowing surfaces are neighbor-smoothed into a bilinear acoustic surface without extra live-world sampling.
- The runtime maintains an always-present **Acoustic Shaders Default Materials** resource pack as a lowest-priority generated database. It precomputes unknown/vanilla/modded block-state acoustics from registry IDs, Minecraft Material/SoundType, OreDictionary, structural geometry and conservative physical heuristics. Its cache is invalidated when the installed mod set or inference schema changes.

Active user/resource-pack data overrides the generated database, so modpack authors can correct or specialize materials and sound events without forking an Acoustic Shader Pack.

## Minecraft 1.12.2 installation

Required:

1. Minecraft 1.12.2
2. Forge 14.23.5.2864
3. MixinBooter 11.15
4. Forgelin-Continuous 2.4.0.0 (required Kotlin runtime; do not install the original Forgelin alongside it)
5. Acoustic Shaders JAR in `mods/`

The bundled **Reference Acoustic Shader** is selected automatically on a fresh configuration. The player may explicitly select `None (vanilla audio)` or replace it with another shader pack; an explicit empty stack is preserved.

The mod adds **Acoustic Shaders...** to `Options -> Music & Sounds`. Acoustic material/medium/source resource data follows the normal Minecraft Resource Pack mechanism, with the generated default database kept available automatically.

## Debugging

Normal releases use concise logging. Detailed capture timings, per-source solves, GPU diagnostics, explosion-path thickness/transmission and pass timings are enabled only when:

```properties
debug=true
```

in:

```text
.minecraft/config/acousticshaders/runtime.properties
```

The diagnostic/test harness deliberately enables debug logging when collecting reports. Shader/resource-pack authors can use the same switch while developing.

## Documentation

Start with [`docs/README.md`](docs/README.md). Important references include:

- [`spec/acoustic-shader-spec-0.3.md`](spec/acoustic-shader-spec-0.3.md) — normative shader/resource ABI;
- [`docs/shader-author-guide/getting-started.md`](docs/shader-author-guide/getting-started.md) — authoring Acoustic Shader Packs;
- [`docs/resource-pack-author-guide.md`](docs/resource-pack-author-guide.md) — material/source resource data;
- [`docs/material-database.md`](docs/material-database.md) — generated database, inference and layering;
- [`docs/source-profiles.md`](docs/source-profiles.md) — explosion/projectile/etc. source behavior;
- [`docs/performance.md`](docs/performance.md) — threading, CUDA/OpenCL and budgets;
- [`docs/minecraft-1.12.2-integration.md`](docs/minecraft-1.12.2-integration.md) — Forge/Paulscode/OpenAL integration;
- [`docs/testing.md`](docs/testing.md) — conformance and regression strategy;
- [`docs/extension-author-guide.md`](docs/extension-author-guide.md) — extending the runtime with new passes/backends.

## Development and verification

The portable modules target Java 8. Local verification is intentionally independent of a running Minecraft client and includes deterministic headless physics, shader conformance, Java-8 architecture boundaries, Forge/Mixin/Paulscode contract compilation, SRG-only GUI smoke tests, CUDA/OpenCL kernel checks and packaged-JAR smoke tests.

Useful commands:

```bash
./verify.sh
./tools/verification/1.12.2/scripts/dev-conformance.sh
./benchmark.sh
./build-local-release.sh
```

Real Minecraft and physical hardware/device checks are part of release certification, not substitutes for the deterministic local suite. The tracked 1.12.2 candidate has already passed the dedicated projectile, TNT/liquid, CUDA-FDTD and physical-audio gates; any later production change must rerun the relevant gate before release.

## Compatibility scope

The 1.12.2 implementation is designed to coexist with OptiFine and LoliASM-family/coremod-heavy packs by keeping its Minecraft transformer surface narrow and concentrating audio hooks in Paulscode/Mixin integration. Compatibility must still be verified empirically for each large modpack combination.

Version `0.3.0` is intentionally scoped to **Minecraft 1.12.2 / Forge 14.23.5.2864**. The portable API keeps a version-neutral `PlatformFrameSnapshot` / `PlatformFrameAdapter` boundary so future adapters can reuse the same acoustic core, but newer Minecraft versions are explicitly out of scope for this release and are not claimed compatible.

Detailed 1.12.2 Forge/SRG/WinLab verification procedures live in [`docs/testing.md`](docs/testing.md) and [`docs/minecraft-1.12.2-integration.md`](docs/minecraft-1.12.2-integration.md).
