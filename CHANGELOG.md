# Changelog

## Unreleased / 1.0.0 preparation
- Cross-version atomic source snapshots now preserve the resolved `AcousticSourceProfile`; `PlatformAdapterRuntime` publishes source id/profile into the typed shader DAG while retaining the four-argument Java snapshot constructor as a generic-profile compatibility overload.

- Production implementation is now 100% Kotlin; Java remains only in deliberate external-API test/contract stubs.
- Added explicit AIR/WATER/LAVA propagation media, impedance/refraction/bulk-loss transport, heterogeneous FDTD, and partial-volume liquid surfaces.
- Added configurable `acoustic_media` overlays for ordinary Resource Packs and declarative shader-pack `media/` data so modded fluids can define density, sound speed and eight-band bulk attenuation independently from surface material.
- Minecraft 1.12.2 flowing/modded fluids publish a separate medium fill shape; reflective `getFilledPercentage(world,pos)` is used when available and medium overlays hot-reload transactionally.
- Release logging is concise by default; detailed solver/capture/GPU/path diagnostics require `debug=true`.
- Public project metadata names Robolightning as author and uses the MIT license.
- Documentation reorganized around user, resource-pack, Acoustic Shader and extension-author workflows.
- Final hardware gate: ULTRA/MAXIMUM CUDA FDTD and frame pacing on a real NVIDIA/legacy Minecraft client.
- Added a manifest-verified offline RetroFuturaGradle build-environment pack/import contract covering the official Gradle 8.14.3 ZIP, exact RFG 1.4.9 Git commit, dependency/RFG caches and the Gradle-managed Java 8 toolchain; the local runner now has a no-task preflight mode and pins bootstrap Java 17.
- Started the cross-version adapter stage with an immutable `PlatformFrameSnapshot` boundary and version-neutral `PlatformAdapterRuntime`: adapters can capture scene/listener/sources as one logical frame, capability negotiation happens before activation, non-finite/duplicate source data is rejected, and stale frame-sequence regressions are detected without coupling the portable runtime to any Minecraft loader.
- Added a minimal frame-only `PlatformFrameAdapter` contract for modern frontends; `PlatformAdapterRuntime` now depends on that atomic interface while the older split-capture `AcousticPlatformAdapter` remains source-compatible as a legacy/default bridge.
- Preserved platform source/listener metadata through the version-neutral runtime: `source.gain`, `listener.forward` and `listener.up` are now typed DAG resources alongside source id/profile, with backward-compatible runtime overloads and neutral defaults; RC19 standard propagation intentionally does not re-apply Minecraft source gain.
- Extended that atomic metadata seam with `source.velocity` and `listener.velocity`; legacy Java snapshot/runtime call shapes remain available with zero-motion defaults, while Minecraft 1.12.2 now carries its already-computed source velocity into the immutable platform frame instead of dropping it before the portable boundary.
- Routed the Minecraft 1.12.2 production capture path through that atomic frame boundary: listener state and immutable active-source seeds are frozen with the captured scene before asynchronous room/reflection work, so delayed source scheduling cannot mix geometry/listener state from one capture with live source state from a later tick; generation guards still discard stopped/replaced sources before queueing.
- Closed the late-source race introduced by that stricter frame ownership: when a Paulscode source starts before the current room/reflection frame is full-ready, the runtime requests one coalesced source-frame refresh on the next client tick so the source is solved from a newly coherent scene/listener/source frame instead of starving or being mixed into an older frame.

## 0.3.0-rc20 (work in progress)

- Restored bounded vanilla projectile flight emitters for arrows, throwable entities, fireballs, llama spit and shulker bullets. Flight audio is an ordinary Minecraft `MovingSound`, so it traverses the normal SoundHandler -> Paulscode -> Mixin -> `LegacySoundHook` -> Acoustic Shaders path. Third-party projectile subclasses are not given a synthetic duplicate by default.
- Split projectile semantics into launch / flight / impact: launch and impact remain transient while entity-derived flight sources preserve velocity metadata and Doppler sensitivity.
- Added a probe-only, read-only `LegacyDirectPathDiagnostic` with source-generation ownership so a late asynchronous solve cannot resurrect a diagnostic after source cleanup.
- Added the mono `acousticshaders:projectile.flight` asset and made projectile lifecycle plus diagnostic race tests mandatory legacy-contract checks.
- Runtime disable is now a full audio-state boundary: disabling effects or selecting no Acoustic Shader stops synthetic projectile emitters, invalidates pending/stale source work, clears software-wet voices, detaches OpenAL EFX filters and resets AcousticShaders-owned Doppler velocity on the Paulscode/OpenAL owner thread. Both source-level and post-package regressions cover the transition.
- Runtime `softwareWet.enabled` changes now invalidate the software-render generation even when the renderer thread count is unchanged, so an in-flight pre-toggle convolution cannot publish after re-enable; disabling software wet removes active wet voices on the audio owner thread and restores each dry source's EFX direct/reverb state.
- Projectile/fly-by Doppler now uses core OpenAL `AL_VELOCITY` independently of `ALC_EXT_EFX`; devices without EFX lose only filter/reverb processing, not moving-source Doppler, and AcousticShaders-owned velocity is still reset on source cleanup.
- Recycled Paulscode/OpenAL source IDs are now treated as a new logical-source boundary: stale AcousticShaders wet/filter/diagnostic state is cleared before the replacement source starts, stale asynchronous generations remain invalid, Doppler velocity ownership is value-checked on both cleanup and later writes, and EFX detachment uses the last AcousticShaders direct-filter id as an ownership witness. Pre-existing or subsequently replaced third-party `AL_VELOCITY` and EFX direct/aux state are therefore preserved instead of being blindly overwritten/zeroed/detached. EFX ownership conflicts retire native filter ids into a bounded 128-object per-context orphan budget; exhaustion fails closed for new EFX allocations until the OpenAL context changes while core Doppler remains available.
- OpenAL **context replacement** is now a hard source-identity boundary too: the audio owner thread detects a changed ALC context before play/cleanup/result draining, retires all old logical source generations, clears pending/completed source work and diagnostics, and advances the software-wet render generation so late old-context jobs cannot publish into same-numbered sources in the replacement context. Normal owned recycling of a four-ID pool is stress-tested for 512 cycles without consuming orphan budget, while replacement-context third-party EFX/velocity remains untouched.
- Minecraft **world replacement/unload** is now a logical source-identity boundary even when the ALC context survives: active/pending source generations, completed/deferred results, diagnostics and software-wet worker generation are retired on the client thread, while EFX/wet/AcousticShaders-owned velocity cleanup is requested for and performed only on the audio owner thread. Initial null→first-world attach is deliberately not treated as a destructive transition.
- Context/world identity regressions now cross **actually in-flight** workers: a software-wet convolution blocked at final publication is invalidated by ALC replacement, and a source solve blocked inside the real shader pipeline cannot repopulate `completedSources` after a world replacement. Source-result publication now requires the original epoch and active source generation to still match under `sourceLock`, symmetrically with room publication.
- Runtime recreation/rebind is now an explicit lifecycle boundary: a superseded `LegacyClientRuntime` is closed before replacement, its analysis/physics/accelerator/software-wet workers are shut down, logical source generations and queued results are retired, stale GUI/config references become inert, and native OpenAL cleanup is requested for the audio owner thread instead of being performed from Forge `preInit`.
- Runtime close is now an **asynchronous publication barrier**, not just a shutdown hint: room/source publications are linearized against `close()`, source registration arms its diagnostic under the same lifecycle lock, worker task submission cannot race executor shutdown, and software-wet submit/reconfigure/final-result publication all re-check the closed generation under one lock. `RuntimeCloseRaceSmokeTest` deterministically blocks source registration/wet submission and a real in-flight convolution across close, proving none can resurrect state or a worker pool afterward.
- Runtime activation/reconfiguration is now a hard **analysis-worker generation** boundary: superseded room/source workers cannot consume requests queued for a replacement executor pool, source/room running markers are generation-owned, and the cross-thread world epoch is explicitly volatile. `RuntimeActivationGenerationSmokeTest` deterministically blocks every replacement analysis thread and proves interrupted old workers cannot steal new-generation room/source requests.
- Extended Forge-fluid discovery to `IFluidBlock` and `FluidRegistry.lookupFluidForBlock`, including stable `forge-fluid:<name>` identities, `MEDIUM_ID` overrides and top-down geometry for Forge's negative gaseous fill convention.
- Strengthened Acoustic Shader Pack hot reload for directory and ZIP packs: an invalid edit retains the last-good runtime and a later valid edit atomically replaces it.
- Added an advanced explosion/projectile x AIR/WATER/LAVA matrix, bringing the current portable suites to 54 headless + 39 advanced tests.
- Added strict real-SRG compile probes for a real `EntityTippedArrow` gameplay path and five TNT WATER/LAVA scenarios; `dev-verify.sh` requires both whenever the pinned official Minecraft/MCPConfig/Forge inputs are supplied.
- Removed the historical fixed production-class count from the RFG-reobf-equivalent check. It now requires exact equality with the already verified production class set and requires the new projectile/diagnostic/resources in the audit JAR.
- Release metadata/tooling now targets `0.3.0-rc20`. RC20 is not declared final until the exact Kotlin 2.4.0 clean release chain and remaining physical client/hardware gates are rerun on this tree.

## 0.3.0-rc19

- Added an offline official-binary Forge/SRG validation layer: Forge 14.23.5.2864 event/GUI ABI is audited directly, the official Minecraft 1.12.2 client plus MCPConfig are remapped to SRG names, production reflection symbols are checked against real owners, and all Forge/Mixin-facing Kotlin classes compile/link as Java-8 bytecode without Minecraft/Forge stubs.
- Extended post-package Windows/Forge audio validation through real LWJGL2/OpenAL and Paulscode, transformed Mixin callbacks, a real spatial `SoundHandler` source, an integrated-server world sound packet, and a vanilla `EntityTNTPrimed` explosion whose normal entity tick produces and cleans up the captured gameplay source.
- Corrected the Forge block-registry compatibility fallback: `func_82594_a` is the real `RegistrySimple#getObject(K)` lookup and is no longer misused as zero-argument registry enumeration; wrappers fall back to `iterator()` instead, with a regression smoke test.

- Hardened the exact Kotlin 2.4.0/JVM 8 release toolchain: Kotlin 2.x uses the non-deprecated `-jvm-default=no-compatibility` ABI mode, legacy contract smokes consume the same selected Kotlin distribution, and 2.4-only compiler diagnostics are treated as errors.
- Restored the nullable geometric accelerator result contract so a device backend may explicitly request safe CPU fallback without throwing; added regression coverage for the null-result fallback path.
- Restored the optional bounded Minecraft 1.12.2 software-wet output path: strict static mono PCM16 capture, `standard.foa` ACN/SN3D output, sample-rate conversion, partitioned FFT convolution, stereo OpenAL wet voices, generation/epoch/scene stale guards, pause safety, wet-voice eviction and EFX fallback. The path remains disabled by default pending real-client validation.
- Added separate `Runtime & Audio` controls and crash-safe `legacy-audio.properties` limits for renderer threads, pending work, FFT block size, RIR duration, wet gain, wet voices and PCM bytes.
- FOA output now carries exact direct-arrival sample metadata from the hybrid response; wet convolution removes that arrival after sample-rate conversion instead of guessing from peak amplitude, preserving stronger later reflections.


- Production implementation migrated fully to Kotlin/JVM 8; Forgelin-Continuous 2.4.0.0 is mandatory on Minecraft 1.12.2 and MixinBooter remains a separate dependency.
- Added AIR/WATER/LAVA and configurable modded volume media with density, sound speed and eight-band bulk attenuation.
- Added impedance reflection/transmission, layered Snell/Fermat refraction, liquid-surface reflections and heterogeneous variable-density FDTD.
- Added independent liquid `mediumShape`, flowing/modded partial fill, subvoxel FDTD sampling, vertical liquid continuity and neighbor-aware bilinear free-surface reconstruction with analytical ray normals.
- Added ordinary Resource Pack `acoustic_media` overlays and shader-pack `media/` overlays with transactional hot reload.
- OpenCL/CUDA host backends are Kotlin; current homogeneous GPU FDTD kernels safely fall back to CPU for heterogeneous-media scenes.
- Unified verification now reuses one verified production bytecode tree instead of recompiling Kotlin repeatedly; 54 headless + 35 advanced tests and all legacy/kernel/package gates pass locally.
- Release builder is Kotlin-aware and requires the exact Kotlin 2.4.0 toolchain before RC19 can be called final.

## 0.3.0-rc18

- Replaced monolithic world-entry snapshot with center-out progressive capture bounded by `CAPTURE_BUDGET_MS`; ULTRA/MAXIMUM keep their full final volume instead of trading quality for frame pacing.
- Added per-capture Minecraft `Chunk` reuse and exact-state material-resolution caching on the 1.12.2 hot capture path.
- Added regression coverage for progressive bootstrap ordering/budget/convergence and the chunk-local SRG block-state fast path.
- Extended debug diagnostics with bootstrap progress and capture-budget telemetry.
- Retains the RC17 quiet-by-default logging/public README/MIT/release-hardening work while awaiting the real ULTRA CUDA-FDTD hardware gate.

## 0.3.0-rc16

- CUDA ray backend validated on a real GeForce MX130; OpenCL/CPU remain fallbacks.
- Exact partial-block AABB acoustics for CPU/OpenCL/CUDA ray paths.
- Thickness-aware direct transmission through walls/panes/partial geometry.
- Generated `Acoustic Shaders Default Materials` resource database, mod-set cache invalidation and resource-pack material/source overrides.
- Source profiles for explosions/projectiles/impacts/footsteps/machines/weather.
- Frequency-aware wave/ray hybridization and FDTD trusted-bandwidth crossover.
- Resource-pack migration and PowerShell 5.1 repair hardening.
