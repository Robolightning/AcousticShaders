package dev.acoustic.api.pipeline

import java.util.Collections

interface Pass {
    fun id(): String
    fun reads(): Set<ResourceKey<*>>
    fun writes(): Set<ResourceKey<*>>
    fun optionalReads(): Set<ResourceKey<*>> = Collections.emptySet()
    @Throws(Exception::class)
    fun execute(context: PassContext)
}
