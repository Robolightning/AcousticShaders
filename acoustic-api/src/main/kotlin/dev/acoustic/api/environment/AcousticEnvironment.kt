package dev.acoustic.api.environment

import dev.acoustic.api.material.FrequencyBands
import java.util.Arrays
import kotlin.math.exp

/** Platform-neutral propagation environment. */
class AcousticEnvironment(speedOfSoundMetersPerSecond: Double, airAbsorptionNepersPerMeter: FloatArray) {
    private val speedOfSoundMetersPerSecond: Double
    private val airAbsorptionNepersPerMeter: FloatArray

    init {
        if (!(speedOfSoundMetersPerSecond > 0.0) || speedOfSoundMetersPerSecond.isNaN() || speedOfSoundMetersPerSecond.isInfinite()) {
            throw IllegalArgumentException("speed of sound must be finite and positive")
        }
        FrequencyBands.requireSpectrum(airAbsorptionNepersPerMeter, "air absorption")
        for (value in airAbsorptionNepersPerMeter) {
            if (value < 0f || value.isNaN() || value.isInfinite()) {
                throw IllegalArgumentException("air absorption must be finite and non-negative")
            }
        }
        this.speedOfSoundMetersPerSecond = speedOfSoundMetersPerSecond
        this.airAbsorptionNepersPerMeter = airAbsorptionNepersPerMeter.clone()
    }

    fun speedOfSoundMetersPerSecond(): Double = speedOfSoundMetersPerSecond
    fun airAbsorptionNepersPerMeter(band: Int): Float = airAbsorptionNepersPerMeter[band]
    fun airAbsorptionSpectrum(): FloatArray = airAbsorptionNepersPerMeter.clone()
    fun airTransmission(band: Int, distanceMeters: Double): Float = if (distanceMeters <= 0.0) 1f else exp(-airAbsorptionNepersPerMeter[band] * distanceMeters).toFloat()

    override fun toString(): String = "AcousticEnvironment{c=$speedOfSoundMetersPerSecond, air=${Arrays.toString(airAbsorptionNepersPerMeter)}}"

    companion object {
        @JvmField val STANDARD = AcousticEnvironment(343.0, FloatArray(FrequencyBands.COUNT))
    }
}
