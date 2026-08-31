package dev.acoustic.core.dsp

import dev.acoustic.api.math.Vec3
import dev.acoustic.core.passes.EarlyReflectionField
import dev.acoustic.core.rir.ImpulseResponse
import kotlin.math.sqrt

/** Builds/decodes a conservative FOA wet field while preserving the mono reference RIR as total energy. */
object FoaRenderer {
    private const val INV_SQRT_2 = 0.7071067811865476

    @JvmStatic
    fun encode(reference: ImpulseResponse, early: EarlyReflectionField?): FoaImpulseResponse = encode(reference, early, -1)

    @JvmStatic
    fun encode(reference: ImpulseResponse, early: EarlyReflectionField?, directSampleIndex: Int): FoaImpulseResponse {
        val mono = reference.samples()
        val w = FloatArray(mono.size)
        val y = FloatArray(mono.size)
        val z = FloatArray(mono.size)
        val x = FloatArray(mono.size)
        // Omnidirectional bed carries the full late/wave field. Directional early events are added below.
        for (i in mono.indices) w[i] = (mono[i] * INV_SQRT_2).toFloat()
        if (early != null) {
            for (event in early.events()) {
                val index = (event.delaySeconds() * reference.sampleRate()).toInt()
                if (index !in mono.indices) continue
                val direction = safeNormalize(event.arrivalDirection())
                var energy = 0.0
                val spectrum = event.energySpectrum()
                for (v in spectrum) energy += v
                val amplitude = (energy / spectrum.size.coerceAtLeast(1)).toFloat()
                // ACN/SN3D: W,Y,Z,X. Minecraft world coordinates map X right, Y up, Z forward.
                w[index] += (amplitude * INV_SQRT_2).toFloat()
                y[index] += (amplitude * direction.y).toFloat()
                z[index] += (amplitude * direction.z).toFloat()
                x[index] += (amplitude * direction.x).toFloat()
            }
        }
        return FoaImpulseResponse(reference.sampleRate(), w, y, z, x, directSampleIndex)
    }

    /** Basic binaural-like stereo decode around listener yaw basis; HRTF providers can replace this later. */
    @JvmStatic
    fun decodeStereo(foa: FoaImpulseResponse, forward: Vec3, up: Vec3 = Vec3(0.0, 1.0, 0.0)): Array<FloatArray> {
        val f = safeNormalize(forward)
        val u0 = safeNormalize(up)
        val right = safeNormalize(Vec3(f.z, 0.0, -f.x))
        val w = foa.channel(0); val y = foa.channel(1); val z = foa.channel(2); val x = foa.channel(3)
        val left = FloatArray(foa.length()); val outRight = FloatArray(foa.length())
        for (i in left.indices) {
            val worldX = x[i]; val worldY = y[i]; val worldZ = z[i]
            val side = (worldX * right.x + worldY * right.y + worldZ * right.z).toFloat()
            val front = (worldX * f.x + worldY * f.y + worldZ * f.z).toFloat()
            val vertical = (worldX * u0.x + worldY * u0.y + worldZ * u0.z).toFloat()
            val base = w[i] * INV_SQRT_2.toFloat() + front * 0.18f + vertical * 0.03f
            left[i] = base - side * 0.50f
            outRight[i] = base + side * 0.50f
        }
        return arrayOf(left, outRight)
    }

    private fun safeNormalize(v: Vec3): Vec3 {
        val len2 = v.lengthSquared()
        return if (len2 <= 1.0e-12) Vec3(0.0, 0.0, 1.0) else v.multiply(1.0 / sqrt(len2))
    }
}
