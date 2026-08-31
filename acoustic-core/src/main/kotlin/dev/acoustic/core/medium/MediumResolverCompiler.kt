package dev.acoustic.core.medium

import dev.acoustic.api.environment.resolve.MediumRule
import dev.acoustic.core.pack.LoadedShaderPack
import java.util.Collections

object MediumResolverCompiler {
    @JvmStatic fun compile(pack: LoadedShaderPack): MediumResolver = compile(pack, Collections.emptyList())
    @JvmStatic fun compile(pack: LoadedShaderPack, externalRules: List<MediumRule>?): MediumResolver {
        val rules = ArrayList<MediumRule>()
        for (mediumPack in pack.mediumPacks().values) rules.addAll(MediumRuleLayers.rebase(mediumPack, 0))
        if (externalRules != null) rules.addAll(externalRules)
        return MediumResolver(rules)
    }
}
