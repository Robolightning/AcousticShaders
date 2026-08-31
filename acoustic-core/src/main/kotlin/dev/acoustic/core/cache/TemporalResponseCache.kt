package dev.acoustic.core.cache

import dev.acoustic.api.math.Vec3

/** Conservative temporal cache; invalidates on scene revision or meaningful endpoint movement. */
class TemporalResponseCache<T>(private val moveThreshold: Double) {
    private var revision = Long.MIN_VALUE
    private var source: Vec3? = null
    private var listener: Vec3? = null
    private var value: T? = null
    init { require(moveThreshold >= 0) { "threshold" } }
    @Synchronized fun get(rev: Long, src: Vec3, lis: Vec3): T? {
        val current = value ?: return null
        val s = source ?: return null
        val l = listener ?: return null
        return if (rev == revision && s.distance(src) <= moveThreshold && l.distance(lis) <= moveThreshold) current else null
    }
    @Synchronized fun put(rev: Long, src: Vec3, lis: Vec3, v: T?) {
        revision = rev; source = src; listener = lis; value = v
    }
    @Synchronized fun clear() { value = null; source = null; listener = null; revision = Long.MIN_VALUE }
}
