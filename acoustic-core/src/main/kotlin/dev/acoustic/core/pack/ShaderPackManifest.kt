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

        /**
         * Builds the effective manifest for an ordered shader stack.
         *
         * Identity/name stay anchored to the lowest/base layer for diagnostics, while capability
         * requirements are the union of every layer. A higher overlay therefore cannot silently
         * hide a capability that it needs from validation of the effective stack.
         */
        @JvmStatic
        fun compose(stack: List<ShaderPackManifest>?): ShaderPackManifest {
            require(!stack.isNullOrEmpty()) { "shader manifest stack must contain at least one layer" }
            val base = stack[0]
            val required = EnumSet.noneOf(Capability::class.java)
            val optional = EnumSet.noneOf(Capability::class.java)
            for (manifest in stack) {
                require(manifest.format == base.format) { "manifest format mismatch in shader stack" }
                require(manifest.spec == base.spec) { "shader spec mismatch in shader stack: ${manifest.spec} != ${base.spec}" }
                required.addAll(manifest.required)
                optional.addAll(manifest.optional)
            }
            optional.removeAll(required)
            return ShaderPackManifest(
                base.format,
                base.spec,
                base.id,
                base.name,
                immutableCapabilities(required),
                immutableCapabilities(optional)
            )
        }

        private fun capabilities(value: Any?): Set<Capability> {
            if (value == null) return immutableCapabilities(EnumSet.noneOf(Capability::class.java))
            if (value !is List<*>) throw IllegalArgumentException("capability list must be array")
            val set = EnumSet.noneOf(Capability::class.java)
            for (raw in value) {
                if (raw !is String) throw IllegalArgumentException("capability must be string")
                val normalized = raw.trim().uppercase()
                try { set.add(Capability.valueOf(normalized)) }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("unknown capability: $raw") }
            }
            return immutableCapabilities(set)
        }

        private fun immutableCapabilities(source: Set<Capability>): Set<Capability> {
            val copy = if (source.isEmpty()) EnumSet.noneOf(Capability::class.java) else EnumSet.copyOf(source)
            return Collections.unmodifiableSet(copy)
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
