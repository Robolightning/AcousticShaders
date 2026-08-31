package dev.acoustic.core.pack

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet

/** Loader-neutral UI description derived from OptiFine/Iris-inspired properties. */
class PackUiModel private constructor(screenTokens: List<String>, sliders: Set<String>, profiles: List<String>) {
    private val screenTokens = Collections.unmodifiableList(ArrayList(screenTokens))
    private val sliders = Collections.unmodifiableSet(LinkedHashSet(sliders))
    private val profiles = Collections.unmodifiableList(ArrayList(profiles))
    fun screenTokens(): List<String> = screenTokens
    fun sliders(): Set<String> = sliders
    fun profiles(): List<String> = profiles
    companion object {
        @JvmStatic fun from(options: PackOptions): PackUiModel {
            val screen = tokens(options.get("screen"))
            val sliders = LinkedHashSet(tokens(options.get("sliders")))
            return PackUiModel(screen, sliders, ArrayList(options.profiles()))
        }
        private fun tokens(raw: String?): List<String> {
            if (raw == null) return ArrayList()
            val out = ArrayList<String>()
            for (x in raw.trim().split(Regex("\\s+"))) if (x.isNotEmpty()) out.add(x)
            return out
        }
    }
}
