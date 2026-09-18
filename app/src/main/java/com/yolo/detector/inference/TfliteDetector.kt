package com.yolo.detector.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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

private const val INPUT_SIZE = 640
private const val NUM_BOXES = 8400
private const val NUM_CLASSES = 80

/**
 * Overlap between neighbouring tiles of SAHI-style sliced inference, as a
 * fraction of the tile size. Keeps objects straddling a tile seam from being
 * split in half (and thus missed).
 */
private const val SLICE_OVERLAP = 0.2f

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
 * When [InferenceSettings.slicedInference] is enabled, the frame is first tiled into
 * overlapping 640×640 crops (SAHI-style, see [SliceGrid]) and each tile is run through
 * the model at full input resolution; the results are fused back to frame coordinates
 * and globally NMS'd. This roughly doubles the effective resolution the detector sees,
 * which helps recall on small/distant objects at the cost of ~6× more inference work
 * per frame.
 *
 * Thread safety: [detect] must be called from a single background thread.
 * Close this instance when the camera pipeline shuts down.
 */
class TfliteDetector(
    context: Context,
    @Volatile var settings: InferenceSettings,
) : Closeable {

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val isChannelsFirst: Boolean
    private val isInputChannelsFirst: Boolean
    private val numBoxes: Int
    private val numClasses: Int
    private val outputBuffer: ByteBuffer
    private val outputFloatBuffer: java.nio.FloatBuffer

    // Precomputed float normalization lookup table (0..255 -> 0.0f..1.0f)
    private val normTable = FloatArray(256) { it / 255f }

    // Preallocated buffers to eliminate GC churn and direct ByteBuffer native memory leaks
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        .apply { order(ByteOrder.nativeOrder()) }
    private val inputFloatBuffer: java.nio.FloatBuffer = inputBuffer.asFloatBuffer()

    // Reused across every inference call (incl. every slice) to avoid ~1.2M-float allocations.
    private val inputFloatArr = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
    private val scaledPixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    // Reusable 640×640 staging bitmap for SAHI-style sliced inference.
    private var tileBitmap: Bitmap? = null
    private var tileCanvas: Canvas? = null
    private val tilePaint = Paint().apply { isFilterBitmap = true }

    // Tile grid cached per frame size (frames are a constant resolution while bound).
    private val tilesCacheKey = intArrayOf(0, 0)
    private var cachedTiles: List<SliceGrid.Tile> = emptyList()

    init {
        val model = loadModelFile(context)
        val options = buildInterpreterOptions()
        interpreter = Interpreter(model, options)

        val inputTensor = interpreter.getInputTensor(0)
        val inShape = inputTensor.shape()
        isInputChannelsFirst = (inShape.size >= 4 && inShape[1] == 3)

        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape()
        // shape is either [1, 84, 8400] (channels first) or [1, 8400, 84] (channels last)
        isChannelsFirst = (shape.size >= 3 && shape[1] <= 100 && shape[2] > 100)
        numBoxes = if (isChannelsFirst) shape[2] else shape[1]
        numClasses = (if (isChannelsFirst) shape[1] else shape[2]) - 4

        outputBuffer = ByteBuffer
            .allocateDirect(outputTensor.numElements() * 4)
            .apply { order(ByteOrder.nativeOrder()) }
        outputFloatBuffer = outputBuffer.asFloatBuffer()

        android.util.Log.i("TfliteDetector", "Model initialized. InShape=${inShape.joinToString()}, OutShape=${shape.joinToString()}, isInputCF=$isInputChannelsFirst, isOutCF=$isChannelsFirst, boxes=$numBoxes, classes=$numClasses")
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFd = context.assets.openFd(ModelAssets.FILE_NAME)
        return assetFd.createInputStream().channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength,
        )
    }

    private fun buildInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()
        if (settings.enableGpuDelegate) {
            try {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate()
                    gpuDelegate = delegate
                    options.addDelegate(delegate)
                }
            } catch (e: Exception) {
                android.util.Log.w("TfliteDetector", "Failed to init GPU delegate, fallback to CPU", e)
                gpuDelegate = null
            }
        }
        val availableCores = Runtime.getRuntime().availableProcessors()
        options.setNumThreads(availableCores.coerceIn(4, 8))
        return options
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Runs inference on [bitmap] and returns filtered, NMS-deduplicated detections.
     *
     * @param bitmap Source frame. Will be scaled to 640×640 internally (or, when
     *        [InferenceSettings.slicedInference] is on, tiled into 640×640 crops
     *        that are fused back to frame coordinates).
     * @return List of [Detection] with trackId = -1 (untracked).
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        val timestampMs = System.currentTimeMillis()
        if (settings.slicedInference) {
            return detectSliced(bitmap, timestampMs)
        }

        val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
        }

        scaled.getPixels(scaledPixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        return runOnInput(timestampMs).take(settings.maxObjects)
    }

    /**
     * SAHI-style sliced inference: runs the model on the overlapping tile grid
     * covering the frame, then fuses the tile-local detections back to frame
     * coordinates and NMS'ing them globally.
     */
    private fun detectSliced(bitmap: Bitmap, timestampMs: Long): List<Detection> {
        val srcW = bitmap.width
        val srcH = bitmap.height

        if (srcW != tilesCacheKey[0] || srcH != tilesCacheKey[1]) {
            cachedTiles = SliceGrid.compute(srcW, srcH, INPUT_SIZE, SLICE_OVERLAP)
            tilesCacheKey[0] = srcW
            tilesCacheKey[1] = srcH
        }

        val canvas = ensureTileCanvas()
        val all = ArrayList<Detection>(32 * cachedTiles.size)
        for (tile in cachedTiles) {
            drawTile(bitmap, canvas, tile)
            for (detection in runOnInput(timestampMs)) {
                val bbox = detection.bbox
                val mapped = SliceGrid.mapToFrame(
                    tile, srcW, srcH,
                    SliceGrid.Box(bbox.left, bbox.top, bbox.right, bbox.bottom),
                )
                all.add(detection.copy(bbox = RectF(mapped.left, mapped.top, mapped.right, mapped.bottom)))
            }
        }

        return applyFusionNms(all)
            .sortedByDescending { it.confidence }
            .take(settings.maxObjects)
    }

    private fun ensureTileCanvas(): Canvas =
        tileCanvas ?: run {
            val bmp = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            tileBitmap = bmp
            Canvas(bmp).also { tileCanvas = it }
        }

    /** Draws [tile] of [source] into the reusable 640×640 tile bitmap and reads its pixels. */
    private fun drawTile(source: Bitmap, canvas: Canvas, tile: SliceGrid.Tile) {
        val srcRect = Rect(tile.left, tile.top, tile.right, tile.bottom)
        val dstRect = RectF(0f, 0f, INPUT_SIZE.toFloat(), INPUT_SIZE.toFloat())
        canvas.drawBitmap(source, srcRect, dstRect, tilePaint)
        tileBitmap!!.getPixels(scaledPixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
    }

    /**
     * Converts [scaledPixels] (a 640×640 frame in ARGB ints, either the full frame
     * or one slice) into the input buffer, runs the interpreter, and parses the raw
     * output tensor into filtered, NMS-deduplicated detections.
     */
    private fun runOnInput(timestampMs: Long): List<Detection> {
        inputFloatBuffer.rewind()
        val norm = normTable
        val floatArr = inputFloatArr
        if (isInputChannelsFirst) {
            // NCHW format: RRR... GGG... BBB...
            val planeSize = INPUT_SIZE * INPUT_SIZE
            for (i in 0 until planeSize) {
                val pixel = scaledPixels[i]
                floatArr[i] = norm[(pixel shr 16) and 0xFF]
                floatArr[planeSize + i] = norm[(pixel shr 8) and 0xFF]
                floatArr[planeSize * 2 + i] = norm[pixel and 0xFF]
            }
        } else {
            // NHWC format: RGB RGB RGB...
            var idx = 0
            for (pixel in scaledPixels) {
                floatArr[idx++] = norm[(pixel shr 16) and 0xFF]
                floatArr[idx++] = norm[(pixel shr 8) and 0xFF]
                floatArr[idx++] = norm[pixel and 0xFF]
            }
        }
        inputFloatBuffer.put(floatArr)
        inputBuffer.rewind()

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputFloatBuffer.rewind()

        return parseOutput(timestampMs)
    }

    // ── Output parsing ────────────────────────────────────────────────────────

    private fun parseOutput(timestampMs: Long): List<Detection> {
        val candidates = ArrayList<Detection>(64)
        val confThreshold = settings.confidenceThreshold
        val activeFilter = settings.classFilter.toIntArray()
        val totalBoxes = numBoxes
        val totalClasses = numClasses

        for (boxIdx in 0 until totalBoxes) {
            // Find best class first before doing coordinate math
            var bestClassId = -1
            var bestScore = confThreshold

            if (isChannelsFirst) {
                for (cls in activeFilter) {
                    if (cls >= totalClasses) continue
                    val score = outputFloatBuffer.get((4 + cls) * totalBoxes + boxIdx)
                    if (score > bestScore) {
                        bestScore = score
                        bestClassId = cls
                    }
                }
            } else {
                val boxOffset = boxIdx * (totalClasses + 4)
                for (cls in activeFilter) {
                    if (cls >= totalClasses) continue
                    val score = outputFloatBuffer.get(boxOffset + 4 + cls)
                    if (score > bestScore) {
                        bestScore = score
                        bestClassId = cls
                    }
                }
            }

            if (bestClassId == -1) continue

            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            if (isChannelsFirst) {
                cx = outputFloatBuffer.get(0 * totalBoxes + boxIdx)
                cy = outputFloatBuffer.get(1 * totalBoxes + boxIdx)
                w  = outputFloatBuffer.get(2 * totalBoxes + boxIdx)
                h  = outputFloatBuffer.get(3 * totalBoxes + boxIdx)
            } else {
                val boxOffset = boxIdx * (totalClasses + 4)
                cx = outputFloatBuffer.get(boxOffset + 0)
                cy = outputFloatBuffer.get(boxOffset + 1)
                w  = outputFloatBuffer.get(boxOffset + 2)
                h  = outputFloatBuffer.get(boxOffset + 3)
            }

            // Auto-detect whether output box coordinates are normalized [0, 1] or raw pixels [0, 640]
            val scale = if (cx > 1.5f || cy > 1.5f || w > 1.5f || h > 1.5f) INPUT_SIZE.toFloat() else 1.0f
            val halfW = (w / 2f) / scale
            val halfH = (h / 2f) / scale
            val normCx = cx / scale
            val normCy = cy / scale

            val left   = (normCx - halfW).coerceIn(0f, 1f)
            val top    = (normCy - halfH).coerceIn(0f, 1f)
            val right  = (normCx + halfW).coerceIn(0f, 1f)
            val bottom = (normCy + halfH).coerceIn(0f, 1f)

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

        // NOTE: no `take(maxObjects)` here — callers apply the global cap, and the
        // sliced path must see every tile's survivors before fused NMS.
        return applyNms(candidates).sortedByDescending { it.confidence }
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

    /**
     * SAHI-style fusion pass used for sliced detections, mirroring `sahi`'s
     * GREEDYNMM postprocessing.
     *
     * Same greedy within-class suppression as [applyNms], but matching on IOS
     * (intersection over the smaller area) instead of IoU. The duplicate from a
     * tile seam is a *contained* partial box: it can be a fraction of the full
     * box's area, so its IoU with the full box easily stays below the threshold —
     * while its IOS is ~1. A single object detected in two tiles therefore
     * collapses to one detection instead of two tracks.
     */
    private fun applyFusionNms(detections: List<Detection>): List<Detection> {
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
                    if (ios(sorted[i].bbox, sorted[j].bbox) > settings.iouThreshold) {
                        keep[j] = false
                    }
                }
            }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float =
        BboxMetrics.iou(a.left, a.top, a.right, a.bottom, b.left, b.top, b.right, b.bottom)

    private fun ios(a: RectF, b: RectF): Float =
        BboxMetrics.ios(a.left, a.top, a.right, a.bottom, b.left, b.top, b.right, b.bottom)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        try {
            interpreter.close()
        } catch (e: Exception) {
            android.util.Log.w("TfliteDetector", "Error closing interpreter", e)
        }
        try {
            gpuDelegate?.close()
        } catch (e: Exception) {
            android.util.Log.w("TfliteDetector", "Error closing GPU delegate", e)
        }
        gpuDelegate = null

        tileBitmap?.takeIf { !it.isRecycled }?.recycle()
        tileBitmap = null
        tileCanvas = null
    }
}
