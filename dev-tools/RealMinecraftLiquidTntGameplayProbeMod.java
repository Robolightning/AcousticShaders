package dev.acoustic.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
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
    modid = "acousticshaders_liquid_tnt_gameplay_probe",
    name = "Acoustic Shaders Liquid TNT Gameplay Probe",
    version = "1",
    dependencies = "required-after:acousticshaders",
    clientSideOnly = true
)
public final class RealMinecraftLiquidTntGameplayProbeMod {
    private static final ProbeListener LISTENER = new ProbeListener();

    @Mod.EventHandler
    @SuppressWarnings("deprecation")
    public void postInit(FMLPostInitializationEvent event) {
        System.setProperty("acousticshaders.probe.directPath", "true");
        FMLCommonHandler.instance().bus().register(LISTENER);
        System.out.println("ACOUSTIC-LIQUID-TNT-GAMEPLAY-PROBE-REGISTERED");
    }

    private enum Scenario {
        WATER_SOURCE_AIR_LISTENER("water-source-air-listener", "acoustic:water", "acoustic:air", 1),
        AIR_SOURCE_WATER_LISTENER("air-source-water-listener", "acoustic:air", "acoustic:water", 1),
        WATER_WATER("water-water", "acoustic:water", "acoustic:water", 0),
        AIR_WATER_AIR("air-water-air", "acoustic:air", "acoustic:air", 2),
        AIR_LAVA_AIR("air-lava-air", "acoustic:air", "acoustic:air", 2);

        final String id;
        final String sourceMedium;
        final String listenerMedium;
        final int minimumBoundaries;
        Scenario(String id, String sourceMedium, String listenerMedium, int minimumBoundaries) {
            this.id = id;
            this.sourceMedium = sourceMedium;
            this.listenerMedium = listenerMedium;
            this.minimumBoundaries = minimumBoundaries;
        }
    }

    private static final class ProbeListener {
        private int phase;
        private int phaseTicks;
        private int scenarioIndex;
        private volatile boolean serverActionComplete;
        private volatile String serverFailure;
        private volatile int baseX;
        private volatile int baseY;
        private volatile int baseZ;
        private volatile double eventX;
        private volatile double eventY;
        private volatile double eventZ;
        private Set<Integer> baseline = new HashSet<Integer>();
        private int sourceId;
        private float[] waterSpectrum;
        private float[] lavaSpectrum;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || phase >= 99) return;
            phaseTicks++;
            try {
                Minecraft mc = Minecraft.func_71410_x();
                if (phase == 0) {
                    if (phaseTicks < 20) return;
                    WorldSettings settings = new WorldSettings(
                        0xA11C0A57L, GameType.CREATIVE, false, false, WorldType.field_77138_c
                    );
                    mc.func_71371_a("AcousticShadersLiquidTntProbeWorld", "Acoustic Shaders Liquid TNT Probe World", settings);
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
                        if (phaseTicks > 1200) fail("Acoustic Shaders scene did not become ready for liquid/TNT proof");
                        return;
                    }
                    if (phaseTicks < 20) return;
                    prepareScenario(server, Scenario.values()[scenarioIndex]);
                    phase = 2;
                    phaseTicks = 0;
                    return;
                }
                if (phase == 2) {
                    if (serverFailure != null) fail("server scenario setup failed: " + serverFailure);
                    if (!serverActionComplete) {
                        if (phaseTicks > 240) fail("server scenario setup did not complete");
                        return;
                    }
                    if (phaseTicks < 60) {
                        maintainScenario(mc.func_71401_C(), Scenario.values()[scenarioIndex]);
                        return;
                    }
                    final IntegratedServer server = mc.func_71401_C();
                    require(server != null, "integrated server disappeared before TNT spawn");
                    baseline = new HashSet<Integer>(activeSources().keySet());
                    serverActionComplete = false;
                    scheduleOnServer(server, new Runnable() {
                        @Override public void run() {
                            try {
                                WorldServer world = server.func_71218_a(0);
                                require(world != null, "overworld missing for TNT spawn");
                                EntityTNTPrimed tnt = new EntityTNTPrimed(world, eventX, eventY, eventZ, null);
                                tnt.func_184534_a(2);
                                require(world.func_72838_d(tnt), "failed to spawn vanilla primed TNT");
                                serverActionComplete = true;
                                System.out.println("ACOUSTIC-LIQUID-TNT-SPAWN scenario=" + Scenario.values()[scenarioIndex].id
                                    + " xyz=" + eventX + "," + eventY + "," + eventZ);
                            } catch (Throwable t) {
                                serverFailure = String.valueOf(t);
                                t.printStackTrace(System.out);
                            }
                        }
                    });
                    phase = 3;
                    phaseTicks = 0;
                    return;
                }
                if (phase == 3) {
                    if (serverFailure != null) fail("server TNT spawn failed: " + serverFailure);
                    maintainScenario(mc.func_71401_C(), Scenario.values()[scenarioIndex]);
                    for (Map.Entry<Integer,Object> e : activeSources().entrySet()) {
                        if (baseline.contains(e.getKey())) continue;
                        String soundId = String.valueOf(field(e.getValue(), "soundId"));
                        if (!soundId.contains("explode")) continue;
                        Object pos = field(e.getValue(), "position");
                        double x = numberField(pos,"x"), y = numberField(pos,"y"), z = numberField(pos,"z");
                        require(distanceSq(x,y,z,eventX,eventY,eventZ) <= 9.0,
                            "explosion source too far from TNT event for " + Scenario.values()[scenarioIndex].id);
                        sourceId = e.getKey().intValue();
                        phase = 4;
                        phaseTicks = 0;
                        return;
                    }
                    if (phaseTicks > 240) fail("vanilla TNT explosion did not reach production LegacySoundHook for " + Scenario.values()[scenarioIndex].id);
                    return;
                }
                if (phase == 4) {
                    maintainScenario(mc.func_71401_C(), Scenario.values()[scenarioIndex]);
                    Object snapshot = diagnosticSnapshot(sourceId);
                    if (snapshot != null) {
                        verifySnapshot(Scenario.values()[scenarioIndex], snapshot);
                        phase = 5;
                        phaseTicks = 0;
                        return;
                    }
                    if (phaseTicks > 900) fail("accepted production DirectPathResult did not appear for " + Scenario.values()[scenarioIndex].id);
                    return;
                }
                if (phase == 5) {
                    if (!activeSources().containsKey(Integer.valueOf(sourceId)) && diagnosticSnapshot(sourceId) == null) {
                        System.out.println("ACOUSTIC-LIQUID-TNT-SCENARIO-OK scenario=" + Scenario.values()[scenarioIndex].id);
                        scenarioIndex++;
                        if (scenarioIndex >= Scenario.values().length) {
                            require(waterSpectrum != null && lavaSpectrum != null, "cross-medium spectra missing");
                            boolean differs = false;
                            for (int i=0;i<waterSpectrum.length;i++) {
                                if (Math.abs(waterSpectrum[i]-lavaSpectrum[i]) > 1.0e-7f) { differs = true; break; }
                            }
                            require(differs, "WATER and LAVA transmission spectra collapsed to one generic liquid result");
                            System.out.println("ACOUSTIC-REAL-MINECRAFT-LIQUID-TNT-GAMEPLAY-OK"
                                + " scenarios=5 waterToAir=1 airToWater=1 waterWater=1 airWaterAir=1 airLavaAir=1 bands=8 cleanup=1");
                            phase = 99;
                            return;
                        }
                        serverActionComplete = false;
                        serverFailure = null;
                        sourceId = 0;
                        phase = 1;
                        phaseTicks = 0;
                        return;
                    }
                    if (phaseTicks > 400) fail("TNT source/diagnostic did not clean up for " + Scenario.values()[scenarioIndex].id);
                }
            } catch (Throwable t) {
                System.out.println("ACOUSTIC-LIQUID-TNT-GAMEPLAY-PROBE-FAIL " + t);
                t.printStackTrace(System.out);
                phase = 99;
            }
        }

        private void prepareScenario(final IntegratedServer server, final Scenario scenario) throws Exception {
            serverActionComplete = false;
            serverFailure = null;
            scheduleOnServer(server, new Runnable() {
                @Override public void run() {
                    try {
                        WorldServer world = server.func_71218_a(0);
                        require(world != null, "overworld missing during scenario setup");
                        require(!world.field_73010_i.isEmpty(), "integrated server player missing");
                        require(Thread.currentThread().getName().contains("Server thread"),
                            "liquid scenario setup did not execute on Server thread: " + Thread.currentThread().getName());
                        EntityPlayer player = world.field_73010_i.get(0);
                        baseX = floor(player.field_70165_t);
                        baseY = floor(player.field_70163_u);
                        baseZ = floor(player.field_70161_v);
                        eventX = baseX + 8.5;
                        eventY = baseY + 0.5;
                        eventZ = baseZ + 0.5;
                        applyLayout(world, scenario);
                        serverActionComplete = true;
                        System.out.println("ACOUSTIC-LIQUID-TNT-SETUP scenario=" + scenario.id + " base=" + baseX + "," + baseY + "," + baseZ);
                    } catch (Throwable t) {
                        serverFailure = String.valueOf(t);
                        t.printStackTrace(System.out);
                    }
                }
            });
        }

        private void maintainScenario(final IntegratedServer server, final Scenario scenario) throws Exception {
            if (server == null || phaseTicks % 5 != 0) return;
            scheduleOnServer(server, new Runnable() {
                @Override public void run() {
                    try {
                        WorldServer world = server.func_71218_a(0);
                        if (world != null) applyLayout(world, scenario);
                    } catch (Throwable t) {
                        serverFailure = String.valueOf(t);
                        t.printStackTrace(System.out);
                    }
                }
            });
        }

        private void applyLayout(WorldServer world, Scenario scenario) {
            Block air = Blocks.field_150350_a;
            for (int x=baseX-1;x<=baseX+10;x++) {
                for (int y=baseY;y<=baseY+1;y++) {
                    for (int z=baseZ;z<=baseZ;z++) setBlock(world,x,y,z,air);
                }
            }
            switch (scenario) {
                case WATER_SOURCE_AIR_LISTENER:
                    fill(world,baseX+7,baseX+9,baseY,baseY+1,baseZ,Blocks.field_150355_j);
                    break;
                case AIR_SOURCE_WATER_LISTENER:
                    fill(world,baseX-1,baseX+1,baseY,baseY+1,baseZ,Blocks.field_150355_j);
                    break;
                case WATER_WATER:
                    fill(world,baseX-1,baseX+9,baseY,baseY+1,baseZ,Blocks.field_150355_j);
                    break;
                case AIR_WATER_AIR:
                    fill(world,baseX+3,baseX+5,baseY,baseY+1,baseZ,Blocks.field_150355_j);
                    break;
                case AIR_LAVA_AIR:
                    fill(world,baseX+3,baseX+5,baseY,baseY+1,baseZ,Blocks.field_150353_l);
                    break;
                default:
                    throw new AssertionError(scenario);
            }
        }

        private static void fill(WorldServer world,int minX,int maxX,int minY,int maxY,int z,Block block) {
            for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)setBlock(world,x,y,z,block);
        }

        private static void setBlock(WorldServer world,int x,int y,int z,Block block) {
            world.func_180501_a(new BlockPos(x,y,z),block.func_176223_P(),3);
        }

        private void verifySnapshot(Scenario scenario, Object snapshot) throws Exception {
            String sourceMedium = String.valueOf(invoke(snapshot,"getSourceMediumId"));
            String listenerMedium = String.valueOf(invoke(snapshot,"getListenerMediumId"));
            double liquidMeters = ((Number)invoke(snapshot,"getLiquidMeters")).doubleValue();
            double airMeters = ((Number)invoke(snapshot,"getAirMeters")).doubleValue();
            int boundaries = ((Number)invoke(snapshot,"getMediumBoundaryCount")).intValue();
            double distance = ((Number)invoke(snapshot,"getDistanceMeters")).doubleValue();
            double delay = ((Number)invoke(snapshot,"getDelaySeconds")).doubleValue();
            float[] transmission = (float[])invoke(snapshot,"getTransmission");
            require(scenario.sourceMedium.equals(sourceMedium), scenario.id + " wrong source medium: " + sourceMedium);
            require(scenario.listenerMedium.equals(listenerMedium), scenario.id + " wrong listener medium: " + listenerMedium);
            require(boundaries >= scenario.minimumBoundaries, scenario.id + " too few medium boundaries: " + boundaries);
            require(Double.isFinite(liquidMeters) && liquidMeters > 0.05, scenario.id + " missing liquid path: " + liquidMeters);
            require(Double.isFinite(airMeters) && airMeters >= 0.0, scenario.id + " invalid air path: " + airMeters);
            require(Double.isFinite(distance) && distance > 0.0, scenario.id + " invalid distance: " + distance);
            require(Double.isFinite(delay) && delay > 0.0, scenario.id + " invalid delay: " + delay);
            require(transmission.length == 8, scenario.id + " transmission must have 8 bands");
            for(float v:transmission)require(Float.isFinite(v)&&v>=0f&&v<=1.000001f,scenario.id+" invalid transmission: "+v);
            if (scenario == Scenario.AIR_WATER_AIR) waterSpectrum = transmission.clone();
            if (scenario == Scenario.AIR_LAVA_AIR) lavaSpectrum = transmission.clone();
        }

        private static void scheduleOnServer(IntegratedServer server, Runnable task) throws Exception {
            server.getClass().getMethod("func_152344_a", Runnable.class).invoke(server, task);
        }

        private static boolean runtimeReadyForFullSolve() throws Exception {
            Object runtime = runtime();
            if (runtime == null) return false;
            if (((Boolean)runtime.getClass().getMethod("captureBootstrapActive").invoke(runtime)).booleanValue()) return false;
            Object state = runtime.getClass().getMethod("published").invoke(runtime);
            return ((Boolean)state.getClass().getMethod("ready").invoke(state)).booleanValue();
        }

        @SuppressWarnings("unchecked")
        private static Map<Integer,Object> activeSources() throws Exception {
            Object runtime = runtime();
            if (runtime == null) return new LinkedHashMap<Integer,Object>();
            Object lock = field(runtime,"sourceLock");
            synchronized(lock){return new LinkedHashMap<Integer,Object>((Map<Integer,Object>)field(runtime,"activeSources"));}
        }

        private static Object runtime() throws Exception {
            Class<?> hook=Class.forName("dev.acoustic.mc1122.forge.LegacySoundHook");
            Field f=hook.getDeclaredField("runtime");f.setAccessible(true);return f.get(null);
        }

        private static Object diagnosticSnapshot(int id) throws Exception {
            Class<?> diagnostic=Class.forName("dev.acoustic.mc1122.forge.LegacyDirectPathDiagnostic");
            return diagnostic.getMethod("snapshot",int.class).invoke(null,Integer.valueOf(id));
        }

        private static Object invoke(Object target,String method) throws Exception {return target.getClass().getMethod(method).invoke(target);}
        private static Object field(Object target,String name) throws Exception {Class<?> t=target.getClass();while(t!=null){try{Field f=t.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException ignored){t=t.getSuperclass();}}throw new NoSuchFieldException(name);}
        private static double numberField(Object target,String name) throws Exception {return ((Number)field(target,name)).doubleValue();}
        private static double distanceSq(double ax,double ay,double az,double bx,double by,double bz){double dx=ax-bx,dy=ay-by,dz=az-bz;return dx*dx+dy*dy+dz*dz;}
        private static int floor(double v){int i=(int)v;return v<i?i-1:i;}
        private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
        private static void fail(String message){throw new AssertionError(message);}
    }
}
