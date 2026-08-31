# ADR-0002: DAG pipeline and runtime-owned scheduling

Status: Accepted

## Decision

Passes declare typed resources they read/write. Runtime derives dependencies and schedules independent passes in parallel. Shader packs cannot create unmanaged threads.

## Rationale

This mirrors modern render-graph ideas, permits automatic parallelism and future CPU/GPU placement, and prevents shader authors from creating audio-thread hazards.
