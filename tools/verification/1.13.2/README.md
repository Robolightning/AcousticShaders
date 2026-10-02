# Minecraft 1.13.2 verification

Verified port baseline:

- Java 8
- Forge 25.0.223
- Kotlin 1.3.50
- JavaFML loader major 25
- no Forgelin-Continuous
- no Minecraft-1.12.2 MixinBooter dependency
- no accepted third-party Kotlin language provider yet

The original handoff named Forgelin 1.8.4 for 1.13.2. Upstream source disproves that as a
Forge-25 dependency: tag `1.8.4` itself targets Minecraft 1.12.2 / Forge 14.23.x and imports the
old `Loader`, `FMLCommonHandler` and `FMLPreInitializationEvent` APIs. Forge 1.13.x has already
moved to `ModList`, lifecycle events and `FMLJavaModLoadingContext`. Therefore Forgelin 1.8.4 is
rejected fail-closed for this branch instead of being accepted from game-version metadata alone.

Kottle 1.0.6 is useful historical architecture evidence because it is a 1.13.2 `LANGPROVIDER`,
but its upstream build pins Kotlin 1.3.21, so it is not silently substituted for this project's
exact Kotlin 1.3.50 requirement.

The first shared gate deliberately compiles only `acoustic-api`, `acoustic-platform-api`, and
`acoustic-core` so game/API migration cannot hide shared-source incompatibilities.

`compile-shared.sh` requires the official `kotlin-compiler-1.3.50.zip` and an actual JDK 8. The
verified compiler archive is:

- size: 50,555,421 bytes
- SHA-1: `b23b87de7fb44c94f6459c44c10e2735c183a7ef`
- SHA-256: `69424091a6b7f52d93eed8bba2ace921b02b113dbb71388d704f8180a6bdc6ec`

Example:

```bash
ACOUSTIC_KOTLIN_1350_ZIP=/path/to/kotlin-compiler-1.3.50.zip \
ACOUSTIC_JDK8_HOME=/path/to/jdk8 \
./tools/verification/1.13.2/compile-shared.sh
```

The shared gate has passed on a real JDK 8 at commit `c6c5ae7`: 275 classfiles, maximum major 52.

`javafml-skeleton-contract.sh` is an offline static gate for the first game-module layer. It pins
the exact Forge coordinate, Java/Kotlin bytecode targets, official JavaFML metadata/lifecycle model,
and rejects legacy 1.12.2 loader/provider APIs. It does not claim a networked ForgeGradle build or
real-client launch.

Forge 25.0.223 MDK binary provenance remains a separate input gate. The published MDK SHA-1 is
`cdb74f0b351a5933c1637bb9fd1bd6d4fb82b141`; do not claim exact-MDK execution until those bytes are
actually available and verified.
