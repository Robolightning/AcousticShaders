# Minecraft 1.13.2 bootstrap

This directory is an isolated ForgeGradle 3.x workspace for the 1.13.2 port.
It deliberately does not participate in the proven 1.12.2 root build/release graph.

Pinned port inputs:

- Minecraft 1.13.2
- Forge 25.0.223
- Java 8
- Kotlin 1.3.50
- JavaFML (`modLoader="javafml"`, loader major 25)

The bootstrap uses a regular instantiable Kotlin `@Mod` class and the
`FMLJavaModLoadingContext` lifecycle. No 1.12.2 MixinBooter/core-plugin path is carried forward.
Forge 1.13.x `FMLModContainer` constructs the annotated class with `Class.newInstance()`; the exact
Kotlin 1.3.50 bootstrap contract therefore requires and verifies a public no-arg constructor.

## Kotlin runtime boundary

The Gradle workspace puts exact Kotlin 1.3.50 stdlib/JDK8 on the development runtime classpath.
It does **not** shade or otherwise promise those libraries inside a distributable mod JAR yet.
Runtime delivery remains a separate gate.

Forgelin 1.8.4 is intentionally not a dependency: its upstream source tag is a 1.12.2/Forge-14
coremod/language-adapter implementation using APIs removed by Forge 25.x. Kottle 1.0.6 is useful
architecture evidence for the 1.13-era `LANGPROVIDER` model, but it bundles Kotlin 1.3.21 and is not
silently substituted for the required Kotlin 1.3.50 runtime.

## Current acceptance boundary

This is a loader/module skeleton, not a real-client compatibility claim. Audio hooks, client events,
world adaptation, GUI integration, native backends and runtime packaging are still unported.
