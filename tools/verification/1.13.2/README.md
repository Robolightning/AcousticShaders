# Minecraft 1.13.2 verification

Initial port baseline:

- Java 8
- Forge 25.0.223
- Kotlin 1.3.50
- Forgelin 1.8.4
- no Forgelin-Continuous
- no Minecraft-1.12.2 MixinBooter dependency

The first gate deliberately compiles only `acoustic-api`, `acoustic-platform-api`, and `acoustic-core` so game/API migration cannot hide shared-source incompatibilities.

`compile-shared.sh` requires the official `kotlin-compiler-1.3.50.zip` and an actual JDK 8. The verified compiler archive is:

- size: 50,555,421 bytes
- SHA-1: `b23b87de7fb44c94f6459c44c10e2735c183a7ef`
- SHA-256: `69424091a6b7f52d93eed8bba2ace921b02b113dbb71388d704f8180a6bdc6ec`

Example:

```bash
ACOUSTIC_KOTLIN_1350_ZIP=/path/to/kotlin-compiler-1.3.50.zip \
ACOUSTIC_JDK8_HOME=/path/to/jdk8 \
./tools/verification/1.13.2/compile-shared.sh
```
