package dev.acoustic.core.pipeline

import dev.acoustic.api.pipeline.PassContext
import dev.acoustic.api.pipeline.ResourceKey
import java.util.concurrent.ConcurrentHashMap

class MapPassContext : PassContext {
    private val values = ConcurrentHashMap<ResourceKey<*>, Any>()

    override fun <T> require(key: ResourceKey<T>): T = get(key)
        ?: throw IllegalStateException("Missing required resource: $key")

    override fun <T> get(key: ResourceKey<T>): T? {
        val value = values[key] ?: return null
        return key.type().cast(value)
    }

    override fun <T> put(key: ResourceKey<T>, value: T) {
        require(key.type().isInstance(value)) { "Wrong type for $key" }
        values[key] = value as Any
    }
}
