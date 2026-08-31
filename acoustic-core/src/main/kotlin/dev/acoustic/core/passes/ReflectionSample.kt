package dev.acoustic.core.passes

import dev.acoustic.api.math.Vec3

class ReflectionSample {
    private val pathDistance: Double
    private val delaySeconds: Double
    private val bounceCount: Int
    private val initialDirection: Vec3
    private val energy: FloatArray

    /** Backward-compatible air-only constructor used by current GPU backends and extensions. */
    constructor(pathDistance: Double, bounceCount: Int, initialDirection: Vec3, energy: FloatArray) :
        this(pathDistance, pathDistance / 343.0, bounceCount, initialDirection, energy)

    constructor(pathDistance: Double, delaySeconds: Double, bounceCount: Int, initialDirection: Vec3, energy: FloatArray) {
        this.pathDistance = pathDistance
        this.delaySeconds = delaySeconds
        this.bounceCount = bounceCount
        this.initialDirection = initialDirection
        this.energy = energy.clone()
    }

    fun pathDistance(): Double = pathDistance
    fun delaySeconds(): Double = delaySeconds
    fun bounceCount(): Int = bounceCount
    fun initialDirection(): Vec3 = initialDirection
    fun energy(band: Int): Float = energy[band]
    fun energySpectrum(): FloatArray = energy.clone()
}
