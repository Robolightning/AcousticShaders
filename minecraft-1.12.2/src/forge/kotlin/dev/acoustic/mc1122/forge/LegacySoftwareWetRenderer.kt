package dev.acoustic.mc1122.forge

import dev.acoustic.api.math.Vec3
import dev.acoustic.core.dsp.SoftwareWetPcmRenderer
import dev.acoustic.core.dsp.FoaImpulseResponse
import dev.acoustic.core.passes.EarlyReflectionField
import dev.acoustic.core.rir.ImpulseResponse
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/** Bounded/coalescing worker-side software wet renderer. It never calls OpenAL. */
internal class LegacySoftwareWetRenderer(private var config: LegacyAudioConfig) : AutoCloseable {
    data class PcmCapture(val pcmMono16: ByteArray, val sampleRate: Int)
    data class Request(
        val sourceId: Int, val generation: Long, val epoch: Long, val sceneRevision: Long,
        val capture: PcmCapture, val rir: ImpulseResponse, val early: EarlyReflectionField?, val foa: FoaImpulseResponse?, val forward: Vec3
    )
    data class Result(
        val sourceId: Int, val generation: Long, val epoch: Long, val sceneRevision: Long,
        val rendered: SoftwareWetPcmRenderer.Rendered
    )

    private val lock = Any()
    private val pending = LinkedHashMap<Int, Request>()
    private val completed = ConcurrentLinkedQueue<Result>()
    @Volatile private var running = 0
    @Volatile private var closed = false
    @Volatile private var poolGeneration = 1L
    private var workers: ExecutorService = newPool(config.rendererThreads)
    @Volatile var submitted: Long = 0; private set
    @Volatile var rendered: Long = 0; private set
    @Volatile var dropped: Long = 0; private set
    @Volatile var failures: Long = 0; private set

    fun reconfigure(next: LegacyAudioConfig) {
        if (next.rendererThreads == config.rendererThreads) {
            config = next
            if (!next.softwareWetEnabled) clear()
            return
        }
        val old = workers
        config = next
        workers = newPool(next.rendererThreads)
        synchronized(lock) { poolGeneration++; pending.clear(); running = 0 }
        old.shutdownNow()
        if (!next.softwareWetEnabled) clear()
    }

    fun submit(request: Request) {
        if (closed || !config.softwareWetEnabled) return
        var start = false
        synchronized(lock) {
            if (pending.size >= config.maxPendingJobs && !pending.containsKey(request.sourceId)) {
                val first = pending.keys.iterator().next(); pending.remove(first); dropped++
            }
            pending[request.sourceId] = request
            submitted++
            if (running < config.rendererThreads) { running++; start = true }
        }
        if (start) { val generation = poolGeneration; workers.submit { workerLoop(generation) } }
    }

    fun poll(): Result? = completed.poll()
    fun invalidate(sourceId: Int) { synchronized(lock) { pending.remove(sourceId) } }
    fun clear() { synchronized(lock) { pending.clear() }; completed.clear() }

    private fun workerLoop(generation: Long) {
        while (!closed && generation == poolGeneration) {
            val request = synchronized(lock) {
                if (generation != poolGeneration) return
                if (pending.isEmpty()) { running = maxOf(0, running - 1); return }
                val key = pending.keys.iterator().next(); requireNotNull(pending.remove(key))
            }
            try {
                val c = config
                val renderer = SoftwareWetPcmRenderer(c.fftBlockSize, c.maxIrSeconds)
                val out = if (request.foa != null) renderer.renderMono16(request.capture.pcmMono16, request.capture.sampleRate, request.foa, request.forward, c.wetGain)
                else renderer.renderMono16(request.capture.pcmMono16, request.capture.sampleRate, request.rir, request.early, request.forward, c.wetGain)
                if (generation == poolGeneration) {
                    completed.add(Result(request.sourceId, request.generation, request.epoch, request.sceneRevision, out))
                    rendered++
                    LegacySoundHook.wakeAudioThread()
                } else dropped++
            } catch (t: Throwable) {
                failures++
                AcousticLog.debug("software wet render failed source=${request.sourceId}: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun close() { closed = true; synchronized(lock) { pending.clear() }; completed.clear(); workers.shutdownNow() }

    companion object {
        private fun newPool(count: Int): ExecutorService = Executors.newFixedThreadPool(count, object : ThreadFactory {
            private var n = 0
            @Synchronized override fun newThread(r: Runnable): Thread = Thread(r, "acoustic-wet-render-${n++}").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        })
    }
}
