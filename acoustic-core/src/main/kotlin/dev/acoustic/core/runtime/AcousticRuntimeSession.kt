package dev.acoustic.core.runtime

import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.api.source.AcousticSourceProfile
import dev.acoustic.core.pack.LoadedShaderPack
import dev.acoustic.core.passes.HybridResponse
import dev.acoustic.core.passes.StandardResources
import dev.acoustic.core.pipeline.DefaultPipeline
import dev.acoustic.core.pipeline.ExecutionReport
import dev.acoustic.core.pipeline.MapPassContext
import dev.acoustic.core.pipeline.ParallelPipelineExecutor
import dev.acoustic.core.rir.ImpulseResponse

/** Headless/runtime facade used by platform adapters. */
class AcousticRuntimeSession(pack: LoadedShaderPack, profile: String, workers: Int) : AutoCloseable {
    private val executor = ParallelPipelineExecutor(workers)
    private val pipeline: DefaultPipeline = StandardPipelineCompiler().compile(pack, profile)

    @Throws(Exception::class)
    fun process(scene: AcousticScene, source: Vec3, listener: Vec3): FrameResult =
        process(scene, source, listener, "acoustic:runtime", AcousticSourceProfile.GENERIC)

    @Throws(Exception::class)
    fun process(
        scene: AcousticScene,
        source: Vec3,
        listener: Vec3,
        sourceId: String,
        sourceProfile: AcousticSourceProfile
    ): FrameResult = process(
        scene,
        source,
        listener,
        sourceId,
        sourceProfile,
        1f,
        Vec3(0.0, 0.0, 1.0),
        Vec3(0.0, 1.0, 0.0)
    )

    @Throws(Exception::class)
    fun process(
        scene: AcousticScene,
        source: Vec3,
        listener: Vec3,
        sourceId: String,
        sourceProfile: AcousticSourceProfile,
        sourceGain: Float,
        listenerForward: Vec3,
        listenerUp: Vec3
    ): FrameResult = process(
        scene,
        source,
        listener,
        sourceId,
        sourceProfile,
        sourceGain,
        listenerForward,
        listenerUp,
        Vec3(0.0, 0.0, 0.0),
        Vec3(0.0, 0.0, 0.0)
    )

    @Throws(Exception::class)
    fun process(
        scene: AcousticScene,
        source: Vec3,
        listener: Vec3,
        sourceId: String,
        sourceProfile: AcousticSourceProfile,
        sourceGain: Float,
        listenerForward: Vec3,
        listenerUp: Vec3,
        sourceVelocity: Vec3,
        listenerVelocity: Vec3
    ): FrameResult {
        require(sourceId.isNotBlank()) { "source id must be non-blank" }
        require(sourceGain.isFinite() && sourceGain >= 0f) { "source gain must be finite and non-negative" }
        val forward = requireFiniteDirection(listenerForward, "listener forward")
        val up = requireFiniteDirection(listenerUp, "listener up")
        val sourceVelocityValue = requireFiniteVector(sourceVelocity, "source velocity")
        val listenerVelocityValue = requireFiniteVector(listenerVelocity, "listener velocity")
        require(kotlin.math.abs(forward.dot(up)) <= 0.999) { "listener forward/up must not be collinear" }

        val context = MapPassContext()
        context.put(StandardResources.SCENE, scene)
        context.put(StandardResources.SOURCE_POSITION, source)
        context.put(StandardResources.LISTENER_POSITION, listener)
        context.put(StandardResources.SOURCE_ID, sourceId)
        context.put(StandardResources.SOURCE_PROFILE, sourceProfile)
        context.put(StandardResources.SOURCE_GAIN, sourceGain)
        context.put(StandardResources.SOURCE_VELOCITY, sourceVelocityValue)
        context.put(StandardResources.LISTENER_FORWARD, forward)
        context.put(StandardResources.LISTENER_UP, up)
        context.put(StandardResources.LISTENER_VELOCITY, listenerVelocityValue)
        val report = executor.executeProfiled(pipeline, context)
        val behavior = context.get(StandardResources.SOURCE_BEHAVIOR) ?: sourceProfile
        return FrameResult(
            context.require(StandardResources.HYBRID_RESPONSE),
            context.require(StandardResources.IMPULSE_RESPONSE),
            behavior,
            context.require(StandardResources.SOURCE_GAIN),
            context.require(StandardResources.LISTENER_FORWARD),
            context.require(StandardResources.LISTENER_UP),
            context.require(StandardResources.SOURCE_VELOCITY),
            context.require(StandardResources.LISTENER_VELOCITY),
            report
        )
    }

    private fun requireFiniteVector(value: Vec3, label: String): Vec3 {
        require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) {
            "$label must contain only finite coordinates"
        }
        return value
    }

    private fun requireFiniteDirection(value: Vec3, label: String): Vec3 {
        require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) {
            "$label must contain only finite coordinates"
        }
        return value.normalize()
    }

    override fun close() { executor.close() }

    class FrameResult(
        private val response: HybridResponse,
        private val impulseResponse: ImpulseResponse,
        private val sourceBehavior: AcousticSourceProfile,
        private val sourceGain: Float,
        private val listenerForward: Vec3,
        private val listenerUp: Vec3,
        private val sourceVelocity: Vec3,
        private val listenerVelocity: Vec3,
        private val report: ExecutionReport
    ) {
        fun response(): HybridResponse = response
        fun impulseResponse(): ImpulseResponse = impulseResponse
        fun sourceBehavior(): AcousticSourceProfile = sourceBehavior
        fun sourceGain(): Float = sourceGain
        fun listenerForward(): Vec3 = listenerForward
        fun listenerUp(): Vec3 = listenerUp
        fun sourceVelocity(): Vec3 = sourceVelocity
        fun listenerVelocity(): Vec3 = listenerVelocity
        fun report(): ExecutionReport = report
    }
}
