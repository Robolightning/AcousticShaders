package dev.acoustic.tools

import dev.acoustic.api.capability.Capabilities
import dev.acoustic.api.capability.Capability
import dev.acoustic.api.material.AcousticMaterials
import dev.acoustic.api.math.Vec3
import dev.acoustic.core.io.WavWriter
import dev.acoustic.core.pack.ShaderPackLoader
import dev.acoustic.core.pack.ShaderPackValidator
import dev.acoustic.core.pipeline.DefaultPipeline
import dev.acoustic.core.pipeline.PipelinePlan
import dev.acoustic.core.runtime.AcousticRuntimeSession
import dev.acoustic.core.runtime.StandardPipelineCompiler
import dev.acoustic.testkit.VoxelTestScene
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.EnumSet

/** Headless author tool: validates and executes every declared profile against a deterministic reference room. */
object PackConformanceCli {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        if (args.size !in 1..2) {
            System.err.println("usage: PackConformanceCli <pack-dir-or-zip> [output-dir]")
            System.exit(2)
        }
        val packPath = Paths.get(args[0])
        val out = if (args.size == 2) Paths.get(args[1]) else Paths.get("out/conformance")
        Files.createDirectories(out)
        val loader = ShaderPackLoader()
        val pack = if (Files.isDirectory(packPath)) loader.loadDirectory(packPath) else loader.loadZip(packPath)
        val capabilities = Capabilities(EnumSet.allOf(Capability::class.java))
        ShaderPackValidator().requireValid(pack, capabilities)
        val declaredProfiles = pack.options().profiles()
        if (declaredProfiles.isEmpty()) throw IllegalArgumentException("pack declares no profiles")
        val requestedProfile = System.getenv("ACOUSTIC_CONFORMANCE_PROFILE")?.trim()?.takeIf { it.isNotEmpty() }
        val profiles = if (requestedProfile == null) declaredProfiles else declaredProfiles.filter { it == requestedProfile }
        if (requestedProfile != null && profiles.isEmpty()) throw IllegalArgumentException("pack does not declare requested profile: $requestedProfile")
        val scene = referenceRoom()
        val source = Vec3(2.5, 2.5, 2.5)
        val listener = Vec3(8.5, 3.5, 7.5)
        val workers = maxOf(1, minOf(8, Runtime.getRuntime().availableProcessors()))
        println("Acoustic Shader Conformance")
        println("pack=${pack.manifest().name()} spec=${pack.manifest().spec()}")
        for (profile in profiles) {
            val session = AcousticRuntimeSession(pack, profile, workers)
            try {
                val frame = session.process(scene, source, listener)
                val prefix = safe(profile)
                val wav = out.resolve("$prefix-rir.wav")
                WavWriter.writeMono16(wav, frame.impulseResponse())

                val timings = StringBuilder()
                timings.append("total_ms=").append(frame.report().elapsedMillis()).append('\n')
                for (timing in frame.report().timings()) {
                    timings.append(timing.passId()).append('\t').append(timing.millis()).append(" ms\t").append(timing.threadName()).append('\n')
                }
                Files.write(out.resolve("$prefix-timings.txt"), timings.toString().toByteArray(StandardCharsets.UTF_8))

                val compiled: DefaultPipeline = StandardPipelineCompiler().compile(pack, profile)
                val plan = PipelinePlan.compile(compiled)
                val dot = StringBuilder("digraph acoustic_pipeline {\n  rankdir=LR;\n")
                for (pass in compiled.passes()) dot.append("  \"").append(escape(pass.id())).append("\";\n")
                for ((pass, dependencies) in plan.dependencies()) {
                    for (dependency in dependencies) {
                        dot.append("  \"").append(escape(dependency.id())).append("\" -> \"").append(escape(pass.id())).append("\";\n")
                    }
                }
                dot.append("}\n")
                Files.write(out.resolve("$prefix-pipeline.dot"), dot.toString().toByteArray(StandardCharsets.UTF_8))
                println("[PASS] $profile elapsed_ms=${frame.report().elapsedMillis()} rir_samples=${frame.impulseResponse().length()} -> $wav")
            } finally {
                session.close()
            }
        }
        println("PASS: ${profiles.size} profiles")
    }

    private fun referenceRoom(): VoxelTestScene {
        val builder = VoxelTestScene.builder().boxShell(0, 0, 0, 11, 6, 10, AcousticMaterials.STONE)
        for (x in 1..4) builder.solid(x, 1, 1, AcousticMaterials.WOOD)
        for (z in 3..7) builder.solid(6, 1, z, AcousticMaterials.WOOL)
        return builder.build()
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
