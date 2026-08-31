package dev.acoustic.api.source

import java.util.Locale

class SourceProfileRule(private val priority: Int, private val kind: MatchKind, match: String, private val profile: AcousticSourceProfile) {
    enum class MatchKind { EXACT, PREFIX, CONTAINS, GLOB }
    private val match = normalize(match).also { require(it.isNotEmpty()) { "empty source match" } }
    fun priority(): Int = priority
    fun kind(): MatchKind = kind
    fun match(): String = match
    fun profile(): AcousticSourceProfile = profile
    fun withPriority(p: Int): SourceProfileRule = SourceProfileRule(p, kind, match, profile)
    fun matches(soundId: String?): Boolean {
        val s = normalize(soundId)
        return when (kind) { MatchKind.EXACT -> s == match; MatchKind.PREFIX -> s.startsWith(match); MatchKind.CONTAINS -> s.contains(match); MatchKind.GLOB -> glob(match, s) }
    }
    companion object {
        @JvmStatic fun normalize(s: String?): String {
            var x = s?.trim()?.replace('\\','/')?.lowercase(Locale.ROOT) ?: return ""
            while (x.startsWith("./")) x = x.substring(2)
            return x
        }
        private fun glob(pattern: String, text: String): Boolean {
            var p=0; var t=0; var star=-1; var mark=-1
            while (t<text.length) {
                if (p<pattern.length && (pattern[p]=='?' || pattern[p]==text[t])) { p++; t++; continue }
                if (p<pattern.length && pattern[p]=='*') { star=p++; mark=t; continue }
                if (star>=0) { p=star+1; t=++mark; continue }
                return false
            }
            while (p<pattern.length && pattern[p]=='*') p++
            return p==pattern.length
        }
    }
}
