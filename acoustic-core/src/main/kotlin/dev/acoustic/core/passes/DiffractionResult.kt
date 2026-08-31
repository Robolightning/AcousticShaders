package dev.acoustic.core.passes

import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3

class DiffractionResult {
    private val available: Boolean
    private val pathDistance: Double
    private val delaySeconds: Double
    private val bendPoint: Vec3?
    private val transmission: FloatArray

    constructor(available: Boolean, pathDistance: Double, bendPoint: Vec3?, transmission: FloatArray) :
        this(available, pathDistance, pathDistance / 343.0, bendPoint, transmission)

    constructor(available: Boolean, pathDistance: Double, delaySeconds: Double, bendPoint: Vec3?, transmission: FloatArray) {
        require(transmission.size == FrequencyBands.COUNT) { "wrong spectrum" }
        this.available = available
        this.pathDistance = pathDistance
        this.delaySeconds = delaySeconds
        this.bendPoint = bendPoint
        this.transmission = transmission.clone()
    }

    fun available(): Boolean = available
    fun pathDistance(): Double = pathDistance
    fun delaySeconds(): Double = delaySeconds
    fun bendPoint(): Vec3? = bendPoint
    fun transmission(band: Int): Float = transmission[band]
}
