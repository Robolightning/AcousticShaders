package dev.acoustic.mc1122.forge

import dev.acoustic.api.material.AcousticMaterials
import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticBox
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.api.scene.AcousticShape
import dev.acoustic.api.scene.AcousticVoxel
import dev.acoustic.core.compute.GeometricExternalBackend
import dev.acoustic.core.passes.EnvironmentRayPass
import dev.acoustic.core.passes.ReflectionField
import dev.acoustic.core.passes.ReflectionSample
import dev.acoustic.core.scene.ImmutableVoxelSnapshot
import dev.acoustic.core.trace.VoxelRaycast
import java.nio.file.Path

/** Genuine NVIDIA CUDA Driver API batched voxel ray accelerator compiled by NVRTC. */
internal class CudaGeometricBackend(private val nativeDir: Path) : GeometricExternalBackend {
    private var probed = false
    private var available = false
    private var disabled = false
    private var validated = false
    private var description = "CUDA ray backend not probed"
    private var lastFailure = ""
    private var traceCount = 0L
    private var failureCount = 0L
    private var lastTraceNanos = 0L
    private var probe: CudaSupport.Probe? = null
    private var context: CudaSupport.Context? = null
    private var cachedScene: ImmutableVoxelSnapshot? = null
    private var cachedRevision = Long.MIN_VALUE
    private var sceneShapeOffset = 0L
    private var sceneShapeCount = 0L
    private var sceneBoxes = 0L
    private var sceneScatter = 0L
    private var sceneReflect = 0L
    private var tracePaths = 0L
    private var traceEnergies = 0L
    private var initialDirs = 0L
    private var diffuseDirs = 0L
    private var traceSlots = 0
    private var directionRays = 0
    private var directionBounces = 0

    override fun id(): String = "cuda"
    override fun autoPriority(): Int = 200
    @Synchronized override fun description(): String { ensureProbeQuietly(); return description }
    @Synchronized override fun available(): Boolean { ensureProbeQuietly(); return available && !disabled }

    override fun supports(scene: AcousticScene, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): Boolean {
        if (scene.containsNonAirMedia() || scene !is ImmutableVoxelSnapshot) return false
        val cells = scene.sizeX().toLong() * scene.sizeY().toLong() * scene.sizeZ().toLong()
        val slots = rays.toLong() * bounces.toLong()
        return rays > 0 && rays <= 65536 && bounces > 0 && bounces <= 32 && cells > 0L && cells <= 2_000_000L &&
            slots <= 1_000_000L && maxDistance > 0.0 && maxDistance <= 256.0 && minEnergy >= 0.0
    }

    @Synchronized
    override fun preferredForAuto(scene: AcousticScene, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): Boolean {
        if (!supports(scene, rays, bounces, maxDistance, minEnergy)) return false
        val uploaded = scene === cachedScene && scene.revision() == cachedRevision && sceneShapeOffset != 0L
        return rays.toLong() * bounces.toLong() >= if (uploaded) 768L else 2048L
    }

    @Synchronized override fun traceCount(): Long = traceCount
    @Synchronized override fun failureCount(): Long = failureCount
    @Synchronized override fun lastTraceMillis(): Double = if (lastTraceNanos <= 0L) Double.NaN else lastTraceNanos / 1_000_000.0
    @Synchronized override fun lastFailure(): String = lastFailure

    @Synchronized
    @Throws(Exception::class)
    override fun trace(scene: AcousticScene, listener: Vec3, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): ReflectionField {
        ensureProbe()
        if (!available || disabled) throw IllegalStateException(description)
        if (!supports(scene, rays, bounces, maxDistance, minEnergy)) throw IllegalArgumentException("geometric problem not supported by CUDA backend")
        val started = System.nanoTime()
        var failed = false
        try {
            ensureContext()
            if (!validated) validateKernel()
            val snapshot = scene as ImmutableVoxelSnapshot
            ensureScene(snapshot)
            val raw = runDeviceTrace(snapshot, listener, rays, bounces, maxDistance, minEnergy)
            val paths = raw[0]; val energies = raw[1]
            val samples = ArrayList<ReflectionSample>()
            for (ray in 0 until rays) {
                val initial = EnvironmentRayPass.fibonacciDirection(ray, rays)
                for (bounce in 0 until bounces) {
                    val slot = ray * bounces + bounce
                    val distance = paths[slot]
                    if (distance < 0f) break
                    val energy = FloatArray(FrequencyBands.COUNT)
                    System.arraycopy(energies, slot * FrequencyBands.COUNT, energy, 0, FrequencyBands.COUNT)
                    samples += ReflectionSample(distance.toDouble(), bounce + 1, initial, energy)
                }
            }
            traceCount++
            lastTraceNanos = System.nanoTime() - started
            lastFailure = ""
            return ReflectionField(rays, samples)
        } catch (t: Throwable) {
            failureCount++
            lastTraceNanos = System.nanoTime() - started
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

    private fun runDeviceTrace(snapshot: ImmutableVoxelSnapshot, listener: Vec3, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): Array<FloatArray> {
        ensureScene(snapshot)
        ensureTraceBuffers(rays * bounces)
        ensureDirections(rays, bounces)
        val pathInit = FloatArray(rays * bounces) { -1f }
        val ctx = requireNotNull(context)
        ctx.upload(tracePaths, pathInit)
        val args = CudaSupport.KernelArgs(22)
            .ptr(sceneShapeOffset).ptr(sceneShapeCount).ptr(sceneBoxes).ptr(sceneScatter).ptr(sceneReflect)
            .ptr(tracePaths).ptr(traceEnergies).ptr(initialDirs).ptr(diffuseDirs)
            .i32(snapshot.minX()).i32(snapshot.minY()).i32(snapshot.minZ())
            .i32(snapshot.sizeX()).i32(snapshot.sizeY()).i32(snapshot.sizeZ())
            .f32(listener.x.toFloat()).f32(listener.y.toFloat()).f32(listener.z.toFloat())
            .i32(rays).i32(bounces).f32(maxDistance.toFloat()).f32(minEnergy.toFloat())
        ctx.launch(rays, args)
        ctx.sync()
        val paths = FloatArray(rays * bounces)
        val energies = FloatArray(rays * bounces * FrequencyBands.COUNT)
        ctx.download(tracePaths, paths)
        ctx.download(traceEnergies, energies)
        return arrayOf(paths, energies)
    }

    /** Always validate CUDA DDA/material semantics on known geometry before trusting user-scene output. */
    private fun validateKernel() {
        val n = 9
        val air = AcousticVoxel(false, AcousticMaterials.AIR)
        val stone = AcousticVoxel(true, AcousticMaterials.STONE)
        val halfStone = AcousticVoxel(true, AcousticMaterials.STONE, AcousticShape.of(AcousticBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)))
        val voxels = Array(n * n * n) { air }
        for (y in 0 until n) for (z in 0 until n) for (x in 0 until n) {
            if (x == 0 || y == 0 || z == 0 || x == n - 1 || y == n - 1 || z == n - 1) {
                voxels[(y * n + z) * n + x] = if (x == n - 1 && y in 3..5) halfStone else stone
            }
        }
        val scene = ImmutableVoxelSnapshot(0, 0, 0, n, n, n, air, voxels, -911L)
        val listener = Vec3(4.5, 4.5, 4.5)
        val rays = 32; val bounces = 2; val maxDistance = 12.0
        val raw = runDeviceTrace(scene, listener, rays, bounces, maxDistance, 1.0e-5)
        validateFirstHits(scene, listener, rays, bounces, maxDistance, raw[0], raw[1])
        if (!validated) throw IllegalStateException("CUDA ray self-test did not validate any reference hits")
    }

    private fun validateFirstHits(scene: ImmutableVoxelSnapshot, listener: Vec3, rays: Int, bounces: Int, maxDistance: Double, paths: FloatArray, energies: FloatArray) {
        val checks = minOf(16, rays)
        var hitChecks = 0
        for (c in 0 until checks) {
            val ray = if (checks == 1) 0 else ((c.toLong() * (rays - 1).toLong()) / (checks - 1).toLong()).toInt()
            val direction = EnvironmentRayPass.fibonacciDirection(ray, rays)
            val cpu = VoxelRaycast.firstSolid(scene, listener, direction, maxDistance)
            val slot = ray * bounces
            val gpu = paths[slot]
            if (cpu == null) {
                if (gpu >= 0f) throw IllegalStateException("CUDA ray self-test false hit ray=$ray gpu=$gpu")
                continue
            }
            hitChecks++
            if (gpu < 0f) throw IllegalStateException("CUDA ray self-test missed CPU hit ray=$ray cpu=${cpu.distance()}")
            val error = kotlin.math.abs(gpu.toDouble() - cpu.distance())
            val tolerance = maxOf(0.003, kotlin.math.abs(cpu.distance()) * 3e-4)
            if (error > tolerance) throw IllegalStateException("CUDA ray self-test distance mismatch ray=$ray cpu=${cpu.distance()} gpu=$gpu")
            for (band in 0 until FrequencyBands.COUNT) {
                val expected = cpu.voxel().material().reflection(band).toDouble()
                val actual = energies[slot * FrequencyBands.COUNT + band].toDouble()
                if (!actual.isFinite() || kotlin.math.abs(actual - expected) > 3e-4) {
                    throw IllegalStateException("CUDA ray self-test energy mismatch ray=$ray band=$band cpu=$expected gpu=$actual")
                }
            }
        }
        if (hitChecks > 0) {
            validated = true
            description = baseDescription(description) + "; self-test=pass"
        }
    }

    private fun ensureScene(scene: ImmutableVoxelSnapshot) {
        if (cachedScene === scene && cachedRevision == scene.revision() && sceneShapeOffset != 0L) return
        releaseScene()
        val cells = scene.sizeX() * scene.sizeY() * scene.sizeZ()
        var totalBoxes = 0
        for (y in 0 until scene.sizeY()) for (z in 0 until scene.sizeZ()) for (x in 0 until scene.sizeX()) {
            totalBoxes += scene.voxelAt(scene.minX() + x, scene.minY() + y, scene.minZ() + z).shape().boxCount()
        }
        if (totalBoxes > 4_000_000) throw IllegalStateException("CUDA acoustic shape table too large: $totalBoxes")
        val offsets = IntArray(cells); val counts = IntArray(cells)
        val boxes = FloatArray(maxOf(6, totalBoxes * 6)); val scatter = FloatArray(cells); val reflect = FloatArray(cells * FrequencyBands.COUNT)
        var boxCursor = 0
        for (y in 0 until scene.sizeY()) for (z in 0 until scene.sizeZ()) for (x in 0 until scene.sizeX()) {
            val i = (y * scene.sizeZ() + z) * scene.sizeX() + x
            val voxel = scene.voxelAt(scene.minX() + x, scene.minY() + y, scene.minZ() + z)
            offsets[i] = boxCursor; counts[i] = voxel.shape().boxCount()
            for (q in 0 until voxel.shape().boxCount()) {
                val box = voxel.shape().box(q); val k = boxCursor * 6
                boxes[k] = box.minX.toFloat(); boxes[k + 1] = box.minY.toFloat(); boxes[k + 2] = box.minZ.toFloat()
                boxes[k + 3] = box.maxX.toFloat(); boxes[k + 4] = box.maxY.toFloat(); boxes[k + 5] = box.maxZ.toFloat(); boxCursor++
            }
            scatter[i] = if (voxel.solid()) voxel.material().scattering() else 0f
            for (band in 0 until FrequencyBands.COUNT) reflect[i * FrequencyBands.COUNT + band] = if (voxel.solid()) voxel.material().reflection(band) else 1f
        }
        val ctx = requireNotNull(context)
        var a = 0L; var b = 0L; var c = 0L; var d = 0L; var e = 0L
        try {
            a = ctx.alloc(cells.toLong() * 4L); b = ctx.alloc(cells.toLong() * 4L); c = ctx.alloc(maxOf(6, totalBoxes * 6).toLong() * 4L)
            d = ctx.alloc(cells.toLong() * 4L); e = ctx.alloc(cells.toLong() * FrequencyBands.COUNT.toLong() * 4L)
            ctx.upload(a, offsets); ctx.upload(b, counts); ctx.upload(c, boxes); ctx.upload(d, scatter); ctx.upload(e, reflect)
            sceneShapeOffset = a; sceneShapeCount = b; sceneBoxes = c; sceneScatter = d; sceneReflect = e
            cachedScene = scene; cachedRevision = scene.revision()
        } catch (t: Throwable) {
            safeFree(a); safeFree(b); safeFree(c); safeFree(d); safeFree(e); throw t
        }
    }

    private fun ensureTraceBuffers(slots: Int) {
        if (tracePaths != 0L && traceSlots >= slots) return
        releaseTraceBuffers()
        val ctx = requireNotNull(context)
        tracePaths = ctx.alloc(slots.toLong() * 4L)
        traceEnergies = ctx.alloc(slots.toLong() * FrequencyBands.COUNT.toLong() * 4L)
        traceSlots = slots
    }

    private fun ensureDirections(rays: Int, bounces: Int) {
        if (initialDirs != 0L && directionRays == rays && directionBounces == bounces) return
        releaseDirectionBuffers()
        val initial = FloatArray(rays * 3); val diffuse = FloatArray(rays * bounces * 3)
        for (ray in 0 until rays) {
            val direction = EnvironmentRayPass.fibonacciDirection(ray, rays)
            initial[ray * 3] = direction.x.toFloat(); initial[ray * 3 + 1] = direction.y.toFloat(); initial[ray * 3 + 2] = direction.z.toFloat()
            for (bounce in 1..bounces) {
                val d = unorientedDiffuse(ray, bounce); val offset = (ray * bounces + bounce - 1) * 3
                diffuse[offset] = d.x.toFloat(); diffuse[offset + 1] = d.y.toFloat(); diffuse[offset + 2] = d.z.toFloat()
            }
        }
        val ctx = requireNotNull(context)
        initialDirs = ctx.alloc(initial.size.toLong() * 4L); diffuseDirs = ctx.alloc(diffuse.size.toLong() * 4L)
        ctx.upload(initialDirs, initial); ctx.upload(diffuseDirs, diffuse)
        directionRays = rays; directionBounces = bounces
    }

    private fun ensureProbeQuietly() {
        if (probed) return
        try { ensureProbe() } catch (t: Throwable) {
            available = false; disabled = true; lastFailure = CudaSupport.shortMessage(t); description = "CUDA ray backend unavailable: $lastFailure"
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
        description = requireNotNull(probe).description() + "; ray kernel=NVRTC lazy"
    }

    @Throws(Exception::class)
    private fun ensureContext() {
        if (context != null) return
        val p = requireNotNull(probe)
        val ptx = CudaSupport.compile(p, CudaSupport.readResource("/assets/acousticshaders/cuda/rays.cu"), "acoustic_rays.cu")
        context = CudaSupport.createContext(p, ptx, "acoustic_rays")
    }

    private fun releaseScene() {
        safeFree(sceneShapeOffset); safeFree(sceneShapeCount); safeFree(sceneBoxes); safeFree(sceneScatter); safeFree(sceneReflect)
        sceneShapeOffset = 0L; sceneShapeCount = 0L; sceneBoxes = 0L; sceneScatter = 0L; sceneReflect = 0L
        cachedScene = null; cachedRevision = Long.MIN_VALUE
    }
    private fun releaseTraceBuffers() { safeFree(tracePaths); safeFree(traceEnergies); tracePaths = 0L; traceEnergies = 0L; traceSlots = 0 }
    private fun releaseDirectionBuffers() { safeFree(initialDirs); safeFree(diffuseDirs); initialDirs = 0L; diffuseDirs = 0L; directionRays = 0; directionBounces = 0 }
    private fun safeFree(pointer: Long) { if (pointer != 0L && context != null) try { requireNotNull(context).free(pointer) } catch (_: Throwable) {} }
    private fun releasePersistent() { releaseTraceBuffers(); releaseDirectionBuffers(); releaseScene(); context?.let { try { it.close() } catch (_: Throwable) {} }; context = null }
    @Synchronized override fun close() { disabled = true; available = false; releasePersistent() }

    companion object {
        private const val SEED_A = -7046029254386353131L // 0x9E3779B97F4A7C15
        private const val SEED_B = -4417276706812531889L // 0xC2B2AE3D27D4EB4F
        private fun unorientedDiffuse(rayIndex: Int, bounce: Int): Vec3 {
            var seed = (rayIndex + 1L) * SEED_A + (bounce + 11L) * SEED_B
            seed = seed xor (seed ushr 29)
            val u = (seed and 0xffffffL).toDouble() / 0x1000000L.toDouble()
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val v = (seed and 0xffffffL).toDouble() / 0x1000000L.toDouble()
            val z = u; val r = kotlin.math.sqrt(maxOf(0.0, 1.0 - z * z)); val phi = 2.0 * Math.PI * v
            return Vec3(r * kotlin.math.cos(phi), z, r * kotlin.math.sin(phi)).normalize()
        }
        private fun baseDescription(value: String): String { val i = value.indexOf("; disabled after runtime failure:"); return if (i < 0) value else value.substring(0, i) }
    }
}
