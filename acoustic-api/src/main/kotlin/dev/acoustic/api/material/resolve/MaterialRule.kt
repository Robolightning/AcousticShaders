package dev.acoustic.api.material.resolve

import dev.acoustic.api.material.AcousticMaterial

/** Ordered rule. Higher priority rules are evaluated first. */
class MaterialRule(private val priority: Int, private val kind: MatchKind, private val value: String, private val material: AcousticMaterial) {
    enum class MatchKind { STATE_ID, REGISTRY_ID, TAG, DICTIONARY_PREFIX, DICTIONARY_EXACT }

    fun priority(): Int = priority
    fun kind(): MatchKind = kind
    fun value(): String = value
    fun material(): AcousticMaterial = material
    fun withPriority(newPriority: Int): MaterialRule = MaterialRule(newPriority, kind, value, material)

    fun matches(descriptor: MaterialDescriptor): Boolean = when (kind) {
        MatchKind.STATE_ID -> value == descriptor.stateId()
        MatchKind.REGISTRY_ID -> value == descriptor.registryId()
        MatchKind.TAG -> descriptor.semanticTags().contains(value)
        MatchKind.DICTIONARY_EXACT -> descriptor.dictionaryNames().contains(value)
        MatchKind.DICTIONARY_PREFIX -> descriptor.dictionaryNames().any { it.startsWith(value) }
    }
}
