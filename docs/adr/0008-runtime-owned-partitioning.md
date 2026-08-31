# ADR-0008: Runtime-owned partitioning for heavy passes

Status: Accepted

Heavy solvers implement `PartitionedPass` and expose a deterministic number of work units plus a combine step. The runtime chooses partitions and executes them on its bounded worker pool. Shader implementations must not create threads.

All tasks for an independent dependency level are queued before waiting for completion. This permits independent heavy passes to share the pool and avoids the classic deadlock caused by worker tasks submitting nested work to the same saturated executor and then blocking on it.
