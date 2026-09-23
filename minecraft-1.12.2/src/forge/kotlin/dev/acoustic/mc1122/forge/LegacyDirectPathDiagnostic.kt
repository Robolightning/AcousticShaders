package dev.acoustic.mc1122.forge

import dev.acoustic.core.passes.DirectPathResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only test surface for proving that a real production source solve traversed the expected media.
 * Disabled unless explicitly enabled by a JVM property. It never feeds data back into DSP.
 */
object LegacyDirectPathDiagnostic {
    data class Snapshot(
        val generation: Long,
        val distanceMeters: Double,
        val delaySeconds: Double,
        val transmission: FloatArray,
        val solidCells: Int,
        val occupiedMeters: Double,
        val airMeters: Double,
        val liquidMeters: Double,
        val mediumBoundaryCount: Int,
        val sourceMediumId: String,
        val listenerMediumId: String
    )

    private val armed = ConcurrentHashMap<Int, Long>()
    private val snapshots = ConcurrentHashMap<Int, Snapshot>()

    @JvmStatic fun enabled(): Boolean = java.lang.Boolean.getBoolean("acousticshaders.probe.directPath")

    @JvmStatic fun arm(sourceId: Int, generation: Long) {
        if (!enabled() || sourceId <= 0 || generation <= 0L) return
        armed[sourceId] = generation
        snapshots.remove(sourceId)
    }

    @JvmStatic fun publish(sourceId: Int, generation: Long, result: DirectPathResult?) {
        if (!enabled() || result == null || armed[sourceId] != generation) return
        snapshots[sourceId] = Snapshot(
            generation,
            result.distanceMeters,
            result.delaySeconds,
            result.transmission.clone(),
            result.solidCells,
            result.occupiedMeters,
            result.airMeters,
            result.liquidMeters,
            result.mediumBoundaryCount,
            result.sourceMediumId,
            result.listenerMediumId
        )
    }

    @JvmStatic fun disarm(sourceId: Int) {
        armed.remove(sourceId)
        snapshots.remove(sourceId)
    }

    @JvmStatic fun clear() {
        armed.clear()
        snapshots.clear()
    }

    @JvmStatic fun snapshot(sourceId: Int): Snapshot? = snapshots[sourceId]
}
