package dev.acoustic.core.medium

import dev.acoustic.api.environment.resolve.MediumRule
import dev.acoustic.core.pack.MediumPack
import java.util.Collections

object MediumRuleLayers {
    @JvmStatic fun rebase(pack: MediumPack, basePriority: Int): List<MediumRule> = rebase(pack.rules(), basePriority)
    @JvmStatic fun rebase(rules: List<MediumRule>, basePriority: Int): List<MediumRule> {
        val out = ArrayList<MediumRule>(rules.size)
        for (rule in rules) {
            val sum = basePriority.toLong() + rule.priority().toLong()
            out.add(rule.withPriority(sum.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()))
        }
        return Collections.unmodifiableList(out)
    }
}
