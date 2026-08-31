package dev.acoustic.core.runtime

import dev.acoustic.api.pipeline.Pass
import dev.acoustic.core.pack.PipelineDefinition
import dev.acoustic.core.passes.*
import dev.acoustic.core.wave.FdtdWavePass

/** Built-in portable pass implementations used by the reference Acoustic Shader. */
object StandardPassFactories {
    @JvmStatic
    fun create(): PassFactoryRegistry {
        val registry = PassFactoryRegistry()
        registry.register("standard.source_behavior", factory { o, p -> SourceBehaviorPass(pd(o, "strength", p, "SOURCE_PROFILE_STRENGTH", 1.0).toFloat(), !"OFF".equals(p.get("SOURCE_DOPPLER", "ON"), ignoreCase = true)) })
        registry.register("standard.direct_path", factory { _, p -> DirectPathPass(p.getBoolean("DIRECT_OCCLUSION", true)) })
        registry.register("standard.diffraction", factory { _, _ -> DiffractionPass() })
        registry.register("standard.environment_rays", factory { o, p -> EnvironmentRayPass(pi(o, "rays", p, "RAYS", 512), pi(o, "bounces", p, "BOUNCES", 4), pd(o, "distance", p, "RAY_DISTANCE", 48.0), pd(o, "min_energy", p, "MIN_ENERGY", 0.001), ps(o, "compute_backend", p, "RAY_COMPUTE_BACKEND", "AUTO")) })
        registry.register("standard.early_reflections", factory { o, p -> EarlyReflectionPass(pi(o, "rays", p, "EARLY_RAYS", maxOf(32, p.getInt("RAYS", 512) / 3)), pd(o, "distance", p, "EARLY_DISTANCE", 32.0)) })
        registry.register("standard.late_reverb", factory { _, _ -> LateReverbPass() })
        registry.register("standard.wave_low_frequency", factory { o, p ->
            if ("FDTD".equals(p.get("WAVE", "MODAL"), ignoreCase = true)) FdtdWavePass(pd(o, "radius", p, "FDTD_RADIUS", 6.0), pi(o, "subdivisions", p, "FDTD_SUBDIVISIONS", 1), pi(o, "steps", p, "FDTD_STEPS", 256), 0.45, p.get("COMPUTE_BACKEND", "AUTO"))
            else WaveApproximationPass(pd(o, "probe_distance", p, "WAVE_PROBE_DISTANCE", 48.0), pd(o, "max_hz", p, "WAVE_MAX_HZ", 500.0))
        })
        val hybrid = factory { _, p ->
            val raw = p.get("HYBRID_CROSSOVER_HZ", "AUTO") ?: "AUTO"
            val crossover = if ("AUTO".equals(raw, ignoreCase = true)) -1.0 else raw.toDouble()
            HybridResponsePass(p.get("HYBRID_MODE", "AUTO"), crossover, p.getDouble("HYBRID_CROSSFADE_OCTAVES", 1.0))
        }
        registry.register("standard.hybrid", hybrid)
        registry.register("standard.hybridize", hybrid)
        registry.register("standard.impulse_response", factory { o, p -> ImpulseResponsePass(pi(o, "sample_rate", p, "RIR_SAMPLE_RATE", 48000), pd(o, "seconds", p, "RIR_SECONDS", 2.5)) })
        registry.register("standard.foa", factory { _, _ -> FoaPass() })
        return registry
    }

    private fun factory(maker: (Map<String, Any?>, ResolvedProfile) -> Pass): PassFactory = object : PassFactory {
        override fun create(definition: PipelineDefinition.PassDefinition, profile: ResolvedProfile): Pass = maker(definition.options(), profile)
    }

    private fun pi(options: Map<String, Any?>, option: String, profile: ResolvedProfile, profileKey: String, fallback: Int): Int {
        val pv = profile.get(profileKey, null)
        if (pv != null) return pv.toInt()
        val value = options[option] ?: return fallback
        return (value as Number).toInt()
    }

    private fun pd(options: Map<String, Any?>, option: String, profile: ResolvedProfile, profileKey: String, fallback: Double): Double {
        val pv = profile.get(profileKey, null)
        if (pv != null) return pv.toDouble()
        val value = options[option] ?: return fallback
        return (value as Number).toDouble()
    }

    private fun ps(options: Map<String, Any?>, option: String, profile: ResolvedProfile, profileKey: String, fallback: String): String {
        val pv = profile.get(profileKey, null)
        if (pv != null) return pv
        return options[option]?.toString() ?: fallback
    }
}
