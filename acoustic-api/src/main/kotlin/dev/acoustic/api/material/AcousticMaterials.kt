package dev.acoustic.api.material

/** Conservative built-in fallback material prototypes. User/material-resource-pack data may override them. */
object AcousticMaterials {
    @JvmField val AIR = material("acoustic:air", 1f, 0f, 1f)
    @JvmField val STONE = AcousticMaterial("acoustic:stone", floatArrayOf(0.01f,0.01f,0.015f,0.02f,0.025f,0.03f,0.04f,0.05f), 0.10f, 0.01f)
    @JvmField val CONCRETE = AcousticMaterial("acoustic:concrete", floatArrayOf(0.01f,0.01f,0.015f,0.02f,0.025f,0.035f,0.05f,0.07f), 0.12f, 0.01f)
    @JvmField val BRICK = AcousticMaterial("acoustic:brick", floatArrayOf(0.02f,0.025f,0.03f,0.035f,0.04f,0.05f,0.07f,0.09f), 0.18f, 0.01f)
    @JvmField val CERAMIC = AcousticMaterial("acoustic:ceramic", floatArrayOf(0.015f,0.015f,0.02f,0.025f,0.03f,0.04f,0.05f,0.06f), 0.08f, 0.01f)
    @JvmField val PLASTER = AcousticMaterial("acoustic:plaster", floatArrayOf(0.04f,0.05f,0.06f,0.07f,0.09f,0.12f,0.16f,0.20f), 0.18f, 0.03f)
    @JvmField val WOOD = AcousticMaterial("acoustic:wood", floatArrayOf(0.10f,0.11f,0.10f,0.08f,0.08f,0.07f,0.06f,0.06f), 0.20f, 0.04f)
    @JvmField val WOOL = AcousticMaterial("acoustic:wool", floatArrayOf(0.08f,0.20f,0.45f,0.65f,0.80f,0.90f,0.94f,0.95f), 0.55f, 0.03f)
    @JvmField val CARPET = AcousticMaterial("acoustic:carpet", floatArrayOf(0.04f,0.08f,0.20f,0.38f,0.58f,0.72f,0.82f,0.88f), 0.50f, 0.02f)
    @JvmField val GLASS = AcousticMaterial("acoustic:glass", floatArrayOf(0.18f,0.08f,0.05f,0.04f,0.03f,0.03f,0.02f,0.02f), 0.05f, 0.18f)
    @JvmField val METAL = AcousticMaterial("acoustic:metal", floatArrayOf(0.02f,0.02f,0.02f,0.03f,0.03f,0.04f,0.04f,0.05f), 0.05f, 0.01f)
    @JvmField val POLYMER = AcousticMaterial("acoustic:polymer", floatArrayOf(0.05f,0.06f,0.08f,0.10f,0.13f,0.17f,0.22f,0.28f), 0.18f, 0.08f)
    @JvmField val RUBBER = AcousticMaterial("acoustic:rubber", floatArrayOf(0.08f,0.10f,0.14f,0.20f,0.28f,0.38f,0.48f,0.56f), 0.28f, 0.04f)
    @JvmField val SOIL = AcousticMaterial("acoustic:soil", floatArrayOf(0.10f,0.18f,0.28f,0.42f,0.52f,0.62f,0.70f,0.76f), 0.45f, 0.03f)
    @JvmField val SAND = AcousticMaterial("acoustic:sand", floatArrayOf(0.15f,0.25f,0.38f,0.50f,0.60f,0.68f,0.74f,0.79f), 0.52f, 0.04f)
    @JvmField val FOLIAGE = AcousticMaterial("acoustic:foliage", floatArrayOf(0.12f,0.22f,0.38f,0.55f,0.70f,0.80f,0.86f,0.90f), 0.75f, 0.18f)
    @JvmField val LIQUID = AcousticMaterial("acoustic:liquid", floatArrayOf(0.04f,0.05f,0.07f,0.10f,0.18f,0.30f,0.45f,0.58f), 0.08f, 0.58f)

    private fun material(id: String, absorption: Float, scattering: Float, transmission: Float): AcousticMaterial {
        val spectrum = FloatArray(FrequencyBands.COUNT) { absorption }
        return AcousticMaterial(id, spectrum, scattering, transmission)
    }
}
