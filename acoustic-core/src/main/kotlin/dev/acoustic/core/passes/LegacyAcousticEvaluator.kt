package dev.acoustic.core.passes

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.pipeline.ParallelWorkExecutor
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.trace.RayHit
import dev.acoustic.core.trace.ShapeRaycast
import dev.acoustic.core.trace.VoxelDda
import dev.acoustic.core.trace.VoxelRaycast
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/** Allocation-bounded projection used by legacy real-time backends. */
class LegacyAcousticEvaluator {
    fun evaluate(scene: AcousticScene, source: Vec3, listener: Vec3, room: LegacyRoomEstimate): LegacyEffectParameters =
        evaluate(scene, source, listener, room, LegacyEffectTuning.DEFAULT)

    fun evaluate(scene: AcousticScene, source: Vec3, listener: Vec3, room: LegacyRoomEstimate, tuning: LegacyEffectTuning): LegacyEffectParameters {
        val spectrum = FloatArray(8) { 1f }
        var walls = 0
        val pathDistance = source.distance(listener)
        if (pathDistance > 1.0e-9) {
            val rayDir = listener.subtract(source).normalize()
            VoxelDda.traceCells(scene, source, listener, VoxelDda.CellVisitor { x, y, z, tEnter, tExit, voxel ->
                if (!voxel.solid() || voxel.shape().isEmpty()) return@CellVisitor true
                val occupied = ShapeRaycast.occupiedLength(voxel.shape(), x, y, z, source, rayDir, tEnter, tExit)
                if (occupied <= 1.0e-7) return@CellVisitor true
                walls++
                val base = voxel.material().transmission()
                var i = 0
                while (i < spectrum.size) {
                    val value = max(0.0025, min(1.0, base * (1f - 0.20f * voxel.material().absorption(i)).toDouble()))
                    spectrum[i] *= value.pow(occupied).toFloat()
                    i++
                }
                walls < 12
            })
        }
        val low = average(spectrum, 0, 3)
        val high = average(spectrum, 4, 8)
        var direct = sqrt(max(0.0001f, 0.35f * low + 0.65f * high))
        var hf = if (direct <= 0.0001f) 0f else clamp(sqrt(high / max(0.0001f, low)))
        if (walls == 0) {
            direct = 1f
            hf = 1f
        } else if (tuning.diffraction()) {
            val leak = 0.015f + tuning.diffractionLeak() * 0.22f / sqrt(max(1, walls).toFloat())
            direct = max(direct, leak)
            hf = min(hf, 0.65f + 0.25f * (1f - tuning.diffractionLeak()))
        }

        val distance = source.distance(listener)
        val distanceWet = clamp((1.0 - exp(-distance / 12.0)).toFloat())
        val enclosure = 1f - room.openness()
        val wallBoost = clamp(walls * 0.16f)
        val enclosureWet = enclosure.toDouble().pow(1.45).toFloat()
        val wallWet = wallBoost * (0.30f + 0.70f * enclosure)
        val send = if (tuning.lateReverb()) clamp((0.012f + 0.39f * enclosureWet + 0.12f * distanceWet * enclosure + 0.16f * wallWet) * tuning.wetScale()) else 0f
        val sendHf = clamp(0.38f + 0.62f * room.gainHf())
        return LegacyEffectParameters(direct, hf, send, sendHf)
    }

    fun estimateRoom(scene: AcousticScene, listener: Vec3, maxDistance: Double): LegacyRoomEstimate =
        estimateRoom(scene, listener, maxDistance, LegacyEffectTuning.DEFAULT, 64)

    fun estimateRoom(scene: AcousticScene, listener: Vec3, maxDistance: Double, tuning: LegacyEffectTuning): LegacyRoomEstimate =
        estimateRoom(scene, listener, maxDistance, tuning, 64)

    fun estimateRoom(scene: AcousticScene, listener: Vec3, maxDistance: Double, tuning: LegacyEffectTuning, rayCount: Int): LegacyRoomEstimate =
        estimateRoomParallel(scene, listener, maxDistance, tuning, rayCount, null)

    fun estimateRoomParallel(scene: AcousticScene, listener: Vec3, maxDistance: Double, tuning: LegacyEffectTuning, rayCount: Int, parallel: ParallelWorkExecutor?): LegacyRoomEstimate {
        val directions = max(16, min(ROOM_DIRECTIONS.size, rayCount))
        val distances = DoubleArray(directions)
        val absorptions = DoubleArray(directions)
        val states = ByteArray(directions)
        val task = ParallelWorkExecutor.RangeTask { from, to ->
            var di = from
            while (di < to) {
                val dir = ROOM_DIRECTIONS[di]
                val hit: RayHit? = VoxelRaycast.firstSolid(scene, listener, dir, maxDistance)
                if (hit != null) {
                    states[di] = 1
                    distances[di] = max(0.25, hit.distance())
                    val voxel = scene.voxelAt(hit.x(), hit.y(), hit.z())
                    var absorption = 0.0
                    var b = 0
                    while (b < 8) { absorption += voxel.material().absorption(b); b++ }
                    absorptions[di] = absorption / 8.0
                }
                di++
            }
        }
        try {
            if (parallel != null && parallel.workerCount() > 1) parallel.forRange(0, directions, task) else task.run(0, directions)
        } catch (e: Exception) {
            throw RuntimeException("parallel room probe failed", e)
        }

        var hitDistanceSum = 0.0
        var absorptionSum = 0.0
        var hits = 0
        var upward = 0
        var upwardMisses = 0
        var di = 0
        while (di < directions) {
            val dir = ROOM_DIRECTIONS[di]
            val up = dir.y > 0.18
            if (up) upward++
            if (states[di].toInt() == 0) {
                if (up) upwardMisses++
            } else {
                hits++
                hitDistanceSum += distances[di]
                absorptionSum += absorptions[di]
            }
            di++
        }
        val rawHitFraction = hits.toFloat() / directions
        val skyOpenness = if (upward == 0) 1f else upwardMisses.toFloat() / upward
        val enclosure = clamp(rawHitFraction * (0.35f + 0.65f * (1f - skyOpenness)))
        val openness = 1f - enclosure
        val mean = if (hits == 0) maxDistance else hitDistanceSum / hits
        val meanAbsorption = if (hits == 0) 0.35 else absorptionSum / hits
        val reflectivity = max(0.0, min(1.0, 1.0 - meanAbsorption))
        val enclosedDecay = 0.16 + max(0.5, mean) * (0.13 + 0.15 * reflectivity)
        val enclosureShape = enclosure.toDouble().pow(1.60)
        val decay = max(0.10, min(12.0, (0.12 + enclosureShape * (enclosedDecay - 0.12)) * tuning.decayScale())).toFloat()
        val gainHf = clamp((1.0 - 0.68 * meanAbsorption).toFloat())
        val diffusion = clamp((0.18 + 0.77 * enclosure.toDouble().pow(1.15)).toFloat() * min(1.15f, 0.88f + 0.12f * tuning.wetScale()))
        val density = clamp((0.06 + 0.92 * enclosure.toDouble().pow(1.35)).toFloat() * min(1.15f, 0.90f + 0.10f * tuning.wetScale()))
        return LegacyRoomEstimate(mean, openness, decay, gainHf, diffusion, density)
    }

    private fun average(values: FloatArray, from: Int, to: Int): Float {
        var sum = 0f
        var i = from
        while (i < to) { sum += values[i]; i++ }
        return sum / (to - from)
    }

    private fun clamp(value: Float): Float = max(0f, min(1f, value))

    companion object {
        private val ROOM_DIRECTIONS: Array<Vec3> = createDirections(512)

        private fun createDirections(n: Int): Array<Vec3> {
            val out = Array(n) { Vec3(0.0, 0.0, 1.0) }
            val golden = PI * (3.0 - sqrt(5.0))
            var i = 0
            while (i < n) {
                val y = 1.0 - 2.0 * (i + 0.5) / n
                val r = sqrt(max(0.0, 1.0 - y * y))
                val theta = golden * i
                out[i] = Vec3(cos(theta) * r, y, sin(theta) * r)
                i++
            }
            return out
        }
    }
}
