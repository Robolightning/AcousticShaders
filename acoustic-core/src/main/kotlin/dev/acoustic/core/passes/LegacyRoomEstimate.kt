package dev.acoustic.core.passes

/** Listener-local late-field estimate suitable for an OpenAL EFX reverb slot. */
class LegacyRoomEstimate(meanFreePathMeters: Double, openness: Float, decayTimeSeconds: Float, gainHf: Float, diffusion: Float, density: Float) {
    private val meanFreePathMeters = maxOf(0.25, meanFreePathMeters)
    private val openness = unit(openness)
    private val decayTimeSeconds = decayTimeSeconds.coerceIn(0.1f, 20f)
    private val gainHf = unit(gainHf)
    private val diffusion = unit(diffusion)
    private val density = unit(density)
    fun meanFreePathMeters(): Double = meanFreePathMeters
    fun openness(): Float = openness
    fun decayTimeSeconds(): Float = decayTimeSeconds
    fun gainHf(): Float = gainHf
    fun diffusion(): Float = diffusion
    fun density(): Float = density
    companion object {
        @JvmField val DEFAULT = LegacyRoomEstimate(8.0, 0.45f, 1.2f, 0.78f, 0.85f, 0.65f)
        private fun unit(v: Float): Float = v.coerceIn(0f, 1f)
    }
}
