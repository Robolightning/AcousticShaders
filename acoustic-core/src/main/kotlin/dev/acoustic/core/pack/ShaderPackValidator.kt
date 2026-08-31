package dev.acoustic.core.pack

import dev.acoustic.api.capability.Capabilities
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet

/** Structural/capability validation before a pack can become active. */
class ShaderPackValidator {
    fun validate(pack: LoadedShaderPack, caps: Capabilities): List<String> {
        val issues = ArrayList<String>()
        try { caps.require(pack.manifest().required()) } catch (e: UnsupportedOperationException) { e.message?.let(issues::add) }
        val ids = HashSet<String>()
        for (definition in pack.pipeline().passes()) if (!ids.add(definition.id())) issues.add("duplicate pass id: ${definition.id()}")
        if (pack.options().profiles().isEmpty()) issues.add("pack defines no profiles")
        return Collections.unmodifiableList(issues)
    }
    fun requireValid(pack: LoadedShaderPack, caps: Capabilities) {
        val issues = validate(pack, caps)
        if (issues.isNotEmpty()) throw IllegalArgumentException("invalid shader pack: $issues")
    }
}
