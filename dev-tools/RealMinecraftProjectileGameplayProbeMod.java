package dev.acoustic.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityTippedArrow;
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
    modid = "acousticshaders_projectile_gameplay_probe",
    name = "Acoustic Shaders Projectile Gameplay Probe",
    version = "1",
    dependencies = "required-after:acousticshaders",
    clientSideOnly = true
)
public final class RealMinecraftProjectileGameplayProbeMod {
    private static final ProbeListener LISTENER = new ProbeListener();

    @Mod.EventHandler
    @SuppressWarnings("deprecation")
    public void postInit(FMLPostInitializationEvent event) {
        System.setProperty("acousticshaders.probe.directPath", "true");
        FMLCommonHandler.instance().bus().register(LISTENER);
        System.out.println("ACOUSTIC-PROJECTILE-GAMEPLAY-PROBE-REGISTERED");
    }

    private static final class ProbeListener {
        private int phase;
        private int phaseTicks;
        private volatile boolean serverSpawnComplete;
        private volatile boolean serverRemoveComplete;
        private volatile String serverFailure;
        private volatile EntityTippedArrow serverArrow;
        private int sourceId;
        private double firstX;
        private double firstY;
        private double firstZ;
        private boolean moved;
        private boolean directObserved;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || phase >= 99) return;
            phaseTicks++;
            try {
                Minecraft mc = Minecraft.func_71410_x();
                if (phase == 0) {
                    if (phaseTicks < 20) return;
                    WorldSettings settings = new WorldSettings(
                        0xA220BEEFL, GameType.CREATIVE, false, false, WorldType.field_77138_c
                    );
                    mc.func_71371_a("AcousticShadersProjectileProbeWorld", "Acoustic Shaders Projectile Probe World", settings);
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
                    if (!runtimeReadyForFullSolve()) {
                        if (phaseTicks > 1200) fail("Acoustic Shaders scene did not become ready for projectile proof");
                        return;
                    }
                    if (phaseTicks < 20) return;
                    scheduleOnServer(server, new Runnable() {
                        @Override public void run() {
                            try {
                                WorldServer world = server.func_71218_a(0);
                                require(world != null, "overworld missing on integrated server");
                                require(!world.field_73010_i.isEmpty(), "integrated server player missing");
                                require(Thread.currentThread().getName().contains("Server thread"),
                                    "projectile spawn did not execute on Server thread: " + Thread.currentThread().getName());
                                EntityPlayer player = world.field_73010_i.get(0);
                                EntityTippedArrow arrow = new EntityTippedArrow(
                                    world,
                                    player.field_70165_t + 2.0,
                                    player.field_70163_u + 4.0,
                                    player.field_70161_v - 2.0
                                );
                                arrow.field_70159_w = 0.55;
                                arrow.field_70181_x = 0.12;
                                arrow.field_70179_y = 0.08;
                                require(world.func_72838_d(arrow), "failed to spawn vanilla EntityTippedArrow");
                                serverArrow = arrow;
                                serverSpawnComplete = true;
                                System.out.println("ACOUSTIC-PROJECTILE-GAMEPLAY-PROBE-ARROW-SPAWN entity=EntityTippedArrow");
                            } catch (Throwable t) {
                                serverFailure = String.valueOf(t);
                                t.printStackTrace(System.out);
                            }
                        }
                    });
                    phase = 2;
                    phaseTicks = 0;
                    return;
                }
                if (phase == 2) {
                    if (serverFailure != null) fail("server projectile spawn failed: " + serverFailure);
                    if (!serverSpawnComplete) {
                        if (phaseTicks > 200) fail("server projectile spawn did not complete");
                        return;
                    }
                    Map<Integer,Object> sources = activeSources();
                    for (Map.Entry<Integer,Object> e : sources.entrySet()) {
                        Object state = e.getValue();
                        String soundId = String.valueOf(field(state, "soundId"));
                        if (!soundId.contains("projectile.flight")) continue;
                        Object pos = field(state, "position");
                        sourceId = e.getKey().intValue();
                        firstX = numberField(pos, "x");
                        firstY = numberField(pos, "y");
                        firstZ = numberField(pos, "z");
                        System.out.println("ACOUSTIC-PROJECTILE-GAMEPLAY-PROBE-SOURCE source=" + sourceId + " sound=" + soundId);
                        phase = 3;
                        phaseTicks = 0;
                        return;
                    }
                    if (phaseTicks > 320) fail("production projectile.flight source did not appear");
                    return;
                }
                if (phase == 3) {
                    Object state = activeSources().get(Integer.valueOf(sourceId));
                    if (state == null) fail("projectile source vanished before movement/direct-path proof");
                    Object pos = field(state, "position");
                    double x = numberField(pos, "x"), y = numberField(pos, "y"), z = numberField(pos, "z");
                    if (distanceSq(x,y,z,firstX,firstY,firstZ) > 0.04) moved = true;
                    Object diagnostic = diagnosticSnapshot(sourceId);
                    if (diagnostic != null) {
                        double distance = ((Number)invoke(diagnostic, "getDistanceMeters")).doubleValue();
                        double delay = ((Number)invoke(diagnostic, "getDelaySeconds")).doubleValue();
                        float[] transmission = (float[])invoke(diagnostic, "getTransmission");
                        require(Double.isFinite(distance) && distance >= 0.0, "projectile direct distance invalid: " + distance);
                        require(Double.isFinite(delay) && delay >= 0.0, "projectile direct delay invalid: " + delay);
                        require(transmission.length == 8, "projectile direct transmission must have 8 bands");
                        for (float v : transmission) require(Float.isFinite(v) && v >= 0f && v <= 1.000001f,
                            "projectile direct transmission invalid: " + v);
                        directObserved = true;
                    }
                    if (moved && directObserved) {
                        final IntegratedServer server = mc.func_71401_C();
                        require(server != null, "integrated server vanished before projectile cleanup");
                        scheduleOnServer(server, new Runnable() {
                            @Override public void run() {
                                try {
                                    EntityTippedArrow arrow = serverArrow;
                                    require(arrow != null, "server arrow reference missing");
                                    arrow.func_70106_y();
                                    serverRemoveComplete = true;
                                    System.out.println("ACOUSTIC-PROJECTILE-GAMEPLAY-PROBE-ARROW-REMOVE");
                                } catch (Throwable t) {
                                    serverFailure = String.valueOf(t);
                                    t.printStackTrace(System.out);
                                }
                            }
                        });
                        phase = 4;
                        phaseTicks = 0;
                        return;
                    }
                    if (phaseTicks > 800) fail("projectile source never produced both movement and accepted DirectPathResult");
                    return;
                }
                if (phase == 4) {
                    if (serverFailure != null) fail("server projectile cleanup failed: " + serverFailure);
                    if (!serverRemoveComplete) {
                        if (phaseTicks > 200) fail("server projectile cleanup did not complete");
                        return;
                    }
                    if (!activeSources().containsKey(Integer.valueOf(sourceId)) && diagnosticSnapshot(sourceId) == null) {
                        System.out.println("ACOUSTIC-REAL-MINECRAFT-PROJECTILE-GAMEPLAY-OK"
                            + " vanillaEntityTippedArrow=1 flightSource=1 movement=1 directPath=1 bands=8 cleanup=1 diagnosticCleanup=1");
                        phase = 99;
                        return;
                    }
                    if (phaseTicks > 400) fail("projectile source/diagnostic did not clean up after vanilla entity removal");
                }
            } catch (Throwable t) {
                System.out.println("ACOUSTIC-PROJECTILE-GAMEPLAY-PROBE-FAIL " + t);
                t.printStackTrace(System.out);
                phase = 99;
            }
        }

        private static void scheduleOnServer(IntegratedServer server, Runnable task) throws Exception {
            Method schedule = server.getClass().getMethod("func_152344_a", Runnable.class);
            schedule.invoke(server, task);
        }

        private static boolean runtimeReadyForFullSolve() throws Exception {
            Object runtime = runtime();
            if (runtime == null) return false;
            Method bootstrap = runtime.getClass().getMethod("captureBootstrapActive");
            if (((Boolean)bootstrap.invoke(runtime)).booleanValue()) return false;
            Object state = runtime.getClass().getMethod("published").invoke(runtime);
            return ((Boolean)state.getClass().getMethod("ready").invoke(state)).booleanValue();
        }

        @SuppressWarnings("unchecked")
        private static Map<Integer,Object> activeSources() throws Exception {
            Object runtime = runtime();
            if (runtime == null) return new LinkedHashMap<Integer,Object>();
            Object lock = field(runtime, "sourceLock");
            synchronized (lock) {
                return new LinkedHashMap<Integer,Object>((Map<Integer,Object>)field(runtime, "activeSources"));
            }
        }

        private static Object runtime() throws Exception {
            Class<?> hook = Class.forName("dev.acoustic.mc1122.forge.LegacySoundHook");
            Field runtimeField = hook.getDeclaredField("runtime");
            runtimeField.setAccessible(true);
            return runtimeField.get(null);
        }

        private static Object diagnosticSnapshot(int id) throws Exception {
            Class<?> diagnostic = Class.forName("dev.acoustic.mc1122.forge.LegacyDirectPathDiagnostic");
            return diagnostic.getMethod("snapshot", int.class).invoke(null, Integer.valueOf(id));
        }

        private static Object invoke(Object target, String method) throws Exception {
            return target.getClass().getMethod(method).invoke(target);
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

        private static double distanceSq(double ax,double ay,double az,double bx,double by,double bz) {
            double dx=ax-bx,dy=ay-by,dz=az-bz;
            return dx*dx+dy*dy+dz*dz;
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
        private static void fail(String message) { throw new AssertionError(message); }
    }
}
