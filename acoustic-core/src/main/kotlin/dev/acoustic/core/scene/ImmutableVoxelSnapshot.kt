package dev.acoustic.core.scene

import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.api.scene.AcousticVoxel
import dev.acoustic.api.environment.AcousticMedia

/** Compact immutable rectangular scene snapshot safe for concurrent worker reads. */
class ImmutableVoxelSnapshot(
    private val minX: Int, private val minY: Int, private val minZ: Int,
    private val sizeX: Int, private val sizeY: Int, private val sizeZ: Int,
    outside: AcousticVoxel?, voxels: Array<out AcousticVoxel?>, private val generation: Long
) : AcousticScene {
    private val outside: AcousticVoxel
    private val voxels: Array<AcousticVoxel>
    private val hasNonAirMedia: Boolean

    init {
        require(sizeX > 0 && sizeY > 0 && sizeZ > 0) { "snapshot dimensions must be positive" }
        val count = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
        require(count <= Int.MAX_VALUE.toLong()) { "snapshot too large" }
        require(voxels.size == count.toInt()) { "wrong voxel array length" }
        this.outside = outside ?: throw NullPointerException("outside")
        this.voxels = Array(voxels.size) { index -> voxels[index] ?: throw NullPointerException("voxel") }
        this.hasNonAirMedia = this.voxels.any { !it.solid() && it.medium().id() != AcousticMedia.AIR.id() } || (!this.outside.solid() && this.outside.medium().id() != AcousticMedia.AIR.id())
    }

    override fun voxelAt(x: Int, y: Int, z: Int): AcousticVoxel {
        val lx = x - minX
        val ly = y - minY
        val lz = z - minZ
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ) return outside
        return voxels[(ly * sizeZ + lz) * sizeX + lx]
    }

    override fun revision(): Long = generation
    override fun containsNonAirMedia(): Boolean = hasNonAirMedia
    fun generation(): Long = generation
    fun minX(): Int = minX
    fun minY(): Int = minY
    fun minZ(): Int = minZ
    fun sizeX(): Int = sizeX
    fun sizeY(): Int = sizeY
    fun sizeZ(): Int = sizeZ
}
