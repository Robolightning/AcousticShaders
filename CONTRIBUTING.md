# Contributing

Contributions are welcome under the MIT license.

Before submitting changes:

1. Keep Minecraft/Forge/LWJGL/Paulscode dependencies out of `acoustic-api`, `acoustic-core` and other portable modules.
2. Do not perform world reads or unbounded/blocking work on the audio thread.
3. New GPU/native backends must keep a CPU fallback and a deterministic validation/self-test path where practical.
4. Update the specification or author documentation whenever a public pack/resource/API contract changes.
5. Run `./verify.sh`; physics/runtime changes should add deterministic regression coverage.

For shader/resource-data contributions, see `docs/shader-author-guide/getting-started.md` and `docs/resource-pack-author-guide.md`.
