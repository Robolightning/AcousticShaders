package dev.acoustic.api.pipeline

interface Pipeline {
    fun passes(): List<Pass>
}
