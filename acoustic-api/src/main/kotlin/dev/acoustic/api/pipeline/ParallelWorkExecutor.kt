package dev.acoustic.api.pipeline

interface ParallelWorkExecutor {
    interface RangeTask {
        @Throws(Exception::class)
        fun run(fromInclusive: Int, toExclusive: Int)

        companion object {
            operator fun invoke(block: (Int, Int) -> Unit): RangeTask = object : RangeTask {
                override fun run(fromInclusive: Int, toExclusive: Int) = block(fromInclusive, toExclusive)
            }
        }
    }
    fun workerCount(): Int
    @Throws(Exception::class)
    fun forRange(fromInclusive: Int, toExclusive: Int, task: RangeTask)
}
