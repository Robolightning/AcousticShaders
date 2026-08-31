# ADR-0005: Immutable scene snapshots

Status: Accepted

Minecraft world objects are never read by acoustic worker threads. A platform adapter captures an immutable `AcousticScene` snapshot on a platform-safe thread, assigns a revision/generation, and publishes completed state to workers.

Reasons: deterministic simulation, lock-free read concurrency, avoidance of unsafe cross-thread Minecraft access, and portability across old and modern Minecraft implementations.
