package dev.acoustic.core.source

import dev.acoustic.api.math.Vec3
import java.util.Collections

/** Deterministically ranks audible sources so expensive propagation is spent where perception benefits most. */
class SourceBudgetAllocator {
    fun allocate(sources: List<SourceCandidate>?, listener: Vec3?, highQualitySlots: Int, mediumQualitySlots: Int): Allocation {
        require(sources != null && listener != null && highQualitySlots >= 0 && mediumQualitySlots >= 0)
        val ranked = ArrayList<Scored>()
        for (source in sources) {
            val distance = Math.max(0.5, source.position().subtract(listener).length())
            val score = source.gain() * source.importance() / (1.0 + distance * distance * 0.04)
            if (score > 1e-8) ranked.add(Scored(source, score))
        }
        ranked.sortWith(Comparator { a, b ->
            val scoreCmp = java.lang.Double.compare(b.score, a.score)
            if (scoreCmp != 0) scoreCmp else java.lang.Long.compare(a.source.id(), b.source.id())
        })
        val high = ArrayList<SourceCandidate>()
        val medium = ArrayList<SourceCandidate>()
        val low = ArrayList<SourceCandidate>()
        var i = 0
        while (i < ranked.size) {
            val source = ranked[i].source
            if (i < highQualitySlots) high.add(source)
            else if (i < highQualitySlots + mediumQualitySlots) medium.add(source)
            else low.add(source)
            i++
        }
        return Allocation(high, medium, low)
    }

    private class Scored(val source: SourceCandidate, val score: Double)

    class Allocation internal constructor(high: List<SourceCandidate>, medium: List<SourceCandidate>, low: List<SourceCandidate>) {
        private val high = Collections.unmodifiableList(ArrayList(high))
        private val medium = Collections.unmodifiableList(ArrayList(medium))
        private val low = Collections.unmodifiableList(ArrayList(low))
        fun high(): List<SourceCandidate> = high
        fun medium(): List<SourceCandidate> = medium
        fun low(): List<SourceCandidate> = low
    }
}
