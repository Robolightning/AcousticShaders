package dev.acoustic.core.passes

/** Compact OpenAL-oriented projection of a full acoustic response. */
class LegacyEffectParameters(directGain: Float, directGainHf: Float, sendGain: Float, sendGainHf: Float) {
    private val directGain = unit(directGain)
    private val directGainHf = unit(directGainHf)
    private val sendGain = unit(sendGain)
    private val sendGainHf = unit(sendGainHf)
    fun directGain(): Float = directGain
    fun directGainHf(): Float = directGainHf
    fun sendGain(): Float = sendGain
    fun sendGainHf(): Float = sendGainHf
    companion object { private fun unit(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f) }
}
