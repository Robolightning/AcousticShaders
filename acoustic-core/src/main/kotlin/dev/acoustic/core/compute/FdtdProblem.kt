package dev.acoustic.core.compute

/** Immutable input for a scalar 3-D acoustic FDTD solve. Arrays are read-only by contract. */
class FdtdProblem(
    private val nx: Int, private val ny: Int, private val nz: Int,
    private val maxSteps: Int, private val sourceIndex: Int, private val listenerIndex: Int,
    private val lambda: Float, private val airDamping: Float,
    private val solid: IntArray, private val wallReflection: FloatArray,
    private val lambdaByCell: FloatArray?, private val dampingByCell: FloatArray?, private val densityByCell: FloatArray?
) {
    constructor(
        nx: Int, ny: Int, nz: Int,
        maxSteps: Int, sourceIndex: Int, listenerIndex: Int,
        lambda: Float, airDamping: Float,
        solid: IntArray, wallReflection: FloatArray
    ) : this(nx, ny, nz, maxSteps, sourceIndex, listenerIndex, lambda, airDamping, solid, wallReflection, null, null, null)

    init {
        require(nx >= 3 && ny >= 3 && nz >= 3 && maxSteps >= 1) { "invalid FDTD dimensions" }
        val cells = nx * ny * nz
        require(sourceIndex in 0 until cells && listenerIndex in 0 until cells) { "invalid source/listener index" }
        require(solid.size == cells && wallReflection.size == cells) { "FDTD array size mismatch" }
        require(lambda >= 0f && lambda.isFinite() && airDamping >= 0f && airDamping.isFinite()) { "invalid FDTD coefficients" }
        val heterogeneousArrays = listOf(lambdaByCell, dampingByCell, densityByCell)
        val present = heterogeneousArrays.count { it != null }
        require(present == 0 || present == heterogeneousArrays.size) { "heterogeneous FDTD arrays must be supplied together" }
        if (present != 0) {
            val localLambda = lambdaByCell!!
            val localDamping = dampingByCell!!
            val localDensity = densityByCell!!
            require(localLambda.size == cells && localDamping.size == cells && localDensity.size == cells) { "heterogeneous FDTD array size mismatch" }
            var i = 0
            while (i < cells) {
                require(localLambda[i] >= 0f && localLambda[i].isFinite()) { "invalid local FDTD lambda" }
                require(localDamping[i] >= 0f && localDamping[i].isFinite()) { "invalid local FDTD damping" }
                require(localDensity[i] > 0f && localDensity[i].isFinite()) { "invalid local medium density" }
                i++
            }
        }
    }

    fun nx(): Int = nx
    fun ny(): Int = ny
    fun nz(): Int = nz
    fun cells(): Int = nx * ny * nz
    fun maxSteps(): Int = maxSteps
    fun sourceIndex(): Int = sourceIndex
    fun listenerIndex(): Int = listenerIndex
    fun lambda(): Float = lambda
    fun airDamping(): Float = airDamping
    fun solid(): IntArray = solid
    fun wallReflection(): FloatArray = wallReflection
    fun heterogeneous(): Boolean = lambdaByCell != null
    fun lambdaAt(index: Int): Float = lambdaByCell?.get(index) ?: lambda
    fun dampingAt(index: Int): Float = dampingByCell?.get(index) ?: airDamping
    fun densityAt(index: Int): Float = densityByCell?.get(index) ?: 1f
    fun lambdaSpectrum(): FloatArray? = lambdaByCell?.clone()
    fun dampingSpectrum(): FloatArray? = dampingByCell?.clone()
    fun densityField(): FloatArray? = densityByCell?.clone()
}
