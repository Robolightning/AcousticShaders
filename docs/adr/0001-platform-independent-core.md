# ADR-0001: Platform-independent core

Status: Accepted

## Decision

Minecraft-specific classes are forbidden in `acoustic-api`, `acoustic-core`, shader pack ABI, and conformance tests.

## Rationale

Minecraft 1.12.2 is the first target, not the architecture. Keeping immutable scene/audio abstractions portable allows later adapters to use modern BlockState/VoxelShape/tags without breaking shader packs.
