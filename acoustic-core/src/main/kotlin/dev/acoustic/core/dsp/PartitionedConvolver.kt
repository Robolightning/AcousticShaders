package dev.acoustic.core.dsp

import java.util.Arrays

/** Uniform frequency-domain partitioned mono convolver. */
class PartitionedConvolver(impulse: FloatArray?, private val blockSize: Int) {
    private val fftSize: Int
    private val partitions: Int
    private val hRe: Array<DoubleArray>
    private val hIm: Array<DoubleArray>
    private val xReHistory: Array<DoubleArray>
    private val xImHistory: Array<DoubleArray>
    private val overlap: FloatArray
    private var historyHead = 0

    init {
        require(impulse != null && impulse.isNotEmpty()) { "impulse" }
        require(blockSize >= 16 && Integer.bitCount(blockSize) == 1) { "blockSize must be power of two >= 16" }
        fftSize = blockSize * 2
        partitions = (impulse.size + blockSize - 1) / blockSize
        hRe = Array(partitions) { DoubleArray(fftSize) }
        hIm = Array(partitions) { DoubleArray(fftSize) }
        xReHistory = Array(partitions) { DoubleArray(fftSize) }
        xImHistory = Array(partitions) { DoubleArray(fftSize) }
        overlap = FloatArray(blockSize)
        var partition = 0
        while (partition < partitions) {
            var i = 0
            while (i < blockSize) {
                val index = partition * blockSize + i
                if (index < impulse.size) hRe[partition][i] = impulse[index].toDouble()
                i++
            }
            Radix2Fft.transform(hRe[partition], hIm[partition], false)
            partition++
        }
    }

    fun blockSize(): Int = blockSize

    fun reset() {
        var partition = 0
        while (partition < partitions) {
            Arrays.fill(xReHistory[partition], 0.0)
            Arrays.fill(xImHistory[partition], 0.0)
            partition++
        }
        Arrays.fill(overlap, 0f)
        historyHead = 0
    }

    /** Process exactly blockSize samples and return exactly blockSize samples. */
    fun process(input: FloatArray?): FloatArray {
        require(input != null && input.size == blockSize) { "input block length" }
        val xr = xReHistory[historyHead]
        val xi = xImHistory[historyHead]
        Arrays.fill(xr, 0.0); Arrays.fill(xi, 0.0)
        var i = 0
        while (i < blockSize) { xr[i] = input[i].toDouble(); i++ }
        Radix2Fft.transform(xr, xi, false)
        val yr = DoubleArray(fftSize)
        val yi = DoubleArray(fftSize)
        var partition = 0
        while (partition < partitions) {
            var history = historyHead - partition
            if (history < 0) history += partitions
            val pr = xReHistory[history]; val pi = xImHistory[history]
            val hr = hRe[partition]; val hi = hIm[partition]
            var k = 0
            while (k < fftSize) {
                yr[k] += pr[k] * hr[k] - pi[k] * hi[k]
                yi[k] += pr[k] * hi[k] + pi[k] * hr[k]
                k++
            }
            partition++
        }
        Radix2Fft.transform(yr, yi, true)
        val out = FloatArray(blockSize)
        i = 0
        while (i < blockSize) {
            out[i] = yr[i].toFloat() + overlap[i]
            overlap[i] = yr[i + blockSize].toFloat()
            i++
        }
        historyHead++
        if (historyHead == partitions) historyHead = 0
        return out
    }

    /** Convenience offline path used by conformance tests. */
    fun processAll(input: FloatArray): FloatArray {
        val blocks = (input.size + blockSize - 1) / blockSize
        val tailBlocks = partitions + 1
        val result = FloatArray((blocks + tailBlocks) * blockSize)
        var outputOffset = 0
        var block = 0
        while (block < blocks + tailBlocks) {
            val inputBlock = FloatArray(blockSize)
            if (block < blocks) {
                val count = Math.min(blockSize, input.size - block * blockSize)
                System.arraycopy(input, block * blockSize, inputBlock, 0, count)
            }
            val output = process(inputBlock)
            System.arraycopy(output, 0, result, outputOffset, blockSize)
            outputOffset += blockSize
            block++
        }
        val wanted = input.size + partitions * blockSize - 1
        return Arrays.copyOf(result, Math.min(wanted, result.size))
    }
}
