package dev.acoustic.mc1122.forge;

import dev.acoustic.api.math.Vec3;

/** Core OpenAL Doppler velocity must not depend on the optional ALC_EXT_EFX extension. */
public final class DopplerWithoutEfxSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        org.lwjgl.openal.ALC10.EFX_PRESENT = false;
        org.lwjgl.openal.AL10.VELOCITIES.clear();
        org.lwjgl.openal.EFX10.FILTERS.clear();
        try {
            LegacyEfxBackend backend = new LegacyEfxBackend();
            final int source = 611;
            backend.applyVelocity(source, new Vec3(12.0, -4.0, 2.0), 0.5f);
            float[] velocity = org.lwjgl.openal.AL10.VELOCITIES.get(source);
            check(velocity != null, "AL_VELOCITY was skipped when ALC_EXT_EFX was absent");
            check(velocity[0] == 6.0f && velocity[1] == -2.0f && velocity[2] == 1.0f,
                "unexpected scaled Doppler velocity without EFX");

            backend.applyDirectOnly(source, new dev.acoustic.core.passes.LegacyEffectParameters(0.4f, 0.3f, 0.2f, 0.1f));
            check(org.lwjgl.openal.EFX10.FILTERS.isEmpty(), "EFX filters were created although ALC_EXT_EFX is absent");

            backend.clearSource(source);
            velocity = org.lwjgl.openal.AL10.VELOCITIES.get(source);
            check(velocity != null && velocity[0] == 0.0f && velocity[1] == 0.0f && velocity[2] == 0.0f,
                "Doppler velocity was not reset without EFX");
            System.out.println("PASS: projectile/core OpenAL Doppler velocity remains available and cleanable without ALC_EXT_EFX");
        } finally {
            org.lwjgl.openal.ALC10.EFX_PRESENT = true;
        }
    }
}
