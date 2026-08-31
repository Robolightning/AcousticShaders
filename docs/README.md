# Acoustic Shaders documentation

This directory documents the public author/runtime contracts rather than only internal implementation details.

## Users and modpack authors

- [`minecraft-1.12.2-integration.md`](minecraft-1.12.2-integration.md) — installation/runtime integration and generated data pack behavior.
- [`material-database.md`](material-database.md) — acoustic materials, automatic inference and cache invalidation.
- [`source-profiles.md`](source-profiles.md) — explosions, projectiles, impacts, footsteps, machines and other source categories.
- [`resource-pack-author-guide.md`](resource-pack-author-guide.md) — authoring ordinary Minecraft resource packs with acoustic material/source data.
- [`performance.md`](performance.md) — presets, CPU workers, CUDA/OpenCL, world capture and quality budgets.

## Acoustic Shader authors

- [`shader-author-guide/getting-started.md`](shader-author-guide/getting-started.md) — first Acoustic Shader Pack.
- [`../spec/acoustic-shader-spec-0.3.md`](../spec/acoustic-shader-spec-0.3.md) — normative format/ABI.
- [`architecture.md`](architecture.md) — runtime architecture and threading model.
- [`extension-author-guide.md`](extension-author-guide.md) — registering new passes/compute backends from extension mods.

## Contributors and maintainers

- [`testing.md`](testing.md) — automated and real-client verification.
- [`debugging.md`](debugging.md) — opt-in developer diagnostics and report logging.
- [`progress.md`](progress.md) — development status/history until the first stable release.
- [`adr/`](adr/) — Architecture Decision Records.

Detailed runtime diagnostics are opt-in with `debug=true` in `config/acousticshaders/runtime.properties`; normal releases keep only concise lifecycle/status/warning/error messages.
