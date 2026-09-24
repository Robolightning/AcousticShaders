package dev.acoustic.mc1122.forge;

import dev.acoustic.api.math.Vec3;
import dev.acoustic.api.material.AcousticMaterials;
import dev.acoustic.api.scene.AcousticScene;
import dev.acoustic.api.scene.AcousticVoxel;
import dev.acoustic.core.dsp.SoftwareWetPcmRenderer;
import dev.acoustic.core.passes.LegacyEffectParameters;
import dev.acoustic.core.passes.ReflectionField;
import dev.acoustic.core.passes.ReflectionSample;
import dev.acoustic.mc1122.LegacyPublishedState;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** A validated software-wet result must not start native voice creation across a world boundary. */
public final class NativeWetApplyWorldBoundarySmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/native-wet-apply-world-boundary-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);

        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(root.resolve("config").toFile()));
        LegacyClientRuntime runtime = AcousticShadersForgeMod.runtime();
        LegacyEfxBackend efx = (LegacyEfxBackend) privateStaticField(LegacySoundHook.class, "efx");
        LegacySoftwareWetBackend wet = (LegacySoftwareWetBackend) privateStaticField(LegacySoundHook.class, "wet");
        LegacyAudioConfig audio = new LegacyAudioConfig(true, 1, 2, 64, 0.1, 0.5f, 4, 65536);
        runtime.applyLegacyAudioConfig(audio);
        wet.reconfigure(audio);

        Minecraft mc = Minecraft.getMinecraft();
        World worldA = new World();
        World worldB = new World();
        mc.field_71439_g = new Entity(0.0, 2.0, 0.0);
        mc.field_71441_e = worldA;
        clientTick(mod);
        waitForRoomWorkerIdle(runtime, 10000L);

        // Register the logical source while no published scene exists so no full source worker
        // competes with the manually seeded wet result used by this native-apply race.
        setPublished(runtime, LegacyPublishedState.EMPTY);
        final int source = 924;
        long generation = runtime.sourceStarted(
            source, "minecraft/sounds/random/native_wet_boundary.ogg", new Vec3(1.0, 2.0, 3.0),
            1.0, 1.0, false, false);
        check(generation > 0L, "logical source was not registered");

        SimpleScene scene = new SimpleScene(124L);
        seedReadyPublishedState(runtime, scene);
        setLastEffect(runtime, source, new LegacyEffectParameters(0.55f, 0.42f, 0.18f, 0.23f));
        long epoch = longField(runtime, "epoch");
        long contextGeneration = wet.currentContextGeneration();
        check(contextGeneration > 0L, "test OpenAL context did not initialize");
        org.lwjgl.openal.AL10.STATES.put(source, org.lwjgl.openal.AL10.AL_PLAYING);

        Object renderer = privateField(runtime, "wetRenderer");
        enqueueWetResult(renderer, source, generation, contextGeneration, epoch, scene.revision());

        final CountDownLatch nativeEntered = new CountDownLatch(1);
        final CountDownLatch nativeRelease = new CountDownLatch(1);
        final CountDownLatch boundaryDone = new CountDownLatch(1);
        org.lwjgl.openal.AL10.blockNextBufferWrite(nativeEntered, nativeRelease);

        Thread drain = new Thread(new Runnable() {
            @Override public void run() { runtime.drainFullSourceResults(efx, wet); }
        }, "Thread-NativeWetApplyWorldBoundary-Audio");
        drain.start();
        check(nativeEntered.await(10000L, TimeUnit.MILLISECONDS),
            "wet drain never reached blocked AL_BUFFER native write");

        Thread boundary = new Thread(new Runnable() {
            @Override public void run() {
                mc.field_71441_e = worldB;
                clientTick(mod);
                boundaryDone.countDown();
            }
        }, "Thread-NativeWetApplyWorldBoundary-Client");
        boundary.start();

        check(!boundaryDone.await(250L, TimeUnit.MILLISECONDS),
            "world/session boundary crossed while a validated old-world software-wet native write was still in-flight");

        nativeRelease.countDown();
        drain.join(10000L);
        boundary.join(10000L);
        check(!drain.isAlive(), "wet drain did not finish after native write release");
        check(!boundary.isAlive(), "world boundary did not finish after wet native write release");
        check(activeSourceCount(runtime) == 0, "old-world logical source survived serialized wet boundary");

        audioTick();
        check(!wet.isActive(source, generation), "old-world wet voice survived owner-thread reset after world boundary");
        org.lwjgl.openal.AL10.clearBlockingHooks();
        runtime.close();
        System.out.println("PASS: final logical wet validation and native voice creation are serialized against world/session replacement");
    }

    private static void enqueueWetResult(Object renderer, int source, long generation, long contextGeneration,
                                         long epoch, long sceneRevision) throws Exception {
        Class<?> resultType = Class.forName("dev.acoustic.mc1122.forge.LegacySoftwareWetRenderer$Result");
        Constructor<?> ctor = resultType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        SoftwareWetPcmRenderer.Rendered rendered = new SoftwareWetPcmRenderer.Rendered(new byte[64], 8000, 16);
        Object result = ctor.newInstance(source, generation, contextGeneration, epoch, sceneRevision, rendered);
        Field completedField = renderer.getClass().getDeclaredField("completed");
        completedField.setAccessible(true);
        @SuppressWarnings("unchecked") Queue<Object> completed = (Queue<Object>) completedField.get(renderer);
        completed.add(result);
    }

    private static void setLastEffect(LegacyClientRuntime runtime, int source, LegacyEffectParameters effect) throws Exception {
        Field activeField = LegacyClientRuntime.class.getDeclaredField("activeSources");
        activeField.setAccessible(true);
        Object state = ((Map<?, ?>) activeField.get(runtime)).get(Integer.valueOf(source));
        check(state != null, "active source state missing");
        Field effectField = state.getClass().getDeclaredField("lastEffect");
        effectField.setAccessible(true);
        effectField.set(state, effect);
    }

    private static void clientTick(AcousticShadersForgeMod mod) {
        mod.clientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
    }

    private static void audioTick() throws Exception {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { LegacySoundHook.onAudioCommandTick(); }
        }, "Thread-NativeWetApplyWorldBoundary-Reset");
        thread.start();
        thread.join(10000L);
        check(!thread.isAlive(), "owner-thread reset tick did not finish");
    }

    private static int activeSourceCount(LegacyClientRuntime runtime) throws Exception {
        Field field = LegacyClientRuntime.class.getDeclaredField("activeSources");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(runtime)).size();
    }

    private static void waitForRoomWorkerIdle(LegacyClientRuntime runtime, long timeoutMillis) throws Exception {
        Field field = LegacyClientRuntime.class.getDeclaredField("roomWorkerRunning");
        field.setAccessible(true);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!field.getBoolean(runtime)) return;
            Thread.sleep(1L);
        }
        throw new AssertionError("initial room worker did not become idle");
    }

    private static void seedReadyPublishedState(LegacyClientRuntime runtime, AcousticScene scene) throws Exception {
        long epoch = longField(runtime, "epoch");
        ReflectionField reflection = new ReflectionField(0, Collections.<ReflectionSample>emptyList());
        setPublished(runtime, new LegacyPublishedState(
            scene, new Vec3(0.0, 2.0, 0.0), dev.acoustic.core.passes.LegacyRoomEstimate.DEFAULT,
            reflection, epoch, true));
    }

    private static void setPublished(LegacyClientRuntime runtime, LegacyPublishedState state) throws Exception {
        Field published = LegacyClientRuntime.class.getDeclaredField("publishedValue");
        published.setAccessible(true);
        published.set(runtime, state);
    }

    private static long longField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static Object privateField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object privateStaticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(Modifier.isStatic(field.getModifiers()) ? null : LegacySoundHook.INSTANCE);
    }

    private static final class SimpleScene implements AcousticScene {
        private final long revision;
        SimpleScene(long revision) { this.revision = revision; }
        @Override public AcousticVoxel voxelAt(int x, int y, int z) { return new AcousticVoxel(false, AcousticMaterials.AIR); }
        @Override public long revision() { return revision; }
        @Override public boolean containsNonAirMedia() { return false; }
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
