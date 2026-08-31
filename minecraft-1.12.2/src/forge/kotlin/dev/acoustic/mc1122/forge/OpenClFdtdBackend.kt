package dev.acoustic.mc1122.forge

import dev.acoustic.core.compute.FdtdExternalBackend
import dev.acoustic.core.compute.FdtdProblem
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Collections
import org.lwjgl.BufferUtils
import org.lwjgl.opencl.CL10
import org.lwjgl.opencl.CLCommandQueue
import org.lwjgl.opencl.CLContext
import org.lwjgl.opencl.CLDevice
import org.lwjgl.opencl.CLKernel
import org.lwjgl.opencl.CLMem
import org.lwjgl.opencl.CLProgram

/** Optional OpenCL 1.x FDTD accelerator backed by LWJGL2's OpenCL bindings. */
internal class OpenClFdtdBackend : FdtdExternalBackend {
    private var initialized = false
    private var available = false
    private var disabled = false
    private var validated = false
    private var description = "OpenCL not probed"
    private var lastFailure = ""
    private var solveCount = 0L
    private var failureCount = 0L
    private var lastSolveNanos = 0L

    private var device: CLDevice? = null
    private var context: CLContext? = null
    private var queue: CLCommandQueue? = null
    private var program: CLProgram? = null
    private var kernel: CLKernel? = null

    private var workPrev: CLMem? = null
    private var workCur: CLMem? = null
    private var workNext: CLMem? = null
    private var workSolid: CLMem? = null
    private var workRefl: CLMem? = null
    private var workResponse: CLMem? = null
    private var workCells = 0
    private var workSteps = 0

    override fun id(): String = "opencl"
    override fun autoPriority(): Int = 100

    @Synchronized
    override fun description(): String {
        ensureInitializedQuietly()
        return description
    }

    @Synchronized
    override fun available(): Boolean {
        ensureInitializedQuietly()
        return available && !disabled
    }

    override fun supports(problem: FdtdProblem): Boolean =
        !problem.heterogeneous() && problem.cells() > 0 && problem.cells() <= 4 * 1024 * 1024

    override fun preferredForAuto(problem: FdtdProblem): Boolean {
        if (!supports(problem)) return false
        return problem.cells().toLong() * problem.maxSteps().toLong() >= 1_500_000L
    }

    @Synchronized override fun solveCount(): Long = solveCount
    @Synchronized override fun failureCount(): Long = failureCount
    @Synchronized override fun lastSolveMillis(): Double = if (lastSolveNanos <= 0L) Double.NaN else lastSolveNanos / 1_000_000.0
    @Synchronized override fun lastFailure(): String = lastFailure

    @Synchronized
    @Throws(Exception::class)
    override fun solve(problem: FdtdProblem): FloatArray {
        ensureInitialized()
        if (!available || disabled) throw IllegalStateException(description)
        if (!supports(problem)) throw IllegalArgumentException("FDTD problem is too large for OpenCL backend")
        val started = System.nanoTime()
        try {
            if (!validated) {
                validateKernel()
                validated = true
                description = baseDescription(description) + "; self-test=pass"
            }
            val result = runKernel(problem)
            solveCount++
            lastSolveNanos = System.nanoTime() - started
            lastFailure = ""
            return result
        } catch (t: Throwable) {
            failureCount++
            lastSolveNanos = System.nanoTime() - started
            lastFailure = shortMessage(t)
            disabled = true
            available = false
            description = baseDescription(description) + "; disabled after runtime failure: " + lastFailure
            if (t is Exception) throw t
            throw RuntimeException(t)
        } finally {
            if (disabled) releasePersistent()
        }
    }

    private fun runKernel(problem: FdtdProblem): FloatArray {
        val cells = problem.cells()
        val steps = problem.maxSteps()
        ensureWorkBuffers(cells, steps)

        val zeros = BufferUtils.createFloatBuffer(cells)
        val current = BufferUtils.createFloatBuffer(cells)
        current.put(problem.sourceIndex(), 1f)
        val solidHost = BufferUtils.createIntBuffer(cells)
        solidHost.put(problem.solid()).flip()
        val reflHost = BufferUtils.createFloatBuffer(cells)
        reflHost.put(problem.wallReflection()).flip()

        check(CL10.clEnqueueWriteBuffer(requireNotNull(queue), requireNotNull(workPrev), CL10.CL_TRUE, 0L, zeros, null, null), "clEnqueueWriteBuffer(prev)")
        check(CL10.clEnqueueWriteBuffer(requireNotNull(queue), requireNotNull(workCur), CL10.CL_TRUE, 0L, current, null, null), "clEnqueueWriteBuffer(cur)")
        check(CL10.clEnqueueWriteBuffer(requireNotNull(queue), requireNotNull(workSolid), CL10.CL_TRUE, 0L, solidHost, null, null), "clEnqueueWriteBuffer(solid)")
        check(CL10.clEnqueueWriteBuffer(requireNotNull(queue), requireNotNull(workRefl), CL10.CL_TRUE, 0L, reflHost, null, null), "clEnqueueWriteBuffer(refl)")

        setObject(3, requireNotNull(workSolid))
        setObject(4, requireNotNull(workRefl))
        setObject(5, requireNotNull(workResponse))
        setInt(6, problem.nx())
        setInt(7, problem.ny())
        setInt(8, problem.nz())
        setInt(9, problem.listenerIndex())
        setFloat(10, problem.lambda())
        setFloat(11, problem.airDamping())

        val global = BufferUtils.createPointerBuffer(1)
        global.put(0, cells.toLong())
        var a = requireNotNull(workPrev)
        var b = requireNotNull(workCur)
        var c = requireNotNull(workNext)
        var step = 0
        while (step < steps) {
            setObject(0, a)
            setObject(1, b)
            setObject(2, c)
            setInt(12, step)
            check(CL10.clEnqueueNDRangeKernel(requireNotNull(queue), requireNotNull(kernel), 1, null, global, null, null, null), "clEnqueueNDRangeKernel")
            val t = a
            a = b
            b = c
            c = t
            step++
        }
        check(CL10.clFinish(requireNotNull(queue)), "clFinish")
        val out = BufferUtils.createFloatBuffer(steps)
        check(CL10.clEnqueueReadBuffer(requireNotNull(queue), requireNotNull(workResponse), CL10.CL_TRUE, 0L, out, null, null), "clEnqueueReadBuffer")
        return FloatArray(steps).also { out.get(it) }
    }

    /** Tiny deterministic device-vs-CPU check executed once on the first real solve. */
    private fun validateKernel() {
        val n = 7
        val cells = n * n * n
        val source = index(3, 3, 3, n, n)
        val listener = index(4, 3, 3, n, n)
        val solid = IntArray(cells)
        val reflection = FloatArray(cells) { 1f }
        val probe = FdtdProblem(n, n, n, 12, source, listener, 0.12f, 0.002f, solid, reflection)
        val gpu = runKernel(probe)
        val cpu = cpuReference(probe)
        var signal = 0.0
        for (i in cpu.indices) {
            if (!gpu[i].isFinite()) throw IllegalStateException("OpenCL FDTD self-test produced non-finite output")
            signal += kotlin.math.abs(gpu[i]).toDouble()
            if (kotlin.math.abs(gpu[i] - cpu[i]) > 2.0e-4f) {
                throw IllegalStateException("OpenCL FDTD self-test mismatch at sample $i: gpu=${gpu[i]} cpu=${cpu[i]}")
            }
        }
        if (signal < 1.0e-5) throw IllegalStateException("OpenCL FDTD self-test produced no propagated signal")
    }

    private fun ensureWorkBuffers(cells: Int, steps: Int) {
        if (workPrev != null && workCells >= cells && workSteps >= steps) return
        val c = maxOf(cells, workCells)
        val s = maxOf(steps, workSteps)
        releaseWorkBuffers()
        workPrev = createBuffer(CL10.CL_MEM_READ_WRITE.toLong(), c.toLong() * 4L)
        workCur = createBuffer(CL10.CL_MEM_READ_WRITE.toLong(), c.toLong() * 4L)
        workNext = createBuffer(CL10.CL_MEM_READ_WRITE.toLong(), c.toLong() * 4L)
        workSolid = createBuffer(CL10.CL_MEM_READ_ONLY.toLong(), c.toLong() * 4L)
        workRefl = createBuffer(CL10.CL_MEM_READ_ONLY.toLong(), c.toLong() * 4L)
        workResponse = createBuffer(CL10.CL_MEM_WRITE_ONLY.toLong(), s.toLong() * 4L)
        workCells = c
        workSteps = s
    }

    private fun releaseWorkBuffers() {
        release(workPrev); release(workCur); release(workNext); release(workSolid); release(workRefl); release(workResponse)
        workPrev = null; workCur = null; workNext = null; workSolid = null; workRefl = null; workResponse = null
        workCells = 0; workSteps = 0
    }

    private fun ensureInitializedQuietly() {
        if (initialized) return
        try {
            ensureInitialized()
        } catch (t: Throwable) {
            available = false
            disabled = true
            lastFailure = shortMessage(t)
            description = "OpenCL unavailable: $lastFailure"
            releasePersistent()
        }
    }

    @Throws(Exception::class)
    private fun ensureInitialized() {
        if (initialized) {
            if (!available || disabled) throw IllegalStateException(description)
            return
        }
        initialized = true
        try {
            val selected = OpenClDeviceSelector.selectBestGpu()
            if (selected == null) {
                description = "OpenCL unavailable: no compiler-capable GPU device"
                return
            }
            val best = selected.device
            device = best
            val err = BufferUtils.createIntBuffer(1)
            context = CLContext.create(selected.platform, Collections.singletonList(best), err)
            check(err.get(0), "CLContext.create")
            queue = CL10.clCreateCommandQueue(requireNotNull(context), best, 0L, err)
            check(err.get(0), "clCreateCommandQueue")
            program = CL10.clCreateProgramWithSource(requireNotNull(context), KERNEL_SOURCE, err)
            check(err.get(0), "clCreateProgramWithSource")
            val build = CL10.clBuildProgram(requireNotNull(program), best, "", null)
            if (build != CL10.CL_SUCCESS) {
                var log = ""
                try { log = requireNotNull(program).getBuildInfoString(best, CL10.CL_PROGRAM_BUILD_LOG) } catch (_: Throwable) {}
                throw IllegalStateException("OpenCL kernel build failed code=$build $log")
            }
            kernel = CL10.clCreateKernel(requireNotNull(program), "acoustic_fdtd", err)
            check(err.get(0), "clCreateKernel")
            available = true
            description = "OpenCL GPU: ${selected.description}"
        } catch (t: Throwable) {
            available = false
            disabled = true
            lastFailure = shortMessage(t)
            releasePersistent()
            if (t is Exception) throw t
            throw RuntimeException(t)
        }
    }

    private fun createBuffer(flags: Long, bytes: Long): CLMem {
        val err = BufferUtils.createIntBuffer(1)
        val mem = CL10.clCreateBuffer(requireNotNull(context), flags, bytes, err)
        check(err.get(0), "clCreateBuffer(size)")
        return mem
    }

    private val objectArg = BufferUtils.createByteBuffer(org.lwjgl.PointerBuffer.getPointerSize())

    private fun setObject(index: Int, value: CLMem) {
        org.lwjgl.PointerBuffer.put(objectArg, 0, value.pointer)
        check(CL10.clSetKernelArg(requireNotNull(kernel), index, objectArg), "clSetKernelArg[$index]")
    }

    private fun setInt(index: Int, value: Int) {
        val buffer = BufferUtils.createIntBuffer(1)
        buffer.put(0, value)
        check(CL10.clSetKernelArg(requireNotNull(kernel), index, buffer), "clSetKernelArg[$index]")
    }

    private fun setFloat(index: Int, value: Float) {
        val buffer = BufferUtils.createFloatBuffer(1)
        buffer.put(0, value)
        check(CL10.clSetKernelArg(requireNotNull(kernel), index, buffer), "clSetKernelArg[$index]")
    }

    @Synchronized
    override fun close() {
        disabled = true
        available = false
        releasePersistent()
    }

    private fun releasePersistent() {
        releaseWorkBuffers()
        kernel?.let { try { CL10.clReleaseKernel(it) } catch (_: Throwable) {} }
        program?.let { try { CL10.clReleaseProgram(it) } catch (_: Throwable) {} }
        queue?.let { try { CL10.clReleaseCommandQueue(it) } catch (_: Throwable) {} }
        context?.let { try { CL10.clReleaseContext(it) } catch (_: Throwable) {} }
        kernel = null; program = null; queue = null; context = null; device = null
    }

    companion object {
        private const val KERNEL_SOURCE =
            "inline float acoustic_sample(__global const float* f,__global const int* solid,__global const float* refl,int n,int c){return solid[n]!=0?f[c]*refl[n]:f[n];}\n" +
            "__kernel void acoustic_fdtd(__global const float* prev,__global const float* cur,__global float* next,__global const int* solid,__global const float* refl,__global float* response,int nx,int ny,int nz,int listener,float lambda,float damping,int step){\n" +
            " int i=(int)get_global_id(0); int cells=nx*ny*nz; if(i>=cells)return; int plane=nx*ny; int z=i/plane; int rem=i-z*plane; int y=rem/nx; int x=rem-y*nx;\n" +
            " if(i==listener)response[step]=cur[i];\n" +
            " if(x==0||y==0||z==0||x==nx-1||y==ny-1||z==nz-1||solid[i]!=0){next[i]=0.0f;return;}\n" +
            " float center=cur[i]; float lap=acoustic_sample(cur,solid,refl,i-1,i)+acoustic_sample(cur,solid,refl,i+1,i)+acoustic_sample(cur,solid,refl,i-nx,i)+acoustic_sample(cur,solid,refl,i+nx,i)+acoustic_sample(cur,solid,refl,i-plane,i)+acoustic_sample(cur,solid,refl,i+plane,i)-6.0f*center;\n" +
            " float value=(2.0f-damping)*center-(1.0f-damping)*prev[i]+lambda*lap; next[i]=clamp(value,-8.0f,8.0f);\n" +
            "}\n"

        private fun cpuReference(p: FdtdProblem): FloatArray {
            val cells = p.cells(); val nx = p.nx(); val ny = p.ny(); val nz = p.nz(); val plane = nx * ny
            var previous = FloatArray(cells)
            var current = FloatArray(cells)
            var next = FloatArray(cells)
            val response = FloatArray(p.maxSteps())
            current[p.sourceIndex()] = 1f
            val solid = p.solid(); val refl = p.wallReflection()
            var step = 0
            while (step < p.maxSteps()) {
                response[step] = current[p.listenerIndex()]
                var z = 1
                while (z < nz - 1) {
                    var y = 1
                    while (y < ny - 1) {
                        var x = 1
                        while (x < nx - 1) {
                            val i = index(x, y, z, nx, ny)
                            if (solid[i] != 0) {
                                next[i] = 0f
                            } else {
                                val center = current[i]
                                val lap = sample(current, solid, refl, i - 1, i) + sample(current, solid, refl, i + 1, i) +
                                    sample(current, solid, refl, i - nx, i) + sample(current, solid, refl, i + nx, i) +
                                    sample(current, solid, refl, i - plane, i) + sample(current, solid, refl, i + plane, i) - 6f * center
                                val value = (2f - p.airDamping()) * center - (1f - p.airDamping()) * previous[i] + p.lambda() * lap
                                next[i] = maxOf(-8f, minOf(8f, value))
                            }
                            x++
                        }
                        y++
                    }
                    z++
                }
                val t = previous; previous = current; current = next; next = t
                step++
            }
            return response
        }

        private fun sample(field: FloatArray, solid: IntArray, reflection: FloatArray, neighbor: Int, center: Int): Float =
            if (solid[neighbor] != 0) field[center] * reflection[neighbor] else field[neighbor]

        private fun index(x: Int, y: Int, z: Int, nx: Int, ny: Int): Int = x + nx * (y + ny * z)

        private fun check(code: Int, op: String) {
            if (code != CL10.CL_SUCCESS) throw IllegalStateException("$op failed with OpenCL code $code")
        }

        private fun release(mem: CLMem?) {
            if (mem != null) try { CL10.clReleaseMemObject(mem) } catch (_: Throwable) {}
        }

        private fun baseDescription(value: String): String {
            val i = value.indexOf("; disabled after runtime failure:")
            return if (i < 0) value else value.substring(0, i)
        }

        private fun shortMessage(t: Throwable): String = t.message ?: t.javaClass.simpleName
    }
}
