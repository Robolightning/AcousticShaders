package dev.acoustic.core.compute

import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide registry of optional platform compute backends. */
object FdtdBackendRegistry {
    private val backends = CopyOnWriteArrayList<FdtdExternalBackend>()
    @JvmStatic fun register(backend: FdtdExternalBackend) { if (backends.none { it.id().equals(backend.id(), true) }) backends.add(backend) }
    @JvmStatic fun all(): List<FdtdExternalBackend> = immutableSorted(ArrayList(backends))
    @JvmStatic fun available(): List<FdtdExternalBackend> {
        val out = ArrayList<FdtdExternalBackend>()
        for (backend in backends) try { if (backend.available()) out.add(backend) } catch (_: Throwable) {}
        return immutableSorted(out)
    }
    @JvmStatic fun find(id: String?): FdtdExternalBackend? {
        if (id == null) return null
        for (backend in backends) if (backend.id().equals(id, true)) return try { if (backend.available()) backend else null } catch (_: Throwable) { null }
        return null
    }
    @JvmStatic fun firstSupported(problem: FdtdProblem): FdtdExternalBackend? = available().firstOrNull { try { it.supports(problem) } catch (_: Throwable) { false } }
    @JvmStatic fun firstPreferred(problem: FdtdProblem): FdtdExternalBackend? = available().firstOrNull { try { it.supports(problem) && it.preferredForAuto(problem) } catch (_: Throwable) { false } }
    @JvmStatic fun hasAvailable(id: String?): Boolean = find(id) != null
    @JvmStatic fun closeAll() { for (backend in backends) try { backend.close() } catch (_: Throwable) {}; backends.clear() }
    @JvmStatic fun clearForTests() = closeAll()
    private fun immutableSorted(out: MutableList<FdtdExternalBackend>): List<FdtdExternalBackend> { out.sortWith(compareByDescending<FdtdExternalBackend> { it.autoPriority() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.id() }); return Collections.unmodifiableList(out) }
}
