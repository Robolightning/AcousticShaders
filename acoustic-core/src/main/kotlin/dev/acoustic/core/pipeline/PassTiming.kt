package dev.acoustic.core.pipeline

class PassTiming(private val passId: String, private val nanos: Long, private val threadName: String) {
    fun passId(): String = passId
    fun nanos(): Long = nanos
    fun millis(): Double = nanos / 1_000_000.0
    fun threadName(): String = threadName
}
