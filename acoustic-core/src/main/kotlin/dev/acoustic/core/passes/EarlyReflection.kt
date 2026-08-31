package dev.acoustic.core.passes

import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3

/** Deterministic source-listener early reflection event. */
class EarlyReflection {
    private val pathDistance: Double
    private val delaySeconds: Double
    private val arrivalDirection: Vec3
    private val reflectionPoint: Vec3
    private val energy: FloatArray

    constructor(pathDistance: Double, arrivalDirection: Vec3, reflectionPoint: Vec3, energy: FloatArray) :
        this(pathDistance, pathDistance / 343.0, arrivalDirection, reflectionPoint, energy)

    constructor(pathDistance: Double, delaySeconds: Double, arrivalDirection: Vec3, reflectionPoint: Vec3, energy: FloatArray) {
        require(energy.size == FrequencyBands.COUNT) { "wrong spectrum size" }
        this.pathDistance = pathDistance
        this.delaySeconds = delaySeconds
        this.arrivalDirection = arrivalDirection
        this.reflectionPoint = reflectionPoint
        this.energy = energy.clone()
    }

    fun pathDistance(): Double = pathDistance
    fun delaySeconds(): Double = delaySeconds
    fun arrivalDirection(): Vec3 = arrivalDirection
    fun reflectionPoint(): Vec3 = reflectionPoint
    fun energy(band: Int): Float = energy[band]
    fun energySpectrum(): FloatArray = energy.clone()
}
