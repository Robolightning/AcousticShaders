package dev.acoustic.core.runtime

import dev.acoustic.api.pipeline.Pass
import dev.acoustic.core.pack.PipelineDefinition

/** Extension point for new algorithms/backends without changing the shader-pack format or core compiler. */
interface PassFactory {
    fun create(definition: PipelineDefinition.PassDefinition, profile: ResolvedProfile): Pass

    companion object {
        operator fun invoke(block: (PipelineDefinition.PassDefinition, ResolvedProfile) -> Pass): PassFactory =
            object : PassFactory {
                override fun create(definition: PipelineDefinition.PassDefinition, profile: ResolvedProfile): Pass =
                    block(definition, profile)
            }
    }
}
