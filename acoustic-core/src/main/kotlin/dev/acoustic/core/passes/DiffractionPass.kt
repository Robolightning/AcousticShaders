package dev.acoustic.core.passes

import dev.acoustic.api.environment.AcousticEnvironment
import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.trace.VoxelRaycast
import dev.acoustic.core.trace.LayeredMediumRefraction
import java.util.Collections
import java.util.LinkedHashSet

/** Cheap voxel knife-edge approximation for blocked direct paths. */
class DiffractionPass : Pass {
    override fun id(): String = "standard.diffraction"
    override fun reads(): Set<ResourceKey<*>> = Collections.unmodifiableSet(LinkedHashSet<ResourceKey<*>>().apply {
        add(StandardResources.SCENE); add(StandardResources.SOURCE_POSITION); add(StandardResources.LISTENER_POSITION); add(StandardResources.DIRECT_PATH)
    })
    override fun optionalReads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.ENVIRONMENT)
    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.DIFFRACTION)

    override fun execute(context: PassContext) {
        val scene = context.require(StandardResources.SCENE)
        val source = context.require(StandardResources.SOURCE_POSITION)
        val listener = context.require(StandardResources.LISTENER_POSITION)
        val direct = context.require(StandardResources.DIRECT_PATH)
        val environment = context.get(StandardResources.ENVIRONMENT) ?: AcousticEnvironment.STANDARD
        val none = FloatArray(FrequencyBands.COUNT)
        if (direct.solidCells == 0) {
            ArraysFill.fill(none, 1f)
            context.put(StandardResources.DIFFRACTION, DiffractionResult(false, direct.distanceMeters, direct.delaySeconds, listener, none))
            return
        }
        val delta = listener.subtract(source)
        val hit = VoxelRaycast.firstSolid(scene, source, delta, delta.length())
        if (hit == null) {
            context.put(StandardResources.DIFFRACTION, DiffractionResult(false, direct.distanceMeters, direct.delaySeconds, listener, none))
            return
        }
        var best = Double.POSITIVE_INFINITY
        var bestDelay = Double.POSITIVE_INFINITY
        var bestMetric = Double.POSITIVE_INFINITY
        var bestPoint: Vec3? = null
        var bestMediumTransmission: FloatArray? = null
        val epsilon = 0.02
        val corridors = arrayOf(
            arrayOf(Vec3(hit.x() - epsilon, hit.y() - epsilon, hit.z() + 0.5), Vec3(hit.x() + 1 + epsilon, hit.y() - epsilon, hit.z() + 0.5)),
            arrayOf(Vec3(hit.x() - epsilon, hit.y() + 1 + epsilon, hit.z() + 0.5), Vec3(hit.x() + 1 + epsilon, hit.y() + 1 + epsilon, hit.z() + 0.5)),
            arrayOf(Vec3(hit.x() - epsilon, hit.y() + 0.5, hit.z() - epsilon), Vec3(hit.x() + 1 + epsilon, hit.y() + 0.5, hit.z() - epsilon)),
            arrayOf(Vec3(hit.x() - epsilon, hit.y() + 0.5, hit.z() + 1 + epsilon), Vec3(hit.x() + 1 + epsilon, hit.y() + 0.5, hit.z() + 1 + epsilon))
        )
        for (pair in corridors) {
            val p1 = pair[0]; val p2 = pair[1]
            if (clear(scene, source, p1) && clear(scene, p1, p2) && clear(scene, p2, listener)) {
                val first = LayeredMediumRefraction.bestEffort(scene, source, p1, environment)
                val middle = LayeredMediumRefraction.bestEffort(scene, p1, p2, environment)
                val last = LayeredMediumRefraction.bestEffort(scene, p2, listener, environment)
                val path = first.distanceMeters + middle.distanceMeters + last.distanceMeters
                val delay = first.delaySeconds + middle.delaySeconds + last.delaySeconds
                if (delay < bestMetric) {
                    bestMetric = delay
                    best = path
                    bestDelay = delay
                    bestPoint = p1.add(p2).multiply(0.5)
                    bestMediumTransmission = FloatArray(FrequencyBands.COUNT) { band -> first.transmission[band] * middle.transmission[band] * last.transmission[band] }
                }
            }
        }
        val transmission = FloatArray(FrequencyBands.COUNT)
        if (bestPoint != null) {
            val extra = Math.max(0.0, best - direct.distanceMeters)
            var band = 0
            while (band < transmission.size) {
                val frequency = FrequencyBands.OCTAVE_HZ[band]
                val penalty = Math.exp(-extra * Math.sqrt(frequency / 125.0) * 0.35)
                val mediumGain = bestMediumTransmission?.get(band) ?: 1f
                transmission[band] = (Math.max(0.02, Math.min(1.0, penalty)) * mediumGain).toFloat()
                band++
            }
        }
        context.put(StandardResources.DIFFRACTION, DiffractionResult(bestPoint != null, if (bestPoint == null) direct.distanceMeters else best, if (bestPoint == null) direct.delaySeconds else bestDelay, bestPoint, transmission))
    }

    private fun clear(scene: AcousticScene, a: Vec3, b: Vec3): Boolean {
        val delta = b.subtract(a)
        val length = delta.length()
        return length < 1e-6 || VoxelRaycast.firstSolid(scene, a, delta, Math.max(0.0, length - 1e-4)) == null
    }

    private object ArraysFill {
        fun fill(values: FloatArray, value: Float) { java.util.Arrays.fill(values, value) }
    }
}
