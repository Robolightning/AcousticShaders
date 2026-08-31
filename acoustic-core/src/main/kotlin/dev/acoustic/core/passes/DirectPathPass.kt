package dev.acoustic.core.passes

import dev.acoustic.api.environment.AcousticEnvironment
import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.core.trace.ShapeRaycast
import dev.acoustic.core.trace.LayeredMediumRefraction
import dev.acoustic.core.trace.VoxelDda
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashSet

class DirectPathPass(private val occlusionEnabled: Boolean = true) : Pass {
    override fun id(): String = "standard.direct_path"
    override fun reads(): Set<ResourceKey<*>> = Collections.unmodifiableSet(LinkedHashSet(Arrays.asList(StandardResources.SCENE, StandardResources.SOURCE_POSITION, StandardResources.LISTENER_POSITION)))
    override fun optionalReads(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.ENVIRONMENT)
    override fun writes(): Set<ResourceKey<*>> = Collections.singleton(StandardResources.DIRECT_PATH)

    override fun execute(context: PassContext) {
        val scene = context.require(StandardResources.SCENE)
        val source = context.require(StandardResources.SOURCE_POSITION)
        val listener = context.require(StandardResources.LISTENER_POSITION)
        val environment = context.get(StandardResources.ENVIRONMENT) ?: AcousticEnvironment.STANDARD
        var mediumPath = LayeredMediumRefraction.bestEffort(scene, source, listener, environment)
        val solidTransmission = FloatArray(FrequencyBands.COUNT) { 1f }
        var solidCells = 0
        var occupiedMeters = 0.0

        if (occlusionEnabled && source.distance(listener) > 1.0e-9) {
            val rayDir = listener.subtract(source).normalize()
            VoxelDda.traceCells(scene, source, listener, VoxelDda.CellVisitor { x, y, z, tEnter, tExit, voxel ->
                if (voxel.solid() && !voxel.shape().isEmpty()) {
                    val occupied = ShapeRaycast.occupiedLength(voxel.shape(), x, y, z, source, rayDir, tEnter, tExit)
                    if (occupied > 1.0e-7) {
                        solidCells++
                        occupiedMeters += occupied
                        val materialTransmission = voxel.material().transmission()
                        var band = 0
                        while (band < solidTransmission.size) {
                            val perMeter = Math.max(1.0e-6, Math.min(1.0, (materialTransmission * (1f - 0.25f * voxel.material().absorption(band))).toDouble()))
                            solidTransmission[band] *= Math.pow(perMeter, occupied).toFloat()
                            band++
                        }
                    }
                }
                true
            })
        }

        val transmission = mediumPath.transmission.clone()
        var band = 0
        while (band < transmission.size) { transmission[band] *= solidTransmission[band]; band++ }

        context.put(
            StandardResources.DIRECT_PATH,
            DirectPathResult(
                mediumPath.distanceMeters,
                mediumPath.delaySeconds,
                transmission,
                solidCells,
                occupiedMeters,
                mediumPath.airMeters,
                mediumPath.liquidMeters,
                mediumPath.interfaceCount,
                mediumPath.sourceMedium.id(),
                mediumPath.listenerMedium.id()
            )
        )
    }

    companion object { const val SPEED_OF_SOUND_MPS = 343.0 }
}
