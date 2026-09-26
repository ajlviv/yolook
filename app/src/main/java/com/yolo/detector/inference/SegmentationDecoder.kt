package com.yolo.detector.inference

/**
 * A binary instance mask for a single detection, stored at the model's prototype
 * resolution and mapped back to a normalised frame rectangle.
 *
 * Deliberately a plain value type over a [BooleanArray] rather than an Android
 * `Bitmap`: the decode runs on the JVM in unit tests, and keeping [Detection]
 * equality content-based means tracker/history comparisons stay meaningful.
 *
 * @param left Normalised frame coordinate (0–1) the mask's column 0 maps to.
 * @param top Normalised frame coordinate (0–1) the mask's row 0 maps to.
 * @param right Normalised frame coordinate (0–1) the mask's last column maps to.
 * @param bottom Normalised frame coordinate (0–1) the mask's last row maps to.
 *   Normally (0, 0, 1, 1); sliced inference narrows it to the source tile.
 */
class SegmentationMask(
    val width: Int,
    val height: Int,
    private val bits: BooleanArray,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(width > 0 && height > 0) { "Mask dimensions must be positive" }
        require(bits.size == width * height) {
            "Mask buffer ${bits.size} does not match ${width}x$height"
        }
    }

    /** Number of set (foreground) pixels. */
    val pixelCount: Int = bits.count { it }

    /** True when the mask has no foreground pixel, i.e. nothing worth drawing. */
    fun isEmpty(): Boolean = pixelCount == 0

    /** Whether the pixel at ([x], [y]) is foreground. */
    operator fun get(x: Int, y: Int): Boolean = bits[y * width + x]

    /** Direct access to the row-major bits, for renderers that copy them into a bitmap. */
    internal fun rawBits(): BooleanArray = bits

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentationMask) return false
        return width == other.width &&
            height == other.height &&
            left == other.left &&
            top == other.top &&
            right == other.right &&
            bottom == other.bottom &&
            bits.contentEquals(other.bits)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + bits.contentHashCode()
        result = 31 * result + left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        return result
    }

    override fun toString(): String =
        "SegmentationMask(${width}x$height, $pixelCount px, rect=[$left,$top,$right,$bottom])"
}

/**
 * Turns a YOLOE-style segmentation head output into per-detection binary masks.
 *
 * Mirrors Ultralytics `process_mask`: the mask logits are the dot product of a
 * detection's raw mask coefficients with the raw prototype maps, sigmoid-activated
 * afterwards, cropped to the detection box, and thresholded.
 *
 * Pure Kotlin so it is unit-testable without an emulator; [TfliteDetector] only
 * marshals tensors in and out.
 */
object SegmentationDecoder {

    /** Sigmoid activation applied to the coefficient/prototype dot product. */
    const val MASK_THRESHOLD = 0.5f

    fun sigmoid(x: Float): Float = 1f / (1f + kotlin.math.exp(-x))

    /**
     * Builds the binary mask for one detection.
     *
     * @param coefficients Raw mask coefficients for this detection, length [channels].
     * @param prototypes Raw prototype maps, channel-major: `channels * protoHeight * protoWidth`.
     * @param boxLeft Box left in normalised prototype space (0–1).
     * @param boxTop Box top in normalised prototype space (0–1).
     * @param boxRight Box right in normalised prototype space (0–1).
     * @param boxBottom Box bottom in normalised prototype space (0–1).
     * @param rectLeft Normalised frame coordinate the mask's column 0 maps to.
     * @param rectTop Normalised frame coordinate the mask's row 0 maps to.
     * @param rectRight Normalised frame coordinate the mask's last column maps to.
     * @param rectBottom Normalised frame coordinate the mask's last row maps to.
     */
    fun buildMask(
        coefficients: FloatArray,
        prototypes: FloatArray,
        channels: Int,
        protoWidth: Int,
        protoHeight: Int,
        boxLeft: Float,
        boxTop: Float,
        boxRight: Float,
        boxBottom: Float,
        rectLeft: Float,
        rectTop: Float,
        rectRight: Float,
        rectBottom: Float,
        threshold: Float = MASK_THRESHOLD,
    ): SegmentationMask = buildMask(
        coefficients = coefficients,
        prototypes = java.nio.FloatBuffer.wrap(prototypes),
        channels = channels,
        protoWidth = protoWidth,
        protoHeight = protoHeight,
        boxLeft = boxLeft,
        boxTop = boxTop,
        boxRight = boxRight,
        boxBottom = boxBottom,
        rectLeft = rectLeft,
        rectTop = rectTop,
        rectRight = rectRight,
        rectBottom = rectBottom,
        threshold = threshold,
    )

    /**
     * [FloatBuffer] overload used on the hot path: reads the interpreter's prototype
     * output in place, avoiding a multi-megabyte copy per detection.
     */
    fun buildMask(
        coefficients: FloatArray,
        prototypes: java.nio.FloatBuffer,
        channels: Int,
        protoWidth: Int,
        protoHeight: Int,
        boxLeft: Float,
        boxTop: Float,
        boxRight: Float,
        boxBottom: Float,
        rectLeft: Float,
        rectTop: Float,
        rectRight: Float,
        rectBottom: Float,
        threshold: Float = MASK_THRESHOLD,
    ): SegmentationMask {
        require(channels > 0) { "Mask channels must be positive" }
        require(protoWidth > 0 && protoHeight > 0) { "Prototype dimensions must be positive" }
        require(coefficients.size == channels) {
            "Expected $channels mask coefficients, found ${coefficients.size}"
        }
        val planeSize = protoWidth * protoHeight
        val needed = channels * planeSize
        // `limit()`, not `remaining()`: the read below is absolute, so a leftover
        // position from a previous pass must not shrink the readable range.
        require(prototypes.limit() >= needed) {
            "Prototype buffer holds ${prototypes.limit()} floats, expected $needed"
        }

        // The frame is stretched (not letterboxed) to the model input, so prototype
        // space and frame space share the same normalised coordinates.
        val xStart = (boxLeft.coerceIn(0f, 1f) * protoWidth).toInt().coerceIn(0, protoWidth - 1)
        val xEnd = (boxRight.coerceIn(0f, 1f) * protoWidth).toInt().coerceIn(0, protoWidth - 1)
        val yStart = (boxTop.coerceIn(0f, 1f) * protoHeight).toInt().coerceIn(0, protoHeight - 1)
        val yEnd = (boxBottom.coerceIn(0f, 1f) * protoHeight).toInt().coerceIn(0, protoHeight - 1)
        val xFrom = minOf(xStart, xEnd)
        val xTo = maxOf(xStart, xEnd)
        val yFrom = minOf(yStart, yEnd)
        val yTo = maxOf(yStart, yEnd)

        val bits = BooleanArray(planeSize)
        for (y in yFrom..yTo) {
            val rowBase = y * protoWidth
            for (x in xFrom..xTo) {
                val index = rowBase + x
                var logit = 0f
                for (c in 0 until channels) {
                    logit += coefficients[c] * prototypes.get(c * planeSize + index)
                }
                if (sigmoid(logit) > threshold) bits[index] = true
            }
        }

        return SegmentationMask(
            width = protoWidth,
            height = protoHeight,
            bits = bits,
            left = rectLeft,
            top = rectTop,
            right = rectRight,
            bottom = rectBottom,
        )
    }
}
