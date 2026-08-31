package dev.acoustic.api.environment

import dev.acoustic.api.material.FrequencyBands
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Homogeneous fluid propagation medium carried by non-solid acoustic voxels.
 *
 * Surface material and volume medium are intentionally separate: a water voxel has a liquid
 * propagation medium even though it is not a solid reflecting surface.  This allows path delay,
 * bulk absorption and fluid-fluid boundary transmission to be evaluated without abusing the
 * surface-material transmission coefficient.
 */
class AcousticMedium(
    id: String,
    densityKgPerCubicMeter: Double,
    speedOfSoundMetersPerSecond: Double,
    absorptionNepersPerMeter: FloatArray
) {
    private val id: String
    private val densityKgPerCubicMeter: Double
    private val speedOfSoundMetersPerSecond: Double
    private val absorptionNepersPerMeter: FloatArray

    init {
        require(id.isNotBlank()) { "medium id must not be blank" }
        require(densityKgPerCubicMeter > 0.0 && densityKgPerCubicMeter.isFinite()) { "medium density must be finite and positive" }
        require(speedOfSoundMetersPerSecond > 0.0 && speedOfSoundMetersPerSecond.isFinite()) { "medium speed of sound must be finite and positive" }
        require(absorptionNepersPerMeter.size == FrequencyBands.COUNT) { "medium absorption must contain exactly ${FrequencyBands.COUNT} octave bands" }
        for (value in absorptionNepersPerMeter) require(value >= 0f && value.isFinite()) { "medium absorption must be finite and non-negative" }
        this.id = id
        this.densityKgPerCubicMeter = densityKgPerCubicMeter
        this.speedOfSoundMetersPerSecond = speedOfSoundMetersPerSecond
        this.absorptionNepersPerMeter = absorptionNepersPerMeter.clone()
    }

    fun id(): String = id
    fun densityKgPerCubicMeter(): Double = densityKgPerCubicMeter
    fun speedOfSoundMetersPerSecond(): Double = speedOfSoundMetersPerSecond
    fun characteristicImpedanceRayl(): Double = densityKgPerCubicMeter * speedOfSoundMetersPerSecond
    fun absorptionNepersPerMeter(band: Int): Float = absorptionNepersPerMeter[band]
    fun absorptionSpectrum(): FloatArray = absorptionNepersPerMeter.clone()

    fun transmission(band: Int, distanceMeters: Double): Float =
        if (distanceMeters <= 0.0) 1f else exp(-absorptionNepersPerMeter[band] * distanceMeters).toFloat()

    /**
     * Pressure-amplitude gain at an ideal flat fluid-fluid interface.
     * cosIncidence is |ray dot surfaceNormal|.  If Snell's law has no propagating solution,
     * the far-field transmitted ray is zero (total reflection in the geometrical model).
     */
    /** Signed pressure reflection coefficient for a lossless flat fluid-fluid boundary. */
    fun interfacePressureReflectionTo(other: AcousticMedium, cosIncidence: Double): Double {
        if (id == other.id) return 0.0
        val ci = max(0.0, min(1.0, cosIncidence))
        if (ci <= 1.0e-12) return 1.0
        val sinI = sqrt(max(0.0, 1.0 - ci * ci))
        val sinT = (other.speedOfSoundMetersPerSecond / speedOfSoundMetersPerSecond) * sinI
        if (sinT >= 1.0) return 1.0
        val ct = sqrt(max(0.0, 1.0 - sinT * sinT))
        val z1 = characteristicImpedanceRayl()
        val z2 = other.characteristicImpedanceRayl()
        val denominator = z2 * ci + z1 * ct
        if (denominator <= 0.0 || !denominator.isFinite()) return 1.0
        return ((z2 * ci - z1 * ct) / denominator).coerceIn(-1.0, 1.0)
    }

    /** Fraction of incident acoustic power reflected by the ideal interface. */
    fun interfacePowerReflectionTo(other: AcousticMedium, cosIncidence: Double): Double {
        val r = interfacePressureReflectionTo(other, cosIncidence)
        return (r * r).coerceIn(0.0, 1.0)
    }

    /** Fraction of incident acoustic power carried by the propagating transmitted wave. */
    fun interfacePowerTransmissionTo(other: AcousticMedium, cosIncidence: Double): Double =
        (1.0 - interfacePowerReflectionTo(other, cosIncidence)).coerceIn(0.0, 1.0)

    /**
     * Signal-amplitude throughput used by the renderer.  The shader pipeline transports amplitudes,
     * while boundary conservation is naturally expressed in power, so the gain is sqrt(T_power).
     */
    fun interfaceAmplitudeTransmissionTo(other: AcousticMedium, cosIncidence: Double): Float =
        sqrt(interfacePowerTransmissionTo(other, cosIncidence)).toFloat()

    /** Signal-amplitude magnitude of the reflected branch (phase is not represented by this ABI). */
    fun interfaceAmplitudeReflectionTo(other: AcousticMedium, cosIncidence: Double): Float =
        sqrt(interfacePowerReflectionTo(other, cosIncidence)).toFloat()

    override fun toString(): String = "AcousticMedium{$id, rho=$densityKgPerCubicMeter, c=$speedOfSoundMetersPerSecond}"
}
