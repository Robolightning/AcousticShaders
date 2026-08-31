package dev.acoustic.core.trace

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticVoxel

class RayHit(private val x: Int, private val y: Int, private val z: Int, private val distance: Double, private val position: Vec3, private val normal: Vec3, private val voxel: AcousticVoxel) {
    fun x(): Int = x
    fun y(): Int = y
    fun z(): Int = z
    fun distance(): Double = distance
    fun position(): Vec3 = position
    fun normal(): Vec3 = normal
    fun voxel(): AcousticVoxel = voxel
}
