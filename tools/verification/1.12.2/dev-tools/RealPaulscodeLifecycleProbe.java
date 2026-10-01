import dev.acoustic.mc1122.forge.LegacyAudioConfig;
import dev.acoustic.mc1122.forge.LegacyClientRuntime;
import dev.acoustic.mc1122.forge.LegacySoundHook;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import org.lwjgl.openal.AL10;
import paulscode.sound.Library;
import paulscode.sound.Source;
import paulscode.sound.SoundSystem;
import paulscode.sound.SoundSystemConfig;
import paulscode.sound.libraries.LibraryLWJGLOpenAL;
import paulscode.sound.libraries.SourceLWJGLOpenAL;

/**
 * Windows/Paulscode lifecycle probe for the production legacy sound hook.
 *
 * This intentionally uses Minecraft 1.12.2's real Paulscode SoundSystem and
 * LibraryLWJGLOpenAL classes. The production callback is invoked with the real
 * SourceLWJGLOpenAL object so its reflection contract cannot silently drift
 * behind runtime-smoke stubs.
 */
public final class RealPaulscodeLifecycleProbe {
    private RealPaulscodeLifecycleProbe() {}

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field value = type.getDeclaredField(name);
                value.setAccessible(true);
                return value.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> activeSources(LegacyClientRuntime runtime) throws Exception {
        return (Map<Integer, Object>)field(runtime, "activeSources");
    }

    private static double coordinate(Object vec, String name) throws Exception {
        Field value = vec.getClass().getField(name);
        return ((Number)value.get(vec)).doubleValue();
    }

    private static byte[] mono16Tone(int frames, int sampleRate) {
        byte[] out = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short value = (short)Math.round(
                Math.sin((2.0 * Math.PI * 440.0 * i) / sampleRate) * 2500.0
            );
            int p = i * 2;
            out[p] = (byte)(value & 0xff);
            out[p + 1] = (byte)((value >>> 8) & 0xff);
        }
        return out;
    }

    private static void requirePosition(Object state, double x, double y, double z, String label) throws Exception {
        Object position = field(state, "position");
        double epsilon = 1.0e-6;
        require(Math.abs(coordinate(position, "x") - x) <= epsilon, label + " x mismatch: " + position);
        require(Math.abs(coordinate(position, "y") - y) <= epsilon, label + " y mismatch: " + position);
        require(Math.abs(coordinate(position, "z") - z) <= epsilon, label + " z mismatch: " + position);
    }

    public static void main(String[] args) throws Exception {
        require(System.getProperty("os.name", "").startsWith("Windows"), "Windows JVM required");
        require(args.length == 2, "args: <configDir> <gameDir>");

        Path configDir = Paths.get(args[0]);
        Path gameDir = Paths.get(args[1]);
        Files.createDirectories(configDir);
        Files.createDirectories(gameDir);

        LegacyClientRuntime runtime = new LegacyClientRuntime(configDir, gameDir);
        require(runtime.effectsActive(), "Acoustic runtime is not active");
        runtime.applyLegacyAudioConfig(new LegacyAudioConfig(true, 1, 2, 64, 0.25, 0.5f, 2, 65536));

        SoundSystem sound = new SoundSystem(LibraryLWJGLOpenAL.class);
        try {
            String bufferName = "acoustic-probe.wav";
            String sourceName = "acoustic-probe-source";
            AudioFormat format = new AudioFormat(8000.0f, 16, 1, true, false);

            sound.loadSound(mono16Tone(8000, 8000), format, bufferName);
            sound.CommandQueue(null);
            sound.newSource(
                true,
                sourceName,
                bufferName,
                false,
                1.25f,
                2.5f,
                -3.75f,
                SoundSystemConfig.ATTENUATION_NONE,
                0.0f
            );
            sound.CommandQueue(null);
            sound.play(sourceName);
            sound.CommandQueue(null);

            Library library = (Library)field(sound, "soundLibrary");
            Source source = library.getSource(sourceName);
            require(source != null, "Paulscode source missing");
            require(source instanceof SourceLWJGLOpenAL, "unexpected Paulscode source type: " + source.getClass());

            Object channel = field(source, "channelOpenAL");
            require(channel != null, "channelOpenAL missing after play");
            IntBuffer alSource = (IntBuffer)field(channel, "ALSource");
            require(alSource != null && alSource.capacity() > 0, "ALSource buffer missing");
            int sourceId = alSource.get(0);
            require(sourceId > 0, "invalid OpenAL source id");
            require(
                AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING,
                "Paulscode source is not playing"
            );

            LegacySoundHook.onSourcePlay(source);
            Map<Integer, Object> active = activeSources(runtime);
            Object state = active.get(sourceId);
            require(state != null, "production hook did not register real Paulscode source");
            require(
                bufferName.equals((String)field(state, "soundId")),
                "sound id mismatch: " + field(state, "soundId")
            );
            Object capture = field(state, "pcmCapture");
            require(capture != null, "static mono PCM was not captured from real SoundBuffer");
            byte[] capturedPcm = (byte[])field(capture, "pcmMono16");
            int capturedRate = ((Number)field(capture, "sampleRate")).intValue();
            require(capturedPcm.length == 16000, "captured PCM length mismatch: " + capturedPcm.length);
            require(capturedRate == 8000, "captured PCM sample rate mismatch: " + capturedRate);
            requirePosition(state, 1.25, 2.5, -3.75, "initial");

            source.setPosition(4.5f, -1.0f, 7.25f);
            LegacySoundHook.onSourcePositionChanged(source);
            requirePosition(active.get(sourceId), 4.5, -1.0, 7.25, "moved");

            LegacySoundHook.onAudioCommandTick();
            LegacySoundHook.onSourceCleanup(source);
            require(!activeSources(runtime).containsKey(sourceId), "source survived cleanup callback");
            require(AL10.alGetError() == AL10.AL_NO_ERROR, "OpenAL error after lifecycle probe");

            System.out.println(
                "ACOUSTIC-REAL-PAULSCODE-LIFECYCLE-OK source=" + sourceId +
                " pcm=1 move=1 cleanup=1"
            );
        } finally {
            sound.cleanup();
        }
    }
}
