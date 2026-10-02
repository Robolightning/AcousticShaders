package dev.acoustic.core.pack

import dev.acoustic.core.compat.lowercaseCompat
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipFile

/** Loads acoustic source JSON files from assets/<namespace>/acoustic_sources in ordinary Minecraft resource packs. */
class AcousticSourceResourceLoader {
    class Entry internal constructor(private val path: String, private val pack: SourceProfilePack) {
        fun path(): String = path
        fun pack(): SourceProfilePack = pack
    }

    @Throws(IOException::class)
    fun load(resourcePack: Path): List<Entry> = when {
        Files.isDirectory(resourcePack) -> loadDirectory(resourcePack)
        Files.isRegularFile(resourcePack) -> loadZip(resourcePack)
        else -> emptyList()
    }

    @Throws(IOException::class)
    private fun loadDirectory(root: Path): List<Entry> {
        val out = ArrayList<Entry>()
        val assets = root.resolve("assets")
        if (!Files.isDirectory(assets)) return out
        Files.newDirectoryStream(assets).use { namespaces ->
            for (namespace in namespaces) {
                val dir = namespace.resolve(DIRECTORY)
                if (Files.isDirectory(dir)) walk(root, dir, out)
            }
        }
        sort(out)
        return Collections.unmodifiableList(out)
    }

    @Throws(IOException::class)
    private fun loadZip(path: Path): List<Entry> {
        val out = ArrayList<Entry>()
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (entry.isDirectory || !name.endsWith(".json") || !isPath(name) || unsafe(name)) continue
                InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8).use { reader ->
                    out.add(Entry(name, SourceProfilePack.parse(read(reader))))
                }
            }
        }
        sort(out)
        return Collections.unmodifiableList(out)
    }

    companion object {
        const val DIRECTORY = "acoustic_sources"

        @Throws(IOException::class)
        private fun walk(root: Path, dir: Path, out: MutableList<Entry>) {
            Files.newDirectoryStream(dir).use { stream ->
                for (path in stream) {
                    if (Files.isDirectory(path)) walk(root, path, out)
                    else if (path.fileName.toString().lowercaseCompat(Locale.ROOT).endsWith(".json")) {
                        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                            out.add(Entry(root.relativize(path).toString().replace('\\', '/'), SourceProfilePack.parse(read(reader))))
                        }
                    }
                }
            }
        }
        private fun isPath(name: String): Boolean {
            if (!name.startsWith("assets/")) return false
            val slash = name.indexOf('/', 7)
            return slash >= 0 && name.substring(slash + 1).startsWith("$DIRECTORY/")
        }
        private fun unsafe(name: String): Boolean = name.contains("../") || name.startsWith("/") || name.contains("\\")
        @Throws(IOException::class)
        private fun read(reader: Reader): String {
            val builder = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) return builder.toString()
                builder.append(buffer, 0, count)
            }
        }
        private fun sort(out: MutableList<Entry>) { out.sortWith(Comparator { a, b -> a.path().compareTo(b.path()) }) }
    }
}
