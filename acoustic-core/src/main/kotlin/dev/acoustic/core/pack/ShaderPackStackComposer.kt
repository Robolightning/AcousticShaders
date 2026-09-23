package dev.acoustic.core.pack

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** Deterministically composes an ordered acoustic-shader stack. */
object ShaderPackStackComposer {
    @JvmStatic
    fun compose(stack: List<LoadedShaderPack>?): LoadedShaderPack {
        require(!stack.isNullOrEmpty()) { "shader stack must contain at least one pack" }
        val base = stack[0]
        val passes = LinkedHashMap<String, PipelineDefinition.PassDefinition>()
        val options = LinkedHashMap<String, String>()
        val materials = LinkedHashMap<String, MaterialPack>()
        val media = LinkedHashMap<String, MediumPack>()
        val format = base.pipeline().format()
        val packIds = LinkedHashSet<String>()
        for (pack in stack) {
            require(packIds.add(pack.manifest().id())) { "duplicate shader pack id in stack: ${pack.manifest().id()}" }
            require(pack.pipeline().format() == format) { "pipeline format mismatch in shader stack" }
            for (pass in pack.pipeline().passes()) passes[pass.id()] = pass
            options.putAll(pack.options().values())
            materials.putAll(pack.materialPacks())
            media.putAll(pack.mediumPacks())
        }
        val pipeline = PipelineDefinition.of(format, ArrayList(passes.values))
        val manifest = ShaderPackManifest.compose(stack.map { it.manifest() })
        return LoadedShaderPack(manifest, pipeline, PackOptions.of(options), materials, media)
    }

    @JvmStatic
    fun names(stack: List<LoadedShaderPack>?): List<String> {
        if (stack == null) return emptyList()
        val out = ArrayList<String>(stack.size)
        for (pack in stack) out.add(pack.manifest().name())
        return Collections.unmodifiableList(out)
    }
}
