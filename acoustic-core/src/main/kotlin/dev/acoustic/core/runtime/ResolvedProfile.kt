package dev.acoustic.core.runtime

import dev.acoustic.core.pack.PackOptions
import java.util.Collections
import java.util.LinkedHashMap

/** Pack preset/profile resolved with validated user overrides layered on top. */
class ResolvedProfile private constructor(private val name: String, values: Map<String, String>) {
    private val values: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(values))
    fun name(): String = name
    fun get(k: String, defaultValue: String?): String? = values[k] ?: defaultValue
    fun getInt(k: String, d: Int): Int = Integer.parseInt(get(k, Integer.toString(d))!!)
    fun getDouble(k: String, d: Double): Double = java.lang.Double.parseDouble(get(k, java.lang.Double.toString(d))!!)
    fun getBoolean(k: String, d: Boolean): Boolean {
        val v = get(k, java.lang.Boolean.toString(d)) ?: return d
        return v.equals("true", true) || v.equals("on", true) || v.equals("yes", true) || v == "1"
    }
    fun enabledExpression(expression: Any?): Boolean {
        if (expression is Boolean) return expression
        var s = java.lang.String.valueOf(expression).trim()
        if (s.startsWith("${'$'}{") && s.endsWith("}")) s = s.substring(2, s.length - 1).trim()
        val ne = s.split(Regex("\\s*!=\\s*"))
        if (ne.size == 2) return !(get(ne[0], "")?.equals(ne[1], true) ?: false)
        val eq = s.split(Regex("\\s*==\\s*"))
        if (eq.size == 2) return get(eq[0], "")?.equals(eq[1], true) ?: false
        return s.toBoolean()
    }
    fun values(): Map<String, String> = values
    companion object {
        @JvmStatic fun from(options: PackOptions, name: String): ResolvedProfile = from(options, name, Collections.emptyMap())
        @JvmStatic fun from(options: PackOptions, name: String, overrides: Map<String, String>?): ResolvedProfile {
            val values = LinkedHashMap<String, String>()
            val raw = options.get("profile.$name") ?: throw IllegalArgumentException("unknown profile: $name")
            for (token in raw.trim().split(Regex("\\s+"))) {
                if (token.isEmpty()) continue
                val i = token.indexOf(':')
                if (i <= 0 || i == token.length - 1) throw IllegalArgumentException("bad profile token: $token")
                values[token.substring(0, i)] = token.substring(i + 1)
            }
            if (overrides != null) values.putAll(overrides)
            return ResolvedProfile(name, values)
        }
    }
}
