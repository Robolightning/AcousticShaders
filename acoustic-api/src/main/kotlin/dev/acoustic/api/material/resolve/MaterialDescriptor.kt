package dev.acoustic.api.material.resolve

import java.util.Collections
import java.util.LinkedHashSet

/** Platform-neutral facts about a world material/block state. */
class MaterialDescriptor {
    private val registryId: String
    private val stateId: String
    private val semanticTags: Set<String>
    private val dictionaryNames: Set<String>
    private val solid: Boolean

    constructor(registryId: String, semanticTags: Set<String>?, dictionaryNames: Set<String>?, solid: Boolean) :
        this(registryId, registryId, semanticTags, dictionaryNames, solid)

    constructor(registryId: String, stateId: String?, semanticTags: Set<String>?, dictionaryNames: Set<String>?, solid: Boolean) {
        this.registryId = registryId
        this.stateId = if (stateId.isNullOrEmpty()) registryId else stateId
        this.semanticTags = immutableCopy(semanticTags)
        this.dictionaryNames = immutableCopy(dictionaryNames)
        this.solid = solid
    }

    fun registryId(): String = registryId
    fun stateId(): String = stateId
    fun semanticTags(): Set<String> = semanticTags
    fun dictionaryNames(): Set<String> = dictionaryNames
    fun solid(): Boolean = solid

    companion object {
        private fun immutableCopy(values: Set<String>?): Set<String> = if (values.isNullOrEmpty()) Collections.emptySet() else Collections.unmodifiableSet(LinkedHashSet(values))
    }
}
