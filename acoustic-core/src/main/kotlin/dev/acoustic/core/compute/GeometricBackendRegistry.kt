package dev.acoustic.core.compute

import dev.acoustic.api.scene.AcousticScene
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide registry of optional geometric-ray accelerators. */
object GeometricBackendRegistry {
    private val backends = CopyOnWriteArrayList<GeometricExternalBackend>()
    @JvmStatic fun register(backend: GeometricExternalBackend) { if (backends.none { it.id().equals(backend.id(), true) }) backends.add(backend) }
    @JvmStatic fun all(): List<GeometricExternalBackend> = immutableSorted(ArrayList(backends))
    @JvmStatic fun available(): List<GeometricExternalBackend> {
        val out = ArrayList<GeometricExternalBackend>(); for (backend in backends) try { if (backend.available()) out.add(backend) } catch (_: Throwable) {}; return immutableSorted(out)
    }
    @JvmStatic fun find(id: String?): GeometricExternalBackend? { if (id == null) return null; for (backend in backends) if (backend.id().equals(id, true)) return try { if (backend.available()) backend else null } catch (_: Throwable) { null }; return null }
    @JvmStatic fun firstSupported(scene: AcousticScene, rays: Int, bounces: Int, distance: Double, minEnergy: Double): GeometricExternalBackend? = available().firstOrNull { try { it.supports(scene,rays,bounces,distance,minEnergy) } catch (_: Throwable) { false } }
    @JvmStatic fun firstPreferred(scene: AcousticScene, rays: Int, bounces: Int, distance: Double, minEnergy: Double): GeometricExternalBackend? = available().firstOrNull { try { it.supports(scene,rays,bounces,distance,minEnergy) && it.preferredForAuto(scene,rays,bounces,distance,minEnergy) } catch (_: Throwable) { false } }
    @JvmStatic fun hasAvailable(id: String?): Boolean = find(id) != null
    @JvmStatic fun closeAll() { for (backend in backends) try { backend.close() } catch (_: Throwable) {}; backends.clear() }
    @JvmStatic fun clearForTests() = closeAll()
    private fun immutableSorted(out: MutableList<GeometricExternalBackend>): List<GeometricExternalBackend> { out.sortWith(compareByDescending<GeometricExternalBackend> { it.autoPriority() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.id() }); return Collections.unmodifiableList(out) }
}
