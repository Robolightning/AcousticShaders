package dev.acoustic.mc1122.forge

import dev.acoustic.api.environment.AcousticMedia
import dev.acoustic.api.environment.AcousticMedium
import dev.acoustic.api.scene.AcousticBox
import dev.acoustic.api.scene.AcousticShape
import dev.acoustic.core.material.infer.MaterialFacts
import dev.acoustic.mc1122.LegacyBlockSample
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

/** Reflective 1.12.2 block-state introspection shared by generated DB and live capture. */
internal class ForgeBlockAcousticIntrospector private constructor() {
    private val states: MutableMap<Any, Cached> = Collections.synchronizedMap(IdentityHashMap())
    private val collisionMethods: MutableMap<Class<*>, Method> = Collections.synchronizedMap(HashMap())
    private val missingCollisionMethods: MutableSet<Class<*>> = Collections.synchronizedSet(HashSet())
    private val collisionScratch = object : ThreadLocal<ArrayList<Any>>() { override fun initialValue() = ArrayList<Any>(8) }
    private val lastState = object : ThreadLocal<LastState>() { override fun initialValue() = LastState() }
    @Volatile private var aabbConstructor: Constructor<*>? = null
    @Volatile private var materialNames: Map<Any, String>? = null

    fun sample(state: Any, block: Any, world: Any?, pos: Any?, x: Int, y: Int, z: Int): LegacyBlockSample {
        val cached = cached(state, block, world, pos)
        val shape = collisionShape(state, block, world, pos, x, y, z, cached)
        val acousticSolid = cached.solid || !shape.isEmpty()
        return LegacyBlockSample(
            cached.registryId, cached.stateId, cached.materialName, cached.soundTypeName,
            cached.oreNames, acousticSolid, cached.medium, shape, fluidShape(cached, block, world, pos)
        )
    }

    fun facts(state: Any, block: Any): MaterialFacts {
        val cached = cached(state, block, null, null)
        return MaterialFacts(
            cached.registryId, cached.stateId, cached.materialName, cached.soundTypeName, cached.oreNames,
            cached.solid, cached.fullCube, cached.opaque, cached.liquid, cached.hardness, cached.resistance
        )
    }

    fun enumerateAll(): List<MaterialFacts> {
        val registry = try { ForgeReflection.staticField("net.minecraft.block.Block", "REGISTRY", "field_149771_c") } catch (_: Throwable) { return emptyList() }
        val iterable = asIterable(registry) ?: return emptyList()
        val out = LinkedHashMap<String, MaterialFacts>()
        for (block in iterable) {
            block ?: continue
            var any = false
            var meta = 0
            while (meta < 16) {
                val state = stateFromMeta(block, meta)
                if (state != null) {
                    val facts = facts(state, block)
                    if (facts.stateId().isNotEmpty()) { out[facts.stateId()] = facts; any = true }
                }
                meta++
            }
            if (!any) {
                val state = defaultState(block)
                if (state != null) {
                    val facts = facts(state, block)
                    out[facts.stateId()] = facts
                }
            }
        }
        return Collections.unmodifiableList(ArrayList(out.values))
    }

    private fun cached(state: Any, block: Any, world: Any?, pos: Any?): Cached {
        val local = lastState.get()
        if (local.state === state && local.cached != null) return requireNotNull(local.cached)
        var value = states[state]
        if (value == null) {
            synchronized(states) {
                value = states[state]
                if (value == null) {
                    value = inspect(state, block, world, pos)
                    states[state] = requireNotNull(value)
                }
            }
        }
        local.state = state
        local.cached = value
        return requireNotNull(value)
    }

    private fun inspect(state: Any, block: Any, world: Any?, pos: Any?): Cached {
        val registry = registryId(block)
        val meta = meta(block, state)
        val stateId = if (meta >= 0) "$registry#meta=$meta" else registry
        val material = try { ForgeReflection.invoke(state, arrayOf("getMaterial", "func_185904_a")) } catch (_: Throwable) { null }
        val materialName = materialName(material, block, registry)
        val liquid = bool(material, arrayOf("isLiquid", "func_76224_d"), false)
        val full = bool(state, arrayOf("isFullCube", "func_185917_h"), false)
        val opaque = bool(state, arrayOf("isOpaqueCube", "func_185914_p"), full)
        val blocks = bool(material, arrayOf("blocksMovement", "func_76230_c"), full)
        val solid = !liquid && (full || blocks)
        return Cached(
            registry, stateId, materialName, soundTypeName(state, block, world, pos), oreNames(block, meta),
            solid, full, opaque, liquid, inferMedium(registry, materialName, liquid), meta,
            numberField(block, 0f, "blockHardness", "field_149782_v"),
            numberField(block, 0f, "blockResistance", "field_149781_w")
        )
    }

    private fun collisionShape(state: Any, block: Any, world: Any?, pos: Any?, x: Int, y: Int, z: Int, cached: Cached): AcousticShape {
        if (cached.liquid || cached.registryId == "minecraft:air") return AcousticShape.EMPTY
        if (cached.fullCube) return AcousticShape.FULL
        if (world == null || pos == null) return if (cached.solid) AcousticShape.FULL else AcousticShape.EMPTY
        val raw = collisionScratch.get()
        raw.clear()
        try {
            val query = newQueryBox(x, y, z)
            val method = collisionMethod(block.javaClass)
            if (method != null) {
                method.invoke(block, state, world, pos, query, raw, null, true)
                val shape = shapeFromAabbs(raw, x, y, z)
                if (!shape.isEmpty()) return shape
            }
            val box = try { ForgeReflection.invoke(state, arrayOf("getCollisionBoundingBox", "func_185890_d"), world, pos) } catch (_: Throwable) { null }
            if (box != null) {
                raw.clear(); raw.add(box)
                val shape = shapeFromAabbs(raw, x, y, z)
                if (!shape.isEmpty()) return shape
            }
        } catch (_: Throwable) {
            // Conservative fallback below.
        } finally {
            raw.clear()
        }
        return if (cached.solid) AcousticShape.FULL else AcousticShape.EMPTY
    }

    private fun collisionMethod(type: Class<*>): Method? {
        collisionMethods[type]?.let { return it }
        if (missingCollisionMethods.contains(type)) return null
        synchronized(collisionMethods) {
            collisionMethods[type]?.let { return it }
            if (missingCollisionMethods.contains(type)) return null
            var method = ForgeReflection.findByNameAndArity(type, "addCollisionBoxToList", 7)
            if (method == null) method = ForgeReflection.findByNameAndArity(type, "func_185477_a", 7)
            if (method == null) missingCollisionMethods.add(type) else collisionMethods[type] = method
            return method
        }
    }

    private fun newQueryBox(x: Int, y: Int, z: Int): Any {
        var constructor = aabbConstructor
        if (constructor == null) {
            synchronized(this) {
                constructor = aabbConstructor
                if (constructor == null) {
                    val aabb = ForgeReflection.type("net.minecraft.util.math.AxisAlignedBB")
                    constructor = aabb.getDeclaredConstructor(
                        java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Double.TYPE,
                        java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Double.TYPE
                    ).also { it.isAccessible = true }
                    aabbConstructor = constructor
                }
            }
        }
        return requireNotNull(constructor).newInstance(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
    }

    private fun registryId(block: Any): String = try {
        ForgeReflection.invoke(block, arrayOf("getRegistryName"))?.toString() ?: block.javaClass.name.lowercase(Locale.ROOT)
    } catch (_: RuntimeException) { block.javaClass.name.lowercase(Locale.ROOT) }

    private fun meta(block: Any, state: Any): Int = try {
        ((ForgeReflection.invoke(block, arrayOf("getMetaFromState", "func_176201_c"), state) as Number).toInt() and 15)
    } catch (_: Throwable) { -1 }

    private fun stateFromMeta(block: Any, meta: Int): Any? = try {
        ForgeReflection.invoke(block, arrayOf("getStateFromMeta", "func_176203_a"), meta)
    } catch (_: Throwable) { if (meta == 0) defaultState(block) else null }

    private fun defaultState(block: Any): Any? = try { ForgeReflection.invoke(block, arrayOf("getDefaultState", "func_176223_P")) } catch (_: Throwable) { null }

    private fun materialName(material: Any?, block: Any, registry: String): String {
        if (material == null) return "${block.javaClass.simpleName} $registry"
        var names = materialNames
        if (names == null) {
            synchronized(this) {
                if (materialNames == null) materialNames = scanMaterialNames(material.javaClass)
                names = materialNames
            }
        }
        return "${names?.get(material) ?: material.javaClass.simpleName} ${block.javaClass.simpleName} $registry"
    }

    private fun soundTypeName(state: Any, block: Any, world: Any?, pos: Any?): String {
        try {
            val sound = ForgeReflection.field(block, "blockSoundType", "field_149762_H")
            val name = soundName(sound)
            if (name.isNotEmpty()) return name
        } catch (_: Throwable) { }
        if (world != null && pos != null) {
            try {
                val method = ForgeReflection.findByNameAndArity(block.javaClass, "getSoundType", 4)
                if (method != null) {
                    val name = soundName(method.invoke(block, state, world, pos, null))
                    if (name.isNotEmpty()) return name
                }
            } catch (_: Throwable) { }
        }
        return ""
    }

    private fun oreNames(block: Any, meta: Int): Set<String> {
        if (meta < 0) return emptySet()
        return try {
            val stack = ForgeReflection.construct("net.minecraft.item.ItemStack", block, 1, meta)
            val ore = ForgeReflection.type("net.minecraftforge.oredict.OreDictionary")
            val raw = ForgeReflection.invoke(ore, arrayOf("getOreIDs"), stack)
            if (raw !is IntArray) return emptySet()
            val names = LinkedHashSet<String>()
            for (id in raw) {
                val name = ForgeReflection.invoke(ore, arrayOf("getOreName"), id)?.toString()
                if (!name.isNullOrEmpty()) names.add(name)
            }
            if (names.isEmpty()) emptySet() else Collections.unmodifiableSet(names)
        } catch (_: Throwable) { emptySet() }
    }

    private class LastState { var state: Any? = null; var cached: Cached? = null }
    private class Cached(
        val registryId: String, val stateId: String, val materialName: String, val soundTypeName: String,
        val oreNames: Set<String>, val solid: Boolean, val fullCube: Boolean, val opaque: Boolean,
        val liquid: Boolean, val medium: AcousticMedium, val meta: Int, val hardness: Float, val resistance: Float
    )

    companion object {
        @JvmField val INSTANCE = ForgeBlockAcousticIntrospector()

        private fun inferMedium(registryId: String, materialName: String, liquid: Boolean): AcousticMedium {
            if (!liquid) return AcousticMedia.AIR
            val text = "$registryId $materialName".lowercase(Locale.ROOT)
            return if (text.contains("lava") || text.contains("magma") || text.contains("molten")) AcousticMedia.LAVA else AcousticMedia.WATER
        }

        /**
         * Vanilla 1.12 BlockLiquid free-surface approximation for acoustic volume occupancy.
         * func_149801_b(level) first maps falling levels >= 8 to level 0 and returns (level+1)/9;
         * the rendered/local liquid height is 1-that value, i.e. 8/9 for source/falling blocks
         * and (8-level)/9 for flowing levels 1..7. SceneMediumGeometry separately removes the
         * apparent top surface when the captured voxel above contains the same propagation medium.
         * Unknown/modded metadata still conservatively falls back to the vanilla level-0 height.
         */
        private fun fluidShape(cached: Cached, block: Any, world: Any?, pos: Any?): AcousticShape {
            if (!cached.liquid || cached.medium.id() == AcousticMedia.AIR.id()) return AcousticShape.EMPTY

            // Forge IFluidBlock-style implementations can expose their actual local fill fraction.
            // This is position-sensitive but does not require another world-state lookup.
            if (world != null && pos != null) {
                try {
                    val method = ForgeReflection.findByNameAndArity(block.javaClass, "getFilledPercentage", 2)
                    if (method != null) {
                        val raw = (method.invoke(block, world, pos) as? Number)?.toDouble()
                        if (raw != null && raw.isFinite()) {
                            val fill = kotlin.math.abs(raw).coerceIn(0.0, 1.0)
                            if (fill > 1.0e-6) return if (fill >= 1.0 - 1.0e-6) AcousticShape.FULL else
                                AcousticShape.of(AcousticBox(0.0, 0.0, 0.0, 1.0, fill, 1.0))
                        }
                    }
                } catch (_: Throwable) {
                    // Fall through to vanilla-compatible metadata approximation.
                }
            }

            val rawMeta = cached.meta
            val level = if (rawMeta in 1..7) rawMeta else 0
            val height = ((8 - level) / 9.0).coerceIn(1.0 / 9.0, 8.0 / 9.0)
            return AcousticShape.of(AcousticBox(0.0, 0.0, 0.0, 1.0, height, 1.0))
        }

        private fun shapeFromAabbs(raw: List<*>, x: Int, y: Int, z: Int): AcousticShape {
            if (raw.isEmpty()) return AcousticShape.EMPTY
            val boxes = ArrayList<AcousticBox>(minOf(64, raw.size))
            for (aabb in raw) {
                if (aabb == null || boxes.size >= 64) break
                try {
                    var minX = ForgeReflection.numberField(aabb, "minX", "field_72340_a")
                    var minY = ForgeReflection.numberField(aabb, "minY", "field_72338_b")
                    var minZ = ForgeReflection.numberField(aabb, "minZ", "field_72339_c")
                    var maxX = ForgeReflection.numberField(aabb, "maxX", "field_72336_d")
                    var maxY = ForgeReflection.numberField(aabb, "maxY", "field_72337_e")
                    var maxZ = ForgeReflection.numberField(aabb, "maxZ", "field_72334_f")
                    val worldCoords = minX > 1.000001 || minY > 1.000001 || minZ > 1.000001 || maxX > 1.000001 || maxY > 1.000001 || maxZ > 1.000001 || minX < -0.000001 || minY < -0.000001 || minZ < -0.000001
                    if (worldCoords) { minX -= x; maxX -= x; minY -= y; maxY -= y; minZ -= z; maxZ -= z }
                    minX = clamp01(minX); minY = clamp01(minY); minZ = clamp01(minZ)
                    maxX = clamp01(maxX); maxY = clamp01(maxY); maxZ = clamp01(maxZ)
                    if (maxX - minX > 1e-6 && maxY - minY > 1e-6 && maxZ - minZ > 1e-6) boxes.add(AcousticBox(minX, minY, minZ, maxX, maxY, maxZ))
                } catch (_: Throwable) { }
            }
            return AcousticShape.of(boxes)
        }

        private fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)
        private fun scanMaterialNames(type: Class<*>): Map<Any, String> {
            val out = IdentityHashMap<Any, String>()
            val base = try { ForgeReflection.type("net.minecraft.block.material.Material") } catch (_: Throwable) { type }
            var current: Class<*>? = type
            while (current != null) {
                for (field: Field in current.declaredFields) {
                    try {
                        if (Modifier.isStatic(field.modifiers)) {
                            field.isAccessible = true
                            val value = field.get(null)
                            if (value != null && base.isInstance(value)) out[value] = field.name.lowercase(Locale.ROOT)
                        }
                    } catch (_: Throwable) { }
                }
                current = current.superclass
            }
            return out
        }
        private fun soundName(sound: Any?): String {
            if (sound == null) return ""
            return try {
                val event = ForgeReflection.invoke(sound, arrayOf("getBreakSound", "func_185845_c")) ?: return sound.javaClass.simpleName
                ForgeReflection.invoke(event, arrayOf("getRegistryName"))?.toString() ?: sound.javaClass.simpleName
            } catch (_: Throwable) { sound.javaClass.simpleName }
        }
        private fun bool(target: Any?, names: Array<String>, fallback: Boolean): Boolean {
            if (target == null) return fallback
            return try { ForgeReflection.invoke(target, names) == java.lang.Boolean.TRUE } catch (_: Throwable) { fallback }
        }
        private fun numberField(target: Any, fallback: Float, vararg names: String): Float = try {
            (ForgeReflection.field(target, *names) as Number).toFloat()
        } catch (_: Throwable) { fallback }
        private fun asIterable(registry: Any?): Iterable<*>? {
            if (registry is Iterable<*>) return registry
            if (registry is Map<*, *>) return registry.values
            // Vanilla 1.12.2 Block.REGISTRY is already Iterable. Some forks expose only an
            // iterator()-shaped wrapper, so use that standard contract as the fallback. Do not
            // alias func_82594_a here: in RegistrySimple it is getObject(K) and requires a key.
            try {
                val raw = ForgeReflection.invoke(registry ?: return null, arrayOf("iterator"))
                if (raw is Iterator<*>) {
                    val values = ArrayList<Any?>()
                    while (raw.hasNext()) values.add(raw.next())
                    return values
                }
            } catch (_: Throwable) { }
            return null
        }
    }
}
