package com.yolo.detector.inference

import android.graphics.Bitmap
import android.graphics.RectF
import android.content.Context
import com.yolo.detector.data.Detection
import com.yolo.detector.data.InferenceSettings
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val MODEL_FILE = "yolov8m.tflite"
private const val INPUT_SIZE = 640
private const val NUM_BOXES = 8400
private const val NUM_CLASSES = 80

/**
 * On-device YOLOv8m detector powered by TensorFlow Lite.
 *
 * Loads `yolov8m.tflite` from assets and runs inference with hardware acceleration
 * (GPU delegate → NNAPI → CPU fallback).
 *
 * The model outputs a raw tensor of shape `[1, 84, 8400]`:
 * - First 4 rows: cx, cy, w, h (normalised to [0, 1]).
 * - Remaining 80 rows: per-class confidence scores.
 *
 * This class applies class-confidence filtering, class filter masking, and greedy NMS
 * to return a clean `List<Detection>` (trackId = -1; ByteTracker assigns real IDs).
 *
 * Thread safety: [detect] must be called from a single background thread.
 * Close this instance when the camera pipeline shuts down.
 */
class TfliteDetector(
    context: Context,
    private val settings: InferenceSettings,
) : Closeable {

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    init {
        val model = loadModelFile(context)
        val options = buildInterpreterOptions()
        interpreter = Interpreter(model, options)
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_FILE)
        return assetFd.createInputStream().channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength,
        )
    }

    private fun buildInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()
        if (settings.enableGpuDelegate) {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate!!)
            }
            // NNAPI and CPU fallback happen automatically if GPU delegate not added.
        }
        options.setNumThreads(4)
        return options
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Runs inference on [bitmap] and returns filtered, NMS-deduplicated detections.
     *
     * @param bitmap Source frame. Will be scaled to 640×640 internally.
     * @return List of [Detection] with trackId = -1 (untracked).
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        val inputBuffer = bitmap.toByteBuffer(INPUT_SIZE)

        // Output tensor: [1, 84, 8400]
        val rawOutput = Array(1) { Array(NUM_CLASSES + 4) { FloatArray(NUM_BOXES) } }
        interpreter.run(inputBuffer, rawOutput)

        val timestampMs = System.currentTimeMillis()
        return parseOutput(rawOutput[0], timestampMs)
    }

    // ── Output parsing ────────────────────────────────────────────────────────

    /**
     * Parses the raw `[84, 8400]` output tensor into [Detection] instances.
     *
     * Each of the 8400 anchor predictions has:
     * - [0..3]: cx, cy, w, h (normalised)
     * - [4..83]: per-class scores
     *
     * Processing steps:
     * 1. Find the highest-scoring class for each anchor.
     * 2. Discard anchors below [InferenceSettings.confidenceThreshold].
     * 3. Discard classes not in [InferenceSettings.classFilter].
     * 4. Convert cx/cy/w/h to left/top/right/bottom.
     * 5. Apply greedy NMS per class.
     * 6. Limit to [InferenceSettings.maxObjects] detections.
     */
    private fun parseOutput(
        output: Array<FloatArray>,   // [84][8400]
        timestampMs: Long,
    ): List<Detection> {
        val candidates = mutableListOf<Detection>()

        for (boxIdx in 0 until NUM_BOXES) {
            val cx = output[0][boxIdx]
            val cy = output[1][boxIdx]
            val w  = output[2][boxIdx]
            val h  = output[3][boxIdx]

            // Find best class
            var bestClassId = -1
            var bestScore = settings.confidenceThreshold
            for (cls in 0 until NUM_CLASSES) {
                val score = output[4 + cls][boxIdx]
                if (score > bestScore) {
                    bestScore = score
                    bestClassId = cls
                }
            }
            if (bestClassId == -1) continue
            if (bestClassId !in settings.classFilter) continue

            val left   = (cx - w / 2f).coerceIn(0f, 1f)
            val top    = (cy - h / 2f).coerceIn(0f, 1f)
            val right  = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)

            candidates.add(
                Detection(
                    trackId = -1,
                    classId = bestClassId,
                    confidence = bestScore,
                    bbox = RectF(left, top, right, bottom),
                    timestampMs = timestampMs,
                )
            )
        }

        return applyNms(candidates)
            .sortedByDescending { it.confidence }
            .take(settings.maxObjects)
    }

    // ── NMS ───────────────────────────────────────────────────────────────────

    /**
     * Greedy non-maximum suppression grouped by class.
     *
     * Within each class, suppresses lower-confidence boxes whose IoU with a
     * higher-confidence box exceeds [InferenceSettings.iouThreshold].
     */
    private fun applyNms(detections: List<Detection>): List<Detection> {
        val result = mutableListOf<Detection>()
        val byClass = detections.groupBy { it.classId }

        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()
            val keep = BooleanArray(sorted.size) { true }

            for (i in sorted.indices) {
                if (!keep[i]) continue
                result.add(sorted[i])
                for (j in i + 1 until sorted.size) {
                    if (!keep[j]) continue
                    if (iou(sorted[i].bbox, sorted[j].bbox) > settings.iouThreshold) {
                        keep[j] = false
                    }
                }
            }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left, b.left)
        val interTop    = maxOf(a.top, b.top)
        val interRight  = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val intersection = interW * interH

        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection

        return if (union <= 0f) 0f else intersection / union
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
