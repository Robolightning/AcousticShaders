package dev.acoustic.core.passes

import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale

/** Combines optional acoustic stages and assigns a physically bounded wave/geometric crossover. */
class HybridResponsePass(
    mode: String? = "AUTO",
    private val requestedCrossover: Double = -1.0,
    private val crossfadeOctaves: Double = 1.0
) : Pass {
    private val mode = (mode ?: "AUTO").trim().uppercase(Locale.ROOT)
    override fun id(): String = "standard.hybrid"
    override fun reads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.DIRECT_PATH)
    override fun optionalReads(): Set<ResourceKey<*>> = Collections.unmodifiableSet(LinkedHashSet(Arrays.asList(StandardResources.DIFFRACTION, StandardResources.EARLY_REFLECTIONS, StandardResources.LATE_REVERB, StandardResources.WAVE_FIELD)))
    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.HYBRID_RESPONSE)

    override fun execute(context: PassContext) {
        val direct = context.require(StandardResources.DIRECT_PATH)
        val diffraction = context.get(StandardResources.DIFFRACTION)
            ?: DiffractionResult(false, direct.distanceMeters, Vec3(0.0, 0.0, 0.0), FloatArray(FrequencyBands.COUNT))
        val early = context.get(StandardResources.EARLY_REFLECTIONS) ?: EarlyReflectionField(emptyList())
        val late = context.get(StandardResources.LATE_REVERB) ?: LateReverb(DoubleArray(FrequencyBands.COUNT), FloatArray(FrequencyBands.COUNT))
        val wave = context.get(StandardResources.WAVE_FIELD)
        val crossover = effectiveCrossover(wave)
        val actualMode = if (mode == "AUTO") if (wave == null) "RAY_ONLY" else "RAY_WAVE" else mode
        context.put(StandardResources.HYBRID_RESPONSE, HybridResponse(direct, diffraction, early, late, wave, actualMode, crossover, crossfadeOctaves))
    }

    private fun effectiveCrossover(wave: WaveFieldResult?): Double {
        if (wave == null || mode == "RAY_ONLY") return 0.0
        val trusted = wave.maxTrustedFrequencyHz()
        if (!(trusted > 0.0)) return 0.0
        val target = if (requestedCrossover > 0.0) requestedCrossover else Math.min(250.0, trusted * 0.80)
        return Math.max(20.0, Math.min(target, trusted * 0.95))
    }
}
