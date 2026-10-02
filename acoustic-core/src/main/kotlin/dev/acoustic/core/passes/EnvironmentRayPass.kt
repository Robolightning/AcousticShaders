package dev.acoustic.core.passes

import dev.acoustic.core.compat.uppercaseCompat
import dev.acoustic.core.compat.lowercaseCompat
import dev.acoustic.api.environment.AcousticEnvironment
import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.pipeline.AcceleratedPass
import dev.acoustic.api.pipeline.PartitionedPass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.compute.GeometricBackendRegistry
import dev.acoustic.core.compute.GeometricExternalBackend
import dev.acoustic.core.trace.MediumBoundaryRaycast
import dev.acoustic.core.trace.MediumPathIntegrator
import dev.acoustic.core.trace.VoxelRaycast
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.sin

/** Deterministic listener-centric geometric probe used as the portable CPU reference implementation. */
class EnvironmentRayPass @JvmOverloads constructor(
    private val rayCount: Int,
    private val maxBounces: Int,
    private val maxDistancePerBounce: Double,
    private val minEnergy: Double,
    computeBackend: String? = "AUTO"
) : PartitionedPass, AcceleratedPass {
    private val computeBackend: String = (computeBackend ?: "AUTO").trim().uppercaseCompat(Locale.ROOT)

    init {
        require(rayCount >= 1 && maxBounces >= 1 && maxDistancePerBounce > 0.0 && minEnergy >= 0.0) { "invalid ray settings" }
    }

    override fun id(): String = "standard.environment_rays"

    override fun reads(): Set<ResourceKey<*>> {
        val result = LinkedHashSet<ResourceKey<*>>()
        result.add(StandardResources.SCENE)
        result.add(StandardResources.LISTENER_POSITION)
        return Collections.unmodifiableSet(result)
    }

    override fun optionalReads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.ENVIRONMENT)

    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.REFLECTION_FIELD)
    override fun workUnits(): Int = rayCount

    override fun tryExecuteAccelerated(context: PassContext): String? {
        if (computeBackend == "CPU" || computeBackend == "CPU_PARALLEL" || computeBackend == "CPU_SCALAR") return null
        val scene = context.require(StandardResources.SCENE)
        val listener = context.require(StandardResources.LISTENER_POSITION)
        val backend: GeometricExternalBackend? = when (computeBackend) {
            "AUTO" -> GeometricBackendRegistry.firstPreferred(scene, rayCount, maxBounces, maxDistancePerBounce, minEnergy)
            "GPU" -> GeometricBackendRegistry.firstSupported(scene, rayCount, maxBounces, maxDistancePerBounce, minEnergy)
            else -> GeometricBackendRegistry.find(computeBackend.lowercaseCompat(Locale.ROOT))
        }
        if (backend == null || !backend.supports(scene, rayCount, maxBounces, maxDistancePerBounce, minEnergy)) return null
        return try {
            val field = backend.trace(scene, listener, rayCount, maxBounces, maxDistancePerBounce, minEnergy) ?: return null
            context.put(StandardResources.REFLECTION_FIELD, field)
            backend.id()
        } catch (_: Throwable) {
            null
        }
    }

    override fun executePartition(context: PassContext, fromInclusive: Int, toExclusive: Int): Any {
        val scene = context.require(StandardResources.SCENE)
        val listener = context.require(StandardResources.LISTENER_POSITION)
        val environment = context.get(StandardResources.ENVIRONMENT) ?: AcousticEnvironment.STANDARD
        val samples = ArrayList<ReflectionSample>()
        var i = fromInclusive
        while (i < toExclusive) {
            traceRay(scene, listener, fibonacciDirection(i, rayCount), i, environment, samples)
            i++
        }
        return samples
    }

    override fun combine(context: PassContext, partitionResults: List<Any?>) {
        val all = ArrayList<ReflectionSample>()
        for (result in partitionResults) {
            @Suppress("UNCHECKED_CAST")
            all.addAll(result as List<ReflectionSample>)
        }
        context.put(StandardResources.REFLECTION_FIELD, ReflectionField(rayCount, all))
    }

    private fun traceRay(
        scene: AcousticScene,
        start: Vec3,
        initial: Vec3,
        rayIndex: Int,
        environment: AcousticEnvironment,
        out: MutableList<ReflectionSample>
    ) {
        var origin = start
        var dir = initial
        var total = 0.0
        var totalDelay = 0.0
        val energy = FloatArray(FrequencyBands.COUNT) { 1f }
        var bounce = 1
        while (bounce <= maxBounces) {
            val solidHit = VoxelRaycast.firstSolid(scene, origin, dir, maxDistancePerBounce)
            val mediumHit = MediumBoundaryRaycast.first(scene, origin, dir, maxDistancePerBounce)
            val useMedium = mediumHit != null && (solidHit == null || mediumHit.distance + 1.0e-7 < solidHit.distance())

            if (useMedium) {
                val hit = mediumHit!!
                val incidentMedium = hit.from
                val segment = hit.distance
                total += segment
                totalDelay += segment / MediumPathIntegrator.propagationSpeed(incidentMedium, environment)
                MediumPathIntegrator.applyBulkTransmission(energy, incidentMedium, environment, segment)

                val cosIncidence = kotlin.math.abs(dir.dot(hit.normal)).coerceIn(0.0, 1.0)
                val reflectionGain = incidentMedium.interfaceAmplitudeReflectionTo(hit.to, cosIncidence)
                var alive = false
                var band = 0
                while (band < energy.size) {
                    energy[band] *= reflectionGain
                    if (energy[band] >= minEnergy) alive = true
                    band++
                }
                out.add(ReflectionSample(total, totalDelay, bounce, initial, energy))
                if (!alive) return

                val dot = dir.dot(hit.normal)
                dir = dir.subtract(hit.normal.multiply(2.0 * dot)).normalize()
                origin = hit.position.add(hit.normal.multiply(1.0e-6))
                bounce++
                continue
            }

            val hit = solidHit ?: return
            val segmentMedium = MediumPathIntegrator.mediumAt(scene, origin)
            total += hit.distance()
            totalDelay += hit.distance() / MediumPathIntegrator.propagationSpeed(segmentMedium, environment)
            MediumPathIntegrator.applyBulkTransmission(energy, segmentMedium, environment, hit.distance())
            var alive = false
            var band = 0
            while (band < energy.size) {
                energy[band] *= hit.voxel().material().reflection(band)
                if (energy[band] >= minEnergy) alive = true
                band++
            }
            out.add(ReflectionSample(total, totalDelay, bounce, initial, energy))
            if (!alive) return
            val normal = hit.normal()
            val dot = dir.dot(normal)
            val specular = dir.subtract(normal.multiply(2.0 * dot)).normalize()
            val scatter = hit.voxel().material().scattering().toDouble()
            dir = if (scatter > 0.0) {
                val diffuse = diffuseDirection(rayIndex, bounce, normal)
                specular.multiply(1.0 - scatter).add(diffuse.multiply(scatter)).normalize()
            } else specular
            origin = hit.position().add(normal.multiply(1e-6))
            bounce++
        }
    }

    companion object {
        private fun diffuseDirection(rayIndex: Int, bounce: Int, normal: Vec3): Vec3 {
            var seed = (rayIndex + 1L) * -7046029254386353131L + (bounce + 11L) * -4417276706812531889L
            seed = seed xor (seed ushr 29)
            val u = (seed and 0xffffffL).toDouble() / 0x1000000L.toDouble()
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val v = (seed and 0xffffffL).toDouble() / 0x1000000L.toDouble()
            val z = u
            val r = sqrt(max(0.0, 1.0 - z * z))
            val phi = 2.0 * PI * v
            var direction = Vec3(r * cos(phi), z, r * sin(phi))
            if (direction.dot(normal) < 0.0) direction = direction.multiply(-1.0)
            return direction.normalize()
        }

        @JvmStatic
        fun fibonacciDirection(index: Int, count: Int): Vec3 {
            val golden = PI * (3.0 - sqrt(5.0))
            val y = 1.0 - 2.0 * ((index + 0.5) / count)
            val r = sqrt(max(0.0, 1.0 - y * y))
            val phi = index * golden
            return Vec3(cos(phi) * r, y, sin(phi) * r)
        }
    }
}
