package dev.acoustic.core.passes

import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.core.dsp.FoaRenderer
import kotlin.math.roundToInt

/** Standard portable first-order Ambisonics stage (ACN/SN3D). */
class FoaPass : Pass {
    override fun id(): String = "standard.foa"
    override fun reads(): Set<ResourceKey<*>> = linkedSetOf(StandardResources.IMPULSE_RESPONSE, StandardResources.HYBRID_RESPONSE)
    override fun optionalReads(): Set<ResourceKey<*>> = linkedSetOf(StandardResources.EARLY_REFLECTIONS)
    override fun writes(): Set<ResourceKey<*>> = linkedSetOf(StandardResources.FOA_IMPULSE_RESPONSE)
    override fun execute(context: PassContext) {
        val rir = context.require(StandardResources.IMPULSE_RESPONSE)
        val hybrid = context.require(StandardResources.HYBRID_RESPONSE)
        val early = context.get(StandardResources.EARLY_REFLECTIONS)
        val directCandidate = (hybrid.direct()!!.delaySeconds * rir.sampleRate()).roundToInt()
        val directIndex = if (directCandidate in 0 until rir.length()) directCandidate else -1
        context.put(StandardResources.FOA_IMPULSE_RESPONSE, FoaRenderer.encode(rir, early, directIndex))
    }
}
