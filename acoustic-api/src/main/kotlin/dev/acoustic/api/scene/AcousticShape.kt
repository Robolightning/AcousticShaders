package dev.acoustic.api.scene

import java.util.Collections

/** Immutable collision-derived occupied geometry for one acoustic cell. */
class AcousticShape private constructor(private val boxes: Array<AcousticBox>, private val occupancy: Double) {
    fun isEmpty(): Boolean = boxes.isEmpty()
    fun isFullCube(): Boolean = this === FULL || (boxes.size == 1 && isFull(boxes[0]))
    fun boxCount(): Int = boxes.size
    fun box(index: Int): AcousticBox = boxes[index]
    fun boxes(): List<AcousticBox> = Collections.unmodifiableList(boxes.clone().asList())
    fun occupancyFraction(): Double = occupancy
    fun contains(x: Double, y: Double, z: Double): Boolean {
        var i = 0
        while (i < boxes.size) {
            val b = boxes[i]
            if (x >= b.minX && x < b.maxX && y >= b.minY && y < b.maxY && z >= b.minZ && z < b.maxZ) return true
            i++
        }
        return false
    }
    override fun equals(other: Any?): Boolean = this === other || (other is AcousticShape && boxes.contentEquals(other.boxes))
    override fun hashCode(): Int = boxes.contentHashCode()
    override fun toString(): String = "AcousticShape{boxes=${boxes.size}, occupancy=$occupancy}"

    companion object {
        @JvmField val EMPTY = AcousticShape(emptyArray(), 0.0)
        @JvmField val FULL = AcousticShape(arrayOf(AcousticBox(0.0,0.0,0.0,1.0,1.0,1.0)), 1.0)
        @JvmStatic fun of(values: List<AcousticBox?>): AcousticShape {
            if (values.isEmpty()) return EMPTY
            val clean = values.filterNotNull()
            if (clean.isEmpty()) return EMPTY
            if (clean.size == 1 && isFull(clean[0])) return FULL
            val array = clean.toTypedArray()
            return AcousticShape(array, estimateOccupancy(array))
        }
        @JvmStatic fun of(vararg values: AcousticBox?): AcousticShape = of(values.asList())
        private fun isFull(b: AcousticBox): Boolean = b.minX == 0.0 && b.minY == 0.0 && b.minZ == 0.0 && b.maxX == 1.0 && b.maxY == 1.0 && b.maxZ == 1.0
        private fun estimateOccupancy(boxes: Array<AcousticBox>): Double {
            val n = 8
            var occupied = 0
            val total = n * n * n
            for (y in 0 until n) for (z in 0 until n) for (x in 0 until n) {
                val px = (x + 0.5) / n
                val py = (y + 0.5) / n
                val pz = (z + 0.5) / n
                var hit = false
                for (b in boxes) if (px >= b.minX && px < b.maxX && py >= b.minY && py < b.maxY && pz >= b.minZ && pz < b.maxZ) { hit = true; break }
                if (hit) occupied++
            }
            return occupied.toDouble() / total
        }
    }
}
