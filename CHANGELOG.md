# Changelog

## 0.3.0

- First stable public release for **Minecraft 1.12.2 / Forge 14.23.5.2864**.
- Production implementation is Kotlin/JVM 8 with mandatory MixinBooter 11.15 and Forgelin-Continuous 2.4.0.0+.
- Reference Acoustic Shader provides POTATO/LOW/MEDIUM/HIGH/ULTRA/MAXIMUM profiles, exact partial-block geometry, transmission, diffraction, multi-bounce rays, modal/FDTD wave simulation, hybrid RIR and optional software-wet convolution.
- AIR/WATER/LAVA and modded volume media support density, speed of sound, eight-band attenuation, impedance boundaries, Snell/Fermat refraction, partial/flowing fluid surfaces and heterogeneous CPU FDTD.
- Vanilla projectile flight emitters, source profiles, Doppler and finite medium-aware first-arrival delay run through the ordinary Minecraft -> Paulscode -> Mixin -> Acoustic Shaders path.
- Transactional Acoustic Shader directory/ZIP hot reload and Resource Pack acoustic material/media/source overlays preserve the last-good runtime on invalid edits.
- CUDA and OpenCL ray/FDTD backends include first-use CPU equivalence validation and safe fallback.
- Generated default material/source data provides broad vanilla/modded compatibility while remaining overrideable by Resource Packs.
- Release validation covers 54 headless + 39 advanced regressions, six Reference profiles, Java-8 bytecode/API fencing, real-SRG/Forge contracts, real EntityTippedArrow and TNT AIR/WATER/LAVA gameplay, physical CUDA-FDTD, physical Windows audio/software-wet output and the final human perceptual double-onset check.
- Repository/release hardening adds cross-platform LF normalization, JSON/ZIP/version/path hygiene checks, deterministic package rebuilding and version-agnostic exact Kotlin 2.4 CI.

Pre-release development history is available in the Git commit history.
