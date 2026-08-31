package dev.acoustic.testkit

import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.material.AcousticMaterial
import dev.acoustic.api.material.AcousticMaterials
import dev.acoustic.api.scene.AcousticBox
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.api.scene.AcousticShape
import dev.acoustic.api.scene.AcousticVoxel
import java.util.HashMap

class VoxelTestScene private constructor(
    private val cells: Map<Key, AcousticVoxel>,
    private val revision: Long
) : AcousticScene {
    override fun voxelAt(x: Int, y: Int, z: Int): AcousticVoxel = cells[Key(x, y, z)] ?: AIR
    override fun revision(): Long = revision
    override fun containsNonAirMedia(): Boolean = cells.values.any {
        !it.solid() && !it.mediumShape().isEmpty() && it.medium().id() != "acoustic:air"
    }

    class Builder {
        private val cells = HashMap<Key, AcousticVoxel>()
        private var revision = 0L

        fun solid(x: Int, y: Int, z: Int, material: AcousticMaterial): Builder {
            cells[Key(x, y, z)] = AcousticVoxel(true, material)
            revision++
            return this
        }

        fun shaped(x: Int, y: Int, z: Int, material: AcousticMaterial, shape: AcousticShape): Builder {
            cells[Key(x, y, z)] = AcousticVoxel(true, material, shape)
            revision++
            return this
        }

        fun medium(x: Int, y: Int, z: Int, medium: AcousticMedium): Builder {
            cells[Key(x, y, z)] = AcousticVoxel(false, AcousticMaterials.LIQUID, medium)
            revision++
            return this
        }

        fun partialMedium(x: Int, y: Int, z: Int, medium: AcousticMedium, height: Double): Builder {
            require(height > 0.0 && height <= 1.0) { "medium height must be in (0,1]" }
            val mediumShape = if (height >= 1.0) AcousticShape.FULL else
                AcousticShape.of(AcousticBox(0.0, 0.0, 0.0, 1.0, height, 1.0))
            cells[Key(x, y, z)] = AcousticVoxel(false, AcousticMaterials.LIQUID, AcousticShape.EMPTY, medium, mediumShape)
            revision++
            return this
        }

        fun water(x: Int, y: Int, z: Int): Builder = medium(x, y, z, AcousticMedia.WATER)
        fun lava(x: Int, y: Int, z: Int): Builder = medium(x, y, z, AcousticMedia.LAVA)

        fun boxShell(minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int, material: AcousticMaterial): Builder {
            for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
                if (x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ) solid(x, y, z, material)
            }
            return this
        }

        fun build(): VoxelTestScene = VoxelTestScene(HashMap(cells), revision)
    }

    private class Key(val x: Int, val y: Int, val z: Int) {
        override fun equals(other: Any?): Boolean = this === other || (other is Key && x == other.x && y == other.y && z == other.z)
        override fun hashCode(): Int {
            var result = x
            result = 31 * result + y
            result = 31 * result + z
            return result
        }
    }

    companion object {
        private val AIR = AcousticVoxel(false, AcousticMaterials.AIR)
        @JvmStatic fun builder(): Builder = Builder()
    }
}
