package dev.acoustic.mc1122.forge

import dev.acoustic.mc1122.LegacyBlockSample
import dev.acoustic.mc1122.LegacyWorldAccess
import java.lang.reflect.Method
import java.util.HashMap
import java.util.HashSet

/** Production-world reader with one block-state fetch per sampled voxel and cached hot-path reflection handles/chunks. */
class ReflectiveWorldAccess(private val world: Any, private val revisionValue: Long) : LegacyWorldAccess {
    private val mutablePos: Any? = try { ForgeReflection.construct("net.minecraft.util.math.BlockPos\$MutableBlockPos") } catch (_: Throwable) { null }
    private val worldGetBlockState: Method? = ForgeReflection.findByNameAndArity(world.javaClass, "getBlockState", 1) ?: ForgeReflection.findByNameAndArity(world.javaClass, "func_180495_p", 1)
    private val worldGetChunkFromChunkCoords: Method? = ForgeReflection.findByNameAndArity(world.javaClass, "getChunkFromChunkCoords", 2) ?: ForgeReflection.findByNameAndArity(world.javaClass, "func_72964_e", 2)
    private val mutableSetPos: Method? = mutablePos?.let { ForgeReflection.findByNameAndArity(it.javaClass, "setPos", 3) ?: ForgeReflection.findByNameAndArity(it.javaClass, "func_181079_c", 3) }
    @Volatile private var stateGetBlock: Method? = null
    private val chunks = HashMap<Long, Any>()
    private val chunkStateMethods = HashMap<Class<*>, Method>()
    private val missingChunkStateMethods = HashSet<Class<*>>()

    override fun sample(x: Int, y: Int, z: Int): LegacyBlockSample {
        val pos = position(x, y, z)
        val state = state(x, y, z, pos)
        val block = block(state)
        return ForgeBlockAcousticIntrospector.INSTANCE.sample(state, block, world, pos, x, y, z)
    }
    override fun registryId(x: Int, y: Int, z: Int) = sample(x, y, z).registryId()
    override fun stateId(x: Int, y: Int, z: Int) = sample(x, y, z).stateId()
    override fun materialName(x: Int, y: Int, z: Int) = sample(x, y, z).materialName()
    override fun soundTypeName(x: Int, y: Int, z: Int) = sample(x, y, z).soundTypeName()
    override fun oreDictionaryNames(x: Int, y: Int, z: Int) = sample(x, y, z).oreDictionaryNames()
    override fun solid(x: Int, y: Int, z: Int) = sample(x, y, z).solid()
    override fun revision(): Long = revisionValue

    private fun state(x: Int, y: Int, z: Int, pos: Any): Any {
        val chunk = chunk(x shr 4, z shr 4)
        if (chunk != null) {
            val method = chunkStateMethod(chunk.javaClass)
            if (method != null) try { method.invoke(chunk, pos)?.let { return it } } catch (_: Exception) {}
        }
        if (worldGetBlockState != null) try { return worldGetBlockState.invoke(world, pos) } catch (e: Exception) { throw IllegalStateException("getBlockState failed", e) }
        return requireNotNull(ForgeReflection.invoke(world, arrayOf("getBlockState", "func_180495_p"), pos))
    }

    private fun chunk(cx: Int, cz: Int): Any? {
        val method = worldGetChunkFromChunkCoords ?: return null
        val key = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)
        chunks[key]?.let { return it }
        return try { method.invoke(world, cx, cz)?.also { chunks[key] = it } } catch (_: Exception) { null }
    }

    private fun chunkStateMethod(type: Class<*>): Method? {
        chunkStateMethods[type]?.let { return it }
        if (missingChunkStateMethods.contains(type)) return null
        val method = ForgeReflection.findByNameAndArity(type, "getBlockState", 1) ?: ForgeReflection.findByNameAndArity(type, "func_177435_g", 1)
        if (method == null) missingChunkStateMethods.add(type) else chunkStateMethods[type] = method
        return method
    }

    private fun block(state: Any): Any {
        var method = stateGetBlock
        if (method == null) synchronized(this) {
            method = stateGetBlock
            if (method == null) {
                method = ForgeReflection.findByNameAndArity(state.javaClass, "getBlock", 0) ?: ForgeReflection.findByNameAndArity(state.javaClass, "func_177230_c", 0)
                stateGetBlock = method
            }
        }
        val resolved = method
        if (resolved != null) try { return resolved.invoke(state) } catch (e: Exception) { throw IllegalStateException("IBlockState.getBlock failed", e) }
        return requireNotNull(ForgeReflection.invoke(state, arrayOf("getBlock", "func_177230_c")))
    }

    private fun position(x: Int, y: Int, z: Int): Any {
        if (mutablePos != null && mutableSetPos != null) try {
            mutableSetPos.invoke(mutablePos, x, y, z)
            return mutablePos
        } catch (_: Exception) {}
        return ForgeReflection.construct("net.minecraft.util.math.BlockPos", x, y, z)
    }
}
