package dev.acoustic.core.medium

import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.environment.resolve.MediumRule
import dev.acoustic.api.material.resolve.MaterialDescriptor
import java.util.Collections
import java.util.LinkedHashMap

/** Deterministic volume-medium resolver layered above platform inference. */
class MediumResolver(rules: List<MediumRule>?) {
    private val rules: List<MediumRule>
    private val stateRules: Map<String, MediumRule>
    private val registryRules: Map<String, MediumRule>
    private val mediumRules: Map<String, MediumRule>
    private val genericRules: List<MediumRule>

    init {
        val copy = ArrayList(rules ?: throw NullPointerException("rules"))
        copy.sortWith(Comparator { a, b -> Integer.compare(b.priority(), a.priority()) })
        this.rules = Collections.unmodifiableList(copy)
        val states = LinkedHashMap<String, MediumRule>()
        val registries = LinkedHashMap<String, MediumRule>()
        val media = LinkedHashMap<String, MediumRule>()
        val generic = ArrayList<MediumRule>()
        for (rule in copy) {
            when (rule.kind()) {
                MediumRule.MatchKind.STATE_ID -> if (!states.containsKey(rule.value())) states[rule.value()] = rule
                MediumRule.MatchKind.REGISTRY_ID -> if (!registries.containsKey(rule.value())) registries[rule.value()] = rule
                MediumRule.MatchKind.MEDIUM_ID -> if (!media.containsKey(rule.value())) media[rule.value()] = rule
                else -> generic.add(rule)
            }
        }
        stateRules = Collections.unmodifiableMap(states)
        registryRules = Collections.unmodifiableMap(registries)
        mediumRules = Collections.unmodifiableMap(media)
        genericRules = Collections.unmodifiableList(generic)
    }

    fun resolve(descriptor: MaterialDescriptor, inferred: AcousticMedium): AcousticMedium {
        var best = stateRules[descriptor.stateId()]
        val registry = registryRules[descriptor.registryId()]
        if (registry != null && (best == null || registry.priority() > best.priority())) best = registry
        val medium = mediumRules[inferred.id()]
        if (medium != null && (best == null || medium.priority() > best.priority())) best = medium
        val threshold = best?.priority() ?: Int.MIN_VALUE
        for (rule in genericRules) {
            if (best != null && rule.priority() < threshold) break
            if (rule.matches(descriptor)) {
                if (best == null || rule.priority() > best.priority()) best = rule
                break
            }
        }
        return best?.medium() ?: inferred
    }

    fun rules(): List<MediumRule> = rules
}
