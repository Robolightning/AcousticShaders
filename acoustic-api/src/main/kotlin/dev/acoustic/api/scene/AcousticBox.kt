package dev.acoustic.api.scene

class AcousticBox(
    @JvmField val minX: Double, @JvmField val minY: Double, @JvmField val minZ: Double,
    @JvmField val maxX: Double, @JvmField val maxY: Double, @JvmField val maxZ: Double
) {
    init {
        if (!finite(minX) || !finite(minY) || !finite(minZ) || !finite(maxX) || !finite(maxY) || !finite(maxZ)) throw IllegalArgumentException("non-finite acoustic box")
        if (minX < 0 || minY < 0 || minZ < 0 || maxX > 1 || maxY > 1 || maxZ > 1 || maxX <= minX || maxY <= minY || maxZ <= minZ) throw IllegalArgumentException("invalid acoustic box bounds")
    }
    fun volume(): Double = (maxX - minX) * (maxY - minY) * (maxZ - minZ)
    override fun equals(other: Any?): Boolean = this === other || (other is AcousticBox && java.lang.Double.compare(minX, other.minX) == 0 && java.lang.Double.compare(minY, other.minY) == 0 && java.lang.Double.compare(minZ, other.minZ) == 0 && java.lang.Double.compare(maxX, other.maxX) == 0 && java.lang.Double.compare(maxY, other.maxY) == 0 && java.lang.Double.compare(maxZ, other.maxZ) == 0)
    override fun hashCode(): Int = java.util.Objects.hash(minX, minY, minZ, maxX, maxY, maxZ)
    override fun toString(): String = "AcousticBox{$minX,$minY,$minZ -> $maxX,$maxY,$maxZ}"
    companion object { private fun finite(v: Double): Boolean = !v.isNaN() && !v.isInfinite() }
}
