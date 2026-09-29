package dev.acoustic.mc1122.forge;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import paulscode.sound.Channel;

/** Finite speed-of-sound must delay the first native dry sample without replacing the source. */
public final class PropagationDelaySmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/propagation-delay-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);
        Minecraft mc = Minecraft.func_71410_x();
        mc.field_71441_e = new World();
        mc.field_71439_g = new Entity(0, 2, 0);

        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        Path forgeConfig = root.resolve("config");
        Files.createDirectories(forgeConfig);
        mod.preInit(new TestPre(forgeConfig.toFile()));
        mod.init(new FMLInitializationEvent());
        mod.postInit(new FMLPostInitializationEvent());
        TickEvent.ClientTickEvent tick = new TickEvent.ClientTickEvent(TickEvent.Phase.END);
        mod.clientTick(tick);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (AcousticShadersForgeMod.runtime().captureBootstrapActive() && System.nanoTime() < deadline) mod.clientTick(tick);
        check(!AcousticShadersForgeMod.runtime().captureBootstrapActive(), "scene bootstrap stalled");

        LegacyClientRuntime runtime = AcousticShadersForgeMod.runtime();
        // Listener eye is around y=3.62 in this stub world; keep source at the same height.
        final double distance = 34.3; // ~= 100 ms in 343 m/s air.
        double expected = distance / 343.0;
        double calculated = runtime.directPropagationDelaySeconds(new dev.acoustic.api.math.Vec3(distance, 3.62, 0.0));
        check(Math.abs(calculated - expected) < 0.015,
            "runtime direct propagation delay ignored finite air speed: expected=" + expected + " actual=" + calculated);

        FakeSource delayed = new FakeSource(701, (float) distance, 3.62f, 0f, "minecraft/sounds/random/explode1.ogg");
        delayed.play(delayed.channelOpenAL);
        check(delayed.pauseCalls == 1, "distant source was not placed into Paulscode paused state before native play");
        check(delayed.channelOpenAL.playCalls == 0, "distant source leaked a native sample before propagation delay");
        check(delayed.channelOpenAL.pauseCalls == 1, "distant source channel was not paused");
        check(runtime.computeDiagnostics().contains("propagation={pending=1"), "pending propagation delay missing from diagnostics: " + runtime.computeDiagnostics());

        Thread.sleep(135L);
        audioTick();
        check(delayed.channelOpenAL.playCalls == 1, "distant source was not resumed after physical flight time");
        check(delayed.playCalls == 2, "resume did not reuse the same Paulscode source lifecycle");
        check(runtime.computeDiagnostics().contains("pending=0"), "resumed propagation source remained pending");

        // Sub-audio-quantum delay stays immediate; adding scheduler jitter would be less accurate.
        FakeSource near = new FakeSource(702, 1.0f, 3.62f, 0f, "minecraft/sounds/random/click.ogg");
        near.play(near.channelOpenAL);
        check(near.pauseCalls == 0 && near.channelOpenAL.playCalls == 1,
            "near source was unnecessarily delayed");

        // Disabling acoustics must restore vanilla playback immediately rather than losing a held source.
        FakeSource forced = new FakeSource(703, 343.0f, 3.62f, 0f, "minecraft/sounds/random/explode2.ogg");
        forced.play(forced.channelOpenAL);
        check(forced.channelOpenAL.playCalls == 0, "long-delay source started before reset test");
        LegacySoundHook.requestEffectsReset();
        audioTick();
        check(forced.channelOpenAL.playCalls == 1, "effects reset did not force-resume delayed vanilla sound");

        // Cleanup before arrival cancels the pending wavefront; no ghost source may start later.
        FakeSource cancelled = new FakeSource(704, 34.3f, 3.62f, 0f, "minecraft/sounds/random/explode3.ogg");
        cancelled.play(cancelled.channelOpenAL);
        check(cancelled.channelOpenAL.playCalls == 0, "cancel test source was not delayed");
        LegacySoundHook.onSourceCleanup(cancelled);
        Thread.sleep(135L);
        audioTick();
        check(cancelled.channelOpenAL.playCalls == 0, "cleaned source resurrected after propagation delay");

        System.out.println("PASS: finite 343 m/s direct propagation delays first native dry sample, resumes on owner thread, skips near-source jitter, and respects reset/cleanup");
    }

    private static void audioTick() throws Exception {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { LegacySoundHook.onAudioCommandTick(); }
        }, "Thread-AcousticPropagationDelay");
        thread.start();
        thread.join();
    }

    public static final class FakeSource {
        public final Channel channelOpenAL;
        public final Position position;
        public final float gain = 1f;
        public final float sourceVolume = 1f;
        public final boolean priority = false;
        public final boolean toStream = false;
        public final boolean toLoop = false;
        public final int attModel = 2;
        public final String sourcename;
        public int pauseCalls;
        public int playCalls;
        public boolean paused;

        FakeSource(int sourceId, float x, float y, float z, String name) {
            channelOpenAL = new Channel(sourceId);
            position = new Position(x, y, z);
            sourcename = name;
        }

        public void pause() {
            paused = true;
            pauseCalls++;
            channelOpenAL.pause();
        }

        /** Simulates transformed SourceLWJGLOpenAL.play(Channel): redirect then TAIL callback. */
        public void play(Channel channel) {
            paused = false;
            playCalls++;
            LegacySoundHook.onNativeChannelPlay(this, channel);
            LegacySoundHook.onSourcePlay(this);
        }
    }

    public static final class Position {
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
