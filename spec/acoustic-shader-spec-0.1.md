# Acoustic Shader Specification 0.1 (Draft)

## Goals

1. Packs are portable across Minecraft versions when they only use standard capabilities.
2. Runtime algorithms are replaceable; the specification does not mandate ray tracing, wave simulation, DSP implementation, or execution backend.
3. Passes exchange typed resources through a dependency graph.
4. The runtime, not a shader pack, owns threading and scheduling.
5. Optional capabilities allow graceful fallback across hardware/platforms.

## Standard pipeline resource namespaces (initial)

- `scene.*` immutable geometry/material snapshots
- `source.*` sound emitter state
- `listener.*` listener state
- `direct.*` direct-path products
- `reflection.*` geometric propagation products
- `wave.*` wave-domain products
- `rir.*` impulse response products
- `spatial.*` directional/ambisonic products
- `audio.*` DSP/output products

## Execution model

A pack defines passes with typed read/write resources. The runtime builds a DAG from producer/consumer relations. Passes without dependencies may execute concurrently. A pass must never create unmanaged threads.

## Compatibility principle

The standard contains no Forge, Fabric, NeoForge, OptiFine, LoliASM, Mixin, BlockState or Minecraft-specific type. Platform adapters expose the standard scene and audio interfaces.

## Capability negotiation

Packs declare required and optional capabilities. Required capabilities cause a clear incompatibility error if unavailable; optional capabilities must have a pack-defined or runtime-defined fallback. Initial capability names include `PARALLEL_CPU`, `RAY_QUERY`, `WAVE_FIELD`, `DIFFRACTION`, `HRTF`, `CONVOLUTION`, `AMBISONICS`, and `GPU_COMPUTE`.

## Quality and timing

Quality presets are presentation-layer concepts. Internally, runtime passes should be able to consume a time/compute budget and expose discrete or continuous quality controls. Adaptive scheduling must use hysteresis to avoid rapid audible quality oscillation.


## Pack transport (draft)

A development pack contains `manifest.json` and `pipeline.json`; `acoustic.properties` and `materials/*.json` are optional. The M1 reference loader supports unpacked directories. Zip transport is planned and must not change the logical pack ABI.

## Material resolution (draft)

Portable packs may define eight-band absorption spectra plus scattering and transmission. Rules match platform-neutral registry ids, semantic `acoustic:*` tags, or legacy dictionary names. Rule priority is deterministic. Platform-specific systems such as Forge OreDictionary or modern Minecraft tags are inputs to adapter resolution, not part of the shader ABI.

## Security boundary

Declarative shader packs do not execute arbitrary JVM bytecode. New CPU/native solver implementations are extension modules/mods; future GPU shader programs require an explicitly supported sandboxed backend/capability.
