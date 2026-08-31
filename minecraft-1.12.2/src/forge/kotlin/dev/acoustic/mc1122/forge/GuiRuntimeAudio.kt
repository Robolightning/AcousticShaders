package dev.acoustic.mc1122.forge

import java.io.IOException
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import kotlin.math.max
import kotlin.math.min

/** System/runtime limits intentionally separated from shader-defined acoustic options. */
internal class GuiRuntimeAudio(private val parent: GuiScreen, private val runtime: LegacyClientRuntime) : GuiScreen() {
    private var value = runtime.legacyAudioConfig()
    private var status = ""

    override fun initGui() = initGuiImpl()
    override fun func_73866_w_() = initGuiImpl()

    private fun initGuiImpl() {
        GuiCompat.clearButtons(this)
        val width = GuiCompat.width(this); val height = GuiCompat.height(this)
        add(GuiButton(ID_TOGGLE, width / 2 - 90, 30, 180, 20, "Software wet: ${if (value.softwareWetEnabled) "ON" else "OFF"}"))
        addPair(ID_THREADS, 58, "Renderer threads", value.rendererThreads.toString())
        addPair(ID_PENDING, 84, "Pending jobs", value.maxPendingJobs.toString())
        addPair(ID_FFT, 110, "FFT block", value.fftBlockSize.toString())
        addPair(ID_IR, 136, "Max RIR", "${format(value.maxIrSeconds)} s")
        addPair(ID_GAIN, 162, "Wet gain", format(value.wetGain.toDouble()))
        if (height >= 270) {
            addPair(ID_VOICES, 188, "Wet voices", value.maxWetVoices.toString())
            addPair(ID_PCM, 214, "PCM limit", "${value.maxPcmBytes / 1048576} MiB")
        }
        add(GuiButton(ID_SAVE, width / 2 - 100, height - 27, 96, 20, "Apply"))
        add(GuiButton(ID_DONE, width / 2 + 4, height - 27, 96, 20, "Done"))
    }

    private fun addPair(id: Int, y: Int, label: String, current: String) {
        val width = GuiCompat.width(this)
        add(GuiButton(id, width / 2 + 18, y, 36, 20, "-"))
        add(GuiButton(id + 1, width / 2 + 58, y, 72, 20, current))
        add(GuiButton(id + 2, width / 2 + 134, y, 36, 20, "+"))
    }

    private fun <T : GuiButton> add(button: T): T = GuiCompat.addButton(this, button)

    @Throws(IOException::class)
    override fun actionPerformed(button: GuiButton) = actionPerformedImpl(button)
    @Throws(IOException::class)
    override fun func_146284_a(button: GuiButton) = actionPerformedImpl(button)

    private fun actionPerformedImpl(button: GuiButton) {
        val id = GuiCompat.buttonId(button)
        when (id) {
            ID_DONE -> { GuiCompat.display(this, parent); return }
            ID_SAVE -> {
                try { runtime.applyLegacyAudioConfig(value); status = "Applied" }
                catch (t: Throwable) { status = "Not applied: ${t.message ?: t.javaClass.simpleName}" }
                initGuiImpl(); return
            }
            ID_TOGGLE -> { value = value.copy(softwareWetEnabled = !value.softwareWetEnabled); initGuiImpl(); return }
        }
        val base = id - (id % 10)
        val delta = when (id % 10) { 0 -> -1; 2 -> 1; else -> 0 }
        if (delta == 0) return
        value = when (base) {
            ID_THREADS -> value.copy(rendererThreads = clamp(value.rendererThreads + delta, 1, 8))
            ID_PENDING -> value.copy(maxPendingJobs = clamp(value.maxPendingJobs + delta, 1, 64))
            ID_FFT -> value.copy(fftBlockSize = clampPow2(value.fftBlockSize, delta, 32, 2048))
            ID_IR -> value.copy(maxIrSeconds = (value.maxIrSeconds + delta * 0.25).coerceIn(0.05, 8.0))
            ID_GAIN -> value.copy(wetGain = (value.wetGain + delta * 0.05f).coerceIn(0f, 4f))
            ID_VOICES -> value.copy(maxWetVoices = clamp(value.maxWetVoices + delta, 1, 64))
            ID_PCM -> value.copy(maxPcmBytes = clamp(value.maxPcmBytes / 1048576 + delta, 1, 64) * 1048576)
            else -> value
        }
        initGuiImpl()
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) = drawScreenImpl(mouseX, mouseY, partialTicks)
    override fun func_73863_a(mouseX: Int, mouseY: Int, partialTicks: Float) = drawScreenImpl(mouseX, mouseY, partialTicks)

    private fun drawScreenImpl(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val width = GuiCompat.width(this); val height = GuiCompat.height(this)
        GuiCompat.drawDefaultBackground(this)
        GuiCompat.drawCentered(this, "Runtime & Audio", width / 2, 8, 0xFFFFFF)
        GuiCompat.drawCentered(this, "System limits; shader physics options live on the previous screen.", width / 2, 18, 0x999999)
        val labels = arrayOf("Renderer threads", "Pending jobs", "FFT block", "Max RIR", "Wet gain", "Wet voices", "PCM limit")
        val ys = intArrayOf(64, 90, 116, 142, 168, 194, 220)
        val visible = if (height >= 270) labels.size else 5
        for (i in 0 until visible) GuiCompat.drawString(this, labels[i], width / 2 - 156, ys[i], 0xFFFFFF)
        if (!value.softwareWetEnabled) GuiCompat.drawCentered(this, "Safe default: software wet is OFF until hardware/client validation.", width / 2, height - 44, 0xFFCC66)
        if (status.isNotEmpty()) GuiCompat.drawCentered(this, status, width / 2, height - 54, if (status.startsWith("Not")) 0xFF7777 else 0x66FF66)
        super.func_73863_a(mouseX, mouseY, partialTicks)
    }

    private companion object {
        const val ID_DONE = 1; const val ID_SAVE = 2; const val ID_TOGGLE = 3
        const val ID_THREADS = 100; const val ID_PENDING = 110; const val ID_FFT = 120; const val ID_IR = 130
        const val ID_GAIN = 140; const val ID_VOICES = 150; const val ID_PCM = 160
        fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))
        fun clampPow2(v: Int, delta: Int, lo: Int, hi: Int): Int = if (delta < 0) max(lo, v / 2) else min(hi, v * 2)
        fun format(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}
