package dev.acoustic.core.pipeline

import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.Pipeline
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** Immutable compiled execution plan. Levels contain passes that may execute concurrently. */
class PipelinePlan private constructor(
    private val levels: List<List<Pass>>,
    private val dependencies: Map<Pass, Set<Pass>>
) {
    fun levels(): List<List<Pass>> = levels
    fun dependencies(): Map<Pass, Set<Pass>> = dependencies

    companion object {
        @JvmStatic
        fun compile(pipeline: Pipeline): PipelinePlan {
            PipelineValidator.validate(pipeline)
            val deps = PipelineValidator.dependencies(pipeline)
            val depth = IdentityHashMap<Pass, Int>()
            var max = 0
            for (pass in pipeline.passes()) {
                val value = depth(pass, deps, depth)
                if (value > max) max = value
            }
            val mutable = ArrayList<MutableList<Pass>>(max + 1)
            var i = 0
            while (i <= max) { mutable.add(ArrayList()); i++ }
            for (pass in pipeline.passes()) mutable[depth[pass]!!].add(pass)
            val frozen = ArrayList<List<Pass>>(mutable.size)
            for (level in mutable) frozen.add(Collections.unmodifiableList(ArrayList(level)))
            val frozenDeps = LinkedHashMap<Pass, Set<Pass>>()
            for ((pass, passDeps) in deps) frozenDeps[pass] = Collections.unmodifiableSet(LinkedHashSet(passDeps))
            return PipelinePlan(Collections.unmodifiableList(frozen), Collections.unmodifiableMap(frozenDeps))
        }

        private fun depth(pass: Pass, deps: Map<Pass, Set<Pass>>, cache: MutableMap<Pass, Int>): Int {
            val known = cache[pass]
            if (known != null) return known
            var max = -1
            for (dependency in deps[pass]!!) max = Math.max(max, depth(dependency, deps, cache))
            val value = max + 1
            cache[pass] = value
            return value
        }
    }
}
