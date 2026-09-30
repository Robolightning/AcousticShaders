# Architecture

Acoustic Shaders separates portable physics from platform/audio adapters.

```text
Acoustic Shader Pack
       |
  profile/options resolver
       |
  typed Pipeline DAG
       |
 runtime scheduler -----------------------------------+
   |                 |                                |
PartitionedPass  RuntimeParallelPass          external accelerators
   |                 |                                |
 ray batches     phase barriers             CUDA / OpenCL rays + FDTD
   \                 |                                /
    +------ bounded runtime-owned CPU workers -------+
                         |
              immutable acoustic scene
                         ^
                         |
        Minecraft client-thread rolling capture
                         |
              platform/audio projection
                         |
                 Paulscode / OpenAL EFX
```

## Portable modules

`acoustic-api` defines types, scenes, resources and pass/parallel contracts. `acoustic-core` implements pack loading, DAG planning, reference physics, FDTD, backend registry, RIR/DSP and scheduling. `acoustic-platform-api` defines platform seams; testkit/tools provide deterministic validation. Portable code cannot import Minecraft/Forge/Mixin/LWJGL.

## Cross-version platform frame contract

`acoustic-platform-api` now defines `PlatformFrameSnapshot` as the stable handoff from a concrete game-version adapter into portable processing. One frame owns one immutable scene, listener snapshot, defensive source list, world-epoch token and frame sequence. Simple/legacy adapters may use the default `AcousticPlatformAdapter.captureFrame()` implementation, which derives ordering from the scene revision; newer adapters should override it so scene/listener/source data are captured under one game tick/world ownership boundary.

`PlatformFrameValidator` rejects non-finite listener/source values and duplicate stable source ids at the boundary. `SoundSourceSnapshot` carries the stable source id, normalized sound id, position, gain, already resolved portable `AcousticSourceProfile` and optional velocity; the historical four-argument Java constructor remains available and defaults to `acoustic:generic` with zero velocity. `ListenerSnapshot` likewise keeps its historical three-argument constructor and defaults listener velocity to zero. `PlatformFrameAdapter` is the minimal modern frontend contract: platform id, capabilities and one atomic `captureFrame()` call. The older `AcousticPlatformAdapter` now extends it and keeps the split `captureScene/captureListener/captureActiveSources` compatibility default for simple/legacy ports. `PlatformAdapterRuntime` consumes the minimal frame-only contract, validates shader-pack capabilities before activation, and publishes source id/profile/gain/velocity plus the normalized listener forward/up basis and listener velocity into the typed shader DAG (`source.id`, `source.profile`, `source.gain`, `source.velocity`, `listener.forward`, `listener.up`, `listener.velocity`). It executes all sources in one captured frame against the same scene/listener state, rejects sequence regression inside one world epoch, and permits sequence restart only when the adapter reports a different world epoch. Gain/motion/orientation are metadata resources for shader/extension passes; standard propagation does not multiply source gain or apply an extra Doppler transform merely because those metadata resources exist. This is the version-neutral seam intended for future Forge/Fabric/NeoForge frontends; it does not make any newer Minecraft version compatible by itself.

## Concurrency

The runtime owns all worker pools. Independent DAG passes can run concurrently. Partitioned algorithms expose independent ranges. Barriered algorithms such as FDTD borrow the same pool through `ParallelWorkExecutor` instead of creating nested pools.

Minecraft world state is captured only on the allowed client thread into immutable snapshots. Worker/audio threads never query the live world. The 1.12.2 adapter reuses snapshot overlap and coalesces asynchronous analysis so stale jobs cannot build an unbounded backlog. The 1.12.2 runtime now also materializes each accepted capture as a validated `PlatformFrameSnapshot`: listener orientation and an immutable copy of active source seeds are frozen beside the scene before room/reflection work is queued. The asynchronous source scheduler consumes those frame-owned seeds instead of rereading the live `activeSources` map; a final generation check still rejects sources that stopped or were replaced before queueing. If a source starts while the previously queued room/reflection frame is not yet full-ready, a monotonic source-frame refresh revision forces one subsequent client-thread capture even when scene geometry and the listener are otherwise unchanged. That replacement frame contains the new source seed atomically; coalescing and generation checks still discard obsolete work rather than joining a newer source to an older scene.

## Compute backends

`FdtdExternalBackend` and `GeometricExternalBackend` are neutral acceleration seams. `AcceleratedPass` lets a pass publish the same typed ABI resource through a device backend and transparently request its normal CPU fallback by returning no accelerated result. The core always retains CPU scalar/parallel paths. The 1.12.2 frontend registers optional CUDA Driver API/NVRTC and LWJGL2 OpenCL FDTD/ray implementations at startup. Backends expose explicit AUTO priority (CUDA 200, OpenCL 100), workload suitability and telemetry. Probe, compiler, device, validation or runtime failure is isolated from normal mod operation and the standard CPU paths remain available.

## Material resource architecture

Material identity is independent of the Acoustic Shader DAG. On 1.12.2 Forge post-init enumerates registered block states once and `MaterialInferenceEngine` combines registry/state id, Minecraft `Material`, `SoundType`, OreDictionary names, solidity/shape flags and weak hardness/resistance hints. The resulting exact-state rules are written atomically to the always-present `resourcepacks/Acoustic Shaders Default Materials` cache. Normal world capture then reuses the introspection cache and indexed rules instead of rescanning OreDictionary per voxel.

Ordinary active Minecraft resource packs may add `assets/<namespace>/acoustic_materials/*.json`. They are layered above the generated database and hot-reloaded transactionally. This keeps the first-party Reference Acoustic Shader algorithmic and lets texture/modpack authors correct material physics without forking the shader. See `material-database.md`.

## Volume-medium resource architecture

Volume propagation media are resolved independently from surface materials. Ordinary Resource Packs may add `assets/<namespace>/acoustic_media/*.json`, while shader packs may provide declarative `media/*.json` overlays. Definitions supply density, sound speed and eight-band bulk attenuation; deterministic rules resolve block/state descriptors to a medium. The 1.12.2 platform provides AIR/WATER/LAVA fallbacks and can override them for modded fluids without changing the corresponding `AcousticMaterial`.

Scene voxels carry `mediumShape` independently from solid collision shape. This allows flowing/partially-filled fluid blocks to contain both air and liquid. Geometric paths split at exact medium-shape boundaries; heterogeneous FDTD samples the medium at subcell centers. Current homogeneous OpenCL/CUDA wave kernels reject heterogeneous scenes and fall back to CPU rather than applying an invalid approximation. Resource-pack media files participate in the same transactional content fingerprint/hot-reload mechanism as material/source overlays. For ordinary bottom-up fluid fills, `SceneMediumGeometry` removes fake air sheets between vertically connected voxels and reconstructs a bilinear free surface from neighboring snapshot heights. Ray intersection returns a sloped surface normal, while FDTD queries the same scene-level `mediumAt`; no additional live `World` read is introduced.

## Source/event resource architecture

Sound emitters are independent from world materials. Ordinary Resource Packs may add `assets/<namespace>/acoustic_sources/*.json`; the always-present generated resource pack supplies conservative explosion/projectile/impact/footstep/machine/weather/nonspatial fallbacks. The 1.12.2 bridge derives a normalized identifier from Paulscode `filenameURL`, resolves a portable `AcousticSourceProfile`, and publishes it as the typed `source.profile` resource. `standard.source_behavior` produces `source.behavior` according to shader-local strength/Doppler options. Scheduler priority, moving-source update sensitivity, legacy EFX projection and portable RIR synthesis consume the resulting metadata. Source data remains non-executable and transactionally hot-reloadable.

The Reference Acoustic Shader is selected in a newly created configuration. An explicitly empty shader stack is a valid persisted state and means vanilla/no Acoustic Shader processing; installing/updating the mod does not silently re-enable Reference after the user has explicitly removed it.

## Frequency-aware hybridization

Wave and geometric solvers are independent resource producers. `WaveFieldResult` publishes a conservative `maxTrustedFrequencyHz`; FDTD derives that ceiling from its actual spatial/time discretization. `standard.hybrid` computes an AUTO or requested crossover but clamps it below the wave solver trust ceiling. `standard.impulse_response` then uses a complementary low-frequency wave / high-frequency geometric merge for time-domain FDTD output, or a bounded modal low-frequency contribution for the modal solver. This prevents a coarse wave grid from being treated as physically accurate at frequencies it cannot resolve.

## Audio representation

The portable high-fidelity boundary is `HybridResponse` / RIR / FOA. In 1.12.2 a fast direct/room result is available first; selected live sources execute the source-dependent shader DAG asynchronously while listener-invariant reflection fields are memoized at the published scene boundary. `standard.impulse_response` produces `rir.mono` and `standard.foa` publishes first-order Ambisonics in ACN/SN3D order (`W,Y,Z,X`) together with the exact direct-arrival sample index derived from `HybridResponse`. The direct path itself carries a medium-aware one-way flight time (air uses the configured environment speed, liquids/custom media use their own propagation speed). For one-shot positional legacy sources whose flight time is at least 15 ms, the Mixin redirects the physical `Channel.play()` edge and holds the existing Paulscode source in its normal paused lifecycle until that flight time expires; resume happens on the Paulscode/OpenAL owner thread. Streaming, looping, non-attenuated and bypass sources keep native timing.

The Paulscode command thread keeps an EFX fallback while the full source shader runs. When `legacy-audio.properties` explicitly enables software wet output and a source exposes bounded static signed-mono PCM16, worker threads resample/decode the FOA RIR and run partitioned FFT convolution; only the completed stereo PCM buffer/source creation runs on the OpenAL-owning thread. A wet result that arrives after 220 ms of dry playhead is rejected in favor of the continuous EFX path, because starting a new rich wet voice that late is perceptually a second sound rather than a room tail. Timely wet voices start at zero gain, fade in for 90 ms, and only then permit the EFX auxiliary send to detach. While a dry source is intentionally held for propagation, completed wet work is deferred until dry resume. Streaming, looping, unsupported PCM, queue pressure, stale generations and backend failures remain on EFX.
