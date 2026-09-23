package dev.acoustic.core.pack

import dev.acoustic.api.capability.Capabilities
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet

/** Structural/capability validation before a pack can become active. */
class ShaderPackValidator {
    fun validate(pack: LoadedShaderPack, caps: Capabilities): List<String> = validate(pack, caps, true)

    /**
     * Validates one layer before stack composition. Overlay layers intentionally may omit profiles;
     * the effective stack is required to define at least one preset after composition.
     */
    fun validateLayer(pack: LoadedShaderPack, caps: Capabilities): List<String> = validate(pack, caps, false)

    fun requireValid(pack: LoadedShaderPack, caps: Capabilities) {
        val issues = validate(pack, caps, true)
        if (issues.isNotEmpty()) throw IllegalArgumentException("invalid shader pack: $issues")
    }

    fun requireValidLayer(pack: LoadedShaderPack, caps: Capabilities) {
        val issues = validate(pack, caps, false)
        if (issues.isNotEmpty()) throw IllegalArgumentException("invalid shader pack layer: $issues")
    }

    private fun validate(pack: LoadedShaderPack, caps: Capabilities, requireProfiles: Boolean): List<String> {
        val issues = ArrayList<String>()
        try { caps.require(pack.manifest().required()) } catch (e: UnsupportedOperationException) { e.message?.let(issues::add) }
        val ids = HashSet<String>()
        for (definition in pack.pipeline().passes()) if (!ids.add(definition.id())) issues.add("duplicate pass id: ${definition.id()}")
        if (requireProfiles && pack.options().profiles().isEmpty()) issues.add("effective shader stack defines no profiles")
        return Collections.unmodifiableList(issues)
    }
}
