package dev.acoustic.core.passes

import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import java.util.Collections

/** Statistical late-field estimator from traced reflection energy. */
class LateReverbPass : Pass {
    override fun id(): String = "standard.late_reverb"
    override fun reads(): Set<ResourceKey<*>> = emptySet()
    override fun optionalReads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.REFLECTION_FIELD)
    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.LATE_REVERB)

    override fun execute(context: PassContext) {
        val field = context.get(StandardResources.REFLECTION_FIELD)
        val rt = DoubleArray(FrequencyBands.COUNT)
        val outputEnergy = FloatArray(FrequencyBands.COUNT)
        if (field == null || field.samples().isEmpty()) {
            context.put(StandardResources.LATE_REVERB, LateReverb(rt, outputEnergy))
            return
        }
        val weightedTime = DoubleArray(FrequencyBands.COUNT)
        val sumEnergy = DoubleArray(FrequencyBands.COUNT)
        for (sample in field.samples()) {
            var band = 0
            while (band < FrequencyBands.COUNT) {
                val energy = sample.energy(band).toDouble()
                sumEnergy[band] += energy
                weightedTime[band] += energy * sample.delaySeconds()
                band++
            }
        }
        var band = 0
        while (band < FrequencyBands.COUNT) {
            val mean = if (sumEnergy[band] > 0.0) weightedTime[band] / sumEnergy[band] else 0.0
            rt[band] = Math.max(0.05, Math.min(12.0, mean * 18.0))
            outputEnergy[band] = Math.min(1.0, sumEnergy[band] / Math.max(1, field.raysTraced()).toDouble()).toFloat()
            band++
        }
        context.put(StandardResources.LATE_REVERB, LateReverb(rt, outputEnergy))
    }
}
