package dev.acoustic.mc1122.forge

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraftforge.fml.client.IModGuiFactory
import java.util.Collections

/** Enables the standard Forge Mod List -> Config button. */
class AcousticGuiFactory : IModGuiFactory {
    override fun initialize(minecraftInstance: Minecraft) {}
    override fun hasConfigGui(): Boolean = true
    override fun createConfigGui(parentScreen: GuiScreen): GuiScreen = GuiAcousticShaders(parentScreen, AcousticShadersForgeMod.runtime())
    override fun runtimeGuiCategories(): Set<IModGuiFactory.RuntimeOptionCategoryElement> = Collections.emptySet()
}
