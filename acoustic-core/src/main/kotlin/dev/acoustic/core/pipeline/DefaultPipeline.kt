package dev.acoustic.core.pipeline

import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.Pipeline
import java.util.ArrayList
import java.util.Collections

class DefaultPipeline(passes: List<Pass>) : Pipeline {
    private val passes = Collections.unmodifiableList(ArrayList(passes))
    override fun passes(): List<Pass> = passes
}
