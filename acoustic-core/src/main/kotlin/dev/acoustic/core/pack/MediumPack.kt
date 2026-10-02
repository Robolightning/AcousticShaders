package dev.acoustic.core.pack

import dev.acoustic.core.compat.uppercaseCompat
import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.environment.resolve.MediumRule
import dev.acoustic.api.material.FrequencyBands
import java.util.Collections
import java.util.LinkedHashMap

/** Parses propagation-media definitions and block/state matching rules. */
class MediumPack private constructor(
    private val media: Map<String, AcousticMedium>,
    private val rules: List<MediumRule>
) {
    fun media(): Map<String, AcousticMedium> = media
    fun rules(): List<MediumRule> = rules

    companion object {
        @JvmStatic
        fun parse(json: String): MediumPack {
            val root = StrictJson.parse(json)
            if (root !is Map<*, *>) throw IllegalArgumentException("media root must be object")
            val media = LinkedHashMap<String, AcousticMedium>()
            media[AcousticMedia.AIR.id()] = AcousticMedia.AIR
            media[AcousticMedia.WATER.id()] = AcousticMedia.WATER
            media[AcousticMedia.LAVA.id()] = AcousticMedia.LAVA
            val rawMedia = root["media"]
            if (rawMedia != null) {
                if (rawMedia !is Map<*, *>) throw IllegalArgumentException("media must be object")
                for ((idRaw, valueRaw) in rawMedia) {
                    val id = idRaw as? String ?: throw IllegalArgumentException("medium id must be string")
                    if (id.isBlank()) throw IllegalArgumentException("medium id must not be blank")
                    if (valueRaw !is Map<*, *>) throw IllegalArgumentException("medium $id must be object")
                    val density = positive(valueRaw["density_kg_m3"], "density_kg_m3")
                    val speed = positive(valueRaw["speed_m_s"], "speed_m_s")
                    val absorption = when {
                        valueRaw.containsKey("absorption_nepers_per_meter") -> spectrum(valueRaw["absorption_nepers_per_meter"], "absorption_nepers_per_meter")
                        valueRaw.containsKey("attenuation_db_per_km") -> AcousticMedia.dbPerKilometerToNepersPerMeter(spectrum(valueRaw["attenuation_db_per_km"], "attenuation_db_per_km"))
                        else -> throw IllegalArgumentException("medium $id requires absorption_nepers_per_meter or attenuation_db_per_km")
                    }
                    media[id] = AcousticMedium(id, density, speed, absorption)
                }
            }
            val rules = ArrayList<MediumRule>()
            val rawRules = root["rules"]
            if (rawRules != null) {
                if (rawRules !is List<*>) throw IllegalArgumentException("rules must be array")
                for (raw in rawRules) {
                    if (raw !is Map<*, *>) throw IllegalArgumentException("medium rule must be object")
                    val kindText = requiredString(raw, "kind")
                    val match = requiredString(raw, "match")
                    val mediumId = requiredString(raw, "medium")
                    val medium = media[mediumId] ?: throw IllegalArgumentException("unknown medium in rule: $mediumId")
                    val priority = (raw["priority"] as? Number)?.toInt() ?: 0
                    val kind = try { MediumRule.MatchKind.valueOf(kindText.uppercaseCompat(java.util.Locale.ROOT)) }
                    catch (_: IllegalArgumentException) { throw IllegalArgumentException("unknown medium rule kind: $kindText") }
                    rules.add(MediumRule(priority, kind, match, medium))
                }
            }
            return MediumPack(Collections.unmodifiableMap(media), Collections.unmodifiableList(rules))
        }

        private fun positive(raw: Any?, name: String): Double {
            val v = (raw as? Number)?.toDouble() ?: throw IllegalArgumentException("$name must be number")
            if (!v.isFinite() || v <= 0.0) throw IllegalArgumentException("$name must be finite and positive")
            return v
        }

        private fun spectrum(raw: Any?, name: String): FloatArray {
            if (raw !is List<*> || raw.size != FrequencyBands.COUNT) throw IllegalArgumentException("$name requires ${FrequencyBands.COUNT} bands")
            return FloatArray(FrequencyBands.COUNT) { i ->
                val v = (raw[i] as? Number)?.toFloat() ?: throw IllegalArgumentException("$name[$i] must be number")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("$name[$i] must be finite and non-negative")
                v
            }
        }

        private fun requiredString(map: Map<*, *>, key: String): String {
            val v = map[key]
            if (v !is String || v.isBlank()) throw IllegalArgumentException("$key must be non-empty string")
            return v
        }
    }
}
