package com.yolo.detector.inference

import com.yolo.detector.inference.SliceGrid.Box
import com.yolo.detector.inference.SliceGrid.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceGridTest {

    @Test
    fun compute_1280x720_withOverlap_yieldsSixTilesAndFullCoverage() {
        val tiles = SliceGrid.compute(frameWidth = 1280, frameHeight = 720, tileSize = 640, overlapRatio = 0.2f)

        assertEquals(6, tiles.size)
        // Width axis: 3 columns cover [0..640), [512..1152), [640..1280).
        assertEquals(3, tiles.map { it.left }.distinct().size)
        // Height axis: 2 rows cover [0..640), [80..720).
        assertEquals(2, tiles.map { it.top }.distinct().size)

        assertEquals(0, tiles.minOf { it.left })
        assertEquals(0, tiles.minOf { it.top })
        assertEquals(1280, tiles.maxOf { it.right })
        assertEquals(720, tiles.maxOf { it.bottom })
        assertTrue(tiles.all { it.isNotEmpty })
    }

    @Test
    fun compute_frameSmallerThanTile_returnsSingleTileCoveringFrame() {
        val tiles = SliceGrid.compute(frameWidth = 480, frameHeight = 360, tileSize = 640, overlapRatio = 0.2f)

        assertEquals(1, tiles.size)
        assertEquals(Tile(0, 0, 480, 360), tiles.single())
    }

    @Test
    fun compute_consecutiveTilesOverlap() {
        val tiles = SliceGrid.compute(frameWidth = 1280, frameHeight = 720, tileSize = 640, overlapRatio = 0.2f)
        val cols = tiles.map { it.left }.distinct().sorted()

        for (i in 0 until cols.size - 1) {
            assertTrue(
                "Tile ${cols[i]} must extend into the next tile",
                cols[i] + 640 > cols[i + 1],
            )
        }
    }

    @Test
    fun compute_zeroOverlap_splitsExactly() {
        val tiles = SliceGrid.compute(frameWidth = 1280, frameHeight = 720, tileSize = 640, overlapRatio = 0f)

        assertEquals(2, tiles.distinctBy { it.left }.size)
        assertEquals(2, tiles.distinctBy { it.top }.size)
    }

    @Test
    fun compute_rejectsInvalidOverlap() {
        try {
            SliceGrid.compute(1280, 720, 640, overlapRatio = 1.1f)
        } catch (_: IllegalArgumentException) {
            return
        }
        error("Expected IllegalArgumentException for overlapRatio > 0.9")
    }

    @Test
    fun mapToFrame_mapsTileLocalBoxBackToFrameCoordinates() {
        // Left-bottom tile of a 1280×720 frame: pixels [640, 1280) × [360, 720).
        val tile = Tile(left = 640, top = 360, right = 1280, bottom = 720)

        // A full-tile box should map to the whole right-bottom quadrant.
        val fullBox = SliceGrid.mapToFrame(tile, 1280, 720, Box(0f, 0f, 1f, 1f))
        assertEquals(0.5f, fullBox.left, 1e-5f)
        assertEquals(0.5f, fullBox.top, 1e-5f)
        assertEquals(1f, fullBox.right, 1e-5f)
        assertEquals(1f, fullBox.bottom, 1e-5f)

        // Upper-left quarter of the tile → a quarter of the quadrant.
        val quarter = SliceGrid.mapToFrame(tile, 1280, 720, Box(0f, 0f, 0.5f, 0.5f))
        assertEquals(0.5f, quarter.left, 1e-5f)
        assertEquals(0.5f, quarter.top, 1e-5f)
        assertEquals(0.75f, quarter.right, 1e-5f)
        assertEquals(0.75f, quarter.bottom, 1e-5f)
    }

    @Test
    fun compute_andMapToFrame_pinnedEdgesStayFullSized() {
        // Odd 1000×1000 frame: the final column/row start is pinned to 1000-640=360,
        // so every tile is exactly 640×640 (never a short, stretched strip) and the
        // far edges are still covered exactly once.
        val tiles = SliceGrid.compute(frameWidth = 1000, frameHeight = 1000, tileSize = 640, overlapRatio = 0.2f)

        assertEquals(4, tiles.size)
        assertEquals(0, tiles.minOf { it.left })
        assertEquals(0, tiles.minOf { it.top })
        assertEquals(1000, tiles.maxOf { it.right })
        assertEquals(1000, tiles.maxOf { it.bottom })
        assertTrue(tiles.all { it.width == 640 && it.height == 640 })
        assertEquals(listOf(0, 360), tiles.map { it.left }.distinct().sorted())
        assertEquals(listOf(0, 360), tiles.map { it.top }.distinct().sorted())

        // Bottom-right tile: [360, 1000) × [360, 1000).
        val tile = Tile(left = 360, top = 360, right = 1000, bottom = 1000)
        val full = SliceGrid.mapToFrame(tile, 1000, 1000, Box(0f, 0f, 1f, 1f))
        assertEquals(0.36f, full.left, 1e-5f)
        assertEquals(0.36f, full.top, 1e-5f)
        assertEquals(1f, full.right, 1e-5f)
        assertEquals(1f, full.bottom, 1e-5f)

        val quarter = SliceGrid.mapToFrame(tile, 1000, 1000, Box(0f, 0f, 0.5f, 0.5f))
        assertEquals(0.36f, quarter.left, 1e-5f)
        assertEquals(0.36f, quarter.top, 1e-5f)
        assertEquals(0.36f + 0.32f, quarter.right, 1e-5f)
        assertEquals(0.36f + 0.32f, quarter.bottom, 1e-5f)
    }
}