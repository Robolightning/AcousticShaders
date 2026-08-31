package dev.acoustic.core.trace

import dev.acoustic.api.environment.AcousticEnvironment
import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import kotlin.math.abs

/** Integrates propagation through non-solid volume media along one straight segment. */
object MediumPathIntegrator {
    private const val EPS = 1.0e-9

    class Boundary(
        val axis: Int,
        val coordinate: Double,
        val from: AcousticMedium,
        val to: AcousticMedium
    )

    class Result(
        val distanceMeters: Double,
        val delaySeconds: Double,
        transmission: FloatArray,
        val airMeters: Double,
        val liquidMeters: Double,
        val interfaceCount: Int,
        val sourceMedium: AcousticMedium,
        val listenerMedium: AcousticMedium,
        boundaries: List<Boundary>
    ) {
        val transmission: FloatArray = transmission.clone()
        val boundaries: List<Boundary> = java.util.Collections.unmodifiableList(ArrayList(boundaries))
    }

    @JvmStatic
    fun integrate(scene: AcousticScene, start: Vec3, end: Vec3, environment: AcousticEnvironment): Result {
        val distance = start.distance(end)
        val sourceMedium = mediumAt(scene, start)
        val listenerMedium = mediumAt(scene, end)
        if (distance <= 1.0e-12) {
            return Result(0.0, 0.0, FloatArray(FrequencyBands.COUNT) { 1f }, 0.0, 0.0, 0, sourceMedium, listenerMedium, emptyList())
        }

        val dir = end.subtract(start).normalize()
        val spectrum = FloatArray(FrequencyBands.COUNT) { 1f }
        val boundaries = ArrayList<Boundary>()
        var delay = 0.0
        var airMeters = 0.0
        var liquidMeters = 0.0
        var previousOpenMedium: AcousticMedium? = null
        var previousX = 0
        var previousY = 0
        var previousZ = 0
        var havePreviousOpenSpan = false

        fun transition(
            medium: AcousticMedium,
            x: Int,
            y: Int,
            z: Int,
            tStart: Double,
            startNormal: Vec3?
        ) {
            val previous = previousOpenMedium
            if (!havePreviousOpenSpan || previous == null || previous.id() == medium.id()) return

            var axis = -1
            var coordinate = Double.NaN
            if (startNormal != null && startNormal.length() > 0.5) {
                axis = axisOf(startNormal)
                coordinate = component(start.add(dir.multiply(tStart)), axis)
            } else {
                val dx = x - previousX
                val dy = y - previousY
                val dz = z - previousZ
                if (abs(dx) + abs(dy) + abs(dz) == 1) {
                    axis = if (dx != 0) 0 else if (dy != 0) 1 else 2
                    coordinate = when (axis) {
                        0 -> maxOf(x, previousX).toDouble()
                        1 -> maxOf(y, previousY).toDouble()
                        else -> maxOf(z, previousZ).toDouble()
                    }
                }
            }
            if (axis < 0 || !coordinate.isFinite()) return
            val cosIncidence = when (axis) { 0 -> abs(dir.x); 1 -> abs(dir.y); else -> abs(dir.z) }
            val interfaceGain = previous.interfaceAmplitudeTransmissionTo(medium, cosIncidence)
            var band = 0
            while (band < spectrum.size) { spectrum[band] *= interfaceGain; band++ }
            boundaries.add(Boundary(axis, coordinate, previous, medium))
        }

        fun openSpan(
            medium: AcousticMedium,
            x: Int,
            y: Int,
            z: Int,
            tStart: Double,
            tEnd: Double,
            startNormal: Vec3?
        ) {
            val segment = (tEnd - tStart).coerceAtLeast(0.0)
            if (segment <= EPS) return
            transition(medium, x, y, z, tStart, startNormal)
            delay += segment / propagationSpeed(medium, environment)
            applyBulkTransmission(spectrum, medium, environment, segment)
            if (medium.id() == AcousticMedia.AIR.id()) airMeters += segment else liquidMeters += segment
            previousOpenMedium = medium
            previousX = x; previousY = y; previousZ = z
            havePreviousOpenSpan = true
        }

        VoxelDda.traceCells(scene, start, end, VoxelDda.CellVisitor { x, y, z, tEnter, tExit, voxel ->
            val segment = (tExit - tEnter).coerceAtLeast(0.0)
            if (voxel.solid()) {
                // Surface-material transmission owns solids. Preserve legacy propagation timing there.
                delay += segment / environment.speedOfSoundMetersPerSecond()
                var band = 0
                while (band < spectrum.size) { spectrum[band] *= environment.airTransmission(band, segment); band++ }
                havePreviousOpenSpan = false
                previousOpenMedium = null
                return@CellVisitor true
            }

            val medium = voxel.medium()
            val mediumShape = SceneMediumGeometry.effectiveShape(scene, x, y, z, voxel)
            if (medium.id() == AcousticMedia.AIR.id() || mediumShape.isEmpty()) {
                openSpan(AcousticMedia.AIR, x, y, z, tEnter, tExit, null)
                return@CellVisitor true
            }
            if (mediumShape.isFullCube()) {
                openSpan(medium, x, y, z, tEnter, tExit, null)
                return@CellVisitor true
            }

            val intervals = SceneMediumGeometry.occupiedIntervals(scene, x, y, z, voxel, start, dir, tEnter, tExit)
            var cursor = tEnter
            var nextBoundaryNormal: Vec3? = null
            for (interval in intervals) {
                if (interval.near > cursor + EPS) {
                    openSpan(AcousticMedia.AIR, x, y, z, cursor, interval.near, nextBoundaryNormal)
                }
                val entryNormal = if (interval.near > tEnter + EPS) interval.nearNormal else null
                openSpan(medium, x, y, z, interval.near, interval.far, entryNormal)
                cursor = interval.far
                nextBoundaryNormal = interval.farNormal
            }
            if (cursor < tExit - EPS) {
                openSpan(AcousticMedia.AIR, x, y, z, cursor, tExit, nextBoundaryNormal)
            }
            true
        })

        return Result(distance, delay, spectrum, airMeters, liquidMeters, boundaries.size, sourceMedium, listenerMedium, boundaries)
    }

    @JvmStatic
    fun mediumAt(scene: AcousticScene, point: Vec3): AcousticMedium =
        SceneMediumGeometry.mediumAt(scene, point.x, point.y, point.z)

    /** True only when every non-solid point of the segment belongs to the requested medium. */
    @JvmStatic
    fun segmentMatchesMedium(scene: AcousticScene, start: Vec3, end: Vec3, medium: AcousticMedium): Boolean {
        val delta = end.subtract(start)
        val length = delta.length()
        if (length <= EPS) return true
        val dir = delta.multiply(1.0 / length)
        var valid = true
        VoxelDda.traceCells(scene, start, end, VoxelDda.CellVisitor { x, y, z, tEnter, tExit, voxel ->
            if (voxel.solid()) { valid = false; return@CellVisitor false }
            val total = (tExit - tEnter).coerceAtLeast(0.0)
            if (total <= EPS) return@CellVisitor true
            val wantedAir = medium.id() == AcousticMedia.AIR.id()
            val cellMedium = voxel.medium()
            val mediumShape = SceneMediumGeometry.effectiveShape(scene, x, y, z, voxel)
            val occupied = if (cellMedium.id() == AcousticMedia.AIR.id() || mediumShape.isEmpty()) 0.0 else
                SceneMediumGeometry.occupiedLength(scene, x, y, z, voxel, start, dir, tEnter, tExit)
            val matches = if (wantedAir) {
                occupied <= 1.0e-7
            } else {
                cellMedium.id() == medium.id() && occupied >= total - 1.0e-7
            }
            if (!matches) { valid = false; false } else true
        })
        return valid
    }

    @JvmStatic
    fun propagationSpeed(medium: AcousticMedium, environment: AcousticEnvironment): Double =
        if (medium.id() == AcousticMedia.AIR.id()) environment.speedOfSoundMetersPerSecond() else medium.speedOfSoundMetersPerSecond()

    @JvmStatic
    fun applyBulkTransmission(spectrum: FloatArray, medium: AcousticMedium, environment: AcousticEnvironment, distanceMeters: Double) {
        if (distanceMeters <= 0.0) return
        var band = 0
        if (medium.id() == AcousticMedia.AIR.id()) {
            while (band < spectrum.size) { spectrum[band] *= environment.airTransmission(band, distanceMeters); band++ }
        } else {
            while (band < spectrum.size) { spectrum[band] *= medium.transmission(band, distanceMeters); band++ }
        }
    }

    private fun axisOf(normal: Vec3): Int = when {
        abs(normal.x) > 0.5 -> 0
        abs(normal.y) > 0.5 -> 1
        else -> 2
    }

    private fun component(v: Vec3, axis: Int): Double = when (axis) { 0 -> v.x; 1 -> v.y; else -> v.z }
}
