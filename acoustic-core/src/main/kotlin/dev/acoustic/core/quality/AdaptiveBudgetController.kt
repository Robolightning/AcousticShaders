package dev.acoustic.core.quality

/** Small deterministic controller used by quality-aware passes. It changes quality slowly to avoid audible/CPU oscillation. */
class AdaptiveBudgetController(
    private val targetMillis: Double,
    private val minLevel: Int,
    private val maxLevel: Int,
    initialLevel: Int
) {
    private var level = initialLevel
    private var emaMillis = 0.0
    private var initialized = false
    private var cooldown = 0

    init {
        require(targetMillis > 0.0) { "targetMillis must be > 0" }
        require(minLevel <= maxLevel && initialLevel in minLevel..maxLevel) { "invalid quality range" }
    }

    fun level(): Int = level
    fun smoothedMillis(): Double = emaMillis

    fun sample(elapsedMillis: Double): Int {
        require(elapsedMillis >= 0.0 && !elapsedMillis.isNaN()) { "invalid elapsedMillis" }
        emaMillis = if (initialized) emaMillis * 0.85 + elapsedMillis * 0.15 else elapsedMillis
        initialized = true
        if (cooldown > 0) {
            cooldown--
            return level
        }
        if (emaMillis > targetMillis * 1.10 && level > minLevel) {
            level--
            cooldown = 3
        } else if (emaMillis < targetMillis * 0.72 && level < maxLevel) {
            level++
            cooldown = 7
        }
        return level
    }
}
