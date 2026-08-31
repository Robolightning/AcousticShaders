package dev.acoustic.core.dsp

/** Deterministic offline/reference FIR convolution primitive. Runtime audio backends may replace this. */
object FirConvolver {
    @JvmStatic
    fun convolve(signal: FloatArray?, kernel: FloatArray?): FloatArray {
        require(signal != null && kernel != null && signal.isNotEmpty() && kernel.isNotEmpty()) { "non-empty arrays required" }
        val out = FloatArray(signal.size + kernel.size - 1)
        var i = 0
        while (i < signal.size) {
            var k = 0
            while (k < kernel.size) {
                out[i + k] += signal[i] * kernel[k]
                k++
            }
            i++
        }
        return out
    }
}
