package dev.acoustic.core.source.profile

import dev.acoustic.api.source.AcousticSourceProfile
import dev.acoustic.api.source.SourceProfileRule
import java.util.Collections

/** Immutable priority-ordered resolver for source acoustic profiles. */
class SourceProfileResolver(rules: List<SourceProfileRule>?, fallback: AcousticSourceProfile?) {
    private val rules: List<SourceProfileRule>
    private val fallback: AcousticSourceProfile = fallback ?: AcousticSourceProfile.GENERIC

    init {
        val copy = ArrayList(rules ?: emptyList())
        copy.sortWith(Comparator { a, b -> Integer.compare(b.priority(), a.priority()) })
        this.rules = Collections.unmodifiableList(copy)
    }

    fun resolve(soundId: String?): AcousticSourceProfile {
        for (rule in rules) if (rule.matches(soundId)) return rule.profile()
        return fallback
    }

    fun rules(): List<SourceProfileRule> = rules
}
