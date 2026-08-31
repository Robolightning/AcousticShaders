# ADR-0003: Minecraft 1.12.2 integration

Status: Proposed

## Decision

Use Forge 1.12.2 with RetroFuturaGradle and MixinBooter/CleanMix for platform hooks. OptiFine and LoliASM/forks are compatibility targets, not part of the Acoustic Shader Specification.

## Validation target

CI/smoke matrix will cover baseline Forge+MixinBooter and combinations with OptiFine, LoliASM and selected maintained forks.
