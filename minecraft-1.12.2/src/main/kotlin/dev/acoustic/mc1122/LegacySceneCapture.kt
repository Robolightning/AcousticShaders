package dev.acoustic.mc1122

import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.material.AcousticMaterial
import dev.acoustic.api.material.AcousticMaterials
import dev.acoustic.api.material.resolve.MaterialDescriptor
import dev.acoustic.api.scene.AcousticVoxel
import dev.acoustic.core.material.MaterialResolver
import dev.acoustic.core.medium.MediumResolver
import dev.acoustic.core.scene.ImmutableVoxelSnapshot
import java.util.HashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Converts unsafe live 1.12.2 world access into immutable worker-safe snapshots.
 *
 * Normal rolling captures reuse overlap from the prior snapshot and sample only newly exposed
 * voxels plus a small refresh volume around the listener. Periodic whole-volume validation is
 * time-sliced so a large shader preset never re-reads the entire acoustic volume in one tick.
 *
 * A new world is bootstrapped progressively from the listener outwards. Unsampled cells are
 * temporarily transparent (air) rather than conservative blockers, preserving vanilla audibility
 * while geometry converges. The bootstrap is driven by a wall-clock budget, so expensive modded
 * collision shapes consume fewer samples per tick instead of creating a multi-second client stall.
 */
class LegacySceneCapture(private val resolver: MaterialResolver, private val mediumResolver: MediumResolver) {
    constructor(resolver: MaterialResolver) : this(resolver, MediumResolver(emptyList()))
    private val resolvedMaterials = HashMap<String, AcousticMaterial>()
    private var previous: ImmutableVoxelSnapshot? = null
    private var lastSampled = 0
    private var lastReused = 0
    private var lastChanged = 0
    private var lastSweepSampled = 0
    private var lastBootstrapSampled = 0
    private var sweepActiveValue = false
    private var sweepCursor = 0
    private var sweepMinX = 0
    private var sweepMinY = 0
    private var sweepMinZ = 0
    private var sweepSizeX = 0
    private var sweepSizeY = 0
    private var sweepSizeZ = 0

    private var bootstrapActiveValue = false
    private var bootstrapKnown: BooleanArray? = null
    private var bootstrapCursor = 0
    private var bootstrapKnownCount = 0
    private var bootstrapMinX = 0
    private var bootstrapMinY = 0
    private var bootstrapMinZ = 0
    private var bootstrapSizeX = 0
    private var bootstrapSizeY = 0
    private var bootstrapSizeZ = 0
    private var bootstrapOrder: IntArray? = null
    private var orderSizeX = 0
    private var orderSizeY = 0
    private var orderSizeZ = 0
    private var lastBootstrapProgress = 1.0

    /** Full conservative capture retained for tooling/tests. */
    @Synchronized
    fun capture(w: LegacyWorldAccess, minX: Int, minY: Int, minZ: Int, sizeX: Int, sizeY: Int, sizeZ: Int): ImmutableVoxelSnapshot =
        captureRolling(w, minX, minY, minZ, sizeX, sizeY, sizeZ, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, 0, true)

    /** Existing API keeps its original semantics; forceFull still samples the full region immediately. */
    @Synchronized
    fun captureRolling(
        w: LegacyWorldAccess, minX: Int, minY: Int, minZ: Int, sizeX: Int, sizeY: Int, sizeZ: Int,
        refreshX: Int, refreshY: Int, refreshZ: Int, refreshRadius: Int, forceFull: Boolean
    ): ImmutableVoxelSnapshot = captureRollingBudgeted(
        w, minX, minY, minZ, sizeX, sizeY, sizeZ, refreshX, refreshY, refreshZ, refreshRadius, forceFull, false, 0
    )

    /** Runtime entry point with center-out progressive initial capture. */
    @Synchronized
    fun captureRollingProgressive(
        w: LegacyWorldAccess, minX: Int, minY: Int, minZ: Int, sizeX: Int, sizeY: Int, sizeZ: Int,
        refreshX: Int, refreshY: Int, refreshZ: Int, refreshRadius: Int,
        beginBootstrap: Boolean, captureBudgetMillis: Double, startSweep: Boolean, sweepBudget: Int
    ): ImmutableVoxelSnapshot {
        require(sizeX >= 1 && sizeY >= 1 && sizeZ >= 1) { "snapshot dimensions" }
        if (beginBootstrap) {
            startBootstrap(minX, minY, minZ, sizeX, sizeY, sizeZ)
            sweepActiveValue = false
            sweepCursor = 0
        }
        if (bootstrapActiveValue) return captureBootstrap(w, minX, minY, minZ, sizeX, sizeY, sizeZ, captureBudgetMillis)
        return captureRollingBudgeted(w, minX, minY, minZ, sizeX, sizeY, sizeZ, refreshX, refreshY, refreshZ, refreshRadius, false, startSweep, sweepBudget)
    }

    /** Runtime-oriented rolling capture with a bounded validation sweep. */
    @Synchronized
    fun captureRollingBudgeted(
        w: LegacyWorldAccess, minX: Int, minY: Int, minZ: Int, sizeX: Int, sizeY: Int, sizeZ: Int,
        refreshX: Int, refreshY: Int, refreshZ: Int, refreshRadius: Int,
        forceFull: Boolean, startSweep: Boolean, sweepBudget: Int
    ): ImmutableVoxelSnapshot {
        require(sizeX >= 1 && sizeY >= 1 && sizeZ >= 1) { "snapshot dimensions" }
        if (forceFull) {
            bootstrapActiveValue = false
            bootstrapKnown = null
            bootstrapOrder = null
            lastBootstrapProgress = 1.0
        }
        val volume = sizeX * sizeY * sizeZ
        val vox = arrayOfNulls<AcousticVoxel>(volume)
        var i = 0
        var sampled = 0
        var reused = 0
        var changed = 0
        var sweepSampled = 0
        val old = previous
        val sameGrid = old != null && old.minX() == minX && old.minY() == minY && old.minZ() == minZ && old.sizeX() == sizeX && old.sizeY() == sizeY && old.sizeZ() == sizeZ
        val canReuse = !forceFull && old != null && old.sizeX() == sizeX && old.sizeY() == sizeY && old.sizeZ() == sizeZ

        if (forceFull) {
            sweepActiveValue = false
            sweepCursor = 0
        } else {
            if (startSweep) {
                sweepActiveValue = true
                sweepCursor = 0
                rememberSweepGrid(minX, minY, minZ, sizeX, sizeY, sizeZ)
            } else if (sweepActiveValue && !sameSweepGrid(minX, minY, minZ, sizeX, sizeY, sizeZ)) {
                sweepCursor = 0
                rememberSweepGrid(minX, minY, minZ, sizeX, sizeY, sizeZ)
            }
        }
        val sweepFrom = if (sweepActiveValue) sweepCursor else 0
        val sweepCount = if (sweepActiveValue) max(0, min(max(1, sweepBudget), volume - sweepFrom)) else 0
        val sweepTo = sweepFrom + sweepCount

        for (y in 0 until sizeY) for (z in 0 until sizeZ) for (x in 0 until sizeX) {
            val wx = minX + x
            val wy = minY + y
            val wz = minZ + z
            val linear = i
            val localRefresh = refreshRadius > 0 && abs(wx - refreshX) <= refreshRadius && abs(wy - refreshY) <= refreshRadius && abs(wz - refreshZ) <= refreshRadius
            val sweepRefresh = sweepActiveValue && linear >= sweepFrom && linear < sweepTo
            val oldVoxel = if (old != null && inside(old, wx, wy, wz)) old.voxelAt(wx, wy, wz) else null
            if (canReuse && !localRefresh && !sweepRefresh && oldVoxel != null) {
                vox[i++] = oldVoxel
                reused++
                continue
            }
            val fresh = sampleVoxel(w, wx, wy, wz)
            sampled++
            if (sweepRefresh) sweepSampled++
            if (oldVoxel != null && equivalent(oldVoxel, fresh)) {
                vox[i++] = oldVoxel
                reused++
            } else {
                vox[i++] = fresh
                changed++
            }
        }

        if (sweepActiveValue) {
            sweepCursor = sweepTo
            if (sweepCursor >= volume) {
                sweepActiveValue = false
                sweepCursor = 0
            }
        }
        val revision = if (sameGrid && changed == 0) requireNotNull(old).revision() else w.revision()
        @Suppress("UNCHECKED_CAST")
        val next = ImmutableVoxelSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, UNKNOWN_AIR, vox as Array<AcousticVoxel>, revision)
        previous = next
        lastSampled = sampled
        lastReused = reused
        lastChanged = changed
        lastSweepSampled = sweepSampled
        lastBootstrapSampled = 0
        return next
    }

    private fun captureBootstrap(w: LegacyWorldAccess, minX: Int, minY: Int, minZ: Int, sizeX: Int, sizeY: Int, sizeZ: Int, budgetMillis: Double): ImmutableVoxelSnapshot {
        remapBootstrapGrid(minX, minY, minZ, sizeX, sizeY, sizeZ)
        val volume = sizeX * sizeY * sizeZ
        val vox = arrayOfNulls<AcousticVoxel>(volume)
        val old = previous
        var reused = 0
        var linear = 0
        for (y in 0 until sizeY) for (z in 0 until sizeZ) for (x in 0 until sizeX) {
            val wx = minX + x
            val wy = minY + y
            val wz = minZ + z
            val prior = if (old != null && inside(old, wx, wy, wz)) old.voxelAt(wx, wy, wz) else null
            vox[linear++] = prior ?: UNKNOWN_AIR
            if (prior != null) reused++
        }
        ensureBootstrapOrder(sizeX, sizeY, sizeZ)
        val order = bootstrapOrder ?: error("bootstrap order")
        val known = bootstrapKnown ?: error("bootstrap known")
        val started = System.nanoTime()
        val budgetNanos = if (budgetMillis <= 0.0) 0L else (budgetMillis * 1_000_000.0).toLong()
        var sampled = 0
        var changed = 0
        while (bootstrapCursor < order.size) {
            val index = order[bootstrapCursor++]
            if (known[index]) continue
            val yz = sizeX * sizeZ
            val ly = index / yz
            val rem = index - ly * yz
            val lz = rem / sizeX
            val lx = rem - lz * sizeX
            val before = vox[index] ?: UNKNOWN_AIR
            val fresh = sampleVoxel(w, minX + lx, minY + ly, minZ + lz)
            sampled++
            if (!equivalent(before, fresh)) {
                vox[index] = fresh
                changed++
            }
            known[index] = true
            bootstrapKnownCount++
            if (sampled > 0 && (budgetNanos == 0L || System.nanoTime() - started >= budgetNanos)) break
        }
        bootstrapActiveValue = bootstrapKnownCount < volume
        lastBootstrapProgress = if (volume == 0) 1.0 else min(1.0, bootstrapKnownCount.toDouble() / volume.toDouble())
        if (!bootstrapActiveValue) {
            bootstrapKnown = null
            bootstrapCursor = 0
            bootstrapOrder = null
            orderSizeX = 0
            orderSizeY = 0
            orderSizeZ = 0
            lastBootstrapProgress = 1.0
        }
        val sameGrid = old != null && old.minX() == minX && old.minY() == minY && old.minZ() == minZ && old.sizeX() == sizeX && old.sizeY() == sizeY && old.sizeZ() == sizeZ
        lastSampled = sampled
        lastReused = max(0, reused)
        lastChanged = changed
        lastSweepSampled = 0
        lastBootstrapSampled = sampled
        if (sameGrid && changed == 0) return requireNotNull(old)
        val revision = if (old == null) w.revision() else if (changed == 0) old.revision() else w.revision()
        @Suppress("UNCHECKED_CAST")
        val next = ImmutableVoxelSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, UNKNOWN_AIR, vox as Array<AcousticVoxel>, revision)
        previous = next
        return next
    }

    private fun startBootstrap(minX: Int, minY: Int, minZ: Int, sx: Int, sy: Int, sz: Int) {
        bootstrapActiveValue = true
        bootstrapKnown = BooleanArray(sx * sy * sz)
        bootstrapKnownCount = 0
        bootstrapCursor = 0
        lastBootstrapProgress = 0.0
        rememberBootstrapGrid(minX, minY, minZ, sx, sy, sz)
        ensureBootstrapOrder(sx, sy, sz)
    }

    private fun remapBootstrapGrid(minX: Int, minY: Int, minZ: Int, sx: Int, sy: Int, sz: Int) {
        val known = bootstrapKnown
        if (known == null) {
            startBootstrap(minX, minY, minZ, sx, sy, sz)
            return
        }
        if (sameBootstrapGrid(minX, minY, minZ, sx, sy, sz)) return
        val oldMinX = bootstrapMinX
        val oldMinY = bootstrapMinY
        val oldMinZ = bootstrapMinZ
        val oldSx = bootstrapSizeX
        val oldSy = bootstrapSizeY
        val oldSz = bootstrapSizeZ
        val mapped = BooleanArray(sx * sy * sz)
        var mappedCount = 0
        var linear = 0
        for (y in 0 until sy) for (z in 0 until sz) for (x in 0 until sx) {
            val wx = minX + x
            val wy = minY + y
            val wz = minZ + z
            if (wx >= oldMinX && wy >= oldMinY && wz >= oldMinZ && wx < oldMinX + oldSx && wy < oldMinY + oldSy && wz < oldMinZ + oldSz) {
                val ox = wx - oldMinX
                val oy = wy - oldMinY
                val oz = wz - oldMinZ
                val oldLinear = (oy * oldSz + oz) * oldSx + ox
                if (known[oldLinear]) {
                    mapped[linear] = true
                    mappedCount++
                }
            }
            linear++
        }
        bootstrapKnown = mapped
        bootstrapKnownCount = mappedCount
        bootstrapCursor = 0
        rememberBootstrapGrid(minX, minY, minZ, sx, sy, sz)
        ensureBootstrapOrder(sx, sy, sz)
        lastBootstrapProgress = mappedCount.toDouble() / mapped.size.toDouble()
    }

    private fun ensureBootstrapOrder(sx: Int, sy: Int, sz: Int) {
        if (bootstrapOrder != null && orderSizeX == sx && orderSizeY == sy && orderSizeZ == sz) return
        val volume = sx * sy * sz
        val cx = sx / 2
        val cy = sy / 2
        val cz = sz / 2
        val maxShell = max(cx, max(cy, cz))
        val counts = IntArray(maxShell + 1)
        for (y in 0 until sy) for (z in 0 until sz) for (x in 0 until sx) {
            val shell = max(abs(x - cx), max(abs(y - cy), abs(z - cz)))
            counts[shell]++
        }
        val cursor = IntArray(counts.size)
        var at = 0
        for (index in counts.indices) {
            cursor[index] = at
            at += counts[index]
        }
        val order = IntArray(volume)
        var linear = 0
        for (y in 0 until sy) for (z in 0 until sz) for (x in 0 until sx) {
            val shell = max(abs(x - cx), max(abs(y - cy), abs(z - cz)))
            order[cursor[shell]++] = linear++
        }
        bootstrapOrder = order
        orderSizeX = sx
        orderSizeY = sy
        orderSizeZ = sz
    }

    private fun sampleVoxel(w: LegacyWorldAccess, x: Int, y: Int, z: Int): AcousticVoxel {
        val sample = w.sample(x, y, z)
        val key = sample.stateId()
        val descriptor: MaterialDescriptor = LegacyBlockDescriptor.normalize(
            sample.registryId(), sample.stateId(), sample.materialName(), sample.soundTypeName(), sample.oreDictionaryNames(), sample.solid()
        )
        var material = resolvedMaterials[key]
        if (material == null) {
            material = resolver.resolve(descriptor)
            if (key.isNotEmpty()) resolvedMaterials[key] = material
        }
        val medium = mediumResolver.resolve(descriptor, sample.medium())
        return AcousticVoxel(sample.solid(), material, sample.shape(), medium, sample.mediumShape())
    }

    @Synchronized
    fun reset() {
        previous = null
        lastSampled = 0
        lastReused = 0
        lastChanged = 0
        lastSweepSampled = 0
        lastBootstrapSampled = 0
        sweepActiveValue = false
        sweepCursor = 0
        bootstrapActiveValue = false
        bootstrapKnown = null
        bootstrapCursor = 0
        bootstrapKnownCount = 0
        bootstrapOrder = null
        lastBootstrapProgress = 1.0
    }

    @Synchronized fun lastSampledVoxels(): Int = lastSampled
    @Synchronized fun lastReusedVoxels(): Int = lastReused
    @Synchronized fun lastChangedVoxels(): Int = lastChanged
    @Synchronized fun lastSweepSampledVoxels(): Int = lastSweepSampled
    @Synchronized fun lastBootstrapSampledVoxels(): Int = lastBootstrapSampled
    @Synchronized fun sweepActive(): Boolean = sweepActiveValue
    @Synchronized fun bootstrapActive(): Boolean = bootstrapActiveValue
    @Synchronized fun bootstrapProgress(): Double = lastBootstrapProgress

    private fun rememberSweepGrid(minX: Int, minY: Int, minZ: Int, sx: Int, sy: Int, sz: Int) {
        sweepMinX = minX; sweepMinY = minY; sweepMinZ = minZ; sweepSizeX = sx; sweepSizeY = sy; sweepSizeZ = sz
    }

    private fun sameSweepGrid(minX: Int, minY: Int, minZ: Int, sx: Int, sy: Int, sz: Int): Boolean =
        sweepMinX == minX && sweepMinY == minY && sweepMinZ == minZ && sweepSizeX == sx && sweepSizeY == sy && sweepSizeZ == sz

    private fun rememberBootstrapGrid(minX: Int, minY: Int, minZ: Int, sx: Int, sy: Int, sz: Int) {
        bootstrapMinX = minX; bootstrapMinY = minY; bootstrapMinZ = minZ; bootstrapSizeX = sx; bootstrapSizeY = sy; bootstrapSizeZ = sz
    }

    private fun sameBootstrapGrid(minX: Int, minY: Int, minZ: Int, sx: Int, sy: Int, sz: Int): Boolean =
        bootstrapMinX == minX && bootstrapMinY == minY && bootstrapMinZ == minZ && bootstrapSizeX == sx && bootstrapSizeY == sy && bootstrapSizeZ == sz

    companion object {
        private val UNKNOWN_AIR = AcousticVoxel(false, AcousticMaterials.AIR)
        private fun equivalent(a: AcousticVoxel, b: AcousticVoxel): Boolean =
            a.solid() == b.solid() && a.material().id() == b.material().id() && a.medium().id() == b.medium().id() &&
                a.shape() == b.shape() && a.mediumShape() == b.mediumShape()
        private fun inside(s: ImmutableVoxelSnapshot, x: Int, y: Int, z: Int): Boolean =
            x >= s.minX() && y >= s.minY() && z >= s.minZ() && x < s.minX() + s.sizeX() && y < s.minY() + s.sizeY() && z < s.minZ() + s.sizeZ()
    }
}
