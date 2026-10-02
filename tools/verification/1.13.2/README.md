# Minecraft 1.13.2 verification

Verified port baseline:

- Java 8
- Forge 25.0.223
- Kotlin 1.3.50
- JavaFML loader major 25
- no Forgelin-Continuous
- no Minecraft-1.12.2 MixinBooter dependency
- no accepted third-party Kotlin language provider
- self-contained exact Kotlin 1.3.50 runtime (stdlib + jdk7 + jdk8 only)

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

The 1.13.2 distributable JAR embeds only the exact Kotlin 1.3.50 runtime needed by production code:

The hashes below pin the Maven/Gradle-resolved runtime artifacts used by the real ForgeGradle build; the copies bundled inside the standalone Kotlin compiler distribution are semantically equivalent inputs but are not byte-identical archives.

- `kotlin-stdlib-1.3.50.jar` SHA-256 `e6f05746ee0366d0b52825a090fac474dcf44082c9083bbb205bd16976488d6c`
- `kotlin-stdlib-jdk7-1.3.50.jar` SHA-256 `9a026639e76212f8d57b86d55b075394c2e009f1979110751d34c05c5f75d57b`
- `kotlin-stdlib-jdk8-1.3.50.jar` SHA-256 `1b351fb6e09c14b55525c74c1f4cf48942eae43c348b7bc764a5e6e423d4da0c`

The Gradle build verifies those input hashes before packaging and merges them with reproducible archive ordering/timestamps. `kotlin-runtime-package-contract.sh` checks the resulting JAR, including runtime version, JDK7/JDK8 implementation classes, absence of Kotlin reflect/coroutines payloads, and Java-8 classfile ceiling.

`javafml-skeleton-contract.sh` is an offline static gate for the first game-module layer. It pins
the exact Forge coordinate, Java/Kotlin bytecode targets, official JavaFML metadata/lifecycle model,
and rejects legacy 1.12.2 loader/provider APIs. It does not claim a networked ForgeGradle build or
real-client launch.

Forge 25.0.223 MDK binary provenance remains a separate input gate. The published MDK SHA-1 is
`cdb74f0b351a5933c1637bb9fd1bd6d4fb82b141`; do not claim exact-MDK execution until those bytes are
actually available and verified.
