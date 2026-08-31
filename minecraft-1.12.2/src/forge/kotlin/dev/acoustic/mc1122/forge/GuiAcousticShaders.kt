package dev.acoustic.mc1122.forge

import dev.acoustic.core.pack.LoadedShaderPack
import dev.acoustic.core.pack.ShaderPackStackComposer
import dev.acoustic.mc1122.LegacyShaderPackCatalog
import java.awt.Desktop
import java.io.IOException
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shader-like two-pane acoustic pack selector. */
internal class GuiAcousticShaders(
    private val parent: GuiScreen,
    private val runtime: LegacyClientRuntime?
) : GuiScreen() {
    private var catalog: List<LegacyShaderPackCatalog.Entry> = emptyList()
    private val active = ArrayList<String>()
    private val overrides = LinkedHashMap<String, String>()
    private var profile = "HIGH"
    private var status = ""
    private var leftOffset = 0
    private var rightOffset = 0
    private var dragActive = false
    private var dragFile: String? = null

    init {
        if (runtime != null) {
            val c = runtime.config()
            active.addAll(c.packs())
            profile = c.profile()
            overrides.putAll(c.optionOverrides())
        }
    }

    override fun initGui() = initGuiImpl()
    override fun func_73866_w_() = initGuiImpl()

    private fun initGuiImpl() {
        GuiCompat.clearButtons(this)
        reloadCatalog()
        val width = GuiCompat.width(this)
        val height = GuiCompat.height(this)
        val bottom = height - 26
        add(GuiButton(ID_DONE, width / 2 + 104, bottom, 96, 20, "Done"))
        add(GuiButton(ID_APPLY, width / 2 + 2, bottom, 96, 20, "Apply"))
        add(GuiButton(ID_OPTIONS, width / 2 - 100, bottom, 96, 20, "Shader Options..."))
        add(GuiButton(ID_FOLDER, 15, bottom, 96, 20, "Open Folder"))
        add(GuiButton(ID_RUNTIME_AUDIO, width - 126, 28, 111, 20, "Runtime & Audio..."))
        val top = 52
        val paneW = paneWidth()
        add(GuiButton(ID_LEFT_UP, 15 + paneW - 42, top, 20, 20, "^"))
        add(GuiButton(ID_LEFT_DOWN, 15 + paneW - 21, top, 20, 20, "v"))
        val rx = 30 + paneW
        add(GuiButton(ID_RIGHT_UP, rx + paneW - 42, top, 20, 20, "^"))
        add(GuiButton(ID_RIGHT_DOWN, rx + paneW - 21, top, 20, 20, "v"))
        updateButtons()
    }

    private fun <T : GuiButton> add(button: T): T = GuiCompat.addButton(this, button)

    @Throws(IOException::class)
    override fun actionPerformed(button: GuiButton) = actionPerformedImpl(button)

    @Throws(IOException::class)
    override fun func_146284_a(button: GuiButton) = actionPerformedImpl(button)

    private fun actionPerformedImpl(button: GuiButton) {
        when (GuiCompat.buttonId(button)) {
            ID_DONE -> GuiCompat.display(this, parent)
            ID_APPLY -> applyConfiguration()
            ID_OPTIONS -> if (runtime != null && active.isNotEmpty()) {
                GuiCompat.display(this, GuiAcousticShaderOptions(this))
            }
            ID_FOLDER -> openFolder()
            ID_RUNTIME_AUDIO -> if (runtime != null) GuiCompat.display(this, GuiRuntimeAudio(this, runtime))
            ID_LEFT_UP -> { leftOffset = max(0, leftOffset - 1); updateButtons() }
            ID_LEFT_DOWN -> { leftOffset = min(maxLeftOffset(), leftOffset + 1); updateButtons() }
            ID_RIGHT_UP -> { rightOffset = max(0, rightOffset - 1); updateButtons() }
            ID_RIGHT_DOWN -> { rightOffset = min(maxRightOffset(), rightOffset + 1); updateButtons() }
        }
    }

    private fun applyConfiguration() {
        val r = runtime
        if (r == null) {
            status = "Runtime is not initialized"
            return
        }
        try {
            r.applyUiConfiguration(ArrayList(active), profile, LinkedHashMap(overrides))
            status = if (active.isEmpty()) "Applied: acoustic shaders disabled" else "Applied: ${r.pack().stackNames()}"
            reloadCatalog()
        } catch (t: Throwable) {
            status = "Not applied: ${shortMessage(t)}"
            r.debugError("GUI apply", t)
        }
        updateButtons()
    }

    private fun openFolder() {
        val r = runtime ?: return
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(r.shaderpackDir().toFile())
            else status = "Open: ${r.shaderpackDir()}"
        } catch (t: Throwable) {
            status = "Could not open folder: ${shortMessage(t)}"
        }
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) = drawScreenImpl(mouseX, mouseY, partialTicks)
    override fun func_73863_a(mouseX: Int, mouseY: Int, partialTicks: Float) = drawScreenImpl(mouseX, mouseY, partialTicks)

    private fun drawScreenImpl(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val width = GuiCompat.width(this)
        val height = GuiCompat.height(this)
        GuiCompat.drawDefaultBackground(this)
        GuiCompat.drawCentered(this, "Acoustic Shaders", width / 2, 8, 0xFFFFFF)
        GuiCompat.drawCentered(this, "Drag packs to the right. Presets live inside Shader Options.", width / 2, 18, 0xA0A0A0)
        val paneW = paneWidth()
        val top = 52
        val bottom = height - 34
        drawPanel(15, top, paneW, bottom, "Available", available(), leftOffset, false, mouseX, mouseY)
        drawPanel(30 + paneW, top, paneW, bottom, "Active Stack", activeEntries(), rightOffset, true, mouseX, mouseY)
        if (status.isNotEmpty()) {
            GuiCompat.drawCentered(this, status, width / 2, height - 38, if (status.startsWith("Not applied")) 0xFF7777 else 0xBBBBBB)
        }
        super.func_73863_a(mouseX, mouseY, partialTicks)
        val file = dragFile
        if (file != null) {
            val entry = find(file)
            val label = entry?.name() ?: file
            val w = min(180, GuiCompat.stringWidth(this, label) + 18)
            GuiCompat.drawRect(mouseX + 8, mouseY + 8, mouseX + 8 + w, mouseY + 29, 0xDD202020.toInt())
            GuiCompat.drawString(this, label, mouseX + 14, mouseY + 14, 0xFFFFFF)
        }
    }

    private fun drawPanel(
        x: Int, top: Int, w: Int, bottom: Int, title: String,
        rows: List<LegacyShaderPackCatalog.Entry>, offset: Int, isActive: Boolean,
        mouseX: Int, mouseY: Int
    ) {
        GuiCompat.drawRect(x, top, x + w, bottom, 0x99000000.toInt())
        GuiCompat.drawString(this, title, x + 6, top + 6, 0xFFFFFF)
        val y = top + 24
        if (isActive && rows.isEmpty()) {
            GuiCompat.drawCentered(this, "None (vanilla audio)", x + w / 2, y + 12, 0xAAAAAA)
            return
        }
        val visible = max(1, (bottom - y - 4) / ROW_HEIGHT)
        var slot = 0
        while (slot < visible) {
            val index = offset + slot
            if (index >= rows.size) break
            val e = rows[index]
            val ry = y + slot * ROW_HEIGHT
            val hover = mouseX >= x + 3 && mouseX < x + w - 3 && mouseY >= ry && mouseY < ry + ROW_HEIGHT - 2
            GuiCompat.drawRect(x + 3, ry, x + w - 3, ry + ROW_HEIGHT - 2, if (hover) 0xAA4A4A4A.toInt() else 0x88404040.toInt())
            drawPackIcon(e, x + 8, ry + 6)
            GuiCompat.drawString(this, trim(e.name(), w - 52), x + 40, ry + 7, if (e.valid()) 0xFFFFFF else 0xFF7777)
            val detail = if (isActive) "Priority #${index + 1}${if (index == 0) " (highest)" else ""}" else e.fileName()
            GuiCompat.drawString(this, trim(detail, w - 52), x + 40, ry + 20, if (e.valid()) 0xA0A0A0 else 0xFF9999)
            slot++
        }
    }

    private fun drawPackIcon(e: LegacyShaderPackCatalog.Entry, x: Int, y: Int) {
        val h = e.id().hashCode()
        val rgb = 0x404040 or ((h ushr 8) and 0x007F7F7F)
        GuiCompat.drawRect(x, y, x + 24, y + 24, 0xFF000000.toInt() or rgb)
        GuiCompat.drawRect(x + 2, y + 2, x + 22, y + 22, 0xFF101010.toInt())
        var i = 0
        while (i < 5) {
            val bh = 4 + abs((h shr (i * 4)) and 15)
            val bx = x + 4 + i * 4
            GuiCompat.drawRect(bx, y + 20 - bh, bx + 2, y + 20, 0xFF000000.toInt() or rgb)
            i++
        }
        GuiCompat.drawRect(x + 3, y + 20, x + 21, y + 21, 0xFF000000.toInt() or rgb)
    }

    @Throws(IOException::class)
    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) = mouseClickedImpl(mouseX, mouseY, mouseButton)

    @Throws(IOException::class)
    override fun func_73864_a(mouseX: Int, mouseY: Int, mouseButton: Int) = mouseClickedImpl(mouseX, mouseY, mouseButton)

    private fun mouseClickedImpl(mouseX: Int, mouseY: Int, mouseButton: Int) {
        super.func_73864_a(mouseX, mouseY, mouseButton)
        if (mouseButton != 0) return
        val hit = hit(mouseX, mouseY) ?: return
        dragActive = hit.active
        dragFile = hit.entry.fileName()
    }

    override fun mouseClickMove(mouseX: Int, mouseY: Int, clickedMouseButton: Int, timeSinceLastClick: Long) = mouseClickMoveImpl()
    override fun func_146273_a(mouseX: Int, mouseY: Int, clickedMouseButton: Int, timeSinceLastClick: Long) = mouseClickMoveImpl()
    private fun mouseClickMoveImpl() = Unit

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) = mouseReleasedImpl(mouseX, mouseY, state)
    override fun func_146286_b(mouseX: Int, mouseY: Int, state: Int) = mouseReleasedImpl(mouseX, mouseY, state)

    private fun mouseReleasedImpl(mouseX: Int, mouseY: Int, state: Int) {
        val file = dragFile
        if (state == 0 && file != null) {
            val paneW = paneWidth()
            val rightX = 30 + paneW
            if (mouseX >= rightX && mouseX < rightX + paneW) {
                var target = dropIndex(mouseY, rightOffset, active.size)
                if (dragActive) {
                    val old = active.indexOf(file)
                    if (old >= 0) {
                        active.removeAt(old)
                        if (target > old) target--
                        target = max(0, min(target, active.size))
                        active.add(target, file)
                    }
                } else {
                    active.remove(file)
                    target = max(0, min(target, active.size))
                    active.add(target, file)
                }
                status = "Stack changed; press Apply"
            } else if (mouseX >= 15 && mouseX < 15 + paneW && dragActive) {
                active.remove(file)
                status = if (active.isEmpty()) "No shader selected; press Apply to use vanilla audio" else "Stack changed; press Apply"
            }
            dragFile = null
            leftOffset = min(leftOffset, maxLeftOffset())
            rightOffset = min(rightOffset, maxRightOffset())
            updateButtons()
        }
        super.func_146286_b(mouseX, mouseY, state)
    }

    private fun hit(mx: Int, my: Int): Hit? {
        val paneW = paneWidth()
        val y = 52 + 24
        val bottom = GuiCompat.height(this) - 34
        if (my < y || my >= bottom) return null
        if (mx >= 15 && mx < 15 + paneW) {
            val rows = available()
            val i = leftOffset + (my - y) / ROW_HEIGHT
            if (i in rows.indices && rows[i].valid()) return Hit(false, rows[i])
        }
        val rx = 30 + paneW
        if (mx >= rx && mx < rx + paneW) {
            val rows = activeEntries()
            val i = rightOffset + (my - y) / ROW_HEIGHT
            if (i in rows.indices) return Hit(true, rows[i])
        }
        return null
    }

    private fun dropIndex(mouseY: Int, offset: Int, size: Int): Int {
        val i = offset + (mouseY - (52 + 24) + ROW_HEIGHT / 2) / ROW_HEIGHT
        return max(0, min(i, size))
    }

    private fun paneWidth(): Int = max(160, (GuiCompat.width(this) - 45) / 2)
    private fun visibleRows(): Int = max(1, (GuiCompat.height(this) - 34 - (52 + 24) - 4) / ROW_HEIGHT)
    private fun maxLeftOffset(): Int = max(0, available().size - visibleRows())
    private fun maxRightOffset(): Int = max(0, active.size - visibleRows())

    private fun available(): List<LegacyShaderPackCatalog.Entry> = catalog.filterNot { active.contains(it.fileName()) }

    private fun activeEntries(): List<LegacyShaderPackCatalog.Entry> = active.map { file -> find(file) ?: missing(file) }

    private fun find(file: String): LegacyShaderPackCatalog.Entry? = catalog.firstOrNull { it.fileName() == file }

    private fun reloadCatalog() {
        val r = runtime
        if (r == null) {
            status = "Runtime is not initialized"
            return
        }
        try {
            catalog = LegacyShaderPackCatalog.scan(r.shaderpackDir())
        } catch (t: Throwable) {
            status = "Catalog error: ${shortMessage(t)}"
            catalog = emptyList()
        }
    }

    private fun effectivePack(): LoadedShaderPack {
        val order = ArrayList<LoadedShaderPack>()
        var i = active.size - 1
        while (i >= 0) {
            val e = find(active[i])
            if (e == null || !e.valid()) throw IllegalStateException("missing/invalid pack: ${active[i]}")
            order.add(requireNotNull(e.pack()))
            i--
        }
        return ShaderPackStackComposer.compose(order)
    }

    private fun updateButtons() {
        for (b in GuiCompat.buttons(this)) {
            when (GuiCompat.buttonId(b)) {
                ID_APPLY -> GuiCompat.setButtonEnabled(b, runtime != null)
                ID_OPTIONS -> GuiCompat.setButtonEnabled(b, runtime != null && active.isNotEmpty())
                ID_RUNTIME_AUDIO -> GuiCompat.setButtonEnabled(b, runtime != null)
                ID_LEFT_UP -> GuiCompat.setButtonEnabled(b, leftOffset > 0)
                ID_LEFT_DOWN -> GuiCompat.setButtonEnabled(b, leftOffset < maxLeftOffset())
                ID_RIGHT_UP -> GuiCompat.setButtonEnabled(b, rightOffset > 0)
                ID_RIGHT_DOWN -> GuiCompat.setButtonEnabled(b, rightOffset < maxRightOffset())
            }
        }
    }

    private fun trim(s: String, maxWidth: Int): String = GuiCompat.trimToWidth(this, s, max(20, maxWidth))

    fun previewPack(): LoadedShaderPack = effectivePack()
    internal fun editProfile(): String = profile
    internal fun setEditProfile(value: String) { profile = value }
    internal fun editOverrides(): MutableMap<String, String> = overrides
    internal fun editStack(): MutableList<String> = active
    internal fun optionsChanged() { status = "Options changed; press Apply"; initGuiImpl() }

    private data class Hit(val active: Boolean, val entry: LegacyShaderPackCatalog.Entry)

    private companion object {
        const val ID_DONE = 1
        const val ID_APPLY = 2
        const val ID_OPTIONS = 3
        const val ID_FOLDER = 4
        const val ID_RUNTIME_AUDIO = 5
        const val ID_LEFT_UP = 10
        const val ID_LEFT_DOWN = 11
        const val ID_RIGHT_UP = 12
        const val ID_RIGHT_DOWN = 13
        const val ROW_HEIGHT = 36

        fun missing(file: String): LegacyShaderPackCatalog.Entry =
            LegacyShaderPackCatalog.Entry(file, file, "Missing: $file", emptyList(), "file is missing", null)

        fun shortMessage(t: Throwable): String {
            var message = t.message ?: t.javaClass.simpleName
            if (message.length > 90) message = message.substring(0, 87) + "..."
            return message
        }
    }
}
