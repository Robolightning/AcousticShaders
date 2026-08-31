package dev.acoustic.mc1122

/** Thin seam implemented with Forge/Minecraft classes in the runtime adapter. Called only during main-thread capture. */
interface LegacyWorldAccess {
    fun registryId(x: Int, y: Int, z: Int): String
    fun stateId(x: Int, y: Int, z: Int): String = registryId(x, y, z)
    fun materialName(x: Int, y: Int, z: Int): String
    fun soundTypeName(x: Int, y: Int, z: Int): String
    fun oreDictionaryNames(x: Int, y: Int, z: Int): Set<String>
    fun solid(x: Int, y: Int, z: Int): Boolean
    fun revision(): Long
    /** Fast-path allowing an adapter to fetch the block state only once. */
    fun sample(x: Int, y: Int, z: Int): LegacyBlockSample = LegacyBlockSample(
        registryId(x, y, z), stateId(x, y, z), materialName(x, y, z), soundTypeName(x, y, z), oreDictionaryNames(x, y, z), solid(x, y, z)
    )
}
