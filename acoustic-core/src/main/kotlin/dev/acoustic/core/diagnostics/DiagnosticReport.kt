package dev.acoustic.core.diagnostics

import dev.acoustic.core.pipeline.ExecutionReport
import java.util.Collections
import java.util.LinkedHashMap

/** Text/JSON-free core diagnostic model so platform UIs can serialize as desired. */
class DiagnosticReport {
    private val values = LinkedHashMap<String, String>()
    fun put(k: String, v: Any?): DiagnosticReport { values[k] = java.lang.String.valueOf(v); return this }
    fun execution(report: ExecutionReport): DiagnosticReport {
        put("frame.total_ms", report.elapsedNanos() / 1_000_000.0)
        for (timing in report.timings()) put("pass.${timing.passId()}.ms", timing.millis())
        return this
    }
    fun values(): Map<String, String> = Collections.unmodifiableMap(values)
    fun toText(): String = buildString { for ((k, v) in values) append(k).append('=').append(v).append('\n') }
}
