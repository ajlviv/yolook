package com.yolo.detector.inference

import com.yolo.detector.data.TrafficLightSignal
import java.util.ArrayList

/**
 * Classifies the lit lamp of a traffic light from a downsampled sample of its
 * bounding-box pixels with spatial 3-bulb slot geometry verification.
 *
 * Checks physical slot consistency:
 * - Red is expected in the top slot (or left slot for horizontal lights).
 * - Yellow / Amber in the middle slot.
 * - Green in the bottom slot (or right slot for horizontal lights).
 */
object TrafficLightColorEstimator {

    /** Column count of the downsampled grid; must match DriverSceneBuilder.sampleRegion. */
    private const val SAMPLE_COLS = 10

    private const val MIN_VIVIDNESS = 90        // min (max - min) channel spread to count a pixel as "lit"
    private const val MIN_BRIGHTNESS = 120      // min channel value for a lit pixel
    private const val MIN_BLOB_PIXELS = 3       // min contiguous samples a lamp blob must have
    private const val MIN_BLOB_ENERGY = 30000f  // min energy (vividness*brightness) a blob must average
    private const val MARGIN = 1.25f            // winner must beat the runner-up by this ratio

    private const val NONE = 0
    private const val HUE_RED = 1
    private const val HUE_YELLOW = 2
    private const val HUE_GREEN = 3

    private data class Blob(
        val hue: Int,
        val size: Int,
        val energy: Float,
        val centerRow: Float,
        val centerCol: Float,
    )

    /** Classifies the dominant lit color among [pixels] (packed 0xAARRGGBB). */
    fun classify(pixels: IntArray): TrafficLightSignal {
        if (pixels.isEmpty()) return TrafficLightSignal.OFF

        val cols = SAMPLE_COLS
        val rows = (pixels.size + cols - 1) / cols

        val hue = IntArray(pixels.size)
        val energy = FloatArray(pixels.size)
        var anyLit = false
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            hue[i] = pixelHue(r, g, b)
            energy[i] = litEnergy(r, g, b)
            if (hue[i] != NONE) anyLit = true
        }

        if (!anyLit) {
            return TrafficLightSignal.OFF
        }

        val blobs = findBlobs(pixels, hue, energy, rows, cols).filter {
            it.size >= MIN_BLOB_PIXELS && it.energy / it.size >= MIN_BLOB_ENERGY
        }

        if (blobs.isEmpty()) {
            return TrafficLightSignal.UNKNOWN
        }

        // Apply spatial slot validation score bonus/penalty
        val scoredBlobs = blobs.map { blob ->
            val spatialBonus = calculateSpatialSlotWeight(blob, rows, cols)
            blob to (blob.energy * spatialBonus)
        }

        // Winner: largest weighted energy
        var winner = scoredBlobs[0]
        for (b in scoredBlobs) {
            if (b.second > winner.second) winner = b
        }

        var runnerUp: Pair<Blob, Float>? = null
        for (b in scoredBlobs) {
            if (b.first === winner.first) continue
            if (runnerUp == null || b.second > runnerUp.second) runnerUp = b
        }

        if (runnerUp != null && winner.second < runnerUp.second * MARGIN) {
            return TrafficLightSignal.UNKNOWN
        }

        return when (winner.first.hue) {
            HUE_RED -> TrafficLightSignal.RED
            HUE_YELLOW -> TrafficLightSignal.YELLOW
            HUE_GREEN -> TrafficLightSignal.GREEN
            else -> TrafficLightSignal.UNKNOWN
        }
    }

    /**
     * Calculates spatial compatibility weight based on bulb position in the housing.
     * In vertical lights: Red at top (rows < 0.55), Green at bottom (rows > 0.45).
     */
    private fun calculateSpatialSlotWeight(blob: Blob, rows: Int, cols: Int): Float {
        val normRow = blob.centerRow / rows.toFloat()
        return when (blob.hue) {
            HUE_RED -> if (normRow <= 0.55f) 1.2f else 0.6f
            HUE_GREEN -> if (normRow >= 0.45f) 1.2f else 0.6f
            HUE_YELLOW -> if (normRow in 0.25f..0.75f) 1.1f else 0.8f
            else -> 1.0f
        }
    }

    /** Maps an ARGB pixel to a hue bucket ([NONE] if not brightly saturated). */
    private fun pixelHue(r: Int, g: Int, b: Int): Int {
        val maxC = maxOf(r, maxOf(g, b))
        val minC = minOf(r, minOf(g, b))
        if (maxC - minC < MIN_VIVIDNESS || maxC < MIN_BRIGHTNESS) return NONE
        // RED: red clearly dominates.
        if (r >= maxC * 0.95 && r > g * 1.35f && r > b * 1.55f) return HUE_RED
        // GREEN: green dominates and is clearly brighter than red
        if (g >= maxC * 0.95 && g > r * 1.25f && g > b * 1.35f) return HUE_GREEN
        // YELLOW/AMBER: red and green are both high and close (R ≈ G), blue far behind.
        if (g >= 110 && g > b * 1.4f && r > b * 1.4f && r >= g * 0.75f && r <= g * 1.35f) return HUE_YELLOW
        return NONE
    }

    private fun litEnergy(r: Int, g: Int, b: Int): Float {
        val maxC = maxOf(r, maxOf(g, b))
        val minC = minOf(r, minOf(g, b))
        return ((maxC - minC) * maxC).toFloat()
    }

    /** Joins same-hue 4-connected samples into [Blob]s with center coordinates. */
    private fun findBlobs(
        pixels: IntArray,
        hue: IntArray,
        energy: FloatArray,
        rows: Int,
        cols: Int,
    ): List<Blob> {
        val visited = IntArray(pixels.size)
        val result = ArrayList<Blob>()
        for (start in pixels.indices) {
            if (visited[start] != 0 || hue[start] == NONE) continue
            val h = hue[start]
            var size = 0
            var totalEnergy = 0f
            var rowSum = 0f
            var colSum = 0f
            val frontier = ArrayList<Int>()
            frontier.add(start)
            visited[start] = 1
            while (frontier.isNotEmpty()) {
                val idx = frontier.removeAt(frontier.size - 1)
                size++
                totalEnergy += energy[idx]
                val r = idx / cols
                val c = idx % cols
                rowSum += r
                colSum += c
                pushIf(frontier, visited, hue, h, r - 1, c, rows, cols)
                pushIf(frontier, visited, hue, h, r + 1, c, rows, cols)
                pushIf(frontier, visited, hue, h, r, c - 1, rows, cols)
                pushIf(frontier, visited, hue, h, r, c + 1, rows, cols)
            }
            val centerR = if (size > 0) rowSum / size else 0f
            val centerC = if (size > 0) colSum / size else 0f
            result.add(Blob(h, size, totalEnergy, centerR, centerC))
        }
        return result
    }

    private fun pushIf(
        frontier: ArrayList<Int>,
        visited: IntArray,
        hue: IntArray,
        targetHue: Int,
        r: Int,
        c: Int,
        rows: Int,
        cols: Int,
    ) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) return
        val idx = r * cols + c
        if (visited[idx] != 0 || hue[idx] != targetHue) return
        visited[idx] = 1
        frontier.add(idx)
    }
}