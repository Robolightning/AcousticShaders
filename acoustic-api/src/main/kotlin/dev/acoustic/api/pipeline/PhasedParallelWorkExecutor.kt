package dev.acoustic.api.pipeline

interface PhasedParallelWorkExecutor : ParallelWorkExecutor {
    interface WorkerTask {
        @Throws(Exception::class)
        fun run(workerIndex: Int, workerCount: Int)

        companion object {
            operator fun invoke(block: (Int, Int) -> Unit): WorkerTask = object : WorkerTask {
                override fun run(workerIndex: Int, workerCount: Int) = block(workerIndex, workerCount)
            }
        }
    }
    @Throws(Exception::class)
    fun forWorkers(workers: Int, task: WorkerTask)
}
