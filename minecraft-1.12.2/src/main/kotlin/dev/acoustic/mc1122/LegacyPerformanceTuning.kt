package dev.acoustic.mc1122

/** Live 1.12.2 performance settings resolved from the active acoustic shader preset. */
class LegacyPerformanceTuning @JvmOverloads constructor(
    private val horizontalRadiusValue: Int,
    private val verticalRadiusValue: Int,
    private val captureIntervalTicksValue: Int,
    private val refreshRadiusValue: Int,
    private val fullRefreshTicksValue: Int,
    private val workersValue: Int,
    private val roomRaysValue: Int,
    private val roomProbeDistanceValue: Double,
    private val liveSourceLimitValue: Int,
    private val sourceMoveThresholdValue: Double,
    private val captureBudgetMillisValue: Double = 5.0
) {
    fun horizontalRadius() = horizontalRadiusValue
    fun verticalRadius() = verticalRadiusValue
    fun captureIntervalTicks() = captureIntervalTicksValue
    fun refreshRadius() = refreshRadiusValue
    fun fullRefreshTicks() = fullRefreshTicksValue
    fun workers() = workersValue
    fun roomRays() = roomRaysValue
    fun roomProbeDistance() = roomProbeDistanceValue
    fun liveSourceLimit() = liveSourceLimitValue
    fun sourceMoveThreshold() = sourceMoveThresholdValue
    fun captureBudgetMillis() = captureBudgetMillisValue
}
