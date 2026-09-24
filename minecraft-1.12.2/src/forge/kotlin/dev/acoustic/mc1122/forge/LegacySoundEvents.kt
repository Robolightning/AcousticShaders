package dev.acoustic.mc1122.forge

import net.minecraft.util.ResourceLocation
import net.minecraft.util.SoundEvent
import net.minecraftforge.event.RegistryEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.relauncher.Side

/** Registers the synthetic projectile-flight sound event in the normal Forge 1.12.2 registry. */
@Mod.EventBusSubscriber(value = [Side.CLIENT], modid = AcousticShadersForgeMod.MODID)
object LegacySoundEvents {
    private val PROJECTILE_FLIGHT = ResourceLocation(AcousticShadersForgeMod.MODID, "projectile.flight")

    @JvmStatic
    @SubscribeEvent
    fun registerSounds(event: RegistryEvent.Register<SoundEvent>) {
        val sound = SoundEvent(PROJECTILE_FLIGHT)
        // SoundEvent is Forge-patched to IForgeRegistryEntry in 1.12.2. Keep the patched method
        // reflective here so the independent vanilla+Forge SRG audit does not pretend that the
        // Forge patch is physically present in Mojang's unpatched client.jar.
        ForgeReflection.invoke(sound, arrayOf("setRegistryName"), PROJECTILE_FLIGHT)
        event.registry.register(sound)
    }

    @JvmStatic
    internal fun projectileFlightId(): ResourceLocation = PROJECTILE_FLIGHT
}
