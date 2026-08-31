package dev.acoustic.core.dsp

import dev.acoustic.api.math.Vec3
import dev.acoustic.core.passes.EarlyReflectionField
import dev.acoustic.core.rir.ImpulseResponse
import kotlin.math.max

/** Offline/reference full-RIR wet renderer. Designed to run on bounded worker threads, never the audio thread. */
class SoftwareWetPcmRenderer(private val blockSize: Int = 256, private val maxIrSeconds: Double = 3.0) {
    init {
        require(blockSize >= 16 && Integer.bitCount(blockSize) == 1) { "blockSize must be a power of two" }
        require(maxIrSeconds > 0.0) { "maxIrSeconds" }
    }

    data class Rendered(@JvmField val pcmStereo16: ByteArray, @JvmField val sampleRate: Int, @JvmField val frames: Int)

    fun renderMono16(
        pcm: ByteArray,
        sampleRate: Int,
        rir: ImpulseResponse,
        early: EarlyReflectionField?,
        listenerForward: Vec3,
        wetGain: Float = 1.0f
    ): Rendered {
        require(sampleRate in 8000..192000) { "unsupported sample rate" }
        require(wetGain >= 0f && wetGain.isFinite()) { "wetGain" }
        return renderMono16(pcm, sampleRate, FoaRenderer.encode(trimRir(rir), early), listenerForward, wetGain)
    }

    fun renderMono16(
        pcm: ByteArray,
        sampleRate: Int,
        foa: FoaImpulseResponse,
        listenerForward: Vec3,
        wetGain: Float = 1.0f
    ): Rendered {
        require(sampleRate in 8000..192000) { "unsupported sample rate" }
        require(wetGain >= 0f && wetGain.isFinite()) { "wetGain" }
        val dry = PcmCodec.decodeMono(pcm, 16, true, false)
        val decoded = FoaRenderer.decodeStereo(trimFoa(foa), listenerForward)
        val stereoIr = if (foa.sampleRate() == sampleRate) decoded else arrayOf(
            resample(decoded[0], foa.sampleRate(), sampleRate),
            resample(decoded[1], foa.sampleRate(), sampleRate)
        )
        val directIndex = resampledDirectIndex(foa.directSampleIndex(), foa.sampleRate(), sampleRate, stereoIr[0].size)
        removeDirectImpulse(stereoIr[0], directIndex); removeDirectImpulse(stereoIr[1], directIndex)
        val left = PartitionedConvolver(stereoIr[0], blockSize).processAll(dry)
        val right = PartitionedConvolver(stereoIr[1], blockSize).processAll(dry)
        val frames = max(left.size, right.size)
        val l = FloatArray(frames); val r = FloatArray(frames)
        for (i in 0 until frames) {
            if (i < left.size) l[i] = left[i] * wetGain
            if (i < right.size) r[i] = right[i] * wetGain
        }
        return Rendered(PcmCodec.encodeStereo16(l, r), sampleRate, frames)
    }

    private fun trimFoa(foa: FoaImpulseResponse): FoaImpulseResponse {
        val maxSamples = max(1, (foa.sampleRate() * maxIrSeconds).toInt())
        if (foa.length() <= maxSamples) return foa
        return FoaImpulseResponse(foa.sampleRate(), foa.channel(0).copyOf(maxSamples), foa.channel(1).copyOf(maxSamples), foa.channel(2).copyOf(maxSamples), foa.channel(3).copyOf(maxSamples), foa.directSampleIndex().takeIf { it in 0 until maxSamples } ?: -1)
    }

    private fun trimRir(rir: ImpulseResponse): ImpulseResponse {
        val maxSamples = max(1, (rir.sampleRate() * maxIrSeconds).toInt())
        val src = rir.samples()
        if (src.size <= maxSamples) return rir
        return ImpulseResponse(rir.sampleRate(), src.copyOf(maxSamples))
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input.clone()
        val size = max(1, kotlin.math.ceil(input.size.toDouble() * toRate / fromRate).toInt())
        val out = FloatArray(size)
        val ratio = fromRate.toDouble() / toRate
        for (i in out.indices) {
            val position = i * ratio
            val a = position.toInt().coerceIn(0, input.lastIndex)
            val b = (a + 1).coerceAtMost(input.lastIndex)
            val t = (position - a).toFloat()
            out[i] = input[a] + (input[b] - input[a]) * t
        }
        return out
    }

    private fun resampledDirectIndex(index: Int, fromRate: Int, toRate: Int, length: Int): Int {
        if (index < 0 || length <= 0) return -1
        return kotlin.math.round(index.toDouble() * toRate / fromRate).toInt().coerceIn(0, length - 1)
    }

    /** Remove the known direct arrival. Legacy FOA without metadata keeps the conservative peak heuristic. */
    private fun removeDirectImpulse(channel: FloatArray, directIndex: Int) {
        if (directIndex in channel.indices) {
            // Linear resampling can spread one impulse across the adjacent sample. Remove the tiny direct kernel only.
            for (i in max(0, directIndex - 1)..kotlin.math.min(channel.lastIndex, directIndex + 1)) channel[i] = 0f
            return
        }
        var peak = 0f
        for (v in channel) peak = max(peak, kotlin.math.abs(v))
        if (peak <= 1.0e-8f) return
        val threshold = peak * 0.12f
        for (i in channel.indices) {
            if (kotlin.math.abs(channel[i]) >= threshold) { channel[i] = 0f; return }
        }
    }
}
