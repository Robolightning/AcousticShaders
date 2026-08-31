package dev.acoustic.core.passes

import java.util.ArrayList
import java.util.Collections

/** Portable low-frequency result. A backend may expose modes, a time-domain listener response, or both. */
class WaveFieldResult {
    private val sizeX: Double
    private val sizeY: Double
    private val sizeZ: Double
    private val modesHz: List<Double>
    private val backendId: String
    private val samplePeriodSeconds: Double
    private val listenerImpulse: FloatArray
    private val maxTrustedFrequencyHz: Double

    constructor(x: Double, y: Double, z: Double, modes: List<Double>) : this(x, y, z, modes, "modal", 0.0, FloatArray(0), maxMode(modes))
    constructor(x: Double, y: Double, z: Double, modes: List<Double>, maxTrustedFrequencyHz: Double) : this(x, y, z, modes, "modal", 0.0, FloatArray(0), maxTrustedFrequencyHz)
    constructor(x: Double, y: Double, z: Double, modes: List<Double>, backendId: String, samplePeriodSeconds: Double, listenerImpulse: FloatArray?) : this(x, y, z, modes, backendId, samplePeriodSeconds, listenerImpulse, 0.0)
    constructor(x: Double, y: Double, z: Double, modes: List<Double>, backendId: String, samplePeriodSeconds: Double, listenerImpulse: FloatArray?, maxTrustedFrequencyHz: Double) {
        require(x > 0 && y > 0 && z > 0) { "wave extents must be positive" }
        require(backendId.isNotEmpty()) { "backendId" }
        require(samplePeriodSeconds >= 0 && !samplePeriodSeconds.isNaN()) { "sample period" }
        require(maxTrustedFrequencyHz >= 0 && !maxTrustedFrequencyHz.isNaN() && !maxTrustedFrequencyHz.isInfinite()) { "maxTrustedFrequencyHz" }
        sizeX=x; sizeY=y; sizeZ=z; modesHz=Collections.unmodifiableList(ArrayList(modes)); this.backendId=backendId; this.samplePeriodSeconds=samplePeriodSeconds; this.listenerImpulse=listenerImpulse?.clone() ?: FloatArray(0); this.maxTrustedFrequencyHz=maxTrustedFrequencyHz
    }
    fun sizeX(): Double = sizeX
    fun sizeY(): Double = sizeY
    fun sizeZ(): Double = sizeZ
    fun modesHz(): List<Double> = modesHz
    fun backendId(): String = backendId
    fun hasTimeDomainResponse(): Boolean = listenerImpulse.isNotEmpty() && samplePeriodSeconds > 0
    fun samplePeriodSeconds(): Double = samplePeriodSeconds
    fun listenerImpulse(): FloatArray = listenerImpulse.clone()
    fun maxTrustedFrequencyHz(): Double = maxTrustedFrequencyHz
    companion object {
        private fun maxMode(modes: List<Double>?): Double { var max = 0.0; if (modes != null) for (value in modes) if (value > max) max = value; return max }
    }
}
