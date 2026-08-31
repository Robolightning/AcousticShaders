# Acoustic extension author guide (draft)

Extension mods add algorithms; shader packs compose them.

Register a stable pass ID through `PassFactoryRegistry`. A factory receives the declarative pass definition and resolved profile. It returns a `Pass` or `PartitionedPass` with typed resource declarations.

Rules:

- never create a private thread pool from a pass;
- use `PartitionedPass` for CPU work the runtime may fan out;
- do not read Minecraft world objects from worker threads;
- consume immutable `AcousticScene` snapshots;
- keep output immutable whenever practical;
- declare optional reads explicitly;
- keep pass IDs namespaced and stable;
- document nondeterministic algorithms;
- expose backend requirements as capabilities rather than platform checks.

GPU compute already follows the same resource contract. Optional device implementations register `FdtdExternalBackend` or `GeometricExternalBackend`, declare `supports()`/`preferredForAuto()` and an explicit `autoPriority()`, and must preserve the portable output semantics. RC11 uses priority 200 for CUDA and 100 for OpenCL. A GPU solver and CPU solver remain interchangeable producers of the same ABI resource; device failure must be isolated so CPU fallback remains valid.


## Source-aware algorithms

Portable algorithms may read `StandardResources.SOURCE_PROFILE` or the shader-processed `SOURCE_BEHAVIOR`. Treat source metadata as hints/physical coefficients, not as a request to inspect Minecraft classes. Do not hard-code Minecraft sound paths inside a solver; path-to-profile mapping belongs to `acoustic_sources/*.json`. Entity-aware extensions that synthesize new emitters should publish normal portable source snapshots/profiles and preserve the same scheduler/audio-thread rules.
