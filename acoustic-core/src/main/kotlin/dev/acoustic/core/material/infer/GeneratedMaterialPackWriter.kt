package dev.acoustic.core.material.infer

import dev.acoustic.core.compat.codeCompat
import dev.acoustic.api.material.AcousticMaterial
import java.util.LinkedHashMap
import java.util.Locale

/** Deterministic JSON writer for the auto-generated default acoustic material resource pack. */
class GeneratedMaterialPackWriter {
    class Row(private val facts: MaterialFacts, private val inferred: InferredMaterial) {
        fun facts(): MaterialFacts = facts
        fun inferred(): InferredMaterial = inferred
    }

    fun write(rows: List<Row>, fingerprint: String): String {
        val sorted = ArrayList(rows)
        sorted.sortWith(Comparator { a, b -> a.facts().stateId().compareTo(b.facts().stateId()) })
        val materials = LinkedHashMap<String, AcousticMaterial>()
        for (row in sorted) materials[materialKey(row)] = row.inferred().material()
        val out = StringBuilder(16384)
        out.append("{\n  \"_generated\": {\"schema\": ").append(MaterialInferenceEngine.SCHEMA_VERSION)
            .append(", \"fingerprint\": \"").append(escape(fingerprint)).append("\", \"states\": ").append(sorted.size)
            .append("},\n  \"materials\": {\n")
        var materialIndex = 0
        for ((key, material) in materials) {
            if (materialIndex++ > 0) out.append(",\n")
            out.append("    \"").append(escape(key)).append("\": {\"absorption\": [")
            val absorption = material.absorptionSpectrum()
            var i = 0
            while (i < absorption.size) { if (i > 0) out.append(", "); out.append(number(absorption[i])); i++ }
            out.append("], \"scattering\": ").append(number(material.scattering())).append(", \"transmission\": ").append(number(material.transmission())).append('}')
        }
        out.append("\n  },\n  \"rules\": [\n")
        var ruleIndex = 0
        for (row in sorted) {
            if (ruleIndex++ > 0) out.append(",\n")
            out.append("    {\"priority\": 0, \"kind\": \"STATE_ID\", \"match\": \"").append(escape(row.facts().stateId()))
                .append("\", \"material\": \"").append(escape(materialKey(row))).append("\"}")
        }
        out.append("\n  ],\n  \"inference\": [\n")
        var inferenceIndex = 0
        for (row in sorted) {
            if (inferenceIndex++ > 0) out.append(",\n")
            out.append("    {\"state\": \"").append(escape(row.facts().stateId())).append("\", \"family\": \"")
                .append(escape(row.inferred().family())).append("\", \"confidence\": ").append(number(row.inferred().confidence())).append(", \"ore\": [")
            var oreIndex = 0
            for (ore in row.facts().dictionaryNames()) { if (oreIndex++ > 0) out.append(", "); out.append('"').append(escape(ore)).append('"') }
            out.append("], \"reasons\": [")
            var reasonIndex = 0
            for (reason in row.inferred().reasons()) { if (reasonIndex++ > 0) out.append(", "); out.append('"').append(escape(reason)).append('"') }
            out.append("]}")
        }
        out.append("\n  ]\n}\n")
        return out.toString()
    }

    private fun materialKey(row: Row): String {
        val material = row.inferred().material()
        val signature = StringBuilder(row.inferred().family()).append('|')
        for (value in material.absorptionSpectrum()) signature.append(Math.round(value * 10000f)).append(',')
        signature.append(Math.round(material.scattering() * 10000f)).append('|').append(Math.round(material.transmission() * 10000f))
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325 unsigned as signed long
        val profile = signature.toString()
        var i = 0
        while (i < profile.length) {
            val c = profile[i].codeCompat()
            hash = hash xor (c and 0xff).toLong(); hash *= 0x100000001b3L
            hash = hash xor ((c ushr 8) and 0xff).toLong(); hash *= 0x100000001b3L
            i++
        }
        return "generated:${row.inferred().family()}:${String.format(Locale.ROOT, "%016x", java.lang.Long.valueOf(hash))}"
    }

    private fun number(value: Float): String {
        var text = String.format(Locale.ROOT, "%.5f", java.lang.Float.valueOf(value))
        while (text.indexOf('.') >= 0 && text.endsWith("0")) text = text.substring(0, text.length - 1)
        if (text.endsWith(".")) text = text.substring(0, text.length - 1)
        return text
    }

    private fun escape(value: String): String {
        val out = StringBuilder()
        for (c in value) {
            when {
                c == '"' || c == '\\' -> out.append('\\').append(c)
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.codeCompat() < 32 -> out.append('?')
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
