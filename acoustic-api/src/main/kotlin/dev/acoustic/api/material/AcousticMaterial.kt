package dev.acoustic.api.material

import java.util.Arrays

class AcousticMaterial(
    id: String,
    absorption: FloatArray,
    scattering: Float,
    transmission: Float
) {
    private val id: String = id
    private val absorption: FloatArray
    private val scattering: Float
    private val transmission: Float

    init {
        FrequencyBands.requireSpectrum(absorption, "absorption")
        require(scattering in 0f..1f) { "scattering must be within [0, 1]" }
        require(transmission in 0f..1f) { "transmission must be within [0, 1]" }
        this.absorption = absorption.clone()
        this.scattering = scattering
        this.transmission = transmission
    }

    fun id(): String = id
    fun absorption(band: Int): Float = absorption[band]
    fun reflection(band: Int): Float = maxOf(0f, 1f - absorption[band] - transmission)
    fun absorptionSpectrum(): FloatArray = absorption.clone()
    fun scattering(): Float = scattering
    fun transmission(): Float = transmission

    override fun toString(): String = "AcousticMaterial{$id, absorption=${Arrays.toString(absorption)}}"
}
