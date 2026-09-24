package dev.acoustic.mc1122.forge

import dev.acoustic.api.math.Vec3
import dev.acoustic.core.passes.LegacyAcousticEvaluator
import dev.acoustic.mc1122.LegacyPublishedState
import java.lang.reflect.Field
import java.nio.IntBuffer
import java.util.Locale
import kotlin.math.max

/** Entry point called by the Paulscode mixins. OpenAL calls stay on the sound command thread. */
object LegacySoundHook {
    private val evaluator = LegacyAcousticEvaluator()
    private val efx = LegacyEfxBackend()
    private val wet = LegacySoftwareWetBackend(LegacyAudioConfig.defaults())
    @Volatile private var runtime: LegacyClientRuntime? = null
    @Volatile private var audioThread: Thread? = null
    @Volatile private var effectsResetRequested = false
    @Volatile private var audioContextGeneration = 0L

    @JvmStatic @JvmName("bind") internal fun bind(r: LegacyClientRuntime) {
        runtime = r
        audioContextGeneration = 0L
    }

    @JvmStatic fun onSourcePlay(source: Any) {
        val r = runtime ?: return
        try {
            val id = sourceId(source)
            if (id <= 0) return
            syncAudioContext(r)
            /*
             * Paulscode/OpenAL recycles numeric source ids. A missed/reordered stop or
             * cleanup callback must therefore not allow an old wet voice, EFX filters,
             * async generation, diagnostic snapshot or Doppler velocity to bleed into the
             * next logical sound that receives the same AL id. This runs on the OpenAL
             * owner thread at SourceLWJGLOpenAL.play() TAIL, before the new generation is
             * published to the acoustic runtime.
             */
            r.sourceStopped(id)
            wet.clearSource(id)
            efx.clearSource(id)
            if (!r.effectsActive()) return
            val pos = position(source)
            val soundId = soundIdentifier(source)
            val gain = max(0.0, numberFieldOr(source, "gain", 1.0) * numberFieldOr(source, "sourceVolume", 1.0))
            val importance = if (booleanFieldOr(source, "priority", false)) 2.0 else 1.0
            val streaming = booleanFieldOr(source, "toStream", false)
            val looping = booleanFieldOr(source, "toLoop", false)
            val sourceProfile = r.sourceProfileFor(soundId)
            val generation = r.sourceStarted(id, soundId, pos, gain, importance, streaming, looping)
            if (!streaming && !looping && r.legacyAudioConfig().softwareWetEnabled) {
                r.sourcePcmCaptured(id, generation, captureStaticMonoPcm(source, r.legacyAudioConfig().maxPcmBytes))
            }
            if (sourceProfile.bypassAcoustics()) {
                efx.clearSource(id)
                if (r.debug()) AcousticLog.debug("source=$id id=$soundId profile=${sourceProfile.id()} bypass=true")
                return
            }
            val state: LegacyPublishedState = r.published()
            val scene = state.scene() ?: return
            val listener = state.listener() ?: return
            if (!state.ready()) return
            val params = evaluator.evaluate(scene, pos, listener, state.room(), r.pack().liveTuning())
            efx.apply(id, params, state.room(), scene.revision())
            if (r.debug()) AcousticLog.debug("source=$id id=$soundId profile=${sourceProfile.id()} pos=$pos direct=${params.directGain()} hf=${params.directGainHf()} wet=${params.sendGain()} path=fast-fallback")
        } catch (t: Throwable) { r.debugError("sound hook", t) }
    }

    @JvmStatic fun onSourcePositionChanged(source: Any) {
        val r = runtime ?: return
        try { val id = sourceId(source); if (id > 0) r.sourceMoved(id, position(source)) } catch (t: Throwable) { r.debugError("source move", t) }
    }

    @JvmStatic fun onSourceCleanup(source: Any) {
        val r = runtime
        try {
            val id = sourceId(source)
            if (id > 0) {
                if (r != null) syncAudioContext(r)
                r?.sourceStopped(id)
                wet.clearSource(id)
                efx.clearSource(id)
            }
        } catch (_: Throwable) {}
    }

    @JvmStatic fun onAudioCommandTick() {
        audioThread = Thread.currentThread()
        val r = runtime ?: return
        syncAudioContext(r)
        wet.reconfigure(r.legacyAudioConfig())
        if (effectsResetRequested || !r.effectsActive()) {
            effectsResetRequested = false
            wet.clearAll()
            efx.clearAll()
            return
        }
        r.drainFullSourceResults(efx, wet)
    }

    @JvmStatic @JvmName("requestEffectsReset") internal fun requestEffectsReset() {
        effectsResetRequested = true
        wakeAudioThread()
    }

    @JvmStatic @JvmName("wakeAudioThread") internal fun wakeAudioThread() {
        val thread = audioThread
        if (thread != null && thread !== Thread.currentThread()) thread.interrupt()
    }

    /**
     * Numeric AL source ids belong to one ALC context only. Detect context replacement
     * before processing play/cleanup/results so stale logical source generations from the
     * destroyed context can never target same-numbered sources in the replacement.
     */
    private fun syncAudioContext(r: LegacyClientRuntime) {
        val current = wet.currentContextGeneration()
        if (current <= 0L) return
        val previous = audioContextGeneration
        if (previous == 0L) {
            audioContextGeneration = current
            return
        }
        if (current == previous) return
        audioContextGeneration = current
        r.audioContextChanged()
        // Both calls execute on the OpenAL owner thread. Their context guards discard
        // old-context bookkeeping rather than mutating unrelated state in the new context.
        wet.clearAll()
        efx.clearAll()
    }

    private fun captureStaticMonoPcm(source: Any, maxBytes: Int): LegacySoftwareWetRenderer.PcmCapture? {
        return try {
            val buffer = findField(source, "soundBuffer") ?: return null
            val raw = findField(buffer, "audioData") as? ByteArray ?: return null
            if (raw.isEmpty() || raw.size > maxBytes) return null
            val format = findField(buffer, "audioFormat") ?: return null
            val type = format.javaClass
            val channels = (type.getMethod("getChannels").invoke(format) as Number).toInt()
            val bits = (type.getMethod("getSampleSizeInBits").invoke(format) as Number).toInt()
            val rate = (type.getMethod("getSampleRate").invoke(format) as Number).toFloat().toInt()
            val bigEndian = type.getMethod("isBigEndian").invoke(format) as Boolean
            val encoding = type.getMethod("getEncoding").invoke(format).toString()
            if (channels != 1 || bits != 16 || bigEndian || !encoding.contains("PCM_SIGNED", ignoreCase = true) || rate !in 8000..192000) return null
            LegacySoftwareWetRenderer.PcmCapture(raw.clone(), rate)
        } catch (_: Throwable) { null }
    }

    private fun soundIdentifier(source: Any): String {
        try {
            val filename = findField(source, "filenameURL")
            if (filename != null) try {
                val method = filename.javaClass.getMethod("getFilename")
                val value = method.invoke(filename)
                if (value != null && value.toString().trim().isNotEmpty()) return normalizeSoundId(value.toString())
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        try { findField(source, "sourcename")?.let { return normalizeSoundId(it.toString()) } } catch (_: Throwable) {}
        return "unknown"
    }

    private fun normalizeSoundId(value: String?): String {
        var text = (value ?: "unknown").trim().replace('\\', '/').lowercase(Locale.ROOT)
        val q = text.indexOf('?')
        if (q >= 0) text = text.substring(0, q)
        return text
    }
    private fun position(source: Any): Vec3 { val p = requireNotNull(findField(source, "position")); return Vec3(numberField(p, "x"), numberField(p, "y"), numberField(p, "z")) }
    private fun sourceId(source: Any): Int {
        val channel = try { findField(source, "channelOpenAL") } catch (_: NoSuchFieldException) { findField(source, "channel") } ?: return 0
        val raw = findField(channel, "ALSource")
        if (raw !is IntBuffer) return 0
        return if (raw.capacity() > 0) raw.get(0) else 0
    }
    private fun findField(obj: Any, name: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try { val field: Field = clazz.getDeclaredField(name); field.isAccessible = true; return field.get(obj) } catch (_: NoSuchFieldException) {}
            clazz = clazz.superclass
        }
        throw NoSuchFieldException(name)
    }
    private fun numberFieldOr(obj: Any, name: String, fallback: Double): Double = try { numberField(obj, name) } catch (_: Exception) { fallback }
    private fun booleanFieldOr(obj: Any, name: String, fallback: Boolean): Boolean = try { (findField(obj, name) as? Boolean) ?: fallback } catch (_: Exception) { fallback }
    private fun numberField(obj: Any, name: String): Double {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try { val field = clazz.getDeclaredField(name); field.isAccessible = true; return (field.get(obj) as Number).toDouble() } catch (_: NoSuchFieldException) {}
            clazz = clazz.superclass
        }
        throw NoSuchFieldException(name)
    }
}
