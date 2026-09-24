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
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** World/session replacement must retire logical sound generations even if ALC survives. */
public final class WorldSessionReplacementSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/world-session-replacement-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);

        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(root.resolve("config").toFile()));
        LegacyClientRuntime runtime = AcousticShadersForgeMod.runtime();
        LegacyEfxBackend efx = (LegacyEfxBackend) privateStaticField(LegacySoundHook.class, "efx");
        LegacySoftwareWetBackend wet = (LegacySoftwareWetBackend) privateStaticField(LegacySoundHook.class, "wet");
        Object renderer = privateField(runtime, "wetRenderer");
        Field poolGeneration = renderer.getClass().getDeclaredField("poolGeneration");
        poolGeneration.setAccessible(true);

        LegacyAudioConfig audio = new LegacyAudioConfig(true, 1, 2, 64, 0.1, 0.5f, 4, 65536);
        runtime.applyLegacyAudioConfig(audio);
        audioTick();

        Minecraft mc = Minecraft.getMinecraft();
        World worldA = new World();
        World worldB = new World();
        mc.field_71439_g = new Entity(0.0, 2.0, 0.0);
        mc.field_71441_e = worldA;
        clientTick(mod); // initial attach: no old session exists to retire.

        final int source = 811;
        final long oldGeneration = runtime.sourceStarted(
            source, "minecraft/sounds/random/world_old.ogg", new Vec3(1.0, 2.0, 3.0), 1.0, 1.0, false, false);
        check(oldGeneration > 0L && activeSourceCount(runtime) == 1, "old-world logical source was not registered");

        org.lwjgl.openal.AL10.STATES.put(source, org.lwjgl.openal.AL10.AL_PLAYING);
        efx.applyDirectOnly(source, new LegacyEffectParameters(0.5f, 0.35f, 0.0f, 0.0f));
        efx.applyVelocity(source, new Vec3(3.0, 4.0, 5.0), 1.0f);
        long contextGeneration = wet.currentContextGeneration();
        SoftwareWetPcmRenderer.Rendered rendered = new SoftwareWetPcmRenderer.Rendered(new byte[64], 8000, 16);
        check(wet.apply(source, oldGeneration, contextGeneration, rendered).getApplied(),
            "old-world software-wet voice was not seeded");
        long workerGenerationBefore = poolGeneration.getLong(renderer);
        check(!org.lwjgl.openal.EFX10.FILTERS.isEmpty(), "old-world EFX state missing before replacement");

        mc.field_71441_e = worldB;
        clientTick(mod);
        check(activeSourceCount(runtime) == 0, "old-world logical source survived world replacement");
        check(poolGeneration.getLong(renderer) > workerGenerationBefore,
            "world replacement did not invalidate software-wet worker generation");
        // Native state is OpenAL-owner-thread state and must not be mutated by the client tick.
        check(wet.isActive(source, oldGeneration), "client thread illegally cleared old-world wet voice");
        check(!org.lwjgl.openal.EFX10.FILTERS.isEmpty(), "client thread illegally cleared old-world EFX state");

        audioTick();
        check(!wet.isActive(source, oldGeneration), "old-world wet voice survived owner-thread world reset");
        check(org.lwjgl.openal.EFX10.FILTERS.isEmpty(), "old-world EFX filters survived owner-thread world reset");
        checkVelocity(source, 0f, 0f, 0f, "old-world AcousticShaders velocity survived world reset");

        long replacementGeneration = runtime.sourceStarted(
            source, "minecraft/sounds/random/world_new.ogg", new Vec3(2.0, 2.0, 3.0), 1.0, 1.0, false, false);
        check(replacementGeneration > oldGeneration, "replacement world reused an old logical generation");
        check(activeSourceCount(runtime) == 1, "replacement-world logical source missing");

        // World unload is the same identity boundary and must retire the replacement source.
        long generationBeforeUnload = poolGeneration.getLong(renderer);
        mc.field_71441_e = null;
        clientTick(mod);
        check(activeSourceCount(runtime) == 0, "logical source survived world unload");
        check(poolGeneration.getLong(renderer) > generationBeforeUnload,
            "world unload did not invalidate software-wet worker generation");

        System.out.println("PASS: world replacement/unload retires logical source generations and defers native cleanup to audio owner thread");
    }

    private static void clientTick(AcousticShadersForgeMod mod) {
        mod.clientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
    }

    private static void audioTick() throws Exception {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { LegacySoundHook.onAudioCommandTick(); }
        }, "Thread-WorldSessionReplacement");
        thread.start();
        thread.join();
    }

    private static int activeSourceCount(LegacyClientRuntime runtime) throws Exception {
        Field field = LegacyClientRuntime.class.getDeclaredField("activeSources");
        field.setAccessible(true);
        return ((Map<?,?>) field.get(runtime)).size();
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
