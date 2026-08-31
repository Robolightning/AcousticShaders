package dev.acoustic.core.source.profile

import dev.acoustic.api.source.SourceProfileRule
import java.util.Collections

object SourceProfileRuleLayers {
    @JvmStatic
    fun rebase(rules: List<SourceProfileRule>, basePriority: Int): List<SourceProfileRule> {
        val out = ArrayList<SourceProfileRule>(rules.size)
        for (rule in rules) {
            var priority = basePriority.toLong() + rule.priority().toLong()
            if (priority > Int.MAX_VALUE.toLong()) priority = Int.MAX_VALUE.toLong()
            if (priority < Int.MIN_VALUE.toLong()) priority = Int.MIN_VALUE.toLong()
            out.add(rule.withPriority(priority.toInt()))
        }
        return Collections.unmodifiableList(out)
    }
}
