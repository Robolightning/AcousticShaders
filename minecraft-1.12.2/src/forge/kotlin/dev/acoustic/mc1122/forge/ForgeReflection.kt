package dev.acoustic.mc1122.forge

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

/** Small reflection bridge accepting MCP and SRG names. */
object ForgeReflection {
    private val methods = ConcurrentHashMap<String, Method>()
    private val fields = ConcurrentHashMap<String, Field>()
    private val constructors = ConcurrentHashMap<String, Constructor<*>>()

    @JvmStatic fun type(name: String): Class<*> = try { Class.forName(name) } catch (e: ClassNotFoundException) { throw IllegalStateException("Missing runtime class $name", e) }

    @JvmStatic fun invoke(target: Any, names: Array<String>, vararg args: Any?): Any? {
        val clazz = if (target is Class<*>) target else target.javaClass
        val method = method(clazz, names, args)
        return try { method.invoke(if (target is Class<*>) null else target, *args) } catch (e: Exception) { throw IllegalStateException("Failed invoking ${Arrays.toString(names)} on ${clazz.name}", e) }
    }

    @JvmStatic fun field(target: Any, vararg names: String): Any? {
        val clazz = if (target is Class<*>) target else target.javaClass
        val key = clazz.name + "#F#" + Arrays.toString(names)
        val field = fields[key] ?: findField(clazz, names).also { fields[key] = it }
        return try { field.get(if (target is Class<*>) null else target) } catch (e: IllegalAccessException) { throw IllegalStateException(e) }
    }

    @JvmStatic fun numberField(target: Any, vararg names: String): Double = (field(target, *names) as Number).toDouble()

    @JvmStatic fun setField(target: Any, value: Any?, vararg names: String) {
        val clazz = if (target is Class<*>) target else target.javaClass
        val key = clazz.name + "#F#" + Arrays.toString(names)
        val field = fields[key] ?: findField(clazz, names).also { fields[key] = it }
        try { field.set(if (target is Class<*>) null else target, value) } catch (e: IllegalAccessException) { throw IllegalStateException(e) }
    }

    @JvmStatic fun construct(className: String, vararg args: Any?): Any {
        val clazz = type(className)
        val key = clazz.name + "#C#" + signature(args)
        val ctor = constructors[key] ?: run {
            val found = clazz.declaredConstructors.firstOrNull { candidate ->
                val params = candidate.parameterTypes
                params.size == args.size && matches(params, args)
            } ?: throw IllegalStateException("Missing constructor for $className ${signature(args)}")
            found.isAccessible = true
            constructors[key] = found
            found
        }
        return try { ctor.newInstance(*args) } catch (e: Exception) { throw IllegalStateException("Failed constructing $className", e) }
    }

    @JvmStatic fun findByNameAndArity(clazz: Class<*>, name: String, arity: Int): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) if (method.name == name && method.parameterTypes.size == arity) {
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    @JvmStatic fun invokeByNameAndArity(target: Any, name: String, arity: Int, vararg args: Any?): Any? {
        val method = findByNameAndArity(target.javaClass, name, arity) ?: throw IllegalStateException("Missing method $name/$arity on ${target.javaClass.name}")
        return try { method.invoke(target, *args) } catch (e: Exception) { throw IllegalStateException(e) }
    }

    private fun method(clazz: Class<*>, names: Array<String>, args: Array<out Any?>): Method {
        val key = clazz.name + "#M#" + Arrays.toString(names) + "#" + signature(args)
        methods[key]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null) {
            for (name in names) for (candidate in current.declaredMethods) {
                if (candidate.name == name && candidate.parameterTypes.size == args.size && matches(candidate.parameterTypes, args)) {
                    candidate.isAccessible = true
                    methods[key] = candidate
                    return candidate
                }
            }
            current = current.superclass
        }
        throw IllegalStateException("Missing method ${Arrays.toString(names)} on ${clazz.name} args=${signature(args)}")
    }

    private fun findField(clazz: Class<*>, names: Array<out String>): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            for (name in names) {
                try {
                    return current.getDeclaredField(name).also { it.isAccessible = true }
                } catch (_: NoSuchFieldException) {}
            }
            current = current.superclass
        }
        throw IllegalStateException("Missing field ${Arrays.toString(names)} on ${clazz.name}")
    }

    private fun matches(params: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in params.indices) {
            val arg = args[i]
            if (arg == null) {
                if (params[i].isPrimitive) return false
            } else if (!wrap(params[i]).isAssignableFrom(arg.javaClass)) return false
        }
        return true
    }

    private fun wrap(clazz: Class<*>): Class<*> = when (clazz) {
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Double.TYPE -> Double::class.javaObjectType
        java.lang.Float.TYPE -> Float::class.javaObjectType
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Byte.TYPE -> Byte::class.javaObjectType
        java.lang.Short.TYPE -> Short::class.javaObjectType
        java.lang.Character.TYPE -> Char::class.javaObjectType
        else -> clazz
    }

    private fun signature(args: Array<out Any?>): String = buildString { for (arg in args) append(arg?.javaClass?.name ?: "null").append(';') }
    @JvmStatic fun staticField(className: String, vararg names: String): Any? = field(type(className), *names)
    @JvmStatic fun isStatic(method: Method): Boolean = Modifier.isStatic(method.modifiers)
}
