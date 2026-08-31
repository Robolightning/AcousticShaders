package dev.acoustic.core.material

import dev.acoustic.api.material.AcousticMaterials
import dev.acoustic.api.material.resolve.AcousticTags
import dev.acoustic.api.material.resolve.MaterialRule
import dev.acoustic.core.pack.LoadedShaderPack
import java.util.Collections

/** Builds the effective resolver. Material-resource layers may override legacy shader-pack material rules. */
object MaterialResolverCompiler {
    @JvmStatic fun compile(pack: LoadedShaderPack): MaterialResolver = compile(pack, Collections.emptyList())

    @JvmStatic
    fun compile(pack: LoadedShaderPack, externalRules: List<MaterialRule>?): MaterialResolver {
        val rules = ArrayList<MaterialRule>()
        addFallbacks(rules)
        for (materialPack in pack.materialPacks().values) rules.addAll(MaterialRuleLayers.rebase(materialPack, 0))
        if (externalRules != null) rules.addAll(externalRules)
        return MaterialResolver(rules, AcousticMaterials.STONE, AcousticMaterials.AIR)
    }

    private fun addFallbacks(rules: MutableList<MaterialRule>) {
        val priority = -1_000_000
        fun add(tag: String, material: dev.acoustic.api.material.AcousticMaterial) {
            rules.add(MaterialRule(priority, MaterialRule.MatchKind.TAG, tag, material))
        }
        add(AcousticTags.METAL, AcousticMaterials.METAL); add(AcousticTags.WOOD, AcousticMaterials.WOOD); add(AcousticTags.GLASS, AcousticMaterials.GLASS)
        add(AcousticTags.CARPET, AcousticMaterials.CARPET); add(AcousticTags.FABRIC, AcousticMaterials.WOOL); add(AcousticTags.RUBBER, AcousticMaterials.RUBBER); add(AcousticTags.POLYMER, AcousticMaterials.POLYMER)
        add(AcousticTags.CONCRETE, AcousticMaterials.CONCRETE); add(AcousticTags.BRICK, AcousticMaterials.BRICK); add(AcousticTags.CERAMIC, AcousticMaterials.CERAMIC); add(AcousticTags.PLASTER, AcousticMaterials.PLASTER)
        add(AcousticTags.SAND, AcousticMaterials.SAND); add(AcousticTags.SOIL, AcousticMaterials.SOIL); add(AcousticTags.FOLIAGE, AcousticMaterials.FOLIAGE); add(AcousticTags.LIQUID, AcousticMaterials.LIQUID); add(AcousticTags.STONE, AcousticMaterials.STONE)
    }
}
