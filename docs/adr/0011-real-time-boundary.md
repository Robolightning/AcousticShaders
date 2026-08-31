# ADR-0011: Real-time audio boundary

Status: Accepted.

Simulation runs on runtime-owned workers over immutable snapshots. Complete acoustic results are published atomically. An audio callback/source hook is a consumer only: it must never access Minecraft world state, block on futures, build geometry, or wait for a simulation lock.
