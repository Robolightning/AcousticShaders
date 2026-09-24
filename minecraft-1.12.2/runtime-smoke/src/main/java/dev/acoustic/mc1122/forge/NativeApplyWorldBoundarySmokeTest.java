package dev.acoustic.mc1122.forge;

import dev.acoustic.api.math.Vec3;
import dev.acoustic.api.material.AcousticMaterials;
import dev.acoustic.api.scene.AcousticScene;
import dev.acoustic.api.scene.AcousticVoxel;
import dev.acoustic.core.passes.ReflectionField;
import dev.acoustic.core.passes.ReflectionSample;
import dev.acoustic.mc1122.LegacyPublishedState;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Final logical validation and native EFX apply must be linearized with a world/session boundary. */
public final class NativeApplyWorldBoundarySmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/native-apply-world-boundary-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);

        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(root.resolve("config").toFile()));
        LegacyClientRuntime runtime = AcousticShadersForgeMod.runtime();
        LegacyEfxBackend efx = (LegacyEfxBackend) privateStaticField(LegacySoundHook.class, "efx");
        LegacySoftwareWetBackend wet = (LegacySoftwareWetBackend) privateStaticField(LegacySoundHook.class, "wet");

        Minecraft mc = Minecraft.getMinecraft();
        World worldA = new World();
        World worldB = new World();
        mc.field_71439_g = new Entity(0.0, 2.0, 0.0);
        mc.field_71441_e = worldA;
        clientTick(mod);
        waitForRoomWorkerIdle(runtime, 10000L);

        final int source = 923;
        final SimpleScene scene = new SimpleScene(123L);
        seedReadyPublishedState(runtime, scene);
        long generation = runtime.sourceStarted(
            source, "minecraft/sounds/random/native_apply_boundary.ogg", new Vec3(1.0, 2.0, 3.0),
            1.0, 1.0, false, false);
        check(generation > 0L, "logical source was not registered");
        waitForCompletedSource(runtime, 10000L);
        check(activeSourceCount(runtime) == 1, "logical source disappeared before native drain");

        org.lwjgl.openal.AL10.STATES.put(source, org.lwjgl.openal.AL10.AL_PLAYING);
        final CountDownLatch nativeEntered = new CountDownLatch(1);
        final CountDownLatch nativeRelease = new CountDownLatch(1);
        final CountDownLatch boundaryDone = new CountDownLatch(1);
        org.lwjgl.openal.AL10.blockNextDirectFilterWrite(nativeEntered, nativeRelease);

        Thread drain = new Thread(new Runnable() {
            @Override public void run() { runtime.drainFullSourceResults(efx, wet); }
        }, "Thread-NativeApplyWorldBoundary-Audio");
        drain.start();
        check(nativeEntered.await(10000L, TimeUnit.MILLISECONDS),
            "audio drain never reached blocked AL_DIRECT_FILTER native write");

        Thread boundary = new Thread(new Runnable() {
            @Override public void run() {
                mc.field_71441_e = worldB;
                clientTick(mod);
                boundaryDone.countDown();
            }
        }, "Thread-NativeApplyWorldBoundary-Client");
        boundary.start();

        // The world boundary must not cross final logical validation while the native write is
        // still in-flight. On the buggy implementation drain releases sourceLock before EFX apply,
        // so this latch reaches zero here and the assertion intentionally fails.
        check(!boundaryDone.await(250L, TimeUnit.MILLISECONDS),
            "world/session boundary crossed while a validated old-world native EFX write was still in-flight");

        nativeRelease.countDown();
        drain.join(10000L);
        boundary.join(10000L);
        check(!drain.isAlive(), "audio drain did not finish after native write release");
        check(!boundary.isAlive(), "world boundary did not finish after native write release");
        check(activeSourceCount(runtime) == 0, "old-world logical source survived serialized boundary");

        // Native cleanup is intentionally owner-thread-only and occurs on the next audio command tick.
        audioTick();
        Integer direct = org.lwjgl.openal.AL10.DIRECT_FILTERS.get(Integer.valueOf(source));
        check(direct == null || direct.intValue() == 0,
            "old-world direct filter survived owner-thread reset after serialized world boundary: " + direct);
        org.lwjgl.openal.AL10.clearBlockingHooks();
        runtime.close();
        System.out.println("PASS: final logical source validation and native EFX apply are serialized against world/session replacement");
    }

    private static void clientTick(AcousticShadersForgeMod mod) {
        mod.clientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
    }

    private static void audioTick() throws Exception {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { LegacySoundHook.onAudioCommandTick(); }
        }, "Thread-NativeApplyWorldBoundary-Reset");
        thread.start();
        thread.join(10000L);
        check(!thread.isAlive(), "owner-thread reset tick did not finish");
    }

    private static int activeSourceCount(LegacyClientRuntime runtime) throws Exception {
        Field field = LegacyClientRuntime.class.getDeclaredField("activeSources");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(runtime)).size();
    }

    private static void waitForCompletedSource(LegacyClientRuntime runtime, long timeoutMillis) throws Exception {
        Field field = LegacyClientRuntime.class.getDeclaredField("completedSources");
        field.setAccessible(true);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!((java.util.Queue<?>) field.get(runtime)).isEmpty()) return;
            Thread.sleep(1L);
        }
        throw new AssertionError("source worker did not publish a result for native boundary test");
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
        Field epochField = LegacyClientRuntime.class.getDeclaredField("epoch");
        epochField.setAccessible(true);
        long epoch = epochField.getLong(runtime);
        ReflectionField reflection = new ReflectionField(0, Collections.<ReflectionSample>emptyList());
        LegacyPublishedState state = new LegacyPublishedState(
            scene, new Vec3(0.0, 2.0, 0.0), dev.acoustic.core.passes.LegacyRoomEstimate.DEFAULT,
            reflection, epoch, true);
        Field published = LegacyClientRuntime.class.getDeclaredField("publishedValue");
        published.setAccessible(true);
        published.set(runtime, state);
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
