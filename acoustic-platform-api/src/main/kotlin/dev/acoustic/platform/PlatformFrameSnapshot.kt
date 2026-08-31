package dev.acoustic.platform

import dev.acoustic.api.scene.AcousticScene
import java.util.ArrayList
import java.util.Collections

/**
 * Immutable platform-facing capture of one logical acoustic frame.
 *
 * A concrete Minecraft adapter should override AcousticPlatformAdapter.captureFrame when the
 * underlying game can provide scene/listener/source data under one tick/world ownership boundary.
 * worldEpoch identifies the current world instance; frameSequence orders captures inside that epoch.
 */
class PlatformFrameSnapshot(
    private val scene: AcousticScene,
    private val listener: ListenerSnapshot,
    sources: List<SoundSourceSnapshot>,
    private val worldEpoch: Long,
    private val frameSequence: Long
) {
    private val sources = Collections.unmodifiableList(ArrayList(sources))

    fun scene(): AcousticScene = scene
    fun listener(): ListenerSnapshot = listener
    fun sources(): List<SoundSourceSnapshot> = sources
    fun worldEpoch(): Long = worldEpoch
    fun frameSequence(): Long = frameSequence
}
