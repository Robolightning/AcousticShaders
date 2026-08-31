package dev.acoustic.platform

class SceneCaptureRequest(private val minX: Int, private val minY: Int, private val minZ: Int, private val sizeX: Int, private val sizeY: Int, private val sizeZ: Int) {
    init { require(sizeX > 0 && sizeY > 0 && sizeZ > 0) { "capture dimensions must be positive" } }
    fun minX(): Int = minX
    fun minY(): Int = minY
    fun minZ(): Int = minZ
    fun sizeX(): Int = sizeX
    fun sizeY(): Int = sizeY
    fun sizeZ(): Int = sizeZ
}
