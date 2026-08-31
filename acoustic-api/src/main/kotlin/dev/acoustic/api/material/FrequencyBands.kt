package dev.acoustic.api.material

object FrequencyBands {
    @JvmField val OCTAVE_HZ: IntArray = intArrayOf(125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val COUNT: Int = 8

    @JvmStatic
    fun requireSpectrum(values: FloatArray?, name: String) {
        if (values == null || values.size != COUNT) {
            throw IllegalArgumentException("$name must contain exactly $COUNT octave bands")
        }
        for (value in values) {
            if (value.isNaN() || value < 0f || value > 1f) {
                throw IllegalArgumentException("$name values must be within [0, 1]")
            }
        }
    }
}
