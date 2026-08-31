package dev.acoustic.core.runtime

import dev.acoustic.api.pipeline.Pass
import dev.acoustic.core.pack.LoadedShaderPack
import dev.acoustic.core.pipeline.DefaultPipeline
import java.util.ArrayList
import java.util.Collections

/** Compiles declarative pack passes against an extensible algorithm registry. */
class StandardPipelineCompiler(private val registry: PassFactoryRegistry = StandardPassFactories.create()) {
    fun compile(pack: LoadedShaderPack, profileName: String): DefaultPipeline = compile(pack, profileName, Collections.emptyMap())
    fun compile(pack: LoadedShaderPack, profileName: String, overrides: Map<String, String>?): DefaultPipeline {
        val profile = ResolvedProfile.from(pack.options(), profileName, overrides)
        val passes = ArrayList<Pass>()
        for (definition in pack.pipeline().passes()) if (profile.enabledExpression(definition.enabled())) passes.add(registry.require(definition.id()).create(definition, profile))
        return DefaultPipeline(passes)
    }
}
