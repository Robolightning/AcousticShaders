package dev.acoustic.core.pack

import dev.acoustic.core.compat.uppercaseCompat
import dev.acoustic.api.material.AcousticMaterial
import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.material.resolve.MaterialRule
import java.util.Collections
import java.util.LinkedHashMap

/** Parses portable acoustic material libraries and matching rules from shader packs. */
class MaterialPack private constructor(
    private val materials: Map<String, AcousticMaterial>,
    private val rules: List<MaterialRule>
) {
    fun materials(): Map<String, AcousticMaterial> = materials
    fun rules(): List<MaterialRule> = rules

    companion object {
        @JvmStatic
        fun parse(json: String): MaterialPack {
            val root = StrictJson.parse(json)
            if (root !is Map<*, *>) throw IllegalArgumentException("materials root must be object")
            val materialsRaw = root["materials"]
            if (materialsRaw !is Map<*, *>) throw IllegalArgumentException("materials must be object")
            val materials = LinkedHashMap<String, AcousticMaterial>()
            for ((keyRaw, valueRaw) in materialsRaw) {
                val key = keyRaw as? String ?: throw IllegalArgumentException("material id must be string")
                if (valueRaw !is Map<*, *>) throw IllegalArgumentException("material $key must be object")
                val absorption = spectrum(valueRaw["absorption"], "absorption")
                val scattering = unit(valueRaw["scattering"], "scattering", 0f)
                val transmission = unit(valueRaw["transmission"], "transmission", 0f)
                materials[key] = AcousticMaterial(key, absorption, scattering, transmission)
            }
            val rules = ArrayList<MaterialRule>()
            val rulesRaw = root["rules"]
            if (rulesRaw != null) {
                if (rulesRaw !is List<*>) throw IllegalArgumentException("rules must be array")
                for (raw in rulesRaw) {
                    if (raw !is Map<*, *>) throw IllegalArgumentException("material rule must be object")
                    val kind = requiredString(raw, "kind")
                    val match = requiredString(raw, "match")
                    val materialId = requiredString(raw, "material")
                    val material = materials[materialId] ?: throw IllegalArgumentException("unknown material in rule: $materialId")
                    val priority = if (raw.containsKey("priority")) number(raw["priority"], "priority").toInt() else 0
                    val matchKind = try { MaterialRule.MatchKind.valueOf(kind.uppercaseCompat(java.util.Locale.ROOT)) }
                    catch (_: IllegalArgumentException) { throw IllegalArgumentException("unknown material rule kind: $kind") }
                    rules.add(MaterialRule(priority, matchKind, match, material))
                }
            }
            return MaterialPack(Collections.unmodifiableMap(materials), Collections.unmodifiableList(rules))
        }

        private fun spectrum(raw: Any?, name: String): FloatArray {
            if (raw !is List<*>) throw IllegalArgumentException("$name must be array")
            if (raw.size != FrequencyBands.COUNT) throw IllegalArgumentException("$name requires ${FrequencyBands.COUNT} bands")
            return FloatArray(FrequencyBands.COUNT) { i -> unit(raw[i], "$name[$i]", 0f) }
        }
        private fun unit(raw: Any?, name: String, fallback: Float): Float {
            if (raw == null) return fallback
            val value = number(raw, name).toFloat()
            if (value < 0f || value > 1f || value.isNaN()) throw IllegalArgumentException("$name must be within [0,1]")
            return value
        }
        private fun number(raw: Any?, name: String): Number = raw as? Number ?: throw IllegalArgumentException("$name must be number")
        private fun requiredString(map: Map<*, *>, key: String): String {
            val value = map[key]
            if (value !is String || value.length == 0) throw IllegalArgumentException("$key must be non-empty string")
            return value
        }
    }
}
