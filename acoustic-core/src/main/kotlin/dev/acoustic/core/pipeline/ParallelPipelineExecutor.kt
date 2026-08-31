package dev.acoustic.core.pipeline

import dev.acoustic.api.pipeline.AcceleratedPass
import dev.acoustic.api.pipeline.ParallelWorkExecutor
import dev.acoustic.api.pipeline.PartitionedPass
import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.PhasedParallelWorkExecutor
import dev.acoustic.api.pipeline.Pipeline
import dev.acoustic.api.pipeline.RuntimeParallelPass
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/** Runtime-owned scheduler. Shader passes never create threads themselves. */
class ParallelPipelineExecutor private constructor(
    private val executor: ExecutorService,
    private val workerCount: Int,
    private val ownsExecutor: Boolean
) : AutoCloseable {
    private val acceleratorExecutor: ExecutorService
    private val parallelWork: ParallelWorkExecutor

    constructor(workerCount: Int) : this(newExecutor(workerCount), workerCount, true)

    /**
     * Borrows a runtime-owned CPU pool. Closing this scheduler will not shut the
     * supplied executor down; this is used by platform runtimes so room probes
     * and full shader passes share one bounded CPU budget instead of creating
     * competing thread pools. The tiny accelerator host executor remains owned
     * by this scheduler and exists only to let blocking GPU/OpenCL calls overlap
     * independent CPU work from the same DAG level.
     */
    constructor(executor: ExecutorService, workerCount: Int) : this(executor, workerCount, false)

    init {
        require(workerCount >= 1) { "workerCount must be >= 1" }
        parallelWork = RuntimeParallelWork()
        acceleratorExecutor = Executors.newSingleThreadExecutor(
            NamedDaemonFactory("acoustic-accelerator-", Thread.NORM_PRIORITY - 1)
        )
    }

    fun parallelWork(): ParallelWorkExecutor = parallelWork

    @Throws(Exception::class)
    fun execute(pipeline: Pipeline, context: PassContext) {
        executeProfiled(pipeline, context)
    }

    @Throws(Exception::class)
    fun executeProfiled(pipeline: Pipeline, context: PassContext): ExecutionReport {
        val plan = PipelinePlan.compile(pipeline)
        val timings = ArrayList<PassTiming>()
        val startAll = System.nanoTime()
        for (level in plan.levels()) executeLevel(level, context, timings)
        return ExecutionReport(System.nanoTime() - startAll, timings)
    }

    @Throws(Exception::class)
    private fun executeLevel(level: List<Pass>, context: PassContext, timings: MutableList<PassTiming>) {
        val acceleration = LinkedHashMap<Pass, Future<AccelerationResult>>()
        for (pass in level) {
            if (pass is AcceleratedPass) {
                acceleration[pass] = acceleratorExecutor.submit(Callable {
                    val start = System.nanoTime()
                    try {
                        AccelerationResult(pass.tryExecuteAccelerated(context), System.nanoTime() - start)
                    } catch (_: Throwable) {
                        AccelerationResult(null, System.nanoTime() - start)
                    }
                })
            }
        }

        // Submit ordinary CPU work immediately so it can overlap accelerator host/device work.
        val work = LinkedHashMap<Pass, List<Future<Any?>>>()
        val starts = LinkedHashMap<Pass, Long>()
        for (pass in level) {
            if (pass is AcceleratedPass || pass is RuntimeParallelPass) continue
            submitCpuPass(pass, context, work, starts)
        }

        // Non-accelerated cooperative passes may consume the full bounded CPU pool.
        for (pass in level) {
            if (pass is AcceleratedPass || pass !is RuntimeParallelPass) continue
            val start = System.nanoTime()
            pass.executeParallel(context, parallelWork)
            timings.add(PassTiming(pass.id(), System.nanoTime() - start, "cooperative-parallel[$workerCount]"))
        }

        // Resolve device attempts only after independent CPU work had a chance to run.
        val cpuFallback = ArrayList<Pass>()
        for (pass in level) {
            val future = acceleration[pass] ?: continue
            val result = awaitAcceleration(future)
            if (result.label != null) {
                timings.add(PassTiming(pass.id(), result.elapsedNanos, "accelerated[${result.label}]"))
            } else {
                cpuFallback.add(pass)
            }
        }

        // Queue partitioned/ordinary fallbacks before cooperative fallbacks. This keeps the
        // CPU pool busy without creating nested pools; cooperative passes simply wait for
        // the same bounded workers when necessary.
        for (pass in cpuFallback) {
            if (pass !is RuntimeParallelPass) submitCpuPass(pass, context, work, starts)
        }
        for (pass in cpuFallback) {
            if (pass !is RuntimeParallelPass) continue
            val start = System.nanoTime()
            pass.executeParallel(context, parallelWork)
            timings.add(PassTiming(pass.id(), System.nanoTime() - start, "cooperative-parallel[$workerCount]"))
        }

        try {
            for (pass in level) {
                val futures = work[pass] ?: continue
                val results = ArrayList<Any?>()
                for (future in futures) results.add(await(future))
                if (pass is PartitionedPass) pass.combine(context, Collections.unmodifiableList(results))
                val elapsed = System.nanoTime() - starts.getValue(pass)
                val executorName = if (futures.size > 1) "parallel[${futures.size}]" else "acoustic-worker"
                timings.add(PassTiming(pass.id(), elapsed, executorName))
            }
        } catch (failure: Exception) {
            cancelOutstanding(work)
            cancelAcceleration(acceleration)
            throw failure
        }
    }

    private fun submitCpuPass(
        pass: Pass,
        context: PassContext,
        work: MutableMap<Pass, List<Future<Any?>>>,
        starts: MutableMap<Pass, Long>
    ) {
        starts[pass] = System.nanoTime()
        val futures = ArrayList<Future<Any?>>()
        if (pass is PartitionedPass) {
            val units = pass.workUnits()
            require(units >= 1) { "partitioned pass has no work: ${pass.id()}" }
            val parts = minOf(workerCount, units)
            val base = units / parts
            val extra = units % parts
            var from = 0
            var i = 0
            while (i < parts) {
                val start = from
                val end = start + base + if (i < extra) 1 else 0
                from = end
                futures.add(executor.submit(Callable<Any?> { pass.executePartition(context, start, end) }))
                i++
            }
        } else {
            futures.add(executor.submit(Callable<Any?> {
                pass.execute(context)
                null
            }))
        }
        work[pass] = futures
    }

    private inner class RuntimeParallelWork : PhasedParallelWorkExecutor {
        override fun workerCount(): Int = workerCount

        @Throws(Exception::class)
        override fun forRange(fromInclusive: Int, toExclusive: Int, task: ParallelWorkExecutor.RangeTask) {
            if (toExclusive <= fromInclusive) return
            val units = toExclusive - fromInclusive
            val parts = minOf(workerCount, units)
            if (parts <= 1) {
                task.run(fromInclusive, toExclusive)
                return
            }
            val base = units / parts
            val extra = units % parts
            var from = fromInclusive
            val futures = ArrayList<Future<Any?>>(parts)
            var i = 0
            while (i < parts) {
                val start = from
                val end = start + base + if (i < extra) 1 else 0
                from = end
                futures.add(executor.submit(Callable<Any?> {
                    task.run(start, end)
                    null
                }))
                i++
            }
            try {
                for (future in futures) await(future)
            } catch (failure: Exception) {
                for (future in futures) if (!future.isDone) future.cancel(true)
                throw failure
            }
        }

        @Throws(Exception::class)
        override fun forWorkers(workers: Int, task: PhasedParallelWorkExecutor.WorkerTask) {
            require(workers >= 1 && workers <= workerCount) { "workers must be within [1,$workerCount]" }
            if (workers == 1) {
                task.run(0, 1)
                return
            }
            val futures = ArrayList<Future<Any?>>(workers)
            var i = 0
            while (i < workers) {
                val index = i
                futures.add(executor.submit(Callable<Any?> {
                    task.run(index, workers)
                    null
                }))
                i++
            }
            try {
                for (future in futures) await(future)
            } catch (failure: Exception) {
                for (future in futures) if (!future.isDone) future.cancel(true)
                throw failure
            }
        }
    }

    private data class AccelerationResult(val label: String?, val elapsedNanos: Long)

    private class NamedDaemonFactory(private val prefix: String, priority: Int) : ThreadFactory {
        private val priority = priority.coerceIn(Thread.MIN_PRIORITY, Thread.MAX_PRIORITY)
        private var index = 0

        @Synchronized
        override fun newThread(runnable: Runnable): Thread {
            val thread = Thread(runnable, prefix + index++)
            thread.isDaemon = true
            thread.priority = priority
            return thread
        }
    }

    override fun close() {
        acceleratorExecutor.shutdownNow()
        if (ownsExecutor) executor.shutdownNow()
    }

    companion object {
        private fun newExecutor(workerCount: Int): ExecutorService {
            require(workerCount >= 1) { "workerCount must be >= 1" }
            return Executors.newFixedThreadPool(
                workerCount,
                NamedDaemonFactory("acoustic-worker-", Thread.NORM_PRIORITY - 1)
            )
        }

        private fun cancelOutstanding(work: Map<Pass, List<Future<Any?>>>) {
            for (futures in work.values) for (future in futures) if (!future.isDone) future.cancel(true)
        }

        private fun cancelAcceleration(work: Map<Pass, Future<AccelerationResult>>) {
            for (future in work.values) if (!future.isDone) future.cancel(true)
        }

        @Throws(Exception::class)
        private fun await(future: Future<Any?>): Any? {
            return try {
                future.get()
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                if (cause is Exception) throw cause
                throw RuntimeException(cause)
            }
        }

        @Throws(Exception::class)
        private fun awaitAcceleration(future: Future<AccelerationResult>): AccelerationResult {
            return try {
                future.get()
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (_: ExecutionException) {
                AccelerationResult(null, 0L)
            }
        }
    }
}
