package dev.acoustic.api.pipeline

interface PassContext {
    fun <T> require(key: ResourceKey<T>): T
    fun <T> get(key: ResourceKey<T>): T?
    fun <T> put(key: ResourceKey<T>, value: T)
}
