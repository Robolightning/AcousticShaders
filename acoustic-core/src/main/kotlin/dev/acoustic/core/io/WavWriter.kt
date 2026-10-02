package dev.acoustic.core.io

import dev.acoustic.core.compat.codeCompat
import dev.acoustic.core.rir.ImpulseResponse
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Minimal deterministic PCM16 WAV writer for diagnostics and shader-pack authoring. */
object WavWriter {
    @JvmStatic
    @Throws(IOException::class)
    fun writeMono16(path: Path?, response: ImpulseResponse?) {
        require(path != null && response != null) { "path/response" }
        val parent = path.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        BufferedOutputStream(Files.newOutputStream(path)).use { out ->
            val samples = response.length()
            val dataBytes = samples * 2
            writeAscii(out, "RIFF"); writeLe32(out, 36 + dataBytes); writeAscii(out, "WAVE")
            writeAscii(out, "fmt "); writeLe32(out, 16); writeLe16(out, 1); writeLe16(out, 1)
            writeLe32(out, response.sampleRate())
            writeLe32(out, response.sampleRate() * 2)
            writeLe16(out, 2); writeLe16(out, 16)
            writeAscii(out, "data"); writeLe32(out, dataBytes)
            var peak = 0f
            var i = 0
            while (i < samples) {
                peak = Math.max(peak, Math.abs(response.sample(i)))
                i++
            }
            val gain = if (peak > 1f) 1f / peak else 1f
            i = 0
            while (i < samples) {
                val value = Math.max(-1f, Math.min(1f, response.sample(i) * gain))
                val pcm = if (value <= -1f) -32768 else Math.round(value * 32767f)
                writeLe16(out, pcm)
                i++
            }
        }
    }

    @Throws(IOException::class)
    private fun writeAscii(out: OutputStream, value: String) {
        var i = 0
        while (i < value.length) {
            out.write(value[i].codeCompat().toByte().toInt())
            i++
        }
    }
    @Throws(IOException::class)
    private fun writeLe16(out: OutputStream, value: Int) { out.write(value and 255); out.write((value ushr 8) and 255) }
    @Throws(IOException::class)
    private fun writeLe32(out: OutputStream, value: Int) { writeLe16(out, value); writeLe16(out, value ushr 16) }
}
