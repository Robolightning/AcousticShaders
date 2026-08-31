package dev.acoustic.core.scene

import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.api.scene.AcousticVoxel

object VoxelSnapshotBuilder {
    @JvmStatic
    fun copyRegion(
        scene: AcousticScene?, minX: Int, minY: Int, minZ: Int,
        sizeX: Int, sizeY: Int, sizeZ: Int,
        outside: AcousticVoxel, generation: Long
    ): ImmutableVoxelSnapshot {
        val requiredScene = scene ?: throw NullPointerException("scene")
        require(sizeX > 0 && sizeY > 0 && sizeZ > 0) { "invalid size" }
        val count = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
        require(count <= Int.MAX_VALUE.toLong()) { "snapshot too large" }
        val voxels = arrayOfNulls<AcousticVoxel>(count.toInt())
        var i = 0
        var y = 0
        while (y < sizeY) {
            var z = 0
            while (z < sizeZ) {
                var x = 0
                while (x < sizeX) {
                    voxels[i++] = requiredScene.voxelAt(minX + x, minY + y, minZ + z)
                    x++
                }
                z++
            }
            y++
        }
        @Suppress("UNCHECKED_CAST")
        return ImmutableVoxelSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, outside, voxels as Array<AcousticVoxel>, generation)
    }
}
