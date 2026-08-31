package dev.acoustic.mc1122.forge

import dev.acoustic.api.material.resolve.MaterialRule
import dev.acoustic.api.environment.resolve.MediumRule
import dev.acoustic.api.source.AcousticSourceProfile
import dev.acoustic.api.source.SourceProfileRule
import dev.acoustic.core.material.MaterialRuleLayers
import dev.acoustic.core.medium.MediumRuleLayers
import dev.acoustic.core.pack.AcousticMaterialResourceLoader
import dev.acoustic.core.pack.AcousticMediumResourceLoader
import dev.acoustic.core.pack.AcousticSourceResourceLoader
import dev.acoustic.core.pack.StrictJson
import dev.acoustic.core.source.profile.SourceProfileResolver
import dev.acoustic.core.source.profile.SourceProfileRuleLayers
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.name

/** Material/source database stack for Minecraft 1.12.2 resource packs. */
internal class LegacyMaterialDatabaseManager(private val gameDir: Path) {
    private val optionsFile = gameDir.resolve("options.txt")
    private val resourcepacksDir = gameDir.resolve("resourcepacks")
    private val generated = GeneratedAcousticMaterialPack(gameDir)
    private val loader = AcousticMaterialResourceLoader()
    private val mediumLoader = AcousticMediumResourceLoader()
    private val sourceLoader = AcousticSourceResourceLoader()
    @Volatile private var currentSnapshot = Snapshot.EMPTY
    @Volatile private var lastPollSignature = Long.MIN_VALUE

    @Throws(IOException::class)
    fun ensureSkeleton() = generated.ensureSkeleton()

    @Synchronized
    @Throws(IOException::class)
    fun initializeOrRefreshGenerated(): Snapshot {
        val result = generated.ensureUpToDate()
        generated.ensureVanillaSelected()
        val snapshot = loadAll()
        currentSnapshot = snapshot
        AcousticLog.info("default acoustic database ${if (result.rebuilt) "rebuilt" else "cache hit"}: states=${result.states}")
        AcousticLog.debug("default acoustic database ms=${String.format(java.util.Locale.ROOT, "%.2f", result.elapsedMillis)} path=${result.path} layers=${snapshot.sources}")
        lastPollSignature = signature()
        return snapshot
    }

    @Synchronized
    @Throws(IOException::class)
    fun loadCached(): Snapshot {
        generated.ensureSkeleton()
        generated.ensureVanillaSelected()
        val snapshot = loadAll()
        currentSnapshot = snapshot
        lastPollSignature = signature()
        return snapshot
    }

    fun current(): Snapshot = currentSnapshot

    @Synchronized
    @Throws(IOException::class)
    fun pollResourcePackChanges(): Snapshot? {
        val sig = signature()
        if (sig == lastPollSignature) return null
        val snapshot = loadAll()
        currentSnapshot = snapshot
        lastPollSignature = sig
        AcousticLog.debug("acoustic resource-data overlays reloaded: ${snapshot.sources}")
        return snapshot
    }

    @Throws(IOException::class)
    private fun loadAll(): Snapshot {
        val rules = ArrayList<MaterialRule>()
        val mediumRules = ArrayList<MediumRule>()
        val sourceRules = ArrayList<SourceProfileRule>()
        val sources = ArrayList<String>()
        var count = 0
        var mediumCount = 0
        var sourceCount = 0

        val base = loader.load(generated.path())
        for (entry in base) {
            rules.addAll(MaterialRuleLayers.rebase(entry.pack(), GENERATED_BASE))
            count++
        }
        val baseMedia = mediumLoader.load(generated.path())
        for (entry in baseMedia) {
            mediumRules.addAll(MediumRuleLayers.rebase(entry.pack(), GENERATED_BASE))
            mediumCount++
        }
        val baseSources = sourceLoader.load(generated.path())
        for (entry in baseSources) {
            sourceRules.addAll(SourceProfileRuleLayers.rebase(entry.pack().rules(), GENERATED_BASE))
            sourceCount++
        }
        sources.add("generated:${GeneratedAcousticMaterialPack.PACK_NAME}[materials=${base.size},media=${baseMedia.size},sources=${baseSources.size}]")

        var layer = 0
        for (name in activeResourcePacks()) {
            val path = resolveResourcePack(name) ?: continue
            if (path.normalize() == generated.path().normalize()) continue
            val entries: List<AcousticMaterialResourceLoader.Entry>
            val mediumEntries: List<AcousticMediumResourceLoader.Entry>
            val sourceEntries: List<AcousticSourceResourceLoader.Entry>
            try {
                entries = loader.load(path)
                mediumEntries = mediumLoader.load(path)
                sourceEntries = sourceLoader.load(path)
            } catch (t: Throwable) {
                AcousticLog.warn("acoustic resource pack ignored $name: $t")
                continue
            }
            if (entries.isEmpty() && mediumEntries.isEmpty() && sourceEntries.isEmpty()) continue
            val basePriority = RESOURCE_BASE + layer++ * 10_000
            for (entry in entries) {
                rules.addAll(MaterialRuleLayers.rebase(entry.pack(), basePriority))
                count++
            }
            for (entry in mediumEntries) {
                mediumRules.addAll(MediumRuleLayers.rebase(entry.pack(), basePriority))
                mediumCount++
            }
            for (entry in sourceEntries) {
                sourceRules.addAll(SourceProfileRuleLayers.rebase(entry.pack().rules(), basePriority))
                sourceCount++
            }
            sources.add("resource:$name[materials=${entries.size},media=${mediumEntries.size},sources=${sourceEntries.size}]")
        }
        return Snapshot(
            Collections.unmodifiableList(rules),
            Collections.unmodifiableList(mediumRules),
            SourceProfileResolver(sourceRules, AcousticSourceProfile.GENERIC),
            Collections.unmodifiableList(sources),
            count,
            mediumCount,
            sourceCount
        )
    }

    private fun activeResourcePacks(): List<String> {
        if (!Files.isRegularFile(optionsFile)) return emptyList()
        return try {
            Files.newBufferedReader(optionsFile, StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("resourcePacks:")) continue
                    val value = StrictJson.parse(line.substring("resourcePacks:".length))
                    if (value !is List<*>) return emptyList()
                    return value.filterIsInstance<String>()
                }
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun resolveResourcePack(raw: String?): Path? {
        if (raw == null) return null
        var name = raw.trim()
        if (name.isEmpty() || name == "vanilla" || name == "mod_resources") return null
        if (name.startsWith("file/")) name = name.substring(5)
        if ('\\' in name || ".." in name) return null
        val path = resourcepacksDir.resolve(name).normalize()
        return if (path.startsWith(resourcepacksDir.normalize())) path else null
    }

    private fun signature(): Long {
        var h = FNV_OFFSET
        try {
            if (Files.isRegularFile(optionsFile)) {
                h = mix(h, Files.getLastModifiedTime(optionsFile).toMillis())
                h = mix(h, Files.size(optionsFile))
            }
            for (name in activeResourcePacks()) {
                val path = resolveResourcePack(name) ?: continue
                h = mix(h, name.hashCode().toLong())
                h = resourcePackSignature(h, path)
            }
            val generatedJson = generated.path().resolve(GeneratedAcousticMaterialPack.MATERIAL_PATH)
            if (Files.isRegularFile(generatedJson)) {
                h = mix(h, Files.getLastModifiedTime(generatedJson).toMillis())
                h = mix(h, Files.size(generatedJson))
            }
        } catch (_: IOException) {
            // Polling is best-effort; the last coherent signature remains deterministic.
        }
        return h
    }

    @Throws(IOException::class)
    private fun resourcePackSignature(initial: Long, path: Path): Long {
        var h = initial
        if (!Files.exists(path)) return mix(h, MISSING_SENTINEL)
        if (Files.isRegularFile(path)) {
            h = mix(h, Files.getLastModifiedTime(path).toMillis())
            return mix(h, Files.size(path))
        }
        val assets = path.resolve("assets")
        if (!Files.isDirectory(assets)) return mix(h, Files.getLastModifiedTime(path).toMillis())
        val files = ArrayList<Path>()
        collectAcousticFiles(path, assets, files, 0)
        files.sortBy { path.relativize(it).toString() }
        for (file in files) {
            h = mix(h, path.relativize(file).toString().replace('\\', '/').hashCode().toLong())
            h = mix(h, Files.getLastModifiedTime(file).toMillis())
            h = mix(h, Files.size(file))
        }
        return mix(h, files.size.toLong())
    }

    @Throws(IOException::class)
    private fun collectAcousticFiles(root: Path, dir: Path, out: MutableList<Path>, depth: Int) {
        if (depth > 8) return
        Files.newDirectoryStream(dir).use { stream ->
            for (entry in stream) {
                if (Files.isDirectory(entry)) {
                    collectAcousticFiles(root, entry, out, depth + 1)
                } else {
                    val low = root.relativize(entry).toString().replace('\\', '/').lowercase(java.util.Locale.ROOT)
                    if (low == "pack.mcmeta" || (low.startsWith("assets/") && (low.contains("/acoustic_materials/") || low.contains("/acoustic_media/") || low.contains("/acoustic_sources/")) && low.endsWith(".json"))) {
                        out.add(entry)
                    }
                }
            }
        }
    }

    internal class Snapshot(
        @JvmField val rules: List<MaterialRule>,
        @JvmField val mediumRules: List<MediumRule>,
        @JvmField val sourceProfiles: SourceProfileResolver,
        @JvmField val sources: List<String>,
        @JvmField val files: Int,
        @JvmField val mediumFiles: Int,
        @JvmField val sourceFiles: Int
    ) {
        companion object {
            @JvmField val EMPTY = Snapshot(
                emptyList(),
                emptyList(),
                SourceProfileResolver(emptyList(), AcousticSourceProfile.GENERIC),
                emptyList(),
                0,
                0,
                0
            )
        }
    }

    private companion object {
        const val GENERATED_BASE = -500_000
        const val RESOURCE_BASE = 500_000
        const val FNV_OFFSET = 1469598103934665603L
        const val MISSING_SENTINEL = 0x6d697373696e67L
        fun mix(hash: Long, value: Long): Long = (hash xor value) * 1099511628211L
    }
}
