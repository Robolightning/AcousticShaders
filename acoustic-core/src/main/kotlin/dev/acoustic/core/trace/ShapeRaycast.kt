package dev.acoustic.core.trace

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticBox
import dev.acoustic.api.scene.AcousticShape

/** Exact ray/AABB operations for multipart per-cell acoustic shapes. */
object ShapeRaycast {
    private const val EPS = 1.0e-9

    @JvmStatic
    fun first(shape: AcousticShape, cellX: Int, cellY: Int, cellZ: Int, origin: Vec3, dir: Vec3, minT: Double, maxT: Double): Intersection? {
        var best: Intersection? = null
        var i = 0
        while (i < shape.boxCount()) {
            val hit = box(shape.box(i), cellX, cellY, cellZ, origin, dir, minT, maxT)
            if (hit != null && (best == null || hit.near < best.near)) best = hit
            i++
        }
        return best
    }

    /** Total union length of occupied material along a unit-direction ray inside this cell interval. */
    @JvmStatic
    fun occupiedLength(shape: AcousticShape, cellX: Int, cellY: Int, cellZ: Int, origin: Vec3, dir: Vec3, minT: Double, maxT: Double): Double {
        val intervals = occupiedIntervals(shape, cellX, cellY, cellZ, origin, dir, minT, maxT)
        if (intervals.isEmpty()) return 0.0
        var total = 0.0
        for (interval in intervals) total += interval.far - interval.near
        return total
    }

    /** Ordered, merged occupied intervals. Normals point out of the occupied volume. */
    @JvmStatic
    fun occupiedIntervals(shape: AcousticShape, cellX: Int, cellY: Int, cellZ: Int, origin: Vec3, dir: Vec3, minT: Double, maxT: Double): List<Interval> {
        if (shape.isEmpty() || maxT <= minT) return emptyList()
        val raw = ArrayList<Intersection>()
        var i = 0
        while (i < shape.boxCount()) {
            val hit = box(shape.box(i), cellX, cellY, cellZ, origin, dir, minT, maxT)
            if (hit != null && hit.far > hit.near + EPS) raw.add(hit)
            i++
        }
        if (raw.isEmpty()) return emptyList()
        raw.sortWith(Comparator { a, b -> java.lang.Double.compare(a.near, b.near) })
        val merged = ArrayList<Interval>(raw.size)
        var start = raw[0].near
        var end = raw[0].far
        var nearNormal = raw[0].normal
        var farNormal = raw[0].farNormal
        i = 1
        while (i < raw.size) {
            val hit = raw[i]
            if (hit.near <= end + EPS) {
                if (hit.far > end) { end = hit.far; farNormal = hit.farNormal }
            } else {
                merged.add(Interval(start, end, nearNormal, farNormal))
                start = hit.near; end = hit.far; nearNormal = hit.normal; farNormal = hit.farNormal
            }
            i++
        }
        merged.add(Interval(start, end, nearNormal, farNormal))
        return java.util.Collections.unmodifiableList(merged)
    }

    private fun box(box: AcousticBox, cx: Int, cy: Int, cz: Int, origin: Vec3, dir: Vec3, minT: Double, maxT: Double): Intersection? {
        var near = Double.NEGATIVE_INFINITY
        var far = Double.POSITIVE_INFINITY
        var normal = Vec3(0.0, 0.0, 0.0)
        var farNormal = Vec3(0.0, 0.0, 0.0)
        val origins = doubleArrayOf(origin.x, origin.y, origin.z)
        val directions = doubleArrayOf(dir.x, dir.y, dir.z)
        val min = doubleArrayOf(cx + box.minX, cy + box.minY, cz + box.minZ)
        val max = doubleArrayOf(cx + box.maxX, cy + box.maxY, cz + box.maxZ)
        var axis = 0
        while (axis < 3) {
            if (Math.abs(directions[axis]) < 1e-15) {
                if (origins[axis] < min[axis] - EPS || origins[axis] > max[axis] + EPS) return null
                axis++
                continue
            }
            val t1 = (min[axis] - origins[axis]) / directions[axis]
            val t2 = (max[axis] - origins[axis]) / directions[axis]
            val axisNear = Math.min(t1, t2)
            val axisFar = Math.max(t1, t2)
            val axisNormal = normal(axis, if (directions[axis] > 0.0) -1 else 1)
            val axisFarNormal = normal(axis, if (directions[axis] > 0.0) 1 else -1)
            if (axisNear > near) { near = axisNear; normal = axisNormal }
            if (axisFar < far) { far = axisFar; farNormal = axisFarNormal }
            if (far < near - EPS) return null
            axis++
        }
        var clippedNear = Math.max(near, minT)
        val clippedFar = Math.min(far, maxT)
        if (clippedFar < clippedNear - EPS) return null
        if (clippedNear < minT) clippedNear = minT
        return Intersection(clippedNear, clippedFar, normal, farNormal)
    }

    private fun normal(axis: Int, sign: Int): Vec3 = when (axis) {
        0 -> Vec3(sign.toDouble(), 0.0, 0.0)
        1 -> Vec3(0.0, sign.toDouble(), 0.0)
        else -> Vec3(0.0, 0.0, sign.toDouble())
    }

    class Intersection internal constructor(
        @JvmField val near: Double,
        @JvmField val far: Double,
        @JvmField val normal: Vec3,
        @JvmField val farNormal: Vec3
    )

    class Interval internal constructor(
        @JvmField val near: Double,
        @JvmField val far: Double,
        @JvmField val nearNormal: Vec3,
        @JvmField val farNormal: Vec3
    )
}
