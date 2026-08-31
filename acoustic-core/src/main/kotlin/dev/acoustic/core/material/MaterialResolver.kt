package dev.acoustic.core.material

import dev.acoustic.api.material.AcousticMaterial
import dev.acoustic.api.material.resolve.MaterialDescriptor
import dev.acoustic.api.material.resolve.MaterialRule
import java.util.Collections
import java.util.LinkedHashMap

/** Deterministic ordered resolver used by all platform adapters. Exact block/state lookups remain O(1) without breaking priority semantics. */
class MaterialResolver(rules: List<MaterialRule>?, solidFallback: AcousticMaterial?, nonSolidFallback: AcousticMaterial?) {
    private val rules: List<MaterialRule>
    private val stateRules: Map<String, MaterialRule>
    private val registryRules: Map<String, MaterialRule>
    private val genericRules: List<MaterialRule>
    private val solidFallback = solidFallback ?: throw NullPointerException("solidFallback")
    private val nonSolidFallback = nonSolidFallback ?: throw NullPointerException("nonSolidFallback")

    init {
        val copy = ArrayList(rules ?: throw NullPointerException("rules"))
        copy.sortWith(Comparator { a, b -> Integer.compare(b.priority(), a.priority()) })
        this.rules = Collections.unmodifiableList(copy)
        val states = LinkedHashMap<String, MaterialRule>()
        val registries = LinkedHashMap<String, MaterialRule>()
        val generic = ArrayList<MaterialRule>()
        for (rule in copy) {
            when (rule.kind()) {
                MaterialRule.MatchKind.STATE_ID -> if (!states.containsKey(rule.value())) states[rule.value()] = rule
                MaterialRule.MatchKind.REGISTRY_ID -> if (!registries.containsKey(rule.value())) registries[rule.value()] = rule
                else -> generic.add(rule)
            }
        }
        stateRules = Collections.unmodifiableMap(states)
        registryRules = Collections.unmodifiableMap(registries)
        genericRules = Collections.unmodifiableList(generic)
    }

    fun resolve(descriptor: MaterialDescriptor?): AcousticMaterial {
        val required = descriptor ?: throw NullPointerException("descriptor")
        var best = stateRules[required.stateId()]
        val registry = registryRules[required.registryId()]
        if (registry != null && (best == null || registry.priority() > best.priority())) best = registry
        val threshold = best?.priority() ?: Int.MIN_VALUE
        for (rule in genericRules) {
            if (best != null && rule.priority() < threshold) break
            if (rule.matches(required)) {
                if (best == null || rule.priority() > best.priority()) best = rule
                break
            }
        }
        return best?.material() ?: if (required.solid()) solidFallback else nonSolidFallback
    }

    fun rules(): List<MaterialRule> = rules
}
