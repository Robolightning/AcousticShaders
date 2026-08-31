package dev.acoustic.mc1122.forge

import java.util.Locale
import org.lwjgl.opencl.CL
import org.lwjgl.opencl.CL10
import org.lwjgl.opencl.CLDevice
import org.lwjgl.opencl.CLPlatform

/** Shared OpenCL GPU discovery/selection for the legacy LWJGL2 backend. */
internal object OpenClDeviceSelector {
    private const val CL_DEVICE_HOST_UNIFIED_MEMORY_VALUE = 0x1035
    @Volatile private var discovery: Discovery? = null

    @JvmStatic
    @JvmName("selectBestGpu")
    @Throws(Exception::class)
    internal fun selectBestGpu(): Selection? {
        val d = discover()
        if (d.candidates.isEmpty()) return null
        val requested = System.getProperty("acousticshaders.opencl.device", "").trim()
        if (requested.isNotEmpty() && !requested.equals("AUTO", ignoreCase = true)) {
            requested.toIntOrNull()?.let { index ->
                if (index >= 0 && index < d.candidates.size) return d.candidates[index].selection()
            }
            val q = requested.lowercase(Locale.ROOT)
            for (candidate in d.candidates) {
                if ((candidate.vendor + " " + candidate.name).lowercase(Locale.ROOT).contains(q)) {
                    return candidate.selection()
                }
            }
        }
        var best: Candidate? = null
        for (candidate in d.candidates) {
            val currentBest = best
            if (currentBest == null || compare(candidate, currentBest) > 0) best = candidate
        }
        return best?.selection()
    }

    @JvmStatic
    @JvmName("describeCandidates")
    internal fun describeCandidates(): String = try {
        val d = discover()
        if (d.candidates.isEmpty()) {
            "no compiler-capable OpenCL GPU devices"
        } else {
            buildString {
                d.candidates.forEachIndexed { index, candidate ->
                    if (index > 0) append(" | ")
                    append('[').append(index).append("] ")
                    append(candidate.vendor).append(' ').append(candidate.name).append(' ')
                    append(candidate.computeUnits).append(" CU @ ").append(candidate.mhz).append(" MHz")
                    if (candidate.unifiedKnown) {
                        append(if (candidate.unifiedMemory) " unified-memory" else " discrete-memory")
                    } else {
                        append(" memory-type=unknown")
                    }
                }
            }
        }
    } catch (t: Throwable) {
        "probe failed: " + (t.message ?: t.javaClass.simpleName)
    }

    @Synchronized
    @Throws(Exception::class)
    private fun discover(): Discovery {
        discovery?.let { return it }
        if (!CL.isCreated()) CL.create()
        val platforms = CLPlatform.getPlatforms()
        val out = ArrayList<Candidate>()
        if (platforms != null) {
            for (platform in platforms) {
                val devices = try {
                    platform.getDevices(CL10.CL_DEVICE_TYPE_GPU)
                } catch (_: Throwable) {
                    continue
                } ?: continue
                for (device in devices) {
                    try {
                        if (!device.getInfoBoolean(CL10.CL_DEVICE_AVAILABLE) ||
                            !device.getInfoBoolean(CL10.CL_DEVICE_COMPILER_AVAILABLE)
                        ) continue
                        val vendor = safeString(device, CL10.CL_DEVICE_VENDOR)
                        val name = safeString(device, CL10.CL_DEVICE_NAME)
                        val cu = maxOf(1, device.getInfoInt(CL10.CL_DEVICE_MAX_COMPUTE_UNITS))
                        val mhz = maxOf(1, device.getInfoInt(CL10.CL_DEVICE_MAX_CLOCK_FREQUENCY))
                        var unified = false
                        var known = false
                        try {
                            unified = device.getInfoBoolean(CL_DEVICE_HOST_UNIFIED_MEMORY_VALUE)
                            known = true
                        } catch (_: Throwable) {
                        }
                        out += Candidate(platform, device, vendor, name, cu, mhz, known, unified)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return Discovery(out.toList()).also { discovery = it }
    }

    private fun compare(a: Candidate, b: Candidate): Int {
        val memoryA = memoryRank(a)
        val memoryB = memoryRank(b)
        if (memoryA != memoryB) return memoryA - memoryB
        return localScore(a).compareTo(localScore(b))
    }

    private fun memoryRank(candidate: Candidate): Int {
        if (candidate.unifiedKnown) return if (candidate.unifiedMemory) 1 else 3
        val vendor = candidate.vendor.lowercase(Locale.ROOT)
        val name = candidate.name.lowercase(Locale.ROOT)
        if (vendor.contains("nvidia")) return 3
        if (vendor.contains("intel") || name.contains("uhd") || name.contains("iris")) return 1
        return 2
    }

    private fun localScore(candidate: Candidate): Long {
        var base = candidate.computeUnits.toLong() * candidate.mhz.toLong()
        val vendor = candidate.vendor.lowercase(Locale.ROOT)
        if (vendor.contains("nvidia")) base *= 16L
        else if (vendor.contains("amd") || vendor.contains("advanced micro")) base *= 8L
        return base
    }

    private fun safeString(device: CLDevice, key: Int): String = try {
        device.getInfoString(key)?.trim() ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    internal class Selection(
        @JvmField val platform: CLPlatform,
        @JvmField val device: CLDevice,
        @JvmField val description: String
    )

    private class Candidate(
        val platform: CLPlatform,
        val device: CLDevice,
        val vendor: String,
        val name: String,
        val computeUnits: Int,
        val mhz: Int,
        val unifiedKnown: Boolean,
        val unifiedMemory: Boolean
    ) {
        fun selection(): Selection {
            val suffix = if (unifiedKnown) {
                if (unifiedMemory) ", unified-memory" else ", discrete-memory"
            } else ""
            return Selection(platform, device, "$vendor $name ($computeUnits CU @ $mhz MHz$suffix)")
        }
    }

    private class Discovery(val candidates: List<Candidate>)
}
