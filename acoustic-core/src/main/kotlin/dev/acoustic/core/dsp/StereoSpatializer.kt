package dev.acoustic.core.dsp

import dev.acoustic.api.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Reference equal-power stereo spatializer; deliberately not advertised as HRTF. */
object StereoSpatializer {
    @JvmStatic fun gains(listenerRight: Vec3, arrivalDirection: Vec3): FloatArray {
        val pan = listenerRight.normalize().dot(arrivalDirection.normalize()).coerceIn(-1.0, 1.0)
        val a = (pan + 1.0) * PI / 4.0
        return floatArrayOf(cos(a).toFloat(), sin(a).toFloat())
    }
}
