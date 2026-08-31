package dev.acoustic.mc1122

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.passes.LegacyRoomEstimate
import dev.acoustic.core.passes.ReflectionField

/** Immutable cross-thread handoff from the Minecraft client thread to the Paulscode/OpenAL thread. */
class LegacyPublishedState(
    private val sceneValue: AcousticScene?,
    private val listenerValue: Vec3?,
    private val roomValue: LegacyRoomEstimate,
    private val reflectionFieldValue: ReflectionField?,
    private val epochValue: Long,
    private val effectsEnabledValue: Boolean
) {
    fun scene(): AcousticScene? = sceneValue
    fun listener(): Vec3? = listenerValue
    fun room(): LegacyRoomEstimate = roomValue
    fun reflectionField(): ReflectionField? = reflectionFieldValue
    fun epoch(): Long = epochValue
    fun effectsEnabled(): Boolean = effectsEnabledValue
    fun ready(): Boolean = sceneValue != null && listenerValue != null && effectsEnabledValue

    companion object {
        @JvmField val EMPTY = LegacyPublishedState(null, null, LegacyRoomEstimate.DEFAULT, null, 0L, false)
    }
}
