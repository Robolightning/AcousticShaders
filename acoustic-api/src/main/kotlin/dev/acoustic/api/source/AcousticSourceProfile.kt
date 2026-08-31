package dev.acoustic.api.source

import dev.acoustic.api.material.FrequencyBands

class AcousticSourceProfile(
    id: String, category: String, emissionSpectrum: FloatArray,
    directScale: Float, occlusionScale: Float, diffractionScale: Float,
    earlyScale: Float, lateScale: Float, priorityScale: Float,
    movementSensitivity: Float, dopplerScale: Float, transientScale: Float,
    bypassAcoustics: Boolean
) {
    private val id = required(id, "id")
    private val category = required(category, "category")
    private val emissionSpectrum: FloatArray
    private val directScale: Float
    private val occlusionScale: Float
    private val diffractionScale: Float
    private val earlyScale: Float
    private val lateScale: Float
    private val priorityScale: Float
    private val movementSensitivity: Float
    private val dopplerScale: Float
    private val transientScale: Float
    private val bypassAcoustics: Boolean
    init {
        require(emissionSpectrum.size == FrequencyBands.COUNT) { "emission spectrum requires ${FrequencyBands.COUNT} bands" }
        this.emissionSpectrum = emissionSpectrum.clone()
        for (i in this.emissionSpectrum.indices) this.emissionSpectrum[i] = range(this.emissionSpectrum[i], 0f, 4f, "emission[$i]")
        this.directScale = range(directScale,0f,4f,"directScale")
        this.occlusionScale = range(occlusionScale,0.1f,4f,"occlusionScale")
        this.diffractionScale = range(diffractionScale,0f,4f,"diffractionScale")
        this.earlyScale = range(earlyScale,0f,4f,"earlyScale")
        this.lateScale = range(lateScale,0f,4f,"lateScale")
        this.priorityScale = range(priorityScale,0.1f,8f,"priorityScale")
        this.movementSensitivity = range(movementSensitivity,0.1f,8f,"movementSensitivity")
        this.dopplerScale = range(dopplerScale,0f,4f,"dopplerScale")
        this.transientScale = range(transientScale,0f,4f,"transientScale")
        this.bypassAcoustics = bypassAcoustics
    }
    fun id(): String = id
    fun category(): String = category
    fun emissionSpectrum(): FloatArray = emissionSpectrum.clone()
    fun emission(band: Int): Float = emissionSpectrum[band]
    fun directScale(): Float = directScale
    fun occlusionScale(): Float = occlusionScale
    fun diffractionScale(): Float = diffractionScale
    fun earlyScale(): Float = earlyScale
    fun lateScale(): Float = lateScale
    fun priorityScale(): Float = priorityScale
    fun movementSensitivity(): Float = movementSensitivity
    fun dopplerScale(): Float = dopplerScale
    fun transientScale(): Float = transientScale
    fun bypassAcoustics(): Boolean = bypassAcoustics
    fun blend(strength: Float): AcousticSourceProfile {
        val s = strength.coerceIn(0f, 1f)
        if (s >= 0.9999f) return this
        if (s <= 0.0001f) return GENERIC
        val spectrum = FloatArray(FrequencyBands.COUNT) { i -> lerp(1f, emissionSpectrum[i], s) }
        return AcousticSourceProfile(id, category, spectrum, lerp(1f,directScale,s), lerp(1f,occlusionScale,s), lerp(1f,diffractionScale,s), lerp(1f,earlyScale,s), lerp(1f,lateScale,s), lerp(1f,priorityScale,s), lerp(1f,movementSensitivity,s), lerp(0f,dopplerScale,s), lerp(1f,transientScale,s), bypassAcoustics && s >= 0.5f)
    }
    companion object {
        @JvmField val GENERIC = AcousticSourceProfile("acoustic:generic","generic",FloatArray(FrequencyBands.COUNT){1f},1f,1f,1f,1f,1f,1f,1f,1f,0f,false)
        private fun required(s: String, name: String): String = s.trim().also { require(it.isNotEmpty()) { "$name must not be empty" } }
        private fun range(v: Float, lo: Float, hi: Float, name: String): Float { if (v.isNaN() || v < lo || v > hi) throw IllegalArgumentException("$name out of range [$lo,$hi]: $v"); return v }
        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}
