# Performance engineering

## Runtime-owned CPU parallelism

Shader packs never create threads. The runtime owns bounded worker pools and lends them to passes through two contracts:

- `PartitionedPass` for independent work units such as ray batches;
- `RuntimeParallelPass` + `ParallelWorkExecutor` for algorithms that need barriers between phases, notably FDTD.

This matters for FDTD because every timestep must complete before the next one begins. The scheduler keeps the pass on its coordinator thread while z-slices execute concurrently on the shared `acoustic-worker-*` pool. A regression compares scalar and multicore listener impulses sample-by-sample.

The 1.12.2 live frontend uses a separate lightweight analysis coordinator plus `acoustic-physics-worker-*` threads. Room-probe rays are partitioned deterministically across those workers. `CPU_THREADS=0` means auto, currently bounded to roughly `availableProcessors()-1`; an explicit value is clamped to the machine's available processors.

Separate JVM processes are intentionally not used for realtime simulation: copying immutable voxel snapshots through IPC would add latency and memory traffic without improving the shared-memory workload.

### Live-source scheduling

The selected shader preset also controls `LIVE_SOURCES` and `SOURCE_MOVE_THRESHOLD`. Only the perceptually highest-ranked bounded set receives the full source-specific DAG on each published scene; every source still receives the cheap immediate direct/room fallback. Redundant Paulscode `positionChanged()` notifications are ignored until the source moves far enough or the immutable scene revision changes. Full-source requests are additionally deduplicated by source generation, shader/world epoch, scene revision, position and shared-reflection readiness, avoiding the previous room-only → reflection-ready double solve for an otherwise identical source. A result from the immediately preceding scene may be accepted for a tightly bounded tick/distance window, guarded by source generation and world/shader epoch, so a costly ULTRA/MAXIMUM solve does not become permanently unusable merely because a newer capture arrived while it was finishing.

## Minecraft world capture

Minecraft world APIs stay on the client thread, so capture work has a strict real-time boundary. The current 1.12.2 path minimizes that boundary with:

- one block-state read per sampled voxel;
- reusable `MutableBlockPos` and cached reflection handles;
- a per-capture cache of `Chunk` objects, so repeated samples in the same chunk bypass repeated `World -> chunk provider` lookup;
- cached resolved acoustic material by exact state id;
- immutable snapshots for all worker-side physics;
- overlap reuse after listener movement, so normally only the newly exposed shell plus a small dynamic refresh cube is sampled;
- coalesced room-analysis requests: obsolete pending work is replaced by the newest snapshot rather than building an unbounded queue;
- lower-priority daemon physics workers.

### Progressive initial snapshot

A world/dimension transition no longer forces the complete shader-sized volume through the Minecraft client thread in one tick. `LegacySceneCapture` starts a **center-out bootstrap**: nearest cells are captured first and unsampled cells temporarily behave as transparent air until their real geometry is known. This preserves audibility during warm-up instead of conservatively muting unknown space. The bootstrap continues every client tick until the exact same final snapshot volume is complete.

The Reference Shader option `CAPTURE_BUDGET_MS` sets the wall-clock budget per bootstrap tick. Presets currently scale it from 2.5 ms (POTATO) to 8 ms (MAXIMUM). The budget limits *latency per client tick*, not the quality ceiling: ULTRA/MAXIMUM still converge to their full configured capture radii. Expensive modded collision shapes therefore reduce samples completed on that tick rather than causing a multi-second freeze. Debug logging exposes bootstrap samples and completion ratio.

Periodic validation after bootstrap remains a bounded rolling sweep. Re-sampling acoustically identical cells preserves the prior scene revision, avoiding needless room/ray/source recomputation.

## GPU compute: CUDA + OpenCL

The 1.12.2 runtime ships two optional device families behind the same portable `FdtdExternalBackend` / `GeometricExternalBackend` ABI. The core does not import CUDA, OpenCL, LWJGL or JNA. The Minecraft 1.12.2 frontend registers the concrete device implementations and the normal CPU scalar/parallel algorithms remain authoritative fallbacks.

### CUDA

The NVIDIA path is a real CUDA backend rather than an OpenCL alias. `CudaSupport` binds the CUDA **Driver API** (`nvcuda.dll` on Windows) through the JNA 4.4 already present in the Minecraft 1.12.2 launcher. The shipped `fdtd.cu` and `rays.cu` resources are compiled at runtime to PTX with **NVRTC**, loaded with the Driver API, and launched with `cuLaunchKernel`. No CUDA Runtime (`cudart`) or Java CUDA wrapper is required. Device/context/module and large work buffers are persistent for the session.

NVRTC is optional. The Windows test harness first uses an installed CUDA Toolkit when present; otherwise, on NVIDIA machines only, it best-effort downloads the pinned official NVIDIA `nvidia-cuda-nvrtc-cu12==12.2.128` Windows wheel, verifies its SHA-256, and extracts only NVRTC/native compiler DLLs into `config/acousticshaders/cuda/`. Those proprietary NVIDIA binaries are **not redistributed inside the mod/archive**. Failure to obtain/load NVRTC is non-fatal. CUDA 12.2 is intentionally pinned for this legacy target because NVRTC 12.2 still supports `compute_50`, which covers the Maxwell-generation GeForce MX130 class used in real testing.

CUDA has explicit AUTO priority 200; OpenCL has priority 100. Therefore, when both support a job and both consider it worthwhile, `AUTO` means **CUDA -> OpenCL -> CPU**. This is explicit policy rather than incidental registry/alphabetical ordering. Explicit `CUDA` or `OPENCL` still selects that backend for diagnostics, with safe CPU fallback if it cannot execute.

### OpenCL

The OpenCL backend remains the vendor-neutral path through LWJGL2. FDTD builds `acoustic_fdtd`; geometric tracing builds `acoustic_rays`, uploads a cached immutable voxel/material snapshot, traces deterministic multi-bounce rays and reads back the standard `ReflectionField`. Device work buffers and scene uploads are reused where possible. The selector enumerates compiler-capable GPU devices and favors discrete devices when enough topology information is available.

### Shader options and AUTO thresholds

`COMPUTE_BACKEND` controls FDTD: `AUTO`, `CUDA`, `OPENCL`, `CPU_PARALLEL`, `CPU_SCALAR`. `RAY_COMPUTE_BACKEND` independently controls the listener-centric reflection field: `AUTO`, `CUDA`, `OPENCL`, `CPU_PARALLEL`. HIGH normally uses the modal wave solver, so its FDTD backend option may be idle while CUDA/OpenCL ray tracing is active; ULTRA/MAXIMUM exercise FDTD.

AUTO does not blindly offload every task. Each device implementation advertises `supports()` and `preferredForAuto()`; tiny jobs stay on CPU when transfer/launch overhead is likely to dominate. The CUDA/OpenCL ray paths also consider whether the immutable scene is already resident on the device. MAXIMUM may therefore use a device much more often than POTATO/HIGH even with the same AUTO setting.

Device dispatch is hosted on a runtime-owned accelerator executor. Independent CPU passes from the same DAG level continue on the bounded CPU worker pool while GPU host/device work is in flight. Shader authors never create threads, CUDA contexts or command queues.

### Validation and failure isolation

Every device backend is fail-safe. On first real use, FDTD compares a deterministic miniature GPU wave solve sample-by-sample with the CPU reference. Geometric tracing cross-checks representative first-hit distances and material-band reflection energy with portable CPU DDA. A mismatch, compiler error, allocation failure, driver failure or non-finite result disables only that backend for the session; the pass then tries the next compatible accelerator or CPU. Separate telemetry records availability, solve/trace counts, failures, last execution time and self-test status.

The development container has no NVIDIA CUDA device/NVRTC and no usable OpenCL GPU, so it cannot honestly execute the CUDA kernels. Local gates compile all Java with Java 8 contracts, validate backend selection/fallback/priority, run host-C++ syntax checks over the exact shipped `.cu` resources, and package them into the final JAR. The real-client first-use self-test is the CUDA hardware gate.

## Exact collision geometry cost control

The runtime preserves partial-block acoustics without making every full cube expensive. Full-cube and empty shapes use shared singleton fast paths. **`minecraft:air` is rejected before collision-box reflection**, per-block-class collision methods are cached, the mutable world position and `World.getBlockState` handles are pre-resolved, and a thread-local collision-list scratch buffer avoids a fresh list for every partial sample. Collision-box reflection is therefore required only for actual non-full geometry while a sampled block state is captured on the Minecraft client thread; the immutable snapshot thereafter contains portable AABBs and all worker/GPU passes read those directly.

This specifically addresses an RC14 real-client regression: exact shape capture was correct, but the adapter accidentally called the reflective collision enumeration path even for air. In that report, full 28,749-voxel initial captures reached roughly 3.05–3.53 seconds and steady rolling captures had a ~70 ms median, while full source-shader solves had a ~0.9 ms median. RC15 keeps exact slabs/stairs/fences/panes/bars and removes the avoidable air/reflection overhead rather than reducing acoustic quality to hide the bottleneck.

OpenCL and CUDA do not receive Java object graphs. Scene upload deduplicates per-cell shape metadata into compact `shapeOffset/shapeCount + boxes[]` tables alongside material coefficients, and persistent device buffers are reused across solves. Direct CPU transmission unions overlapping per-cell ray intervals before applying thickness-dependent attenuation, avoiding both over-counting and per-subcell voxel expansion.

The low-frequency FDTD backend intentionally uses an occupancy threshold instead of rasterizing every thin AABB into a finer grid; exact thin-geometry handling remains in the geometric/direct path where it is both physically resolvable and cheaper.

## Preset philosophy

POTATO/LOW/MEDIUM reduce capture radii, ray counts and wave cost. HIGH is a quality-oriented default. ULTRA enables FDTD. MAXIMUM intentionally favors quality over frame rate and raises ray, bounce, room-probe, FDTD and RIR budgets substantially. Users may override every exposed stage after selecting a preset.

The goal is not to make MAXIMUM cheap; it is to make each requested unit of quality execute as efficiently and parallel as the architecture safely permits.

## Audio boundary

The Minecraft 1.12.2 live backend applies a cheap direct/room EFX fallback immediately, then schedules the full source shader DAG for a perceptually ranked, bounded set of active sources. The expensive listener-centric environment ray field is shared once per scene/listener snapshot instead of being recomputed per source. Source requests are coalesced and stale generation/epoch/scene results are discarded. The full source DAG can produce mono RIR and FOA; optional software wet output runs resampling and partitioned FFT convolution on a separately bounded/coalescing renderer pool. OpenAL buffer/source creation remains on Paulscode's command thread. The software path defaults OFF and is limited by PCM bytes, pending jobs, renderer threads, IR duration and wet voices; unsupported/streaming/looping sources continue using EFX.

## RC9 hybrid-laptop and frame-pacing hardening

The first RC8 real-device report exposed two performance issues that synthetic tests could not show. First, OpenCL device selection compared `computeUnits * clockMHz` across vendors. OpenCL compute units are not cross-vendor-equivalent, so an Intel UHD 620 could outrank a discrete NVIDIA MX130. RC9 enumerates every compiler-capable GPU, logs the candidate list, prefers discrete-memory devices when the OpenCL 1.1 property is available, and supports the diagnostic JVM override `-Dacousticshaders.opencl.device=<index-or-name>`. That RC9 result was OpenCL; RC11 now adds the independent CUDA path described above.

Second, HIGH periodically sampled the complete 37x21x37 acoustic volume (28,749 block states) in one client tick and recomputed room/source state even when the acoustic voxel content had not changed. RC9 replaces periodic monolithic refresh with a bounded rolling sweep, keeps the prior scene revision when re-sampled voxels are acoustically identical, and skips room/ray/source re-analysis for unchanged stationary snapshots. Capture duration, changed voxel count and sweep progress are logged in debug mode.

On small CPUs the automatic worker policy now reserves more logical processors for Minecraft's client/integrated-server/audio threads (4 logical CPUs -> 2 acoustic physics workers), and the analysis orchestrator is single-threaded below 8 logical processors. Explicit `CPU_THREADS` still overrides AUTO for users who deliberately choose throughput over frame pacing. Slow per-source solves (>=25 ms) emit per-pass timings in debug logs so future reports can distinguish early reflections, wave work and scheduling contention.
