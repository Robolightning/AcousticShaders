package dev.acoustic.core.passes

import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.api.source.AcousticSourceProfile
import java.util.Collections

/** Applies shader-local strength to resource-pack/source-database physical source metadata. */
class SourceBehaviorPass(private val strength: Float, private val doppler: Boolean = true) : Pass {
    init { require(!strength.isNaN() && strength in 0f..1f) { "strength" } }

    override fun id(): String = "standard.source_behavior"
    override fun reads(): Set<ResourceKey<*>> = emptySet()
    override fun optionalReads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.SOURCE_PROFILE)
    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.SOURCE_BEHAVIOR)

    override fun execute(context: PassContext) {
        val profile = context.get(StandardResources.SOURCE_PROFILE) ?: AcousticSourceProfile.GENERIC
        var behavior = profile.blend(strength)
        if (!doppler && behavior.dopplerScale() > 0f) {
            behavior = AcousticSourceProfile(
                behavior.id(), behavior.category(), behavior.emissionSpectrum(), behavior.directScale(),
                behavior.occlusionScale(), behavior.diffractionScale(), behavior.earlyScale(), behavior.lateScale(),
                behavior.priorityScale(), behavior.movementSensitivity(), 0f, behavior.transientScale(), behavior.bypassAcoustics()
            )
        }
        context.put(StandardResources.SOURCE_BEHAVIOR, behavior)
    }
}
