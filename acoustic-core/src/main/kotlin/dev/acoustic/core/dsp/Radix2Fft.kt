package dev.acoustic.core.dsp

/** Small allocation-free-in-transform radix-2 complex FFT used by the portable reference DSP. */
object Radix2Fft {
    @JvmStatic
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size && Integer.bitCount(n) == 1) { "FFT length must be power of two" }
        var i = 1
        var j = 0
        while (i < n) {
            var bit = n ushr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit ushr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
            i++
        }
        var len = 2
        while (len <= n) {
            val angle = (if (inverse) 2.0 else -2.0) * Math.PI / len.toDouble()
            val wlenR = Math.cos(angle)
            val wlenI = Math.sin(angle)
            i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                j = 0
                while (j < len / 2) {
                    val a = i + j
                    val b = a + len / 2
                    val br = re[b] * wr - im[b] * wi
                    val bi = re[b] * wi + im[b] * wr
                    val ar = re[a]
                    val ai = im[a]
                    re[a] = ar + br
                    im[a] = ai + bi
                    re[b] = ar - br
                    im[b] = ai - bi
                    val nr = wr * wlenR - wi * wlenI
                    wi = wr * wlenI + wi * wlenR
                    wr = nr
                    j++
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            i = 0
            while (i < n) {
                re[i] /= n.toDouble()
                im[i] /= n.toDouble()
                i++
            }
        }
    }
}
