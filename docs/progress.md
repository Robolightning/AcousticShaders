# Project status — 0.3.0-rc20 release cleanup

`0.3.0` is scoped to **Minecraft 1.12.2 / Forge 14.23.5.2864 only**. The portable acoustic API remains version-neutral, but no newer Minecraft version is part of this release or claimed compatible.

The last fully promoted behavioral checkpoint before repository cleanup is `a697bd5` (`Delay live propagation and smooth software wet onset`). It closed the final reproduced perceptual defect: a late software-wet voice could be heard as a separate richer event after the original TNT sound. The same checkpoint also moved finite medium-aware propagation time from the acoustic model into the first live native Paulscode sample for eligible positional one-shots.

## Verified release behavior

The promoted candidate has already demonstrated all of the following on the tracked 1.12.2 target:

- 54/54 headless tests, 39/39 advanced release tests, architecture boundary and six Reference profiles;
- exact Kotlin 2.4.0 / Java 8 clean A/B builds with byte-identical JAR, source snapshot and ALL-IN-ONE;
- real-SRG compile/member audits against official Minecraft 1.12.2, MCPConfig and Forge 14.23.5.2864;
- real `EntityTippedArrow` flight source, movement, direct-path diagnostics and cleanup;
- real vanilla TNT gameplay across WATER→AIR, AIR→WATER, WATER→WATER, AIR→WATER→AIR and AIR→LAVA→AIR;
- physical NVIDIA CUDA rays and CUDA-FDTD with first-use CPU equivalence self-tests and zero backend failures;
- physical Windows OpenAL/software-wet execution;
- human perceptual confirmation that the previously reported distinct ~0.5 s second/richer TNT sound is gone;
- finite live propagation hold/resume on real Paulscode sources, with no late second wet voice in the confirming run.

## Release-cleanup policy

The project is now in **release hardening**, not feature development. Cleanup changes should reduce ambiguity, stale documentation, private-machine assumptions or tooling drift without redesigning already verified physics/audio hot paths.

Before the final `0.3.0` tag, the final cleanup HEAD must pass again:

1. repository hygiene and shell/source syntax checks;
2. `./dev-verify.sh` on a clean tree;
3. exact Kotlin 2.4.0 A/B build and deterministic packaging;
4. official 1.12.2 real-SRG/RFG-equivalent gates;
5. packaged-JAR smoke tests and release archive integrity;
6. targeted physical recheck only if cleanup touches production audio/physics/native behavior.

Publication happens only after those gates are green: push the clean repository to GitHub, create the signed/tagged GitHub release from the exact verified artifact, then publish the same verified JAR and matching metadata to CurseForge and Modrinth. No platform receives a separately rebuilt binary.
