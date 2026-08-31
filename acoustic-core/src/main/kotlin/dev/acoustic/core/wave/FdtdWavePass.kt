package dev.acoustic.core.wave

import dev.acoustic.api.environment.AcousticEnvironment
import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.pipeline.AcceleratedPass
import dev.acoustic.api.pipeline.ParallelWorkExecutor
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.PhasedParallelWorkExecutor
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.api.pipeline.RuntimeParallelPass
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.compute.FdtdBackendRegistry
import dev.acoustic.core.compute.FdtdExternalBackend
import dev.acoustic.core.compute.FdtdProblem
import dev.acoustic.core.passes.StandardResources
import dev.acoustic.core.passes.WaveFieldResult
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import dev.acoustic.core.trace.SceneMediumGeometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bounded 3-D scalar acoustic FDTD pass. The pass can run scalar CPU, true
 * runtime-owned multicore CPU, or an optional external accelerator such as OpenCL.
 */
class FdtdWavePass(
    private val radiusMeters: Double,
    private val subdivisions: Int,
    private val maxSteps: Int,
    private val cflSafety: Double,
    computeBackend: String?
) : RuntimeParallelPass, AcceleratedPass {
    private val computeBackend: String = (computeBackend ?: "AUTO").trim().uppercase(Locale.ROOT)

    constructor(radiusMeters: Double, subdivisions: Int, maxSteps: Int) :
        this(radiusMeters, subdivisions, maxSteps, 0.45, "AUTO")

    constructor(radiusMeters: Double, subdivisions: Int, maxSteps: Int, cflSafety: Double) :
        this(radiusMeters, subdivisions, maxSteps, cflSafety, "AUTO")

    init {
        require(radiusMeters > 0.0 && radiusMeters <= 32.0) { "radiusMeters must be within (0,32]" }
        require(subdivisions in 1..4) { "subdivisions must be within [1,4]" }
        require(maxSteps in 8..8192) { "maxSteps must be within [8,8192]" }
        require(cflSafety > 0.0 && cflSafety < 1.0) { "cflSafety" }
    }

    override fun id(): String = "standard.wave_low_frequency"

    override fun reads(): Set<ResourceKey<*>> {
        val resources = LinkedHashSet<ResourceKey<*>>()
        resources.add(StandardResources.SCENE)
        resources.add(StandardResources.SOURCE_POSITION)
        resources.add(StandardResources.LISTENER_POSITION)
        return Collections.unmodifiableSet(resources)
    }

    override fun optionalReads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.ENVIRONMENT)
    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.WAVE_FIELD)

    override fun execute(context: PassContext) {
        solveAndPublish(context, null, true)
    }

    override fun executeParallel(context: PassContext, parallel: ParallelWorkExecutor) {
        solveAndPublish(context, parallel, false)
    }

    override fun tryExecuteAccelerated(context: PassContext): String? {
        if (!wantsExternal()) return null
        if ((computeBackend == "AUTO" || computeBackend == "GPU") && FdtdBackendRegistry.available().isEmpty()) return null
        if (computeBackend != "AUTO" && computeBackend != "GPU" &&
            FdtdBackendRegistry.find(computeBackend.lowercase(Locale.ROOT)) == null) return null

        val scene = context.require(StandardResources.SCENE)
        val source = context.require(StandardResources.SOURCE_POSITION)
        val listener = context.require(StandardResources.LISTENER_POSITION)
        val environment = context.get(StandardResources.ENVIRONMENT) ?: AcousticEnvironment.STANDARD
        val prepared = prepare(scene, source, listener, environment)
        val backend = selectExternal(prepared.problem)
        if (backend == null || !backend.supports(prepared.problem)) return null
        return try {
            val response = backend.solve(prepared.problem)
            publish(context, prepared, response, backend.id())
            backend.id()
        } catch (_: Throwable) {
            null
        }
    }

    private fun solveAndPublish(context: PassContext, parallel: ParallelWorkExecutor?, allowExternal: Boolean) {
        val scene = context.require(StandardResources.SCENE)
        val source = context.require(StandardResources.SOURCE_POSITION)
        val listener = context.require(StandardResources.LISTENER_POSITION)
        val environment = context.get(StandardResources.ENVIRONMENT) ?: AcousticEnvironment.STANDARD
        val prepared = prepare(scene, source, listener, environment)

        var response: FloatArray? = null
        var backendId: String? = null
        if (allowExternal && wantsExternal()) {
            val backend = selectExternal(prepared.problem)
            if (backend != null && backend.supports(prepared.problem)) {
                try {
                    response = backend.solve(prepared.problem)
                    backendId = backend.id()
                } catch (_: Throwable) {
                    response = null
                    backendId = null
                }
            }
        }

        if (response == null) {
            if (parallel != null && parallel.workerCount() > 1 && computeBackend != "CPU_SCALAR") {
                response = solveCpuParallel(prepared.problem, parallel)
                backendId = "cpu-parallel[${parallel.workerCount()}]"
            } else {
                response = solveCpuScalar(prepared.problem)
                backendId = "cpu-scalar"
            }
        }
        publish(context, prepared, response, backendId!!)
    }

    private fun wantsExternal(): Boolean =
        computeBackend != "CPU" && computeBackend != "CPU_PARALLEL" && computeBackend != "CPU_SCALAR"

    private fun selectExternal(problem: FdtdProblem): FdtdExternalBackend? = when (computeBackend) {
        "AUTO" -> FdtdBackendRegistry.firstPreferred(problem)
        "GPU" -> FdtdBackendRegistry.firstSupported(problem)
        else -> FdtdBackendRegistry.find(computeBackend.lowercase(Locale.ROOT))
    }

    private fun prepare(scene: AcousticScene, source: Vec3, listener: Vec3, environment: AcousticEnvironment): Prepared {
        val dx = 1.0 / subdivisions
        val margin = min(radiusMeters, max(2.0, radiusMeters))
        var minWorldX = min(source.x, listener.x) - margin
        var minWorldY = min(source.y, listener.y) - margin
        var minWorldZ = min(source.z, listener.z) - margin
        val maxWorldX = max(source.x, listener.x) + margin
        val maxWorldY = max(source.y, listener.y) + margin
        val maxWorldZ = max(source.z, listener.z) + margin
        val nx = boundedCells(minWorldX, maxWorldX, dx)
        val ny = boundedCells(minWorldY, maxWorldY, dx)
        val nz = boundedCells(minWorldZ, maxWorldZ, dx)
        if (nx == 40) minWorldX = listener.x - 20 * dx
        if (ny == 40) minWorldY = listener.y - 20 * dx
        if (nz == 40) minWorldZ = listener.z - 20 * dx

        val cells = nx * ny * nz
        val solid = IntArray(cells)
        val wallReflection = FloatArray(cells)
        val speedByCell = FloatArray(cells)
        val densityByCell = FloatArray(cells)
        val absorptionByCell = FloatArray(cells)
        var hasNonAirMedium = false
        var maxSpeed = environment.speedOfSoundMetersPerSecond()
        var minPropagationSpeed = Double.POSITIVE_INFINITY
        var z = 0
        while (z < nz) {
            var y = 0
            while (y < ny) {
                var x = 0
                while (x < nx) {
                    val cellIndex = index(x, y, z, nx, ny)
                    val sampleX = minWorldX + (x + 0.5) * dx
                    val sampleY = minWorldY + (y + 0.5) * dx
                    val sampleZ = minWorldZ + (z + 0.5) * dx
                    val worldX = floor(sampleX).toInt()
                    val worldY = floor(sampleY).toInt()
                    val worldZ = floor(sampleZ).toInt()
                    val voxel = scene.voxelAt(worldX, worldY, worldZ)
                    val boundary = voxel.solid() && voxel.shape().occupancyFraction() >= 0.75
                    solid[cellIndex] = if (boundary) 1 else 0
                    wallReflection[cellIndex] = if (boundary) voxel.material().reflection(0) else 1f

                    val medium = if (!boundary && !voxel.solid()) {
                        SceneMediumGeometry.mediumAt(scene, sampleX, sampleY, sampleZ)
                    } else AcousticMedia.AIR
                    val air = medium.id() == AcousticMedia.AIR.id()
                    val localSpeed = if (air) environment.speedOfSoundMetersPerSecond() else medium.speedOfSoundMetersPerSecond()
                    val localDensity = if (air) AcousticMedia.AIR.densityKgPerCubicMeter() else medium.densityKgPerCubicMeter()
                    val localAbsorption = if (air) environment.airAbsorptionNepersPerMeter(0) else medium.absorptionNepersPerMeter(0)
                    speedByCell[cellIndex] = localSpeed.toFloat()
                    densityByCell[cellIndex] = localDensity.toFloat()
                    absorptionByCell[cellIndex] = localAbsorption
                    if (!air) hasNonAirMedium = true
                    if (!boundary) {
                        if (localSpeed > maxSpeed) maxSpeed = localSpeed
                        if (localSpeed < minPropagationSpeed) minPropagationSpeed = localSpeed
                    }
                    x++
                }
                y++
            }
            z++
        }

        val sx = clamp(floor((source.x - minWorldX) / dx).toInt(), 1, nx - 2)
        val sy = clamp(floor((source.y - minWorldY) / dx).toInt(), 1, ny - 2)
        val sz = clamp(floor((source.z - minWorldZ) / dx).toInt(), 1, nz - 2)
        val lx = clamp(floor((listener.x - minWorldX) / dx).toInt(), 1, nx - 2)
        val ly = clamp(floor((listener.y - minWorldY) / dx).toInt(), 1, ny - 2)
        val lz = clamp(floor((listener.z - minWorldZ) / dx).toInt(), 1, nz - 2)
        val sourceIndex = index(sx, sy, sz, nx, ny)
        val listenerIndex = index(lx, ly, lz, nx, ny)
        solid[sourceIndex] = 0
        solid[listenerIndex] = 0

        val airSpeed = environment.speedOfSoundMetersPerSecond()
        if (!minPropagationSpeed.isFinite()) minPropagationSpeed = airSpeed
        val dt = cflSafety * dx / (maxSpeed * sqrt(3.0))
        val airCourant = airSpeed * dt / dx
        val lambda = (airCourant * airCourant).toFloat()
        val airDamping = min(0.02, environment.airAbsorptionNepersPerMeter(0) * airSpeed * dt).toFloat()
        // A faster liquid lowers the global stable time step. Preserve approximately the same
        // physical response window as the preset requested, otherwise entering water would
        // silently shorten the FDTD impulse by c_liquid/c_air.
        val effectiveSteps = min(8192, max(maxSteps, ceil(maxSteps * maxSpeed / airSpeed).toInt()))
        val problem = if (hasNonAirMedium) {
            val lambdaByCell = FloatArray(cells)
            val dampingByCell = FloatArray(cells)
            var i = 0
            while (i < cells) {
                val localSpeed = speedByCell[i].toDouble()
                val localCourant = localSpeed * dt / dx
                lambdaByCell[i] = (localCourant * localCourant).toFloat()
                dampingByCell[i] = min(0.02, absorptionByCell[i] * localSpeed * dt).toFloat()
                i++
            }
            FdtdProblem(nx, ny, nz, effectiveSteps, sourceIndex, listenerIndex, lambda, airDamping, solid, wallReflection, lambdaByCell, dampingByCell, densityByCell)
        } else {
            FdtdProblem(nx, ny, nz, effectiveSteps, sourceIndex, listenerIndex, lambda, airDamping, solid, wallReflection)
        }
        return Prepared(problem, dx, dt, minPropagationSpeed)
    }

    private class ParallelState(
        @Volatile var previous: FloatArray,
        @Volatile var current: FloatArray,
        @Volatile var next: FloatArray
    ) {
        var step: Int = 0
    }

    private data class Prepared(val problem: FdtdProblem, val dx: Double, val dt: Double, val spatialTrustSpeed: Double)

    companion object {
        private fun publish(context: PassContext, prepared: Prepared, response: FloatArray, backendId: String) {
            normalize(response)
            val modes = estimateModes(response, prepared.dt, 500.0)
            val trusted = min(prepared.spatialTrustSpeed / (8.0 * prepared.dx), 0.40 / prepared.dt)
            context.put(
                StandardResources.WAVE_FIELD,
                WaveFieldResult(
                    prepared.problem.nx() * prepared.dx,
                    prepared.problem.ny() * prepared.dx,
                    prepared.problem.nz() * prepared.dx,
                    modes,
                    "fdtd/$backendId",
                    prepared.dt,
                    response,
                    trusted
                )
            )
        }

        @JvmStatic
        fun solveCpuScalar(problem: FdtdProblem): FloatArray {
            val cells = problem.cells()
            val nz = problem.nz()
            var previous = FloatArray(cells)
            var current = FloatArray(cells)
            var next = FloatArray(cells)
            val response = FloatArray(problem.maxSteps())
            current[problem.sourceIndex()] = 1f
            var step = 0
            while (step < problem.maxSteps()) {
                computeRange(problem, previous, current, next, 1, nz - 1)
                response[step] = current[problem.listenerIndex()]
                val temporary = previous
                previous = current
                current = next
                next = temporary
                step++
            }
            return response
        }

        @JvmStatic
        fun solveCpuParallel(problem: FdtdProblem, parallel: ParallelWorkExecutor): FloatArray {
            if (parallel is PhasedParallelWorkExecutor) return solveCpuParallelPhased(problem, parallel)
            val cells = problem.cells()
            val nz = problem.nz()
            var previous = FloatArray(cells)
            var current = FloatArray(cells)
            var next = FloatArray(cells)
            val response = FloatArray(problem.maxSteps())
            current[problem.sourceIndex()] = 1f
            var step = 0
            while (step < problem.maxSteps()) {
                val phasePrevious = previous
                val phaseCurrent = current
                val phaseNext = next
                try {
                    parallel.forRange(1, nz - 1) { from, to ->
                        computeRange(problem, phasePrevious, phaseCurrent, phaseNext, from, to)
                    }
                } catch (failure: Exception) {
                    throw RuntimeException("parallel FDTD phase failed", failure)
                }
                response[step] = current[problem.listenerIndex()]
                val temporary = previous
                previous = current
                current = next
                next = temporary
                step++
            }
            return response
        }

        private fun solveCpuParallelPhased(problem: FdtdProblem, parallel: PhasedParallelWorkExecutor): FloatArray {
            val cells = problem.cells()
            val nz = problem.nz()
            val parts = min(max(1, parallel.workerCount()), max(1, nz - 2))
            if (parts <= 1) return solveCpuScalar(problem)
            val response = FloatArray(problem.maxSteps())
            val state = ParallelState(FloatArray(cells), FloatArray(cells), FloatArray(cells))
            state.current[problem.sourceIndex()] = 1f
            val barrier = CyclicBarrier(parts) {
                response[state.step] = state.current[problem.listenerIndex()]
                val temporary = state.previous
                state.previous = state.current
                state.current = state.next
                state.next = temporary
                state.step++
            }
            try {
                parallel.forWorkers(parts) { workerIndex, workerCount ->
                    val zFrom = 1 + (nz - 2) * workerIndex / workerCount
                    val zTo = 1 + (nz - 2) * (workerIndex + 1) / workerCount
                    var step = 0
                    while (step < problem.maxSteps()) {
                        computeRange(problem, state.previous, state.current, state.next, zFrom, zTo)
                        try {
                            barrier.await()
                        } catch (failure: BrokenBarrierException) {
                            throw RuntimeException("parallel FDTD barrier broken", failure)
                        }
                        step++
                    }
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeException("parallel FDTD interrupted", failure)
            } catch (failure: Exception) {
                throw RuntimeException("parallel FDTD phase failed", failure)
            }
            return response
        }

        private fun computeRange(
            problem: FdtdProblem,
            previous: FloatArray,
            current: FloatArray,
            next: FloatArray,
            zFrom: Int,
            zTo: Int
        ) {
            val nx = problem.nx()
            val ny = problem.ny()
            val solid = problem.solid()
            val reflection = problem.wallReflection()
            val lambda = problem.lambda().toDouble()
            val airDamping = problem.airDamping().toDouble()
            val heterogeneous = problem.heterogeneous()
            val plane = nx * ny
            var z = zFrom
            while (z < zTo) {
                var y = 1
                while (y < problem.ny() - 1) {
                    var x = 1
                    while (x < problem.nx() - 1) {
                        val i = index(x, y, z, nx, ny)
                        if (solid[i] != 0) {
                            next[i] = 0f
                            x++
                            continue
                        }
                        val center = current[i].toDouble()
                        val localLambda: Double
                        val localDamping: Double
                        val lap: Double
                        if (heterogeneous) {
                            localLambda = problem.lambdaAt(i).toDouble()
                            localDamping = problem.dampingAt(i).toDouble()
                            lap = weightedNeighborDelta(problem, current, solid, reflection, i - 1, i) +
                                weightedNeighborDelta(problem, current, solid, reflection, i + 1, i) +
                                weightedNeighborDelta(problem, current, solid, reflection, i - nx, i) +
                                weightedNeighborDelta(problem, current, solid, reflection, i + nx, i) +
                                weightedNeighborDelta(problem, current, solid, reflection, i - plane, i) +
                                weightedNeighborDelta(problem, current, solid, reflection, i + plane, i)
                        } else {
                            localLambda = lambda
                            localDamping = airDamping
                            lap = neighbor(current, solid, reflection, i - 1, i) +
                                neighbor(current, solid, reflection, i + 1, i) +
                                neighbor(current, solid, reflection, i - nx, i) +
                                neighbor(current, solid, reflection, i + nx, i) +
                                neighbor(current, solid, reflection, i - plane, i) +
                                neighbor(current, solid, reflection, i + plane, i) - 6.0 * center
                        }
                        var value = (2.0 - localDamping) * center -
                            (1.0 - localDamping) * previous[i] + localLambda * lap
                        if (value > 8.0) value = 8.0
                        if (value < -8.0) value = -8.0
                        next[i] = value.toFloat()
                        x++
                    }
                    y++
                }
                z++
            }
        }

        private fun neighbor(
            field: FloatArray,
            solid: IntArray,
            reflection: FloatArray,
            neighbor: Int,
            center: Int
        ): Double = if (solid[neighbor] != 0) {
            field[center] * reflection[neighbor].toDouble()
        } else {
            field[neighbor].toDouble()
        }

        /** Variable-density pressure stencil. For equal densities this reduces exactly to a normal Laplacian delta. */
        private fun weightedNeighborDelta(
            problem: FdtdProblem,
            field: FloatArray,
            solid: IntArray,
            reflection: FloatArray,
            neighbor: Int,
            center: Int
        ): Double {
            val centerValue = field[center].toDouble()
            if (solid[neighbor] != 0) return centerValue * (reflection[neighbor].toDouble() - 1.0)
            val rhoCenter = problem.densityAt(center).toDouble()
            val rhoNeighbor = problem.densityAt(neighbor).toDouble()
            val face = 2.0 * rhoCenter / (rhoCenter + rhoNeighbor)
            return face * (field[neighbor].toDouble() - centerValue)
        }

        private fun boundedCells(minimum: Double, maximum: Double, dx: Double): Int {
            val cells = ceil((maximum - minimum) / dx).toInt() + 3
            return max(5, min(40, cells))
        }

        private fun clamp(value: Int, low: Int, high: Int): Int = max(low, min(high, value))
        private fun index(x: Int, y: Int, z: Int, nx: Int, ny: Int): Int = x + nx * (y + ny * z)

        private fun normalize(values: FloatArray) {
            var maxValue = 0f
            for (value in values) maxValue = max(maxValue, abs(value))
            if (maxValue > 1f) {
                var i = 0
                while (i < values.size) {
                    values[i] /= maxValue
                    i++
                }
            }
        }

        private fun estimateModes(response: FloatArray, dt: Double, maxHz: Double): List<Double> {
            val out = ArrayList<Double>()
            if (response.size < 4) return out
            var last = -1
            var i = 1
            while (i < response.size) {
                if (response[i - 1] <= 0f && response[i] > 0f) {
                    if (last >= 0) {
                        val period = (i - last) * dt
                        if (period > 0.0) {
                            val frequency = 1.0 / period
                            if (frequency > 10.0 && frequency <= maxHz) out.add(frequency)
                        }
                    }
                    last = i
                    if (out.size >= 16) break
                }
                i++
            }
            return out
        }
    }
}
