package dev.acoustic.mc1122.forge;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.World;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.registries.IForgeRegistry;

/** Regression for disabling effects while a synthetic projectile flight sound is active. */
public final class EffectsDisableProjectileSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/effects-disable-projectile-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);

        Minecraft mc = Minecraft.getMinecraft();
        mc.soundHandler.clear();
        SoundEvent.REGISTRY.clear();
        World world = new World();
        Entity listener = new Entity(0, 1, 0);
        mc.field_71441_e = world;
        mc.field_71439_g = listener;

        IForgeRegistry<SoundEvent> registry = new IForgeRegistry<SoundEvent>() {
            @Override public void register(SoundEvent sound) { SoundEvent.REGISTRY.register(sound); }
        };
        LegacySoundEvents.registerSounds(new RegistryEvent.Register<SoundEvent>(registry));

        Path forgeConfig = root.resolve("config");
        Files.createDirectories(forgeConfig);
        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(forgeConfig.toFile()));
        TickEvent.ClientTickEvent tick = new TickEvent.ClientTickEvent(TickEvent.Phase.END);

        EntityTippedArrow arrow = new EntityTippedArrow(2, 1, 0);
        arrow.field_70159_w = 1.0;
        world.loadedEntityList.add(arrow);
        mod.clientTick(tick);
        check(mc.soundHandler.played.size() == 1, "flight sound was not created before disable");
        ISound raw = mc.soundHandler.played.get(0);
        check(raw instanceof MovingSound, "flight sound is not MovingSound");
        MovingSound flight = (MovingSound) raw;
        check(!flight.donePlaying, "flight sound started already stopped");

        Path runtime = forgeConfig.resolve("acousticshaders/runtime.properties");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(runtime)) { properties.load(in); }
        properties.setProperty("effects.enabled", "false");
        Thread.sleep(5L);
        try (OutputStream out = Files.newOutputStream(runtime)) { properties.store(out, "disable regression"); }

        // Config polling happens every 40 client ticks. The same tick that observes the disabled
        // config must stop already-running synthetic MovingSound instances.
        for (int i = 0; i < 45; i++) mod.clientTick(tick);
        check(flight.donePlaying, "active projectile flight sound survived effects.enabled=false");
        check(!AcousticShadersForgeMod.runtime().effectsActive(), "runtime still reports effects active");

        System.out.println("PASS: effects.enabled=false stops active synthetic projectile flight MovingSound");
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
