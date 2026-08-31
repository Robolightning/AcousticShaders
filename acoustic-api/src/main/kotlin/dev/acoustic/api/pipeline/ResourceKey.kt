package dev.acoustic.api.pipeline

class ResourceKey<T>(private val id: String, private val type: Class<T>) {
    fun id(): String = id
    fun type(): Class<T> = type
    override fun equals(other: Any?): Boolean = this === other || (other is ResourceKey<*> && id == other.id && type == other.type)
    override fun hashCode(): Int = java.util.Objects.hash(id, type)
    override fun toString(): String = "$id<${type.simpleName}>"
}
