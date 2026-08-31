package dev.acoustic.core.trace

import dev.acoustic.api.environment.AcousticEnvironment
import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fermat/Snell path through one or more parallel axis-aligned fluid interfaces.
 * This covers the common Minecraft cases: entering water, crossing a pool/column, and leaving it.
 * Non-parallel/stair-stepped topology safely falls back to the straight DDA path.
 */
object LayeredMediumRefraction {
    @JvmStatic
    fun bestEffort(scene: AcousticScene, source: Vec3, listener: Vec3, environment: AcousticEnvironment): MediumPathIntegrator.Result {
        val straight = MediumPathIntegrator.integrate(scene, source, listener, environment)
        if (straight.boundaries.isEmpty()) return straight
        if (straight.boundaries.size == 1) return SingleBoundaryRefraction.solve(scene, source, listener, straight.boundaries[0], environment) ?: straight
        return solveParallel(scene, source, listener, straight, environment) ?: straight
    }

    private fun solveParallel(
        scene: AcousticScene,
        source: Vec3,
        listener: Vec3,
        straight: MediumPathIntegrator.Result,
        environment: AcousticEnvironment
    ): MediumPathIntegrator.Result? {
        val boundaries = straight.boundaries
        val axis = boundaries[0].axis
        if (boundaries.any { it.axis != axis }) return null
        var expected = straight.sourceMedium
        for (boundary in boundaries) {
            if (boundary.from.id() != expected.id()) return null
            expected = boundary.to
        }
        if (expected.id() != straight.listenerMedium.id()) return null

        val sourceNormal = component(source, axis)
        val listenerNormal = component(listener, axis)
        val directionSign = if (listenerNormal >= sourceNormal) 1.0 else -1.0
        var previousPlane = sourceNormal
        for (boundary in boundaries) {
            val delta = (boundary.coordinate - previousPlane) * directionSign
            if (delta <= 1.0e-8) return null
            previousPlane = boundary.coordinate
        }
        if ((listenerNormal - previousPlane) * directionSign <= 1.0e-8) return null

        val media = ArrayList<AcousticMedium>(boundaries.size + 1)
        media.add(straight.sourceMedium)
        for (boundary in boundaries) media.add(boundary.to)
        val normalThickness = DoubleArray(media.size)
        normalThickness[0] = abs(boundaries[0].coordinate - sourceNormal)
        var i = 1
        while (i < boundaries.size) {
            normalThickness[i] = abs(boundaries[i].coordinate - boundaries[i - 1].coordinate)
            i++
        }
        normalThickness[normalThickness.lastIndex] = abs(listenerNormal - boundaries.last().coordinate)

        val tangentDelta = tangent(listener.subtract(source), axis)
        val tangentLength = tangentDelta.length()
        val speeds = DoubleArray(media.size) { index -> MediumPathIntegrator.propagationSpeed(media[index], environment) }
        val p = solveHorizontalSlowness(normalThickness, speeds, tangentLength) ?: return null
        val tangential = DoubleArray(media.size)
        var tangentialTotal = 0.0
        i = 0
        while (i < media.size) {
            val s = (p * speeds[i]).coerceIn(0.0, 1.0 - 1.0e-12)
            val c = sqrt(1.0 - s * s)
            tangential[i] = if (normalThickness[i] <= 1.0e-12) 0.0 else normalThickness[i] * s / c
            tangentialTotal += tangential[i]
            i++
        }
        if (tangentLength > 1.0e-7 && abs(tangentialTotal - tangentLength) > maxOf(1.0e-6, tangentLength * 1.0e-6)) return null

        val tangentUnit = if (tangentLength <= 1.0e-12) Vec3(0.0, 0.0, 0.0) else tangentDelta.multiply(1.0 / tangentLength)
        val points = ArrayList<Vec3>(boundaries.size + 2)
        points.add(source)
        var cumulative = 0.0
        i = 0
        while (i < boundaries.size) {
            cumulative += tangential[i]
            val base = source.add(tangentUnit.multiply(cumulative))
            points.add(withComponent(base, axis, boundaries[i].coordinate))
            i++
        }
        points.add(listener)

        val spectrum = FloatArray(FrequencyBands.COUNT) { 1f }
        var distance = 0.0
        var delay = 0.0
        var airMeters = 0.0
        var liquidMeters = 0.0
        i = 0
        while (i < media.size) {
            val a = points[i]
            val b = points[i + 1]
            val segmentDistance = a.distance(b)
            if (segmentDistance <= 1.0e-9 || !segmentMatchesMedium(scene, a, b, media[i])) return null
            distance += segmentDistance
            delay += segmentDistance / speeds[i]
            MediumPathIntegrator.applyBulkTransmission(spectrum, media[i], environment, segmentDistance)
            if (media[i].id() == AcousticMedia.AIR.id()) airMeters += segmentDistance else liquidMeters += segmentDistance
            if (i < boundaries.size) {
                val cosIncidence = (normalThickness[i] / segmentDistance).coerceIn(0.0, 1.0)
                val gain = media[i].interfaceAmplitudeTransmissionTo(media[i + 1], cosIncidence)
                var band = 0
                while (band < spectrum.size) { spectrum[band] *= gain; band++ }
            }
            i++
        }

        return MediumPathIntegrator.Result(
            distance, delay, spectrum, airMeters, liquidMeters, boundaries.size,
            straight.sourceMedium, straight.listenerMedium, boundaries
        )
    }

    /** p = sin(theta)/c is conserved across parallel interfaces. */
    private fun solveHorizontalSlowness(thickness: DoubleArray, speeds: DoubleArray, tangentLength: Double): Double? {
        if (tangentLength <= 1.0e-12) return 0.0
        var maxSpeed = 0.0
        for (speed in speeds) maxSpeed = maxOf(maxSpeed, speed)
        if (maxSpeed <= 0.0) return null
        var lo = 0.0
        var hi = (1.0 / maxSpeed) * (1.0 - 1.0e-12)
        var iteration = 0
        while (iteration++ < 96) {
            val mid = (lo + hi) * 0.5
            var x = 0.0
            var i = 0
            while (i < thickness.size) {
                val s = mid * speeds[i]
                if (s >= 1.0) { x = Double.POSITIVE_INFINITY; break }
                x += thickness[i] * s / sqrt(maxOf(1.0e-30, 1.0 - s * s))
                i++
            }
            if (x >= tangentLength) hi = mid else lo = mid
        }
        return (lo + hi) * 0.5
    }

    private fun segmentMatchesMedium(scene: AcousticScene, start: Vec3, end: Vec3, medium: AcousticMedium): Boolean {
        val delta = end.subtract(start)
        val length = delta.length()
        if (length <= 1.0e-9) return true
        val dir = delta.multiply(1.0 / length)
        val epsilon = min(1.0e-4, length * 1.0e-4)
        val a = start.add(dir.multiply(epsilon))
        val b = end.subtract(dir.multiply(epsilon))
        if (a.distance(b) <= 1.0e-9) return true
        return MediumPathIntegrator.segmentMatchesMedium(scene, a, b, medium)
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
}
