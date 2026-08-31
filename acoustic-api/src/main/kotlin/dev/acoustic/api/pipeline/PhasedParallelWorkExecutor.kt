package dev.acoustic.api.pipeline

interface PhasedParallelWorkExecutor : ParallelWorkExecutor {
    fun interface WorkerTask {
        @Throws(Exception::class)
        fun run(workerIndex: Int, workerCount: Int)
    }
    @Throws(Exception::class)
    fun forWorkers(workers: Int, task: WorkerTask)
}
