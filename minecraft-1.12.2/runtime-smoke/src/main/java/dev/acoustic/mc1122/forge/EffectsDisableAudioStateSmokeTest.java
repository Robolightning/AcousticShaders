package dev.acoustic.mc1122.forge;

import dev.acoustic.api.math.Vec3;
import dev.acoustic.core.dsp.SoftwareWetPcmRenderer;
import dev.acoustic.core.passes.LegacyEffectParameters;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Regression for owner-thread OpenAL cleanup when effects are disabled at runtime. */
public final class EffectsDisableAudioStateSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/effects-disable-audio-state-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);
        Path forgeConfig = root.resolve("config");
        Files.createDirectories(forgeConfig);

        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(forgeConfig.toFile()));
        LegacyEfxBackend efx = (LegacyEfxBackend) privateField(LegacySoundHook.class, "efx");
        LegacySoftwareWetBackend wet = (LegacySoftwareWetBackend) privateField(LegacySoundHook.class, "wet");

        final int sourceId = 321;
        final long generation = 44L;
        org.lwjgl.openal.AL10.STATES.put(sourceId, org.lwjgl.openal.AL10.AL_PLAYING);
        LegacyAudioConfig wetConfig = new LegacyAudioConfig(true, 1, 2, 64, 0.1, 0.5f, 2, 65536);
        wet.reconfigure(wetConfig);
        efx.applyDirectOnly(sourceId, new LegacyEffectParameters(0.55f, 0.35f, 0.0f, 0.0f));
        efx.applyVelocity(sourceId, new Vec3(9.0, -2.0, 4.0), 1.0f);
        SoftwareWetPcmRenderer.Rendered rendered = new SoftwareWetPcmRenderer.Rendered(new byte[64], 8000, 16);
        LegacySoftwareWetBackend.ApplyResult wetApplied = wet.apply(sourceId, generation, rendered);
        check(wetApplied.getApplied(), "software-wet voice did not become active before disable");
        check(wet.isActive(sourceId, generation), "software-wet voice missing before disable");
        check(!org.lwjgl.openal.EFX10.FILTERS.isEmpty(), "EFX direct filter missing before disable");
        float[] beforeVelocity = org.lwjgl.openal.AL10.VELOCITIES.get(sourceId);
        check(beforeVelocity != null && beforeVelocity[0] == 9.0f && beforeVelocity[1] == -2.0f && beforeVelocity[2] == 4.0f,
            "AcousticShaders Doppler velocity missing before disable");

        Path runtime = forgeConfig.resolve("acousticshaders/runtime.properties");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(runtime)) { properties.load(in); }
        properties.setProperty("effects.enabled", "false");
        Thread.sleep(5L);
        try (OutputStream out = Files.newOutputStream(runtime)) { properties.store(out, "audio-state disable regression"); }

        TickEvent.ClientTickEvent tick = new TickEvent.ClientTickEvent(TickEvent.Phase.END);
        for (int i = 0; i < 45; i++) mod.clientTick(tick);
        check(!AcousticShadersForgeMod.runtime().effectsActive(), "runtime still reports effects active");
        // OpenAL state is owner-thread state: client tick requests reset, but may not mutate it directly.
        check(wet.isActive(sourceId, generation), "client thread illegally cleared software-wet OpenAL state");

        audioTick();
        assertCleared(wet, sourceId, generation, "effects.enabled=false");

        // Re-enable, seed another owner-thread state, then disable the entire shader stack.
        properties.setProperty("effects.enabled", "true");
        properties.setProperty("shaderpack.stack", "AcousticShaders-Reference-Hybrid.zip");
        Thread.sleep(5L);
        try (OutputStream out = Files.newOutputStream(runtime)) { properties.store(out, "re-enable before shader-stack regression"); }
        for (int i = 0; i < 45; i++) mod.clientTick(tick);
        check(AcousticShadersForgeMod.runtime().effectsActive(), "runtime did not re-enable before shader-stack disable regression");

        final int sourceId2 = 322;
        final long generation2 = 45L;
        org.lwjgl.openal.AL10.STATES.put(sourceId2, org.lwjgl.openal.AL10.AL_PLAYING);
        wet.reconfigure(wetConfig);
        efx.applyDirectOnly(sourceId2, new LegacyEffectParameters(0.65f, 0.45f, 0.0f, 0.0f));
        efx.applyVelocity(sourceId2, new Vec3(-3.0, 5.0, 1.0), 1.0f);
        check(wet.apply(sourceId2, generation2, rendered).getApplied(), "software-wet voice did not become active before shader-stack disable");
        properties.setProperty("shaderpack.stack", "");
        properties.setProperty("shaderpack", "");
        Thread.sleep(5L);
        try (OutputStream out = Files.newOutputStream(runtime)) { properties.store(out, "shader-stack disable regression"); }
        for (int i = 0; i < 45; i++) mod.clientTick(tick);
        check(!AcousticShadersForgeMod.runtime().effectsActive(), "empty shader stack still reports effects active");
        audioTick();
        assertCleared(wet, sourceId2, generation2, "empty shader stack");

        System.out.println("PASS: runtime disable clears EFX + software-wet + AcousticShaders Doppler velocity on audio owner thread (effects toggle + empty shader stack)");
    }


    private static void assertCleared(LegacySoftwareWetBackend wet, int sourceId, long generation, String boundary) {
        check(!wet.isActive(sourceId, generation), "software-wet voice survived " + boundary + " owner-thread reset");
        check(org.lwjgl.openal.EFX10.FILTERS.isEmpty(), "EFX filters survived " + boundary + " owner-thread reset");
        float[] afterVelocity = org.lwjgl.openal.AL10.VELOCITIES.get(sourceId);
        check(afterVelocity != null && afterVelocity[0] == 0.0f && afterVelocity[1] == 0.0f && afterVelocity[2] == 0.0f,
            "AcousticShaders Doppler velocity survived " + boundary + " owner-thread reset");
    }

    private static Object privateField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(Modifier.isStatic(field.getModifiers()) ? null : LegacySoundHook.INSTANCE);
    }

    private static void audioTick() throws Exception {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { LegacySoundHook.onAudioCommandTick(); }
        }, "Thread-AcousticDisableAudioState");
        thread.start();
        thread.join();
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
