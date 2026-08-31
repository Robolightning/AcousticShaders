package dev.acoustic.core.pack

import java.util.Collections
import java.util.LinkedHashMap

/** Portable declarative pipeline description. Runtime registries later bind pass ids to implementations. */
class PipelineDefinition private constructor(private val format: Int, private val passes: List<PassDefinition>) {
    class PassDefinition internal constructor(
        private val id: String,
        private val enabled: Any,
        private val options: Map<String, Any?>
    ) {
        fun id(): String = id
        fun enabled(): Any = enabled
        fun options(): Map<String, Any?> = options
    }

    fun format(): Int = format
    fun passes(): List<PassDefinition> = passes

    companion object {
        @JvmStatic
        fun parse(json: String): PipelineDefinition {
            val root = StrictJson.parse(json)
            if (root !is Map<*, *>) throw IllegalArgumentException("pipeline root must be object")
            val formatValue = root["format"]
            if (formatValue !is Number) throw IllegalArgumentException("pipeline format must be number")
            val format = formatValue.toInt()
            if (format != 1) throw IllegalArgumentException("unsupported pipeline format: $format")
            val listValue = root["passes"]
            if (listValue !is List<*>) throw IllegalArgumentException("passes must be array")
            val out = ArrayList<PassDefinition>()
            for (raw in listValue) {
                if (raw !is Map<*, *>) throw IllegalArgumentException("pass must be object")
                val idValue = raw["id"]
                if (idValue !is String || idValue.isEmpty()) throw IllegalArgumentException("pass id must be non-empty string")
                val enabledValue: Any? = if (raw.containsKey("enabled")) raw["enabled"] else true
                val enabled = when (enabledValue) {
                    is Boolean -> enabledValue
                    is String -> enabledValue
                    else -> throw IllegalArgumentException("enabled must be boolean or expression string")
                }
                val options = LinkedHashMap<String, Any?>()
                val optionValue = raw["options"]
                if (optionValue != null) {
                    if (optionValue !is Map<*, *>) throw IllegalArgumentException("pass options must be object")
                    for ((key, value) in optionValue) {
                        if (key !is String) throw IllegalArgumentException("pass option key must be string")
                        options[key] = value
                    }
                }
                out.add(PassDefinition(idValue, enabled, Collections.unmodifiableMap(options)))
            }
            return PipelineDefinition(format, Collections.unmodifiableList(out))
        }

        @JvmStatic
        fun of(format: Int, passes: List<PassDefinition>?): PipelineDefinition {
            if (format != 1) throw IllegalArgumentException("unsupported pipeline format: $format")
            if (passes == null) throw IllegalArgumentException("passes")
            return PipelineDefinition(format, Collections.unmodifiableList(ArrayList(passes)))
        }
    }
}
