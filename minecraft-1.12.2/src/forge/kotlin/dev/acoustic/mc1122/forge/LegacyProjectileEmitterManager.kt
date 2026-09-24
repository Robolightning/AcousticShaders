package dev.acoustic.mc1122.forge

import net.minecraft.client.audio.MovingSound
import net.minecraft.client.audio.ISound
import net.minecraft.util.ResourceLocation
import net.minecraft.util.SoundCategory
import net.minecraft.util.SoundEvent
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bounded client-side flight emitters for vanilla 1.12.2 projectiles.
 *
 * The emitted sound is a normal Minecraft MovingSound. Consequently it follows the normal
 * SoundHandler -> Paulscode -> mixin -> LegacySoundHook path and receives the same acoustic
 * processing as any other positional source.
 */
internal class LegacyProjectileEmitterManager {
    private val active = LinkedHashMap<Any, ProjectileFlightSound>()
    private var worldIdentity: Any? = null

    fun tick(world: Any?, listener: Any?) {
        if (world == null || listener == null) {
            clear()
            worldIdentity = null
            return
        }
        if (world !== worldIdentity) {
            clear()
            worldIdentity = world
        }

        val candidates = loadedEntities(world)
            .asSequence()
            .filter { isSupportedVanillaProjectile(it) }
            .filter { isFlying(it) }
            .map { it to distanceSquared(it, listener) }
            .filter { it.second <= MAX_DISTANCE_SQ }
            .sortedBy { it.second }
            .take(MAX_EMITTERS)
            .map { it.first }
            .toList()
        val keep = candidates.toHashSet()

        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in keep || !isFlying(entry.key)) {
                entry.value.stopNow()
                iterator.remove()
            }
        }

        for (entity in candidates) {
            val existing = active[entity]
            if (existing != null) {
                existing.syncFromEntity(entity)
                continue
            }
            val sound = ProjectileFlightSound(entity, resolveFlightSoundEvent())
            active[entity] = sound
            play(sound)
        }
    }

    fun clear() {
        for (sound in active.values) sound.stopNow()
        active.clear()
    }

    internal fun activeCount(): Int = active.size
    internal fun hasEntity(entity: Any): Boolean = active.containsKey(entity)

    private fun play(sound: ProjectileFlightSound) {
        val minecraft = ForgeReflection.invoke(
            ForgeReflection.type("net.minecraft.client.Minecraft"),
            arrayOf("getMinecraft", "func_71410_x")
        ) ?: throw IllegalStateException("Minecraft singleton unavailable")
        val handler = ForgeReflection.invoke(minecraft, arrayOf("getSoundHandler", "func_147118_V"))
            ?: throw IllegalStateException("SoundHandler unavailable")
        ForgeReflection.invoke(handler, arrayOf("playSound", "func_147682_a"), sound)
    }

    private fun loadedEntities(world: Any): List<Any> {
        val value = try { ForgeReflection.field(world, "loadedEntityList", "field_72996_f") } catch (_: RuntimeException) { return emptyList() }
        @Suppress("UNCHECKED_CAST")
        return (value as? List<Any>) ?: emptyList()
    }

    private fun isSupportedVanillaProjectile(entity: Any): Boolean {
        if (!entity.javaClass.name.startsWith("net.minecraft.")) return false
        return PROJECTILE_BASES.any { name ->
            try { ForgeReflection.type(name).isAssignableFrom(entity.javaClass) } catch (_: RuntimeException) { false }
        }
    }

    private fun isFlying(entity: Any): Boolean {
        if (booleanField(entity, "isDead", "field_70128_L")) return false
        if (isArrow(entity) && booleanField(entity, "inGround", "field_70254_i")) return false
        return speedSquared(entity) >= MIN_SPEED_SQ
    }

    private fun isArrow(entity: Any): Boolean = try {
        ForgeReflection.type("net.minecraft.entity.projectile.EntityArrow").isAssignableFrom(entity.javaClass)
    } catch (_: RuntimeException) { false }

    private fun speedSquared(entity: Any): Double {
        val x = numberField(entity, "motionX", "field_70159_w")
        val y = numberField(entity, "motionY", "field_70181_x")
        val z = numberField(entity, "motionZ", "field_70179_y")
        return x * x + y * y + z * z
    }

    private fun distanceSquared(entity: Any, listener: Any): Double {
        val dx = numberField(entity, "posX", "field_70165_t") - numberField(listener, "posX", "field_70165_t")
        val dy = numberField(entity, "posY", "field_70163_u") - numberField(listener, "posY", "field_70163_u")
        val dz = numberField(entity, "posZ", "field_70161_v") - numberField(listener, "posZ", "field_70161_v")
        return dx * dx + dy * dy + dz * dz
    }

    private fun booleanField(entity: Any, vararg names: String): Boolean = try {
        (ForgeReflection.field(entity, *names) as? Boolean) == true
    } catch (_: RuntimeException) { false }

    private fun numberField(entity: Any, vararg names: String): Double = try {
        ForgeReflection.numberField(entity, *names)
    } catch (_: RuntimeException) { 0.0 }

    private fun resolveFlightSoundEvent(): SoundEvent {
        val registry = ForgeReflection.staticField("net.minecraft.util.SoundEvent", "REGISTRY", "field_187505_a")
            ?: throw IllegalStateException("SoundEvent registry unavailable")
        val location = ResourceLocation("acousticshaders", "projectile.flight")
        return ForgeReflection.invoke(registry, arrayOf("getObject", "func_82594_a"), location) as? SoundEvent
            ?: throw IllegalStateException("acousticshaders:projectile.flight is not registered")
    }

    private class ProjectileFlightSound(entity: Any, event: SoundEvent) : MovingSound(event, SoundCategory.NEUTRAL) {
        private var tracked: Any? = entity

        init {
            setInheritedField(true, "repeat", "field_147659_g")
            setInheritedField(0, "repeatDelay", "field_147665_h")
            setInheritedField(ISound.AttenuationType.LINEAR, "attenuationType", "field_147666_i")
            syncFromEntity(entity)
        }

        override fun update() {
            val entity = tracked
            if (entity == null) {
                setInheritedField(true, "donePlaying", "field_147668_j")
                return
            }
            syncFromEntity(entity)
        }

        // Physical SRG source view keeps this alias as the override and strips override from update().
        fun func_73660_a() = update()

        fun syncFromEntity(entity: Any) {
            val x = ForgeReflection.numberField(entity, "posX", "field_70165_t")
            val y = ForgeReflection.numberField(entity, "posY", "field_70163_u")
            val z = ForgeReflection.numberField(entity, "posZ", "field_70161_v")
            setInheritedField(x.toFloat(), "xPosF", "field_147660_d")
            setInheritedField(y.toFloat(), "yPosF", "field_147661_e")
            setInheritedField(z.toFloat(), "zPosF", "field_147658_f")
            val vx = ForgeReflection.numberField(entity, "motionX", "field_70159_w")
            val vy = ForgeReflection.numberField(entity, "motionY", "field_70181_x")
            val vz = ForgeReflection.numberField(entity, "motionZ", "field_70179_y")
            val speed = sqrt(vx * vx + vy * vy + vz * vz)
            setInheritedField(min(0.55f, max(0.08f, (0.10 + speed * 0.10).toFloat())), "volume", "field_147662_b")
            setInheritedField(min(1.35f, max(0.75f, (0.88 + speed * 0.08).toFloat())), "pitch", "field_147663_c")
        }

        private fun setInheritedField(value: Any?, mcpName: String, srgName: String) {
            ForgeReflection.setField(this, value, mcpName, srgName)
        }

        fun stopNow() {
            tracked = null
            setInheritedField(true, "donePlaying", "field_147668_j")
        }
    }

    companion object {
        private const val MAX_EMITTERS = 24
        private const val MAX_DISTANCE_SQ = 64.0 * 64.0
        private const val MIN_SPEED_SQ = 0.01 * 0.01
        private val PROJECTILE_BASES = arrayOf(
            "net.minecraft.entity.projectile.EntityArrow",
            "net.minecraft.entity.projectile.EntityThrowable",
            "net.minecraft.entity.projectile.EntityFireball",
            "net.minecraft.entity.projectile.EntityLlamaSpit",
            "net.minecraft.entity.projectile.EntityShulkerBullet"
        )
    }
}
