package dev.acoustic.api.math

import kotlin.math.sqrt

class Vec3(@JvmField val x: Double, @JvmField val y: Double, @JvmField val z: Double) {
    fun add(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    fun subtract(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    fun multiply(scalar: Double): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)
    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z
    fun lengthSquared(): Double = dot(this)
    fun length(): Double = sqrt(lengthSquared())
    fun distance(other: Vec3): Double = subtract(other).length()
    fun normalize(): Vec3 {
        val length = length()
        if (length == 0.0) throw IllegalStateException("Cannot normalize zero vector")
        return multiply(1.0 / length)
    }
    override fun equals(other: Any?): Boolean = this === other || (other is Vec3 && java.lang.Double.compare(other.x, x) == 0 && java.lang.Double.compare(other.y, y) == 0 && java.lang.Double.compare(other.z, z) == 0)
    override fun hashCode(): Int = java.util.Objects.hash(x, y, z)
    override fun toString(): String = "Vec3{$x,$y,$z}"
}
