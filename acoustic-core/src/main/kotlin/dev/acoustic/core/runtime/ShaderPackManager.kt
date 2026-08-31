package dev.acoustic.core.runtime

import dev.acoustic.core.pack.LoadedShaderPack
import dev.acoustic.core.pack.ShaderPackLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Atomic pack publication for safe UI/hot-reload integration. */
class ShaderPackManager {
    private val active = AtomicReference<LoadedShaderPack?>()
    private val loader = ShaderPackLoader()
    fun active(): LoadedShaderPack? = active.get()
    @Throws(IOException::class) fun loadAndActivate(path: Path): LoadedShaderPack { val pack = if (Files.isDirectory(path)) loader.loadDirectory(path) else loader.loadZip(path); active.set(pack); return pack }
    fun activate(pack: LoadedShaderPack) { active.set(pack) }
}
