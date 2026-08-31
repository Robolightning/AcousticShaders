package dev.acoustic.core.pack

import java.io.IOException
import java.io.Reader
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Properties

/** OptiFine/Iris-inspired user options and shader-local presets. */
class PackOptions private constructor(
    private val values: Map<String, String>,
    private val profiles: List<String>
) {
    fun get(key: String): String? = values[key]
    fun profiles(): List<String> = profiles
    fun values(): Map<String, String> = values

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun load(reader: Reader): PackOptions {
            val properties = Properties()
            properties.load(reader)
            val values = LinkedHashMap<String, String>()
            for (key in properties.stringPropertyNames()) values[key] = properties.getProperty(key).trim()
            return build(values)
        }

        @JvmStatic
        fun of(source: Map<String, String?>): PackOptions {
            val values = LinkedHashMap<String, String>()
            for ((key, value) in source) {
                if (value == null) throw IllegalArgumentException("null pack option")
                values[key] = value
            }
            return build(values)
        }

        private fun build(source: Map<String, String>): PackOptions {
            val defined = LinkedHashSet<String>()
            for (key in source.keys) {
                if (key.startsWith("profile.") && key != "profile.order") defined.add(key.substring("profile.".length))
            }
            val profiles = ArrayList<String>()
            var order = source["profile.order"]
            if (order == null) order = source["quality.order"]
            if (order != null) {
                for (profile in order.trim().split(Regex("\\s+"))) if (defined.remove(profile)) profiles.add(profile)
            }
            val rest = ArrayList(defined)
            Collections.sort(rest)
            profiles.addAll(rest)
            return PackOptions(
                Collections.unmodifiableMap(LinkedHashMap(source)),
                Collections.unmodifiableList(profiles)
            )
        }
    }
}
