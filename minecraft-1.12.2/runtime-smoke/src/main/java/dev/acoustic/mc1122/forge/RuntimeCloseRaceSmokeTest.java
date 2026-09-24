package dev.acoustic.mc1122.forge;

import dev.acoustic.api.math.Vec3;
import dev.acoustic.core.rir.ImpulseResponse;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** Closing a runtime/renderer must be an atomic publication barrier for in-flight work. */
public final class RuntimeCloseRaceSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        testSourceStartCannotCrossClose();
        testWetSubmitCannotCrossClose();
        testWetReconfigureCannotReviveClosedPool();
        testWetRenderCannotPublishAfterClose();
        System.out.println("PASS: runtime close is a publication barrier for source registration and in-flight software-wet results");
    }

    private static void testSourceStartCannotCrossClose() throws Exception {
        Path root = Paths.get("out/runtime-close-race-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);
        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(root.resolve("config").toFile()));
        LegacyClientRuntime runtime = AcousticShadersForgeMod.runtime();
        Object sourceLock = privateField(runtime, "sourceLock");
        AtomicLong result = new AtomicLong(Long.MIN_VALUE);
        Thread starter;
        synchronized (sourceLock) {
            starter = new Thread(new Runnable() {
                @Override public void run() {
                    result.set(runtime.sourceStarted(1501, "minecraft/sounds/random/close_race.ogg",
                        new Vec3(1.0, 2.0, 3.0), 1.0, 1.0, false, false));
                }
            }, "RuntimeCloseRace-sourceStarted");
            starter.start();
            waitForState(starter, Thread.State.BLOCKED, 5000L,
                "sourceStarted did not block on sourceLock before close");
            // close() re-enters sourceLock from this same thread after setting closed=true.
            // When the starter is released it must re-check closed while holding sourceLock.
            runtime.close();
        }
        starter.join(5000L);
        check(!starter.isAlive(), "sourceStarted thread survived runtime close");
        check(result.get() == 0L, "sourceStarted crossed close boundary: generation=" + result.get());
        check(activeSourceCount(runtime) == 0, "closed runtime resurrected an active source");
    }


    private static void testWetSubmitCannotCrossClose() throws Exception {
        LegacyAudioConfig config = new LegacyAudioConfig(true, 1, 8, 64, 0.25, 0.5f, 2, 1024 * 1024);
        LegacySoftwareWetRenderer renderer = new LegacySoftwareWetRenderer(config);
        Object lock = privateField(renderer, "lock");
        LegacySoftwareWetRenderer.Request request = new LegacySoftwareWetRenderer.Request(
            1551, 1L, 1L, 1L, 1L,
            new LegacySoftwareWetRenderer.PcmCapture(new byte[128], 8000),
            new ImpulseResponse(8000, new float[] {1.0f}), null, null, new Vec3(0.0, 0.0, 1.0));
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread submitter;
        synchronized (lock) {
            submitter = new Thread(new Runnable() {
                @Override public void run() {
                    try { renderer.submit(request); }
                    catch (Throwable t) { failure.set(t); }
                }
            }, "RuntimeCloseRace-wetSubmit");
            submitter.start();
            waitForState(submitter, Thread.State.BLOCKED, 5000L,
                "software-wet submit did not block on lifecycle lock before close");
            // close() sets closed first and then re-enters this lock. Once released, the
            // waiting submitter must re-check closed instead of enqueueing/submitting work
            // to the now-shutdown executor.
            renderer.close();
        }
        submitter.join(5000L);
        check(!submitter.isAlive(), "software-wet submitter survived close barrier");
        check(failure.get() == null, "software-wet submit crossed close into executor failure: " + failure.get());
        check(renderer.getSubmitted() == 0L, "software-wet submit was counted after close barrier");
        check(renderer.poll() == null, "software-wet submit published a result after close barrier");
    }


    private static void testWetReconfigureCannotReviveClosedPool() throws Exception {
        LegacyAudioConfig original = new LegacyAudioConfig(true, 1, 8, 64, 0.25, 0.5f, 2, 1024 * 1024);
        LegacySoftwareWetRenderer renderer = new LegacySoftwareWetRenderer(original);
        Object poolBefore = privateField(renderer, "workers");
        renderer.close();
        LegacyAudioConfig changed = new LegacyAudioConfig(true, 2, 8, 128, 0.5, 0.7f, 3, 2 * 1024 * 1024);
        renderer.reconfigure(changed);
        check(privateField(renderer, "workers") == poolBefore,
            "reconfigure replaced the worker pool after renderer close");
        check(privateField(renderer, "config") == original,
            "reconfigure mutated closed renderer configuration");
    }

    private static void testWetRenderCannotPublishAfterClose() throws Exception {
        LegacyAudioConfig config = new LegacyAudioConfig(true, 1, 8, 64, 3.0, 0.65f, 4, 8 * 1024 * 1024);
        LegacySoftwareWetRenderer renderer = new LegacySoftwareWetRenderer(config);
        Object lock = privateField(renderer, "lock");

        final int rate = 16000;
        byte[] pcm = new byte[rate * 2 * 2]; // two seconds mono PCM16
        float[] impulse = new float[rate * 3];
        impulse[0] = 1.0f;
        for (int i = 97; i < impulse.length; i += 97) impulse[i] = 0.02f;
        LegacySoftwareWetRenderer.Request request = new LegacySoftwareWetRenderer.Request(
            1601, 1L, 1L, 1L, 1L,
            new LegacySoftwareWetRenderer.PcmCapture(pcm, rate),
            new ImpulseResponse(rate, impulse), null, null, new Vec3(0.0, 0.0, 1.0));
        renderer.submit(request);

        Thread worker = waitForWetRenderThread(10000L);
        check(worker != null, "software-wet worker never entered real convolution");

        synchronized (lock) {
            waitForState(worker, Thread.State.BLOCKED, 15000L,
                "software-wet worker did not reach publication lock");
            long generationBefore = longField(renderer, "poolGeneration");
            renderer.close(); // re-enters lock and invalidates generation atomically
            check(longField(renderer, "poolGeneration") > generationBefore,
                "software-wet close did not advance render generation");
            check(renderer.poll() == null, "close left a completed wet result queued");
        }

        worker.join(5000L);
        check(!worker.isAlive(), "software-wet worker survived close publication barrier");
        check(renderer.poll() == null, "in-flight wet render published after close");
        check(renderer.getRendered() == 0L, "closed renderer counted stale render as published");
        check(renderer.getDropped() >= 1L, "closed renderer did not account stale in-flight render as dropped");
    }

    private static Thread waitForWetRenderThread(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (Map.Entry<Thread,StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (!thread.getName().startsWith("acoustic-wet-render-")) continue;
                for (StackTraceElement frame : entry.getValue()) {
                    String c = frame.getClassName();
                    if (c.equals("dev.acoustic.core.dsp.SoftwareWetPcmRenderer") ||
                        c.equals("dev.acoustic.core.dsp.PartitionedConvolver") ||
                        c.equals("dev.acoustic.core.dsp.Radix2Fft")) return thread;
                }
            }
            Thread.sleep(1L);
        }
        return null;
    }

    private static void waitForState(Thread thread, Thread.State state, long timeoutMillis, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (thread.getState() == state) return;
            if (!thread.isAlive()) break;
            Thread.sleep(1L);
        }
        throw new AssertionError(message + "; state=" + thread.getState());
    }

    private static int activeSourceCount(LegacyClientRuntime runtime) throws Exception {
        @SuppressWarnings("unchecked") Map<Integer,Object> active = (Map<Integer,Object>) privateField(runtime, "activeSources");
        return active.size();
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
