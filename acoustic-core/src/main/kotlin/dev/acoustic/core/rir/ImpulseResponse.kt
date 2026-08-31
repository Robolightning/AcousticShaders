package dev.acoustic.core.rir

/** Immutable mono reference RIR. Spatial backends may expose Ambisonics/HRTF representations later. */
class ImpulseResponse(private val sampleRate: Int, samples: FloatArray) {
    private val samples: FloatArray
    init {
        require(sampleRate >= 8000 && samples.isNotEmpty()) { "invalid IR" }
        this.samples = samples.clone()
    }
    fun sampleRate(): Int = sampleRate
    fun length(): Int = samples.size
    fun sample(i: Int): Float = samples[i]
    fun samples(): FloatArray = samples.clone()
}
