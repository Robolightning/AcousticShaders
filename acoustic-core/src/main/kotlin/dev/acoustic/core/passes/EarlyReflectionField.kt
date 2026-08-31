package dev.acoustic.core.passes

import java.util.ArrayList
import java.util.Collections

class EarlyReflectionField(events: List<EarlyReflection>) {
    private val events = Collections.unmodifiableList(ArrayList(events))
    fun events(): List<EarlyReflection> = events
}
