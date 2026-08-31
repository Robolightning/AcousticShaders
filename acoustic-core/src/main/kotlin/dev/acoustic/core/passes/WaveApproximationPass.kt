package dev.acoustic.core.passes

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.trace.VoxelRaycast
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashSet

/** Cheap low-frequency rectangular-modal approximation derived from six local boundary probes. */
class WaveApproximationPass(private val maxProbe: Double, private val maxHz: Double) : Pass {
    init { require(maxProbe > 0.0 && maxHz > 0.0) { "invalid wave settings" } }
    override fun id(): String = "standard.wave_low_frequency"
    override fun reads(): Set<ResourceKey<*>> = LinkedHashSet(Arrays.asList(StandardResources.SCENE, StandardResources.LISTENER_POSITION))
    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.WAVE_FIELD)

    override fun execute(context: PassContext) {
        val scene = context.require(StandardResources.SCENE)
        val position = context.require(StandardResources.LISTENER_POSITION)
        val xp = distance(scene, position, Vec3(1.0, 0.0, 0.0))
        val xn = distance(scene, position, Vec3(-1.0, 0.0, 0.0))
        val yp = distance(scene, position, Vec3(0.0, 1.0, 0.0))
        val yn = distance(scene, position, Vec3(0.0, -1.0, 0.0))
        val zp = distance(scene, position, Vec3(0.0, 0.0, 1.0))
        val zn = distance(scene, position, Vec3(0.0, 0.0, -1.0))
        val x = xp + xn; val y = yp + yn; val z = zp + zn
        var modes = ArrayList<Double>()
        var nx = 0
        while (nx <= 4) {
            var ny = 0
            while (ny <= 4) {
                var nz = 0
                while (nz <= 4) {
                    if (nx + ny + nz != 0) {
                        val frequency = 343.0 / 2.0 * Math.sqrt(sq(nx / x) + sq(ny / y) + sq(nz / z))
                        if (frequency <= maxHz) modes.add(frequency)
                    }
                    nz++
                }
                ny++
            }
            nx++
        }
        modes.sortWith(Comparator { a, b -> java.lang.Double.compare(a, b) })
        if (modes.size > 24) modes = ArrayList(modes.subList(0, 24))
        context.put(StandardResources.WAVE_FIELD, WaveFieldResult(x, y, z, modes, maxHz))
    }

    private fun distance(scene: AcousticScene, position: Vec3, direction: Vec3): Double {
        val hit = VoxelRaycast.firstSolid(scene, position, direction, maxProbe)
        return hit?.distance() ?: maxProbe
    }
    private fun sq(value: Double): Double = value * value
}
