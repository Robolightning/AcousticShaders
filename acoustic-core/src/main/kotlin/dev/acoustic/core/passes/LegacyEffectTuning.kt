package dev.acoustic.core.passes

/** Compact live-backend projection of shader/profile intent. */
class LegacyEffectTuning(private val lateReverb: Boolean, private val diffraction: Boolean, wetScale: Float, decayScale: Float, diffractionLeak: Float) {
    private val wetScale = clamp(wetScale, 0f, 2f)
    private val decayScale = clamp(decayScale, 0.25f, 2.5f)
    private val diffractionLeak = clamp(diffractionLeak, 0f, 0.8f)
    fun lateReverb(): Boolean = lateReverb
    fun diffraction(): Boolean = diffraction
    fun wetScale(): Float = wetScale
    fun decayScale(): Float = decayScale
    fun diffractionLeak(): Float = diffractionLeak
    companion object {
        @JvmField val DEFAULT = LegacyEffectTuning(true, true, 1f, 1f, 0.35f)
        private fun clamp(v: Float, lo: Float, hi: Float): Float = if (v.isNaN()) lo else v.coerceIn(lo, hi)
    }
}
