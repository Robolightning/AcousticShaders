package dev.acoustic.mc1122

import dev.acoustic.core.pack.LoadedShaderPack
import dev.acoustic.core.pack.ShaderPackLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Locale

/** Filesystem catalog used by the legacy GUI without depending on Minecraft classes. */
object LegacyShaderPackCatalog {
    class Entry(
        private val fileNameValue: String,
        private val idValue: String,
        private val nameValue: String,
        private val profilesValue: List<String>,
        private val errorValue: String?,
        private val packValue: LoadedShaderPack?
    ) {
        fun fileName() = fileNameValue
        fun id() = idValue
        fun name() = nameValue
        fun profiles(): List<String> = profilesValue
        fun valid() = errorValue == null
        fun error(): String? = errorValue
        fun pack(): LoadedShaderPack? = packValue
    }

    @JvmStatic
    @Throws(IOException::class)
    fun scan(dir: Path): List<Entry> {
        Files.createDirectories(dir)
        val out = ArrayList<Entry>()
        val loader = ShaderPackLoader()
        Files.newDirectoryStream(dir).use { stream ->
            for (path in stream) {
                if (!Files.isDirectory(path) && !path.fileName.toString().lowercase(Locale.ROOT).endsWith(".zip")) continue
                val file = path.fileName.toString()
                try {
                    val pack = if (Files.isDirectory(path)) loader.loadDirectory(path) else loader.loadZip(path)
                    out.add(Entry(file, pack.manifest().id(), pack.manifest().name(), Collections.unmodifiableList(ArrayList(pack.options().profiles())), null, pack))
                } catch (t: Throwable) {
                    out.add(Entry(file, file, file, emptyList(), shortMessage(t), null))
                }
            }
        }
        out.sortWith(Comparator { a, b -> val byName = a.name().compareTo(b.name(), ignoreCase = true); if (byName != 0) byName else a.fileName().compareTo(b.fileName(), ignoreCase = true) })
        return Collections.unmodifiableList(out)
    }

    private fun shortMessage(t: Throwable): String {
        var message = t.message
        if (message.isNullOrBlank()) message = t.javaClass.simpleName
        return if (message.length > 160) message.substring(0, 157) + "..." else message
    }
}
