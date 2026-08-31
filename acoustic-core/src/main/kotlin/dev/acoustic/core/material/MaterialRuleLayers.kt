package dev.acoustic.core.material

import dev.acoustic.api.material.resolve.MaterialRule
import dev.acoustic.core.pack.MaterialPack
import java.util.ArrayList
import java.util.Collections

/** Priority rebasing for independently composable acoustic-material layers. */
object MaterialRuleLayers {
    @JvmStatic fun rebase(pack: MaterialPack, basePriority: Int): List<MaterialRule> = rebase(pack.rules(), basePriority)
    @JvmStatic fun rebase(rules: List<MaterialRule>, basePriority: Int): List<MaterialRule> {
        val out = ArrayList<MaterialRule>()
        for (rule in rules) {
            var p = basePriority.toLong() + rule.priority().toLong()
            if (p > Int.MAX_VALUE) p = Int.MAX_VALUE.toLong()
            if (p < Int.MIN_VALUE) p = Int.MIN_VALUE.toLong()
            out.add(rule.withPriority(p.toInt()))
        }
        return Collections.unmodifiableList(out)
    }
}
