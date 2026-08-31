package dev.acoustic.core.passes

import dev.acoustic.api.material.FrequencyBands

class LateReverb(rt60: DoubleArray, energy: FloatArray) {
    private val rt60: DoubleArray
    private val energy: FloatArray
    init {
        require(rt60.size == FrequencyBands.COUNT && energy.size == FrequencyBands.COUNT) { "wrong spectrum" }
        this.rt60 = rt60.clone()
        this.energy = energy.clone()
    }
    fun rt60(b: Int): Double = rt60[b]
    fun energy(b: Int): Float = energy[b]
}
