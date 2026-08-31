package dev.acoustic.core.runtime

import java.util.Collections
import java.util.LinkedHashMap

class PassFactoryRegistry {
    private val factories = LinkedHashMap<String, PassFactory>()
    @Synchronized fun register(id: String?, factory: PassFactory?): PassFactoryRegistry {
        if (id.isNullOrEmpty() || factory == null) throw IllegalArgumentException()
        if (factories.containsKey(id)) throw IllegalArgumentException("pass factory already registered: $id")
        factories[id] = factory
        return this
    }
    @Synchronized fun require(id: String): PassFactory = factories[id] ?: throw IllegalArgumentException("no pass factory registered: $id")
    @Synchronized fun snapshot(): Map<String, PassFactory> = Collections.unmodifiableMap(LinkedHashMap(factories))
}
