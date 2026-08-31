package dev.acoustic.core.trace

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.api.scene.AcousticVoxel

object VoxelDda {
    fun interface Visitor {
        /** Return false to stop traversal. */
        fun visit(x: Int, y: Int, z: Int, traveled: Double, voxel: AcousticVoxel): Boolean
    }

    fun interface CellVisitor {
        /** tEnter/tExit are distances along the normalized start->end ray. Return false to stop. */
        fun visit(x: Int, y: Int, z: Int, tEnter: Double, tExit: Double, voxel: AcousticVoxel): Boolean
    }

    @JvmStatic
    fun trace(scene: AcousticScene, start: Vec3, end: Vec3, visitor: Visitor) {
        traceCells(scene, start, end, CellVisitor { x, y, z, tEnter, _, voxel -> visitor.visit(x, y, z, tEnter, voxel) })
    }

    @JvmStatic
    fun traceCells(scene: AcousticScene, start: Vec3, end: Vec3, visitor: CellVisitor) {
        val delta = end.subtract(start)
        val maxDistance = delta.length()
        if (maxDistance == 0.0) return
        val dir = delta.multiply(1.0 / maxDistance)
        var x = floor(start.x); var y = floor(start.y); var z = floor(start.z)
        val endX = floor(end.x); val endY = floor(end.y); val endZ = floor(end.z)
        val stepX = sign(dir.x); val stepY = sign(dir.y); val stepZ = sign(dir.z)
        val tDeltaX = if (stepX == 0) Double.POSITIVE_INFINITY else Math.abs(1.0 / dir.x)
        val tDeltaY = if (stepY == 0) Double.POSITIVE_INFINITY else Math.abs(1.0 / dir.y)
        val tDeltaZ = if (stepZ == 0) Double.POSITIVE_INFINITY else Math.abs(1.0 / dir.z)
        var tMaxX = firstBoundaryDistance(start.x, x, stepX, dir.x)
        var tMaxY = firstBoundaryDistance(start.y, y, stepY, dir.y)
        var tMaxZ = firstBoundaryDistance(start.z, z, stepZ, dir.z)
        var entered = 0.0
        var guard = 0
        while (guard++ < 1_000_000) {
            val exit = Math.min(maxDistance, Math.min(tMaxX, Math.min(tMaxY, tMaxZ)))
            if (!visitor.visit(x, y, z, entered, Math.max(entered, exit), scene.voxelAt(x, y, z))) return
            if (x == endX && y == endY && z == endZ) return
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                entered = tMaxX; tMaxX += tDeltaX; x += stepX
            } else if (tMaxY <= tMaxZ) {
                entered = tMaxY; tMaxY += tDeltaY; y += stepY
            } else {
                entered = tMaxZ; tMaxZ += tDeltaZ; z += stepZ
            }
            if (entered > maxDistance) return
        }
        throw IllegalStateException("DDA traversal guard exceeded")
    }

    private fun floor(value: Double): Int = Math.floor(value).toInt()
    private fun sign(value: Double): Int = if (value > 0.0) 1 else if (value < 0.0) -1 else 0
    private fun firstBoundaryDistance(origin: Double, cell: Int, step: Int, dir: Double): Double {
        if (step == 0) return Double.POSITIVE_INFINITY
        val boundary = if (step > 0) cell + 1.0 else cell.toDouble()
        return (boundary - origin) / dir
    }
}
