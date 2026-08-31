package dev.acoustic.mc1122

import java.util.Collections

/** Runtime detection rules kept behind a callback so tests do not load optional coremods. */
class LegacyCompatibilityProbe {
    fun interface ClassLookup { fun present(binaryName: String): Boolean }

    class Result internal constructor(
        @JvmField val optifine: Boolean,
        @JvmField val mixinBooter: Boolean,
        @JvmField val loliAsmFamily: Boolean,
        evidence: List<String>
    ) {
        @JvmField val evidence: List<String> = Collections.unmodifiableList(ArrayList(evidence))
    }

    fun probe(c: ClassLookup): Result {
        val evidence = ArrayList<String>()
        val optifine = hit(c, evidence, "optifine.OptiFineClassTransformer") || hit(c, evidence, "Config")
        val mixin = hit(c, evidence, "zone.rong.mixinbooter.MixinBooterPlugin") || hit(c, evidence, "io.github.legacymoddingmc.unimixins.common.UnimixinsMod")
        val loli = hit(c, evidence, "zone.rong.loliasm.LoliASM") || hit(c, evidence, "zone.rong.loliasm.core.LoliLoadingPlugin") || hit(c, evidence, "com.cleanroommc.blackbox.Blackbox")
        return Result(optifine, mixin, loli, evidence)
    }

    private fun hit(c: ClassLookup, evidence: MutableList<String>, name: String): Boolean {
        if (c.present(name)) { evidence.add(name); return true }
        return false
    }
}
