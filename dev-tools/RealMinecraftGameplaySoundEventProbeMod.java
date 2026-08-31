package dev.acoustic.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(
    modid = "acousticshaders_gameplay_probe",
    name = "Acoustic Shaders Gameplay Sound Event Probe",
    version = "1",
    dependencies = "required-after:acousticshaders",
    clientSideOnly = true
)
public final class RealMinecraftGameplaySoundEventProbeMod {
    private static final ProbeListener LISTENER = new ProbeListener();
    private static final boolean REQUIRE_CUDA_FDTD = Boolean.getBoolean("acousticshaders.probe.requireCudaFdtd");

    @Mod.EventHandler
    @SuppressWarnings("deprecation")
    public void postInit(FMLPostInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(LISTENER);
        System.out.println("ACOUSTIC-GAMEPLAY-SOUND-PROBE-REGISTERED");
    }

    private static final class ProbeListener {
        private int phase;
        private int phaseTicks;
        private Set<Integer> baseline = new HashSet<Integer>();
        private int sourceId;
        private volatile boolean serverActionComplete;
        private volatile String serverActionFailure;
        private volatile double eventX;
        private volatile double eventY;
        private volatile double eventZ;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || phase >= 99) return;
            phaseTicks++;
            try {
                Minecraft mc = Minecraft.func_71410_x();
                if (phase == 0) {
                    if (phaseTicks < 20) return;
                    WorldSettings settings = new WorldSettings(
                        0xAC0571CL, GameType.CREATIVE, false, false, WorldType.field_77138_c
                    );
                    System.out.println("ACOUSTIC-GAMEPLAY-SOUND-PROBE-CREATE-WORLD");
                    mc.func_71371_a("AcousticShadersGameplayProbeWorld", "Acoustic Shaders Gameplay Probe World", settings);
                    phase = 1;
                    phaseTicks = 0;
                    return;
                }
                if (phase == 1) {
                    final IntegratedServer server = mc.func_71401_C();
                    if (mc.field_71441_e == null || mc.field_71439_g == null || server == null || !server.func_71278_l()) {
                        if (phaseTicks > 600) fail("integrated world did not become ready");
                        return;
                    }
                    if (REQUIRE_CUDA_FDTD && !runtimeReadyForFullSolve()) {
                        if (phaseTicks > 1200) fail("Acoustic Shaders scene did not become ready for CUDA FDTD proof");
                        return;
                    }
                    if (phaseTicks < 20) return;
                    baseline = new HashSet<Integer>(activeSources().keySet());
                    scheduleOnServer(server, new Runnable() {
                        @Override public void run() {
                            try {
                                WorldServer world = server.func_71218_a(0);
                                require(world != null, "overworld missing on integrated server");
                                require(!world.field_73010_i.isEmpty(), "integrated server player missing");
                                require(Thread.currentThread().getName().contains("Server thread"),
                                    "gameplay action did not execute on Server thread: " + Thread.currentThread().getName());
                                EntityPlayer player = world.field_73010_i.get(0);
                                eventX = player.field_70165_t + 2.0;
                                eventY = player.field_70163_u + 0.25;
                                eventZ = player.field_70161_v - 2.0;
                                System.out.println("ACOUSTIC-GAMEPLAY-SOUND-PROBE-TNT-SPAWN xyz="
                                    + eventX + "," + eventY + "," + eventZ + " thread=" + Thread.currentThread().getName());
                                EntityTNTPrimed tnt = new EntityTNTPrimed(world, eventX, eventY, eventZ, null);
                                tnt.func_184534_a(2);
                                require(world.func_72838_d(tnt), "failed to spawn vanilla primed TNT entity");
                                serverActionComplete = true;
                            } catch (Throwable t) {
                                serverActionFailure = String.valueOf(t);
                                t.printStackTrace(System.out);
                            }
                        }
                    });
                    phase = 2;
                    phaseTicks = 0;
                    return;
                }
                if (phase == 2) {
                    if (serverActionFailure != null) fail("server gameplay action failed: " + serverActionFailure);
                    if (!serverActionComplete) {
                        if (phaseTicks > 160) fail("server gameplay action did not complete");
                        return;
                    }
                    for (Map.Entry<Integer, Object> e : activeSources().entrySet()) {
                        if (baseline.contains(e.getKey())) continue;
                        Object state = e.getValue();
                        String soundId = String.valueOf(field(state, "soundId"));
                        if (!soundId.contains("explode")) continue;
                        Object pos = field(state, "position");
                        double x = numberField(pos, "x");
                        double y = numberField(pos, "y");
                        double z = numberField(pos, "z");
                        require(distanceSq(x, y, z, eventX, eventY, eventZ) <= 4.0,
                            "TNT explosion sound too far from spawned entity: " + x + "," + y + "," + z
                                + " spawn=" + eventX + "," + eventY + "," + eventZ);
                        sourceId = e.getKey().intValue();
                        System.out.println("ACOUSTIC-GAMEPLAY-SOUND-PROBE-PLAY-OK source=" + sourceId + " sound=" + soundId);
                        phase = 3;
                        phaseTicks = 0;
                        return;
                    }
                    if (phaseTicks > 160) fail("vanilla explosion did not reach production LegacySoundHook");
                    return;
                }
                if (phase == 3) {
                    if (!activeSources().containsKey(Integer.valueOf(sourceId))) {
                        if (REQUIRE_CUDA_FDTD) {
                            phase = 4;
                            phaseTicks = 0;
                            return;
                        }
                        success();
                    } else if (phaseTicks > 240) {
                        fail("explosion sound source did not clean up id=" + sourceId);
                    }
                    return;
                }
                if (phase == 4) {
                    CudaFdtdStatus status = cudaFdtdStatus();
                    if (status.passed()) {
                        System.out.println("ACOUSTIC-REAL-CUDA-FDTD-OK backend=cuda solves=" + status.solves
                            + " failures=" + status.failures + " desc=" + status.description);
                        success();
                        return;
                    }
                    if (!status.available && (status.description.contains("unavailable") || status.description.contains("not registered"))) {
                        fail("CUDA FDTD backend unavailable: " + status);
                    }
                    if (status.failures > 0L || status.description.contains("disabled after runtime failure")) {
                        fail("CUDA FDTD backend failed: " + status);
                    }
                    if (phaseTicks > 1200) fail("CUDA FDTD backend did not execute a validated solve: " + status);
                }
            } catch (Throwable t) {
                System.out.println("ACOUSTIC-SOUND-EVENT-PROBE-FAIL " + t);
                t.printStackTrace(System.out);
                phase = 99;
            }
        }

        private void success() {
            System.out.println("ACOUSTIC-REAL-MINECRAFT-GAMEPLAY-SOUND-EVENT-OK source=" + sourceId
                + " loadedWorld=1 integratedServer=1 serverThread=1 vanillaTntEntity=1 vanillaExplosion=1 play=1 cleanup=1");
            System.out.println("ACOUSTIC-REAL-MINECRAFT-SOUND-EVENT-OK source=" + sourceId
                + " play=1 cleanup=1 world=1 gameplay=1");
            phase = 99;
        }

        private static void scheduleOnServer(IntegratedServer server, Runnable task) throws Exception {
            Method schedule = server.getClass().getMethod("func_152344_a", Runnable.class);
            schedule.invoke(server, task);
        }

        @SuppressWarnings("unchecked")
        private static Map<Integer, Object> activeSources() throws Exception {
            Object runtime = runtime();
            if (runtime == null) return new LinkedHashMap<Integer, Object>();
            Object lock = field(runtime, "sourceLock");
            synchronized (lock) {
                return new LinkedHashMap<Integer, Object>((Map<Integer, Object>)field(runtime, "activeSources"));
            }
        }

        private static Object runtime() throws Exception {
            Class<?> hook = Class.forName("dev.acoustic.mc1122.forge.LegacySoundHook");
            Field runtimeField = hook.getDeclaredField("runtime");
            runtimeField.setAccessible(true);
            return runtimeField.get(null);
        }

        private static boolean runtimeReadyForFullSolve() throws Exception {
            Object runtime = runtime();
            if (runtime == null) return false;
            Method bootstrap = runtime.getClass().getMethod("captureBootstrapActive");
            if (((Boolean)bootstrap.invoke(runtime)).booleanValue()) return false;
            Method published = runtime.getClass().getMethod("published");
            Object state = published.invoke(runtime);
            Method ready = state.getClass().getMethod("ready");
            return ((Boolean)ready.invoke(state)).booleanValue();
        }

        private static CudaFdtdStatus cudaFdtdStatus() throws Exception {
            Class<?> registry = Class.forName("dev.acoustic.core.compute.FdtdBackendRegistry");
            @SuppressWarnings("unchecked")
            List<Object> backends = (List<Object>)registry.getMethod("all").invoke(null);
            for (Object backend : backends) {
                String id = String.valueOf(backend.getClass().getMethod("id").invoke(backend));
                if (!"cuda".equalsIgnoreCase(id)) continue;
                boolean available = ((Boolean)backend.getClass().getMethod("available").invoke(backend)).booleanValue();
                long solves = ((Number)backend.getClass().getMethod("solveCount").invoke(backend)).longValue();
                long failures = ((Number)backend.getClass().getMethod("failureCount").invoke(backend)).longValue();
                String description = String.valueOf(backend.getClass().getMethod("description").invoke(backend));
                return new CudaFdtdStatus(available, solves, failures, description);
            }
            return new CudaFdtdStatus(false, 0L, 0L, "CUDA FDTD backend not registered");
        }

        private static Object field(Object target, String name) throws Exception {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field f = type.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        }

        private static double numberField(Object target, String name) throws Exception {
            return ((Number)field(target, name)).doubleValue();
        }

        private static double distanceSq(double ax, double ay, double az, double bx, double by, double bz) {
            double dx = ax - bx, dy = ay - by, dz = az - bz;
            return dx * dx + dy * dy + dz * dz;
        }
        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
        private static void fail(String message) { throw new AssertionError(message); }

        private static final class CudaFdtdStatus {
            final boolean available;
            final long solves;
            final long failures;
            final String description;
            CudaFdtdStatus(boolean available, long solves, long failures, String description) {
                this.available = available;
                this.solves = solves;
                this.failures = failures;
                this.description = description == null ? "" : description;
            }
            boolean passed() {
                return available && solves > 0L && failures == 0L && description.contains("self-test=pass");
            }
            @Override public String toString() {
                return "available=" + available + " solves=" + solves + " failures=" + failures + " desc=" + description;
            }
        }
    }
}
