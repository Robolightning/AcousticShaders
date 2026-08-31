package dev.acoustic.core.source

import dev.acoustic.api.math.Vec3

/** Immutable source metadata used by the platform-neutral perceptual budget allocator. */
class SourceCandidate(private val id: Long, private val position: Vec3, private val gain: Double, private val importance: Double) {
    init { require(gain >= 0 && importance >= 0) { "invalid source" } }
    fun id(): Long = id
    fun position(): Vec3 = position
    fun gain(): Double = gain
    fun importance(): Double = importance
}
