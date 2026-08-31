package dev.acoustic.core.runtime

/** Conservative online worker-count tuner; changes only after repeated evidence. */
class WorkerTuner(initial: Int, private val max: Int) {
    private var workers = initial
    private var bestMs = Double.POSITIVE_INFINITY
    private var badSamples = 0
    init { require(initial >= 1 && max >= initial) }
    fun workers(): Int = workers
    fun sample(elapsedMs: Double) {
        if (elapsedMs <= 0) return
        if (elapsedMs < bestMs * 0.92) {
            bestMs = elapsedMs
            badSamples = 0
            if (workers < max) workers++
        } else if (elapsedMs > bestMs * 1.20) {
            badSamples++
            if (badSamples >= 3 && workers > 1) {
                workers--
                badSamples = 0
                bestMs = elapsedMs
            }
        } else badSamples = 0
    }
}
