package dev.acoustic.api.scene

interface AcousticScene {
    fun voxelAt(x: Int, y: Int, z: Int): AcousticVoxel
    fun revision(): Long

    /** True when this snapshot contains a non-air propagation medium such as water. */
    fun containsNonAirMedia(): Boolean = false
}
