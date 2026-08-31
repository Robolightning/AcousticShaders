# Acoustic Shader Specification 0.3 (release-candidate draft)

Status: **experimental**. This specifies the portable data contract, not a particular Forge/OpenAL/GPU implementation.

## 1. Data model

Acoustic Shaders deliberately separates three authoring layers:

1. an **Acoustic Shader Pack** describes algorithms, pass ordering, presets and user-visible quality controls;
2. ordinary Minecraft **Resource Packs** may describe surfaces under `acoustic_materials/`, volume propagation media under `acoustic_media/`, and sound emitters/events under `acoustic_sources/`;
3. trusted runtime/extension modules implement executable CPU/GPU algorithms and platform integration.

An Acoustic Shader Pack is a directory or ZIP containing a declarative acoustic graph. Required root files are `manifest.json` and `pipeline.json`; `acoustic.properties` is optional, although the reference conformance profile expects at least one preset.

Runtimes may continue accepting legacy shader-local `materials/*.json` for compatibility, but new content SHOULD keep physical surface/medium/source data in ordinary Resource Packs so one algorithmic shader can be combined with different modpacks and databases.

Packs cannot execute arbitrary JVM/native code. Trusted runtime/extension modules provide algorithms and compute backends.

A fresh 1.12.2 installation selects the bundled **Reference Acoustic Shader** by default. A user MAY explicitly select an empty Acoustic Shader stack to request vanilla/no Acoustic Shader processing, or replace the Reference pack with a third-party pack. Once explicitly saved, an empty stack MUST NOT be silently repopulated by the runtime.

## 2. Manifest/capabilities

```json
{
  "format": 1,
  "spec": "0.3",
  "id": "author:pack",
  "name": "Pack Name",
  "requires": ["ray_query"],
  "optional": ["parallel_cpu", "gpu_compute", "wave_field", "hrtf"]
}
```

`requires` is hard. `optional` advertises an acceleration/feature path that must have a fallback when absent.

## 3. Pipeline and standard source behavior

Reference pass IDs include `standard.source_behavior`, `standard.direct_path`, `standard.diffraction`, `standard.environment_rays`, `standard.early_reflections`, `standard.late_reverb`, `standard.wave_low_frequency`, `standard.hybrid`, `standard.impulse_response` and `standard.foa`. `standard.foa` reads `rir.mono` plus optional early-reflection directionality and publishes first-order Ambisonics in ACN channel order `W,Y,Z,X` with SN3D normalization as `rir.foa.acn_sn3d`.

Typed reads/writes define the DAG. Optional reads create dependencies only if their producer is active. Shader packs do not control worker threads.

The runtime may publish the portable resources:

- `source.id` — normalized platform source/resource identifier when available;
- `source.profile` — physical source metadata resolved from Resource Packs;
- `source.gain` — platform-reported source gain metadata;
- `source.velocity` — platform-reported source velocity in world units per second when available, otherwise a zero vector;
- `listener.forward` / `listener.up` — normalized listener orientation basis supplied by the platform adapter;
- `listener.velocity` — platform-reported listener velocity in world units per second when available, otherwise a zero vector;
- `source.behavior` — shader-produced effective source behavior after shader-local strength/toggles are applied.

`standard.source_behavior` reads `source.profile` optionally and writes `source.behavior`. A third-party shader may omit this stage, replace it through a trusted extension pass, or consume `source.profile` from another stage. Source profile data MUST remain data-only and cannot execute code. Gain/velocity/orientation resources are metadata inputs; the Reference RC19 propagation graph does not implicitly multiply gain or apply an additional Doppler transform merely because those resources are present.

## 4. Shader-local presets and user overrides

`profile.<NAME>` entries define presets. `profile.order` (or legacy alias `quality.order`) defines UI order; unlisted profiles follow deterministically.

```properties
profile.order=POTATO LOW MEDIUM HIGH ULTRA MAXIMUM
profile.POTATO=RAYS:64 BOUNCES:2 WAVE:OFF
profile.HIGH=RAYS:768 BOUNCES:6 WAVE:MODAL
profile.MAXIMUM=RAYS:8192 BOUNCES:16 WAVE:FDTD COMPUTE_BACKEND:AUTO
```

The runtime resolves defaults -> selected preset -> validated user overrides. Presets are pack data, not global runtime quality names; a third-party pack may define different names.

Simple enabled expressions include `${KEY == VALUE}` and `${KEY != VALUE}`.

The Reference pack uses:

- `WAVE:OFF` — no wave pass;
- `WAVE:MODAL` — lightweight modal approximation;
- `WAVE:FDTD` — bounded 3-D FDTD resource producer.

The Reference source-behavior controls are `SOURCE_PROFILES=ON/OFF`, `SOURCE_PROFILE_STRENGTH=0..1` and `SOURCE_DOPPLER=ON/OFF`. These are Reference conventions rather than mandatory keys for every third-party shader.

`COMPUTE_BACKEND` is the Reference FDTD backend option; `RAY_COMPUTE_BACKEND` independently selects the geometric-ray backend. They are pack options, not guarantees that every runtime ships every value. RC15 Reference conventions are FDTD `AUTO/CUDA/OPENCL/CPU_PARALLEL/CPU_SCALAR` and rays `AUTO/CUDA/OPENCL/CPU_PARALLEL`. The stable ABI remains the output resource: runtimes may add further native/SIMD/device implementations without changing pack resources. `AUTO` is runtime policy, not a pack guarantee; the 1.12.2 runtime ranks CUDA above OpenCL when both are available and still retains CPU fallback.

## 5. Acoustic Material Resource Packs

Reference acoustics use eight octave bands, per-band absorption, scattering and transmission. Reflection is derived from a bounded energy budget rather than authored as an independent energy source.

The preferred material database is an ordinary Minecraft resource pack containing one or more:

```text
assets/<namespace>/acoustic_materials/**/*.json
```

Each JSON may define `materials` and ordered `rules`. Standard rule kinds are `STATE_ID`, `REGISTRY_ID`, `TAG`, `DICTIONARY_EXACT` and `DICTIONARY_PREFIX`. Platform-specific state IDs, registry IDs, OreDictionary aliases or modern block tags are adapter data; the portable acoustic material representation remains platform-neutral.

A runtime MAY maintain a generated platform-specific base database. The 1.12.2 runtime always maintains `Acoustic Shaders Default Materials` as the lowest resource-data layer and allows active ordinary resource packs to override it. Generated data MUST be replaceable/cacheable without mutating user packs. User/resource-pack rules MUST retain deterministic precedence over generated inference.

Portable semantic families currently include stone, concrete, brick, ceramic, plaster, wood, metal, glass, fabric, carpet, polymer, rubber, soil, sand, foliage, liquid and air. Runtimes may infer these from platform metadata, but inferred coefficients are estimates unless a material pack supplies curated/measured data.

### 5.1 Surface material versus scene geometry

`AcousticMaterial` describes frequency-dependent surface interaction; it MUST NOT imply that the containing Minecraft block occupies a full cube. Platform adapters may publish exact/multipart cell geometry independently. The 1.12.2 reference adapter represents collision geometry as zero or more local AABBs per voxel. Geometric/direct solvers SHOULD intersect that geometry rather than a binary solid-cell approximation. Direct transmission SHOULD scale material loss by actually traversed occupied thickness and MUST avoid double-counting overlapping multipart boxes.

Optional accelerator backends that claim equivalent geometric-ray behavior MUST consume an equivalent scene shape representation or demonstrate equivalence against the CPU reference. Coarse numerical wave solvers MAY simplify sub-grid geometry when their discretization cannot resolve it, but SHOULD prefer a conservative open/porous treatment over turning a thin object into an unjustified full-cell low-frequency wall.

### 5.2 Volume propagation media

Volume propagation media are distinct from surface materials. A medium describes the fluid/gas through which pressure waves travel; it does not describe a wall coating or imply solid collision geometry. Ordinary Resource Packs may provide:

```text
assets/<namespace>/acoustic_media/**/*.json
```

Shader packs MAY additionally contain declarative `media/*.json` overlays. A medium definition contains `density_kg_m3`, `speed_m_s`, and exactly one eight-band bulk-loss representation: `absorption_nepers_per_meter` or `attenuation_db_per_km`. Rules use the material descriptor identity surface and may match `STATE_ID`, `REGISTRY_ID`, `GLOB`, `TAG`, `DICTIONARY_EXACT`, or `DICTIONARY_PREFIX`. Higher-priority rules win deterministically.

Platform adapters SHOULD provide deterministic inferred media when no rule matches. The 1.12.2 adapter supplies AIR plus typed WATER/LAVA fallbacks and permits Resource Pack/shader-pack rules to override a vanilla or modded block/state with a custom medium such as oil or another fluid. Unknown fluids may retain a conservative platform fallback rather than fabricating measured coefficients.

A scene cell MAY expose medium geometry independently from solid collision geometry. The portable 1.12.2 scene uses `mediumShape` so a flowing or modded fluid can occupy only part of a Minecraft voxel. Geometric propagation SHOULD split path length at the actual medium boundary; interface delay, bulk attenuation, impedance reflection/transmission and Snell/Fermat refraction then use the media on the two sides. Numerical wave solvers SHOULD sample the medium at their own subcell positions when the discretization permits it. For simple bottom-up connected fluids, runtimes MAY reconstruct a neighbor-aware continuous free surface from immutable scene data; the 1.12.2 reference runtime uses a bilinear patch, analytical geometric-ray intersection and the same point-sampling function for FDTD. Unusual multipart modded-fluid shapes retain their explicit medium geometry instead of being forced through this reconstruction.

Optional GPU backends that do not implement heterogeneous media MUST refuse such workloads and use an equivalent CPU fallback rather than silently treating every cell as one homogeneous medium.

## 6. Acoustic Source Profiles in Resource Packs

Physical sound-source metadata is authored independently of surface materials. An ordinary active Resource Pack may contain:

```text
assets/<namespace>/acoustic_sources/**/*.json
```

A source JSON defines named `profiles` and ordered `rules`. Standard matching kinds are `EXACT`, `PREFIX`, `CONTAINS` and `GLOB`. The normalized source identifier is platform data; Minecraft 1.12.2 prefers the Paulscode filename/resource path and falls back to the source name.

A profile has the following portable semantics:

- `category` — stable semantic category/debug label;
- `emission[8]` — relative spectral energy in the same 125..16000 Hz bands used by materials;
- `direct` — direct-path contribution multiplier;
- `occlusion` — relative obstruction/occlusion strength;
- `diffraction` — diffracted-path contribution multiplier;
- `early_reflections` — early-reflection energy multiplier;
- `late_reverb` — late-field/reverb energy multiplier;
- `priority` — perceptual scheduler-importance multiplier;
- `movement_sensitivity` — how aggressively a moving source is re-evaluated;
- `doppler` — platform Doppler/source-velocity scale when supported;
- `transient` — temporal-importance hint;
- `bypass` — requests world-acoustic bypass for non-spatial/UI/music-style sources.

Relative values are bounded by the portable profile parser. Source profiles are descriptive metadata: they do not replace the underlying audio asset and cannot create arbitrary executable behavior.

The 1.12.2 generated base resource pack includes conservative fallback categories `generic`, `explosion`, `projectile`, `impact`, `footstep`, `machine`, `weather` and `nonspatial`. Active user/resource packs are layered above generated rules and therefore can correct mod-specific sounds without modifying an Acoustic Shader Pack.

A runtime SHOULD hot-reload valid data changes transactionally and retain the last working resolver if replacement content is invalid. The 1.12.2 runtime fingerprints `acoustic_materials`, `acoustic_media`, and `acoustic_sources` files in active directory resource packs.

Source profiles operate on sound emitters that actually exist. A runtime is not required to synthesize a continuous projectile sound for an otherwise silent entity; procedural/entity-derived emitters, when implemented, are a separate platform/extension capability.

## 7. Frequency-aware wave/ray hybridization

`standard.wave_low_frequency` may publish a `WaveFieldResult` with a conservative `maxTrustedFrequencyHz`. A numerical wave backend MUST NOT claim a trusted ceiling above what its spatial/time discretization can physically resolve. The reference FDTD path derives its ceiling from cell spacing and timestep.

`standard.hybrid` accepts shader-local mode/crossover controls. Reference values are:

- `HYBRID_MODE=AUTO/RAY_WAVE/RAY_ONLY/WAVE_ONLY`;
- `HYBRID_CROSSOVER_HZ=AUTO` or an explicit supported frequency;
- `HYBRID_CROSSFADE_OCTAVES` for transition width.

AUTO chooses a conservative crossover and explicit crossover values are clamped below the wave solver's trusted ceiling. For time-domain wave data, the reference RIR path uses complementary low-frequency wave and high-frequency geometric components so the same band is not simply added twice. Modal data contributes only within the low-frequency hybrid region.

The exact crossover filter implementation is runtime/reference-algorithm behavior rather than a promise that every future solver uses identical filters; the required semantic is that solver validity bounds are respected and double-counting is avoided.

## 8. Execution boundary

The runtime owns scheduling, pools, cancellation and compute-device selection. A pass may implement optional acceleration only if it publishes the same declared typed resources as its CPU path and can safely fall back when that accelerator is optional. Live platform state must be captured into immutable data before worker/audio use. Audio consumers must not perform unsafe live-world reads. Runtimes may memoize passes whose declared inputs are listener/scene invariant, provided observable resources remain equivalent.

Reference implementations may expose platform-capture budget options such as `CAPTURE_BUDGET_MS`. Such a budget controls how platform state is incrementally materialized into an immutable scene; it MUST NOT silently reduce the final requested shader capture volume or change the declared acoustic material/shape semantics. A runtime may publish progressively refined snapshots during world warm-up as long as incomplete cells are handled deterministically and later revisions converge to the same complete scene that a full capture would have produced.

Source-profile scheduler hints may influence which source is solved first, but MUST NOT allow a data pack to create unbounded work. Runtime hard budgets remain authoritative.

Portable high-fidelity output is layered: `HybridResponse`, mono RIR (`rir.mono`) and, when requested by the pipeline, FOA ACN/SN3D (`rir.foa.acn_sn3d`). Standard FOA output also carries the exact direct-arrival sample index derived from the hybrid direct path so a wet renderer can remove the duplicated dry arrival after resampling without discarding a stronger later reflection. A platform may project these to EFX, software/hardware convolution, HRTF or another backend. Audio-thread implementations MUST NOT perform propagation solving, RIR synthesis, resampling or FFT convolution on the real-time/command thread; those operations belong on bounded worker resources. A runtime that falls back from a richer output path MUST preserve direct-path processing and must reject stale source-generation/physics-epoch results rather than attaching them to a recycled platform source identifier.

## 9. FDTD/acceleration

The core supplies deterministic scalar and runtime-parallel bounded FDTD. Optional external implementations satisfy the same FDTD problem/result contract. GPU acceleration must be optional unless `gpu_compute` is a hard manifest requirement. An implementation should provide a safe CPU fallback for `AUTO`-style selection.

## 10. Resource layering and generated defaults

For Minecraft 1.12.2 the always-present generated resource pack contains both:

```text
assets/acousticshaders/acoustic_materials/generated.json
assets/acousticshaders/acoustic_sources/generated.json
```

It is always an Acoustic Shaders base layer. The 1.12.2 reference runtime also keeps it selected in the vanilla Resource Packs screen so visible UI matches runtime state, but acoustic availability does not depend on that UI selection. Its contents are regenerated/cache-invalidated from runtime schema plus installed-mod/platform fingerprints. It is not a user-editable authority: active ordinary Resource Packs override it.

Shader selection is independent of resource-data selection. Removing the Acoustic Shader does not delete generated/user material/source databases; those databases remain available for a later shader, while an explicit empty shader stack produces vanilla/no Acoustic Shader processing.

## 11. Security

ZIP traversal/resource abuse must be rejected. Pack data cannot embed executable classes as shader algorithms. Resource-data JSON is parsed with bounded/strict loaders and must not gain filesystem access outside the selected pack.

## 12. Conformance

A reference-conforming pack parses strictly, satisfies required capabilities, defines at least one preset, compiles every preset to a valid DAG, executes the deterministic scene and produces a non-empty RIR when enabled. The repository conformance tool emits WAV, timings and pipeline DOT.

A runtime/resource-loader regression SHOULD include material overlay precedence, source-profile overlay precedence, transactional hot reload, generated-default recreation and explicit empty-shader persistence.

Specification 0.3 is not frozen; compatibility becomes a hard contract at 1.0.
