package dev.acoustic.core.material.infer

import dev.acoustic.core.compat.lowercaseCompat
import dev.acoustic.api.material.AcousticMaterial
import dev.acoustic.api.material.AcousticMaterials
import dev.acoustic.api.material.FrequencyBands
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Conservative best-effort classifier for unknown/modded blocks. */
class MaterialInferenceEngine {
    fun infer(f: MaterialFacts): InferredMaterial {
        if (f.liquid()) return result("liquid", AcousticMaterials.LIQUID, 0.99f, f, "liquid fact")
        if (!f.solid() && !f.fullCube() && !f.opaque()) {
            val all = text(f)
            if (!containsAny(all, "leaves", "leaf", "foliage", "vine", "plant", "grass", "hedge")) {
                return result("air", AcousticMaterials.AIR, 0.95f, f, "non-solid/non-opaque")
            }
        }
        val score = LinkedHashMap<String, Double>()
        for (family in FAMILIES) score[family] = 0.0
        val reasons = ArrayList<String>()
        val registry = f.registryId().lowercaseCompat(Locale.ROOT)
        val material = f.materialName().lowercaseCompat(Locale.ROOT)
        val sound = f.soundTypeName().lowercaseCompat(Locale.ROOT)
        val joined = "$registry $material $sound"

        semantic(score, reasons, material, 3.4, "material")
        semantic(score, reasons, sound, 3.0, "sound")
        semantic(score, reasons, registry, 1.8, "registry")
        for (ore in f.dictionaryNames()) oreEvidence(score, reasons, ore)

        if (containsAny(joined, "pane", "window")) { add(score, "glass", 1.8); reasons.add("thin/window form") }
        if (containsAny(joined, "carpet", "rug")) { add(score, "carpet", 4.0); reasons.add("carpet token") }
        if (containsAny(joined, "foam", "sponge")) { add(score, "fabric", 2.8); add(score, "rubber", 0.8); reasons.add("porous token") }
        if (containsAny(joined, "machine", "casing", "chassis")) { add(score, "metal", 1.2); reasons.add("machine/casing token") }
        if (containsAny(joined, "terracotta", "porcelain", "tile")) { add(score, "ceramic", 2.2); reasons.add("ceramic token") }

        if (f.hardness() >= 5f) { add(score, "stone", 0.25); add(score, "metal", 0.25) }
        if (f.resistance() >= 20f) { add(score, "stone", 0.20); add(score, "metal", 0.20) }
        if (f.hardness() > 0f && f.hardness() < 0.6f) { add(score, "fabric", 0.15); add(score, "soil", 0.15); add(score, "rubber", 0.10) }

        var best = "stone"
        var second = "stone"
        var bestScore = -1.0
        var secondScore = -1.0
        for ((key, value) in score) {
            if (value > bestScore) {
                second = best
                secondScore = bestScore
                best = key
                bestScore = value
            } else if (value > secondScore) {
                second = key
                secondScore = value
            }
        }
        if (bestScore <= 0.01) {
            best = if (f.solid()) "stone" else "air"
            bestScore = 0.2
            secondScore = 0.0
            reasons.add("generic fallback")
        }
        val base = prototype(best)
        val confidence = max(0.20, min(0.99, bestScore / (bestScore + max(0.6, secondScore + 0.6)))).toFloat()
        val adjusted = adjust("generated:${sanitize(best)}:${shortState(f.stateId())}", base, f, confidence)
        reasons.add("family=$best score=${round(bestScore)} runnerUp=$second score=${round(secondScore)}")
        if (f.hardness() != 0f) reasons.add("hardness=${f.hardness()}")
        if (f.resistance() != 0f) reasons.add("resistance=${f.resistance()}")
        return InferredMaterial(best, adjusted, confidence, reasons)
    }

    companion object {
        @JvmField val SCHEMA_VERSION: Int = 2
        private val FAMILIES = arrayOf("metal", "glass", "rubber", "polymer", "fabric", "carpet", "foliage", "liquid", "sand", "soil", "wood", "concrete", "brick", "ceramic", "plaster", "stone")

        private fun result(family: String, material: AcousticMaterial, confidence: Float, facts: MaterialFacts, reason: String): InferredMaterial {
            val reasons = arrayListOf(reason)
            return InferredMaterial(family, adjust("generated:$family:${shortState(facts.stateId())}", material, facts, confidence), confidence, reasons)
        }

        private fun adjust(id: String, base: AcousticMaterial, f: MaterialFacts, confidence: Float): AcousticMaterial {
            val absorption = base.absorptionSpectrum()
            var scatter = base.scattering()
            var transmission = base.transmission()
            if (!f.fullCube()) { scatter += 0.12f; transmission += 0.06f }
            if (!f.opaque()) transmission += 0.05f
            val hard = f.hardness()
            val resistance = f.resistance()
            val hardFactor = when {
                hard >= 8f -> 0.88f
                hard >= 4f -> 0.94f
                hard > 0f && hard < 0.5f -> 1.10f
                else -> 1f
            }
            var i = 0
            while (i < absorption.size) {
                val hf = 1f + (i / (FrequencyBands.COUNT - 1).toFloat()) * 0.08f * (1f - confidence)
                absorption[i] = clamp(absorption[i] * hardFactor * hf, 0f, 0.96f)
                i++
            }
            transmission *= when {
                resistance >= 30f -> 0.72f
                resistance >= 10f -> 0.85f
                resistance > 0f && resistance < 2f -> 1.12f
                else -> 1f
            }
            transmission = clamp(transmission, 0f, 0.90f)
            scatter = clamp(scatter, 0f, 0.95f)
            i = 0
            while (i < absorption.size) {
                if (absorption[i] + transmission > 0.985f) absorption[i] = max(0f, 0.985f - transmission)
                i++
            }
            return AcousticMaterial(id, absorption, scatter, transmission)
        }

        private fun prototype(family: String): AcousticMaterial = when (family) {
            "metal" -> AcousticMaterials.METAL
            "glass" -> AcousticMaterials.GLASS
            "rubber" -> AcousticMaterials.RUBBER
            "polymer" -> AcousticMaterials.POLYMER
            "fabric" -> AcousticMaterials.WOOL
            "carpet" -> AcousticMaterials.CARPET
            "foliage" -> AcousticMaterials.FOLIAGE
            "liquid" -> AcousticMaterials.LIQUID
            "sand" -> AcousticMaterials.SAND
            "soil" -> AcousticMaterials.SOIL
            "wood" -> AcousticMaterials.WOOD
            "concrete" -> AcousticMaterials.CONCRETE
            "brick" -> AcousticMaterials.BRICK
            "ceramic" -> AcousticMaterials.CERAMIC
            "plaster" -> AcousticMaterials.PLASTER
            else -> AcousticMaterials.STONE
        }

        private fun semantic(score: MutableMap<String, Double>, reasons: MutableList<String>, text: String?, weight: Double, source: String) {
            if (text.isNullOrEmpty()) return
            hit(score,reasons,text,weight,source,"metal", arrayOf("metal","iron","steel","copper","bronze","brass","aluminium","aluminum","silver","gold","lead","tin","nickel","zinc","titanium"))
            hit(score,reasons,text,weight,source,"glass", arrayOf("glass","crystal","quartz_window")); hit(score,reasons,text,weight,source,"wood", arrayOf("wood","plank","log","timber","bamboo"))
            hit(score,reasons,text,weight,source,"concrete", arrayOf("concrete","cement")); hit(score,reasons,text,weight,source,"brick", arrayOf("brick","masonry")); hit(score,reasons,text,weight,source,"ceramic", arrayOf("ceramic","terracotta","porcelain","tile")); hit(score,reasons,text,weight,source,"plaster", arrayOf("plaster","gypsum","drywall"))
            hit(score,reasons,text,weight,source,"fabric", arrayOf("cloth","wool","fabric","felt","foam","sponge")); hit(score,reasons,text,weight,source,"carpet", arrayOf("carpet","rug"))
            hit(score,reasons,text,weight,source,"rubber", arrayOf("rubber","latex")); hit(score,reasons,text,weight,source,"polymer", arrayOf("plastic","polymer","resin","pvc","acrylic"))
            hit(score,reasons,text,weight,source,"foliage", arrayOf("leaves","leaf","foliage","vine","plant","hedge")); hit(score,reasons,text,weight,source,"sand", arrayOf("sand","gravel")); hit(score,reasons,text,weight,source,"soil", arrayOf("soil","dirt","ground","grass","clay","snow","mud")); hit(score,reasons,text,weight,source,"liquid", arrayOf("water","lava","liquid","fluid"))
            hit(score,reasons,text,weight,source,"stone", arrayOf("stone","rock","cobble","basalt","granite","diorite","andesite","marble","slate","obsidian"))
        }

        private fun oreEvidence(score: MutableMap<String, Double>, reasons: MutableList<String>, ore: String?) {
            val original = ore ?: ""
            val value = original.lowercaseCompat(Locale.ROOT)
            if (value.length == 0) return
            val weight = 4.5
            when {
                containsAny(value,"wood","plank","log") -> { add(score,"wood",weight); reasons.add("ore:$original->wood") }
                containsAny(value,"glass") -> { add(score,"glass",weight); reasons.add("ore:$original->glass") }
                containsAny(value,"wool","cloth","fabric") -> { add(score,"fabric",weight); reasons.add("ore:$original->fabric") }
                containsAny(value,"rubber") -> { add(score,"rubber",weight); reasons.add("ore:$original->rubber") }
                containsAny(value,"plastic","polymer") -> { add(score,"polymer",weight); reasons.add("ore:$original->polymer") }
                containsAny(value,"concrete","cement") -> { add(score,"concrete",weight); reasons.add("ore:$original->concrete") }
                containsAny(value,"brick") -> { add(score,"brick",weight); reasons.add("ore:$original->brick") }
                containsAny(value,"sand","gravel") -> { add(score,"sand",weight); reasons.add("ore:$original->sand") }
                isMetalOreName(value) -> { val form = if (startsAny(value,"block","plate","sheet","ingot","nugget","gear","rod","stick","wire","ore")) weight else 3.2; add(score,"metal",form); reasons.add("ore:$original->metal") }
                else -> semantic(score,reasons,value,2.7,"ore")
            }
        }

        private fun isMetalOreName(value: String): Boolean = containsAny(value,"iron","steel","copper","bronze","brass","aluminium","aluminum","silver","gold","lead","tin","nickel","zinc","titanium","electrum","invar","constantan","uranium")
        private fun startsAny(value: String, vararg tokens: String): Boolean = tokens.any { value.startsWith(it) }
        private fun hit(score: MutableMap<String, Double>, reasons: MutableList<String>, text: String, weight: Double, source: String, family: String, tokens: Array<String>) {
            for (token in tokens) if (text.contains(token)) { add(score,family,weight); reasons.add("$source:$token->$family"); return }
        }
        private fun add(score: MutableMap<String, Double>, key: String, value: Double) { val old = score[key]; if (old != null) score[key] = old + value }
        private fun containsAny(value: String, vararg tokens: String): Boolean = tokens.any { value.contains(it) }
        private fun text(f: MaterialFacts): String = buildString { append(f.registryId()).append(' ').append(f.materialName()).append(' ').append(f.soundTypeName()); for (ore in f.dictionaryNames()) append(' ').append(ore) }.lowercaseCompat(Locale.ROOT)
        private fun clamp(value: Float, lo: Float, hi: Float): Float = if (value < lo) lo else if (value > hi) hi else value
        private fun sanitize(value: String): String = value.replace(Regex("[^a-z0-9_.-]"), "_")
        private fun shortState(value: String): String = Integer.toHexString(value.hashCode())
        private fun round(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    }
}
