package dev.acoustic.core.passes

import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.source.AcousticSourceProfile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Projects the portable hybrid response to compact legacy OpenAL EFX controls. */
class HybridLegacyProjector {
    fun project(response: HybridResponse): LegacyEffectParameters = project(response, LegacyEffectTuning.DEFAULT, AcousticSourceProfile.GENERIC)
    fun project(response: HybridResponse, tuning: LegacyEffectTuning?): LegacyEffectParameters = project(response, tuning, AcousticSourceProfile.GENERIC)

    fun project(response: HybridResponse, tuning: LegacyEffectTuning?, source: AcousticSourceProfile?): LegacyEffectParameters {
        val actualTuning = tuning ?: LegacyEffectTuning.DEFAULT
        val actualSource = source ?: AcousticSourceProfile.GENERIC
        if (actualSource.bypassAcoustics()) return LegacyEffectParameters(1f, 1f, 0f, 1f)
        val direct = response.direct()!!
        val low = average(direct.transmission, 0, 3)
        val high = average(direct.transmission, 4, FrequencyBands.COUNT)
        var directGain = sqrt(max(0.0001f, 0.35f * low + 0.65f * high))
        var directHf = if (directGain <= 0.0001f) 0f else clamp(sqrt(high / max(0.0001f, low)))
        if (direct.solidCells == 0 && direct.mediumBoundaryCount == 0) {
            directGain = 1f
            directHf = 1f
        } else {
            directGain = clamp(directGain).toDouble().pow(actualSource.occlusionScale().toDouble()).toFloat()
            directHf = clamp(directHf).toDouble().pow(max(0.35f, actualSource.occlusionScale()).toDouble()).toFloat()
        }

        val diffraction = response.diffraction()
        if (direct.solidCells > 0 && actualTuning.diffraction() && diffraction != null && diffraction.available() && actualSource.diffractionScale() > 0f) {
            var dLow = 0f
            var dHigh = 0f
            var b = 0
            while (b < 3) { dLow += diffraction.transmission(b); b++ }
            b = 4
            while (b < FrequencyBands.COUNT) { dHigh += diffraction.transmission(b); b++ }
            dLow /= 3f
            dHigh /= (FrequencyBands.COUNT - 4).toFloat()
            val diffracted = sqrt(max(0f, 0.55f * dLow + 0.45f * dHigh)) * actualSource.diffractionScale()
            directGain = max(directGain, clamp(diffracted))
            if (diffracted > 0.0001f) directHf = max(directHf * 0.55f, clamp(sqrt(dHigh / max(0.0001f, dLow))))
        }

        val emissionLow = averageEmission(actualSource, 0, 3)
        val emissionHigh = averageEmission(actualSource, 4, FrequencyBands.COUNT)
        val emissionMean = averageEmission(actualSource, 0, FrequencyBands.COUNT)
        directGain = clamp(directGain * actualSource.directScale() * sqrt(max(0.05f, emissionMean)))
        if (emissionLow > 1.0e-5f) directHf = clamp(directHf * sqrt(emissionHigh / emissionLow))

        /*
         * Playback transfer for a human listener submerged in liquid.  The propagation model above
         * remains a pressure-field model; this small EFX-only coloration represents ear/head coupling
         * rather than pretending that water itself strongly absorbs treble over a few metres.
         */
        if (direct.listenerSubmerged()) {
            directGain = clamp(directGain * 0.82f)
            directHf = clamp(directHf * 0.48f)
        }

        var earlyEnergy = 0f
        var earlyCount = 0
        val early = response.early()
        if (early != null) for (event in early.events()) {
            var b = 0
            while (b < FrequencyBands.COUNT) { earlyEnergy += event.energy(b) * actualSource.emission(b); b++ }
            earlyCount += FrequencyBands.COUNT
        }
        if (earlyCount > 0) earlyEnergy = earlyEnergy / earlyCount * actualSource.earlyScale()

        val late = response.late()
        var lateEnergy = 0f
        var lateLow = 0f
        var lateHigh = 0f
        var rt60 = 0.0
        if (late != null) {
            var b = 0
            while (b < FrequencyBands.COUNT) {
                val e = late.energy(b) * actualSource.emission(b) * actualSource.lateScale()
                lateEnergy += e
                rt60 += late.rt60(b)
                if (b < 3) lateLow += e else if (b >= 4) lateHigh += e
                b++
            }
            lateEnergy /= FrequencyBands.COUNT.toFloat()
            lateLow /= 3f
            lateHigh /= (FrequencyBands.COUNT - 4).toFloat()
            rt60 /= FrequencyBands.COUNT.toDouble()
        }

        var waveEnergy = 0f
        val wave = response.wave()
        if (response.waveEnabledForHybrid() && wave != null && wave.hasTimeDomainResponse()) {
            val impulse = wave.listenerImpulse()
            var sum = 0.0
            for (v in impulse) sum += v.toDouble() * v.toDouble()
            if (impulse.isNotEmpty()) waveEnergy = sqrt(sum / impulse.size).toFloat() * max(0.1f, emissionLow)
        }

        var wet = 0f
        if (actualTuning.lateReverb()) {
            val physical = (0.72 * sqrt(max(0f, lateEnergy).toDouble()) + 0.20 * sqrt(max(0f, earlyEnergy).toDouble()) + 0.08 * min(1f, waveEnergy * 3f)).toFloat()
            val duration = max(0.55, min(1.35, if (rt60 <= 0.0) 0.55 else 0.55 + 0.16 * sqrt(rt60))).toFloat()
            wet = clamp(physical * duration * actualTuning.wetScale())
        }
        var sendHf = if (lateEnergy <= 1.0e-6f) directHf else clamp(sqrt(lateHigh / max(1.0e-6f, lateLow)))
        if (direct.listenerSubmerged()) sendHf = clamp(sendHf * 0.62f)
        return LegacyEffectParameters(directGain, directHf, wet, sendHf)
    }

    private fun average(values: FloatArray, from: Int, to: Int): Float {
        var sum = 0f
        var i = from
        while (i < to) { sum += values[i]; i++ }
        return sum / (to - from)
    }

    private fun averageEmission(profile: AcousticSourceProfile, from: Int, to: Int): Float {
        var sum = 0f
        var i = from
        while (i < to) { sum += profile.emission(i); i++ }
        return sum / (to - from)
    }

    private fun clamp(value: Float): Float = if (value.isNaN()) 0f else max(0f, min(1f, value))
}
