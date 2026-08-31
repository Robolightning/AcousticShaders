package dev.acoustic.core.pack

import dev.acoustic.api.capability.Capability
import java.util.Collections
import java.util.EnumSet

class ShaderPackManifest private constructor(
    private val format: Int,
    private val spec: String,
    private val id: String,
    private val name: String,
    private val required: Set<Capability>,
    private val optional: Set<Capability>
) {
    fun format(): Int = format
    fun spec(): String = spec
    fun id(): String = id
    fun name(): String = name
    fun required(): Set<Capability> = required
    fun optional(): Set<Capability> = optional

    companion object {
        @JvmStatic
        fun parse(json: String): ShaderPackManifest {
            val root = StrictJson.parse(json)
            if (root !is Map<*, *>) throw IllegalArgumentException("manifest root must be object")
            val format = intValue(root, "format")
            if (format != 1) throw IllegalArgumentException("unsupported pack format: $format")
            return ShaderPackManifest(
                format, string(root, "spec"), string(root, "id"), string(root, "name"),
                capabilities(root["requires"]), capabilities(root["optional"])
            )
        }

        private fun capabilities(value: Any?): Set<Capability> {
            if (value == null) return Collections.unmodifiableSet(EnumSet.noneOf(Capability::class.java))
            if (value !is List<*>) throw IllegalArgumentException("capability list must be array")
            val set = EnumSet.noneOf(Capability::class.java)
            for (raw in value) {
                if (raw !is String) throw IllegalArgumentException("capability must be string")
                val normalized = raw.trim().uppercase()
                try { set.add(Capability.valueOf(normalized)) }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("unknown capability: $raw") }
            }
            return Collections.unmodifiableSet(set)
        }

        private fun intValue(map: Map<*, *>, key: String): Int {
            val value = map[key]
            if (value !is Number) throw IllegalArgumentException("$key must be number")
            return value.toInt()
        }
        private fun string(map: Map<*, *>, key: String): String {
            val value = map[key]
            if (value !is String || value.isEmpty()) throw IllegalArgumentException("$key must be non-empty string")
            return value
        }
    }
}
