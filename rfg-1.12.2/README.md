# Acoustic Shaders — RetroFuturaGradle 1.12.2 integration gate

This is a **separate integration/reobfuscation build**, not a replacement for the deterministic
`build-local-release.sh` pipeline. It consumes the same production Kotlin source roots directly.

The gate pins:

- RetroFuturaGradle `1.4.9`, tag commit `94702da47e2c0d626986a42bd8124c63e52afc2a`;
- Gradle `8.14.3`;
- Minecraft `1.12.2`;
- Forge `14.23.5.2847` for RFG decompile/tooling userdev;
- Forge `14.23.5.2864` for Acoustic Shaders runtime/universal ABI and final validation;
- MixinBooter `11.15`;
- Forgelin-Continuous `2.4.0.0`;
- exact Kotlin compiler `2.4.0` with JVM target 8;
- a Java 8 Minecraft toolchain provisioned through Foojay while Gradle/RFG itself runs on the WSL JDK 17.

RFG 1.4.9 itself defaults Minecraft 1.12.2 to Forge 14.23.5.2847. `bootstrap-wsl.sh`
verifies the official 1.4.9 tag and deliberately leaves that source/default untouched because the
2847 userdev artifact exists and is the tooling input expected by RFG. The build upgrades only the
`forgeUniversal` module used for runtime/final ABI resolution to exact 2864 and rejects any other
resolved universal. There is no Forge 2864 legacy userdev artifact, so the two roles must not be
collapsed into one version.

The authoritative source has dual MCP/SRG GUI entry points because the custom release pipeline is
already SRG-facing. A normal RFG build compiles against MCP names and then reobfuscates them to
SRG, so compiling those dual methods verbatim would create duplicate methods. The build therefore
uses `dev-tools/prepare-rfg-kotlin-source.py` to create a temporary MCP-only GUI view. No production
source file is modified.

`compileAcousticKotlin` keeps `-Werror` enabled, but filters non-existent filesystem entries from
Gradle's resolved compile classpath immediately before invoking `kotlinc`. RFG/Gradle can expose
empty output locations for source sets that legitimately contain no Java/resources (for example
`api`, `injectedTags`, or `mcLauncher` resources). Passing those absent paths to Kotlin 2.4 emits
classpath warnings unrelated to Acoustic Shaders source quality; filtering only absent entries
prevents that false failure while preserving warnings-as-errors for real Kotlin diagnostics.

On the user's WSL, with the previously verified Kotlin 2.4.0 distribution still at
`~/acoustic-kotlin-2.4/extracted/kotlinc`, run:

```bash
./rfg-1.12.2/bootstrap-wsl.sh
```

The final integration artifact is expected at:

`rfg-1.12.2/build/libs/acoustic-shaders-mc1122-0.3.0-rc19.jar`

If an older WSL run left `~/acoustic-rfg-1.12.2/RetroFuturaGradle-1.4.9-forge2864`, the bootstrap reuses its local Git objects to create the new clean `RetroFuturaGradle-1.4.9` checkout. It does not modify or trust the old worktree, so the obsolete uncommitted 2847→2864 patch cannot leak into the corrected userdev/tooling path.

On WSL the bootstrap also writes `~/acoustic-rfg-1.12.2/AcousticShaders-RFG-1.12.2-Result.zip` containing the reobfuscated JAR, full gate log and SHA-256/toolchain report. The report records the split explicitly as `tooling/userdev=2847` and `runtime/universal=2864`. When Windows interop is available it copies that result ZIP into the user's Windows `Downloads` directory automatically. On failure it copies `AcousticShaders-RFG-FAILED.log` there instead.
If the already verified Minecraft 1.12.2 client, MCPConfig, Forge universal and installer files are present in Windows `Downloads`, the bootstrap also SHA-checks them and runs the project's official-binary SRG bytecode-reference audit against the **reobfuscated RFG JAR itself**; its log is included in the result ZIP.

`verifyReobfAcousticJar` checks required entries, Java-8 classfile versions, Mixin manifest metadata,
absence of shaded `kotlin/*.class`, and exact Forge 14.23.5.2864 universal resolution. The Forge
coordinate check reads Gradle's resolved module identity (`moduleVersion.id.group/name/version`) and
requires the `universal` classifier; it deliberately does not compare `ResolvedArtifact.name`, which is
an artifact name and caused a false empty-version result after a successful real reobfuscation.

## Included-build dependency repositories

RFG 1.4.9 is consumed by this workspace as an included Gradle plugin build. Its plugin runtime
contains `net.fabricmc:mercury:0.6.0` and `net.fabricmc:mapping-io`, which are hosted on Fabric
Maven rather than Maven Central. Gradle resolves those included-plugin runtime dependencies through
the consumer `pluginManagement.repositories` set, so this workspace deliberately mirrors RFG's
Forge/Mojang/Fabric/GTNH repository universe there.

The WSL bootstrap probes the exact Mercury 0.6.0 POM before starting Gradle. If that probe fails,
the problem is network/DNS access to Fabric Maven. If Gradle reports that it searched only GTNH,
Plugin Portal and Maven Central for Mercury, the workspace is stale and predates the Fabric Maven
`pluginManagement` fix.

## Transferable offline build environment

The repository now has a strict transfer format for moving a previously warmed, known-good RFG environment
into a network-isolated machine without treating an arbitrary Gradle cache as provenance. On the online/WSL
machine, `../dev-rfg-buildenv-pack.sh` requires the official Gradle 8.14.3 ZIP with SHA-256
`bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`, a clean RFG checkout exactly at
`94702da47e2c0d626986a42bd8124c63e52afc2a`, `caches/modules-2`, `caches/retro_futura_gradle`, and a
Gradle-managed Java 8 toolchain under `~/.gradle/jdks`. It stores RFG as a Git bundle and covers every
payload file/symlink with a SHA-256 manifest.

On the isolated machine, `../dev-rfg-buildenv-import.sh <bundle.tar.zst>` validates the payload manifest and
pinned metadata, reconstructs `out/rfg-local/{gradle-8.14.3,RetroFuturaGradle-1.4.9,gradle-user-home}`, and
runs `../dev-rfg-local-runner.sh --preflight-only`. The normal runner additionally requires bootstrap Java
17 and exact Kotlin 2.4.0, then runs `rfgReleaseGate` with `--offline`. This format deliberately does not
claim a successful RFG build merely because a cache was imported; only the subsequent Gradle task can close
formal RFG provenance.

## Windows / WSL one-block launcher

For a Windows-hosted test, `run-rfg-gate-windows.ps1` is the canonical bridge. It deliberately does **not** pass a Windows path through `wslpath` from `wsl.exe` command-line arguments, because native argument translation can strip the backslashes before `wslpath` sees them. It converts an absolute drive path such as `C:\Users\...` directly to `/mnt/c/Users/...` in PowerShell using explicit separator character codes (`[char]92` -> `[char]47`) and runs an exact path-conversion self-test before starting WSL, writes the multiline Linux runner to a temporary UTF-8/LF `.sh` file, then invokes `wsl.exe bash <runner>`. The launcher never hard-codes a bundle SHA or repository HEAD: it accepts `ACOUSTIC_RFG_BUNDLE` as an explicit path or selects the newest `AcousticShaders-RFG-WSL-Bundle-<git-short>.zip` in Downloads, derives the expected Git short id from that filename, and verifies the extracted full HEAD plus a clean working tree before RFG starts. Before RFG starts, the generated Bash syntax check is retried three times to tolerate transient `Wsl/Service/*` failures. If WSL still cannot execute, the launcher writes `AcousticShaders-RFG-FAILED.log` with `wsl.exe --version`, `--status`, and `-l -v` diagnostics instead of failing without a transferable artifact. It deliberately does not call `wsl.exe --shutdown`, because that could terminate unrelated WSL sessions.

The launcher is wrapped in one PowerShell script block so an interactive paste executes atomically, and its result reporting uses independent `if` statements instead of a top-level `if/elseif/else` chain. These are regression requirements after the 2026-09-08 Windows/WSL bridge failure that occurred before Gradle/RFG itself started.


### RFG disk preflight

The bootstrap requires 8192 MiB free and safely reclaims only regenerable Acoustic/RFG state before a real build. Dependency/Minecraft caches and toolchains are preserved.
