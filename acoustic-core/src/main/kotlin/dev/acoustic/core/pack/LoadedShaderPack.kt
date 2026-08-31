package dev.acoustic.core.pack

import java.util.Collections
import java.util.LinkedHashMap

class LoadedShaderPack(
    private val manifest: ShaderPackManifest,
    private val pipeline: PipelineDefinition,
    private val options: PackOptions,
    materialPacks: Map<String, MaterialPack>,
    mediumPacks: Map<String, MediumPack>
) {
    private val materialPacks = Collections.unmodifiableMap(LinkedHashMap(materialPacks))
    private val mediumPacks = Collections.unmodifiableMap(LinkedHashMap(mediumPacks))

    constructor(manifest: ShaderPackManifest, pipeline: PipelineDefinition, options: PackOptions, materialPacks: Map<String, MaterialPack>) :
        this(manifest, pipeline, options, materialPacks, emptyMap())

    fun manifest(): ShaderPackManifest = manifest
    fun pipeline(): PipelineDefinition = pipeline
    fun options(): PackOptions = options
    fun materialPacks(): Map<String, MaterialPack> = materialPacks
    fun mediumPacks(): Map<String, MediumPack> = mediumPacks
}
