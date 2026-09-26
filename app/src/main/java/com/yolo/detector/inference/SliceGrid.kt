package com.yolo.detector.inference

/**
 * Tile geometry for SAHI-style sliced inference.
 *
 * Mirrors how the Python `sahi` library slices a frame before hyper-inference:
 * the frame is divided into [tileSize]×[tileSize] pixels tiles that step by
 * `tileSize * (1 - overlapRatio)`, with the final row/column pinned so every
 * pixel is covered exactly once and seam objects stay intact thanks to the
 * overlap between neighbouring tiles.
 */
object SliceGrid {

    /** A tile rect in source-frame pixel coordinates. */
    data class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isNotEmpty: Boolean get() = width > 0 && height > 0
    }

    /** A [0, 1]-normalised box, used for mapping tile-local detections back to the frame. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Computes the tiles needed to cover [frameWidth]×[frameHeight] with
     * [tileSize]×[tileSize] crops stepped by [overlapRatio].
     *
     * A single tile is returned when the frame fits entirely in one crop (so
     * sliced inference degrades gracefully to plain inference).
     */
    fun compute(frameWidth: Int, frameHeight: Int, tileSize: Int, overlapRatio: Float): List<Tile> {
        require(tileSize > 0) { "tileSize must be positive" }
        require(overlapRatio in 0f..0.9f) { "overlapRatio must be in [0, 0.9]" }
        if (frameWidth <= 0 || frameHeight <= 0) return emptyList()

        val cols = startPoints(frameWidth, tileSize, overlapRatio)
        val rows = startPoints(frameHeight, tileSize, overlapRatio)

        val tiles = ArrayList<Tile>(cols.size * rows.size)
        for (row in rows) {
            for (col in cols) {
                tiles.add(
                    Tile(
                        left = col,
                        top = row,
                        right = minOf(col + tileSize, frameWidth),
                        bottom = minOf(row + tileSize, frameHeight),
                    )
                )
            }
        }
        return tiles
    }

    /**
     * Maps a tile-local box (normalised to the [tileSize] crop, i.e. the model-sized
     * input) back onto normalized frame coordinates.
     */
    fun mapToFrame(tile: Tile, frameWidth: Int, frameHeight: Int, box: Box): Box {
        val scaleX = tile.width.toFloat() / frameWidth
        val scaleY = tile.height.toFloat() / frameHeight
        return Box(
            left = tile.left / frameWidth.toFloat() + box.left * scaleX,
            top = tile.top / frameHeight.toFloat() + box.top * scaleY,
            right = tile.left / frameWidth.toFloat() + box.right * scaleX,
            bottom = tile.top / frameHeight.toFloat() + box.bottom * scaleY,
        )
    }

    private fun startPoints(length: Int, tileSize: Int, overlapRatio: Float): List<Int> {
        if (length <= tileSize) return listOf(0)
        val step = maxOf(1, (tileSize * (1f - overlapRatio)).toInt())
        val starts = ArrayList<Int>()
        var pos = 0
        while (pos + tileSize < length) {
            starts.add(pos)
            pos += step
        }
        // Pin the final slice so the far edge is covered even when the last
        // step would otherwise land short.
        val last = starts.lastOrNull() ?: 0
        if (last + tileSize < length) starts.add(length - tileSize)
        return starts
    }
}