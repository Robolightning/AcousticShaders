package dev.acoustic.api.scene

import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.material.AcousticMaterial

class AcousticVoxel {
    private val solid: Boolean
    private val material: AcousticMaterial
    private val shape: AcousticShape
    private val medium: AcousticMedium
    private val mediumShape: AcousticShape

    constructor(solid: Boolean, material: AcousticMaterial) : this(
        solid,
        material,
        if (solid) AcousticShape.FULL else AcousticShape.EMPTY,
        defaultMedium(solid, material),
        defaultMediumShape(solid, defaultMedium(solid, material))
    )

    constructor(solid: Boolean, material: AcousticMaterial, shape: AcousticShape) : this(
        solid,
        material,
        shape,
        defaultMedium(solid, material),
        defaultMediumShape(solid, defaultMedium(solid, material))
    )

    constructor(solid: Boolean, material: AcousticMaterial, medium: AcousticMedium) : this(
        solid,
        material,
        if (solid) AcousticShape.FULL else AcousticShape.EMPTY,
        medium,
        defaultMediumShape(solid, medium)
    )

    constructor(solid: Boolean, material: AcousticMaterial, shape: AcousticShape, medium: AcousticMedium) : this(
        solid, material, shape, medium, defaultMediumShape(solid, medium)
    )

    constructor(solid: Boolean, material: AcousticMaterial, shape: AcousticShape, medium: AcousticMedium, mediumShape: AcousticShape) {
        this.material = material
        this.shape = shape
        this.solid = solid && !shape.isEmpty()
        this.medium = if (this.solid) AcousticMedia.AIR else medium
        this.mediumShape = if (this.solid || this.medium.id() == AcousticMedia.AIR.id()) AcousticShape.EMPTY else mediumShape
    }

    fun solid(): Boolean = solid
    fun material(): AcousticMaterial = material
    fun shape(): AcousticShape = shape
    fun medium(): AcousticMedium = medium
    fun mediumShape(): AcousticShape = mediumShape
    fun mediumAt(localX: Double, localY: Double, localZ: Double): AcousticMedium =
        if (!solid && medium.id() != AcousticMedia.AIR.id() && mediumShape.contains(localX, localY, localZ)) medium else AcousticMedia.AIR

    companion object {
        private fun defaultMedium(solid: Boolean, material: AcousticMaterial): AcousticMedium {
            if (!solid && material.id() == "acoustic:liquid") return AcousticMedia.WATER
            return AcousticMedia.AIR
        }

        private fun defaultMediumShape(solid: Boolean, medium: AcousticMedium): AcousticShape =
            if (!solid && medium.id() != AcousticMedia.AIR.id()) AcousticShape.FULL else AcousticShape.EMPTY
    }
}
