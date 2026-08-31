package dev.acoustic.core.trace

import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import kotlin.math.abs

/** First open-fluid interface encountered by a geometrical ray. Solid faces are deliberately ignored. */
object MediumBoundaryRaycast {
    private const val EPS = 1.0e-9

    class Hit(
        val distance: Double,
        val position: Vec3,
        /** Normal points back into the incident (`from`) medium. */
        val normal: Vec3,
        val from: AcousticMedium,
        val to: AcousticMedium
    )

    @JvmStatic
    fun first(scene: AcousticScene, origin: Vec3, direction: Vec3, maxDistance: Double): Hit? {
        if (maxDistance <= 0.0 || direction.length() <= 1.0e-12) return null
        val dir = direction.normalize()
        val end = origin.add(dir.multiply(maxDistance))
        var previousMedium: AcousticMedium? = null
        var previousX = 0
        var previousY = 0
        var previousZ = 0
        var previousOpen = false
        var result: Hit? = null

        fun span(medium: AcousticMedium, x: Int, y: Int, z: Int, tStart: Double, startNormal: Vec3?): Boolean {
            val previous = previousMedium
            if (previousOpen && previous != null && previous.id() != medium.id()) {
                var normal = startNormal
                if (normal == null) {
                    val dx = x - previousX
                    val dy = y - previousY
                    val dz = z - previousZ
                    if (abs(dx) + abs(dy) + abs(dz) == 1) {
                        normal = when {
                            dx > 0 -> Vec3(-1.0, 0.0, 0.0)
                            dx < 0 -> Vec3(1.0, 0.0, 0.0)
                            dy > 0 -> Vec3(0.0, -1.0, 0.0)
                            dy < 0 -> Vec3(0.0, 1.0, 0.0)
                            dz > 0 -> Vec3(0.0, 0.0, -1.0)
                            else -> Vec3(0.0, 0.0, 1.0)
                        }
                    }
                }
                if (normal != null) {
                    result = Hit(tStart, origin.add(dir.multiply(tStart)), normal, previous, medium)
                    return false
                }
            }
            previousMedium = medium
            previousX = x; previousY = y; previousZ = z
            previousOpen = true
            return true
        }

        VoxelDda.traceCells(scene, origin, end, VoxelDda.CellVisitor { x, y, z, tEnter, tExit, voxel ->
            if (voxel.solid()) {
                previousOpen = false
                previousMedium = null
                return@CellVisitor true
            }
            val medium = voxel.medium()
            val mediumShape = SceneMediumGeometry.effectiveShape(scene, x, y, z, voxel)
            if (medium.id() == AcousticMedia.AIR.id() || mediumShape.isEmpty()) {
                return@CellVisitor span(AcousticMedia.AIR, x, y, z, tEnter, null)
            }
            if (mediumShape.isFullCube()) {
                return@CellVisitor span(medium, x, y, z, tEnter, null)
            }

            val intervals = SceneMediumGeometry.occupiedIntervals(scene, x, y, z, voxel, origin, dir, tEnter, tExit)
            var cursor = tEnter
            var airStartNormal: Vec3? = null
            for (interval in intervals) {
                if (interval.near > cursor + EPS) {
                    if (!span(AcousticMedia.AIR, x, y, z, cursor, airStartNormal)) return@CellVisitor false
                }
                val entryNormal = if (interval.near > tEnter + EPS) interval.nearNormal else null
                if (!span(medium, x, y, z, interval.near, entryNormal)) return@CellVisitor false
                cursor = interval.far
                airStartNormal = negateNormal(interval.farNormal)
            }
            if (cursor < tExit - EPS) {
                if (!span(AcousticMedia.AIR, x, y, z, cursor, airStartNormal)) return@CellVisitor false
            }
            true
        })
        return result
    }

    private fun negateNormal(normal: Vec3): Vec3 = Vec3(
        if (normal.x == 0.0) 0.0 else -normal.x,
        if (normal.y == 0.0) 0.0 else -normal.y,
        if (normal.z == 0.0) 0.0 else -normal.z
    )
}
