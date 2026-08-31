package dev.acoustic.core.trace

import dev.acoustic.api.environment.AcousticEnvironment
import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Exact Fermat/Snell solution for a source and listener separated by one locally planar,
 * axis-aligned fluid boundary. Used for the common air <-> water-surface case.
 */
object SingleBoundaryRefraction {
    @JvmStatic
    fun bestEffort(scene: AcousticScene, source: Vec3, listener: Vec3, environment: AcousticEnvironment): MediumPathIntegrator.Result {
        val straight = MediumPathIntegrator.integrate(scene, source, listener, environment)
        if (straight.boundaries.size != 1 || straight.sourceMedium.id() == straight.listenerMedium.id()) return straight
        return solve(scene, source, listener, straight.boundaries[0], environment) ?: straight
    }

    @JvmStatic
    fun solve(
        scene: AcousticScene,
        source: Vec3,
        listener: Vec3,
        boundary: MediumPathIntegrator.Boundary,
        environment: AcousticEnvironment
    ): MediumPathIntegrator.Result? {
        val from = boundary.from
        val to = boundary.to
        if (from.id() == to.id()) return null
        val axis = boundary.axis
        val plane = boundary.coordinate
        val sourceNormal = component(source, axis)
        val listenerNormal = component(listener, axis)
        if ((sourceNormal - plane) * (listenerNormal - plane) >= 0.0) return null

        val tangentDelta = tangent(listener.subtract(source), axis)
        val tangentLength = tangentDelta.length()
        val a = abs(plane - sourceNormal)
        val b = abs(listenerNormal - plane)
        val c1 = MediumPathIntegrator.propagationSpeed(from, environment)
        val c2 = MediumPathIntegrator.propagationSpeed(to, environment)

        var u = if (tangentLength <= 1.0e-12) 0.5 else solveFraction(a, b, tangentLength, c1, c2)
        if (!u.isFinite()) u = 0.5
        val point = withComponent(source.add(tangentDelta.multiply(u)), axis, plane)
        val d1 = source.distance(point)
        val d2 = point.distance(listener)
        if (d1 <= 1.0e-9 || d2 <= 1.0e-9) return null

        val directionSign = if (listenerNormal > sourceNormal) 1.0 else -1.0
        val normal = axisVector(axis).multiply(directionSign)
        val epsilon = 1.0e-4
        val before = point.subtract(normal.multiply(epsilon))
        val after = point.add(normal.multiply(epsilon))
        if (MediumPathIntegrator.mediumAt(scene, before).id() != from.id()) return null
        if (MediumPathIntegrator.mediumAt(scene, after).id() != to.id()) return null
        if (!clear(scene, source, before) || !clear(scene, after, listener)) return null

        val first = MediumPathIntegrator.integrate(scene, source, before, environment)
        val second = MediumPathIntegrator.integrate(scene, after, listener, environment)
        if (first.interfaceCount != 0 || second.interfaceCount != 0) return null

        val cosIncidence = a / d1
        val interfaceGain = from.interfaceAmplitudeTransmissionTo(to, cosIncidence)
        val spectrum = FloatArray(FrequencyBands.COUNT) { band -> first.transmission[band] * interfaceGain * second.transmission[band] }
        val airMeters = (if (from.id() == AcousticMedia.AIR.id()) d1 else 0.0) + (if (to.id() == AcousticMedia.AIR.id()) d2 else 0.0)
        val liquidMeters = (if (from.id() == AcousticMedia.AIR.id()) 0.0 else d1) + (if (to.id() == AcousticMedia.AIR.id()) 0.0 else d2)
        return MediumPathIntegrator.Result(
            d1 + d2,
            d1 / c1 + d2 / c2,
            spectrum,
            airMeters,
            liquidMeters,
            1,
            from,
            to,
            listOf(boundary)
        )
    }

    private fun solveFraction(a: Double, b: Double, tangentLength: Double, c1: Double, c2: Double): Double {
        var lo = 0.0
        var hi = 1.0
        var iteration = 0
        while (iteration++ < 72) {
            val u = (lo + hi) * 0.5
            val x1 = u * tangentLength
            val x2 = (1.0 - u) * tangentLength
            val d1 = sqrt(a * a + x1 * x1)
            val d2 = sqrt(b * b + x2 * x2)
            val derivative = x1 / (c1 * d1) - x2 / (c2 * d2)
            if (derivative > 0.0) hi = u else lo = u
        }
        return (lo + hi) * 0.5
    }

    private fun clear(scene: AcousticScene, a: Vec3, b: Vec3): Boolean {
        val delta = b.subtract(a)
        val length = delta.length()
        return length <= 1.0e-7 || VoxelRaycast.firstSolid(scene, a, delta, length) == null
    }

    private fun component(v: Vec3, axis: Int): Double = when (axis) { 0 -> v.x; 1 -> v.y; else -> v.z }
    private fun withComponent(v: Vec3, axis: Int, value: Double): Vec3 = when (axis) {
        0 -> Vec3(value, v.y, v.z)
        1 -> Vec3(v.x, value, v.z)
        else -> Vec3(v.x, v.y, value)
    }
    private fun tangent(v: Vec3, axis: Int): Vec3 = when (axis) {
        0 -> Vec3(0.0, v.y, v.z)
        1 -> Vec3(v.x, 0.0, v.z)
        else -> Vec3(v.x, v.y, 0.0)
    }
    private fun axisVector(axis: Int): Vec3 = when (axis) {
        0 -> Vec3(1.0, 0.0, 0.0)
        1 -> Vec3(0.0, 1.0, 0.0)
        else -> Vec3(0.0, 0.0, 1.0)
    }
}
