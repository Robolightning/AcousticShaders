package dev.acoustic.core.dsp

import kotlin.math.max
import kotlin.math.min

/** Strict little/big-endian PCM helpers shared by legacy and future platform audio backends. */
object PcmCodec {
    @JvmStatic
    fun decodeMono(bytes: ByteArray, bits: Int, signed: Boolean = true, bigEndian: Boolean = false): FloatArray {
        require(bits == 8 || bits == 16) { "only PCM8/PCM16 are supported" }
        val width = bits / 8
        require(bytes.size % width == 0) { "truncated PCM sample" }
        val out = FloatArray(bytes.size / width)
        if (bits == 8) {
            for (i in out.indices) {
                val u = bytes[i].toInt() and 0xff
                val v = if (signed) bytes[i].toInt() else u - 128
                out[i] = (v / 128.0f).coerceIn(-1f, 1f)
            }
        } else {
            var p = 0
            for (i in out.indices) {
                val a = bytes[p++].toInt() and 0xff
                val b = bytes[p++].toInt() and 0xff
                val raw = if (bigEndian) (a shl 8) or b else (b shl 8) or a
                val value = if (signed) raw.toShort().toInt() else raw - 32768
                out[i] = (value / 32768.0f).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    /** Interleaved signed PCM16 stereo, suitable for AL_FORMAT_STEREO16. */
    @JvmStatic
    fun encodeStereo16(left: FloatArray, right: FloatArray, bigEndian: Boolean = false): ByteArray {
        require(left.size == right.size) { "channel length mismatch" }
        val out = ByteArray(left.size * 4)
        var p = 0
        for (i in left.indices) {
            p = write16(out, p, left[i], bigEndian)
            p = write16(out, p, right[i], bigEndian)
        }
        return out
    }

    private fun write16(out: ByteArray, offset: Int, sample: Float, bigEndian: Boolean): Int {
        val value = max(-32768, min(32767, (sample.coerceIn(-1f, 1f) * 32767.0f).toInt()))
        if (bigEndian) {
            out[offset] = (value shr 8).toByte()
            out[offset + 1] = value.toByte()
        } else {
            out[offset] = value.toByte()
            out[offset + 1] = (value shr 8).toByte()
        }
        return offset + 2
    }
}
