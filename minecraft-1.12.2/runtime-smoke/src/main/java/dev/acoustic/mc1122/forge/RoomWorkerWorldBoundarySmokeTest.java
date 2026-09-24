package dev.acoustic.mc1122.forge;

import dev.acoustic.api.material.AcousticMaterials;
import dev.acoustic.api.math.Vec3;
import dev.acoustic.api.pipeline.ParallelWorkExecutor;
import dev.acoustic.api.scene.AcousticScene;
import dev.acoustic.api.scene.AcousticVoxel;
import dev.acoustic.mc1122.LegacyPerformanceTuning;
import dev.acoustic.mc1122.LegacyPublishedState;
import dev.acoustic.mc1122.LegacyShaderPackRuntime;
import dev.acoustic.platform.ListenerSnapshot;
import dev.acoustic.platform.PlatformFrameSnapshot;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** A room solve started in one world must not publish after that world/session is retired. */
public final class RoomWorkerWorldBoundarySmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/room-worker-world-boundary-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);

        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(root.resolve("config").toFile()));
        LegacyClientRuntime runtime = AcousticShadersForgeMod.runtime();

        Minecraft mc = Minecraft.getMinecraft();
        mc.field_71439_g = new Entity(0.0, 2.0, 0.0);
        mc.field_71441_e = new World();
        clientTick(mod);
        waitForRoomWorkerIdle(runtime, 10000L);

        long oldEpoch = longField(runtime, "epoch");
        BlockingScene oldScene = new BlockingScene(901L);
        Object request = roomRequest(runtime, oldScene, oldEpoch, 91L);
        invokeScheduleRoom(runtime, request);
        check(oldScene.awaitEntered(10000L), "old-world room worker never entered real room estimation");

        // A real world unload is a hard session identity boundary and does not schedule a
        // replacement room request, so any later publication can only come from this stale worker.
        mc.field_71441_e = null;
        clientTick(mod);
        check(longField(runtime, "epoch") > oldEpoch, "world unload did not advance acoustic epoch");
        check(!runtime.published().ready(), "world unload did not clear published room state");
        check(privateField(runtime, "pendingRoom") == null, "world unload left a room request pending");

        oldScene.release();
        waitForRoomWorkerIdle(runtime, 10000L);
        LegacyPublishedState after = runtime.published();
        check(!after.ready(), "old-world in-flight room solve republished after world unload");
        check(after.scene() == null, "old-world scene crossed the world/session boundary");

        runtime.close();
        System.out.println("PASS: in-flight old-world room analysis cannot republish across a real world unload boundary");
    }

    private static Object roomRequest(LegacyClientRuntime runtime, AcousticScene scene, long epoch, long sequence) throws Exception {
        Vec3 listener = new Vec3(0.0, 2.0, 0.0);
        PlatformFrameSnapshot frame = new PlatformFrameSnapshot(
            scene,
            new ListenerSnapshot(listener, new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 1.0, 0.0)),
            Collections.emptyList(), epoch, sequence);
        Class<?> type = Class.forName("dev.acoustic.mc1122.forge.LegacyClientRuntime$RoomRequest");
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        LegacyShaderPackRuntime pack = runtime.pack();
        LegacyPerformanceTuning tuning = pack.performanceTuning();
        ParallelWorkExecutor parallel = (ParallelWorkExecutor) privateField(runtime, "parallelWork");
        return ctor.newInstance(frame, Collections.emptyList(), Boolean.TRUE, pack, tuning, parallel);
    }

    private static void invokeScheduleRoom(LegacyClientRuntime runtime, Object request) throws Exception {
        Method method = LegacyClientRuntime.class.getDeclaredMethod("scheduleRoom", request.getClass());
        method.setAccessible(true);
        method.invoke(runtime, request);
    }

    private static void clientTick(AcousticShadersForgeMod mod) {
        mod.clientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
    }

    private static void waitForRoomWorkerIdle(LegacyClientRuntime runtime, long timeoutMillis) throws Exception {
        Field field = LegacyClientRuntime.class.getDeclaredField("roomWorkerRunning");
        field.setAccessible(true);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!field.getBoolean(runtime)) return;
            Thread.sleep(1L);
        }
        throw new AssertionError("room worker did not become idle after stale request release");
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

    private static final class BlockingScene implements AcousticScene {
        private final long revision;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingScene(long revision) { this.revision = revision; }

        @Override public AcousticVoxel voxelAt(int x, int y, int z) {
            entered.countDown();
            while (release.getCount() != 0L) {
                try { release.await(); }
                catch (InterruptedException ignored) {
                    // World/session replacement intentionally does not own the executor lifecycle;
                    // remain blocked until the test explicitly releases the stale room solve.
                }
            }
            return new AcousticVoxel(false, AcousticMaterials.AIR);
        }

        @Override public long revision() { return revision; }
        @Override public boolean containsNonAirMedia() { return false; }
        boolean awaitEntered(long timeoutMillis) throws InterruptedException {
            return entered.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
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
