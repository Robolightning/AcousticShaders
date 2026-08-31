package dev.acoustic.core.material.infer

import dev.acoustic.api.material.AcousticMaterial
import java.util.ArrayList
import java.util.Collections

/** Result of heuristic material inference. Confidence is descriptive, never used as fake physical certainty. */
class InferredMaterial(private val family: String, private val material: AcousticMaterial, confidence: Float, reasons: List<String>) {
    private val confidence = confidence.coerceIn(0f, 1f)
    private val reasons = Collections.unmodifiableList(ArrayList(reasons))
    fun family(): String = family
    fun material(): AcousticMaterial = material
    fun confidence(): Float = confidence
    fun reasons(): List<String> = reasons
}
