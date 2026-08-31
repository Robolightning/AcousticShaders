package dev.acoustic.core.compute

/** Optional platform backend for an FDTD solve, typically OpenCL/CUDA. */
interface FdtdExternalBackend {
    fun id(): String
    fun description(): String
    fun available(): Boolean
    fun supports(problem: FdtdProblem): Boolean
    fun preferredForAuto(problem: FdtdProblem): Boolean = supports(problem)
    fun autoPriority(): Int = 0
    @Throws(Exception::class) fun solve(problem: FdtdProblem): FloatArray
    fun solveCount(): Long = -1L
    fun failureCount(): Long = -1L
    fun lastSolveMillis(): Double = Double.NaN
    fun lastFailure(): String = ""
    fun close() {}
}
