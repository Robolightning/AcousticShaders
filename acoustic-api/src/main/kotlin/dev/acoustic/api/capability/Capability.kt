package dev.acoustic.api.capability

/** Stable names used by packs to negotiate optional runtime features. */
enum class Capability {
    PARALLEL_CPU,
    RAY_QUERY,
    WAVE_FIELD,
    DIFFRACTION,
    HRTF,
    CONVOLUTION,
    AMBISONICS,
    GPU_COMPUTE
}
