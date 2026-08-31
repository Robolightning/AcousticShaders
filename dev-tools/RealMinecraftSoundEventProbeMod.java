package dev.acoustic.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;

@Mod(
    modid = "acousticshaders_probe",
    name = "Acoustic Shaders Sound Event Probe",
    version = "1",
    dependencies = "required-after:acousticshaders",
    clientSideOnly = true
)
public final class RealMinecraftSoundEventProbeMod {
    private static final ProbeListener LISTENER = new ProbeListener();

    @Mod.EventHandler
    @SuppressWarnings("deprecation")
    public void postInit(FMLPostInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(LISTENER);
        System.out.println("ACOUSTIC-SOUND-EVENT-PROBE-REGISTERED");
    }

    private static final class ProbeListener {
        private int ticks;
        private int phase;
        private int phaseTicks;
        private Object sound;
        private Object soundHandler;
        private int sourceId;
        private String sourceName;
        private Set<Integer> baseline = new HashSet<Integer>();

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || phase >= 99) return;
            ticks++;
            phaseTicks++;
            try {
                if (phase == 0) {
                    if (ticks < 20) return;
                    soundHandler = minecraftSoundHandler();
                    if (soundHandler == null) return;
                    invoke(soundHandler, "func_147689_b"); // stopSounds
                    phase = 1;
                    phaseTicks = 0;
                    return;
                }
                if (phase == 1) {
                    if (phaseTicks < 5) return;
                    baseline = activeSourceIds();
                    sound = makeSound();
                    Class<?> iSound = Class.forName("net.minecraft.client.audio.ISound");
                    invoke(soundHandler, "func_147682_a", new Class<?>[]{iSound}, new Object[]{sound});
                    System.out.println("ACOUSTIC-SOUND-EVENT-PROBE-PLAY-QUEUED baseline=" + baseline.size());
                    phase = 2;
                    phaseTicks = 0;
                    return;
                }
                if (phase == 2) {
                    ProbeSource p = resolveProbeSource();
                    if (p == null) {
                        if (phaseTicks > 100) fail("Minecraft SoundHandler did not create Paulscode source");
                        return;
                    }
                    sourceId = p.sourceId;
                    sourceName = p.sourceName;
                    Map<Integer, Object> active = activeSources();
                    Object state = active.get(Integer.valueOf(sourceId));
                    if (state == null) {
                        if (phaseTicks > 100) fail("automatic Mixin play callback did not register source id=" + sourceId);
                        return;
                    }
                    String soundId = String.valueOf(field(state, "soundId"));
                    require(soundId.contains("note/harp2"), "unexpected captured sound id: " + soundId);
                    Object initialPos = field(state, "position");
                    require(near(numberField(initialPos, "x"), 1.25) && near(numberField(initialPos, "y"), 2.5) && near(numberField(initialPos, "z"), -3.75), "initial spatial position mismatch: " + initialPos);
                    System.out.println("ACOUSTIC-SOUND-EVENT-PROBE-PLAY-OK source=" + sourceId + " name=" + sourceName + " sound=" + soundId);
                    Method setPosition = p.source.getClass().getMethod("setPosition", float.class, float.class, float.class);
                    setPosition.invoke(p.source, Float.valueOf(4.5f), Float.valueOf(-1.0f), Float.valueOf(7.25f));
                    phase = 3;
                    phaseTicks = 0;
                    return;
                }
                if (phase == 3) {
                    Object state = activeSources().get(Integer.valueOf(sourceId));
                    require(state != null, "source disappeared before move verification");
                    Object pos = field(state, "position");
                    double x = numberField(pos, "x");
                    double y = numberField(pos, "y");
                    double z = numberField(pos, "z");
                    if (near(x, 4.5) && near(y, -1.0) && near(z, 7.25)) {
                        System.out.println("ACOUSTIC-SOUND-EVENT-PROBE-MOVE-OK source=" + sourceId);
                        Class<?> iSound = Class.forName("net.minecraft.client.audio.ISound");
                        invoke(soundHandler, "func_147683_b", new Class<?>[]{iSound}, new Object[]{sound});
                        phase = 4;
                        phaseTicks = 0;
                    } else if (phaseTicks > 60) {
                        fail("automatic Mixin position callback did not update source: " + x + "," + y + "," + z);
                    }
                    return;
                }
                if (phase == 4) {
                    if (!activeSources().containsKey(Integer.valueOf(sourceId))) {
                        System.out.println("ACOUSTIC-REAL-MINECRAFT-SOUND-EVENT-OK source=" + sourceId + " play=1 move=1 cleanup=1");
                        phase = 99;
                    } else if (phaseTicks > 100) {
                        fail("automatic Mixin cleanup callback did not remove source id=" + sourceId);
                    }
                }
            } catch (Throwable t) {
                System.out.println("ACOUSTIC-SOUND-EVENT-PROBE-FAIL " + t);
                t.printStackTrace(System.out);
                phase = 99;
            }
        }

        private static Object minecraftSoundHandler() throws Exception {
            Class<?> mcType = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcType.getMethod("func_71410_x").invoke(null);
            if (mc == null) return null;
            return mcType.getMethod("func_147118_V").invoke(mc);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object makeSound() throws Exception {
            Class<?> resourceLocation = Class.forName("net.minecraft.util.ResourceLocation");
            Object location = resourceLocation.getConstructor(String.class, String.class)
                .newInstance("minecraft", "block.note.harp");
            Class<?> soundCategory = Class.forName("net.minecraft.util.SoundCategory");
            Object master = Enum.valueOf((Class<? extends Enum>)soundCategory.asSubclass(Enum.class), "BLOCKS");
            Class<?> attenuation = Class.forName("net.minecraft.client.audio.ISound$AttenuationType");
            Object none = Enum.valueOf((Class<? extends Enum>)attenuation.asSubclass(Enum.class), "LINEAR");
            Class<?> positioned = Class.forName("net.minecraft.client.audio.PositionedSoundRecord");
            Constructor<?> ctor = positioned.getConstructor(
                resourceLocation, soundCategory, float.class, float.class, boolean.class, int.class,
                attenuation, float.class, float.class, float.class
            );
            return ctor.newInstance(location, master, Float.valueOf(1.0f), Float.valueOf(1.0f), Boolean.FALSE,
                Integer.valueOf(0), none, Float.valueOf(1.25f), Float.valueOf(2.5f), Float.valueOf(-3.75f));
        }

        private ProbeSource resolveProbeSource() throws Exception {
            Object manager = field(soundHandler, "field_147694_f");
            @SuppressWarnings("unchecked")
            Map<Object, String> soundToName = (Map<Object, String>)field(manager, "field_148630_i");
            String name = soundToName.get(sound);
            if (name == null) return null;
            Object system = field(manager, "field_148620_e");
            if (system == null) return null;
            Object library = field(system, "soundLibrary");
            if (library == null) return null;
            Method getSource = library.getClass().getMethod("getSource", String.class);
            Object source = getSource.invoke(library, name);
            if (source == null) return null;
            Object channel = field(source, "channelOpenAL");
            if (channel == null) return null;
            IntBuffer alSource = (IntBuffer)field(channel, "ALSource");
            if (alSource == null || alSource.capacity() == 0) return null;
            int id = alSource.get(0);
            return id > 0 ? new ProbeSource(name, id, source) : null;
        }

        private static Set<Integer> activeSourceIds() throws Exception {
            return new HashSet<Integer>(activeSources().keySet());
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

        private static Object invoke(Object target, String name) throws Exception {
            return invoke(target, name, new Class<?>[0], new Object[0]);
        }

        private static Object invoke(Object target, String name, Class<?>[] types, Object[] args) throws Exception {
            Method m = target.getClass().getMethod(name, types);
            return m.invoke(target, args);
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

        private static boolean near(double a, double b) { return Math.abs(a - b) <= 1.0e-5; }
        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
        private static void fail(String message) { throw new AssertionError(message); }

        private static final class ProbeSource {
            final String sourceName;
            final int sourceId;
            final Object source;
            ProbeSource(String sourceName, int sourceId, Object source) {
                this.sourceName = sourceName;
                this.sourceId = sourceId;
                this.source = source;
            }
        }
    }
}
