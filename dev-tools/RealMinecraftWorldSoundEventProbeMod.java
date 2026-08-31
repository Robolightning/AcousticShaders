package dev.acoustic.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
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
    modid = "acousticshaders_world_probe",
    name = "Acoustic Shaders World Sound Event Probe",
    version = "1",
    dependencies = "required-after:acousticshaders",
    clientSideOnly = true
)
public final class RealMinecraftWorldSoundEventProbeMod {
    private static final ProbeListener LISTENER = new ProbeListener();

    @Mod.EventHandler
    @SuppressWarnings("deprecation")
    public void postInit(FMLPostInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(LISTENER);
        System.out.println("ACOUSTIC-WORLD-SOUND-PROBE-REGISTERED");
    }

    private static final class ProbeListener {
        private int phase;
        private int phaseTicks;
        private Set<Integer> baseline = new HashSet<Integer>();
        private int sourceId;
        private volatile boolean serverEmissionComplete;
        private volatile String serverEmissionFailure;
        private volatile double emitX;
        private volatile double emitY;
        private volatile double emitZ;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || phase >= 99) return;
            phaseTicks++;
            try {
                Minecraft mc = Minecraft.func_71410_x();
                if (phase == 0) {
                    if (phaseTicks < 20) return;
                    WorldSettings settings = new WorldSettings(
                        0xAC0571CL,
                        GameType.CREATIVE,
                        false,
                        false,
                        WorldType.field_77138_c
                    );
                    System.out.println("ACOUSTIC-WORLD-SOUND-PROBE-CREATE-WORLD");
                    mc.func_71371_a("AcousticShadersProbeWorld", "Acoustic Shaders Probe World", settings);
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
                    if (phaseTicks < 20) return;
                    baseline = new HashSet<Integer>(activeSources().keySet());
                    scheduleOnServer(server, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                WorldServer world = server.func_71218_a(0);
                                require(world != null, "overworld missing on integrated server");
                                require(!world.field_73010_i.isEmpty(), "integrated server player missing");
                                require(Thread.currentThread().getName().contains("Server thread"),
                                    "world sound did not execute on Server thread: " + Thread.currentThread().getName());
                                EntityPlayer player = world.field_73010_i.get(0);
                                emitX = player.field_70165_t + 1.25;
                                emitY = player.field_70163_u + 0.5;
                                emitZ = player.field_70161_v - 1.75;
                                SoundEvent sound = SoundEvent.field_187505_a.func_82594_a(
                                    new ResourceLocation("minecraft", "block.note.harp")
                                );
                                require(sound != null, "block.note.harp SoundEvent missing");
                                System.out.println("ACOUSTIC-WORLD-SOUND-PROBE-SERVER-PLAYER xyz="
                                    + player.field_70165_t + "," + player.field_70163_u + "," + player.field_70161_v
                                    + " thread=" + Thread.currentThread().getName());
                                world.func_184148_a(null, emitX, emitY, emitZ, sound, SoundCategory.BLOCKS, 1.0f, 1.0f);
                                System.out.println("ACOUSTIC-WORLD-SOUND-PROBE-EMIT baseline=" + baseline.size()
                                    + " xyz=" + emitX + "," + emitY + "," + emitZ);
                                serverEmissionComplete = true;
                            } catch (Throwable t) {
                                serverEmissionFailure = String.valueOf(t);
                                t.printStackTrace(System.out);
                            }
                        }
                    });
                    phase = 2;
                    phaseTicks = 0;
                    return;
                }
                if (phase == 2) {
                    if (serverEmissionFailure != null) fail("server emission failed: " + serverEmissionFailure);
                    if (!serverEmissionComplete) {
                        if (phaseTicks > 120) fail("server-thread world sound emission did not complete");
                        return;
                    }
                    for (Map.Entry<Integer, Object> e : activeSources().entrySet()) {
                        if (baseline.contains(e.getKey())) continue;
                        Object state = e.getValue();
                        String soundId = String.valueOf(field(state, "soundId"));
                        if (!soundId.contains("note/harp")) continue;
                        Object pos = field(state, "position");
                        double x = numberField(pos, "x");
                        double y = numberField(pos, "y");
                        double z = numberField(pos, "z");
                        require(near(x, emitX) && near(y, emitY) && near(z, emitZ),
                            "world sound spatial position mismatch: " + x + "," + y + "," + z
                                + " expected=" + emitX + "," + emitY + "," + emitZ);
                        sourceId = e.getKey().intValue();
                        System.out.println("ACOUSTIC-WORLD-SOUND-PROBE-PLAY-OK source=" + sourceId + " sound=" + soundId);
                        phase = 3;
                        phaseTicks = 0;
                        return;
                    }
                    if (phaseTicks > 120) fail("world/server sound did not reach production LegacySoundHook");
                    return;
                }
                if (phase == 3) {
                    if (!activeSources().containsKey(Integer.valueOf(sourceId))) {
                        System.out.println("ACOUSTIC-REAL-MINECRAFT-WORLD-SOUND-EVENT-OK source=" + sourceId
                            + " loadedWorld=1 integratedServer=1 serverThread=1 play=1 cleanup=1");
                        System.out.println("ACOUSTIC-REAL-MINECRAFT-SOUND-EVENT-OK source=" + sourceId
                            + " play=1 cleanup=1 world=1");
                        phase = 99;
                    } else if (phaseTicks > 200) {
                        fail("world sound source did not clean up id=" + sourceId);
                    }
                }
            } catch (Throwable t) {
                System.out.println("ACOUSTIC-SOUND-EVENT-PROBE-FAIL " + t);
                t.printStackTrace(System.out);
                phase = 99;
            }
        }

        private static void scheduleOnServer(IntegratedServer server, Runnable task) throws Exception {
            Method schedule = server.getClass().getMethod("func_152344_a", Runnable.class);
            schedule.invoke(server, task);
        }

        @SuppressWarnings("unchecked")
        private static Map<Integer, Object> activeSources() throws Exception {
            Class<?> hook = Class.forName("dev.acoustic.mc1122.forge.LegacySoundHook");
            Field runtimeField = hook.getDeclaredField("runtime");
            runtimeField.setAccessible(true);
            Object runtime = runtimeField.get(null);
            if (runtime == null) return new LinkedHashMap<Integer, Object>();
            Object lock = field(runtime, "sourceLock");
            synchronized (lock) {
                return new LinkedHashMap<Integer, Object>((Map<Integer, Object>)field(runtime, "activeSources"));
            }
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

        private static boolean near(double a, double b) {
            return Math.abs(a - b) <= 1.0e-4;
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }

        private static void fail(String message) {
            throw new AssertionError(message);
        }
    }
}
