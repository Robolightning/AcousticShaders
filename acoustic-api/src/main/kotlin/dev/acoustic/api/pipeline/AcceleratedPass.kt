package dev.acoustic.api.pipeline

interface AcceleratedPass : Pass {
    @Throws(Exception::class)
    fun tryExecuteAccelerated(context: PassContext): String?
}
