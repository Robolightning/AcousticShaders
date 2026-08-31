package dev.acoustic.mc1122.forge

import dev.acoustic.core.compute.FdtdBackendRegistry
import dev.acoustic.core.compute.GeometricBackendRegistry
import dev.acoustic.core.pack.LoadedShaderPack
import dev.acoustic.core.pack.PackUiModel
import dev.acoustic.core.runtime.ResolvedProfile
import java.io.IOException
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Shader-defined option editor. Presets are shader-local and every value can be overridden. */
internal class GuiAcousticShaderOptions(private val parent: GuiAcousticShaders) : GuiScreen() {
    private var pack: LoadedShaderPack? = null
    private val keys = ArrayList<String>()
    private var error = ""
    private var page = 0

    override fun initGui() = initGuiImpl()
    override fun func_73866_w_() = initGuiImpl()

    private fun initGuiImpl() {
        GuiCompat.clearButtons(this)
        keys.clear()
        error = ""
        try {
            val loaded = parent.previewPack()
            pack = loaded
            val ui = PackUiModel.from(loaded.options())
            for (key in ui.screenTokens()) {
                if (!key.equals("PROFILE", true) && !key.equals("PRESET", true)) keys.add(key)
            }
        } catch (t: Throwable) {
            error = shortMessage(t)
            pack = null
        }
        val width = GuiCompat.width(this)
        val height = GuiCompat.height(this)
        add(GuiButton(ID_DONE, width / 2 + 4, height - 27, 96, 20, "Done"))
        add(GuiButton(ID_RESET, width / 2 - 100, height - 27, 96, 20, "Reset custom"))
        add(GuiButton(ID_PRESET, width / 2 - 95, 24, 190, 20, "Preset: ${parent.editProfile()}"))
        val pages = pageCount()
        if (page >= pages) page = max(0, pages - 1)
        add(GuiButton(ID_PREV, width / 2 - 150, 24, 48, 20, "<"))
        add(GuiButton(ID_NEXT, width / 2 + 102, 24, 48, 20, ">"))
        if (pack != null) buildOptionButtons()
        updateNavigation()
    }

    private fun <T : GuiButton> add(button: T): T = GuiCompat.addButton(this, button)
    private fun rowsPerPage(): Int = max(3, min(6, (GuiCompat.height(this) - 112) / 26))
    private fun pageCount(): Int = max(1, (keys.size + rowsPerPage() - 1) / rowsPerPage())

    private fun buildOptionButtons() {
        val width = GuiCompat.width(this)
        var y = 60
        val from = page * rowsPerPage()
        val to = min(keys.size, from + rowsPerPage())
        var index = from
        while (index < to) {
            val key = keys[index]
            val type = value(key, "type", inferType(key))
            val id = 1000 + index * 10
            if (type.equals("enum", true) || type.equals("boolean", true)) {
                add(GuiButton(id, width / 2 + 16, y, 140, 20, current(key)))
            } else {
                add(GuiButton(id, width / 2 + 16, y, 36, 20, "-"))
                add(GuiButton(id + 1, width / 2 + 56, y, 60, 20, current(key)))
                add(GuiButton(id + 2, width / 2 + 120, y, 36, 20, "+"))
            }
            y += 26
            index++
        }
    }

    private fun updateNavigation() {
        for (b in GuiCompat.buttons(this)) {
            when (GuiCompat.buttonId(b)) {
                ID_PREV -> GuiCompat.setButtonEnabled(b, page > 0)
                ID_NEXT -> GuiCompat.setButtonEnabled(b, page + 1 < pageCount())
            }
        }
    }

    @Throws(IOException::class)
    override fun actionPerformed(b: GuiButton) = actionPerformedImpl(b)

    @Throws(IOException::class)
    override fun func_146284_a(b: GuiButton) = actionPerformedImpl(b)

    private fun actionPerformedImpl(b: GuiButton) {
        val id = GuiCompat.buttonId(b)
        when (id) {
            ID_DONE -> { parent.optionsChanged(); GuiCompat.display(this, parent); return }
            ID_RESET -> { parent.editOverrides().clear(); initGuiImpl(); return }
            ID_PRESET -> { cyclePreset(); return }
            ID_PREV -> { page = max(0, page - 1); initGuiImpl(); return }
            ID_NEXT -> { page = min(pageCount() - 1, page + 1); initGuiImpl(); return }
        }
        if (id < 1000) return
        val row = (id - 1000) / 10
        if (row !in keys.indices) return
        val key = keys[row]
        val op = (id - 1000) % 10
        val type = value(key, "type", inferType(key))
        if (type.equals("enum", true) || type.equals("boolean", true)) {
            cycleEnum(key)
            initGuiImpl()
            return
        }
        if (op == 1) {
            parent.editOverrides().remove(key)
            initGuiImpl()
            return
        }
        adjustNumber(key, if (op == 0) -1 else 1)
        initGuiImpl()
    }

    private fun cyclePreset() {
        val loaded = pack ?: return
        val profiles = loaded.options().profiles()
        if (profiles.isEmpty()) return
        val i = profiles.indexOf(parent.editProfile())
        parent.setEditProfile(profiles[(i + 1 + profiles.size) % profiles.size])
        parent.editOverrides().clear()
        page = 0
        initGuiImpl()
    }

    private fun cycleEnum(key: String) {
        var values = tokens(value(key, "values", ""))
        if (values.isEmpty() && value(key, "type", "").equals("boolean", true)) values = listOf("ON", "OFF")
        if (values.isEmpty()) return
        val current = current(key)
        val i = values.indexOf(current)
        parent.editOverrides()[key] = values[(i + 1 + values.size) % values.size]
    }

    private fun adjustNumber(key: String, direction: Int) {
        try {
            var v = current(key).toDouble()
            val minimum = value(key, "min", "0").toDouble()
            val maximum = value(key, "max", "999999").toDouble()
            val step = value(key, "step", "1").toDouble()
            v = max(minimum, min(maximum, v + direction * step))
            val type = value(key, "type", inferType(key))
            parent.editOverrides()[key] = if (type.equals("integer", true)) round(v).toInt().toString() else format(v)
        } catch (_: RuntimeException) {
            // Invalid shader metadata remains non-fatal and leaves the current value unchanged.
        }
    }

    private fun current(key: String): String {
        val loaded = pack ?: return "?"
        parent.editOverrides()[key]?.let { return it }
        return try { ResolvedProfile.from(loaded.options(), parent.editProfile()).get(key, "?") ?: "?" } catch (_: Throwable) { "?" }
    }

    private fun inferType(key: String): String {
        val raw = optionalValue(key, "values")
        if (!raw.isNullOrBlank()) return "enum"
        val c = current(key)
        c.toIntOrNull()?.let { return "integer" }
        c.toDoubleOrNull()?.let { return "float" }
        return "enum"
    }

    private fun value(key: String, suffix: String, fallback: String): String =
        pack?.options()?.get("option.$key.$suffix") ?: fallback

    private fun optionalValue(key: String, suffix: String): String? =
        pack?.options()?.get("option.$key.$suffix")

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) = drawScreenImpl(mouseX, mouseY, partialTicks)
    override fun func_73863_a(mouseX: Int, mouseY: Int, partialTicks: Float) = drawScreenImpl(mouseX, mouseY, partialTicks)

    private fun drawScreenImpl(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val width = GuiCompat.width(this)
        val height = GuiCompat.height(this)
        GuiCompat.drawDefaultBackground(this)
        GuiCompat.drawCentered(this, "Acoustic Shader Options", width / 2, 8, 0xFFFFFF)
        if (pack != null) {
            var y = 66
            val from = page * rowsPerPage()
            val to = min(keys.size, from + rowsPerPage())
            var i = from
            while (i < to) {
                val key = keys[i]
                val label = value(key, "label", key)
                GuiCompat.drawString(this, label, width / 2 - 156, y, 0xFFFFFF)
                if (parent.editOverrides().containsKey(key)) GuiCompat.drawString(this, "*", width / 2 - 12, y, 0x66FF66)
                y += 26
                i++
            }
            GuiCompat.drawCentered(this, "Page ${page + 1}/${pageCount()}   * custom override; click numeric value to reset", width / 2, height - 42, 0x999999)
            GuiCompat.drawCentered(this, GuiCompat.trimToWidth(this, computeStatus(), max(40, width - 20)), width / 2, height - 52, 0x88CCFF)
        } else {
            GuiCompat.drawCentered(this, "Cannot load options: $error", width / 2, 70, 0xFF7777)
        }
        super.func_73863_a(mouseX, mouseY, partialTicks)
    }

    private companion object {
        const val ID_DONE = 1
        const val ID_RESET = 2
        const val ID_PRESET = 3
        const val ID_PREV = 4
        const val ID_NEXT = 5

        fun tokens(raw: String?): List<String> = if (raw == null) emptyList() else raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        fun computeStatus(): String {
            val rays = GeometricBackendRegistry.available()
            val waves = FdtdBackendRegistry.available()
            val r = if (rays.isEmpty()) "rays=CPU parallel" else "rays=${rays[0].description()}"
            val w = if (waves.isEmpty()) "FDTD=CPU parallel" else "FDTD=${waves[0].description()}"
            return "Compute: $r | $w"
        }

        fun format(v: Double): String {
            val r = round(v).toLong()
            return if (abs(v - r) < 1e-9) r.toString() else v.toString()
        }

        fun shortMessage(t: Throwable): String = t.message ?: t.javaClass.simpleName
    }
}
