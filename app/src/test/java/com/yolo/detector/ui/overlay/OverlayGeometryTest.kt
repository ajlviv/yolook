package com.yolo.detector.ui.overlay

import com.yolo.detector.ui.overlay.OverlayGeometry.ContentRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    @Test
    fun portraitPhone_sideCropped() {
        // 720×1280 analysis frame (aspect 0.5625) shown on a 1080×2400 view (aspect 0.45):
        // the frame is relatively wider, fills the height and overflows the sides.
        val rect = OverlayGeometry.fillCenterContentRect(viewW = 1080f, viewH = 2400f, frameAspect = 0.5625f)!!

        assertEquals(0f, rect.top, 1e-3f)
        assertEquals(2400f, rect.height, 1e-3f)
        assertEquals(2400f * 0.5625f, rect.width, 1e-3f)
        // Centered: content [left,right] = [-135, 1215] → visible window [0, 1080]
        // corresponds to the central 80% of the frame width.
        assertEquals(-(135f), rect.left, 1e-3f)
        assertEquals(1080f + 135f, rect.right, 1e-3f)
    }

    @Test
    fun landscapeScreen_topBottomCropped() {
        // 1280×720 frame (aspect 1.7778) on a 2400×1080 view (aspect 2.222):
        // the frame fills the width and overflows top/bottom.
        val rect = OverlayGeometry.fillCenterContentRect(viewW = 2400f, viewH = 1080f, frameAspect = 1280f / 720f)!!

        assertEquals(0f, rect.left, 1e-3f)
        assertEquals(2400f, rect.width, 1e-3f)
        assertEquals(2400f / (1280f / 720f), rect.height, 1e-3f)
        assertTrue(rect.top < 0f)
        assertTrue(rect.bottom > 1080f)
    }

    @Test
    fun matchingAspects_noCrop() {
        assertNull(OverlayGeometry.fillCenterContentRect(1080f, 2400f, 1080f / 2400f))
    }

    @Test
    fun unknownAspect_noCrop() {
        assertNull(OverlayGeometry.fillCenterContentRect(1080f, 2400f, -1f))
        assertNull(OverlayGeometry.fillCenterContentRect(1080f, 2400f, 0f))
        assertNull(OverlayGeometry.fillCenterContentRect(1080f, 2400f, Float.NaN))
    }

    @Test
    fun degenerateView_noCrop() {
        assertNull(OverlayGeometry.fillCenterContentRect(0f, 2400f, 0.5f))
        assertNull(OverlayGeometry.fillCenterContentRect(1080f, 0f, 0.5f))
    }

    @Test
    fun squareFrameOnTallView_sideCroppedSymmetric() {
        // Square view and frame: no crop.
        assertNull(OverlayGeometry.fillCenterContentRect(1000f, 1000f, 1f))

        // Wide frame on a square view: sides cropped, content centered.
        val rect = OverlayGeometry.fillCenterContentRect(1000f, 1000f, frameAspect = 2f)!!
        assertEquals(-500f, rect.left, 1e-3f)
        assertEquals(1500f, rect.right, 1e-3f)
        assertEquals(0f, rect.top, 1e-3f)
        assertEquals(1000f, rect.bottom, 1e-3f)
    }

    @Test
    fun mapping_framesInsideWindow_landOnVisibleSegment() {
        // Same scenario as portraitPhone_sideCropped: only the central 80% of the
        // frame width is visible, so a box at x∈[0,1] must land at content.left + x*width.
        val rect = OverlayGeometry.fillCenterContentRect(1080f, 2400f, 0.5625f)!!
        val visibleFraction = 1080f / rect.width
        assertEquals(0.8f, visibleFraction, 1e-3f)

        // x = 0.1 and 0.9 map exactly to the left/right view edges.
        assertEquals(0f, rect.left + 0.1f * rect.width, 1e-3f)
        assertEquals(1080f, rect.left + 0.9f * rect.width, 1e-3f)
    }
}