package dev.acoustic.mc1122

import dev.acoustic.api.material.resolve.AcousticTags
import dev.acoustic.api.material.resolve.MaterialDescriptor
import java.util.HashSet

/** Cross-version normalization of cheap legacy facts used during live capture. */
object LegacyBlockDescriptor {
    @JvmStatic
    fun normalize(registryId: String, legacyMaterial: String?, soundType: String?, oreNames: Set<String>?, solid: Boolean): MaterialDescriptor =
        normalize(registryId, registryId, legacyMaterial, soundType, oreNames, solid)

    @JvmStatic
    fun normalize(registryId: String, stateId: String, legacyMaterial: String?, soundType: String?, oreNames: Set<String>?, solid: Boolean): MaterialDescriptor {
        val tags = HashSet<String>()
        val text = ((legacyMaterial ?: "") + " " + (soundType ?: "") + " " + registryId).lowercase()
        if (text.contains("wood") || text.contains("plank") || text.contains("log")) tags.add(AcousticTags.WOOD)
        if (text.contains("stone") || text.contains("rock") || text.contains("cobble")) tags.add(AcousticTags.STONE)
        if (text.contains("concrete") || text.contains("cement")) tags.add(AcousticTags.CONCRETE)
        if (text.contains("brick")) tags.add(AcousticTags.BRICK)
        if (text.contains("ceramic") || text.contains("terracotta") || text.contains("porcelain")) tags.add(AcousticTags.CERAMIC)
        if (text.contains("plaster") || text.contains("gypsum")) tags.add(AcousticTags.PLASTER)
        if (text.contains("glass")) tags.add(AcousticTags.GLASS)
        if (text.contains("metal") || text.contains("iron") || text.contains("steel")) tags.add(AcousticTags.METAL)
        if (text.contains("cloth") || text.contains("wool") || text.contains("fabric")) tags.add(AcousticTags.FABRIC)
        if (text.contains("carpet") || text.contains("rug")) tags.add(AcousticTags.CARPET)
        if (text.contains("rubber") || text.contains("latex")) tags.add(AcousticTags.RUBBER)
        if (text.contains("plastic") || text.contains("polymer") || text.contains("resin")) tags.add(AcousticTags.POLYMER)
        if (text.contains("water") || text.contains("lava") || text.contains("liquid") || text.contains("fluid")) tags.add(AcousticTags.LIQUID)
        if (text.contains("sand") || text.contains("gravel")) tags.add(AcousticTags.SAND)
        if (text.contains("grass") || text.contains("ground") || text.contains("dirt") || text.contains("clay") || text.contains("snow")) tags.add(AcousticTags.SOIL)
        if (text.contains("leaves") || text.contains("plant") || text.contains("vine") || text.contains("foliage")) tags.add(AcousticTags.FOLIAGE)
        if (oreNames != null) {
            for (ore in oreNames) {
                val o = ore.lowercase()
                if (o.contains("wood") || o.contains("plank") || o.contains("log")) tags.add(AcousticTags.WOOD)
                if (o.contains("glass")) tags.add(AcousticTags.GLASS)
                if (o.contains("wool") || o.contains("cloth")) tags.add(AcousticTags.FABRIC)
                if (o.contains("rubber")) tags.add(AcousticTags.RUBBER)
                if (o.contains("plastic") || o.contains("polymer")) tags.add(AcousticTags.POLYMER)
                if (o.contains("concrete")) tags.add(AcousticTags.CONCRETE)
                if (o.contains("brick")) tags.add(AcousticTags.BRICK)
                if (o.contains("sand") || o.contains("gravel")) tags.add(AcousticTags.SAND)
                if ((o.startsWith("block") || o.startsWith("plate") || o.startsWith("ore")) &&
                    (o.contains("iron") || o.contains("copper") || o.contains("steel") || o.contains("tin") || o.contains("lead") || o.contains("silver") || o.contains("gold") || o.contains("aluminum") || o.contains("aluminium"))) {
                    tags.add(AcousticTags.METAL)
                }
            }
        }
        return MaterialDescriptor(registryId, stateId, tags, oreNames, solid)
    }
}
