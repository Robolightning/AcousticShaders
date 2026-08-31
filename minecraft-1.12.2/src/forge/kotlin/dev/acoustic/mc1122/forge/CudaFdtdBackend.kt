package dev.acoustic.mc1122.forge

import dev.acoustic.core.compute.FdtdExternalBackend
import dev.acoustic.core.compute.FdtdProblem
import java.nio.file.Path

/** Genuine NVIDIA CUDA Driver API FDTD backend; CUDA C is compiled by NVRTC at runtime. */
internal class CudaFdtdBackend(private val nativeDir: Path) : FdtdExternalBackend {
    private var probed = false
    private var available = false
    private var disabled = false
    private var validated = false
    private var description = "CUDA FDTD not probed"
    private var lastFailure = ""
    private var solveCount = 0L
    private var failureCount = 0L
    private var lastSolveNanos = 0L
    private var probe: CudaSupport.Probe? = null
    private var context: CudaSupport.Context? = null
    private var workPrev = 0L
    private var workCur = 0L
    private var workNext = 0L
    private var workSolid = 0L
    private var workRefl = 0L
    private var workResponse = 0L
    private var workCells = 0
    private var workSteps = 0

    override fun id(): String = "cuda"
    override fun autoPriority(): Int = 200

    @Synchronized override fun description(): String { ensureProbeQuietly(); return description }
    @Synchronized override fun available(): Boolean { ensureProbeQuietly(); return available && !disabled }
    override fun supports(problem: FdtdProblem): Boolean = !problem.heterogeneous() && problem.cells() > 0 && problem.cells() <= 4 * 1024 * 1024
    override fun preferredForAuto(problem: FdtdProblem): Boolean = supports(problem) && problem.cells().toLong() * problem.maxSteps().toLong() >= 1_500_000L
    @Synchronized override fun solveCount(): Long = solveCount
    @Synchronized override fun failureCount(): Long = failureCount
    @Synchronized override fun lastSolveMillis(): Double = if (lastSolveNanos <= 0L) Double.NaN else lastSolveNanos / 1_000_000.0
    @Synchronized override fun lastFailure(): String = lastFailure

    @Synchronized
    @Throws(Exception::class)
    override fun solve(problem: FdtdProblem): FloatArray {
        ensureProbe()
        if (!available || disabled) throw IllegalStateException(description)
        if (!supports(problem)) throw IllegalArgumentException("FDTD problem not supported by CUDA backend")
        val started = System.nanoTime()
        var failed = false
        try {
            ensureContext()
            if (!validated) {
                validateKernel()
                validated = true
                description = baseDescription(description) + "; self-test=pass"
            }
            val out = runKernel(problem)
            solveCount++
            lastSolveNanos = System.nanoTime() - started
            lastFailure = ""
            return out
        } catch (t: Throwable) {
            failureCount++
            lastSolveNanos = System.nanoTime() - started
            lastFailure = CudaSupport.shortMessage(t)
            disabled = true
            available = false
            description = baseDescription(description) + "; disabled after runtime failure: " + lastFailure
            failed = true
            if (t is Exception) throw t
            throw RuntimeException(t)
        } finally {
            if (failed) releasePersistent()
        }
    }

    private fun runKernel(problem: FdtdProblem): FloatArray {
        val cells = problem.cells(); val steps = problem.maxSteps()
        ensureWorkBuffers(cells, steps)
        val zero = FloatArray(cells)
        val current = FloatArray(cells)
        current[problem.sourceIndex()] = 1f
        val ctx = requireNotNull(context)
        ctx.upload(workPrev, zero); ctx.upload(workCur, current); ctx.upload(workSolid, problem.solid()); ctx.upload(workRefl, problem.wallReflection())
        ctx.uploadZeros(workResponse, steps.toLong() * 4L)
        var a = workPrev; var b = workCur; var c = workNext
        for (step in 0 until steps) {
            val args = CudaSupport.KernelArgs(13)
                .ptr(a).ptr(b).ptr(c).ptr(workSolid).ptr(workRefl).ptr(workResponse)
                .i32(problem.nx()).i32(problem.ny()).i32(problem.nz()).i32(problem.listenerIndex())
                .f32(problem.lambda()).f32(problem.airDamping()).i32(step)
            ctx.launch(cells, args)
            val t = a; a = b; b = c; c = t
        }
        ctx.sync()
        return FloatArray(steps).also { ctx.download(workResponse, it) }
    }

    private fun validateKernel() {
        val n = 7; val cells = n * n * n
        val source = index(3, 3, 3, n, n); val listener = index(4, 3, 3, n, n)
        val solid = IntArray(cells); val reflection = FloatArray(cells) { 1f }
        val problem = FdtdProblem(n, n, n, 12, source, listener, 0.12f, 0.002f, solid, reflection)
        val gpu = runKernel(problem); val cpu = cpuReference(problem)
        var signal = 0.0
        for (i in cpu.indices) {
            if (!gpu[i].isFinite()) throw IllegalStateException("CUDA FDTD self-test produced non-finite output")
            signal += kotlin.math.abs(gpu[i]).toDouble()
            if (kotlin.math.abs(gpu[i] - cpu[i]) > 2.5e-4f) throw IllegalStateException("CUDA FDTD self-test mismatch sample=$i gpu=${gpu[i]} cpu=${cpu[i]}")
        }
        if (signal < 1e-5) throw IllegalStateException("CUDA FDTD self-test produced no propagated signal")
    }

    private fun ensureProbeQuietly() {
        if (probed) return
        try { ensureProbe() } catch (t: Throwable) {
            available = false; disabled = true; lastFailure = CudaSupport.shortMessage(t); description = "CUDA FDTD unavailable: $lastFailure"
        }
    }

    @Throws(Exception::class)
    private fun ensureProbe() {
        if (probed) {
            if (!available || disabled) throw IllegalStateException(description)
            return
        }
        probed = true
        probe = CudaSupport.probe(nativeDir)
        available = true
        description = requireNotNull(probe).description() + "; FDTD kernel=NVRTC lazy"
    }

    @Throws(Exception::class)
    private fun ensureContext() {
        if (context != null) return
        val p = requireNotNull(probe)
        val ptx = CudaSupport.compile(p, CudaSupport.readResource("/assets/acousticshaders/cuda/fdtd.cu"), "acoustic_fdtd.cu")
        context = CudaSupport.createContext(p, ptx, "acoustic_fdtd")
    }

    private fun ensureWorkBuffers(cells: Int, steps: Int) {
        if (workPrev != 0L && workCells >= cells && workSteps >= steps) return
        val c = maxOf(cells, workCells); val s = maxOf(steps, workSteps)
        releaseWorkBuffers()
        val ctx = requireNotNull(context)
        workPrev = ctx.alloc(c.toLong() * 4L); workCur = ctx.alloc(c.toLong() * 4L); workNext = ctx.alloc(c.toLong() * 4L)
        workSolid = ctx.alloc(c.toLong() * 4L); workRefl = ctx.alloc(c.toLong() * 4L); workResponse = ctx.alloc(s.toLong() * 4L)
        workCells = c; workSteps = s
    }

    private fun releaseWorkBuffers() {
        if (context != null) {
            safeFree(workPrev); safeFree(workCur); safeFree(workNext); safeFree(workSolid); safeFree(workRefl); safeFree(workResponse)
        }
        workPrev = 0L; workCur = 0L; workNext = 0L; workSolid = 0L; workRefl = 0L; workResponse = 0L
        workCells = 0; workSteps = 0
    }

    private fun safeFree(pointer: Long) {
        if (pointer != 0L) try { requireNotNull(context).free(pointer) } catch (_: Throwable) {}
    }

    private fun releasePersistent() {
        releaseWorkBuffers()
        context?.let { try { it.close() } catch (_: Throwable) {} }
        context = null
    }

    @Synchronized override fun close() { disabled = true; available = false; releasePersistent() }

    companion object {
        private fun cpuReference(problem: FdtdProblem): FloatArray {
            val cells = problem.cells(); val nx = problem.nx(); val ny = problem.ny(); val nz = problem.nz(); val plane = nx * ny
            var prev = FloatArray(cells); var cur = FloatArray(cells); var next = FloatArray(cells); val response = FloatArray(problem.maxSteps())
            cur[problem.sourceIndex()] = 1f
            val solid = problem.solid(); val refl = problem.wallReflection()
            for (step in 0 until problem.maxSteps()) {
                response[step] = cur[problem.listenerIndex()]
                for (z in 1 until nz - 1) for (y in 1 until ny - 1) for (x in 1 until nx - 1) {
                    val i = index(x, y, z, nx, ny)
                    if (solid[i] != 0) { next[i] = 0f; continue }
                    val center = cur[i]
                    val lap = sample(cur, solid, refl, i - 1, i) + sample(cur, solid, refl, i + 1, i) +
                        sample(cur, solid, refl, i - nx, i) + sample(cur, solid, refl, i + nx, i) +
                        sample(cur, solid, refl, i - plane, i) + sample(cur, solid, refl, i + plane, i) - 6f * center
                    val value = (2f - problem.airDamping()) * center - (1f - problem.airDamping()) * prev[i] + problem.lambda() * lap
                    next[i] = maxOf(-8f, minOf(8f, value))
                }
                val t = prev; prev = cur; cur = next; next = t
            }
            return response
        }
        private fun sample(field: FloatArray, solid: IntArray, refl: FloatArray, neighbor: Int, center: Int): Float = if (solid[neighbor] != 0) field[center] * refl[neighbor] else field[neighbor]
        private fun index(x: Int, y: Int, z: Int, nx: Int, ny: Int): Int = x + nx * (y + ny * z)
        private fun baseDescription(value: String): String { val i = value.indexOf("; disabled after runtime failure:"); return if (i < 0) value else value.substring(0, i) }
    }
}
