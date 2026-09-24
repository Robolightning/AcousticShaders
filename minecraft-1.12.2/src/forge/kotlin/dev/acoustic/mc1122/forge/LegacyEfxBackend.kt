package dev.acoustic.mc1122.forge

import dev.acoustic.api.math.Vec3
import dev.acoustic.core.passes.LegacyEffectParameters
import dev.acoustic.core.passes.LegacyRoomEstimate
import java.lang.reflect.Method
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** OpenAL EFX backend invoked only from Paulscode's audio/command thread. Uses reflection to avoid compile-time LWJGL coupling. */
internal class LegacyEfxBackend {
    private val filters = LinkedHashMap<Int, Filters>(64, 0.75f, true)
    private val velocitySources = LinkedHashSet<Int>()
    private var context: Any? = null
    private var effect = 0
    private var slot = 0
    private var roomEpoch = Long.MIN_VALUE
    private var available = false
    private var efxInitialized = false
    private var warned = false
    private var lastRoomSignature: RoomSignature? = null
    private var al10: Class<*>? = null
    private var al11: Class<*>? = null
    private var alc10: Class<*>? = null
    private var efx10: Class<*>? = null

    @Synchronized
    fun apply(sourceId: Int, p: LegacyEffectParameters, room: LegacyRoomEstimate, epoch: Long) {
        if (sourceId <= 0) return
        try {
            if (!ensureEfxContext()) return
            if (roomEpoch != epoch) {
                val signature = RoomSignature.of(room)
                val previous = lastRoomSignature
                if (previous == null || !previous.near(signature)) {
                    configureRoom(room)
                    lastRoomSignature = signature
                }
                roomEpoch = epoch
            }
            removeFilters(sourceId, false)
            val direct = gen("alGenFilters")
            val send = gen("alGenFilters")
            val efx = requireNotNull(efx10)
            call(efx, "alFilteri", direct, constant(efx, "AL_FILTER_TYPE"), constant(efx, "AL_FILTER_LOWPASS"))
            call(efx, "alFilterf", direct, constant(efx, "AL_LOWPASS_GAIN"), p.directGain())
            call(efx, "alFilterf", direct, constant(efx, "AL_LOWPASS_GAINHF"), p.directGainHf())
            call(efx, "alFilteri", send, constant(efx, "AL_FILTER_TYPE"), constant(efx, "AL_FILTER_LOWPASS"))
            call(efx, "alFilterf", send, constant(efx, "AL_LOWPASS_GAIN"), p.sendGain())
            call(efx, "alFilterf", send, constant(efx, "AL_LOWPASS_GAINHF"), p.sendGainHf())
            call(requireNotNull(al10), "alSourcei", sourceId, constant(efx, "AL_DIRECT_FILTER"), direct)
            call(requireNotNull(al11), "alSource3i", sourceId, constant(efx, "AL_AUXILIARY_SEND_FILTER"), slot, 0, send)
            val err = (call(requireNotNull(al10), "alGetError") as Number).toInt()
            if (err != 0) {
                safeDeleteFilter(direct)
                safeDeleteFilter(send)
                throw IllegalStateException("OpenAL error 0x${Integer.toHexString(err)}")
            }
            filters[sourceId] = Filters(direct, send)
            trim()
        } catch (t: Throwable) {
            disable(t)
        }
    }

    /** Apply direct-path filtering while explicitly detaching the shared reverb send. Used with software wet convolution. */
    @Synchronized
    fun applyDirectOnly(sourceId: Int, p: LegacyEffectParameters) {
        if (sourceId <= 0) return
        try {
            if (!ensureEfxContext()) return
            removeFilters(sourceId, true)
            val direct = gen("alGenFilters")
            val efx = requireNotNull(efx10)
            call(efx, "alFilteri", direct, constant(efx, "AL_FILTER_TYPE"), constant(efx, "AL_FILTER_LOWPASS"))
            call(efx, "alFilterf", direct, constant(efx, "AL_LOWPASS_GAIN"), p.directGain())
            call(efx, "alFilterf", direct, constant(efx, "AL_LOWPASS_GAINHF"), p.directGainHf())
            call(requireNotNull(al10), "alSourcei", sourceId, constant(efx, "AL_DIRECT_FILTER"), direct)
            call(requireNotNull(al11), "alSource3i", sourceId, constant(efx, "AL_AUXILIARY_SEND_FILTER"), 0, 0, constant(efx, "AL_FILTER_NULL"))
            filters[sourceId] = Filters(direct, 0)
            trim()
        } catch (t: Throwable) { disable(t) }
    }

    @Synchronized
    fun clearSource(sourceId: Int) {
        if (sourceId <= 0) return
        try {
            if (!ensureAlContext()) return
            if (filters.containsKey(sourceId)) removeFilters(sourceId, true)
            resetVelocity(sourceId)
        } catch (t: Throwable) {
            disable(t)
        }
    }


    /** Detach all AcousticShaders-owned EFX state and restore velocity on the OpenAL owner thread. */
    @Synchronized
    fun clearAll() {
        try {
            if (!ensureAlContext()) { filters.clear(); velocitySources.clear(); return }
            val ids = LinkedHashSet<Int>()
            ids.addAll(filters.keys)
            ids.addAll(velocitySources)
            for (sourceId in ids) {
                if (filters.containsKey(sourceId)) removeFilters(sourceId, true)
                resetVelocity(sourceId)
            }
        } catch (t: Throwable) {
            disable(t)
        }
    }

    private fun resetVelocity(sourceId: Int) {
        if (!velocitySources.remove(sourceId)) return
        try {
            val al = requireNotNull(al10)
            call(al, "alSource3f", sourceId, constant(al, "AL_VELOCITY"), 0f, 0f, 0f)
        } catch (_: Throwable) {}
    }

    /** Optional projectile/fly-by Doppler. Called only on the OpenAL-owning command thread.
     *  AL_VELOCITY is core OpenAL state and must not depend on ALC_EXT_EFX availability. */
    @Synchronized
    fun applyVelocity(sourceId: Int, velocity: Vec3?, dopplerScale: Float) {
        if (sourceId <= 0 || velocity == null) return
        try {
            if (!ensureAlContext()) return
            val scale = max(0f, min(4f, dopplerScale))
            val al = requireNotNull(al10)
            call(
                al,
                "alSource3f",
                sourceId,
                constant(al, "AL_VELOCITY"),
                (velocity.x * scale).toFloat(),
                (velocity.y * scale).toFloat(),
                (velocity.z * scale).toFloat()
            )
            if (scale > 0f) velocitySources.add(sourceId) else velocitySources.remove(sourceId)
        } catch (t: Throwable) {
            disable(t)
        }
    }

    @Throws(Exception::class)
    private fun ensureAlContext(): Boolean {
        if (alc10 == null) {
            al10 = Class.forName("org.lwjgl.openal.AL10")
            alc10 = Class.forName("org.lwjgl.openal.ALC10")
        }
        val now = call(requireNotNull(alc10), "alcGetCurrentContext") ?: return false
        if (now != context) {
            context = now
            filters.clear()
            velocitySources.clear()
            effect = 0
            slot = 0
            roomEpoch = Long.MIN_VALUE
            lastRoomSignature = null
            available = false
            efxInitialized = false
        }
        return true
    }

    @Throws(Exception::class)
    private fun ensureEfxContext(): Boolean {
        if (!ensureAlContext()) return false
        if (efx10 == null) {
            al11 = Class.forName("org.lwjgl.openal.AL11")
            efx10 = Class.forName("org.lwjgl.openal.EFX10")
        }
        if (!efxInitialized) {
            efxInitialized = true
            initialize()
        }
        return available
    }

    @Throws(Exception::class)
    private fun initialize() {
        val alc = requireNotNull(alc10)
        val efx = requireNotNull(efx10)
        val device = call(alc, "alcGetContextsDevice", context)
        val present = call(alc, "alcIsExtensionPresent", device, "ALC_EXT_EFX")
        if (present != true) {
            available = false
            return
        }
        effect = gen("alGenEffects")
        slot = gen("alGenAuxiliaryEffectSlots")
        call(efx, "alEffecti", effect, constant(efx, "AL_EFFECT_TYPE"), constant(efx, "AL_EFFECT_REVERB"))
        call(efx, "alAuxiliaryEffectSloti", slot, constant(efx, "AL_EFFECTSLOT_EFFECT"), effect)
        available = true
    }

    @Throws(Exception::class)
    private fun configureRoom(room: LegacyRoomEstimate) {
        val enclosure = max(0f, min(1f, 1f - room.openness()))
        val roomGain = 0.055f + 0.265f * enclosure.toDouble().pow(1.35).toFloat()
        effectf("AL_REVERB_DENSITY", room.density())
        effectf("AL_REVERB_DIFFUSION", room.diffusion())
        effectf("AL_REVERB_GAIN", roomGain)
        effectf("AL_REVERB_GAINHF", room.gainHf())
        effectf("AL_REVERB_DECAY_TIME", room.decayTimeSeconds())
        effectf("AL_REVERB_DECAY_HFRATIO", max(0.1f, min(2f, 0.55f + 0.65f * room.gainHf())))
        /*
         * The legacy EFX effect exposes one coarse early-reflection cluster. Mapping the
         * geometric mean-free-path literally to that single delay produced a distinct
         * second onset ("double sound") in caves. Keep the EFX fallback diffuse and
         * short; physically timed early reflections remain represented by the full RIR
         * path rather than pretending that one EFX tap is the whole reflection field.
         */
        val reflectionGain = min(0.10f, 0.003f + 0.065f * room.density() * enclosure)
        val reflectionDelay = min(0.012f, max(0.001f, (room.meanFreePathMeters() * 0.35 / 343.0).toFloat()))
        val lateGain = min(4f, 0.08f + 1.25f * room.density() * enclosure)
        val lateDelay = min(0.018f, max(0.003f, (room.meanFreePathMeters() * 0.55 / 343.0).toFloat()))
        effectf("AL_REVERB_REFLECTIONS_GAIN", reflectionGain)
        effectf("AL_REVERB_REFLECTIONS_DELAY", reflectionDelay)
        effectf("AL_REVERB_LATE_REVERB_GAIN", lateGain)
        effectf("AL_REVERB_LATE_REVERB_DELAY", lateDelay)
        val efx = requireNotNull(efx10)
        call(efx, "alAuxiliaryEffectSloti", slot, constant(efx, "AL_EFFECTSLOT_EFFECT"), effect)
    }

    @Throws(Exception::class)
    private fun effectf(name: String, value: Float) {
        val efx = requireNotNull(efx10)
        call(efx, "alEffectf", effect, constant(efx, name), value)
    }

    @Throws(Exception::class)
    private fun gen(method: String): Int = (call(requireNotNull(efx10), method) as Number).toInt()

    @Throws(Exception::class)
    private fun removeFilters(sourceId: Int, detach: Boolean) {
        val entry = filters.remove(sourceId) ?: return
        val efx = requireNotNull(efx10)
        if (detach) {
            try {
                call(requireNotNull(al10), "alSourcei", sourceId, constant(efx, "AL_DIRECT_FILTER"), constant(efx, "AL_FILTER_NULL"))
            } catch (_: Throwable) {}
            try {
                call(requireNotNull(al11), "alSource3i", sourceId, constant(efx, "AL_AUXILIARY_SEND_FILTER"), 0, 0, constant(efx, "AL_FILTER_NULL"))
            } catch (_: Throwable) {}
        }
        safeDeleteFilter(entry.direct)
        if (entry.send > 0) safeDeleteFilter(entry.send)
    }

    @Throws(Exception::class)
    private fun trim() {
        while (filters.size > 96) {
            val first = filters.keys.iterator().next()
            removeFilters(first, false)
        }
    }

    private fun safeDeleteFilter(id: Int) {
        try { call(requireNotNull(efx10), "alDeleteFilters", id) } catch (_: Throwable) {}
    }

    private fun disable(t: Throwable) {
        available = false
        if (!warned) {
            warned = true
            AcousticLog.warn("OpenAL EFX backend disabled after error: $t")
        }
    }

    /** Quantized acoustic signature used to avoid resetting the shared EFX reverb state for tiny scene revisions. */
    private data class RoomSignature(
        val openness: Float,
        val decay: Float,
        val hf: Float,
        val diffusion: Float,
        val density: Float,
        val mean: Float
    ) {
        fun near(other: RoomSignature): Boolean =
            kotlin.math.abs(openness - other.openness) < 0.035f &&
                kotlin.math.abs(decay - other.decay) < 0.075f &&
                kotlin.math.abs(hf - other.hf) < 0.035f &&
                kotlin.math.abs(diffusion - other.diffusion) < 0.04f &&
                kotlin.math.abs(density - other.density) < 0.04f &&
                kotlin.math.abs(mean - other.mean) < 0.65f

        companion object {
            fun of(room: LegacyRoomEstimate): RoomSignature = RoomSignature(
                room.openness(),
                room.decayTimeSeconds(),
                room.gainHf(),
                room.diffusion(),
                room.density(),
                room.meanFreePathMeters().toFloat()
            )
        }
    }

    private data class Filters(val direct: Int, val send: Int)

    private companion object {
        @Throws(Exception::class)
        fun constant(clazz: Class<*>, name: String): Int = clazz.getField(name).getInt(null)

        @Throws(Exception::class)
        fun call(clazz: Class<*>, name: String, vararg args: Any?): Any? {
            var candidate: Method? = null
            for (method in clazz.methods) {
                if (method.name == name && method.parameterTypes.size == args.size && matches(method.parameterTypes, args)) {
                    candidate = method
                    break
                }
            }
            val selected = candidate ?: throw NoSuchMethodException("${clazz.name}.$name/${args.size}")
            return selected.invoke(null, *args)
        }

        fun matches(parameters: Array<Class<*>>, args: Array<out Any?>): Boolean {
            var i = 0
            while (i < parameters.size) {
                val arg = args[i]
                if (arg == null) {
                    if (parameters[i].isPrimitive) return false
                    i++
                    continue
                }
                if (!wrap(parameters[i]).isAssignableFrom(arg.javaClass)) return false
                i++
            }
            return true
        }

        fun wrap(clazz: Class<*>): Class<*> {
            if (!clazz.isPrimitive) return clazz
            return when (clazz) {
                Integer.TYPE -> Int::class.javaObjectType
                java.lang.Float.TYPE -> Float::class.javaObjectType
                java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
                java.lang.Long.TYPE -> Long::class.javaObjectType
                java.lang.Double.TYPE -> Double::class.javaObjectType
                else -> clazz
            }
        }
    }
}
