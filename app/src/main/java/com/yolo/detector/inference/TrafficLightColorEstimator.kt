package com.yolo.detector.inference

import com.yolo.detector.data.TrafficLightSignal

/**
 * Classifies the lit lamp of a traffic light from a downsampled sample of its
 * bounding-box pixels.
 *
 * The COCO model only emits class 9 "traffic light" as a plain bounding box with no
 * signal (red/yellow/green) information, so we recover the signal from pixel color.
 * Each ARGB pixel is tested against HSV-style thresholds (derived here from RGB
 * ratios for speed). We count vividly saturated pixels that fall into the red,
 * yellow, or green hue buckets and pick the bucket with the most votes.
 *
 * This is intentionally a pure function of the sampled [IntArray] so it is easy to
 * unit test with synthetic pixel arrays. Production callers downsample the real
 * frame's bbox region and pass the packed ARGB pixels here.
 */
object TrafficLightColorEstimator {

    private const val MIN_VIVIDNESS = 90       // minimum (max - min) channel spread to count a pixel as "lit"
    private const val MIN_BRIGHTNESS = 120     // minimum channel for a lit pixel

    /** Classifies the dominant lit color among [pixels] (packed 0xAARRGGBB). */
    fun classify(pixels: IntArray): TrafficLightSignal {
        var red = 0
        var yellow = 0
        var green = 0

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            val vivid = maxC - minC
            if (vivid < MIN_VIVIDNESS || maxC < MIN_BRIGHTNESS) continue

            when {
                // Red: strongly red-dominant, quite blue-poor.
                r >= 130 && r > g * 1.4f && r > b * 1.6f -> red++
                // Green vs yellow: both have high G; decide by R/B balance.
                g >= 110 && r > b * 1.2f && g > b * 1.2f -> yellow++
                g >= 110 && g >= r * 1.15f && g > b * 1.3f -> green++
            }
        }

        val total = red + yellow + green
        if (total == 0) return TrafficLightSignal.OFF

        return if (red >= green && red >= yellow) {
            TrafficLightSignal.RED
        } else if (yellow > green) {
            TrafficLightSignal.YELLOW
        } else {
            TrafficLightSignal.GREEN
        }
    }
}