package dev.acoustic.core.runtime

import dev.acoustic.core.pack.LoadedShaderPack
import dev.acoustic.core.pack.ShaderPackValidator
import dev.acoustic.platform.PlatformFrameAdapter
import dev.acoustic.platform.PlatformFrameSnapshot
import dev.acoustic.platform.PlatformFrameValidator
import dev.acoustic.platform.SceneCaptureRequest
import dev.acoustic.platform.SoundSourceSnapshot
import java.util.ArrayList
import java.util.Collections

/**
 * Version-neutral bridge from a concrete game adapter to the portable acoustic runtime.
 *
 * It consumes exactly one PlatformFrameSnapshot per call, so all sources in that call see the same
 * immutable scene/listener state. Sequence ordering is enforced inside one world epoch; an epoch
 * change intentionally permits the sequence to restart for a newly loaded world.
 */
class PlatformAdapterRuntime(
    private val adapter: PlatformFrameAdapter,
    pack: LoadedShaderPack,
    profile: String,
    workers: Int,
    private val captureRequest: SceneCaptureRequest
) : AutoCloseable {
    private val platformId = PlatformFrameValidator.requirePlatformId(adapter.platformId())
    private val session: AcousticRuntimeSession
    private var hasPreviousFrame = false
    private var previousWorldEpoch = 0L
    private var previousFrameSequence = 0L

    init {
        ShaderPackValidator().requireValid(pack, adapter.capabilities())
        session = AcousticRuntimeSession(pack, profile, workers)
    }

    @Throws(Exception::class)
    fun captureAndProcess(): PlatformFrameResult {
        val currentPlatformId = PlatformFrameValidator.requirePlatformId(adapter.platformId())
        require(currentPlatformId == platformId) {
            "platform id changed during runtime: $platformId -> $currentPlatformId"
        }

        val frame = adapter.captureFrame(captureRequest)
        PlatformFrameValidator.requireValid(frame)
        requireOrdered(frame)

        val results = ArrayList<SourceFrameResult>(frame.sources().size)
        for (source in frame.sources()) {
            results.add(
                SourceFrameResult(
                    source,
                    session.process(
                        frame.scene(),
                        source.position(),
                        frame.listener().position(),
                        source.soundId(),
                        source.profile(),
                        source.gain(),
                        frame.listener().forward(),
                        frame.listener().up(),
                        source.velocity(),
                        frame.listener().velocity()
                    )
                )
            )
        }
        return PlatformFrameResult(frame, results)
    }

    private fun requireOrdered(frame: PlatformFrameSnapshot) {
        if (hasPreviousFrame && frame.worldEpoch() == previousWorldEpoch) {
            require(frame.frameSequence() >= previousFrameSequence) {
                "platform frame sequence regressed inside world epoch ${frame.worldEpoch()}: " +
                    "$previousFrameSequence -> ${frame.frameSequence()}"
            }
        }
        hasPreviousFrame = true
        previousWorldEpoch = frame.worldEpoch()
        previousFrameSequence = frame.frameSequence()
    }

    override fun close() {
        session.close()
    }

    class PlatformFrameResult internal constructor(
        private val frame: PlatformFrameSnapshot,
        sourceResults: List<SourceFrameResult>
    ) {
        private val sourceResults = Collections.unmodifiableList(ArrayList(sourceResults))
        fun frame(): PlatformFrameSnapshot = frame
        fun sourceResults(): List<SourceFrameResult> = sourceResults
    }

    class SourceFrameResult internal constructor(
        private val source: SoundSourceSnapshot,
        private val result: AcousticRuntimeSession.FrameResult
    ) {
        fun source(): SoundSourceSnapshot = source
        fun result(): AcousticRuntimeSession.FrameResult = result
    }
}
