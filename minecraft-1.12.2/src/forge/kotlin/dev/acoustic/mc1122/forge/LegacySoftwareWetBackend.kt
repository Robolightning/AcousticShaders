package dev.acoustic.mc1122.forge

import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap

/** OpenAL owner-thread backend for completed software-wet PCM. No rendering happens here. */
internal class LegacySoftwareWetBackend(private var config: LegacyAudioConfig) {
    data class ApplyResult(
        val applied: Boolean,
        val evictedDrySource: Int = 0,
        val paused: Boolean = false,
        val staleContext: Boolean = false
    )
    private data class Voice(val generation: Long, val wetSource: Int, val buffer: Int)

    private val voices = LinkedHashMap<Int, Voice>(16, 0.75f, true)
    private var context: Any? = null
    private var contextGeneration = 0L
    private var al10: Class<*>? = null
    private var al11: Class<*>? = null
    private var alc10: Class<*>? = null
    @Volatile var applied: Long = 0; private set
    @Volatile var fallback: Long = 0; private set
    @Volatile var stale: Long = 0

    fun reconfigure(next: LegacyAudioConfig) { config = next }

    @Synchronized
    fun isActive(drySource: Int, generation: Long): Boolean {
        try { if (!ensureContext()) return false } catch (_: Throwable) { return false }
        val voice = voices[drySource] ?: return false
        return voice.generation == generation
    }

    @Synchronized
    fun apply(drySource: Int, generation: Long, rendered: dev.acoustic.core.dsp.SoftwareWetPcmRenderer.Rendered): ApplyResult =
        apply(drySource, generation, currentContextGeneration(), rendered)

    @Synchronized
    fun apply(
        drySource: Int,
        generation: Long,
        expectedContextGeneration: Long,
        rendered: dev.acoustic.core.dsp.SoftwareWetPcmRenderer.Rendered
    ): ApplyResult {
        if (!config.softwareWetEnabled || drySource <= 0) return ApplyResult(false)
        try {
            if (!ensureContext()) return ApplyResult(false)
            if (expectedContextGeneration <= 0L || expectedContextGeneration != contextGeneration) {
                stale++
                return ApplyResult(false, staleContext = true)
            }
            val al = requireNotNull(al10)
            val state = (call(al, "alGetSourcei", drySource, constant(al, "AL_SOURCE_STATE")) as? Number)?.toInt()
            val pausedConst = constant(al, "AL_PAUSED")
            if (state == pausedConst) return ApplyResult(false, paused = true)
            val playingConst = constant(al, "AL_PLAYING")
            if (state != null && state != 0 && state != playingConst) return ApplyResult(false)

            removeVoice(drySource)
            val buffer = (call(al, "alGenBuffers") as Number).toInt()
            val wetSource = (call(al, "alGenSources") as Number).toInt()
            try {
                val bytes = rendered.pcmStereo16
                val data = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()); data.put(bytes); data.flip()
                call(al, "alBufferData", buffer, constant(al, "AL_FORMAT_STEREO16"), data, rendered.sampleRate)
                call(al, "alSourcei", wetSource, constant(al, "AL_BUFFER"), buffer)
                // Renderer already applied configured wet gain; keep OpenAL gain neutral.
                call(al, "alSourcef", wetSource, constant(al, "AL_GAIN"), 1.0f)
                // AL_SEC_OFFSET is an OpenAL 1.1 token and LWJGL2 exposes the constant on AL11,
                // while the scalar alGetSourcef/alSourcef entry points remain on AL10. Keeping
                // the owner split exact prevents the runtime fallback that a permissive test stub
                // previously hid on the real Minecraft 1.12.2 LWJGL 2.9.4 client.
                val secOffset = constant(requireNotNull(al11), "AL_SEC_OFFSET")
                val offset = (call(al, "alGetSourcef", drySource, secOffset) as? Number)?.toFloat() ?: 0f
                if (offset > 0f) call(al, "alSourcef", wetSource, secOffset, offset)
                call(al, "alSourcePlay", wetSource)
                voices[drySource] = Voice(generation, wetSource, buffer)
                applied++
                val evicted = trim()
                return ApplyResult(true, evicted)
            } catch (t: Throwable) {
                safeDeleteSource(wetSource); safeDeleteBuffer(buffer); throw t
            }
        } catch (t: Throwable) {
            fallback++
            AcousticLog.debug("software wet OpenAL apply failed source=$drySource: ${t.message ?: t.javaClass.simpleName}")
            return ApplyResult(false)
        }
    }

    @Synchronized
    fun currentContextGeneration(): Long = try { if (ensureContext()) contextGeneration else 0L } catch (_: Throwable) { 0L }

    @Synchronized
    fun clearSource(drySource: Int) { try { if (ensureContext()) removeVoice(drySource) } catch (_: Throwable) {} }

    @Synchronized
    fun clearAll(): List<Int> {
        val dry = voices.keys.toList()
        try { if (ensureContext()) for (id in dry) removeVoice(id) } catch (_: Throwable) { voices.clear() }
        return dry
    }

    private fun ensureContext(): Boolean {
        if (alc10 == null) {
            al10 = Class.forName("org.lwjgl.openal.AL10")
            al11 = Class.forName("org.lwjgl.openal.AL11")
            alc10 = Class.forName("org.lwjgl.openal.ALC10")
        }
        val now = call(requireNotNull(alc10), "alcGetCurrentContext") ?: return false
        if (now != context) {
            context = now
            voices.clear()
            contextGeneration++
        }
        return true
    }

    private fun trim(): Int {
        var evicted = 0
        while (voices.size > config.maxWetVoices) {
            val first = voices.keys.iterator().next(); removeVoice(first); if (evicted == 0) evicted = first
        }
        return evicted
    }

    private fun removeVoice(drySource: Int) {
        val v = voices.remove(drySource) ?: return
        try { call(requireNotNull(al10), "alSourceStop", v.wetSource) } catch (_: Throwable) {}
        safeDeleteSource(v.wetSource); safeDeleteBuffer(v.buffer)
    }
    private fun safeDeleteSource(id: Int) { try { call(requireNotNull(al10), "alDeleteSources", id) } catch (_: Throwable) {} }
    private fun safeDeleteBuffer(id: Int) { try { call(requireNotNull(al10), "alDeleteBuffers", id) } catch (_: Throwable) {} }

    companion object {
        private fun constant(clazz: Class<*>, name: String): Int = clazz.getField(name).getInt(null)
        private fun call(clazz: Class<*>, name: String, vararg args: Any?): Any? {
            val selected: Method = clazz.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size && matches(it.parameterTypes, args) }
                ?: throw NoSuchMethodException("${clazz.name}.$name/${args.size}")
            return selected.invoke(null, *args)
        }
        private fun matches(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
            for (i in types.indices) {
                val a = args[i] ?: if (types[i].isPrimitive) return false else continue
                if (!wrap(types[i]).isAssignableFrom(a.javaClass)) return false
            }
            return true
        }
        private fun wrap(c: Class<*>): Class<*> = when (c) {
            Integer.TYPE -> Int::class.javaObjectType
            java.lang.Float.TYPE -> Float::class.javaObjectType
            java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
            java.lang.Long.TYPE -> Long::class.javaObjectType
            java.lang.Double.TYPE -> Double::class.javaObjectType
            else -> c
        }
    }
}
