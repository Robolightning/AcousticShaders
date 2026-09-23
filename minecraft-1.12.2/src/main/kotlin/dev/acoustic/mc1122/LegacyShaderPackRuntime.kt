package dev.acoustic.mc1122

import dev.acoustic.api.capability.Capabilities
import dev.acoustic.api.capability.Capability
import dev.acoustic.api.material.resolve.MaterialRule
import dev.acoustic.api.environment.resolve.MediumRule
import dev.acoustic.api.pipeline.Pass
import dev.acoustic.core.compute.FdtdBackendRegistry
import dev.acoustic.core.compute.GeometricBackendRegistry
import dev.acoustic.core.material.MaterialResolver
import dev.acoustic.core.material.MaterialResolverCompiler
import dev.acoustic.core.medium.MediumResolver
import dev.acoustic.core.medium.MediumResolverCompiler
import dev.acoustic.core.pack.LoadedShaderPack
import dev.acoustic.core.pack.MaterialPack
import dev.acoustic.core.pack.PackOptions
import dev.acoustic.core.pack.PipelineDefinition
import dev.acoustic.core.pack.ShaderPackLoader
import dev.acoustic.core.pack.ShaderPackManifest
import dev.acoustic.core.pack.ShaderPackStackComposer
import dev.acoustic.core.pack.ShaderPackValidator
import dev.acoustic.core.passes.LegacyEffectTuning
import dev.acoustic.core.passes.StandardResources
import dev.acoustic.core.pipeline.DefaultPipeline
import dev.acoustic.core.pipeline.PipelineValidator
import dev.acoustic.core.runtime.ResolvedProfile
import dev.acoustic.core.runtime.AcousticPassRegistry
import dev.acoustic.core.runtime.StandardPipelineCompiler
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.LinkedHashSet
import java.util.EnumSet
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Validates, composes and prepares the selected acoustic-shader stack for the 1.12.2 backend. */
class LegacyShaderPackRuntime private constructor(
    private val packValue: LoadedShaderPack,
    private val resolverValue: MaterialResolver,
    private val mediumResolverValue: MediumResolver,
    private val profileValue: String,
    private val stackFilesValue: List<String>,
    private val stackNamesValue: List<String>,
    private val liveTuningValue: LegacyEffectTuning,
    private val performanceTuningValue: LegacyPerformanceTuning,
    private val livePipelineValue: DefaultPipeline,
    private val listenerInvariantPipelineValue: DefaultPipeline,
    private val sourcePipelineValue: DefaultPipeline,
    private val sourceProfileStrengthValue: Float,
    private val disabledValue: Boolean
) {
    fun pack(): LoadedShaderPack = packValue
    fun resolver(): MaterialResolver = resolverValue
    fun mediumResolver(): MediumResolver = mediumResolverValue
    fun profile(): String = profileValue
    fun stackFiles(): List<String> = stackFilesValue
    fun stackNames(): List<String> = stackNamesValue
    fun liveTuning(): LegacyEffectTuning = liveTuningValue
    fun performanceTuning(): LegacyPerformanceTuning = performanceTuningValue
    fun livePipeline(): DefaultPipeline = livePipelineValue
    fun listenerInvariantPipeline(): DefaultPipeline = listenerInvariantPipelineValue
    fun sourcePipeline(): DefaultPipeline = sourcePipelineValue
    fun sourceProfileStrength(): Float = sourceProfileStrengthValue
    fun disabled(): Boolean = disabledValue

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun load(shaderpackDir: Path, config: LegacyRuntimeConfig): LegacyShaderPackRuntime = load(shaderpackDir, config, emptyList(), emptyList())

        @JvmStatic
        @Throws(IOException::class)
        fun load(shaderpackDir: Path, config: LegacyRuntimeConfig, externalMaterialRules: List<MaterialRule>): LegacyShaderPackRuntime =
            load(shaderpackDir, config, externalMaterialRules, emptyList())

        @JvmStatic
        @Throws(IOException::class)
        fun load(shaderpackDir: Path, config: LegacyRuntimeConfig, externalMaterialRules: List<MaterialRule>, externalMediumRules: List<MediumRule>): LegacyShaderPackRuntime {
            if (config.packs().isEmpty()) return disabledRuntime(config, externalMaterialRules, externalMediumRules)
            val loader = ShaderPackLoader()
            val loaded = ArrayList<LoadedShaderPack>()
            val names = ArrayList<String>()
            val capabilitySet = EnumSet.of(Capability.RAY_QUERY, Capability.PARALLEL_CPU, Capability.WAVE_FIELD)
            if (FdtdBackendRegistry.hasAvailable("cuda") || GeometricBackendRegistry.hasAvailable("cuda") ||
                FdtdBackendRegistry.hasAvailable("opencl") || GeometricBackendRegistry.hasAvailable("opencl")) {
                capabilitySet.add(Capability.GPU_COMPUTE)
            }
            val caps = Capabilities(capabilitySet)
            val normalizedRoot = shaderpackDir.normalize()
            for (file in config.packs()) {
                val path = shaderpackDir.resolve(file).normalize()
                require(path.startsWith(normalizedRoot)) { "shaderpack escapes shaderpack directory" }
                require(Files.exists(path)) { "shaderpack not found: ${path.fileName}" }
                val loadedPack = if (Files.isDirectory(path)) loader.loadDirectory(path) else loader.loadZip(path)
                ShaderPackValidator().requireValidLayer(loadedPack, caps)
                loaded.add(loadedPack)
                names.add(loadedPack.manifest().name())
            }
            val compositionOrder = ArrayList(loaded)
            Collections.reverse(compositionOrder)
            val effective = ShaderPackStackComposer.compose(compositionOrder)
            ShaderPackValidator().requireValid(effective, caps)
            require(effective.options().profiles().contains(config.profile())) { "preset ${config.profile()} is not defined by effective shader stack" }
            val compiled = StandardPipelineCompiler(AcousticPassRegistry.snapshot()).compile(effective, config.profile(), config.optionOverrides())
            PipelineValidator.validate(compiled)
            val live = outputClosure(compiled, StandardResources.HYBRID_RESPONSE, "legacy live backend requires a pass that writes response.hybrid")
            val invariant = listenerInvariantPipeline(compiled)
            val source = sourcePipeline(compiled, invariant)
            val tuning = deriveTuning(effective, config)
            val performance = derivePerformance(effective, config)
            val resolved = ResolvedProfile.from(effective.options(), config.profile(), config.optionOverrides())
            val sourceStrength = if (containsPass(compiled, "standard.source_behavior")) max(0.0, min(1.0, safeDouble(resolved, "SOURCE_PROFILE_STRENGTH", 1.0))).toFloat() else 0f
            return LegacyShaderPackRuntime(
                effective,
                MaterialResolverCompiler.compile(effective, externalMaterialRules),
                MediumResolverCompiler.compile(effective, externalMediumRules),
                config.profile(),
                Collections.unmodifiableList(ArrayList(config.packs())),
                Collections.unmodifiableList(names),
                tuning, performance, live, invariant, source, sourceStrength, false
            )
        }

        @Throws(IOException::class)
        private fun disabledRuntime(config: LegacyRuntimeConfig, externalMaterialRules: List<MaterialRule>, externalMediumRules: List<MediumRule>): LegacyShaderPackRuntime {
            val manifest = ShaderPackManifest.parse("{\"format\":1,\"spec\":\"0.3\",\"id\":\"acoustic:none\",\"name\":\"No Acoustic Shader\",\"requires\":[],\"optional\":[]}")
            val pipeline = PipelineDefinition.parse("{\"format\":1,\"passes\":[]}")
            val values = linkedMapOf("profile.NONE" to "", "profile.order" to "NONE")
            val synthetic = LoadedShaderPack(manifest, pipeline, PackOptions.of(values), emptyMap<String, MaterialPack>())
            val resolver = MaterialResolverCompiler.compile(synthetic, externalMaterialRules)
            val mediumResolver = MediumResolverCompiler.compile(synthetic, externalMediumRules)
            val empty = DefaultPipeline(emptyList<Pass>())
            val workers = max(1, min(2, Runtime.getRuntime().availableProcessors()))
            val perf = LegacyPerformanceTuning(config.horizontalRadius(), config.verticalRadius(), config.captureIntervalTicks(), 3, 240, workers, 32, config.roomProbeDistance(), 1, 1.0, 5.0)
            return LegacyShaderPackRuntime(
                synthetic, resolver, mediumResolver, "NONE", emptyList(), emptyList(),
                LegacyEffectTuning(false, false, 0f, 1f, 0f), perf,
                empty, empty, empty, 0f, true
            )
        }

        private fun outputClosure(compiled: DefaultPipeline, output: dev.acoustic.api.pipeline.ResourceKey<*>, missingMessage: String): DefaultPipeline {
            val writers = compiled.passes().filter { it.writes().contains(output) }
            require(writers.size == 1) { if (writers.isEmpty()) missingMessage else "multiple passes write $output" }
            val dependencies = PipelineValidator.dependencies(compiled)
            val keep = LinkedHashSet<Pass>()
            fun include(pass: Pass) {
                if (!keep.add(pass)) return
                for (dependency in dependencies[pass] ?: emptySet()) include(dependency)
            }
            include(writers[0])
            return DefaultPipeline(compiled.passes().filter { keep.contains(it) })
        }

        /**
         * Listener-only reflection precompute is an optimization, not part of the shader ABI.
         * It is used only when the reflection writer is self-contained and source-independent;
         * otherwise the custom stage remains in the per-source pipeline where all typed inputs exist.
         */
        private fun listenerInvariantPipeline(compiled: DefaultPipeline): DefaultPipeline {
            val writers = compiled.passes().filter { it.writes().contains(StandardResources.REFLECTION_FIELD) }
            if (writers.size != 1) return DefaultPipeline(emptyList<Pass>())
            val writer = writers[0]
            if (writer.writes().size != 1) return DefaultPipeline(emptyList<Pass>())
            val forbidden = setOf(
                StandardResources.SOURCE_POSITION, StandardResources.SOURCE_ID, StandardResources.SOURCE_PROFILE,
                StandardResources.SOURCE_GAIN, StandardResources.SOURCE_VELOCITY, StandardResources.SOURCE_BEHAVIOR
            )
            if (writer.reads().any { forbidden.contains(it) } || writer.optionalReads().any { forbidden.contains(it) }) return DefaultPipeline(emptyList<Pass>())
            val dependencies = PipelineValidator.dependencies(compiled)[writer] ?: emptySet()
            if (dependencies.isNotEmpty()) return DefaultPipeline(emptyList<Pass>())
            return DefaultPipeline(listOf(writer))
        }

        private fun sourcePipeline(compiled: DefaultPipeline, invariant: DefaultPipeline): DefaultPipeline {
            if (invariant.passes().isEmpty()) return compiled
            val excluded = invariant.passes().toSet()
            return DefaultPipeline(compiled.passes().filterNot { excluded.contains(it) })
        }

        private fun deriveTuning(pack: LoadedShaderPack, config: LegacyRuntimeConfig): LegacyEffectTuning {
            val rp = ResolvedProfile.from(pack.options(), config.profile(), config.optionOverrides())
            val rays = safeInt(rp, "RAYS", 512)
            val bounces = safeInt(rp, "BOUNCES", 4)
            val wave = rp.get("WAVE", "OFF")
            var late = rp.get("LATE_REVERB", "ON").equals("ON", ignoreCase = true)
            var diffraction = rp.get("DIFFRACTION", "ON").equals("ON", ignoreCase = true)
            val irSeconds = safeDouble(rp, "RIR_SECONDS", 2.5)
            for (pass in pack.pipeline().passes()) {
                if (pass.id() == "standard.late_reverb" && !rp.enabledExpression(pass.enabled())) late = false
                else if (pass.id() == "standard.diffraction" && !rp.enabledExpression(pass.enabled())) diffraction = false
            }
            val rayFactor = max(0.48, min(1.50, sqrt(max(16, rays) / 1024.0)))
            val bounceFactor = max(0.60, min(1.45, 0.58 + bounces * 0.075))
            val waveFactor = if (wave.equals("FDTD", ignoreCase = true)) 1.12 else if (wave.equals("MODAL", ignoreCase = true)) 1.03 else 0.94
            val wet = max(0.35, min(1.75, rayFactor * bounceFactor * waveFactor)).toFloat()
            val decay = max(0.50, min(1.75, (0.70 + 0.30 * (irSeconds / 3.0)) * bounceFactor * waveFactor)).toFloat()
            val leak = if (diffraction) max(0.12, min(0.60, 0.17 + 0.035 * bounces)).toFloat() else 0f
            return LegacyEffectTuning(late, diffraction, wet, decay, leak)
        }

        private fun derivePerformance(pack: LoadedShaderPack, config: LegacyRuntimeConfig): LegacyPerformanceTuning {
            val rp = ResolvedProfile.from(pack.options(), config.profile(), config.optionOverrides())
            val available = max(1, Runtime.getRuntime().availableProcessors())
            val requested = safeInt(rp, "CPU_THREADS", 0)
            val workers = if (requested <= 0) autoWorkers(available) else max(1, min(available, requested))
            val horizontal = clamp(safeInt(rp, "CAPTURE_HORIZONTAL", config.horizontalRadius()), 6, 48)
            val vertical = clamp(safeInt(rp, "CAPTURE_VERTICAL", config.verticalRadius()), 3, 24)
            val interval = clamp(safeInt(rp, "CAPTURE_INTERVAL", config.captureIntervalTicks()), 1, 100)
            val refresh = clamp(safeInt(rp, "CAPTURE_REFRESH_RADIUS", 4), 1, 12)
            val full = clamp(safeInt(rp, "CAPTURE_FULL_REFRESH_TICKS", 120), 20, 1200)
            val roomRays = clamp(safeInt(rp, "ROOM_RAYS", 64), 16, 512)
            val liveSources = clamp(safeInt(rp, "LIVE_SOURCES", max(1, min(8, workers * 2))), 1, 32)
            val probe = max(4.0, min(64.0, safeDouble(rp, "ROOM_PROBE_DISTANCE", config.roomProbeDistance())))
            val moveThreshold = max(0.05, min(2.0, safeDouble(rp, "SOURCE_MOVE_THRESHOLD", 0.35)))
            val captureBudget = max(1.0, min(20.0, safeDouble(rp, "CAPTURE_BUDGET_MS", 5.0)))
            return LegacyPerformanceTuning(horizontal, vertical, interval, refresh, full, workers, roomRays, probe, liveSources, moveThreshold, captureBudget)
        }

        private fun containsPass(pipeline: DefaultPipeline, id: String): Boolean = pipeline.passes().any { it.id() == id }
        private fun autoWorkers(available: Int): Int {
            if (available <= 2) return 1
            if (available <= 4) return 2
            val reserve = max(2, available / 4)
            return max(2, available - reserve)
        }
        private fun safeInt(rp: ResolvedProfile, key: String, fallback: Int): Int = try { rp.getInt(key, fallback) } catch (_: RuntimeException) { fallback }
        private fun safeDouble(rp: ResolvedProfile, key: String, fallback: Double): Double = try { rp.getDouble(key, fallback) } catch (_: RuntimeException) { fallback }
        private fun clamp(value: Int, low: Int, high: Int): Int = max(low, min(high, value))
    }
}
