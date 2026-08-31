package dev.acoustic.api.environment

import dev.acoustic.api.material.FrequencyBands

/** Built-in propagation-media defaults. Shader/platform integrations may provide richer media later. */
object AcousticMedia {
    /** Air absorption remains owned by AcousticEnvironment for backward compatibility. */
    @JvmField val AIR = AcousticMedium("acoustic:air", 1.2041, 343.0, FloatArray(FrequencyBands.COUNT))

    /**
     * Fresh-water-like Minecraft liquid.  Bulk attenuation is deliberately tiny over room/game
     * distances; the dominant air/water effect is the impedance mismatch at the interface.
     * Values are conservative octave-band approximations expressed as amplitude nepers/metre.
     */
    @JvmField val WATER = AcousticMedium(
        "acoustic:water",
        998.2,
        1482.0,
        dbPerKilometerToNepersPerMeter(floatArrayOf(0.001f, 0.003f, 0.015f, 0.06f, 0.12f, 0.25f, 0.65f, 2.0f))
    )


    /**
     * Basaltic-melt-like propagation medium used for vanilla lava and molten-fluid fallbacks.
     * Density/sound speed intentionally model the volume medium; spectral loss remains conservative
     * because reliable audible-band attenuation data for arbitrary Minecraft/modded melts is scarce.
     */
    @JvmField val LAVA = AcousticMedium(
        "acoustic:lava",
        2700.0,
        2600.0,
        FloatArray(FrequencyBands.COUNT)
    )

    @JvmStatic
    fun dbPerKilometerToNepersPerMeter(dbPerKilometer: FloatArray): FloatArray {
        require(dbPerKilometer.size == FrequencyBands.COUNT) { "wrong spectrum size" }
        return FloatArray(dbPerKilometer.size) { i ->
            val value = dbPerKilometer[i]
            require(value >= 0f && value.isFinite()) { "attenuation must be finite and non-negative" }
            (value / 8.685889638 / 1000.0).toFloat()
        }
    }
}
