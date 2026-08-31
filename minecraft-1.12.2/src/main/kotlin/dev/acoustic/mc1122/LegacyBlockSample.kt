package dev.acoustic.mc1122

import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.scene.AcousticShape
import java.util.Collections
import java.util.LinkedHashSet

/** One immutable block-state read normalized for acoustic scene capture. */
class LegacyBlockSample constructor(
    registryId: String?,
    stateId: String?,
    materialName: String?,
    soundTypeName: String?,
    oreDictionaryNames: Set<String>?,
    solid: Boolean,
    medium: AcousticMedium,
    shape: AcousticShape?,
    mediumShape: AcousticShape?
) {
    private val registryIdValue = registryId ?: ""
    private val stateIdValue = if (stateId.isNullOrEmpty()) registryIdValue else stateId
    private val materialNameValue = materialName ?: ""
    private val soundTypeNameValue = soundTypeName ?: ""
    private val oreDictionaryNamesValue: Set<String> = if (oreDictionaryNames == null) Collections.emptySet() else Collections.unmodifiableSet(LinkedHashSet(oreDictionaryNames))
    private val shapeValue = shape ?: if (solid) AcousticShape.FULL else AcousticShape.EMPTY
    private val solidValue = solid && !shapeValue.isEmpty()
    private val mediumValue = if (solidValue) AcousticMedia.AIR else medium
    private val liquidValue = !solidValue && mediumValue.id() != AcousticMedia.AIR.id()
    private val mediumShapeValue = if (liquidValue) (mediumShape ?: AcousticShape.FULL) else AcousticShape.EMPTY

    constructor(registryId: String?, stateId: String?, materialName: String?, soundTypeName: String?, oreDictionaryNames: Set<String>?, solid: Boolean, medium: AcousticMedium, shape: AcousticShape?) :
        this(registryId, stateId, materialName, soundTypeName, oreDictionaryNames, solid, medium, shape, null)

    constructor(registryId: String?, materialName: String?, soundTypeName: String?, oreDictionaryNames: Set<String>?, solid: Boolean) :
        this(registryId, registryId, materialName, soundTypeName, oreDictionaryNames, solid, AcousticMedia.AIR, if (solid) AcousticShape.FULL else AcousticShape.EMPTY, AcousticShape.EMPTY)

    constructor(registryId: String?, stateId: String?, materialName: String?, soundTypeName: String?, oreDictionaryNames: Set<String>?, solid: Boolean) :
        this(registryId, stateId, materialName, soundTypeName, oreDictionaryNames, solid, AcousticMedia.AIR, if (solid) AcousticShape.FULL else AcousticShape.EMPTY, AcousticShape.EMPTY)

    constructor(registryId: String?, stateId: String?, materialName: String?, soundTypeName: String?, oreDictionaryNames: Set<String>?, solid: Boolean, shape: AcousticShape?) :
        this(registryId, stateId, materialName, soundTypeName, oreDictionaryNames, solid, AcousticMedia.AIR, shape, AcousticShape.EMPTY)

    /** Backward-compatible liquid flag constructor: historical liquid=true means water. */
    constructor(registryId: String?, stateId: String?, materialName: String?, soundTypeName: String?, oreDictionaryNames: Set<String>?, solid: Boolean, liquid: Boolean, shape: AcousticShape?) :
        this(registryId, stateId, materialName, soundTypeName, oreDictionaryNames, solid, if (liquid) AcousticMedia.WATER else AcousticMedia.AIR, shape, if (liquid) AcousticShape.FULL else AcousticShape.EMPTY)

    fun registryId(): String = registryIdValue
    fun stateId(): String = stateIdValue
    fun materialName(): String = materialNameValue
    fun soundTypeName(): String = soundTypeNameValue
    fun oreDictionaryNames(): Set<String> = oreDictionaryNamesValue
    fun solid(): Boolean = solidValue
    fun liquid(): Boolean = liquidValue
    fun medium(): AcousticMedium = mediumValue
    fun shape(): AcousticShape = shapeValue
    fun mediumShape(): AcousticShape = mediumShapeValue
}
