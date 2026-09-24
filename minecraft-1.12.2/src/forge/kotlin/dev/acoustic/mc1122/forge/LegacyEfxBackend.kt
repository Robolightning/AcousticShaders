package dev.acoustic.mc1122.forge

import dev.acoustic.api.math.Vec3
import dev.acoustic.core.passes.LegacyEffectParameters
import dev.acoustic.core.passes.LegacyRoomEstimate
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** OpenAL EFX backend invoked only from Paulscode's audio/command thread. Uses reflection to avoid compile-time LWJGL coupling. */
internal class LegacyEfxBackend {
    private val filters = LinkedHashMap<Int, Filters>(64, 0.75f, true)
    /*
     * OpenAL source ids are recycled by Paulscode. Tracking only the integer id is not
     * enough to decide whether a later cleanup still owns AL_VELOCITY: another mod may
     * have written a new velocity after AcousticShaders' last update. Keep the exact
     * vector we wrote and clear it only while the live AL value still matches it.
     */
    private val velocitySources = LinkedHashMap<Int, OwnedVelocity>()
    /*
     * A source can be recycled after another mod has already replaced its EFX state.
     * In that case detaching/deleting our old filters could corrupt the new owner. Keep
     * those native filter ids as context-owned orphans instead. Their count is strictly
     * bounded; once the budget is exhausted we fail closed for EFX until the OpenAL
     * context changes (context destruction releases the native objects). Core Doppler is
     * independent and remains available through ensureAlContext().
     */
    private val orphanFilters = LinkedHashSet<Int>()
    private var efxResourceSuspended = false
    private var orphanPressureWarned = false
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
            if (!prepareForEfxWrite(sourceId)) return
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
            if (!prepareForEfxWrite(sourceId)) return
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
            if (filters.containsKey(sourceId)) removeFilters(sourceId)
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
            ids.addAll(velocitySources.keys)
            for (sourceId in ids) {
                if (filters.containsKey(sourceId)) removeFilters(sourceId)
                resetVelocity(sourceId)
            }
        } catch (t: Throwable) {
            disable(t)
        }
    }

    private fun resetVelocity(sourceId: Int) {
        val owned = velocitySources.remove(sourceId) ?: return
        try {
            val al = requireNotNull(al10)
            val current = currentVelocity(sourceId, al) ?: return
            if (!owned.matches(current[0], current[1], current[2])) return
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
            val x = (velocity.x * scale).toFloat()
            val y = (velocity.y * scale).toFloat()
            val z = (velocity.z * scale).toFloat()
            if (scale <= 0f || (kotlin.math.abs(x) < 1.0e-6f && kotlin.math.abs(y) < 1.0e-6f && kotlin.math.abs(z) < 1.0e-6f)) {
                // A zero-Doppler profile must not overwrite velocity owned by Minecraft or
                // another mod. It only releases state that AcousticShaders can still prove
                // it owns from a previous update of this same OpenAL source id.
                resetVelocity(sourceId)
                return
            }
            val al = requireNotNull(al10)
            val current = currentVelocity(sourceId, al) ?: return
            val owned = velocitySources[sourceId]
            if (owned == null) {
                // AL_VELOCITY is a shared core OpenAL property.  Do not claim a source
                // that already carries a non-zero vector supplied by Minecraft or another
                // mod; there is no namespaced slot that lets us compose both owners.
                if (!isZeroVelocity(current[0], current[1], current[2])) return
            } else if (!owned.matches(current[0], current[1], current[2])) {
                // Another owner replaced the vector after our previous write.  Relinquish
                // bookkeeping and fail closed instead of immediately overwriting it again.
                velocitySources.remove(sourceId)
                return
            }
            call(
                al,
                "alSource3f",
                sourceId,
                constant(al, "AL_VELOCITY"),
                x,
                y,
                z
            )
            velocitySources[sourceId] = OwnedVelocity(x, y, z)
        } catch (t: Throwable) {
            disable(t)
        }
    }

    private fun isZeroVelocity(x: Float, y: Float, z: Float): Boolean =
        kotlin.math.abs(x) < 1.0e-6f &&
            kotlin.math.abs(y) < 1.0e-6f &&
            kotlin.math.abs(z) < 1.0e-6f

    private fun currentVelocity(sourceId: Int, al: Class<*>): FloatArray? {
        return try {
            // LWJGL 2.9.4 exposes AL10.alGetSource(int,int,FloatBuffer) for vector
            // properties. Use a direct native-order buffer because the generated binding
            // rejects heap buffers on the physical 1.12.2 runtime.
            val values = ByteBuffer.allocateDirect(3 * java.lang.Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            call(al, "alGetSource", sourceId, constant(al, "AL_VELOCITY"), values)
            floatArrayOf(values.get(0), values.get(1), values.get(2))
        } catch (_: Throwable) {
            null
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
            orphanFilters.clear()
            efxResourceSuspended = false
            orphanPressureWarned = false
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
        if (efxResourceSuspended) return false
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

    /**
     * Prepare a source for an AcousticShaders EFX write without overwriting a different
     * EFX owner.  If this source already has our tracked pair, it is detached first and
     * only then deleted; deleting an attached filter and dropping bookkeeping can leak a
     * native object on implementations that reject deletion while referenced.
     */
    @Throws(Exception::class)
    private fun prepareForEfxWrite(sourceId: Int): Boolean {
        if (efxResourceSuspended) return false
        if (filters.containsKey(sourceId)) return removeFilters(sourceId)
        val efx = requireNotNull(efx10)
        val liveDirect = currentDirectFilter(sourceId, efx) ?: return false
        return liveDirect == constant(efx, "AL_FILTER_NULL")
    }

    /**
     * Detach and delete a tracked pair only while the live direct-filter still proves
     * ownership.  If ownership was lost, retire the old ids into the bounded context
     * orphan set and leave the new owner's source state untouched.
     */
    @Throws(Exception::class)
    private fun removeFilters(sourceId: Int): Boolean {
        val entry = filters.remove(sourceId) ?: return true
        val efx = requireNotNull(efx10)
        val liveDirect = currentDirectFilter(sourceId, efx)
        if (liveDirect == null || liveDirect != entry.direct) {
            retireOrphan(entry)
            return false
        }
        try {
            call(requireNotNull(al10), "alSourcei", sourceId, constant(efx, "AL_DIRECT_FILTER"), constant(efx, "AL_FILTER_NULL"))
        } catch (_: Throwable) {}
        try {
            call(requireNotNull(al11), "alSource3i", sourceId, constant(efx, "AL_AUXILIARY_SEND_FILTER"), 0, 0, constant(efx, "AL_FILTER_NULL"))
        } catch (_: Throwable) {}
        safeDeleteFilter(entry.direct)
        if (entry.send > 0) safeDeleteFilter(entry.send)
        return true
    }

    private fun retireOrphan(entry: Filters) {
        if (entry.direct > 0) orphanFilters.add(entry.direct)
        if (entry.send > 0) orphanFilters.add(entry.send)
        if (orphanFilters.size >= MAX_ORPHAN_FILTERS && !efxResourceSuspended) {
            efxResourceSuspended = true
            if (!orphanPressureWarned) {
                orphanPressureWarned = true
                AcousticLog.warn(
                    "OpenAL EFX ownership-conflict budget exhausted (${orphanFilters.size} orphan filters); " +
                        "suspending EFX allocation until the OpenAL context changes"
                )
            }
        }
    }

    private fun currentDirectFilter(sourceId: Int, efx: Class<*>): Int? {
        return try {
            (call(requireNotNull(al10), "alGetSourcei", sourceId, constant(efx, "AL_DIRECT_FILTER")) as? Number)?.toInt()
        } catch (_: Throwable) {
            null
        }
    }

    @Throws(Exception::class)
    private fun trim() {
        while (filters.size > 96) {
            val first = filters.keys.iterator().next()
            removeFilters(first)
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

    private data class OwnedVelocity(val x: Float, val y: Float, val z: Float) {
        fun matches(otherX: Float, otherY: Float, otherZ: Float): Boolean =
            kotlin.math.abs(x - otherX) <= 1.0e-5f &&
                kotlin.math.abs(y - otherY) <= 1.0e-5f &&
                kotlin.math.abs(z - otherZ) <= 1.0e-5f
    }

    private companion object {
        const val MAX_ORPHAN_FILTERS = 128
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
