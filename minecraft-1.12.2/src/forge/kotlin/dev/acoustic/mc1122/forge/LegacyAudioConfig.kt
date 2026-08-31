package dev.acoustic.mc1122.forge

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Safety limits for optional legacy software convolution. Kept separate from shader-defined physics options. */
internal data class LegacyAudioConfig(
    val softwareWetEnabled: Boolean,
    val rendererThreads: Int,
    val maxPendingJobs: Int,
    val fftBlockSize: Int,
    val maxIrSeconds: Double,
    val wetGain: Float,
    val maxWetVoices: Int,
    val maxPcmBytes: Int
) {
    init {
        require(rendererThreads in 1..8)
        require(maxPendingJobs in 1..64)
        require(fftBlockSize in 32..2048 && Integer.bitCount(fftBlockSize) == 1)
        require(maxIrSeconds in 0.05..8.0)
        require(wetGain in 0f..4f)
        require(maxWetVoices in 1..64)
        require(maxPcmBytes in 4096..(64 * 1024 * 1024))
    }

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        val p = Properties()
        p.setProperty("softwareWet.enabled", softwareWetEnabled.toString())
        p.setProperty("softwareWet.rendererThreads", rendererThreads.toString())
        p.setProperty("softwareWet.maxPendingJobs", maxPendingJobs.toString())
        p.setProperty("softwareWet.fftBlockSize", fftBlockSize.toString())
        p.setProperty("softwareWet.maxIrSeconds", maxIrSeconds.toString())
        p.setProperty("softwareWet.wetGain", wetGain.toString())
        p.setProperty("softwareWet.maxWetVoices", maxWetVoices.toString())
        p.setProperty("softwareWet.maxPcmBytes", maxPcmBytes.toString())
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newOutputStream(tmp).use { p.store(it, "Acoustic Shaders legacy audio backend") }
        try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        fun defaults(): LegacyAudioConfig = LegacyAudioConfig(false, 1, 4, 256, 2.5, 0.65f, 8, 8 * 1024 * 1024)
        fun loadOrCreate(path: Path): LegacyAudioConfig {
            if (!Files.isRegularFile(path)) return defaults().also { it.save(path) }
            val p = Properties(); Files.newInputStream(path).use { p.load(it) }
            val d = defaults()
            return try {
                LegacyAudioConfig(
                    p.getProperty("softwareWet.enabled")?.toBooleanStrictOrNull() ?: d.softwareWetEnabled,
                    p.getProperty("softwareWet.rendererThreads")?.toIntOrNull() ?: d.rendererThreads,
                    p.getProperty("softwareWet.maxPendingJobs")?.toIntOrNull() ?: d.maxPendingJobs,
                    p.getProperty("softwareWet.fftBlockSize")?.toIntOrNull() ?: d.fftBlockSize,
                    p.getProperty("softwareWet.maxIrSeconds")?.toDoubleOrNull() ?: d.maxIrSeconds,
                    p.getProperty("softwareWet.wetGain")?.toFloatOrNull() ?: d.wetGain,
                    p.getProperty("softwareWet.maxWetVoices")?.toIntOrNull() ?: d.maxWetVoices,
                    p.getProperty("softwareWet.maxPcmBytes")?.toIntOrNull() ?: d.maxPcmBytes
                )
            } catch (bad: IllegalArgumentException) {
                AcousticLog.warn("invalid legacy-audio.properties; keeping safe defaults: ${bad.message}")
                d
            }
        }
    }
}
