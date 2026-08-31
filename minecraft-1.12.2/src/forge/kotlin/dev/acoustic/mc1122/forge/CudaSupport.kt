package dev.acoustic.mc1122.forge

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.Locale

/** Minimal CUDA Driver API + NVRTC binding using the JNA already shipped by Minecraft 1.12.2. */
internal object CudaSupport {
    const val CUDA_SUCCESS = 0
    private val PINNED_NATIVE_LIBRARIES: MutableList<NativeLibrary> =
        Collections.synchronizedList(ArrayList<NativeLibrary>())

    @JvmStatic
    @Throws(Exception::class)
    fun probe(nativeDir: Path?): Probe {
        if (Native.POINTER_SIZE != 8) throw IllegalStateException("CUDA backend requires a 64-bit JVM")
        val driver = loadDriver()
        val cuInit = fn(driver, "cuInit")
        check(driver, cuInit.invokeInt(arrayOf<Any?>(0)), "cuInit")
        val countRef = IntByReference()
        check(driver, fn(driver, "cuDeviceGetCount").invokeInt(arrayOf<Any?>(countRef)), "cuDeviceGetCount")
        val count = countRef.value
        if (count < 1) throw IllegalStateException("CUDA driver reports no devices")
        val devices = ArrayList<Device>(count)
        for (ordinal in 0 until count) devices += queryDevice(driver, ordinal)
        val selected = selectDevice(devices, System.getProperty("acousticshaders.cuda.device", ""))
        val nvrtc = loadNvrtc(nativeDir)
        return Probe(driver, nvrtc, selected, Collections.unmodifiableList(devices))
    }

    @JvmStatic
    fun describeCandidates(nativeDir: Path?): String = try {
        val driver = loadDriver()
        val cuInit = fn(driver, "cuInit")
        check(driver, cuInit.invokeInt(arrayOf<Any?>(0)), "cuInit")
        val count = IntByReference()
        check(driver, fn(driver, "cuDeviceGetCount").invokeInt(arrayOf<Any?>(count)), "cuDeviceGetCount")
        val out = ArrayList<String>()
        for (i in 0 until count.value) out += queryDevice(driver, i).description()
        val compiler = try { loadNvrtc(nativeDir).name } catch (t: Throwable) { "NVRTC unavailable: ${shortMessage(t)}" }
        "$out; $compiler"
    } catch (t: Throwable) {
        "CUDA unavailable: ${shortMessage(t)}"
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readResource(path: String): String {
        val input = CudaSupport::class.java.getResourceAsStream(path)
            ?: throw IOException("missing CUDA kernel resource $path")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n > 0) out.write(buffer, 0, n)
            }
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun compile(probe: Probe, source: String, sourceName: String): ByteArray {
        val programRef = PointerByReference()
        val create = fn(probe.nvrtc, "nvrtcCreateProgram")
        var code = create.invokeInt(arrayOf<Any?>(programRef, source, sourceName, 0, null, null))
        checkNvrtc(code, "nvrtcCreateProgram")
        val program = programRef.value ?: throw IllegalStateException("nvrtcCreateProgram returned null")
        try {
            val arch = "--gpu-architecture=compute_${probe.device.major}${probe.device.minor}"
            val options = CStringArray(arrayOf(arch, "--std=c++11"))
            code = fn(probe.nvrtc, "nvrtcCompileProgram").invokeInt(arrayOf<Any?>(program, options.count, options.table))
            val log = programLog(probe.nvrtc, program)
            if (code != 0) throw IllegalStateException("NVRTC compile failed code=$code${if (log.isEmpty()) "" else " log=$log"}")
            val size = LongByReference()
            checkNvrtc(fn(probe.nvrtc, "nvrtcGetPTXSize").invokeInt(arrayOf<Any?>(program, size)), "nvrtcGetPTXSize")
            val n = size.value
            if (n <= 1L || n > 64L * 1024L * 1024L) throw IllegalStateException("invalid NVRTC PTX size $n")
            val ptx = Memory(n)
            checkNvrtc(fn(probe.nvrtc, "nvrtcGetPTX").invokeInt(arrayOf<Any?>(program, ptx)), "nvrtcGetPTX")
            return ptx.getByteArray(0L, n.toInt())
        } finally {
            val destroy = PointerByReference(program)
            try { fn(probe.nvrtc, "nvrtcDestroyProgram").invokeInt(arrayOf<Any?>(destroy)) } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun createContext(probe: Probe, ptx: ByteArray, kernelName: String): Context {
        val create = fnAny(probe.driver, "cuCtxCreate_v2", "cuCtxCreate")
        val contextRef = PointerByReference()
        check(probe.driver, create.invokeInt(arrayOf<Any?>(contextRef, 0, probe.device.handle)), "cuCtxCreate")
        val ctx = contextRef.value ?: throw IllegalStateException("cuCtxCreate returned null")
        val out = Context(probe.driver, probe.device, ctx)
        var ok = false
        try {
            out.makeCurrent()
            val image = Memory(ptx.size + 1L)
            image.clear()
            image.write(0L, ptx, 0, ptx.size)
            val moduleRef = PointerByReference()
            check(probe.driver, fn(probe.driver, "cuModuleLoadData").invokeInt(arrayOf<Any?>(moduleRef, image)), "cuModuleLoadData")
            out.module = moduleRef.value
            val functionRef = PointerByReference()
            check(probe.driver, fn(probe.driver, "cuModuleGetFunction").invokeInt(arrayOf<Any?>(functionRef, out.module, kernelName)), "cuModuleGetFunction($kernelName)")
            out.kernel = functionRef.value
            ok = true
            return out
        } finally {
            if (!ok) out.close()
        }
    }

    class Probe(
        @JvmField val driver: NativeLibrary,
        @JvmField val nvrtc: NativeLibrary,
        @JvmField val device: Device,
        @JvmField val devices: List<Device>
    ) {
        fun description(): String = "CUDA GPU: ${device.description()}; NVRTC=${nvrtc.name}"
    }

    class Device(
        @JvmField val ordinal: Int,
        @JvmField val handle: Int,
        @JvmField val major: Int,
        @JvmField val minor: Int,
        @JvmField val memory: Long,
        @JvmField val driverVersion: Int,
        @JvmField val name: String
    ) {
        fun description(): String = "[$ordinal] $name cc=$major.$minor vram=${memory / (1024L * 1024L)}MiB driver=$driverVersion"
    }

    class Context(
        @JvmField val driver: NativeLibrary,
        @JvmField val device: Device,
        @JvmField val context: Pointer
    ) : AutoCloseable {
        @JvmField var module: Pointer? = null
        @JvmField var kernel: Pointer? = null
        private var closed = false
        private var staging: Memory? = null
        private var stagingBytes = 0L

        fun makeCurrent() {
            if (closed) throw IllegalStateException("CUDA context closed")
            check(driver, fn(driver, "cuCtxSetCurrent").invokeInt(arrayOf<Any?>(context)), "cuCtxSetCurrent")
        }

        fun alloc(bytes: Long): Long {
            makeCurrent()
            val pointer = LongByReference()
            check(driver, fnAny(driver, "cuMemAlloc_v2", "cuMemAlloc").invokeInt(arrayOf<Any?>(pointer, bytes)), "cuMemAlloc")
            return pointer.value
        }

        fun free(ptr: Long) {
            if (ptr == 0L) return
            makeCurrent()
            check(driver, fnAny(driver, "cuMemFree_v2", "cuMemFree").invokeInt(arrayOf<Any?>(ptr)), "cuMemFree")
        }

        fun upload(ptr: Long, data: IntArray) {
            val bytes = data.size.toLong() * 4L
            val memory = staging(bytes)
            memory.write(0L, data, 0, data.size)
            copyHtoD(ptr, memory, bytes)
        }

        fun upload(ptr: Long, data: FloatArray) {
            val bytes = data.size.toLong() * 4L
            val memory = staging(bytes)
            memory.write(0L, data, 0, data.size)
            copyHtoD(ptr, memory, bytes)
        }

        fun uploadZeros(ptr: Long, bytes: Long) {
            val memory = staging(bytes)
            memory.setMemory(0L, bytes, 0.toByte())
            copyHtoD(ptr, memory, bytes)
        }

        fun download(ptr: Long, out: FloatArray) {
            val bytes = out.size.toLong() * 4L
            val memory = staging(bytes)
            copyDtoH(memory, ptr, bytes)
            memory.read(0L, out, 0, out.size)
        }

        private fun staging(bytes: Long): Memory {
            if (bytes <= 0L) throw IllegalArgumentException("staging bytes must be positive")
            if (staging == null || stagingBytes < bytes) {
                val grown = if (stagingBytes <= 0L) bytes else maxOf(bytes, minOf(Long.MAX_VALUE / 2L, stagingBytes * 2L))
                staging = Memory(grown)
                stagingBytes = grown
            }
            return requireNotNull(staging)
        }

        private fun copyHtoD(dst: Long, src: Pointer, bytes: Long) {
            makeCurrent()
            check(driver, fnAny(driver, "cuMemcpyHtoD_v2", "cuMemcpyHtoD").invokeInt(arrayOf<Any?>(dst, src, bytes)), "cuMemcpyHtoD")
        }

        private fun copyDtoH(dst: Pointer, src: Long, bytes: Long) {
            makeCurrent()
            check(driver, fnAny(driver, "cuMemcpyDtoH_v2", "cuMemcpyDtoH").invokeInt(arrayOf<Any?>(dst, src, bytes)), "cuMemcpyDtoH")
        }

        fun launch(workItems: Int, args: KernelArgs) {
            if (workItems < 1) return
            makeCurrent()
            val block = 128
            val grid = (workItems + block - 1) / block
            check(
                driver,
                fn(driver, "cuLaunchKernel").invokeInt(arrayOf<Any?>(kernel, grid, 1, 1, block, 1, 1, 0, null, args.table, null)),
                "cuLaunchKernel"
            )
        }

        fun sync() {
            makeCurrent()
            check(driver, fn(driver, "cuCtxSynchronize").invokeInt(emptyArray()), "cuCtxSynchronize")
        }

        override fun close() {
            if (closed) return
            closed = true
            try { fn(driver, "cuCtxSetCurrent").invokeInt(arrayOf<Any?>(context)) } catch (_: Throwable) {}
            module?.let { try { fn(driver, "cuModuleUnload").invokeInt(arrayOf<Any?>(it)) } catch (_: Throwable) {} }
            try { fnAny(driver, "cuCtxDestroy_v2", "cuCtxDestroy").invokeInt(arrayOf<Any?>(context)) } catch (_: Throwable) {}
            module = null; kernel = null; staging = null; stagingBytes = 0L
        }
    }

    class KernelArgs(count: Int) {
        @JvmField val table = Memory(Native.POINTER_SIZE.toLong() * count.toLong())
        private val values = ArrayList<Memory>()
        private var index = 0

        init { table.clear() }

        fun ptr(value: Long): KernelArgs {
            val memory = Memory(8L); memory.setLong(0L, value); put(memory); return this
        }
        fun i32(value: Int): KernelArgs {
            val memory = Memory(4L); memory.setInt(0L, value); put(memory); return this
        }
        fun f32(value: Float): KernelArgs {
            val memory = Memory(4L); memory.setFloat(0L, value); put(memory); return this
        }
        private fun put(memory: Memory) {
            values += memory
            table.setPointer(index.toLong() * Native.POINTER_SIZE.toLong(), memory)
            index++
        }
    }

    private fun queryDevice(driver: NativeLibrary, ordinal: Int): Device {
        val dev = IntByReference()
        check(driver, fn(driver, "cuDeviceGet").invokeInt(arrayOf<Any?>(dev, ordinal)), "cuDeviceGet")
        val handle = dev.value
        val nameMem = Memory(256L); nameMem.clear()
        check(driver, fn(driver, "cuDeviceGetName").invokeInt(arrayOf<Any?>(nameMem, 255, handle)), "cuDeviceGetName")
        val major = IntByReference(); val minor = IntByReference()
        check(driver, fn(driver, "cuDeviceComputeCapability").invokeInt(arrayOf<Any?>(major, minor, handle)), "cuDeviceComputeCapability")
        val mem = LongByReference()
        check(driver, fnAny(driver, "cuDeviceTotalMem_v2", "cuDeviceTotalMem").invokeInt(arrayOf<Any?>(mem, handle)), "cuDeviceTotalMem")
        val version = IntByReference()
        check(driver, fn(driver, "cuDriverGetVersion").invokeInt(arrayOf<Any?>(version)), "cuDriverGetVersion")
        return Device(ordinal, handle, major.value, minor.value, mem.value, version.value, nameMem.getString(0L))
    }

    private fun selectDevice(devices: List<Device>, override: String?): Device {
        if (!override.isNullOrBlank()) {
            val q = override.trim().lowercase(Locale.ROOT)
            q.toIntOrNull()?.let { ordinal -> devices.firstOrNull { it.ordinal == ordinal }?.let { return it } }
            devices.firstOrNull { it.name.lowercase(Locale.ROOT).contains(q) }?.let { return it }
            throw IllegalArgumentException("CUDA device override did not match: $override")
        }
        return devices.sortedWith(compareByDescending<Device> { it.major * 10 + it.minor }
            .thenByDescending { it.memory }.thenBy { it.ordinal }).first()
    }

    private fun loadDriver(): NativeLibrary {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return NativeLibrary.getInstance(if (os.contains("win")) "nvcuda" else "cuda")
    }

    private fun loadNvrtc(nativeDir: Path?): NativeLibrary {
        val attempts = ArrayList<String>()
        preloadNvrtcBuiltins(nativeDir)
        if (nativeDir != null && Files.isDirectory(nativeDir)) {
            try {
                val dlls = ArrayList<Path>()
                Files.newDirectoryStream(nativeDir).use { stream ->
                    for (path in stream) {
                        val name = path.fileName.toString().lowercase(Locale.ROOT)
                        if ((name.startsWith("nvrtc64_") && name.endsWith(".dll")) || name.startsWith("libnvrtc.so")) dlls.add(path)
                    }
                }
                dlls.sortDescending()
                for (path in dlls) attempts += path.toAbsolutePath().toString()
            } catch (_: IOException) {}
        }
        val explicit = System.getProperty("acousticshaders.cuda.nvrtc", "").trim()
        if (explicit.isNotEmpty()) attempts.add(0, explicit)
        val cudaPath = System.getenv("CUDA_PATH")
        if (!cudaPath.isNullOrBlank()) {
            val bin = Paths.get(cudaPath, "bin")
            if (Files.isDirectory(bin)) {
                try {
                    Files.newDirectoryStream(bin, "nvrtc64_*.dll").use { stream -> for (path in stream) attempts += path.toAbsolutePath().toString() }
                } catch (_: IOException) {}
            }
        }
        attempts.addAll(arrayOf(
            "nvrtc64_130_0", "nvrtc64_129_0", "nvrtc64_128_0", "nvrtc64_127_0", "nvrtc64_126_0", "nvrtc64_125_0",
            "nvrtc64_124_0", "nvrtc64_123_0", "nvrtc64_122_0", "nvrtc64_121_0", "nvrtc64_120_0", "nvrtc64_118_0",
            "nvrtc64_117_0", "nvrtc64_116_0", "nvrtc64_115_0", "nvrtc64_114_0", "nvrtc64_113_0", "nvrtc64_112_0", "nvrtc"
        ))
        var last: Throwable? = null
        for (attempt in attempts) {
            try { return NativeLibrary.getInstance(attempt) } catch (t: Throwable) { last = t }
        }
        throw IllegalStateException("NVRTC library not found${if (last == null) "" else ": ${shortMessage(last)}"}")
    }

    private fun preloadNvrtcBuiltins(nativeDir: Path?) {
        val candidates = ArrayList<Path>()
        if (nativeDir != null && Files.isDirectory(nativeDir)) collectBuiltins(nativeDir, candidates)
        val cudaPath = System.getenv("CUDA_PATH")
        if (!cudaPath.isNullOrBlank()) {
            val bin = Paths.get(cudaPath, "bin")
            if (Files.isDirectory(bin)) collectBuiltins(bin, candidates)
        }
        candidates.sortDescending()
        for (path in candidates) {
            try {
                PINNED_NATIVE_LIBRARIES += NativeLibrary.getInstance(path.toAbsolutePath().toString())
                return
            } catch (_: Throwable) {}
        }
    }

    private fun collectBuiltins(dir: Path, out: MutableList<Path>) {
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (path in stream) {
                    val name = path.fileName.toString().lowercase(Locale.ROOT)
                    if ((name.startsWith("nvrtc-builtins64_") && name.endsWith(".dll")) || name.startsWith("libnvrtc-builtins.so")) out.add(path)
                }
            }
        } catch (_: IOException) {}
    }

    private fun programLog(lib: NativeLibrary, program: Pointer): String {
        return try {
            val size = LongByReference()
            if (fn(lib, "nvrtcGetProgramLogSize").invokeInt(arrayOf<Any?>(program, size)) != 0) return ""
            val n = size.value
            if (n <= 1L || n > 4L * 1024L * 1024L) return ""
            val memory = Memory(n)
            if (fn(lib, "nvrtcGetProgramLog").invokeInt(arrayOf<Any?>(program, memory)) != 0) return ""
            memory.getString(0L).trim()
        } catch (_: Throwable) { "" }
    }

    private fun fn(lib: NativeLibrary, name: String): Function = lib.getFunction(name)

    private fun fnAny(lib: NativeLibrary, vararg names: String): Function {
        var last: Throwable? = null
        for (name in names) {
            try { return lib.getFunction(name) } catch (t: Throwable) { last = t }
        }
        throw IllegalStateException("native function not found: ${names.contentToString()}", last)
    }

    private fun check(driver: NativeLibrary, code: Int, op: String) {
        if (code == CUDA_SUCCESS) return
        var detail = ""
        try {
            val pointer = PointerByReference()
            if (fn(driver, "cuGetErrorName").invokeInt(arrayOf<Any?>(code, pointer)) == 0 && pointer.value != null) {
                detail = requireNotNull(pointer.value).getString(0L)
            }
        } catch (_: Throwable) {}
        throw IllegalStateException("$op failed CUDA code=$code${if (detail.isEmpty()) "" else " $detail"}")
    }

    private fun checkNvrtc(code: Int, op: String) {
        if (code != 0) throw IllegalStateException("$op failed NVRTC code=$code")
    }

    @JvmStatic
    fun shortMessage(t: Throwable): String = t.message ?: t.javaClass.simpleName

    private class CStringArray(values: Array<String>) {
        val count = values.size
        val table = Memory(maxOf(1, count).toLong() * Native.POINTER_SIZE.toLong())
        val keep = ArrayList<Memory>()
        init {
            table.clear()
            values.forEachIndexed { index, value ->
                val utf8 = (value + "\u0000").toByteArray(StandardCharsets.UTF_8)
                val memory = Memory(utf8.size.toLong())
                memory.write(0L, utf8, 0, utf8.size)
                keep += memory
                table.setPointer(index.toLong() * Native.POINTER_SIZE.toLong(), memory)
            }
        }
    }
}
