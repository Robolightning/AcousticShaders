package dev.acoustic.core.passes

import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.material.FrequencyBands

class DirectPathResult {
    @JvmField val distanceMeters: Double
    @JvmField val delaySeconds: Double
    @JvmField val transmission: FloatArray
    @JvmField val solidCells: Int
    @JvmField val occupiedMeters: Double
    @JvmField val airMeters: Double
    @JvmField val liquidMeters: Double
    @JvmField val mediumBoundaryCount: Int
    @JvmField val sourceMediumId: String
    @JvmField val listenerMediumId: String

    constructor(distanceMeters: Double, delaySeconds: Double, transmission: FloatArray, solidCells: Int) :
        this(distanceMeters, delaySeconds, transmission, solidCells, solidCells.toDouble())

    constructor(distanceMeters: Double, delaySeconds: Double, transmission: FloatArray, solidCells: Int, occupiedMeters: Double) :
        this(
            distanceMeters, delaySeconds, transmission, solidCells, occupiedMeters,
            distanceMeters.coerceAtLeast(0.0), 0.0, 0,
            AcousticMedia.AIR.id(), AcousticMedia.AIR.id()
        )

    constructor(
        distanceMeters: Double,
        delaySeconds: Double,
        transmission: FloatArray,
        solidCells: Int,
        occupiedMeters: Double,
        airMeters: Double,
        liquidMeters: Double,
        mediumBoundaryCount: Int,
        sourceMediumId: String,
        listenerMediumId: String
    ) {
        require(transmission.size == FrequencyBands.COUNT) { "wrong spectrum size" }
        this.distanceMeters = distanceMeters
        this.delaySeconds = delaySeconds
        this.transmission = transmission.clone()
        this.solidCells = solidCells
        this.occupiedMeters = maxOf(0.0, occupiedMeters)
        this.airMeters = maxOf(0.0, airMeters)
        this.liquidMeters = maxOf(0.0, liquidMeters)
        this.mediumBoundaryCount = maxOf(0, mediumBoundaryCount)
        this.sourceMediumId = sourceMediumId
        this.listenerMediumId = listenerMediumId
    }

    fun sourceSubmerged(): Boolean = sourceMediumId != AcousticMedia.AIR.id()
    fun listenerSubmerged(): Boolean = listenerMediumId != AcousticMedia.AIR.id()
}
