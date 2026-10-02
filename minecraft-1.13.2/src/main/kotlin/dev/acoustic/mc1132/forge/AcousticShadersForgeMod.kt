package dev.acoustic.mc1132.forge

import java.util.function.Consumer
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

/**
 * Minimal Forge-25/JavaFML bootstrap only.
 *
 * Runtime/audio hooks are deliberately not ported here. The first 1.13.2 milestone proves
 * loader/lifecycle compatibility before any legacy hook strategy is selected.
 */
@Mod(AcousticShadersForgeMod.MOD_ID)
class AcousticShadersForgeMod {
    init {
        FMLJavaModLoadingContext.get().modEventBus.addListener(
            Consumer<FMLCommonSetupEvent> { event -> commonSetup(event) }
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun commonSetup(event: FMLCommonSetupEvent) {
        // Intentionally empty until the 1.13.2 platform adapter contract is established.
    }

    companion object {
        const val MOD_ID = "acousticshaders"
    }
}
