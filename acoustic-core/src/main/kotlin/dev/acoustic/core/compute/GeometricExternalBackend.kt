package dev.acoustic.core.compute

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.passes.ReflectionField

/** Optional accelerator for listener-centric multi-bounce geometric rays. */
interface GeometricExternalBackend {
    fun id(): String
    fun description(): String
    fun available(): Boolean
    fun supports(scene: AcousticScene, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): Boolean
    fun preferredForAuto(scene: AcousticScene, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): Boolean = supports(scene, rays, bounces, maxDistance, minEnergy)
    fun autoPriority(): Int = 0
    @Throws(Exception::class) fun trace(scene: AcousticScene, listener: Vec3, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): ReflectionField?
    fun traceCount(): Long = -1L
    fun failureCount(): Long = -1L
    fun lastTraceMillis(): Double = Double.NaN
    fun lastFailure(): String = ""
    fun close() {}
}
