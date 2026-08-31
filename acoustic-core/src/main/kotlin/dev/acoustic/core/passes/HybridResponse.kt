package dev.acoustic.core.passes

/** Broadband hybrid result plus the explicit wave/geometric crossover contract used by downstream renderers. */
class HybridResponse {
    private val direct: DirectPathResult?
    private val diffraction: DiffractionResult?
    private val early: EarlyReflectionField?
    private val late: LateReverb?
    private val wave: WaveFieldResult?
    private val hybridMode: String
    private val crossoverHz: Double
    private val crossfadeOctaves: Double

    constructor(direct: DirectPathResult?, diffraction: DiffractionResult?, early: EarlyReflectionField?, late: LateReverb?, wave: WaveFieldResult?) :
        this(direct, diffraction, early, late, wave, "AUTO", autoCrossover(wave), 1.0)

    constructor(direct: DirectPathResult?, diffraction: DiffractionResult?, early: EarlyReflectionField?, late: LateReverb?, wave: WaveFieldResult?, mode: String?, crossoverHz: Double, crossfadeOctaves: Double) {
        this.direct = direct; this.diffraction = diffraction; this.early = early; this.late = late; this.wave = wave
        this.hybridMode = mode ?: "AUTO"
        this.crossoverHz = maxOf(0.0, crossoverHz)
        this.crossfadeOctaves = crossfadeOctaves.coerceIn(0.25, 3.0)
    }
    fun direct(): DirectPathResult? = direct
    fun diffraction(): DiffractionResult? = diffraction
    fun early(): EarlyReflectionField? = early
    fun late(): LateReverb? = late
    fun wave(): WaveFieldResult? = wave
    fun hasWaveField(): Boolean = wave != null
    fun hybridMode(): String = hybridMode
    fun crossoverHz(): Double = crossoverHz
    fun crossfadeOctaves(): Double = crossfadeOctaves
    fun waveEnabledForHybrid(): Boolean = wave != null && !hybridMode.equals("RAY_ONLY", true) && crossoverHz > 0
    companion object {
        private fun autoCrossover(wave: WaveFieldResult?): Double = if (wave == null || wave.maxTrustedFrequencyHz() <= 0) 0.0 else minOf(250.0, wave.maxTrustedFrequencyHz() * 0.80)
    }
}
