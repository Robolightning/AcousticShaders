# Debugging and diagnostics

Normal releases intentionally keep logs concise. Detailed solver/capture diagnostics are for shader/resource-pack development and bug reports.

## Enabling developer diagnostics

Edit:

```text
.minecraft/config/acousticshaders/runtime.properties
```

and set:

```properties
debug=true
```

The runtime hot-reloads this configuration. The bundled test/report harness sets `debug=true` automatically so test reports contain enough information to diagnose GPU, world-capture, propagation and source-profile issues.

With debug enabled, the 1.12.2 runtime may log:

- CUDA/OpenCL device candidates, backend initialization and self-test results;
- periodic compute counters and last execution time;
- scene-capture sampled/reused/changed voxel counts, timings, progressive-bootstrap samples/completion and periodic-sweep state;
- room openness, mean free path, RT60 and density estimates;
- selected-source full-DAG timings and slow-pass breakdowns;
- explosion direct-path collision-cell count, exact occupied thickness, low/high-band transmission and diffraction availability;
- shader/resource-data hot reload activity.

With debug disabled, expected startup output is limited to concise runtime initialization, active shader/preset, generated database status, and warnings/errors that affect functionality.

Do not design shader packs around log output: logs are diagnostic and not part of the Acoustic Shader ABI.
