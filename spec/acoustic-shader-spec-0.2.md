# Acoustic Shader Specification 0.2 (draft)

Status: **experimental draft**. This document specifies the portable shader-pack contract. It deliberately does not specify Forge, Fabric, OptiFine, Mixin or any Minecraft class.

## 1. Goals

An Acoustic Shader Pack describes an acoustic processing graph, material rules, user profiles and capability requirements. A conforming runtime may execute compatible passes on CPU, GPU, native code, or another backend. Packs must not depend on the execution backend unless they declare that requirement.

The format is designed so that a pack authored for the 1.12.2 implementation can remain valid on later Minecraft adapters when it only uses standard resources and passes.

## 2. Pack layout

A pack is either a directory or ZIP archive whose root contains:

```text
manifest.json              required
pipeline.json              required
acoustic.properties        optional, but profiles are required by 0.2 runtime
materials/*.json           optional
```

Unknown non-required files must be ignored by a 0.2 loader. ZIP readers must not extract entries merely to parse a pack.

## 3. Manifest

```json
{
  "format": 1,
  "spec": "0.2",
  "id": "author:pack",
  "name": "Pack Name",
  "requires": ["RAY_QUERY"],
  "optional": ["PARALLEL_CPU", "GPU_COMPUTE", "WAVE_FIELD", "HRTF"]
}
```

`requires` is a hard compatibility requirement. `optional` advertises capabilities the pack can exploit but must be able to run without unless an enabled pass itself requires an extension implementation.

## 4. Pipeline

`pipeline.json` is an ordered declaration of pass instances. Ordering is a declaration convenience, not an execution barrier. The runtime derives dependencies from typed resources and compiles a DAG.

```json
{
  "format": 1,
  "passes": [
    {"id":"standard.direct_path"},
    {"id":"standard.diffraction"},
    {"id":"standard.environment_rays", "options":{"rays":"${RAYS}","bounces":"${BOUNCES}"}},
    {"id":"standard.wave_low_frequency", "enabled":"${WAVE != OFF}"},
    {"id":"standard.hybrid"},
    {"id":"standard.impulse_response"}
  ]
}
```

Each pass has a stable implementation ID resolved through the runtime `PassFactoryRegistry`. Extension mods may register additional IDs. A normal shader pack cannot execute arbitrary JVM bytecode.

### 4.1 Resources

Passes declare typed `reads`, `optionalReads`, and `writes`. A required read must have exactly one producer or be a runtime-provided input. Optional reads create a dependency only when a producer is active. Multiple writers for the same resource are invalid unless a future specification explicitly defines merge semantics.

Standard 0.2 resource names currently include:

```text
scene.world
source.position
listener.position
propagation.direct
propagation.diffraction
reflection.field
rir.early
rir.late
wave.field
response.hybrid
rir.mono
```

The Java reference implementation exposes corresponding `ResourceKey<T>` types. Resource names are ABI; Java classes are reference-runtime details.

## 5. Profiles and options

`acoustic.properties` intentionally resembles OptiFine/Iris shader option conventions:

```properties
screen=PROFILE RAYS BOUNCES WAVE
sliders=RAYS BOUNCES
profile.LOW=RAYS:128 BOUNCES:2 WAVE:OFF
profile.HIGH=RAYS:1024 BOUNCES:6 WAVE:LOW
profile.ULTRA=RAYS:4096 BOUNCES:10 WAVE:HIGH
```

A runtime may expose profiles through its GUI and may switch between them automatically when adaptive quality is enabled. User-selected `CUSTOM` behaviour is implementation-defined until a later spec revision formalises option persistence.

Enabled expressions in 0.2 support simple equality/inequality profile checks, for example `${WAVE != OFF}`. Unsupported expression syntax is an error rather than silently evaluating to false.

## 6. Materials

Material packs define named spectra and matching rules. The canonical reference model uses eight frequency bands. A material contains an eight-element absorption spectrum plus scattering and transmission coefficients.

Resolution priority is rule-driven. Platform adapters map game-specific metadata into portable `MaterialDescriptor` values containing registry ID, semantic acoustic tags and legacy aliases such as OreDictionary names.

Semantic tags include, at minimum:

```text
acoustic:stone
acoustic:wood
acoustic:glass
acoustic:metal
acoustic:fabric
acoustic:soil
acoustic:foliage
acoustic:water
```

Minecraft 1.12.2 OreDictionary is a platform fallback and is not part of the shader-pack ABI.

## 7. Execution and threading

Shader packs never own threads. The runtime owns all worker pools and may partition a `PartitionedPass` over available workers. Independent passes in the same DAG level may execute concurrently.

A conforming runtime must publish complete acoustic frame results atomically to an audio consumer. The real-time audio callback must never wait on scene simulation, world access, futures, or a blocking lock.

Reference passes are deterministic with respect to worker count where their algorithm permits this. Non-deterministic extension passes must document that property.

## 8. Adaptive quality

A runtime may maintain a time budget and select a profile or work partition count using measured pass cost. Profile switching should use hysteresis to avoid oscillation. The shader pack describes quality knobs; the runtime decides scheduling policy.

## 9. Security boundary

Directory/ZIP shader packs are data and shader/config content, not trusted JVM extensions. New JVM-side algorithms are installed as normal extension mods. GPU-program support, when standardised, must have an explicit capability and backend contract.

## 10. Current standard passes

The 0.2 reference runtime provides:

- `standard.direct_path`
- `standard.diffraction`
- `standard.environment_rays`
- `standard.early_reflections`
- `standard.late_reverb`
- `standard.wave_low_frequency`
- `standard.hybrid` / `standard.hybridize`
- `standard.impulse_response`

The reference `wave_low_frequency` implementation is a **modal approximation**, not a full wave-equation solver. The stable resource boundary is deliberately designed so a future FDTD/FEM/DG/native/GPU solver can replace it.

## 11. Conformance

The repository reference runtime provides a headless pack conformance tool. A pack is expected to:

1. parse strictly;
2. satisfy required capabilities;
3. compile every declared profile into a valid resource DAG;
4. execute every declared profile against the deterministic reference scene;
5. produce a non-empty final RIR when the pipeline declares `standard.impulse_response`.

Specification 0.2 is not yet frozen. Backward compatibility becomes a release requirement once the project declares Specification 1.0.
