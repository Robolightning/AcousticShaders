package dev.acoustic.core.pack

import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.zip.ZipFile

/** Filesystem loader for unpacked development packs and zip transport. */
class ShaderPackLoader {
    @Throws(IOException::class)
    fun loadDirectory(root: Path): LoadedShaderPack {
        if (!Files.isDirectory(root)) throw IllegalArgumentException("shader pack root must be a directory: $root")
        val manifest = ShaderPackManifest.parse(readUtf8(required(root, "manifest.json")))
        val pipeline = PipelineDefinition.parse(readUtf8(required(root, "pipeline.json")))
        val properties = root.resolve("acoustic.properties")
        val options = if (Files.isRegularFile(properties)) Files.newBufferedReader(properties, StandardCharsets.UTF_8).use { PackOptions.load(it) } else PackOptions.load(StringReader(""))
        val materials = LinkedHashMap<String, MaterialPack>()
        val dir = root.resolve("materials")
        if (Files.isDirectory(dir)) {
            Files.newDirectoryStream(dir, "*.json").use { stream ->
                for (path in stream) materials[path.fileName.toString()] = MaterialPack.parse(readUtf8(path))
            }
        }
        val media = LinkedHashMap<String, MediumPack>()
        val mediaDir = root.resolve("media")
        if (Files.isDirectory(mediaDir)) {
            Files.newDirectoryStream(mediaDir, "*.json").use { stream ->
                for (path in stream) media[path.fileName.toString()] = MediumPack.parse(readUtf8(path))
            }
        }
        return LoadedShaderPack(manifest, pipeline, options, materials, media)
    }

    @Throws(IOException::class)
    fun loadZip(zipPath: Path): LoadedShaderPack {
        if (!Files.isRegularFile(zipPath)) throw IllegalArgumentException("shader pack zip must be a file: $zipPath")
        ZipFile(zipPath.toFile()).use { zip ->
            val manifest = ShaderPackManifest.parse(readZipUtf8(zip, "manifest.json", true)!!)
            val pipeline = PipelineDefinition.parse(readZipUtf8(zip, "pipeline.json", true)!!)
            val properties = readZipUtf8(zip, "acoustic.properties", false)
            val options = PackOptions.load(StringReader(properties ?: ""))
            val materials = LinkedHashMap<String, MaterialPack>()
            val media = LinkedHashMap<String, MediumPack>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!entry.isDirectory && name.startsWith("materials/") && name.endsWith(".json") && name.indexOf("..") < 0) {
                    materials[name.substring("materials/".length)] = MaterialPack.parse(readZipUtf8(zip, name, true)!!)
                } else if (!entry.isDirectory && name.startsWith("media/") && name.endsWith(".json") && name.indexOf("..") < 0) {
                    media[name.substring("media/".length)] = MediumPack.parse(readZipUtf8(zip, name, true)!!)
                }
            }
            return LoadedShaderPack(manifest, pipeline, options, materials, media)
        }
    }

    @Throws(IOException::class)
    private fun readZipUtf8(zip: ZipFile, name: String, required: Boolean): String? {
        val entry = zip.getEntry(name)
        if (entry == null) {
            if (required) throw IllegalArgumentException("missing required shader pack file: $name")
            return null
        }
        if (entry.isDirectory) throw IllegalArgumentException("shader pack entry is directory: $name")
        InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8).use { return read(it) }
    }

    private fun required(root: Path, name: String): Path {
        val path = root.resolve(name)
        if (!Files.isRegularFile(path)) throw IllegalArgumentException("missing required shader pack file: $name")
        return path
    }
    @Throws(IOException::class)
    private fun readUtf8(path: Path): String = Files.newBufferedReader(path, StandardCharsets.UTF_8).use { read(it) }
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
}
