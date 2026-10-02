#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

BUILD='minecraft-1.13.2/build.gradle'
PROPS='minecraft-1.13.2/gradle.properties'
MODS='minecraft-1.13.2/src/main/resources/META-INF/mods.toml'
BOOT='minecraft-1.13.2/src/main/kotlin/dev/acoustic/mc1132/forge/AcousticShadersForgeMod.kt'

for f in "$BUILD" "$PROPS" "$MODS" "$BOOT" minecraft-1.13.2/settings.gradle minecraft-1.13.2/src/main/resources/pack.mcmeta; do
  [[ -s "$f" ]] || { echo "ERROR: missing 1.13.2 skeleton file: $f" >&2; exit 1; }
done

grep -F "forge_version=1.13.2-25.0.223" "$PROPS" >/dev/null
grep -F "kotlin_version=1.3.50" "$PROPS" >/dev/null
grep -F "sourceCompatibility = targetCompatibility = compileJava.sourceCompatibility = compileJava.targetCompatibility = '1.8'" "$BUILD" >/dev/null
grep -F "compileKotlin.kotlinOptions.jvmTarget = '1.8'" "$BUILD" >/dev/null
grep -F "compileKotlin.kotlinOptions.allWarningsAsErrors = true" "$BUILD" >/dev/null
grep -F "mappings channel: 'snapshot', version: '20180921-1.13'" "$BUILD" >/dev/null
grep -F 'minecraft "net.minecraftforge:forge:${forge_version}"' "$BUILD" >/dev/null
grep -F 'implementation "org.jetbrains.kotlin:kotlin-stdlib:${kotlin_version}"' "$BUILD" >/dev/null
grep -F 'implementation "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlin_version}"' "$BUILD" >/dev/null
grep -F 'kotlinRuntimeBundle("org.jetbrains.kotlin:kotlin-stdlib:${kotlin_version}") { transitive = false }' "$BUILD" >/dev/null
grep -F 'kotlinRuntimeBundle("org.jetbrains.kotlin:kotlin-stdlib-jdk7:${kotlin_version}") { transitive = false }' "$BUILD" >/dev/null
grep -F 'kotlinRuntimeBundle("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlin_version}") { transitive = false }' "$BUILD" >/dev/null
grep -F 'preserveFileTimestamps = false' "$BUILD" >/dev/null
grep -F 'reproducibleFileOrder = true' "$BUILD" >/dev/null

grep -F 'modLoader="javafml"' "$MODS" >/dev/null
grep -F 'loaderVersion="[25,)"' "$MODS" >/dev/null
grep -F 'versionRange="[25.0.223,26)"' "$MODS" >/dev/null
grep -F 'versionRange="[1.13.2]"' "$MODS" >/dev/null

grep -F '@Mod(AcousticShadersForgeMod.MOD_ID)' "$BOOT" >/dev/null
grep -F 'FMLJavaModLoadingContext.get().modEventBus.addListener' "$BOOT" >/dev/null
grep -F 'Consumer<FMLCommonSetupEvent>' "$BOOT" >/dev/null

if grep -R -E -i 'forgelin|mixinbooter|FMLPreInitializationEvent|FMLCommonHandler|net\.minecraftforge\.fml\.common\.Loader' \
    minecraft-1.13.2/build.gradle minecraft-1.13.2/gradle.properties minecraft-1.13.2/src >/dev/null; then
  echo 'ERROR: legacy 1.12.2 loader/provider dependency leaked into 1.13.2 skeleton' >&2
  exit 1
fi

printf '%s\n' '[PASS] 1.13.2 JavaFML skeleton contract: Forge 25.0.223, Java 8, Kotlin 1.3.50, no legacy provider path'
