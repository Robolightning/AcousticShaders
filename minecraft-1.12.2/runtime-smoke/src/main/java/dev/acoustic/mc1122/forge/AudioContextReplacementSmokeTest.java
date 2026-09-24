package dev.acoustic.mc1122.forge;

import dev.acoustic.api.math.Vec3;
import dev.acoustic.core.dsp.SoftwareWetPcmRenderer;
import dev.acoustic.core.passes.LegacyEffectParameters;
import dev.acoustic.core.passes.LegacyRoomEstimate;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * ALC context replacement is a hard identity boundary for every numeric OpenAL source id.
 * Old logical generations, wet jobs and backend ownership must not survive into the new
 * context, and cleanup of old ownership must never mutate third-party state there.
 */
public final class AudioContextReplacementSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/audio-context-replacement-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);

        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(root.resolve("config").toFile()));
        LegacyClientRuntime runtime = AcousticShadersForgeMod.runtime();
        LegacyEfxBackend efx = (LegacyEfxBackend) privateStaticField(LegacySoundHook.class, "efx");
        LegacySoftwareWetBackend wet = (LegacySoftwareWetBackend) privateStaticField(LegacySoundHook.class, "wet");
        Object renderer = privateField(runtime, "wetRenderer");
        Field rendererGenerationField = renderer.getClass().getDeclaredField("poolGeneration");
        rendererGenerationField.setAccessible(true);

        LegacyAudioConfig audio = new LegacyAudioConfig(true, 1, 2, 64, 0.1, 0.5f, 4, 65536);
        runtime.applyLegacyAudioConfig(audio);
        audioTick();

        final int source = 731;
        final long oldLogicalGeneration = runtime.sourceStarted(
            source, "minecraft/sounds/random/context_old.ogg", new Vec3(1.0, 2.0, 3.0), 1.0, 1.0, false, false);
        check(oldLogicalGeneration > 0L, "old logical source generation missing");

        org.lwjgl.openal.AL10.STATES.put(source, org.lwjgl.openal.AL10.AL_PLAYING);
        LegacyEffectParameters effect = new LegacyEffectParameters(0.55f, 0.35f, 0.25f, 0.15f);
        LegacyRoomEstimate room = new LegacyRoomEstimate(4.0, 0.1f, 0.8f, 0.8f, 0.8f, 0.8f);
        efx.apply(source, effect, room, 1L);
        efx.applyVelocity(source, new Vec3(4.0, 5.0, 6.0), 1.0f);
        long oldContextGeneration = wet.currentContextGeneration();
        check(oldContextGeneration > 0L, "initial audio context generation missing");
        SoftwareWetPcmRenderer.Rendered rendered = new SoftwareWetPcmRenderer.Rendered(new byte[64], 8000, 16);
        check(wet.apply(source, oldLogicalGeneration, oldContextGeneration, rendered).getApplied(),
            "old-context wet voice was not seeded");
        check(wet.isActive(source, oldLogicalGeneration), "old-context wet voice missing before replacement");
        long rendererGenerationBefore = rendererGenerationField.getLong(renderer);

        // Real context destruction releases the old native objects. The test maps are global,
        // so clear them explicitly, then seed unrelated third-party state on the same numeric
        // source id in the replacement context.
        org.lwjgl.openal.EFX10.FILTERS.clear();
        org.lwjgl.openal.AL10.DIRECT_FILTERS.clear();
        org.lwjgl.openal.AL11.AUX_SENDS.clear();
        org.lwjgl.openal.AL10.VELOCITIES.clear();
        org.lwjgl.openal.ALC10.CURRENT = new org.lwjgl.openal.ALCcontext();
        org.lwjgl.openal.AL10.STATES.put(source, org.lwjgl.openal.AL10.AL_PLAYING);
        org.lwjgl.openal.AL10.alSourcei(source, org.lwjgl.openal.EFX10.AL_DIRECT_FILTER, 7701);
        org.lwjgl.openal.AL11.alSource3i(source, org.lwjgl.openal.EFX10.AL_AUXILIARY_SEND_FILTER, 7702, 0, 7703);
        org.lwjgl.openal.AL10.alSource3f(source, org.lwjgl.openal.AL10.AL_VELOCITY, 9f, 8f, 7f);

        audioTick();
        long newContextGeneration = wet.currentContextGeneration();
        check(newContextGeneration > oldContextGeneration, "OpenAL context replacement did not advance generation");
        check(rendererGenerationField.getLong(renderer) > rendererGenerationBefore,
            "OpenAL context replacement did not invalidate running/pending software-wet generation");
        check(activeSourceCount(runtime) == 0, "old logical source generations survived OpenAL context replacement");
        check(!wet.isActive(source, oldLogicalGeneration), "old-context wet voice survived context replacement");
        check(directFilter(source) == 7701, "old-context cleanup clobbered new-context third-party direct filter");
        int[] aux = org.lwjgl.openal.AL11.AUX_SENDS.get(Integer.valueOf(source));
        check(aux != null && aux[0] == 7702 && aux[2] == 7703,
            "old-context cleanup clobbered new-context third-party auxiliary send");
        checkVelocity(source, 9f, 8f, 7f, "old-context cleanup clobbered new-context third-party velocity");

        LegacySoftwareWetBackend.ApplyResult stale = wet.apply(
            source, oldLogicalGeneration, oldContextGeneration, rendered);
        check(!stale.getApplied() && stale.getStaleContext(),
            "old-context wet result was accepted by replacement context");
        check(!wet.isActive(source, oldLogicalGeneration), "stale old-context result created a wet voice in replacement context");

        // Register a new logical sound on the recycled numeric id. Context synchronization
        // already happened, so the new generation may be published but must not acquire or
        // clear state that belongs to the third-party owner.
        LegacySoundHook.onSourcePlay(new FakeSource(source, "minecraft/sounds/random/context_new.ogg"));
        long newLogicalGeneration = activeGeneration(runtime, source);
        check(newLogicalGeneration > oldLogicalGeneration, "replacement context did not create a fresh logical source generation");
        check(directFilter(source) == 7701, "new source registration clobbered pre-owned replacement-context EFX");
        checkVelocity(source, 9f, 8f, 7f, "new source registration clobbered pre-owned replacement-context velocity");

        System.out.println("PASS: OpenAL context replacement retires old source generations/wet work and preserves new-context third-party state");
    }

    private static int activeSourceCount(LegacyClientRuntime runtime) throws Exception {
        Field field = LegacyClientRuntime.class.getDeclaredField("activeSources");
        field.setAccessible(true);
        return ((Map<?,?>) field.get(runtime)).size();
    }

    private static long activeGeneration(LegacyClientRuntime runtime, int source) throws Exception {
        Field field = LegacyClientRuntime.class.getDeclaredField("activeSources");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer,Object> active = (Map<Integer,Object>) field.get(runtime);
        Object state = active.get(Integer.valueOf(source));
        check(state != null, "replacement logical source state missing");
        Field generation = state.getClass().getDeclaredField("generation");
        generation.setAccessible(true);
        return generation.getLong(state);
    }

    private static int directFilter(int source) {
        Integer value = org.lwjgl.openal.AL10.DIRECT_FILTERS.get(Integer.valueOf(source));
        return value == null ? 0 : value.intValue();
    }

    private static void checkVelocity(int source, float x, float y, float z, String message) {
        float[] value = org.lwjgl.openal.AL10.VELOCITIES.get(Integer.valueOf(source));
        check(value != null && value[0] == x && value[1] == y && value[2] == z,
            message + " actual=" + (value == null ? "null" : value[0] + "," + value[1] + "," + value[2]));
    }

    private static Object privateStaticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(Modifier.isStatic(field.getModifiers()) ? null : LegacySoundHook.INSTANCE);
    }

    private static Object privateField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void audioTick() throws Exception {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { LegacySoundHook.onAudioCommandTick(); }
        }, "Thread-AudioContextReplacement");
        thread.start();
        thread.join();
    }

    private static final class FakeSource {
        public final Channel channelOpenAL;
        public final Position position = new Position(0f, 0f, 0f);
        public final float gain = 1f;
        public final float sourceVolume = 1f;
        public final boolean priority = false;
        public final boolean toStream = false;
        public final boolean toLoop = false;
        public final String sourcename;
        FakeSource(int sourceId, String name) { this.channelOpenAL = new Channel(sourceId); this.sourcename = name; }
    }
    private static final class Channel {
        public final IntBuffer ALSource = IntBuffer.allocate(1);
        Channel(int id) { ALSource.put(0, id); }
    }
    private static final class Position {
        public final float x, y, z;
        Position(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }
    private static final class TestPre extends FMLPreInitializationEvent {
        private final File directory;
        TestPre(File directory) { this.directory = directory; }
        @Override public File getModConfigurationDirectory() { return directory; }
    }
    private static void delete(Path path) throws Exception {
        if (!Files.exists(path)) return;
        List<Path> all = new ArrayList<Path>();
        Files.walk(path).forEach(all::add);
        Collections.reverse(all);
        for (Path item : all) Files.delete(item);
    }
}
