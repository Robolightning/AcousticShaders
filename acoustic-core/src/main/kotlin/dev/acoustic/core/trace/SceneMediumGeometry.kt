package dev.acoustic.core.trace

import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticBox
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.api.scene.AcousticShape
import dev.acoustic.api.scene.AcousticVoxel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Scene-level effective geometry for volume media.
 *
 * Besides preserving vertical continuity between connected liquid voxels, simple bottom-up
 * fluid fills are reconstructed as a bilinear free surface using only neighboring cells from
 * the immutable snapshot.  This avoids both fake internal air sheets and block-step acoustic
 * surfaces without adding any live Minecraft World reads.
 */
object SceneMediumGeometry {
    private const val EPS = 1.0e-9
    private const val FOOTPRINT_EPS = 1.0e-9

    @JvmStatic
    fun effectiveShape(scene: AcousticScene, x: Int, y: Int, z: Int, voxel: AcousticVoxel = scene.voxelAt(x, y, z)): AcousticShape {
        if (voxel.solid()) return AcousticShape.EMPTY
        val medium = voxel.medium()
        val base = voxel.mediumShape()
        if (medium.id() == AcousticMedia.AIR.id() || base.isEmpty() || base.isFullCube()) return base

        val above = scene.voxelAt(x, y + 1, z)
        if (above.solid() || above.medium().id() != medium.id() || above.mediumShape().isEmpty()) return base

        val boxes = ArrayList<AcousticBox?>(base.boxCount() + above.mediumShape().boxCount())
        boxes.addAll(base.boxes())
        var added = false
        for (box in above.mediumShape().boxes()) {
            if (box.minY <= EPS && box.maxX > box.minX && box.maxZ > box.minZ) {
                boxes.add(AcousticBox(box.minX, 0.0, box.minZ, box.maxX, 1.0, box.maxZ))
                added = true
            }
        }
        return if (added) AcousticShape.of(boxes) else base
    }

    @JvmStatic
    fun mediumAt(scene: AcousticScene, pointX: Double, pointY: Double, pointZ: Double): AcousticMedium {
        val x = floor(pointX).toInt()
        val y = floor(pointY).toInt()
        val z = floor(pointZ).toInt()
        val voxel = scene.voxelAt(x, y, z)
        if (voxel.solid()) return AcousticMedia.AIR
        val medium = voxel.medium()
        if (medium.id() == AcousticMedia.AIR.id()) return AcousticMedia.AIR
        val localX = pointX - x
        val localY = pointY - y
        val localZ = pointZ - z
        val patch = surfacePatch(scene, x, y, z, voxel)
        if (patch != null) return if (localY < patch.height(localX, localZ) - EPS) medium else AcousticMedia.AIR
        val shape = effectiveShape(scene, x, y, z, voxel)
        return if (shape.contains(localX, localY, localZ)) medium else AcousticMedia.AIR
    }

    @JvmStatic
    fun occupiedIntervals(
        scene: AcousticScene,
        x: Int,
        y: Int,
        z: Int,
        voxel: AcousticVoxel,
        origin: Vec3,
        dir: Vec3,
        minT: Double,
        maxT: Double
    ): List<ShapeRaycast.Interval> {
        if (maxT <= minT || voxel.solid() || voxel.medium().id() == AcousticMedia.AIR.id()) return emptyList()
        val patch = surfacePatch(scene, x, y, z, voxel)
        if (patch == null) {
            return ShapeRaycast.occupiedIntervals(effectiveShape(scene, x, y, z, voxel), x, y, z, origin, dir, minT, maxT)
        }
        return patch.occupiedIntervals(x, y, z, origin, dir, minT, maxT)
    }

    @JvmStatic
    fun occupiedLength(
        scene: AcousticScene,
        x: Int,
        y: Int,
        z: Int,
        voxel: AcousticVoxel,
        origin: Vec3,
        dir: Vec3,
        minT: Double,
        maxT: Double
    ): Double {
        var total = 0.0
        for (interval in occupiedIntervals(scene, x, y, z, voxel, origin, dir, minT, maxT)) total += interval.far - interval.near
        return total
    }

    private fun surfacePatch(scene: AcousticScene, x: Int, y: Int, z: Int, voxel: AcousticVoxel): SurfacePatch? {
        if (voxel.solid() || voxel.medium().id() == AcousticMedia.AIR.id()) return null
        val ownHeight = simpleRawFillHeight(voxel) ?: return null
        if (hasFullVerticalContinuation(scene, x, y, z, voxel.medium().id())) return null

        val mediumId = voxel.medium().id()
        val h00 = cornerHeight(scene, x, y, z, mediumId, ownHeight, -1, -1)
        val h10 = cornerHeight(scene, x, y, z, mediumId, ownHeight, 1, -1)
        val h01 = cornerHeight(scene, x, y, z, mediumId, ownHeight, -1, 1)
        val h11 = cornerHeight(scene, x, y, z, mediumId, ownHeight, 1, 1)
        if (abs(h00 - ownHeight) < EPS && abs(h10 - ownHeight) < EPS && abs(h01 - ownHeight) < EPS && abs(h11 - ownHeight) < EPS) return null
        return SurfacePatch(h00, h10, h01, h11)
    }

    private fun cornerHeight(
        scene: AcousticScene,
        x: Int,
        y: Int,
        z: Int,
        mediumId: String,
        ownHeight: Double,
        sx: Int,
        sz: Int
    ): Double {
        var sum = ownHeight
        var count = 1
        val candidates = arrayOf(
            intArrayOf(x + sx, z),
            intArrayOf(x, z + sz),
            intArrayOf(x + sx, z + sz)
        )
        for (candidate in candidates) {
            val height = columnHeight(scene, candidate[0], y, candidate[1], mediumId)
            if (height != null) { sum += height; count++ }
        }
        return (sum / count).coerceIn(0.0, 1.0)
    }

    private fun columnHeight(scene: AcousticScene, x: Int, y: Int, z: Int, mediumId: String): Double? {
        val voxel = scene.voxelAt(x, y, z)
        if (voxel.solid() || voxel.medium().id() != mediumId) return null
        if (hasFullVerticalContinuation(scene, x, y, z, mediumId)) return 1.0
        return simpleRawFillHeight(voxel)
    }

    private fun hasFullVerticalContinuation(scene: AcousticScene, x: Int, y: Int, z: Int, mediumId: String): Boolean {
        val above = scene.voxelAt(x, y + 1, z)
        if (above.solid() || above.medium().id() != mediumId) return false
        return simpleRawFillHeight(above) != null
    }

    private fun simpleRawFillHeight(voxel: AcousticVoxel): Double? {
        val shape = voxel.mediumShape()
        if (shape.isFullCube()) return 1.0
        if (shape.boxCount() != 1) return null
        val box = shape.box(0)
        if (abs(box.minX) > FOOTPRINT_EPS || abs(box.minY) > FOOTPRINT_EPS || abs(box.minZ) > FOOTPRINT_EPS) return null
        if (abs(box.maxX - 1.0) > FOOTPRINT_EPS || abs(box.maxZ - 1.0) > FOOTPRINT_EPS) return null
        return box.maxY
    }

    private class SurfacePatch(
        private val h00: Double,
        private val h10: Double,
        private val h01: Double,
        private val h11: Double
    ) {
        private val ax = h10 - h00
        private val az = h01 - h00
        private val cross = h00 - h10 - h01 + h11

        fun height(localX: Double, localZ: Double): Double = h00 + ax * localX + az * localZ + cross * localX * localZ

        fun normal(localX: Double, localZ: Double): Vec3 {
            val dhdx = ax + cross * localZ
            val dhdz = az + cross * localX
            return Vec3(-dhdx, 1.0, -dhdz).normalize()
        }

        fun occupiedIntervals(cellX: Int, cellY: Int, cellZ: Int, origin: Vec3, dir: Vec3, minT: Double, maxT: Double): List<ShapeRaycast.Interval> {
            if (maxT <= minT) return emptyList()
            val u0 = origin.x - cellX
            val v0 = origin.z - cellZ
            val a = -cross * dir.x * dir.z
            val b = dir.y - ax * dir.x - az * dir.z - cross * (u0 * dir.z + v0 * dir.x)
            val c = origin.y - cellY - h00 - ax * u0 - az * v0 - cross * u0 * v0
            val roots = rootsInRange(a, b, c, minT, maxT)
            val points = DoubleArray(roots.size + 2)
            points[0] = minT
            var ri = 0
            while (ri < roots.size) { points[ri + 1] = roots[ri]; ri++ }
            points[points.lastIndex] = maxT

            val intervals = ArrayList<ShapeRaycast.Interval>(2)
            var segment = 0
            while (segment + 1 < points.size) {
                val start = points[segment]
                val end = points[segment + 1]
                if (end > start + EPS) {
                    val mid = (start + end) * 0.5
                    if (valueAt(a, b, c, mid) < -EPS) {
                        val nearNormal = if (segment == 0) Vec3(0.0, 0.0, 0.0) else normalAt(cellX, cellZ, origin, dir, start)
                        val farNormal = if (segment + 1 == points.lastIndex) Vec3(0.0, 0.0, 0.0) else normalAt(cellX, cellZ, origin, dir, end)
                        intervals.add(ShapeRaycast.Interval(start, end, nearNormal, farNormal))
                    }
                }
                segment++
            }
            return java.util.Collections.unmodifiableList(intervals)
        }

        private fun normalAt(cellX: Int, cellZ: Int, origin: Vec3, dir: Vec3, t: Double): Vec3 {
            val localX = (origin.x + dir.x * t - cellX).coerceIn(0.0, 1.0)
            val localZ = (origin.z + dir.z * t - cellZ).coerceIn(0.0, 1.0)
            return normal(localX, localZ)
        }

        private fun rootsInRange(a: Double, b: Double, c: Double, minT: Double, maxT: Double): DoubleArray {
            val raw = ArrayList<Double>(2)
            if (abs(a) < EPS) {
                if (abs(b) >= EPS) raw.add(-c / b)
            } else {
                val disc = b * b - 4.0 * a * c
                if (disc >= -EPS) {
                    val root = sqrt(max(0.0, disc))
                    val q = -0.5 * (b + if (b >= 0.0) root else -root)
                    if (abs(q) > EPS) {
                        raw.add(q / a)
                        raw.add(c / q)
                    } else {
                        raw.add(-b / (2.0 * a))
                    }
                }
            }
            raw.sort()
            val filtered = ArrayList<Double>(2)
            for (t in raw) {
                if (t > minT + EPS && t < maxT - EPS && (filtered.isEmpty() || abs(t - filtered.last()) > 1.0e-8)) filtered.add(t)
            }
            return filtered.toDoubleArray()
        }

        private fun valueAt(a: Double, b: Double, c: Double, t: Double): Double = (a * t + b) * t + c
    }
}
