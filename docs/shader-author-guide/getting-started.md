# Your first Acoustic Shader Pack

Copy `examples/reference-pack`. A pack is data and needs no Java project unless it depends on a trusted extension pass.

## Minimal structure

```text
MyPack/
  manifest.json
  pipeline.json
  acoustic.properties
```

## Shader-local presets and overrides

Presets belong to the pack and are shown inside Shader Options. Define an explicit order:

```properties
profile.order=POTATO LOW MEDIUM HIGH ULTRA MAXIMUM
profile.POTATO=RAYS:64 BOUNCES:2 WAVE:OFF
profile.HIGH=RAYS:768 BOUNCES:6 WAVE:MODAL
profile.MAXIMUM=RAYS:8192 BOUNCES:16 WAVE:FDTD COMPUTE_BACKEND:AUTO
```

A user chooses a preset, then may override individual declared `option.*` values. Keep option keys stable between pack releases.

Use enabled expressions to remove a stage entirely:

```json
{"id":"standard.diffraction","enabled":"${DIFFRACTION == ON}"}
{"id":"standard.wave_low_frequency","enabled":"${WAVE != OFF}"}
```

Downstream standard hybrid stages use optional resources where appropriate, so disabling diffraction/early/late/wave is valid.

For audio-ready output, the Reference pack continues from `standard.hybrid` through `standard.impulse_response` and `standard.foa`. `standard.foa` publishes `rir.foa.acn_sn3d` (ACN `W,Y,Z,X`, SN3D). Packs that do not need a spatial RIR may omit it; platform runtimes may still use `HybridResponse` for a cheaper EFX-style fallback.

## Performance controls

Packs describe workload knobs, but the runtime owns threads and devices. Standard/reference conventions include `CPU_THREADS`, FDTD `COMPUTE_BACKEND=AUTO/CUDA/OPENCL/CPU_PARALLEL/CPU_SCALAR`, and ray `RAY_COMPUTE_BACKEND=AUTO/CUDA/OPENCL/CPU_PARALLEL`. Do not require a GPU unless the pack manifest declares it as a hard capability; prefer an optional GPU path plus CPU fallback. `AUTO` is deliberately runtime-owned: on the 1.12.2 backend it currently ranks CUDA above OpenCL when both are available, but packs should depend on standard resources rather than backend identity.

## Physical resource data is separate from shader algorithms

Do not put block/source databases into a new Acoustic Shader Pack unless you need legacy compatibility. Ordinary Minecraft Resource Packs own `assets/<namespace>/acoustic_materials/*.json` and `assets/<namespace>/acoustic_sources/*.json`; the runtime supplies a generated lowest-priority base and active resource packs may override it. This lets the same shader pipeline work with different modpacks/data sets. See `docs/material-database.md`, `docs/source-profiles.md`, `docs/resource-pack-author-guide.md` and `examples/material-resource-pack/`.

The Reference pipeline begins with optional `standard.source_behavior`. It reads `source.profile`, applies shader-local `SOURCE_PROFILE_STRENGTH` / Doppler policy and publishes `source.behavior` for propagation/RIR stages. Shader authors should treat source categories as data rather than hardcoding Minecraft sound IDs in solver code.

## Default selection and explicit vanilla mode

The bundled Reference Acoustic Shader is selected on a fresh 1.12.2 configuration. The selector also permits an explicitly empty active stack (`None / vanilla audio`). Runtimes must preserve that explicit user choice rather than silently re-enabling Reference. A third-party shader can fully replace Reference.

## Validate without Minecraft

```bash
./dev-conformance.sh path/to/MyPack out/my-pack-conformance
```

The tool validates and runs every preset in a deterministic room and emits `*-rir.wav`, `*-timings.txt` and `*-pipeline.dot`.

## Custom algorithms

Packs cannot execute arbitrary JVM code. New algorithms are installed as trusted extension mods that register a stable `PassFactory`/compute backend. This keeps downloaded Acoustic Shader Packs data-oriented.
