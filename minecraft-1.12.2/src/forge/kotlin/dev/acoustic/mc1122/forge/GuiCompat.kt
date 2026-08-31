package dev.acoustic.mc1122.forge

import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import kotlin.math.max

/** Production-name-safe bridge for Minecraft 1.12.2 GUI members. */
object GuiCompat {
    @JvmStatic fun width(screen: GuiScreen): Int = intField(screen, "width", "field_146294_l")
    @JvmStatic fun height(screen: GuiScreen): Int = intField(screen, "height", "field_146295_m")
    @JvmStatic @Suppress("UNCHECKED_CAST") fun buttons(screen: GuiScreen): MutableList<GuiButton> = ForgeReflection.field(screen, "buttonList", "field_146292_n") as MutableList<GuiButton>
    @JvmStatic fun font(screen: GuiScreen): Any = requireNotNull(ForgeReflection.field(screen, "fontRenderer", "field_146289_q"))
    @JvmStatic fun minecraft(screen: GuiScreen): Any = requireNotNull(ForgeReflection.field(screen, "mc", "field_146297_k"))

    @JvmStatic fun buttonId(button: GuiButton): Int = intField(button, "id", "field_146127_k")
    @JvmStatic fun buttonX(button: GuiButton): Int = intField(button, "x", "field_146128_h")
    @JvmStatic fun buttonY(button: GuiButton): Int = intField(button, "y", "field_146129_i")
    @JvmStatic fun buttonWidth(button: GuiButton): Int = intField(button, "width", "field_146120_f")
    @JvmStatic fun buttonHeight(button: GuiButton): Int = intField(button, "height", "field_146121_g")
    @JvmStatic fun setButtonPosition(button: GuiButton, x: Int, y: Int) {
        ForgeReflection.setField(button, x, "x", "field_146128_h")
        ForgeReflection.setField(button, y, "y", "field_146129_i")
    }
    @JvmStatic fun setButtonEnabled(button: GuiButton, value: Boolean) = ForgeReflection.setField(button, value, "enabled", "field_146124_l")
    @JvmStatic fun setButtonText(button: GuiButton, value: String) = ForgeReflection.setField(button, value, "displayString", "field_146126_j")
    @JvmStatic fun clearButtons(screen: GuiScreen) = buttons(screen).clear()
    @JvmStatic fun <T : GuiButton> addButton(screen: GuiScreen, button: T): T { buttons(screen).add(button); return button }
    @JvmStatic fun display(source: GuiScreen, target: GuiScreen) { ForgeReflection.invoke(minecraft(source), arrayOf("displayGuiScreen", "func_147108_a"), target) }
    @JvmStatic fun drawDefaultBackground(screen: GuiScreen) { ForgeReflection.invoke(screen, arrayOf("drawDefaultBackground", "func_146276_q_")) }
    @JvmStatic fun drawCentered(screen: GuiScreen, text: String, x: Int, y: Int, color: Int) { ForgeReflection.invoke(screen, arrayOf("drawCenteredString", "func_73732_a"), font(screen), text, x, y, color) }
    @JvmStatic fun drawString(screen: GuiScreen, text: String, x: Int, y: Int, color: Int) { ForgeReflection.invoke(screen, arrayOf("drawString", "func_73731_b"), font(screen), text, x, y, color) }
    @JvmStatic fun drawRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) { ForgeReflection.invoke(ForgeReflection.type("net.minecraft.client.gui.Gui"), arrayOf("drawRect", "func_73734_a"), left, top, right, bottom, color) }
    @JvmStatic fun stringWidth(screen: GuiScreen, text: String): Int = (ForgeReflection.invoke(font(screen), arrayOf("getStringWidth", "func_78256_a"), text) as Number).toInt()

    @JvmStatic fun trimToWidth(screen: GuiScreen, text: String?, width: Int): String {
        if (text.isNullOrEmpty()) return ""
        val maxWidth = max(0, width)
        if (stringWidth(screen, text) <= maxWidth) return text
        val suffix = "..."
        val suffixWidth = stringWidth(screen, suffix)
        if (suffixWidth > maxWidth) return ""
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            val candidate = text.substring(0, mid)
            if (stringWidth(screen, candidate) + suffixWidth <= maxWidth) lo = mid else hi = mid - 1
        }
        while (lo > 0 && Character.isHighSurrogate(text[lo - 1])) lo--
        return text.substring(0, lo) + suffix
    }

    private fun intField(target: Any, mcp: String, srg: String): Int = (ForgeReflection.field(target, mcp, srg) as Number).toInt()
}
