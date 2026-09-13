package com.yolo.detector.inference

import android.graphics.RectF
import com.yolo.detector.data.Detection
import com.yolo.detector.data.SignType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignRecognizerTest {

    private fun box() = RectF().apply {
        left = 0.2f
        top = 0.1f
        right = 0.4f
        bottom = 0.3f
    }

    @Test
    fun testStopSignIsRecognized() {
        // class 11 = "stop sign"
        val dets = listOf(Detection(-1, 11, 0.9f, box(), 100L))
        val signs = SignRecognizer().recognize(dets)
        assertEquals(1, signs.size)
        assertEquals(SignType.STOP, signs[0].type)
    }

    @Test
    fun testPersonYieldsNoSign() {
        val dets = listOf(Detection(-1, 0, 0.9f, box(), 100L)) // person
        assertTrue(SignRecognizer().recognize(dets).isEmpty())
    }

    @Test
    fun testSignRecognizerReset() {
        val recognizer = SignRecognizer()
        assertNull(recognizer.activeSpeedLimit)
        recognizer.reset()
        assertNull(recognizer.activeSpeedLimit)
    }
}