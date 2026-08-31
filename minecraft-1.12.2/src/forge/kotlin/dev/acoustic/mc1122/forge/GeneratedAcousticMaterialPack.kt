package dev.acoustic.mc1122.forge

import dev.acoustic.core.material.infer.GeneratedMaterialPackWriter
import dev.acoustic.core.material.infer.MaterialInferenceEngine
import dev.acoustic.core.pack.StrictJson
import dev.acoustic.core.source.profile.DefaultSourceProfiles
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties

/** Persistent, auto-regenerated lowest-priority acoustic material/source resource pack. */
internal class GeneratedAcousticMaterialPack(private val gameDir: Path) {
    private val packDir = gameDir.resolve("resourcepacks").resolve(PACK_NAME)

    fun path(): Path = packDir

    @Synchronized
    @Throws(IOException::class)
    fun ensureUpToDate(): Result {
        val started = System.nanoTime()
        ensureSkeleton()
        val fingerprint = modSetFingerprint()
        val material = packDir.resolve(MATERIAL_PATH)
        val meta = packDir.resolve(META)
        val old = load(meta)
        val oldFingerprint = old.getProperty("fingerprint", "")
        val oldSchema = parseInt(old.getProperty("schema"), -1)
        if (Files.isRegularFile(material) && fingerprint == oldFingerprint && oldSchema == MaterialInferenceEngine.SCHEMA_VERSION) {
            return Result(false, parseInt(old.getProperty("states"), -1), fingerprint, packDir, elapsedMillis(started))
        }

        val facts = ForgeBlockAcousticIntrospector.INSTANCE.enumerateAll()
        if (facts.isEmpty()) {
            if (Files.isRegularFile(material)) return Result(false, parseInt(old.getProperty("states"), -1), oldFingerprint, packDir, elapsedMillis(started))
            throw IOException("Minecraft block registry enumeration produced no states; generated material database not replaced")
        }
        val engine = MaterialInferenceEngine()
        val rows = ArrayList<GeneratedMaterialPackWriter.Row>(facts.size)
        for (fact in facts) rows.add(GeneratedMaterialPackWriter.Row(fact, engine.infer(fact)))
        val json = GeneratedMaterialPackWriter().write(rows, fingerprint)
        atomicWrite(material, json.toByteArray(StandardCharsets.UTF_8))
        val properties = Properties()
        properties.setProperty("schema", MaterialInferenceEngine.SCHEMA_VERSION.toString())
        properties.setProperty("fingerprint", fingerprint)
        properties.setProperty("states", rows.size.toString())
        properties.setProperty("minecraft", "1.12.2")
        atomicProperties(meta, properties)
        writePackMcmeta(rows.size)
        return Result(true, rows.size, fingerprint, packDir, elapsedMillis(started))
    }

    @Throws(IOException::class)
    fun ensureSkeleton() {
        cleanupLegacyGeneratedPack()
        Files.createDirectories(packDir.resolve("assets/acousticshaders/acoustic_materials"))
        Files.createDirectories(packDir.resolve("assets/acousticshaders/acoustic_sources"))
        val source = packDir.resolve(SOURCE_PATH)
        val sourceBytes = DefaultSourceProfiles.json().toByteArray(StandardCharsets.UTF_8)
        if (!Files.isRegularFile(source) || !Files.readAllBytes(source).contentEquals(sourceBytes)) atomicWrite(source, sourceBytes)
        val readme = packDir.resolve("README-AcousticShaders.txt")
        val readmeText = "Acoustic Shaders Default Materials\r\n" +
            "This resource pack is generated and maintained automatically by Acoustic Shaders.\r\n" +
            "It is kept selected in Minecraft and is ALWAYS used as the lowest-priority acoustic material/source database.\r\n" +
            "Do not edit generated files. Put block overrides under assets/<namespace>/acoustic_materials/*.json and source/event overrides under assets/<namespace>/acoustic_sources/*.json in your own resource pack.\r\n"
        val readmeBytes = readmeText.toByteArray(StandardCharsets.UTF_8)
        if (!Files.isRegularFile(readme) || !Files.readAllBytes(readme).contentEquals(readmeBytes)) atomicWrite(readme, readmeBytes)
        installIcon()
        if (!Files.isRegularFile(packDir.resolve("pack.mcmeta"))) writePackMcmeta(-1)
    }

    private fun writePackMcmeta(states: Int) {
        val suffix = if (states >= 0) " ($states states)" else ""
        val text = "{\n  \"pack\": {\n    \"pack_format\": 3,\n    \"description\": \"Default acoustic material database$suffix\"\n  }\n}\n"
        atomicWrite(packDir.resolve("pack.mcmeta"), text.toByteArray(StandardCharsets.UTF_8))
    }

    @Synchronized
    fun ensureVanillaSelected() {
        var minecraft: Any? = null
        try {
            minecraft = ForgeReflection.invoke(ForgeReflection.type("net.minecraft.client.Minecraft"), arrayOf("getMinecraft", "func_71410_x"))
            val liveMinecraft = requireNotNull(minecraft)
            val settings = requireNotNull(ForgeReflection.field(liveMinecraft, "gameSettings", "field_71474_y"))
            val raw = ForgeReflection.field(settings, "resourcePacks", "field_151453_l")
            if (raw is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val packs = raw as MutableList<String>
                if (normalizeSelectedNames(packs)) ForgeReflection.invoke(settings, arrayOf("saveOptions", "func_74303_b"))
            }
        } catch (t: Throwable) {
            AcousticLog.warn("could not repair in-memory GameSettings resource-pack selection: ${shortMessage(t)}")
        }
        try { ensureOptionsFileSelected() }
        catch (t: Throwable) { AcousticLog.warn("could not persist generated resource-pack selection: ${shortMessage(t)}") }
        if (minecraft != null) {
            try { repairLiveRepository(minecraft) }
            catch (t: Throwable) { AcousticLog.warn("could not repair live ResourcePackRepository selection: ${shortMessage(t)}") }
        }
    }

    private fun repairLiveRepository(minecraft: Any) {
        val repo = ForgeReflection.invoke(minecraft, arrayOf("getResourcePackRepository", "func_110438_M")) ?: return
        ForgeReflection.invoke(repo, arrayOf("updateRepositoryEntriesAll", "func_110611_a"))
        val all = ForgeReflection.invoke(repo, arrayOf("getRepositoryEntriesAll", "func_110609_b")) as? List<*> ?: return
        val selected = ForgeReflection.invoke(repo, arrayOf("getRepositoryEntries", "func_110613_c")) as? List<*> ?: return
        val next = ArrayList<Any>(selected.size + 1)
        var generated: Any? = null
        var changed = false
        for (entry in selected) {
            entry ?: continue
            val name = repositoryEntryName(entry)
            if (isLegacyPackName(name)) { changed = true; continue }
            if (isCurrentPackName(name)) {
                if (generated == null) { generated = entry; next.add(entry) } else changed = true
                continue
            }
            next.add(entry)
        }
        if (generated == null) {
            generated = all.firstOrNull { it != null && isCurrentPackName(repositoryEntryName(it)) }
            if (generated != null) { next.add(generated); changed = true }
        }
        if (!changed) return
        ForgeReflection.invoke(repo, arrayOf("setRepositories", "func_148527_a"), next)
        ForgeReflection.invoke(minecraft, arrayOf("refreshResources", "func_110436_a"))
        AcousticLog.debug("repaired live ResourcePackRepository selection: removed legacy generated pack and selected '$PACK_NAME'")
    }

    @Throws(IOException::class)
    private fun ensureOptionsFileSelected() {
        val options = gameDir.resolve("options.txt")
        if (!Files.isRegularFile(options)) return
        val lines = Files.readAllLines(options, StandardCharsets.UTF_8).toMutableList()
        var found = false
        var changed = false
        for (i in lines.indices) {
            if (!lines[i].startsWith("resourcePacks:")) continue
            found = true
            val payload = lines[i].substring("resourcePacks:".length)
            val packs = ArrayList<String>()
            try {
                val parsed = StrictJson.parse(payload)
                if (parsed is List<*>) packs.addAll(parsed.filterIsInstance<String>())
            } catch (_: Throwable) { }
            if (normalizeSelectedNames(packs)) {
                lines[i] = "resourcePacks:${jsonArray(packs)}"
                changed = true
            }
            break
        }
        if (!found) {
            lines.add("resourcePacks:${jsonArray(listOf(PACK_NAME))}")
            changed = true
        }
        if (changed) {
            val tmp = options.resolveSibling(options.fileName.toString() + ".acoustic.tmp")
            Files.write(tmp, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            move(tmp, options)
        }
    }

    private fun installIcon() {
        val target = packDir.resolve("pack.png")
        val input = GeneratedAcousticMaterialPack::class.java.getResourceAsStream("/assets/acousticshaders/generated_pack/pack.png") ?: return
        input.use {
            val tmp = target.resolveSibling("pack.png.tmp")
            Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING)
            move(tmp, target)
        }
    }

    private fun cleanupLegacyGeneratedPack() {
        val old = gameDir.resolve("resourcepacks").resolve(LEGACY_PACK_NAME)
        if (old == packDir || !Files.isDirectory(old) || !Files.isRegularFile(old.resolve(META))) return
        try { deleteTree(old) } catch (_: IOException) { }
    }

    @Throws(IOException::class)
    private fun modSetFingerprint(): String {
        val digest = try { MessageDigest.getInstance("SHA-256") } catch (e: Exception) { throw IOException(e) }
        update(digest, "acoustic-material-inference-schema=${MaterialInferenceEngine.SCHEMA_VERSION}\nsource-profile-schema=${DefaultSourceProfiles.SCHEMA_VERSION}\nmc=1.12.2\n")
        val mods = gameDir.resolve("mods")
        val files = ArrayList<Path>()
        if (Files.isDirectory(mods)) collect(mods, mods, files, 0)
        files.sortBy { mods.relativize(it).toString() }
        for (file in files) {
            val rel = mods.relativize(file).toString().replace('\\', '/')
            update(digest, "$rel\t${Files.size(file)}\t${Files.getLastModifiedTime(file).toMillis()}\n")
        }
        return hex(digest.digest())
    }

    internal class Result(
        @JvmField val rebuilt: Boolean,
        @JvmField val states: Int,
        @JvmField val fingerprint: String,
        @JvmField val path: Path,
        @JvmField val elapsedMillis: Double
    )

    companion object {
        const val PACK_NAME = "Acoustic Shaders Default Materials"
        const val LEGACY_PACK_NAME = "AcousticShaders-Generated-Materials"
        const val MATERIAL_PATH = "assets/acousticshaders/acoustic_materials/generated.json"
        const val SOURCE_PATH = "assets/acousticshaders/acoustic_sources/generated.json"
        private const val META = ".acousticshaders-generated.properties"

        private fun repositoryEntryName(entry: Any): String = try {
            ForgeReflection.invoke(entry, arrayOf("getResourcePackName", "func_110515_d"))?.toString() ?: ""
        } catch (_: Throwable) { entry.toString() }

        private fun normalizeSelectedNames(packs: MutableList<String>): Boolean {
            var changed = removeLegacyPack(packs)
            var seen = false
            var i = packs.size - 1
            while (i >= 0) {
                if (isCurrentPackName(packs[i])) {
                    if (!seen) seen = true else { packs.removeAt(i); changed = true }
                }
                i--
            }
            if (!seen) { packs.add(PACK_NAME); changed = true }
            return changed
        }

        private fun removeLegacyPack(packs: MutableList<String>): Boolean {
            var changed = false
            var i = packs.size - 1
            while (i >= 0) {
                if (isLegacyPackName(packs[i])) { packs.removeAt(i); changed = true }
                i--
            }
            return changed
        }

        private fun isCurrentPackName(raw: String?): Boolean = stripFilePrefix(raw) == PACK_NAME
        private fun isLegacyPackName(raw: String?): Boolean {
            val p = stripFilePrefix(raw)
            return p == LEGACY_PACK_NAME || p.startsWith("$LEGACY_PACK_NAME-") || (p.startsWith("$LEGACY_PACK_NAME ") && p.contains(PACK_NAME))
        }
        private fun stripFilePrefix(raw: String?): String {
            val p = raw?.trim() ?: return ""
            return if (p.startsWith("file/")) p.substring(5) else p
        }
        private fun shortMessage(t: Throwable): String = t.message ?: t.javaClass.simpleName
        private fun jsonArray(values: List<String>): String = buildString {
            append('[')
            values.forEachIndexed { index, value ->
                if (index > 0) append(',')
                append('"')
                for (c in value) { if (c == '"' || c == '\\') append('\\'); append(c) }
                append('"')
            }
            append(']')
        }
        @Throws(IOException::class)
        private fun deleteTree(path: Path) {
            if (Files.isDirectory(path)) Files.newDirectoryStream(path).use { stream -> for (child in stream) deleteTree(child) }
            Files.deleteIfExists(path)
        }
        @Throws(IOException::class)
        private fun collect(root: Path, dir: Path, out: MutableList<Path>, depth: Int) {
            if (depth > 2) return
            Files.newDirectoryStream(dir).use { stream ->
                for (path in stream) {
                    if (Files.isDirectory(path)) collect(root, path, out, depth + 1)
                    else {
                        val n = path.fileName.toString().lowercase(java.util.Locale.ROOT)
                        if (n.endsWith(".jar") || n.endsWith(".zip")) out.add(path)
                    }
                }
            }
        }
        private fun update(digest: MessageDigest, value: String) = digest.update(value.toByteArray(StandardCharsets.UTF_8))
        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) { for (b in bytes) append(String.format(java.util.Locale.ROOT, "%02x", b.toInt() and 255)) }
        private fun load(path: Path): Properties {
            val properties = Properties()
            if (!Files.isRegularFile(path)) return properties
            try { Files.newInputStream(path).use { properties.load(it) } } catch (_: IOException) { }
            return properties
        }
        private fun parseInt(value: String?, fallback: Int): Int = value?.toIntOrNull() ?: fallback
        @Throws(IOException::class)
        private fun atomicWrite(target: Path, bytes: ByteArray) {
            Files.createDirectories(target.parent)
            val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
            Files.write(tmp, bytes)
            move(tmp, target)
        }
        @Throws(IOException::class)
        private fun atomicProperties(target: Path, properties: Properties) {
            Files.createDirectories(target.parent)
            val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
            Files.newOutputStream(tmp).use { properties.store(it, "Acoustic Shaders generated material database cache") }
            move(tmp, target)
        }
        @Throws(IOException::class)
        private fun move(tmp: Path, target: Path) {
            try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
        }
        private fun elapsedMillis(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0
    }
}
