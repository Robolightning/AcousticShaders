package dev.acoustic.platform

import dev.acoustic.api.math.Vec3
import java.util.HashSet

/** Stable validation shared by every concrete game-version adapter. */
object PlatformFrameValidator {
    @JvmStatic
    fun requirePlatformId(platformId: String): String {
        require(platformId.isNotBlank()) { "platform id must be non-blank" }
        require(platformId.length <= 128) { "platform id is too long" }
        require(platformId.none { it.isISOControl() }) { "platform id contains control characters" }
        return platformId
    }

    @JvmStatic
    fun requireValid(frame: PlatformFrameSnapshot) {
        requireFinite(frame.listener().position(), "listener position")
        requireFinite(frame.listener().forward(), "listener forward")
        requireFinite(frame.listener().up(), "listener up")
        requireFinite(frame.listener().velocity(), "listener velocity")

        val sourceIds = HashSet<Long>()
        for (source in frame.sources()) {
            require(sourceIds.add(source.id())) { "duplicate platform source id: ${source.id()}" }
            require(source.soundId().isNotBlank()) { "platform source sound id must be non-blank" }
            require(source.gain().isFinite() && source.gain() >= 0f) {
                "platform source gain must be finite and non-negative"
            }
            requireFinite(source.position(), "platform source position")
            requireFinite(source.velocity(), "platform source velocity")
        }
    }

    private fun requireFinite(value: Vec3, label: String) {
        require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) {
            "$label must contain only finite coordinates"
        }
    }
}
