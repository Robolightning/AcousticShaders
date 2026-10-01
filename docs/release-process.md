# Release process

Acoustic Shaders release artifacts are promoted from one verified Git commit. Do not rebuild independently for different distribution sites.

## 1. Freeze

- target Minecraft: `1.12.2`;
- target Forge: `14.23.5.2864`;
- compiler: exact Kotlin `2.4.0`;
- JVM/API target: Java 8;
- required runtime mods: MixinBooter `11.15`, Forgelin-Continuous `2.4.0.0+`;
- working tree clean, no untracked release inputs.

## 2. Verify

Run the repository hygiene gate and `./verify.sh`. In the exact Windows/toolchain environment, repeat independent A/B builds and require byte-identical JAR, source snapshot and ALL-IN-ONE. Verify the final packaged JAR, Java-8 classfile ceiling, Mixin retention, real-SRG references and archive checksums.

Production behavior changed after the last hardware/perceptual certification only if the final diff touches runtime/physics/audio/native paths. In that case rerun the relevant real-client/hardware gate before tagging.

## 3. Tag and GitHub

Push the exact verified commit. Create the final version tag only after CI passes on that same commit. The GitHub release must attach the already verified JAR, source snapshot, ALL-IN-ONE and checksum file; do not rebuild after tagging.

## 4. CurseForge and Modrinth

Publish the exact same JAR bytes that were attached to the GitHub release. Keep Minecraft/Forge version requirements, dependencies, changelog and SHA-256 consistent across all distribution pages. MixinBooter and Forgelin-Continuous are required dependencies for the 1.12.2 build.

## 5. Post-release invariant

Any hotfix starts from the released tag, receives a new version, and repeats the same verification/publishing chain. Never replace an already published binary in place with different bytes under the same version.
