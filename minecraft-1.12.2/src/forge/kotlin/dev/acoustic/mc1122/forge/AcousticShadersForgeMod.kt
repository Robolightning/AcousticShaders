package dev.acoustic.mc1122.forge

import dev.acoustic.core.compute.FdtdBackendRegistry
import dev.acoustic.core.compute.GeometricBackendRegistry
import dev.acoustic.mc1122.LegacyRuntimeConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreenOptionsSounds
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.event.FMLInitializationEvent
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import kotlin.math.max
import kotlin.math.min

@Mod(
    modid = AcousticShadersForgeMod.MODID,
    name = "Acoustic Shaders",
    version = AcousticShadersForgeMod.VERSION,
    clientSideOnly = true,
    acceptedMinecraftVersions = "[1.12.2]",
    guiFactory = "dev.acoustic.mc1122.forge.AcousticGuiFactory",
    dependencies = "required-after:mixinbooter;required-after:forgelin_continuous@[2.4.0.0,);"
)
class AcousticShadersForgeMod {
    private var runtime: LegacyClientRuntime? = null

    @Mod.EventHandler
    fun preInit(event: FMLPreInitializationEvent) {
        val root = event.modConfigurationDirectory.toPath().resolve("acousticshaders")
        val cudaDir = root.resolve("cuda")
        try {
            AcousticLog.setDebug(LegacyRuntimeConfig.loadOrCreate(root.resolve("runtime.properties")).debug())
        } catch (_: Throwable) {
            AcousticLog.setDebug(false)
        }
        try {
            Files.createDirectories(cudaDir)
        } catch (t: Throwable) {
            AcousticLog.warn("CUDA native directory unavailable: ${shortMessage(t)}")
        }

        try { AcousticLog.debug("CUDA candidates: ${CudaSupport.describeCandidates(cudaDir)}") }
        catch (t: Throwable) { AcousticLog.debug("CUDA candidate probe failed: ${shortMessage(t)}") }
        try {
            val cuda = CudaFdtdBackend(cudaDir)
            FdtdBackendRegistry.register(cuda)
            AcousticLog.debug("CUDA FDTD probe: ${cuda.description()}")
        } catch (t: Throwable) { AcousticLog.debug("optional CUDA FDTD backend unavailable: ${shortMessage(t)}") }
        try {
            val cudaRays = CudaGeometricBackend(cudaDir)
            GeometricBackendRegistry.register(cudaRays)
            AcousticLog.debug("CUDA ray probe: ${cudaRays.description()}")
        } catch (t: Throwable) { AcousticLog.debug("optional CUDA ray backend unavailable: ${shortMessage(t)}") }

        try { AcousticLog.debug("OpenCL GPU candidates: ${OpenClDeviceSelector.describeCandidates()}") }
        catch (t: Throwable) { AcousticLog.debug("OpenCL candidate probe failed: ${shortMessage(t)}") }
        try {
            val opencl = OpenClFdtdBackend()
            FdtdBackendRegistry.register(opencl)
            AcousticLog.debug("OpenCL FDTD probe: ${opencl.description()}")
        } catch (t: Throwable) { AcousticLog.debug("optional OpenCL FDTD backend unavailable: ${shortMessage(t)}") }
        try {
            val openclRays = OpenClGeometricBackend()
            GeometricBackendRegistry.register(openclRays)
            AcousticLog.debug("OpenCL ray probe: ${openclRays.description()}")
        } catch (t: Throwable) { AcousticLog.debug("optional OpenCL ray backend unavailable: ${shortMessage(t)}") }

        try {
            val packs = root.resolve("shaderpacks")
            Files.createDirectories(packs)
            installBuiltin(packs, LegacyRuntimeConfig.DEFAULT_PACK, "/assets/acousticshaders/shaderpacks/${LegacyRuntimeConfig.DEFAULT_PACK}")
            var gameDir = event.modConfigurationDirectory.toPath().parent
            if (gameDir == null) gameDir = event.modConfigurationDirectory.toPath()
            val created = LegacyClientRuntime(root, gameDir)
            runtime = created
            ACTIVE_RUNTIME = created
        } catch (t: Throwable) {
            AcousticLog.error("initialization failed", t)
        }
    }

    @Mod.EventHandler
    fun init(event: FMLInitializationEvent) {
        MinecraftForge.EVENT_BUS.register(this)
        AcousticLog.info("initialized for Minecraft 1.12.2; debug=${AcousticLog.debugEnabled()}")
    }

    @Mod.EventHandler
    fun postInit(event: FMLPostInitializationEvent) {
        runtime?.refreshGeneratedMaterialDatabase()
    }

    @SubscribeEvent
    fun clientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END) runtime?.tick()
    }

    @SubscribeEvent
    fun onSoundOptionsInit(event: GuiScreenEvent.InitGuiEvent.Post) {
        if (event.gui !is GuiScreenOptionsSounds) return
        for (button in event.buttonList) if (GuiCompat.buttonId(button) == SOUND_OPTIONS_BUTTON) return

        val screenWidth = GuiCompat.width(event.gui)
        val screenHeight = GuiCompat.height(event.gui)
        var subtitles: GuiButton? = null
        var done: GuiButton? = null
        var contentBottom = 0
        for (button in event.buttonList) {
            when (GuiCompat.buttonId(button)) {
                201 -> subtitles = button
                200 -> done = button
                else -> contentBottom = max(contentBottom, GuiCompat.buttonY(button) + GuiCompat.buttonHeight(button))
            }
        }

        var firstY = contentBottom + 4
        val required = 20 + 4 + 20 + 4 + 20
        if (firstY + required > screenHeight - 2) firstY = max(2, screenHeight - 2 - required)
        val subtitlesY = firstY
        val acousticY = subtitlesY + 24
        val doneY = acousticY + 24
        subtitles?.let { GuiCompat.setButtonPosition(it, (screenWidth - GuiCompat.buttonWidth(it)) / 2, subtitlesY) }
        done?.let { GuiCompat.setButtonPosition(it, (screenWidth - GuiCompat.buttonWidth(it)) / 2, doneY) }

        val w = min(200, max(150, screenWidth - 80))
        event.buttonList.add(GuiButton(SOUND_OPTIONS_BUTTON, (screenWidth - w) / 2, acousticY, w, 20, "Acoustic Shaders..."))
    }

    @SubscribeEvent
    fun onSoundOptionsAction(event: GuiScreenEvent.ActionPerformedEvent.Pre) {
        if (event.gui !is GuiScreenOptionsSounds || GuiCompat.buttonId(event.button) != SOUND_OPTIONS_BUTTON) return
        event.setCanceled(true)
        GuiCompat.display(event.gui, GuiAcousticShaders(event.gui, runtime))
    }

    private fun installBuiltin(dir: Path, name: String, resource: String) {
        val target = dir.resolve(name)
        if (Files.isRegularFile(target)) return
        val input = AcousticShadersForgeMod::class.java.getResourceAsStream(resource)
            ?: throw IOException("missing built-in shaderpack $resource")
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        input.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MODID = "acousticshaders"
        const val VERSION = "0.3.0-rc19"
        private const val SOUND_OPTIONS_BUTTON = 0xAC51
        @Volatile private var ACTIVE_RUNTIME: LegacyClientRuntime? = null

        @JvmStatic @JvmName("runtime") internal fun runtime(): LegacyClientRuntime = requireNotNull(ACTIVE_RUNTIME) { "Acoustic Shaders runtime is not initialized" }
        private fun shortMessage(t: Throwable): String = t.message ?: t.javaClass.simpleName
    }
}
