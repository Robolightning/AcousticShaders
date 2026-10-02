package dev.acoustic.core.passes

import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.api.source.AcousticSourceProfile
import dev.acoustic.core.rir.ImpulseResponse
import java.util.Collections
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic broadband RIR synthesis with complementary wave/geometric low-frequency crossover. */
class ImpulseResponsePass(private val sampleRate: Int, private val seconds: Double) : Pass {
    init {
        require(sampleRate >= 8000 && seconds > 0.0 && seconds <= 20.0) { "invalid IR settings" }
    }

    override fun id(): String = "standard.impulse_response"
    override fun reads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.HYBRID_RESPONSE)
    override fun optionalReads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.SOURCE_BEHAVIOR)
    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.IMPULSE_RESPONSE)

    override fun execute(context: PassContext) {
        val hybrid = context.require(StandardResources.HYBRID_RESPONSE)
        val source = context.get(StandardResources.SOURCE_BEHAVIOR) ?: AcousticSourceProfile.GENERIC
        val geometric = geometric(hybrid, source)
        var impulse = geometric
        val wave = hybrid.wave()
        if (hybrid.waveEnabledForHybrid() && wave != null) {
            impulse = when {
                wave.hasTimeDomainResponse() -> mergeFdtd(geometric, hybrid, wave)
                wave.modesHz().isNotEmpty() -> addModalTail(geometric, hybrid, wave)
                else -> impulse
            }
        }
        context.put(StandardResources.IMPULSE_RESPONSE, ImpulseResponse(sampleRate, impulse))
    }

    private fun geometric(hybrid: HybridResponse, source: AcousticSourceProfile): FloatArray {
        val ir = FloatArray(max(1, ceil(sampleRate * seconds).toInt()))
        val direct = hybrid.direct()!!
        if (source.bypassAcoustics()) {
            add(ir, direct.delaySeconds, 1f)
            return ir
        }
        add(
            ir,
            direct.delaySeconds,
            average(direct.transmission) * source.directScale() * emissionAverage(source, 0, FrequencyBands.COUNT)
        )
        for (event in hybrid.early()!!.events()) {
            add(ir, event.delaySeconds(), weightedAverage(event.energySpectrum(), source) * source.earlyScale())
        }
        val late = hybrid.late()!!
        var rt = 0.0
        var energy = 0.0
        var band = 0
        while (band < FrequencyBands.COUNT) {
            rt += late.rt60(band)
            energy += late.energy(band) * source.emission(band) * source.lateScale()
            band++
        }
        rt /= FrequencyBands.COUNT
        energy /= FrequencyBands.COUNT
        if (rt > 0.0 && energy > 0.0) {
            val start = min(ir.size - 1, (0.05 * sampleRate).toInt())
            var state = -7046029254386353131L
            var i = start
            while (i < ir.size) {
                state = state xor (state shl 13)
                state = state xor (state ushr 7)
                state = state xor (state shl 17)
                val noise = ((state and 0xffffL) / 32767.5) - 1.0
                val t = (i - start) / sampleRate.toDouble()
                val env = 10.0.pow(-3.0 * t / rt)
                ir[i] += (noise * env * energy * 0.015).toFloat()
                i++
            }
        }
        return ir
    }

    private fun mergeFdtd(geometric: FloatArray, hybrid: HybridResponse, wave: WaveFieldResult): FloatArray {
        val response = resample(wave.listenerImpulse(), wave.samplePeriodSeconds(), geometric.size)
        alignAndScale(response, hybrid)
        val stages = when {
            hybrid.crossfadeOctaves() <= 0.65 -> 3
            hybrid.crossfadeOctaves() <= 1.15 -> 2
            else -> 1
        }
        val geometricLow = lowpass(geometric, hybrid.crossoverHz(), stages)
        val waveLow = lowpass(response, hybrid.crossoverHz(), stages)
        val out = FloatArray(geometric.size)
        val waveOnly = hybrid.hybridMode().equals("WAVE_ONLY", ignoreCase = true)
        var i = 0
        while (i < out.size) {
            out[i] = (if (waveOnly) 0f else geometric[i] - geometricLow[i]) + waveLow[i]
            i++
        }
        return out
    }

    private fun addModalTail(geometric: FloatArray, hybrid: HybridResponse, wave: WaveFieldResult): FloatArray {
        val out = geometric.clone()
        val late = hybrid.late()!!
        var rt = 0.0
        var band = 0
        while (band < 3) {
            rt += late.rt60(band)
            band++
        }
        rt = max(0.25, rt / 3.0)
        val direct = hybrid.direct()!!
        val start = min(out.size - 1, (direct.delaySeconds * sampleRate).roundToInt())
        val directGain = average(direct.transmission, 0, 3)
        val n = max(1, wave.modesHz().size)
        for (mode in wave.modesHz()) {
            val frequency = mode
            if (frequency <= 0.0 || frequency > hybrid.crossoverHz()) continue
            val amplitude = directGain * 0.018 / sqrt(n.toDouble())
            var i = start
            while (i < out.size) {
                val t = (i - start) / sampleRate.toDouble()
                val env = 10.0.pow(-3.0 * t / rt)
                out[i] += (sin(2.0 * Math.PI * frequency * t) * env * amplitude).toFloat()
                i++
            }
        }
        return out
    }

    private fun resample(input: FloatArray, samplePeriodSeconds: Double, length: Int): FloatArray {
        val out = FloatArray(length)
        if (input.isEmpty() || !(samplePeriodSeconds > 0.0)) return out
        val sourceRate = 1.0 / samplePeriodSeconds
        var i = 0
        while (i < length) {
            val position = i * sourceRate / sampleRate
            val sourceIndex = floor(position).toInt()
            if (sourceIndex >= 0 && sourceIndex < input.size) {
                val fraction = position - sourceIndex
                val a = input[sourceIndex]
                val b = if (sourceIndex + 1 < input.size) input[sourceIndex + 1] else a
                out[i] = (a + (b - a) * fraction).toFloat()
            }
            i++
        }
        return out
    }

    private fun alignAndScale(wave: FloatArray, hybrid: HybridResponse) {
        var maxValue = 0f
        var first = 0
        for (value in wave) maxValue = max(maxValue, abs(value))
        if (maxValue <= 1e-8f) return
        val threshold = maxValue * 0.025f
        var i = 0
        while (i < wave.size) {
            if (abs(wave[i]) >= threshold) {
                first = i
                break
            }
            i++
        }
        val direct = hybrid.direct()!!
        val target = max(0, min(wave.size - 1, (direct.delaySeconds * sampleRate).roundToInt()))
        val shift = target - first
        if (shift != 0) {
            val copy = wave.clone()
            java.util.Arrays.fill(wave, 0f)
            i = 0
            while (i < copy.size) {
                val destination = i + shift
                if (destination >= 0 && destination < wave.size) wave[destination] = copy[i]
                i++
            }
        }
        var peak = 0f
        val radius = max(1, (0.012 * sampleRate).toInt())
        i = max(0, target - radius)
        val end = min(wave.size, target + radius)
        while (i < end) {
            peak = max(peak, abs(wave[i]))
            i++
        }
        val wanted = average(direct.transmission, 0, 3)
        val scale = if (peak > 1e-7f) min(4f, wanted / peak) else 0f
        i = 0
        while (i < wave.size) {
            wave[i] *= scale
            i++
        }
    }

    private fun lowpass(input: FloatArray, cutoffHz: Double, stages: Int): FloatArray {
        var current = input.clone()
        val alpha = 1.0 - exp(-2.0 * Math.PI * max(5.0, min(cutoffHz, sampleRate * 0.45)) / sampleRate)
        var stage = 0
        while (stage < stages) {
            val next = FloatArray(current.size)
            var y = 0.0
            var i = 0
            while (i < current.size) {
                y += alpha * (current[i] - y)
                next[i] = y.toFloat()
                i++
            }
            current = next
            stage++
        }
        return current
    }

    private fun add(ir: FloatArray, delaySeconds: Double, gain: Float) {
        val index = (delaySeconds * sampleRate).roundToInt()
        if (index >= 0 && index < ir.size) ir[index] += gain
    }

    companion object {
        private fun weightedAverage(values: FloatArray, source: AcousticSourceProfile): Float {
            var sum = 0.0
            var i = 0
            while (i < values.size) {
                sum += values[i] * source.emission(i)
                i++
            }
            return (sum / values.size).toFloat()
        }

        private fun emissionAverage(source: AcousticSourceProfile, from: Int, to: Int): Float {
            var sum = 0.0
            var i = from
            while (i < to) {
                sum += source.emission(i)
                i++
            }
            return (sum / (to - from)).toFloat()
        }

        private fun average(values: FloatArray): Float = average(values, 0, values.size)

        private fun average(values: FloatArray, from: Int, to: Int): Float {
            var sum = 0.0
            var i = from
            while (i < to) {
                sum += values[i]
                i++
            }
            return (sum / (to - from)).toFloat()
        }
    }
}
