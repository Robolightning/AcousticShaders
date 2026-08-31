package dev.acoustic.api.pipeline

interface RuntimeParallelPass : Pass {
    @Throws(Exception::class)
    fun executeParallel(context: PassContext, parallel: ParallelWorkExecutor)
}
