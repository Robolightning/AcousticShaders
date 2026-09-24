package dev.acoustic.mc1122.forge;

import dev.acoustic.api.math.Vec3;
import dev.acoustic.core.dsp.SoftwareWetPcmRenderer;
import dev.acoustic.core.passes.LegacyEffectParameters;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** Recreating the Forge runtime must close superseded workers and defer native reset to audio owner thread. */
public final class RuntimeRebindSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/runtime-rebind-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);
        File forgeConfig = root.resolve("config").toFile();

        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        TestPre event = new TestPre(forgeConfig);
        mod.preInit(event);
        LegacyClientRuntime oldRuntime = AcousticShadersForgeMod.runtime();
        LegacyEfxBackend efx = (LegacyEfxBackend) privateStaticField(LegacySoundHook.class, "efx");
        LegacySoftwareWetBackend wet = (LegacySoftwareWetBackend) privateStaticField(LegacySoundHook.class, "wet");

        LegacyAudioConfig audio = new LegacyAudioConfig(true, 1, 2, 64, 0.1, 0.5f, 4, 65536);
        oldRuntime.applyLegacyAudioConfig(audio);
        audioTick();

        final int source = 901;
        final long oldGeneration = oldRuntime.sourceStarted(
            source, "minecraft/sounds/random/rebind_old.ogg", new Vec3(1.0, 2.0, 3.0), 1.0, 1.0, false, false);
        check(oldGeneration > 0L, "old runtime did not register logical source");
        org.lwjgl.openal.AL10.STATES.put(source, org.lwjgl.openal.AL10.AL_PLAYING);
        efx.applyDirectOnly(source, new LegacyEffectParameters(0.55f, 0.35f, 0.0f, 0.0f));
        efx.applyVelocity(source, new Vec3(2.0, 3.0, 4.0), 1.0f);
        long contextGeneration = wet.currentContextGeneration();
        SoftwareWetPcmRenderer.Rendered rendered = new SoftwareWetPcmRenderer.Rendered(new byte[64], 8000, 16);
        check(wet.apply(source, oldGeneration, contextGeneration, rendered).getApplied(),
            "old runtime wet voice was not seeded");
        check(!org.lwjgl.openal.EFX10.FILTERS.isEmpty(), "old runtime EFX state was not seeded");

        // A second preInit is not normal Forge gameplay, but is the strongest local model of
        // launcher/test runtime recreation. It must close the old runtime before replacement.
        mod.preInit(event);
        LegacyClientRuntime replacement = AcousticShadersForgeMod.runtime();
        check(replacement != oldRuntime, "preInit recreation did not replace runtime instance");
        check(boolField(oldRuntime, "closed"), "superseded runtime was not marked closed");
        check(!oldRuntime.effectsActive(), "superseded runtime still reports effects active");
        check(oldRuntime.sourceStarted(source + 1, "old/after_close.ogg", new Vec3(0.0, 0.0, 0.0), 1.0, 1.0, false, false) == 0L,
            "closed runtime accepted a new logical source");

        check(executorField(oldRuntime, "analysisExecutor").isShutdown(), "old analysis executor survived runtime replacement");
        check(executorField(oldRuntime, "physicsWorkers").isShutdown(), "old physics executor survived runtime replacement");
        Object oldWetRenderer = privateField(oldRuntime, "wetRenderer");
        check(boolField(oldWetRenderer, "closed"), "old software-wet renderer survived runtime replacement");
        ExecutorService oldWetWorkers = executorField(oldWetRenderer, "workers");
        check(oldWetWorkers.isShutdown(), "old software-wet worker pool survived runtime replacement");
        Object oldPipeline = privateField(oldRuntime, "pipelineExecutor");
        ExecutorService oldAccelerator = executorField(oldPipeline, "acceleratorExecutor");
        check(oldAccelerator.isShutdown(), "old accelerator host executor survived runtime replacement");

        // Stale GUI/runtime references must not be able to resurrect executors or rewrite
        // configuration after the instance was superseded.
        oldRuntime.applyLegacyAudioConfig(new LegacyAudioConfig(true, 2, 3, 128, 0.2, 0.4f, 5, 32768));
        oldRuntime.applyUiConfiguration(Collections.singletonList("AcousticShaders-Reference-Hybrid.zip"), "LOW", Collections.<String,String>emptyMap());
        oldRuntime.refreshGeneratedMaterialDatabase();
        check(privateField(oldWetRenderer, "workers") == oldWetWorkers && oldWetWorkers.isShutdown(),
            "closed runtime reconfiguration resurrected software-wet workers");
        check(executorField(oldRuntime, "analysisExecutor").isShutdown(),
            "closed runtime UI/configuration resurrected analysis executor");
        check(executorField(oldRuntime, "physicsWorkers").isShutdown(),
            "closed runtime UI/configuration resurrected physics executor");
        check(oldAccelerator.isShutdown(), "closed runtime UI/configuration resurrected accelerator executor");

        // preInit/client code may only request native reset; OpenAL mutations remain owner-thread-only.
        check(wet.isActive(source, oldGeneration), "runtime recreation illegally cleared wet voice off audio owner thread");
        check(!org.lwjgl.openal.EFX10.FILTERS.isEmpty(), "runtime recreation illegally cleared EFX off audio owner thread");
        float[] before = org.lwjgl.openal.AL10.VELOCITIES.get(Integer.valueOf(source));
        check(before != null && before[0] == 2f && before[1] == 3f && before[2] == 4f,
            "runtime recreation illegally cleared velocity off audio owner thread");

        audioTick();
        check(!wet.isActive(source, oldGeneration), "old wet voice survived replacement owner-thread reset");
        check(org.lwjgl.openal.EFX10.FILTERS.isEmpty(), "old EFX filters survived replacement owner-thread reset");
        float[] after = org.lwjgl.openal.AL10.VELOCITIES.get(Integer.valueOf(source));
        check(after != null && after[0] == 0f && after[1] == 0f && after[2] == 0f,
            "old AcousticShaders velocity survived replacement owner-thread reset");

        // New runtime remains usable after old resources are retired.
        long fresh = replacement.sourceStarted(source, "minecraft/sounds/random/rebind_new.ogg", new Vec3(2.0, 2.0, 3.0), 1.0, 1.0, false, false);
        check(fresh > 0L, "replacement runtime is not usable after rebind cleanup");

        System.out.println("PASS: runtime rebind closes superseded workers/logical state and defers native cleanup to audio owner thread");
    }

    private static ExecutorService executorField(Object target, String name) throws Exception {
        return (ExecutorService) privateField(target, name);
    }

    private static boolean boolField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
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
        }, "Thread-RuntimeRebind");
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
