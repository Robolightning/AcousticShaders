# Changelog

## 0.3.0

- First stable public release for **Minecraft 1.12.2 / Forge 14.23.5.2864**.
- Production implementation is Kotlin/JVM 8 with mandatory MixinBooter 11.15 and Forgelin-Continuous 2.4.0.0+.
- Reference Acoustic Shader provides POTATO/LOW/MEDIUM/HIGH/ULTRA/MAXIMUM profiles, exact partial-block geometry, transmission, diffraction, multi-bounce rays, modal/FDTD wave simulation, hybrid RIR and optional software-wet convolution.
- AIR/WATER/LAVA and modded volume media support density, speed of sound, eight-band attenuation, impedance boundaries, Snell/Fermat refraction, partial/flowing fluid surfaces and heterogeneous CPU FDTD.
- Vanilla projectile flight emitters, source profiles, Doppler and finite medium-aware first-arrival delay run through the ordinary Minecraft -> Paulscode -> Mixin -> Acoustic Shaders path.
- Transactional Acoustic Shader directory/ZIP hot reload and Resource Pack acoustic material/media/source overlays preserve the last-good runtime on invalid edits.
- CUDA and OpenCL ray/FDTD backends include first-use CPU equivalence validation and safe fallback.
- Generated default material/source data provides broad vanilla/modded compatibility while remaining overrideable by Resource Packs.
- Release validation covers 54 headless + 39 advanced regressions, six Reference profiles, Java-8 bytecode/API fencing, real-SRG/Forge contracts, real EntityTippedArrow and TNT AIR/WATER/LAVA gameplay, physical CUDA-FDTD, physical Windows audio/software-wet output and the final human perceptual double-onset check.
- Repository/release hardening adds cross-platform LF normalization, JSON/ZIP/version/path hygiene checks, deterministic package rebuilding and version-agnostic exact Kotlin 2.4 CI.

## 0.3.0-rc20 (release candidate)

- Added finite live propagation delay for eligible positional one-shots: the first native Paulscode sample now respects the medium-aware path time already calculated by the acoustic core instead of arriving immediately. Streaming/looping/non-attenuated/bypass sounds keep vanilla timing.
- Hardened software-wet activation against the audible double-onset defect: late convolution results no longer start a new wet voice, timely wet voices fade in, and EFX handoff occurs only after the transition. The exact Forge/Paulscode listening run and human perceptual check both confirm the former ~0.5 s second/richer TNT event is gone.
- Added deterministic propagation-delay and software-wet activation regressions plus runtime counters for scheduled/resumed/cancelled/forced propagation and late wet fallback.
- Release cleanup removes the obsolete 1.13.2 diagnostic branch from the 1.12.2 release tree, removes maintainer-specific paths from RFG tooling, makes the exact Kotlin workflow version-agnostic, and adds a repository hygiene gate.

- Reference-Hybrid shaderpack packaging now canonicalizes text line endings before ZIP creation, so Windows CRLF checkout policy cannot make the tracked built-in pack appear stale during the exact release gate; binary shaderpack resources remain byte-preserved.
- Real Forge projectile gameplay also exposed an event-id vs resolved-filename separator mismatch: the live source identifier is `acousticshaders:sounds/projectile/flight.ogg`, not `projectile.flight`. The built-in source-profile rule now recognizes both forms so the real derived sound receives `acoustic:projectile_flight`, and the physical gameplay probe observes the actual runtime filename instead of silently ignoring a live source.
- Real Forge 14.23.5.2864 gameplay validation caught a deterministic-package linkage bug in projectile flight audio: direct MCP `MovingSound`/`PositionedSound` field references (starting at `repeat`) do not exist under the SRG runtime because this release path is not ForgeGradle-reobfuscated. Projectile flight now writes all inherited sound fields through the cached MCP+SRG `ForgeReflection` bridge, the independent real-SRG source view no longer hides the issue with a temporary field rewrite, and both build-class and packaged-JAR gates reject any future MCP-only inherited `Fieldref` leakage.
- Restored bounded vanilla projectile flight emitters for arrows, throwable entities, fireballs, llama spit and shulker bullets. Flight audio is an ordinary Minecraft `MovingSound`, so it traverses the normal SoundHandler -> Paulscode -> Mixin -> `LegacySoundHook` -> Acoustic Shaders path. Third-party projectile subclasses are not given a synthetic duplicate by default.
- Split projectile semantics into launch / flight / impact: launch and impact remain transient while entity-derived flight sources preserve velocity metadata and Doppler sensitivity.
- Added a probe-only, read-only `LegacyDirectPathDiagnostic` with source-generation ownership so a late asynchronous solve cannot resurrect a diagnostic after source cleanup.
- Added the mono `acousticshaders:projectile.flight` asset and made projectile lifecycle plus diagnostic race tests mandatory legacy-contract checks.
- Runtime disable is now a full audio-state boundary: disabling effects or selecting no Acoustic Shader stops synthetic projectile emitters, invalidates pending/stale source work, clears software-wet voices, detaches OpenAL EFX filters and resets AcousticShaders-owned Doppler velocity on the Paulscode/OpenAL owner thread. Both source-level and post-package regressions cover the transition.
- Runtime `softwareWet.enabled` changes now invalidate the software-render generation even when the renderer thread count is unchanged, so an in-flight pre-toggle convolution cannot publish after re-enable; disabling software wet removes active wet voices on the audio owner thread and restores each dry source's EFX direct/reverb state.
- Fixed a physical-audio double-onset artifact: asynchronously rendered software-wet output is rejected if it arrives after the transient dry playhead has advanced beyond 220 ms, while timely wet voices fade in over 90 ms and keep the continuous EFX send until the fade completes instead of abruptly starting a second richer voice.
- Live one-shot positional audio now observes finite propagation time before the first native Paulscode/OpenAL dry sample. `SourceLWJGLOpenAL`'s physical `Channel.play()` edge is intercepted so sources with >=15 ms flight time are held in Paulscode's normal paused lifecycle and resumed on the audio-owner thread; the delay uses the same medium-aware direct path as the shader (343 m/s air, WATER/LAVA/custom `speed_m_s`) and cleanup/disable/context replacement cannot resurrect a held source. Streaming, looping and non-attenuated/bypass sources preserve native timing.
- Projectile/fly-by Doppler now uses core OpenAL `AL_VELOCITY` independently of `ALC_EXT_EFX`; devices without EFX lose only filter/reverb processing, not moving-source Doppler, and AcousticShaders-owned velocity is still reset on source cleanup.
- Recycled Paulscode/OpenAL source IDs are now treated as a new logical-source boundary: stale AcousticShaders wet/filter/diagnostic state is cleared before the replacement source starts, stale asynchronous generations remain invalid, Doppler velocity ownership is value-checked on both cleanup and later writes, and EFX detachment uses the last AcousticShaders direct-filter id as an ownership witness. Pre-existing or subsequently replaced third-party `AL_VELOCITY` and EFX direct/aux state are therefore preserved instead of being blindly overwritten/zeroed/detached. EFX ownership conflicts retire native filter ids into a bounded 128-object per-context orphan budget; exhaustion fails closed for new EFX allocations until the OpenAL context changes while core Doppler remains available.
- OpenAL **context replacement** is now a hard source-identity boundary too: the audio owner thread detects a changed ALC context before play/cleanup/result draining, retires all old logical source generations, clears pending/completed source work and diagnostics, and advances the software-wet render generation so late old-context jobs cannot publish into same-numbered sources in the replacement context. Normal owned recycling of a four-ID pool is stress-tested for 512 cycles without consuming orphan budget, while replacement-context third-party EFX/velocity remains untouched.
- Minecraft **world replacement/unload** is now a logical source-identity boundary even when the ALC context survives: active/pending source generations, completed/deferred results, diagnostics and software-wet worker generation are retired on the client thread, while EFX/wet/AcousticShaders-owned velocity cleanup is requested for and performed only on the audio owner thread. Initial null→first-world attach is deliberately not treated as a destructive transition.
- Context/world identity regressions now cross **actually in-flight** workers: a software-wet convolution blocked at final publication is invalidated by ALC replacement, and a source solve blocked inside the real shader pipeline cannot repopulate `completedSources` after a world replacement. Source-result publication now requires the original epoch and active source generation to still match under `sourceLock`, symmetrically with room publication.
- Room/session publication now has a dedicated deterministic proof too: `RoomWorkerWorldBoundarySmokeTest` blocks the real room estimator in an old world, performs a real world unload, then releases the stale worker and requires the published room state to remain empty. The existing room epoch/generation guard passed unchanged, so no production change was needed.
- Final audio-owner native application is now linearized with logical source/session retirement: `drainFullSourceResults`, `drainWetResults` and EFX restoration keep final generation/epoch validation and the authorized EFX/velocity/software-wet native mutation inside the same `sourceLock` lifecycle section. A regression first reproduced a world replacement completing while an old-world `AL_DIRECT_FILTER` write was blocked after validation; the fix serializes that transition, and a second regression proves the same guarantee for real software-wet `AL_BUFFER` voice creation. `close()` now sets its closed/epoch/source-retirement boundary under the same lock.
- Runtime recreation/rebind is now an explicit lifecycle boundary: a superseded `LegacyClientRuntime` is closed before replacement, its analysis/physics/accelerator/software-wet workers are shut down, logical source generations and queued results are retired, stale GUI/config references become inert, and native OpenAL cleanup is requested for the audio owner thread instead of being performed from Forge `preInit`.
- Runtime close is now an **asynchronous publication barrier**, not just a shutdown hint: room/source publications are linearized against `close()`, source registration arms its diagnostic under the same lifecycle lock, worker task submission cannot race executor shutdown, and software-wet submit/reconfigure/final-result publication all re-check the closed generation under one lock. `RuntimeCloseRaceSmokeTest` deterministically blocks source registration/wet submission and a real in-flight convolution across close, proving none can resurrect state or a worker pool afterward.
- Runtime activation/reconfiguration is now a hard **analysis-worker generation** boundary: superseded room/source workers cannot consume requests queued for a replacement executor pool, source/room running markers are generation-owned, and the cross-thread world epoch is explicitly volatile. `RuntimeActivationGenerationSmokeTest` deterministically blocks every replacement analysis thread and proves interrupted old workers cannot steal new-generation room/source requests.
- Extended Forge-fluid discovery to `IFluidBlock` and `FluidRegistry.lookupFluidForBlock`, including stable `forge-fluid:<name>` identities, `MEDIUM_ID` overrides and top-down geometry for Forge's negative gaseous fill convention.
- Strengthened Acoustic Shader Pack hot reload for directory and ZIP packs: an invalid edit retains the last-good runtime and a later valid edit atomically replaces it.
- Added an advanced explosion/projectile x AIR/WATER/LAVA matrix, bringing the current portable suites to 54 headless + 39 advanced tests.
- Added strict real-SRG compile probes for a real `EntityTippedArrow` gameplay path and five TNT WATER/LAVA scenarios; `tools/verification/1.12.2/scripts/dev-verify.sh` requires both whenever the pinned official Minecraft/MCPConfig/Forge inputs are supplied.
- Removed the historical fixed production-class count from the RFG-reobf-equivalent check. It now requires exact equality with the already verified production class set and requires the new projectile/diagnostic/resources in the audit JAR.
- Release metadata/tooling targets `0.3.0-rc20`. The pre-cleanup candidate passed exact Kotlin 2.4.0 A/B packaging, real projectile/TNT-liquid gameplay, physical CUDA-FDTD and physical/perceptual audio gates. The final release tag must rerun the deterministic release chain after cleanup changes rather than inheriting an older verification stamp.

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
