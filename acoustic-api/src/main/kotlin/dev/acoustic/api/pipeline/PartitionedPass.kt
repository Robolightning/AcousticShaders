package dev.acoustic.api.pipeline

import java.util.Collections

interface PartitionedPass : Pass {
    fun workUnits(): Int
    @Throws(Exception::class)
    fun executePartition(context: PassContext, fromInclusive: Int, toExclusive: Int): Any?
    @Throws(Exception::class)
    fun combine(context: PassContext, partitionResults: List<@JvmSuppressWildcards Any?>)
    @Throws(Exception::class)
    override fun execute(context: PassContext) {
        val result = executePartition(context, 0, workUnits())
        combine(context, Collections.singletonList(result))
    }
}
