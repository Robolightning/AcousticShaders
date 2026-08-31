package dev.acoustic.api.environment.resolve

import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.material.resolve.MaterialDescriptor

/** Ordered volume-medium rule. Higher priority rules win. */
class MediumRule(
    private val priority: Int,
    private val kind: MatchKind,
    private val value: String,
    private val medium: AcousticMedium
) {
    enum class MatchKind { STATE_ID, REGISTRY_ID, GLOB, TAG, DICTIONARY_PREFIX, DICTIONARY_EXACT }

    fun priority(): Int = priority
    fun kind(): MatchKind = kind
    fun value(): String = value
    fun medium(): AcousticMedium = medium
    fun withPriority(newPriority: Int): MediumRule = MediumRule(newPriority, kind, value, medium)

    fun matches(descriptor: MaterialDescriptor): Boolean = when (kind) {
        MatchKind.STATE_ID -> value == descriptor.stateId()
        MatchKind.REGISTRY_ID -> value == descriptor.registryId()
        MatchKind.GLOB -> glob(value, descriptor.stateId()) || glob(value, descriptor.registryId())
        MatchKind.TAG -> descriptor.semanticTags().contains(value)
        MatchKind.DICTIONARY_EXACT -> descriptor.dictionaryNames().contains(value)
        MatchKind.DICTIONARY_PREFIX -> descriptor.dictionaryNames().any { it.startsWith(value) }
    }

    companion object {
        private fun glob(pattern: String, text: String): Boolean {
            var p = 0
            var t = 0
            var star = -1
            var retry = -1
            while (t < text.length) {
                if (p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t])) {
                    p++; t++; continue
                }
                if (p < pattern.length && pattern[p] == '*') {
                    star = p++
                    retry = t
                    continue
                }
                if (star >= 0) {
                    p = star + 1
                    t = ++retry
                    continue
                }
                return false
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }
    }
}
