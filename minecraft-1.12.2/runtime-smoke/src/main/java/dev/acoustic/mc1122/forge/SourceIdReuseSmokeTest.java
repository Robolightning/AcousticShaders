package dev.acoustic.mc1122.forge;

import dev.acoustic.api.math.Vec3;
import dev.acoustic.core.dsp.SoftwareWetPcmRenderer;
import dev.acoustic.core.passes.LegacyEffectParameters;
import dev.acoustic.core.passes.LegacyRoomEstimate;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * Paulscode/OpenAL recycle numeric source ids. A new logical sound must not inherit
 * AcousticShaders state when an old stop/cleanup callback was missed or reordered.
 * Cleanup must also leave a velocity alone after another owner changed it.
 */
public final class SourceIdReuseSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out/source-id-reuse-smoke").toAbsolutePath();
        delete(root);
        Files.createDirectories(root);
        AcousticShadersForgeMod mod = new AcousticShadersForgeMod();
        mod.preInit(new TestPre(root.resolve("config").toFile()));

        LegacyEfxBackend efx = (LegacyEfxBackend) privateField(LegacySoundHook.class, "efx");
        LegacySoftwareWetBackend wet = (LegacySoftwareWetBackend) privateField(LegacySoundHook.class, "wet");
        wet.reconfigure(new LegacyAudioConfig(true, 1, 2, 64, 0.1, 0.5f, 4, 65536));
        SoftwareWetPcmRenderer.Rendered rendered = new SoftwareWetPcmRenderer.Rendered(new byte[64], 8000, 16);
        LegacyEffectParameters effect = new LegacyEffectParameters(0.55f, 0.35f, 0.25f, 0.15f);
        LegacyRoomEstimate room = new LegacyRoomEstimate(4.0, 0.1f, 0.8f, 0.8f, 0.8f, 0.8f);

        // Simulate a missed old cleanup. The next logical source receives the same AL id.
        final int reused = 451;
        final long oldGeneration = 77L;
        org.lwjgl.openal.AL10.STATES.put(reused, org.lwjgl.openal.AL10.AL_PLAYING);
        efx.apply(reused, effect, room, 1L);
        efx.applyVelocity(reused, new Vec3(6.0, -2.0, 1.0), 1.0f);
        check(wet.apply(reused, oldGeneration, rendered).getApplied(), "old wet voice was not seeded");
        check(wet.isActive(reused, oldGeneration), "old wet voice missing before source-id reuse");
        check(value(org.lwjgl.openal.AL10.DIRECT_FILTERS, reused) != 0, "old direct filter missing before source-id reuse");
        check(auxFilter(reused) != 0, "old auxiliary filter missing before source-id reuse");
        checkVelocity(reused, 6f, -2f, 1f, "old AcousticShaders velocity missing before source-id reuse");

        LegacySoundHook.onSourcePlay(new FakeSource(reused, "minecraft/sounds/random/reused.ogg"));
        check(!wet.isActive(reused, oldGeneration), "recycled source inherited old software-wet voice");
        check(value(org.lwjgl.openal.AL10.DIRECT_FILTERS, reused) == 0, "recycled source inherited old direct filter");
        check(auxFilter(reused) == 0, "recycled source inherited old auxiliary send filter");
        checkVelocity(reused, 0f, 0f, 0f, "recycled source inherited old AcousticShaders Doppler velocity");

        // If another owner changes velocity after AcousticShaders wrote it, stale cleanup
        // must relinquish ownership instead of blindly zeroing the new value.
        final int thirdParty = 452;
        org.lwjgl.openal.AL10.STATES.put(thirdParty, org.lwjgl.openal.AL10.AL_PLAYING);
        efx.applyVelocity(thirdParty, new Vec3(3.0, 4.0, 5.0), 1.0f);
        org.lwjgl.openal.AL10.alSource3f(thirdParty, org.lwjgl.openal.AL10.AL_VELOCITY, 9f, 8f, 7f);
        LegacySoundHook.onSourcePlay(new FakeSource(thirdParty, "minecraft/sounds/random/third_party_velocity.ogg"));
        checkVelocity(thirdParty, 9f, 8f, 7f, "source-id reuse clobbered third-party-owned AL velocity");

        // The ownership rule must also apply on writes, not only cleanup. A source that
        // already has third-party non-zero velocity is not ours to claim.
        final int preownedVelocity = 456;
        org.lwjgl.openal.AL10.alSource3f(preownedVelocity, org.lwjgl.openal.AL10.AL_VELOCITY, 13f, -6f, 2f);
        efx.applyVelocity(preownedVelocity, new Vec3(1.0, 2.0, 3.0), 1.0f);
        checkVelocity(preownedVelocity, 13f, -6f, 2f,
            "AcousticShaders overwrote pre-existing third-party AL velocity on first claim");

        // If another owner replaces velocity between two AcousticShaders updates, the
        // second update must relinquish ownership rather than overwriting the replacement.
        final int replacedVelocity = 457;
        efx.applyVelocity(replacedVelocity, new Vec3(2.0, 3.0, 4.0), 1.0f);
        checkVelocity(replacedVelocity, 2f, 3f, 4f, "AcousticShaders failed to claim an initially-zero AL velocity");
        org.lwjgl.openal.AL10.alSource3f(replacedVelocity, org.lwjgl.openal.AL10.AL_VELOCITY, -9f, -8f, -7f);
        efx.applyVelocity(replacedVelocity, new Vec3(5.0, 6.0, 7.0), 1.0f);
        checkVelocity(replacedVelocity, -9f, -8f, -7f,
            "AcousticShaders overwrote third-party velocity during an owned-source update");
        // Bookkeeping must have been released: zero-Doppler cleanup must leave the
        // third-party replacement untouched too.
        efx.applyVelocity(replacedVelocity, new Vec3(1.0, 1.0, 1.0), 0.0f);
        checkVelocity(replacedVelocity, -9f, -8f, -7f,
            "velocity ownership was not relinquished after third-party replacement");

        // EFX ownership needs the same protection.  Seed AcousticShaders filters, then
        // simulate another mod replacing the new logical source's direct+aux state before
        // our play-tail callback.  The stale AcousticShaders bookkeeping must be retired
        // without writing AL_FILTER_NULL over that newer owner.
        final int thirdPartyEfx = 454;
        org.lwjgl.openal.AL10.STATES.put(thirdPartyEfx, org.lwjgl.openal.AL10.AL_PLAYING);
        efx.apply(thirdPartyEfx, effect, room, 2L);
        check(value(org.lwjgl.openal.AL10.DIRECT_FILTERS, thirdPartyEfx) != 0, "AcousticShaders EFX direct filter was not seeded");
        org.lwjgl.openal.AL10.alSourcei(thirdPartyEfx, org.lwjgl.openal.EFX10.AL_DIRECT_FILTER, 777);
        org.lwjgl.openal.AL11.alSource3i(thirdPartyEfx, org.lwjgl.openal.EFX10.AL_AUXILIARY_SEND_FILTER, 888, 0, 999);
        LegacySoundHook.onSourcePlay(new FakeSource(thirdPartyEfx, "minecraft/sounds/random/third_party_efx.ogg"));
        check(value(org.lwjgl.openal.AL10.DIRECT_FILTERS, thirdPartyEfx) == 777, "source-id reuse clobbered third-party direct EFX filter");
        int[] thirdPartyAux = org.lwjgl.openal.AL11.AUX_SENDS.get(Integer.valueOf(thirdPartyEfx));
        check(thirdPartyAux != null && thirdPartyAux[0] == 888 && thirdPartyAux[2] == 999, "source-id reuse clobbered third-party auxiliary EFX send");

        // A zero-Doppler source that AcousticShaders never owned must likewise not zero
        // a velocity supplied by Minecraft/another mod.
        final int zeroDoppler = 453;
        org.lwjgl.openal.AL10.alSource3f(zeroDoppler, org.lwjgl.openal.AL10.AL_VELOCITY, -4f, 2f, 11f);
        efx.applyVelocity(zeroDoppler, new Vec3(100.0, 100.0, 100.0), 0.0f);
        checkVelocity(zeroDoppler, -4f, 2f, 11f, "zero-Doppler profile overwrote unowned AL velocity");

        // An untracked source that already carries third-party EFX must not be claimed by
        // a later AcousticShaders update. OpenAL has only one direct filter slot, so the
        // safe compatibility policy is fail-closed rather than overwriting that owner.
        final int preownedEfx = 455;
        org.lwjgl.openal.AL10.alSourcei(preownedEfx, org.lwjgl.openal.EFX10.AL_DIRECT_FILTER, 1777);
        org.lwjgl.openal.AL11.alSource3i(preownedEfx, org.lwjgl.openal.EFX10.AL_AUXILIARY_SEND_FILTER, 1888, 0, 1999);
        int filtersBeforePreownedApply = org.lwjgl.openal.EFX10.FILTERS.size();
        efx.apply(preownedEfx, effect, room, 3L);
        check(value(org.lwjgl.openal.AL10.DIRECT_FILTERS, preownedEfx) == 1777,
            "AcousticShaders overwrote a pre-existing third-party direct EFX filter");
        int[] preownedAux = org.lwjgl.openal.AL11.AUX_SENDS.get(Integer.valueOf(preownedEfx));
        check(preownedAux != null && preownedAux[0] == 1888 && preownedAux[2] == 1999,
            "AcousticShaders overwrote a pre-existing third-party auxiliary EFX send");
        check(org.lwjgl.openal.EFX10.FILTERS.size() == filtersBeforePreownedApply,
            "pre-owned EFX source allocated AcousticShaders filters despite fail-closed ownership");

        // Stress distinct ownership conflicts. Each conflict may leave the old AS filters
        // alive until context teardown because LWJGL2 cannot reliably query the aux-send
        // triple. Native resource growth must nevertheless be bounded: after 128 orphan
        // filter objects the backend suspends new EFX allocations for this context.
        boolean suspended = false;
        for (int i = 0; i < 256; i++) {
            int source = 1000 + i;
            efx.apply(source, effect, room, 10L + i);
            int ours = value(org.lwjgl.openal.AL10.DIRECT_FILTERS, source);
            if (ours == 0) {
                suspended = true;
                break;
            }
            org.lwjgl.openal.AL10.alSourcei(source, org.lwjgl.openal.EFX10.AL_DIRECT_FILTER, 20000 + i);
            org.lwjgl.openal.AL11.alSource3i(source, org.lwjgl.openal.EFX10.AL_AUXILIARY_SEND_FILTER, 30000 + i, 0, 40000 + i);
            LegacySoundHook.onSourcePlay(new FakeSource(source, "minecraft/sounds/random/conflict_" + i + ".ogg"));
        }
        check(suspended, "repeated EFX ownership conflicts never reached bounded fail-closed suspension");
        check(org.lwjgl.openal.EFX10.FILTERS.size() <= 128,
            "orphan EFX filter growth exceeded the per-context safety budget: " + org.lwjgl.openal.EFX10.FILTERS.size());
        int boundedCount = org.lwjgl.openal.EFX10.FILTERS.size();
        for (int i = 0; i < 32; i++) efx.apply(5000 + i, effect, room, 1000L + i);
        check(org.lwjgl.openal.EFX10.FILTERS.size() == boundedCount,
            "EFX allocations continued after ownership-conflict budget suspension");

        // A new OpenAL context is an explicit ownership/resource boundary. Real context
        // destruction releases native filter objects; model that release and verify that
        // the backend clears orphan pressure and can initialize EFX again.
        org.lwjgl.openal.EFX10.FILTERS.clear();
        org.lwjgl.openal.AL10.DIRECT_FILTERS.clear();
        org.lwjgl.openal.AL11.AUX_SENDS.clear();
        org.lwjgl.openal.ALC10.CURRENT = new org.lwjgl.openal.ALCcontext();
        final int afterContextReset = 9000;
        efx.apply(afterContextReset, effect, room, 2000L);
        check(value(org.lwjgl.openal.AL10.DIRECT_FILTERS, afterContextReset) != 0,
            "new OpenAL context did not reset EFX ownership-conflict suspension");
        check(org.lwjgl.openal.EFX10.FILTERS.size() == 2,
            "new OpenAL context did not restart with a clean filter set");

        System.out.println("PASS: recycled OpenAL source ids preserve third-party state, bound orphan EFX pressure, and reset ownership on context replacement");
    }

    private static int value(java.util.Map<Integer,Integer> map, int key) {
        Integer value = map.get(Integer.valueOf(key));
        return value == null ? 0 : value.intValue();
    }

    private static int auxFilter(int source) {
        int[] values = org.lwjgl.openal.AL11.AUX_SENDS.get(Integer.valueOf(source));
        return values == null ? 0 : values[2];
    }

    private static void checkVelocity(int source, float x, float y, float z, String message) {
        float[] value = org.lwjgl.openal.AL10.VELOCITIES.get(Integer.valueOf(source));
        check(value != null && value[0] == x && value[1] == y && value[2] == z,
            message + " actual=" + (value == null ? "null" : value[0] + "," + value[1] + "," + value[2]));
    }

    private static Object privateField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(Modifier.isStatic(field.getModifiers()) ? null : LegacySoundHook.INSTANCE);
    }

    private static final class FakeSource {
        public final Channel channelOpenAL;
        public final Position position = new Position(0f, 0f, 0f);
        public final float gain = 1f;
        public final float sourceVolume = 1f;
        public final boolean priority = false;
        public final boolean toStream = false;
        public final boolean toLoop = false;
        public final String sourcename;
        FakeSource(int sourceId, String name) { this.channelOpenAL = new Channel(sourceId); this.sourcename = name; }
    }
    private static final class Channel {
        public final IntBuffer ALSource = IntBuffer.allocate(1);
        Channel(int id) { ALSource.put(0, id); }
    }
    private static final class Position {
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
