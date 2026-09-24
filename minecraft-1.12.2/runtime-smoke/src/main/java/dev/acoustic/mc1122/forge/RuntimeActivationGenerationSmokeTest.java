package dev.acoustic.mc1122.forge;

import dev.acoustic.api.material.AcousticMaterials;
import dev.acoustic.api.math.Vec3;
import dev.acoustic.api.scene.AcousticScene;
import dev.acoustic.api.scene.AcousticVoxel;
import dev.acoustic.core.passes.ReflectionField;
import dev.acoustic.core.passes.ReflectionSample;
import dev.acoustic.mc1122.LegacyPublishedState;
import dev.acoustic.platform.ListenerSnapshot;
import dev.acoustic.platform.PlatformFrameSnapshot;
import java.io.File;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** Executor/config activation must retire old analysis workers before they can consume new-generation work. */
public final class RuntimeActivationGenerationSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Field epoch = LegacyClientRuntime.class.getDeclaredField("epoch");
        check(Modifier.isVolatile(epoch.getModifiers()), "cross-thread epoch guard is not volatile");
        testSourceWorkerCannotConsumeNewGeneration();
        testRoomWorkerCannotConsumeNewGeneration();
        System.out.println("PASS: runtime activation is a hard generation boundary for source/room workers and epoch is cross-thread visible");
    }

    private static void testSourceWorkerCannotConsumeNewGeneration() throws Exception {
        LegacyClientRuntime runtime = runtime("source");
        Object sourceLock = privateField(runtime, "sourceLock");
        BlockingScene oldScene = new BlockingScene(301L);
        seedReadyPublishedState(runtime, oldScene);
        long oldGeneration = runtime.sourceStarted(1801, "minecraft/sounds/random/activation_old.ogg",
            new Vec3(1.0, 2.0, 3.0), 1.0, 1.0, false, false);
        check(oldGeneration > 0L, "old source did not start");
        check(oldScene.awaitEntered(10000L), "old source worker never entered the real shader pipeline");
        ExecutorService oldAnalysis = (ExecutorService) privateField(runtime, "analysisExecutor");
        Blockers blockers;
        synchronized (sourceLock) {
            reactivate(runtime);
            ExecutorService current = (ExecutorService) privateField(runtime, "analysisExecutor");
            blockers = occupy(current);
            seedReadyPublishedState(runtime, new AirScene(302L));
            long nextGeneration = runtime.sourceStarted(1802, "minecraft/sounds/random/activation_new.ogg",
                new Vec3(2.0, 2.0, 3.0), 1.0, 1.0, false, false);
            check(nextGeneration > oldGeneration, "new source generation did not advance across activation");
            check(pendingSourceCount(runtime) == 1, "new source request was not queued behind the blocked new executor");
            oldScene.release();
        }
        check(oldAnalysis.awaitTermination(10000L, TimeUnit.MILLISECONDS), "old analysis executor did not terminate after activation");
        check(pendingSourceCount(runtime) == 1,
            "old source worker consumed a request belonging to the new analysis generation");
        blockers.release.countDown();
        waitForPendingSources(runtime, 0, 10000L);
        runtime.close();
    }

    private static void testRoomWorkerCannotConsumeNewGeneration() throws Exception {
        LegacyClientRuntime runtime = runtime("room");
        BlockingScene oldScene = new BlockingScene(401L);
        Object oldRequest = roomRequest(runtime, oldScene, 1L);
        invokeScheduleRoom(runtime, oldRequest);
        check(oldScene.awaitEntered(10000L), "old room worker never entered room estimation");
        ExecutorService oldAnalysis = (ExecutorService) privateField(runtime, "analysisExecutor");
        Blockers blockers;
        synchronized (runtime) {
            reactivate(runtime);
            ExecutorService current = (ExecutorService) privateField(runtime, "analysisExecutor");
            blockers = occupy(current);
            Object nextRequest = roomRequest(runtime, new AirScene(402L), 2L);
            invokeScheduleRoom(runtime, nextRequest);
            check(privateField(runtime, "pendingRoom") != null, "new room request was not queued behind the blocked new executor");
            oldScene.release();
        }
        check(oldAnalysis.awaitTermination(10000L, TimeUnit.MILLISECONDS), "old room analysis executor did not terminate after activation");
        check(privateField(runtime, "pendingRoom") != null,
            "old room worker consumed a request belonging to the new analysis generation");
        blockers.release.countDown();
        waitForPendingRoom(runtime, 10000L);
        waitForPublishedRevision(runtime, 402L, 10000L);
        runtime.close();
    }

    private static LegacyClientRuntime runtime(String suffix) throws Exception {
        Path root = Paths.get("out/runtime-activation-generation-" + suffix).toAbsolutePath();
        delete(root);
        Files.createDirectories(root);
        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(root.resolve("config").toFile()));
        return AcousticShadersForgeMod.runtime();
    }

    private static void reactivate(LegacyClientRuntime runtime) throws Exception {
        runtime.applyUiConfiguration(runtime.config().packs(), runtime.config().profile(), runtime.config().optionOverrides());
    }

    private static Blockers occupy(ExecutorService executor) throws Exception {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        int workers = pool.getMaximumPoolSize();
        CountDownLatch entered = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < workers; i++) {
            executor.submit(new Runnable() {
                @Override public void run() {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
        }
        check(entered.await(5000L, TimeUnit.MILLISECONDS), "new analysis executor blockers did not start");
        return new Blockers(release);
    }

    private static Object roomRequest(LegacyClientRuntime runtime, AcousticScene scene, long sequence) throws Exception {
        long epoch = longField(runtime, "epoch");
        Vec3 listener = new Vec3(0.0, 2.0, 0.0);
        PlatformFrameSnapshot frame = new PlatformFrameSnapshot(
            scene,
            new ListenerSnapshot(listener, new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 1.0, 0.0)),
            Collections.emptyList(), epoch, sequence);
        Class<?> type = Class.forName("dev.acoustic.mc1122.forge.LegacyClientRuntime$RoomRequest");
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(frame, Collections.emptyList(), Boolean.TRUE, runtime.pack(),
            runtime.pack().performanceTuning(), privateField(runtime, "parallelWork"));
    }

    private static void invokeScheduleRoom(LegacyClientRuntime runtime, Object request) throws Exception {
        Method method = LegacyClientRuntime.class.getDeclaredMethod("scheduleRoom", request.getClass());
        method.setAccessible(true);
        method.invoke(runtime, request);
    }

    private static void seedReadyPublishedState(LegacyClientRuntime runtime, AcousticScene scene) throws Exception {
        long epoch = longField(runtime, "epoch");
        ReflectionField reflection = new ReflectionField(0, Collections.<ReflectionSample>emptyList());
        LegacyPublishedState state = new LegacyPublishedState(
            scene, new Vec3(0.0, 2.0, 0.0), dev.acoustic.core.passes.LegacyRoomEstimate.DEFAULT, reflection, epoch, true);
        Field published = LegacyClientRuntime.class.getDeclaredField("publishedValue");
        published.setAccessible(true);
        published.set(runtime, state);
    }

    private static int pendingSourceCount(LegacyClientRuntime runtime) throws Exception {
        @SuppressWarnings("unchecked") Map<Integer,Object> pending = (Map<Integer,Object>) privateField(runtime, "pendingSources");
        return pending.size();
    }

    private static void waitForPendingSources(LegacyClientRuntime runtime, int expected, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (pendingSourceCount(runtime) == expected) return;
            Thread.sleep(1L);
        }
        throw new AssertionError("pending source count did not reach " + expected + "; actual=" + pendingSourceCount(runtime));
    }

    private static void waitForPendingRoom(LegacyClientRuntime runtime, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (privateField(runtime, "pendingRoom") == null) return;
            Thread.sleep(1L);
        }
        throw new AssertionError("new room request remained pending after releasing the current executor");
    }

    private static void waitForPublishedRevision(LegacyClientRuntime runtime, long revision, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            LegacyPublishedState state = runtime.published();
            if (state.ready() && state.scene() != null && state.scene().revision() == revision) return;
            Thread.sleep(1L);
        }
        throw new AssertionError("new room generation was not published; expected revision=" + revision);
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

    private static final class Blockers {
        final CountDownLatch release;
        Blockers(CountDownLatch release) { this.release = release; }
    }

    private static class AirScene implements AcousticScene {
        private final long revision;
        AirScene(long revision) { this.revision = revision; }
        @Override public AcousticVoxel voxelAt(int x, int y, int z) { return new AcousticVoxel(false, AcousticMaterials.AIR); }
        @Override public long revision() { return revision; }
        @Override public boolean containsNonAirMedia() { return false; }
    }

    private static final class BlockingScene extends AirScene {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        BlockingScene(long revision) { super(revision); }
        @Override public AcousticVoxel voxelAt(int x, int y, int z) {
            entered.countDown();
            while (release.getCount() != 0L) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* activation intentionally interrupts old pools */ }
            }
            return new AcousticVoxel(false, AcousticMaterials.AIR);
        }
        boolean awaitEntered(long timeoutMillis) throws InterruptedException { return entered.await(timeoutMillis, TimeUnit.MILLISECONDS); }
        void release() { release.countDown(); }
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
