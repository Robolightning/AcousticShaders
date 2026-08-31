package dev.acoustic.api.capability

import java.util.Collections
import java.util.EnumSet

class Capabilities(available: Set<Capability>) {
    private val available: Set<Capability> = Collections.unmodifiableSet(
        if (available.isEmpty()) EnumSet.noneOf(Capability::class.java) else EnumSet.copyOf(available)
    )

    fun supports(capability: Capability): Boolean = available.contains(capability)
    fun all(): Set<Capability> = available

    fun require(required: Set<Capability>) {
        val missing = if (required.isEmpty()) EnumSet.noneOf(Capability::class.java) else EnumSet.copyOf(required)
        missing.removeAll(available)
        if (missing.isNotEmpty()) throw UnsupportedOperationException("Missing acoustic capabilities: $missing")
    }
}
