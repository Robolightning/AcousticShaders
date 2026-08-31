package dev.acoustic.platform

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.source.AcousticSourceProfile

class SoundSourceSnapshot @JvmOverloads constructor(
    private val id: Long,
    private val soundId: String,
    private val position: Vec3,
    private val gain: Float,
    private val profile: AcousticSourceProfile = AcousticSourceProfile.GENERIC,
    private val velocity: Vec3 = Vec3(0.0, 0.0, 0.0)
) {
    init {
        require(soundId.isNotBlank()) { "sound id must be non-blank" }
        requireFinite(position, "source position")
        requireFinite(velocity, "source velocity")
        require(gain.isFinite() && gain >= 0f) { "gain must be finite and non-negative" }
    }

    fun id(): Long = id
    fun soundId(): String = soundId
    fun position(): Vec3 = position
    fun gain(): Float = gain
    fun profile(): AcousticSourceProfile = profile
    fun velocity(): Vec3 = velocity

    private fun requireFinite(value: Vec3, label: String) {
        require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) {
            "$label must contain only finite coordinates"
        }
    }
}
