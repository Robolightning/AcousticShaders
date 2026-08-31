package dev.acoustic.core.scene

import java.util.concurrent.atomic.AtomicReference

/** Lock-free publication point from simulation workers to consumers such as an audio thread. */
class SnapshotExchange<T : Any>(initial: T) {
    private val current = AtomicReference(initial)
    fun current(): T = current.get()
    fun publish(value: T): T = current.getAndSet(value)
}
