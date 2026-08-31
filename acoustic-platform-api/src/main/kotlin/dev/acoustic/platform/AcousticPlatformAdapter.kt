package dev.acoustic.platform

import dev.acoustic.api.scene.AcousticScene

interface AcousticPlatformAdapter : PlatformFrameAdapter {
    fun captureScene(request: SceneCaptureRequest): AcousticScene
    fun captureListener(): ListenerSnapshot
    fun captureActiveSources(): List<SoundSourceSnapshot>

    /**
     * Capture one logical frame. Legacy/simple adapters may rely on this compatibility default.
     * Modern adapters should override it when their game API can capture scene/listener/sources
     * under one tick/world ownership boundary.
     */
    override fun captureFrame(request: SceneCaptureRequest): PlatformFrameSnapshot {
        val scene = captureScene(request)
        return PlatformFrameSnapshot(
            scene,
            captureListener(),
            captureActiveSources(),
            0L,
            scene.revision()
        )
    }
}
