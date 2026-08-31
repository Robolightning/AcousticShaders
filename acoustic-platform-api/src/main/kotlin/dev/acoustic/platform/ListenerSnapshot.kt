package dev.acoustic.platform

import dev.acoustic.api.math.Vec3
import kotlin.math.abs

class ListenerSnapshot @JvmOverloads constructor(
    position: Vec3,
    forward: Vec3,
    up: Vec3,
    velocity: Vec3 = Vec3(0.0, 0.0, 0.0)
) {
    private val position = requireFinite(position, "listener position")
    private val forward = requireFinite(forward, "listener forward").normalize()
    private val up = requireFinite(up, "listener up").normalize()
    private val velocity = requireFinite(velocity, "listener velocity")

    init {
        require(abs(this.forward.dot(this.up)) <= 0.999) { "forward/up must not be collinear" }
    }

    fun position(): Vec3 = position
    fun forward(): Vec3 = forward
    fun up(): Vec3 = up
    fun velocity(): Vec3 = velocity

    companion object {
        private fun requireFinite(value: Vec3, label: String): Vec3 {
            require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) {
                "$label must contain only finite coordinates"
            }
            return value
        }
    }
}
