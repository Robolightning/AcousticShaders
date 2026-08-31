package dev.acoustic.mc1122

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Properties

/** User-facing 1.12.2 runtime configuration, including the ordered acoustic-shader stack. */
class LegacyRuntimeConfig(
    packs: List<String>?,
    profile: String?,
    private val horizontalRadiusValue: Int,
    private val verticalRadiusValue: Int,
    private val captureIntervalTicksValue: Int,
    private val roomProbeDistanceValue: Double,
    private val effectsEnabledValue: Boolean,
    private val debugValue: Boolean,
    optionOverrides: Map<String, String>?
) {
    private val packsValue: List<String>
    private val profileValue: String
    private val optionOverridesValue: Map<String, String>

    constructor(pack: String, profile: String, horizontalRadius: Int, verticalRadius: Int, captureIntervalTicks: Int, roomProbeDistance: Double, effectsEnabled: Boolean, debug: Boolean) :
        this(Collections.singletonList(pack), profile, horizontalRadius, verticalRadius, captureIntervalTicks, roomProbeDistance, effectsEnabled, debug, Collections.emptyMap())

    init {
        require(packs != null && packs.size <= 16) { "packs" }
        val clean = ArrayList<String>()
        for (pack in packs) {
            require(!pack.isNullOrBlank()) { "pack" }
            val n = pack.trim()
            require('/' !in n && '\\' !in n && ".." !in n) { "unsafe pack name: $n" }
            require(!clean.contains(n)) { "duplicate pack: $n" }
            clean.add(n)
        }
        require(!profile.isNullOrBlank()) { "profile" }
        require(horizontalRadiusValue in 4..48) { "horizontalRadius" }
        require(verticalRadiusValue in 3..24) { "verticalRadius" }
        require(captureIntervalTicksValue in 1..200) { "captureIntervalTicks" }
        require(roomProbeDistanceValue in 2.0..64.0) { "roomProbeDistance" }
        val opts = LinkedHashMap<String, String>()
        if (optionOverrides != null) {
            for ((key, value) in optionOverrides) {
                require(key.isNotBlank()) { "option override" }
                opts[key.trim()] = value.trim()
            }
        }
        packsValue = Collections.unmodifiableList(clean)
        profileValue = profile.trim()
        optionOverridesValue = Collections.unmodifiableMap(opts)
    }

    @Throws(IOException::class)
    fun save(file: Path) {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val p = Properties()
        p.setProperty("shaderpack", pack())
        p.setProperty("shaderpack.stack", joinStack(packsValue))
        p.setProperty("profile", profileValue)
        p.setProperty("snapshot.horizontalRadius", horizontalRadiusValue.toString())
        p.setProperty("snapshot.verticalRadius", verticalRadiusValue.toString())
        p.setProperty("snapshot.intervalTicks", captureIntervalTicksValue.toString())
        p.setProperty("room.maxProbeDistance", roomProbeDistanceValue.toString())
        p.setProperty("effects.enabled", effectsEnabledValue.toString())
        p.setProperty("debug", debugValue.toString())
        for ((key, value) in optionOverridesValue) p.setProperty("option.$key", value)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newOutputStream(tmp).use { p.store(it, "Acoustic Shaders 1.12.2 runtime configuration") }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun pack(): String = if (packsValue.isEmpty()) "" else packsValue[0]
    fun packs(): List<String> = packsValue
    fun profile(): String = profileValue
    fun horizontalRadius(): Int = horizontalRadiusValue
    fun verticalRadius(): Int = verticalRadiusValue
    fun captureIntervalTicks(): Int = captureIntervalTicksValue
    fun roomProbeDistance(): Double = roomProbeDistanceValue
    fun effectsEnabled(): Boolean = effectsEnabledValue
    fun debug(): Boolean = debugValue
    fun optionOverrides(): Map<String, String> = optionOverridesValue
    fun withUi(stack: List<String>, newProfile: String, overrides: Map<String, String>): LegacyRuntimeConfig =
        LegacyRuntimeConfig(stack, newProfile, horizontalRadiusValue, verticalRadiusValue, captureIntervalTicksValue, roomProbeDistanceValue, effectsEnabledValue, debugValue, overrides)

    companion object {
        const val DEFAULT_PACK: String = "AcousticShaders-Reference-Hybrid.zip"
        const val DEFAULT_PROFILE: String = "HIGH"

        @JvmStatic
        fun defaults(): LegacyRuntimeConfig = LegacyRuntimeConfig(DEFAULT_PACK, DEFAULT_PROFILE, 18, 10, 10, 24.0, true, false)

        @JvmStatic
        @Throws(IOException::class)
        fun loadOrCreate(file: Path): LegacyRuntimeConfig {
            if (!Files.isRegularFile(file)) {
                val defaults = defaults()
                defaults.save(file)
                return defaults
            }
            val p = Properties()
            Files.newInputStream(file).use { p.load(it) }
            val d = defaults()
            val stack = if (p.containsKey("shaderpack.stack")) {
                parseStack(p.getProperty("shaderpack.stack"))
            } else {
                val legacy = p.getProperty("shaderpack", d.pack())
                if (legacy.isNullOrBlank()) emptyList() else Collections.singletonList(legacy)
            }
            val overrides = LinkedHashMap<String, String>()
            for (key in p.stringPropertyNames()) {
                if (key.startsWith("option.")) {
                    val name = key.substring("option.".length).trim()
                    if (name.isNotEmpty()) overrides[name] = p.getProperty(key).trim()
                }
            }
            return LegacyRuntimeConfig(
                stack, p.getProperty("profile", d.profile()),
                integer(p, "snapshot.horizontalRadius", d.horizontalRadius()),
                integer(p, "snapshot.verticalRadius", d.verticalRadius()),
                integer(p, "snapshot.intervalTicks", d.captureIntervalTicks()),
                decimal(p, "room.maxProbeDistance", d.roomProbeDistance()),
                bool(p, "effects.enabled", d.effectsEnabled()), bool(p, "debug", d.debug()), overrides
            )
        }

        private fun parseStack(raw: String?): List<String> {
            val out = ArrayList<String>()
            if (raw == null) return out
            for (s in raw.split(';')) {
                val n = s.trim()
                if (n.isNotEmpty()) out.add(n)
            }
            return out
        }

        private fun joinStack(stack: List<String>): String = stack.joinToString(";")
        private fun integer(p: Properties, key: String, fallback: Int): Int = p.getProperty(key)?.trim()?.toIntOrNull() ?: fallback
        private fun decimal(p: Properties, key: String, fallback: Double): Double = p.getProperty(key)?.trim()?.toDoubleOrNull() ?: fallback
        private fun bool(p: Properties, key: String, fallback: Boolean): Boolean { val value = p.getProperty(key) ?: return fallback; return java.lang.Boolean.parseBoolean(value.trim()) }
    }
}
