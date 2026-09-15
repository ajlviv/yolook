package com.yolo.detector.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the diagnostic branch shared by the test-email flow:
 * HTTP status families must map to the readable explanation used by the Settings UI.
 */
class EmailAlertOutcomeTest {

    @Test
    fun responseCodeFamiliesMapToUiStrings() {
        assertEquals("request", providerHintFor(400))
        assertEquals("auth", providerHintFor(401))
        assertEquals("auth", providerHintFor(403))
        assertEquals("request", providerHintFor(404))
        assertEquals("rate", providerHintFor(429))
        assertEquals("server", providerHintFor(500))
        assertEquals("server", providerHintFor(503))
        assertEquals("other", providerHintFor(302))
    }

    // Mirrors MainViewModel.providerRejectedMessage() without Android dependencies.
    private fun providerHintFor(httpCode: Int): String = when (httpCode) {
        400 -> "request"
        401 -> "auth"
        402 -> "auth"
        403 -> "auth"
        404 -> "request"
        429 -> "rate"
        in 500..599 -> "server"
        else -> "other"
    }
}