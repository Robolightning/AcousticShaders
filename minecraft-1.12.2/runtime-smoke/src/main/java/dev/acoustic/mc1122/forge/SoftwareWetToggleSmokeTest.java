package dev.acoustic.mc1122.forge;

import dev.acoustic.api.math.Vec3;
import dev.acoustic.api.scene.AcousticScene;
import dev.acoustic.api.scene.AcousticVoxel;
import dev.acoustic.core.dsp.SoftwareWetPcmRenderer;
import dev.acoustic.core.passes.LegacyEffectParameters;
import dev.acoustic.core.passes.LegacyRoomEstimate;
import dev.acoustic.mc1122.LegacyPublishedState;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Runtime software-wet toggle: owner-thread voice removal, dry EFX restoration and stale-render invalidation. */
public final class SoftwareWetToggleSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/software-wet-toggle-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);
        Path forgeConfig = root.resolve("config");
        Files.createDirectories(forgeConfig);

        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(forgeConfig.toFile()));
        LegacyClientRuntime runtime = AcousticShadersForgeMod.runtime();
        LegacyAudioConfig enabled = new LegacyAudioConfig(true, 1, 2, 64, 0.1, 0.5f, 2, 65536);
        runtime.applyLegacyAudioConfig(enabled);
        // Backend config is owner-thread state; synchronize it before seeding a wet voice.
        audioTick();

        LegacyEfxBackend efx = (LegacyEfxBackend) privateStaticField(LegacySoundHook.class, "efx");
        LegacySoftwareWetBackend wet = (LegacySoftwareWetBackend) privateStaticField(LegacySoundHook.class, "wet");
        final int source = 501;
        final long generation = runtime.sourceStarted(source, "acousticshaders:projectile.flight", new Vec3(2.0, 1.0, 0.0), 1.0, 1.0, false, false);
        check(generation > 0L, "runtime source generation was not created");
        seedLastEffect(runtime, source, new LegacyEffectParameters(0.62f, 0.41f, 0.27f, 0.19f));
        seedReadyPublishedState(runtime, 77L);

        org.lwjgl.openal.AL10.STATES.put(source, org.lwjgl.openal.AL10.AL_PLAYING);
        org.lwjgl.openal.EFX10.FILTERS.clear();
        efx.applyDirectOnly(source, new LegacyEffectParameters(0.62f, 0.41f, 0.0f, 0.0f));
        SoftwareWetPcmRenderer.Rendered rendered = new SoftwareWetPcmRenderer.Rendered(new byte[64], 8000, 16);
        check(wet.apply(source, generation, rendered).getApplied(), "software-wet voice was not active before toggle");
        check(wet.isActive(source, generation), "wet voice missing before toggle");
        check(org.lwjgl.openal.EFX10.FILTERS.size() == 1, "expected direct-only EFX before wet disable");

        Path audio = forgeConfig.resolve("acousticshaders/legacy-audio.properties");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(audio)) { properties.load(in); }
        properties.setProperty("softwareWet.enabled", "false");
        Thread.sleep(5L);
        try (OutputStream out = Files.newOutputStream(audio)) { properties.store(out, "software-wet runtime toggle regression"); }
        TickEvent.ClientTickEvent tick = new TickEvent.ClientTickEvent(TickEvent.Phase.END);
        for (int i = 0; i < 45; i++) mod.clientTick(tick);
        check(!runtime.legacyAudioConfig().getSoftwareWetEnabled(), "runtime did not observe softwareWet.enabled=false");
        check(wet.isActive(source, generation), "client thread illegally destroyed owner-thread wet voice");

        int sendCallsBefore = org.lwjgl.openal.AL11.source3iCalls;
        audioTick();
        check(!wet.isActive(source, generation), "wet voice survived owner-thread software-wet disable");
        check(org.lwjgl.openal.EFX10.FILTERS.size() == 2, "dry source EFX direct+send state was not restored after wet disable");
        check(org.lwjgl.openal.AL11.source3iCalls > sendCallsBefore && org.lwjgl.openal.AL11.lastSlot != 0,
            "dry source auxiliary EFX send was not restored on owner thread");

        proveRendererGenerationBarrier(enabled);
        System.out.println("PASS: softwareWet runtime disable removes wet voice, restores dry EFX, and invalidates pre-toggle render generation");
    }

    private static void proveRendererGenerationBarrier(LegacyAudioConfig enabled) throws Exception {
        Class<?> type = Class.forName("dev.acoustic.mc1122.forge.LegacySoftwareWetRenderer");
        Constructor<?> ctor = type.getDeclaredConstructor(LegacyAudioConfig.class);
        ctor.setAccessible(true);
        Object renderer = ctor.newInstance(enabled);
        Field generation = type.getDeclaredField("poolGeneration"); generation.setAccessible(true);
        Field completed = type.getDeclaredField("completed"); completed.setAccessible(true);
        @SuppressWarnings("unchecked") Queue<Object> queue = (Queue<Object>) completed.get(renderer);
        queue.add("sentinel-pre-toggle-result");
        long before = generation.getLong(renderer);
        LegacyAudioConfig disabled = new LegacyAudioConfig(false, 1, 2, 64, 0.1, 0.5f, 2, 65536);
        Method reconfigure = type.getDeclaredMethod("reconfigure", LegacyAudioConfig.class); reconfigure.setAccessible(true);
        reconfigure.invoke(renderer, disabled);
        long afterDisable = generation.getLong(renderer);
        check(afterDisable > before, "same-thread-count disable did not advance wet renderer generation");
        check(queue.isEmpty(), "completed wet results survived disable reconfigure");
        reconfigure.invoke(renderer, enabled);
        long afterEnable = generation.getLong(renderer);
        check(afterEnable > afterDisable, "re-enable did not advance wet renderer generation");
        Method close = type.getDeclaredMethod("close"); close.setAccessible(true); close.invoke(renderer);
    }

    private static void seedLastEffect(LegacyClientRuntime runtime, int source, LegacyEffectParameters effect) throws Exception {
        Field activeField = LegacyClientRuntime.class.getDeclaredField("activeSources"); activeField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer,Object> active = (Map<Integer,Object>) activeField.get(runtime);
        Object state = active.get(source);
        check(state != null, "active source state missing");
        Field effectField = state.getClass().getDeclaredField("lastEffect"); effectField.setAccessible(true); effectField.set(state, effect);
        Field revisionField = state.getClass().getDeclaredField("lastEffectRevision"); revisionField.setAccessible(true); revisionField.setLong(state, 77L);
    }

    private static void seedReadyPublishedState(LegacyClientRuntime runtime, long revision) throws Exception {
        AcousticScene scene = new AcousticScene() {
            @Override public AcousticVoxel voxelAt(int x, int y, int z) { return null; }
            @Override public long revision() { return revision; }
            @Override public boolean containsNonAirMedia() { return false; }
        };
        LegacyPublishedState state = new LegacyPublishedState(scene, new Vec3(0.0, 1.0, 0.0), LegacyRoomEstimate.DEFAULT, null, 1L, true);
        Field published = LegacyClientRuntime.class.getDeclaredField("publishedValue"); published.setAccessible(true); published.set(runtime, state);
    }

    private static Object privateStaticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(Modifier.isStatic(field.getModifiers()) ? null : LegacySoundHook.INSTANCE);
    }

    private static void audioTick() throws Exception {
        Thread thread = new Thread(new Runnable() { @Override public void run() { LegacySoundHook.onAudioCommandTick(); } }, "Thread-SoftwareWetToggle");
        thread.start(); thread.join();
    }

    private static final class TestPre extends FMLPreInitializationEvent {
        private final File directory;
        TestPre(File directory) { this.directory = directory; }
        @Override public File getModConfigurationDirectory() { return directory; }
    }

    private static void delete(Path path) throws Exception {
        if (!Files.exists(path)) return;
        List<Path> all = new ArrayList<Path>(); Files.walk(path).forEach(all::add); Collections.reverse(all);
        for (Path item : all) Files.delete(item);
    }
}
