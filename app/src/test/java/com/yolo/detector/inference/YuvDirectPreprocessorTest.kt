package com.yolo.detector.inference

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class YuvDirectPreprocessorTest {

    @Test
    fun preprocessor_allocatesZeroDuringRepeatedRuns() {
        val preprocessor = YuvDirectPreprocessor(640, 640)
        
        assertNotNull(preprocessor.floatArray)
        assertEquals(640 * 640 * 3, preprocessor.floatArray.size)
        assertEquals(640 * 640 * 3, preprocessor.byteArray.size)
    }

    @Test
    fun preprocessor_extractScaledPixels_correctDimensions() {
        val preprocessor = YuvDirectPreprocessor(10, 10)
        assertEquals(10 * 10 * 3, preprocessor.floatArray.size)
    }
}
