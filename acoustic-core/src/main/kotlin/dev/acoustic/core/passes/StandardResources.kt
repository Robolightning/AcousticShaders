package dev.acoustic.core.passes

import dev.acoustic.api.environment.AcousticEnvironment
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.pipeline.ResourceKey
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.api.source.AcousticSourceProfile
import dev.acoustic.core.rir.ImpulseResponse
import dev.acoustic.core.dsp.FoaImpulseResponse

object StandardResources {
    @JvmField val ENVIRONMENT = ResourceKey("environment", AcousticEnvironment::class.java)
    @JvmField val SCENE = ResourceKey("scene", AcousticScene::class.java)
    @JvmField val SOURCE_POSITION = ResourceKey("source.position", Vec3::class.java)
    @JvmField val SOURCE_ID = ResourceKey("source.id", String::class.java)
    @JvmField val SOURCE_PROFILE = ResourceKey("source.profile", AcousticSourceProfile::class.java)
    @JvmField val SOURCE_GAIN = ResourceKey("source.gain", Float::class.javaObjectType)
    @JvmField val SOURCE_VELOCITY = ResourceKey("source.velocity", Vec3::class.java)
    @JvmField val SOURCE_BEHAVIOR = ResourceKey("source.behavior", AcousticSourceProfile::class.java)
    @JvmField val LISTENER_POSITION = ResourceKey("listener.position", Vec3::class.java)
    @JvmField val LISTENER_FORWARD = ResourceKey("listener.forward", Vec3::class.java)
    @JvmField val LISTENER_UP = ResourceKey("listener.up", Vec3::class.java)
    @JvmField val LISTENER_VELOCITY = ResourceKey("listener.velocity", Vec3::class.java)
    @JvmField val DIRECT_PATH = ResourceKey("direct.path", DirectPathResult::class.java)
    @JvmField val REFLECTION_FIELD = ResourceKey("reflection.field", ReflectionField::class.java)
    @JvmField val EARLY_REFLECTIONS = ResourceKey("rir.early", EarlyReflectionField::class.java)
    @JvmField val DIFFRACTION = ResourceKey("propagation.diffraction", DiffractionResult::class.java)
    @JvmField val LATE_REVERB = ResourceKey("rir.late", LateReverb::class.java)
    @JvmField val HYBRID_RESPONSE = ResourceKey("response.hybrid", HybridResponse::class.java)
    @JvmField val WAVE_FIELD = ResourceKey("wave.field", WaveFieldResult::class.java)
    @JvmField val IMPULSE_RESPONSE = ResourceKey("rir.mono", ImpulseResponse::class.java)
    @JvmField val FOA_IMPULSE_RESPONSE = ResourceKey("rir.foa.acn_sn3d", FoaImpulseResponse::class.java)
}
