# Minecraft 1.12.2 integration

## Runtime dependencies

Target: Minecraft 1.12.2 + Forge 14.23.5.2864 + MixinBooter 11.15 + Forgelin-Continuous 2.4.0.0 for the pinned release profile. The production dependency accepts `forgelin_continuous >= 2.4.0.0`; newer runtime JARs may be audited with `tools/verification/1.12.2/scripts/dev-real-forgelin-contract.sh`, but they do not change the exact Kotlin 2.4.0 release-compiler requirement. Do not install the original Shadowfacts Forgelin alongside Forgelin-Continuous. GPU compute is optional. OpenCL uses LWJGL2 plus the system OpenCL ICD. CUDA uses the NVIDIA Driver API via the JNA 4.4 already shipped on the Minecraft 1.12.2 launcher classpath and requires NVRTC only when CUDA kernels are actually compiled.

## World/thread boundary

The 1.12.2 integration is now checked at three complementary levels. The deterministic legacy harness supplies narrow stubs so lifecycle, Mixin retention, Paulscode/OpenAL behavior and SRG-only GUI regressions are reproducible without external downloads. When the official client/MCPConfig/Forge artifacts are supplied, the real-SRG source gate remaps the actual 1.12.2 binaries, compiles the Forge/Mixin-facing Kotlin layer against them and JVM-verifies every resulting class without Minecraft/Forge stubs. A separate bytecode gate then scans the actual deterministic production classes and resolves their direct Minecraft/Forge Methodref/Fieldref instructions against those official SRG binaries. The registry enumerator specifically relies on the real `Block.REGISTRY` `Iterable` contract (with a generic `iterator()` fallback for wrappers); it never aliases SRG `func_82594_a`, which is `RegistrySimple#getObject(K)` rather than a values-collection method. A standalone `tools/verification/1.12.2/rfg/` workspace is prepared as the final MCP→SRG reobfuscation layer, but its networked build/client boot remains a distinct external gate rather than something inferred from the lower-level checks.

Minecraft world access stays on the client thread. `ReflectiveWorldAccess.sample()` retrieves one block state and derives registry/material/sound metadata from it using a reusable mutable position and pre-resolved hot-path reflection handles. Within each capture it caches `Chunk` instances and reads block state directly from the chunk when that SRG/MCP method is available, falling back to `World.getBlockState` if necessary. `minecraft:air` and full cubes bypass expensive collision enumeration. RC15 asks only actual partial blocks/states for their collision boxes at that world position (`addCollisionBoxToList`/collision-bounding-box fallback), converts them to portable local AABBs and stores them in `AcousticShape`. This preserves neighbour-dependent shapes for connected fences, panes/bars, stairs and compatible modded blocks instead of replacing every solid state with a 1m cube. `LegacySceneCapture` publishes the resulting immutable voxel+shape snapshot and reuses overlap between captures. A new world uses a center-out progressive bootstrap bounded by shader option `CAPTURE_BUDGET_MS`; once complete, normal rolling refresh and periodic validation use the same exact geometry.

Room analysis is coalesced off-thread. A single analysis coordinator partitions independent room rays across a bounded `acoustic-physics-worker-*` pool. Stale epochs are ignored. The Paulscode/OpenAL thread never touches the live world.


## RetroFuturaGradle integration gate

`tools/verification/1.12.2/rfg/` is intentionally separate from the deterministic custom release builder. It pins RetroFuturaGradle 1.4.9 and Gradle 8.14.3, keeps RFG's verified Minecraft-1.12.2 decompile/tooling path on Forge 14.23.5.2847 because that legacy userdev artifact exists, upgrades only the runtime `forgeUniversal` dependency to the project's exact Forge 14.23.5.2864 target, compiles Acoustic Shaders with the official Kotlin 2.4.0 CLI at JVM target 8, and declares MixinBooter 11.15 plus Forgelin-Continuous 2.4.0.0. The RFG output verification rejects non-Java-8 classes, a shaded Kotlin runtime, missing Mixin manifest metadata, or any resolved runtime universal other than 2864. The pinned RFG checkout itself must remain clean; patching its tooling default to 2864 would make it request a non-existent `14.23.5.2864-userdev.jar`.

The deterministic release sources deliberately ship both MCP-compatible GUI methods and explicit SRG aliases because that JAR is produced without ForgeGradle reobfuscation. RFG instead compiles in MCP space and owns the MCP→SRG transformation. `tools/verification/1.12.2/scripts/dev-verification-tool.sh prepare-rfg-source` therefore creates a temporary RFG-only source view that removes exactly the 12 explicit SRG GUI aliases and rewrites five explicit SRG superclass calls back to their MCP equivalents. It does not edit the authoritative production tree or reflection aliases. `tools/verification/1.12.2/scripts/dev-rfg-workspace-contract.sh` verifies this transformation locally. A full `rfgReleaseGate`/`runObfClient` result is not claimed until the Gradle/RFG dependency graph is actually available and executed.

Projectile `MovingSound` inherited state is handled differently from those dual-name methods. The deterministic JAR must run directly in an SRG client, so `repeat`, repeat delay, attenuation, done-playing, position, volume and pitch are written through the cached `ForgeReflection.setField` bridge with both MCP and SRG names instead of emitting direct MCP field references. A `javap -v` release gate rejects MCP-only inherited `Fieldref` entries in `ProjectileFlightSound`; the independent real-SRG compile now consumes the same production source rather than rewriting those fields only in its temporary view.

## CUDA and OpenCL

At pre-init the frontend registers `CudaFdtdBackend`, `CudaGeometricBackend`, `OpenClFdtdBackend` and `OpenClGeometricBackend` independently. None is a hard launch dependency. Probe failures are logged and the runtime continues with the remaining accelerator/CPU paths.

The CUDA implementation calls the NVIDIA CUDA Driver API (`nvcuda.dll`) dynamically through JNA and compiles the shipped CUDA C kernels with NVRTC to PTX. RC15 preloads and pins `nvrtc-builtins64_*.dll` by absolute path before loading NVRTC, fixing the RC13 real-client failure where NVRTC itself could start but could not locate its builtins companion DLL. The mod does not bundle `cudart` or NVIDIA proprietary DLLs. The Windows ALL-IN-ONE harness can, only on a detected NVIDIA machine, best-effort obtain the pinned official NVIDIA NVRTC 12.2.128 wheel, verify its SHA-256 and place the compiler DLLs under `config/acousticshaders/cuda/`; an installed CUDA Toolkit is preferred when already present. A failed download/compiler load simply leaves CUDA unavailable.

`AUTO` is explicit runtime policy: **CUDA (priority 200) -> OpenCL (priority 100) -> CPU**, filtered by `supports()` and per-workload `preferredForAuto()`. Small jobs may intentionally remain on CPU. `COMPUTE_BACKEND` independently selects the FDTD backend; `RAY_COMPUTE_BACKEND` selects batched listener-centric reflection rays. Explicit `CUDA` and `OPENCL` values are available for diagnostics but still fail safely to CPU if the requested backend cannot produce a validated result.

On first device execution each FDTD backend compares a miniature device wave solve against the CPU reference; each ray backend validates representative first-hit distance/material energy against portable CPU DDA. A disagreement disables only that backend for the current process.

## Generated material database and ordinary resource packs

At Forge post-init the runtime ensures `.minecraft/resourcepacks/Acoustic Shaders Default Materials` exists. RC15 supplies a dedicated icon and also adds the pack to Minecraft's selected Resource Packs list so the vanilla UI reflects its active status. Selection migration is three-way: persisted `options.txt`, live `GameSettings.resourcePacks`, and live `ResourcePackRepository` entries are reconciled; the old `AcousticShaders-Generated-Materials` entry is removed and the ResourceManager is refreshed when needed. Its generated material JSON is always loaded by Acoustic Shaders as the lowest-priority database regardless, so vanilla UI state cannot accidentally remove the safety base layer. A schema/mod-set fingerprint avoids unnecessary regeneration. When regeneration is required, Forge block states are enumerated once, Material/SoundType/OreDictionary and structural hints are precomputed/cached, and the JSON is replaced atomically.

Active ordinary resource packs may overlay `assets/<namespace>/acoustic_materials/**/*.json`; the resource-pack list and material file metadata are polled for safe hot reload. This gives modpacks and texture/resource packs a version-native override mechanism without coupling block data to the Reference Acoustic Shader.

Active resource packs may also overlay `assets/<namespace>/acoustic_media/**/*.json`. Volume media are independent from surface materials: they can override the inferred AIR/WATER/LAVA medium for a vanilla or modded block/state with explicit density, sound speed and eight-band bulk attenuation. Directory/ZIP media entries participate in the same transactional fingerprint/hot-reload path. The Forge block introspector also publishes a separate fluid `mediumShape`; vanilla flowing-liquid metadata and reflective modded-fluid `getFilledPercentage(world,pos)` are used when available so a liquid need not occupy the entire voxel. After capture, connected same-medium cells are reconciled only from the immutable snapshot: vertical columns do not gain artificial air gaps, and simple neighboring fill heights produce a bilinear free surface for ray/refraction/reflection and FDTD sampling.

The same always-present generated resource pack also contains `assets/acousticshaders/acoustic_sources/generated.json`. Active resource packs may overlay `assets/<namespace>/acoustic_sources/**/*.json`. Source profiles classify emitters/events separately from surfaces and can adjust spectral propagation metadata, occlusion/diffraction/early/late weighting, perceptual source priority, moving-source refresh sensitivity and optional OpenAL Doppler. Important modded sounds should use explicit resource-pack rules rather than relying only on fallback filename heuristics.

## Walls, openings and partial blocks

Direct sound is not binary visibility. Along the source-to-listener segment the runtime measures the exact occupied material thickness intersected in every voxel and applies eight-band absorption/transmission accordingly. A thick stone wall therefore leaves a strongly filtered but non-zero transmitted component when the material profile permits it. Diffraction/alternate propagation can contribute additional energy around corners, doors and openings.

For partial geometry, the same exact AABB representation is consumed by portable CPU ray casting and by the OpenCL/CUDA ray backends. A ray can pass through the empty half of a slab or through a gap not occupied by fence/pane geometry; a thin pane still attenuates the portion actually crossed. Multipart/overlapping AABBs are unioned for transmission thickness so the same physical interval is not charged twice.

The FDTD path uses a conservative coarse-grid occupancy threshold (75%) rather than voxelizing a thin pane into a complete low-frequency wall. This is deliberately different from the exact geometric solver because the FDTD cell spacing cannot honestly resolve every Minecraft collision detail.

## Mixin scope and live audio

The shipped Mixins target Paulscode/LWJGL audio classes rather than Minecraft rendering or `SoundManager`: source play/movement/lifecycle plus the Paulscode command queue. Source play gets an immediate direct/room EFX fallback. Full selected-source shader results are computed only on analysis/physics workers, queued with generation/scene epochs, and applied to OpenAL only when the Paulscode command thread drains them.

The Minecraft 1.12.2 integration keeps two related pipelines. The fast EFX pipeline is deliberately truncated at `standard.hybrid`; `standard.environment_rays` is split into a listener-invariant stage computed once per scene snapshot and reused by source-specific work. The bounded full-source pipeline continues through `standard.impulse_response` and `standard.foa`. If optional software wet output is enabled, safe static mono PCM sources can consume that FOA RIR through worker-side partitioned convolution and a synchronized stereo OpenAL wet voice. The feature defaults OFF until real-client validation; streaming/looping or unsupported buffers stay on EFX.

## Shaderpack location/UI

```text
config/acousticshaders/runtime.properties
config/acousticshaders/shaderpacks/AcousticShaders-Reference-Hybrid.zip
```

The filename remains `Reference-Hybrid` for upgrade compatibility, but its display name is **Reference Acoustic Shader**. Deprecated first-party Performance/Cinematic packs are removed by the RC15 installer because their purpose is now covered by shader-local presets.
A fresh configuration selects Reference automatically. The selector intentionally allows an empty Active Stack, shown as `None (vanilla audio)`; saving that choice persists it and the runtime does not silently reinstall Reference into the active stack. The built-in ZIP itself remains available so the user can re-enable it later or replace it with another pack.

UI: `Options -> Music & Sounds -> Acoustic Shaders...` or `Mods -> Acoustic Shaders -> Config`. The selector retains stacking for future third-party base/overlay packs. Preset selection moved into `Shader Options...`.

## Compatibility test order

1. Forge + MixinBooter + Forgelin-Continuous 2.4.0.0 + Acoustic Shaders.
2. Add OptiFine.
3. Add LoliASM/fork.
4. Add both.
5. Add the rest of the target modpack.

## Launcher harness

The Microsoft Store Launcher has no reliable supported command that means “play this exact Forge profile”. The Windows harness opens it and uses UI Automation only when the accessibility tree proves the detected Forge 1.12.2 selection. Otherwise it requests one manual Play click; it never blindly clicks coordinates or extracts authentication tokens.
