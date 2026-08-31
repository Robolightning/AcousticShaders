package dev.acoustic.api.pipeline

interface ParallelWorkExecutor {
    fun interface RangeTask {
        @Throws(Exception::class)
        fun run(fromInclusive: Int, toExclusive: Int)
    }
    fun workerCount(): Int
    @Throws(Exception::class)
    fun forRange(fromInclusive: Int, toExclusive: Int, task: RangeTask)
}
