package dev.acoustic.core.trace

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene

/** First-hit ray query against exact multipart per-cell acoustic shapes. */
object VoxelRaycast {
    @JvmStatic
    fun firstSolid(scene: AcousticScene, origin: Vec3, direction: Vec3, maxDistance: Double): RayHit? {
        if (maxDistance <= 0.0) return null
        val dir = direction.normalize()
        var x = floor(origin.x)
        var y = floor(origin.y)
        var z = floor(origin.z)
        val stepX = sign(dir.x)
        val stepY = sign(dir.y)
        val stepZ = sign(dir.z)
        val deltaX = if (stepX == 0) Double.POSITIVE_INFINITY else Math.abs(1.0 / dir.x)
        val deltaY = if (stepY == 0) Double.POSITIVE_INFINITY else Math.abs(1.0 / dir.y)
        val deltaZ = if (stepZ == 0) Double.POSITIVE_INFINITY else Math.abs(1.0 / dir.z)
        var nextX = boundary(origin.x, x, stepX, dir.x)
        var nextY = boundary(origin.y, y, stepY, dir.y)
        var nextZ = boundary(origin.z, z, stepZ, dir.z)
        var entered = 0.0
        var guard = 0
        while (guard < 1_000_000) {
            val exit = Math.min(maxDistance, Math.min(nextX, Math.min(nextY, nextZ)))
            val voxel = scene.voxelAt(x, y, z)
            if (voxel.solid() && !voxel.shape().isEmpty()) {
                val hit = ShapeRaycast.first(voxel.shape(), x, y, z, origin, dir, entered, exit)
                if (hit != null && hit.near <= maxDistance + 1e-9) {
                    return RayHit(x, y, z, hit.near, origin.add(dir.multiply(hit.near)), hit.normal, voxel)
                }
            }
            if (exit >= maxDistance) return null
            if (nextX <= nextY && nextX <= nextZ) {
                entered = nextX; nextX += deltaX; x += stepX
            } else if (nextY <= nextZ) {
                entered = nextY; nextY += deltaY; y += stepY
            } else {
                entered = nextZ; nextZ += deltaZ; z += stepZ
            }
            guard++
        }
        throw IllegalStateException("raycast traversal guard exceeded")
    }

    private fun floor(value: Double): Int = Math.floor(value).toInt()
    private fun sign(value: Double): Int = if (value > 0.0) 1 else if (value < 0.0) -1 else 0
    private fun boundary(origin: Double, cell: Int, step: Int, dir: Double): Double {
        if (step == 0) return Double.POSITIVE_INFINITY
        val edge = if (step > 0) cell + 1.0 else cell.toDouble()
        return (edge - origin) / dir
    }
}
