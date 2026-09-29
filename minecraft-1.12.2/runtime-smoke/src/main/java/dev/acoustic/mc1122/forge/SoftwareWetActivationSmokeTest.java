package dev.acoustic.mc1122.forge;

import dev.acoustic.core.dsp.SoftwareWetPcmRenderer;
import java.util.List;
import java.util.Map;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

/** Regression for late async wet rejection and smooth EFX->software-wet handoff. */
public final class SoftwareWetActivationSmokeTest {
    private SoftwareWetActivationSmokeTest() {}

    public static void main(String[] args) {
        ALC10.CURRENT = new org.lwjgl.openal.ALCcontext();
        LegacyAudioConfig config = new LegacyAudioConfig(true, 1, 4, 64, 0.1, 0.5f, 4, 65536);
        LegacySoftwareWetBackend wet = new LegacySoftwareWetBackend(config);
        SoftwareWetPcmRenderer.Rendered rendered = new SoftwareWetPcmRenderer.Rendered(new byte[256], 8000, 64);

        int earlyDry = 501;
        AL10.STATES.put(earlyDry, AL10.AL_PLAYING);
        AL10.SOURCE_OFFSETS.put(earlyDry, 0.05f);
        int playsBefore = AL10.sourcePlayCalls;
        LegacySoftwareWetBackend.ApplyResult early = wet.apply(earlyDry, 11L, rendered);
        check(early.getApplied(), "early wet result was not applied");
        check(!early.getLateFallback(), "early wet result was marked late");
        check(AL10.sourcePlayCalls == playsBefore + 1, "wet source did not start");
        check(!wet.directOnlyReady(earlyDry, 11L), "EFX send detached before wet fade completed");

        int wetSource = newestGainSource();
        check(wetSource > 0 && wetSource != earlyDry, "wet source gain state missing");
        check(Math.abs(AL10.GAINS.get(wetSource)) < 1.0e-6f, "wet source did not start at zero gain");

        List<LegacySoftwareWetBackend.Transition> transitioned = wet.advanceTransitions(Long.MAX_VALUE);
        check(transitioned.size() == 1, "wet fade did not produce one transition: " + transitioned.size());
        check(transitioned.get(0).getDrySource() == earlyDry && transitioned.get(0).getGeneration() == 11L,
            "wet transition identity mismatch");
        check(wet.directOnlyReady(earlyDry, 11L), "wet voice did not become direct-only ready");
        check(Math.abs(AL10.GAINS.get(wetSource) - 1.0f) < 1.0e-6f, "wet source did not fade to full gain");

        int lateDry = 502;
        AL10.STATES.put(lateDry, AL10.AL_PLAYING);
        AL10.SOURCE_OFFSETS.put(lateDry, 0.50f);
        playsBefore = AL10.sourcePlayCalls;
        LegacySoftwareWetBackend.ApplyResult late = wet.apply(lateDry, 12L, rendered);
        check(!late.getApplied(), "late wet result created a second voice");
        check(late.getLateFallback(), "late wet result did not fail closed to EFX");
        check(AL10.sourcePlayCalls == playsBefore, "late wet result called alSourcePlay");
        check(wet.getLateFallbacks() == 1L, "late fallback diagnostic counter mismatch");

        wet.clearAll();
        System.out.println("PASS: software-wet late activation is rejected and early activation fades before EFX handoff");
    }

    private static int newestGainSource() {
        int best = 0;
        for (Map.Entry<Integer, Float> entry : AL10.GAINS.entrySet()) {
            if (entry.getKey() > best) best = entry.getKey();
        }
        return best;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
