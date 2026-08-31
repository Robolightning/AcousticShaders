package dev.acoustic.core.pack

import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.source.AcousticSourceProfile
import dev.acoustic.api.source.SourceProfileRule
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/** Parses physical source-category profiles and sound-id matching rules. */
class SourceProfilePack private constructor(
    private val profiles: Map<String, AcousticSourceProfile>,
    private val rules: List<SourceProfileRule>
) {
    fun profiles(): Map<String, AcousticSourceProfile> = profiles
    fun rules(): List<SourceProfileRule> = rules

    companion object {
        @JvmStatic
        fun parse(json: String): SourceProfilePack {
            val root = StrictJson.parse(json)
            if (root !is Map<*, *>) throw IllegalArgumentException("source profile root must be object")
            val profilesRaw = root["profiles"]
            if (profilesRaw !is Map<*, *>) throw IllegalArgumentException("profiles must be object")
            val profiles = LinkedHashMap<String, AcousticSourceProfile>()
            for ((idRaw, valueRaw) in profilesRaw) {
                val id = idRaw as? String ?: throw IllegalArgumentException("profile id must be string")
                if (valueRaw !is Map<*, *>) throw IllegalArgumentException("profile $id must be object")
                val profile = AcousticSourceProfile(
                    id, string(valueRaw, "category", "generic"), spectrum(valueRaw["emission"]),
                    number(valueRaw, "direct", 1f, 0f, 4f), number(valueRaw, "occlusion", 1f, 0.1f, 4f),
                    number(valueRaw, "diffraction", 1f, 0f, 4f), number(valueRaw, "early_reflections", 1f, 0f, 4f),
                    number(valueRaw, "late_reverb", 1f, 0f, 4f), number(valueRaw, "priority", 1f, 0.1f, 8f),
                    number(valueRaw, "movement_sensitivity", 1f, 0.1f, 8f), number(valueRaw, "doppler", 0f, 0f, 4f),
                    number(valueRaw, "transient", 1f, 0f, 4f), bool(valueRaw, "bypass", false)
                )
                profiles[id] = profile
            }
            val rules = ArrayList<SourceProfileRule>()
            val rulesRaw = root["rules"]
            if (rulesRaw != null) {
                if (rulesRaw !is List<*>) throw IllegalArgumentException("rules must be array")
                for (raw in rulesRaw) {
                    if (raw !is Map<*, *>) throw IllegalArgumentException("source rule must be object")
                    val profileId = requiredString(raw, "profile")
                    val profile = profiles[profileId] ?: throw IllegalArgumentException("unknown profile in rule: $profileId")
                    val kind = requiredString(raw, "kind")
                    val matchKind = try { SourceProfileRule.MatchKind.valueOf(kind.uppercase(Locale.ROOT)) }
                    catch (_: IllegalArgumentException) { throw IllegalArgumentException("unknown source rule kind: $kind") }
                    val priorityRaw = raw["priority"]
                    val priority = if (raw.containsKey("priority")) (priorityRaw as? Number ?: throw IllegalArgumentException("priority must be number")).toInt() else 0
                    rules.add(SourceProfileRule(priority, matchKind, requiredString(raw, "match"), profile))
                }
            }
            return SourceProfilePack(Collections.unmodifiableMap(profiles), Collections.unmodifiableList(rules))
        }

        private fun spectrum(raw: Any?): FloatArray {
            val out = FloatArray(FrequencyBands.COUNT)
            Arrays.fill(out, 1f)
            if (raw == null) return out
            if (raw !is List<*>) throw IllegalArgumentException("emission must be array")
            if (raw.size != FrequencyBands.COUNT) throw IllegalArgumentException("emission requires ${FrequencyBands.COUNT} bands")
            var i = 0
            while (i < out.size) {
                val number = raw[i] as? Number ?: throw IllegalArgumentException("emission[$i] must be number")
                val value = number.toFloat()
                if (value.isNaN() || value < 0f || value > 4f) throw IllegalArgumentException("emission[$i] out of range")
                out[i] = value
                i++
            }
            return out
        }
        private fun number(map: Map<*, *>, key: String, fallback: Float, lo: Float, hi: Float): Float {
            val raw = map[key] ?: return fallback
            val value = (raw as? Number ?: throw IllegalArgumentException("$key must be number")).toFloat()
            if (value.isNaN() || value < lo || value > hi) throw IllegalArgumentException("$key out of range")
            return value
        }
        private fun bool(map: Map<*, *>, key: String, fallback: Boolean): Boolean {
            val raw = map[key] ?: return fallback
            return raw as? Boolean ?: throw IllegalArgumentException("$key must be boolean")
        }
        private fun string(map: Map<*, *>, key: String, fallback: String): String {
            val raw = map[key] ?: return fallback
            if (raw !is String || raw.trim().isEmpty()) throw IllegalArgumentException("$key must be string")
            return raw.trim()
        }
        private fun requiredString(map: Map<*, *>, key: String): String {
            val raw = map[key]
            if (raw !is String || raw.trim().isEmpty()) throw IllegalArgumentException("$key is required")
            return raw.trim()
        }
    }
}
