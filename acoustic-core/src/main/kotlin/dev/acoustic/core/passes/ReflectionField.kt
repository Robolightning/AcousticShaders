package dev.acoustic.core.passes

import java.util.ArrayList
import java.util.Collections

class ReflectionField(private val raysTraced: Int, samples: List<ReflectionSample>) {
    private val samples = Collections.unmodifiableList(ArrayList(samples))
    fun raysTraced(): Int = raysTraced
    fun samples(): List<ReflectionSample> = samples
}
