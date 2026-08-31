package dev.acoustic.core.passes

import dev.acoustic.api.environment.AcousticEnvironment
import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.pipeline.PartitionedPass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.trace.VoxelRaycast
import dev.acoustic.core.trace.LayeredMediumRefraction
import java.util.Collections
import java.util.LinkedHashSet

/** Listener-centric deterministic single-bounce early-reflection estimator. */
class EarlyReflectionPass(private val rayCount: Int, private val maxDistance: Double) : PartitionedPass {
    private val epsilon = 1e-4
    init { require(rayCount >= 1 && maxDistance > 0.0) { "invalid settings" } }
    override fun id(): String = "standard.early_reflections"
    override fun reads(): Set<ResourceKey<*>> = Collections.unmodifiableSet(LinkedHashSet<ResourceKey<*>>().apply {
        add(StandardResources.SCENE); add(StandardResources.SOURCE_POSITION); add(StandardResources.LISTENER_POSITION)
    })
    override fun optionalReads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.ENVIRONMENT)
    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.EARLY_REFLECTIONS)
    override fun workUnits(): Int = rayCount

    override fun executePartition(context: PassContext, fromInclusive: Int, toExclusive: Int): Any {
        val scene = context.require(StandardResources.SCENE)
        val source = context.require(StandardResources.SOURCE_POSITION)
        val listener = context.require(StandardResources.LISTENER_POSITION)
        val environment = context.get(StandardResources.ENVIRONMENT) ?: AcousticEnvironment.STANDARD
        val out = ArrayList<EarlyReflection>()
        var i = fromInclusive
        while (i < toExclusive) {
            val dir = fibonacci(i, rayCount)
            val hit = VoxelRaycast.firstSolid(scene, listener, dir, maxDistance)
            if (hit != null) {
                val point = hit.position().add(hit.normal().multiply(epsilon))
                val toSource = source.subtract(point)
                val sourceDistance = toSource.length()
                if (sourceDistance > epsilon) {
                    val blocker = VoxelRaycast.firstSolid(scene, point, toSource, Math.max(0.0, sourceDistance - epsilon))
                    if (blocker == null) {
                        val listenerLeg = LayeredMediumRefraction.bestEffort(scene, listener, point, environment)
                        val sourceLeg = LayeredMediumRefraction.bestEffort(scene, point, source, environment)
                        val total = listenerLeg.distanceMeters + sourceLeg.distanceMeters
                        val delay = listenerLeg.delaySeconds + sourceLeg.delaySeconds
                        val energy = FloatArray(FrequencyBands.COUNT)
                        val spread = 1.0 / Math.max(1.0, total)
                        var band = 0
                        while (band < energy.size) {
                            energy[band] = (hit.voxel().material().reflection(band) * spread * listenerLeg.transmission[band] * sourceLeg.transmission[band]).toFloat()
                            band++
                        }
                        out.add(EarlyReflection(total, delay, dir.multiply(-1.0), point, energy))
                    }
                }
            }
            i++
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    override fun combine(context: PassContext, partitionResults: List<@JvmSuppressWildcards Any?>) {
        val all = ArrayList<EarlyReflection>()
        for (part in partitionResults) all.addAll(part as List<EarlyReflection>)
        context.put(StandardResources.EARLY_REFLECTIONS, EarlyReflectionField(all))
    }

    private fun fibonacci(index: Int, count: Int): Vec3 {
        val golden = Math.PI * (3.0 - Math.sqrt(5.0))
        val y = 1.0 - 2.0 * ((index + 0.5) / count.toDouble())
        val radius = Math.sqrt(Math.max(0.0, 1.0 - y * y))
        val phase = index * golden
        return Vec3(Math.cos(phase) * radius, y, Math.sin(phase) * radius)
    }
}
