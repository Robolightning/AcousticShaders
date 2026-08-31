package dev.acoustic.core.pipeline

import dev.acoustic.api.pipeline.Pass
import dev.acoustic.api.pipeline.Pipeline
import dev.acoustic.api.pipeline.ResourceKey
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet

object PipelineValidator {
    @JvmStatic
    fun validate(pipeline: Pipeline) {
        val ids = HashMap<String, Pass>()
        val writer = HashMap<ResourceKey<*>, Pass>()
        for (pass in pipeline.passes()) {
            if (ids.put(pass.id(), pass) != null) throw IllegalArgumentException("Duplicate pass id: ${pass.id()}")
            for (resource in pass.writes()) {
                val previous = writer.put(resource, pass)
                if (previous != null) throw IllegalArgumentException("Multiple writers for $resource: ${previous.id()}, ${pass.id()}")
            }
        }
        val deps = dependencies(pipeline)
        val visiting = HashSet<Pass>()
        val visited = HashSet<Pass>()
        for (pass in pipeline.passes()) dfs(pass, deps, visiting, visited)
    }

    @JvmStatic
    fun dependencies(pipeline: Pipeline): Map<Pass, Set<Pass>> {
        val writer = HashMap<ResourceKey<*>, Pass>()
        for (pass in pipeline.passes()) for (key in pass.writes()) writer[key] = pass
        val result = LinkedHashMap<Pass, Set<Pass>>()
        for (pass in pipeline.passes()) {
            val deps = LinkedHashSet<Pass>()
            val inputs = LinkedHashSet<ResourceKey<*>>(pass.reads())
            inputs.addAll(pass.optionalReads())
            for (key in inputs) {
                val producer = writer[key]
                if (producer != null && producer !== pass) deps.add(producer)
            }
            result[pass] = deps
        }
        return result
    }

    private fun dfs(pass: Pass, deps: Map<Pass, Set<Pass>>, visiting: MutableSet<Pass>, visited: MutableSet<Pass>) {
        if (visited.contains(pass)) return
        if (!visiting.add(pass)) throw IllegalArgumentException("Pipeline dependency cycle involving ${pass.id()}")
        for (dependency in deps[pass]!!) dfs(dependency, deps, visiting, visited)
        visiting.remove(pass)
        visited.add(pass)
    }
}
