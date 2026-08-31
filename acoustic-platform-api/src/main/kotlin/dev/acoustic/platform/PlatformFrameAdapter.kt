package dev.acoustic.platform

import dev.acoustic.api.capability.Capabilities

/**
 * Minimal atomic-frame contract for modern game-version frontends.
 *
 * New adapters should prefer this interface when their platform API can capture scene, listener
 * and active sources under one tick/world ownership boundary. Split-capture legacy adapters remain
 * source-compatible through AcousticPlatformAdapter, which extends this interface.
 */
interface PlatformFrameAdapter {
    fun platformId(): String
    fun capabilities(): Capabilities
    fun captureFrame(request: SceneCaptureRequest): PlatformFrameSnapshot
}
