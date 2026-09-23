package dev.acoustic.core.runtime

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Process-wide registry for trusted extension-mod pass implementations.
 *
 * Acoustic Shader Packs remain declarative data. A mod may register a stable namespaced pass id
 * during initialization; every subsequently compiled shader pack can then reference that id.
 * Built-in `standard.*` ids are reserved and cannot be replaced through this registry.
 */
object AcousticPassRegistry {
    private val extensions = LinkedHashMap<String, PassFactory>()

    @JvmStatic
    @Synchronized
    fun register(id: String?, factory: PassFactory?): Unit {
        require(!id.isNullOrBlank() && factory != null) { "pass id/factory" }
        val normalized = id.trim()
        require(!normalized.startsWith("standard.")) { "standard.* pass ids are reserved: $normalized" }
        require(!extensions.containsKey(normalized)) { "extension pass factory already registered: $normalized" }
        extensions[normalized] = factory
    }

    @JvmStatic
    @Synchronized
    fun registered(): Map<String, PassFactory> = Collections.unmodifiableMap(LinkedHashMap(extensions))

    /** Fresh compiler registry so a runtime activation observes one atomic extension snapshot. */
    @JvmStatic
    @Synchronized
    fun snapshot(): PassFactoryRegistry {
        val registry = StandardPassFactories.create()
        for ((id, factory) in extensions) registry.register(id, factory)
        return registry
    }
}
