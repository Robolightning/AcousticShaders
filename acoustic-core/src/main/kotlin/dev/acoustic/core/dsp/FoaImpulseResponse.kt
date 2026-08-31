package dev.acoustic.core.dsp

/** Immutable first-order Ambisonics response in ACN channel order W,Y,Z,X with SN3D normalization. */
class FoaImpulseResponse @JvmOverloads constructor(
    private val sampleRate: Int,
    w: FloatArray,
    y: FloatArray,
    z: FloatArray,
    x: FloatArray,
    private val directSampleIndex: Int = -1
) {
    private val channels = arrayOf(w.clone(), y.clone(), z.clone(), x.clone())
    init {
        require(sampleRate >= 8000) { "invalid sample rate" }
        require(w.isNotEmpty() && y.size == w.size && z.size == w.size && x.size == w.size) { "FOA channel length mismatch" }
        require(directSampleIndex in -1 until w.size) { "invalid direct sample index" }
    }
    fun sampleRate(): Int = sampleRate
    fun length(): Int = channels[0].size
    fun directSampleIndex(): Int = directSampleIndex
    fun channel(acn: Int): FloatArray { require(acn in 0..3); return channels[acn].clone() }
    fun sample(acn: Int, index: Int): Float = channels[acn][index]
}
