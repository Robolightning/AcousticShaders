package dev.acoustic.core.material.infer

import java.util.Collections
import java.util.LinkedHashSet

/** Platform-neutral block-state facts used only while generating the cached default material database. */
class MaterialFacts(
    registryId: String?, stateId: String?, materialName: String?, soundTypeName: String?, dictionaryNames: Set<String>?,
    private val solid: Boolean, private val fullCube: Boolean, private val opaque: Boolean, private val liquid: Boolean,
    hardness: Float, resistance: Float
) {
    private val registryId = clean(registryId)
    private val stateId = if (stateId == null || stateId.trim().isEmpty()) this.registryId else stateId.trim()
    private val materialName = clean(materialName)
    private val soundTypeName = clean(soundTypeName)
    private val dictionaryNames: Set<String> = if (dictionaryNames == null) emptySet() else Collections.unmodifiableSet(LinkedHashSet(dictionaryNames))
    private val hardness = finite(hardness)
    private val resistance = finite(resistance)

    fun registryId(): String = registryId
    fun stateId(): String = stateId
    fun materialName(): String = materialName
    fun soundTypeName(): String = soundTypeName
    fun dictionaryNames(): Set<String> = dictionaryNames
    fun solid(): Boolean = solid
    fun fullCube(): Boolean = fullCube
    fun opaque(): Boolean = opaque
    fun liquid(): Boolean = liquid
    fun hardness(): Float = hardness
    fun resistance(): Float = resistance

    companion object {
        private fun clean(value: String?): String = value?.trim() ?: ""
        private fun finite(value: Float): Float = if (value.isNaN() || value.isInfinite()) 0f else value
    }
}
