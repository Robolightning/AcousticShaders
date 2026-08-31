package dev.acoustic.core.pipeline

import java.util.ArrayList
import java.util.Collections

class ExecutionReport(private val elapsedNanos: Long, timings: List<PassTiming>) {
    private val timings = Collections.unmodifiableList(ArrayList(timings))
    fun elapsedNanos(): Long = elapsedNanos
    fun elapsedMillis(): Double = elapsedNanos / 1_000_000.0
    fun timings(): List<PassTiming> = timings
    fun timing(passId: String): PassTiming? = timings.firstOrNull { it.passId() == passId }
}
