import dev.acoustic.core.dsp.SoftwareWetPcmRenderer;
import dev.acoustic.mc1122.forge.LegacyAudioConfig;
import dev.acoustic.mc1122.forge.LegacySoftwareWetBackend;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;

/**
 * Windows/LWJGL2 integration probe for the production software-wet OpenAL backend.
 *
 * This deliberately uses the real Minecraft 1.12.2 LWJGL2 classes/natives rather than
 * runtime-smoke stubs, so reflective owner mistakes (for example AL10 vs AL11 constants)
 * are release-gating failures.
 */
public final class RealOpenALWetProbe {
    private RealOpenALWetProbe() {}

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static ByteBuffer mono16Tone(int frames, int sampleRate) {
        ByteBuffer data = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < frames; i++) {
            double sample = Math.sin((2.0 * Math.PI * 440.0 * i) / sampleRate);
            data.putShort((short)Math.round(sample * 3000.0));
        }
        data.flip();
        return data;
    }

    private static byte[] stereo16Wet(int frames) {
        byte[] out = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            short value = (short)((i % 32 == 0) ? 1200 : 0);
            int p = i * 4;
            out[p] = (byte)(value & 0xff);
            out[p + 1] = (byte)((value >>> 8) & 0xff);
            out[p + 2] = out[p];
            out[p + 3] = out[p + 1];
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        require(System.getProperty("os.name", "").startsWith("Windows"), "Windows JVM required");
        AL.create();
        int dryBuffer = 0;
        int drySource = 0;
        try {
            require(AL.isCreated(), "OpenAL context was not created");
            dryBuffer = AL10.alGenBuffers();
            drySource = AL10.alGenSources();
            AL10.alBufferData(dryBuffer, AL10.AL_FORMAT_MONO16, mono16Tone(8000, 8000), 8000);
            AL10.alSourcei(drySource, AL10.AL_BUFFER, dryBuffer);
            AL10.alSourcePlay(drySource);
            require(
                AL10.alGetSourcei(drySource, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING,
                "dry OpenAL source is not playing"
            );

            LegacyAudioConfig config = new LegacyAudioConfig(true, 1, 2, 64, 0.25, 0.5f, 2, 65536);
            LegacySoftwareWetBackend wet = new LegacySoftwareWetBackend(config);
            SoftwareWetPcmRenderer.Rendered rendered = new SoftwareWetPcmRenderer.Rendered(
                stereo16Wet(4000), 8000, 4000
            );

            LegacySoftwareWetBackend.ApplyResult applied = wet.apply(drySource, 77L, rendered);
            require(applied.getApplied(), "real OpenAL wet apply fell back; fallback=" + wet.getFallback());
            require(!applied.getPaused(), "playing source was reported paused");
            require(wet.isActive(drySource, 77L), "wet voice is not active after apply");
            require(wet.getApplied() == 1L, "wet apply counter mismatch");
            require(wet.getFallback() == 0L, "wet fallback counter is non-zero");
            require(AL10.alGetError() == AL10.AL_NO_ERROR, "OpenAL error after wet apply");

            wet.clearSource(drySource);
            require(!wet.isActive(drySource, 77L), "wet voice survived clearSource");
            require(AL10.alGetError() == AL10.AL_NO_ERROR, "OpenAL error after wet cleanup");

            System.out.println(
                "ACOUSTIC-REAL-OPENAL-WET-OK applied=" + wet.getApplied() + " fallback=" + wet.getFallback()
            );
        } finally {
            if (drySource != 0) {
                try {
                    AL10.alSourceStop(drySource);
                    AL10.alDeleteSources(drySource);
                } catch (Throwable ignored) {
                    // Best-effort cleanup in a diagnostic probe.
                }
            }
            if (dryBuffer != 0) {
                try {
                    AL10.alDeleteBuffers(dryBuffer);
                } catch (Throwable ignored) {
                    // Best-effort cleanup in a diagnostic probe.
                }
            }
            if (AL.isCreated()) AL.destroy();
        }
    }
}
