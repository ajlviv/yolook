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
    @Volatile var settings: InferenceSettings,
) : Closeable {

    private var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val isChannelsFirst: Boolean
    private val isInputChannelsFirst: Boolean
    private val numBoxes: Int
    private val numClasses: Int
    private val isInputQuantized: Boolean
    private val isOutputQuantized: Boolean
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val outputBuffer: ByteBuffer
    private val outputFloatBuffer: java.nio.FloatBuffer

    // Precomputed float normalization lookup table (0..255 -> 0.0f..1.0f)
    private val normTable = FloatArray(256) { it / 255f }

    // Preallocated buffers to eliminate GC churn and direct ByteBuffer native memory leaks
    private val inputBuffer: ByteBuffer
    private val inputFloatBuffer: java.nio.FloatBuffer?
    private val scaledPixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val preallocatedFloatArray = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)

    private val contextRef: Context = context.applicationContext

    init {
        val model = loadModelFile(context)
        val options = buildInterpreterOptions()
        interpreter = Interpreter(model, options)

        val inputTensor = interpreter.getInputTensor(0)
        val inShape = inputTensor.shape()
        isInputChannelsFirst = (inShape.size >= 4 && inShape[1] == 3)
        isInputQuantized = (inputTensor.dataType() == org.tensorflow.lite.DataType.INT8 || inputTensor.dataType() == org.tensorflow.lite.DataType.UINT8)
        val inQParams = inputTensor.quantizationParams()
        inputScale = if (inQParams.scale > 0f) inQParams.scale else 1f
        inputZeroPoint = inQParams.zeroPoint

        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape()
        // shape is either [1, 84, 8400] (channels first) or [1, 8400, 84] (channels last)
        isChannelsFirst = (shape.size >= 3 && shape[1] <= 100 && shape[2] > 100)
        numBoxes = if (isChannelsFirst) shape[2] else shape[1]
        numClasses = (if (isChannelsFirst) shape[1] else shape[2]) - 4
        isOutputQuantized = (outputTensor.dataType() == org.tensorflow.lite.DataType.INT8 || outputTensor.dataType() == org.tensorflow.lite.DataType.UINT8)
        val outQParams = outputTensor.quantizationParams()
        outputScale = if (outQParams.scale > 0f) outQParams.scale else 1f
        outputZeroPoint = outQParams.zeroPoint

        val inputBytesPerElem = if (isInputQuantized) 1 else 4
        inputBuffer = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * inputBytesPerElem)
            .apply { order(ByteOrder.nativeOrder()) }
        inputFloatBuffer = if (isInputQuantized) null else inputBuffer.asFloatBuffer()

        val outputBytesPerElem = if (isOutputQuantized) 1 else 4
        outputBuffer = ByteBuffer
            .allocateDirect(outputTensor.numElements() * outputBytesPerElem)
            .apply { order(ByteOrder.nativeOrder()) }
        outputFloatBuffer = outputBuffer.asFloatBuffer()

        android.util.Log.i(
            "TfliteDetector",
            "Model initialized. InShape=${inShape.joinToString()}, InQuant=$isInputQuantized, OutShape=${shape.joinToString()}, OutQuant=$isOutputQuantized, isInputCF=$isInputChannelsFirst, isOutCF=$isChannelsFirst, boxes=$numBoxes, classes=$numClasses"
        )
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

    private fun buildInterpreterOptions(forceCpu: Boolean = false): Interpreter.Options {
        val options = Interpreter.Options()
        if (settings.enableGpuDelegate && !forceCpu) {
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
        options.setNumThreads(availableCores.coerceIn(2, 6))
        options.setUseXNNPACK(true)
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
        val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
        }

        scaled.getPixels(scaledPixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        inputBuffer.rewind()
        if (isInputQuantized) {
            // Quantized INT8/UINT8 feeding directly into inputBuffer
            if (isInputChannelsFirst) {
                val planeSize = INPUT_SIZE * INPUT_SIZE
                for (i in 0 until planeSize) {
                    val p = scaledPixels[i]
                    val r = ((p shr 16) and 0xFF) / 255f
                    val g = ((p shr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    inputBuffer.put(i, (r / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
                    inputBuffer.put(planeSize + i, (g / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
                    inputBuffer.put(planeSize * 2 + i, (b / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
                }
            } else {
                var idx = 0
                for (pixel in scaledPixels) {
                    val r = ((pixel shr 16) and 0xFF) / 255f
                    val g = ((pixel shr 8) and 0xFF) / 255f
                    val b = (pixel and 0xFF) / 255f
                    inputBuffer.put(idx++, (r / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
                    inputBuffer.put(idx++, (g / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
                    inputBuffer.put(idx++, (b / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
                }
            }
        } else {
            // Float32 feeding into pre-allocated reusable array
            inputFloatBuffer?.rewind()
            val norm = normTable
            val floatArr = preallocatedFloatArray
            if (isInputChannelsFirst) {
                val planeSize = INPUT_SIZE * INPUT_SIZE
                for (i in 0 until planeSize) {
                    val pixel = scaledPixels[i]
                    floatArr[i] = norm[(pixel shr 16) and 0xFF]
                    floatArr[planeSize + i] = norm[(pixel shr 8) and 0xFF]
                    floatArr[planeSize * 2 + i] = norm[pixel and 0xFF]
                }
            } else {
                var idx = 0
                for (pixel in scaledPixels) {
                    floatArr[idx++] = norm[(pixel shr 16) and 0xFF]
                    floatArr[idx++] = norm[(pixel shr 8) and 0xFF]
                    floatArr[idx++] = norm[pixel and 0xFF]
                }
            }
            inputFloatBuffer?.put(floatArr)
        }
        inputBuffer.rewind()

        outputBuffer.rewind()
        try {
            interpreter.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            android.util.Log.e("TfliteDetector", "Interpreter run failed, attempting CPU fallback", e)
            fallbackToCpu()
            outputBuffer.rewind()
            inputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
        }
        if (!isOutputQuantized) {
            outputFloatBuffer.rewind()
        }

        val timestampMs = System.currentTimeMillis()
        return parseOutput(timestampMs)
    }

    private fun fallbackToCpu() {
        try {
            gpuDelegate?.close()
        } catch (_: Exception) {}
        gpuDelegate = null
        try {
            val model = loadModelFile(contextRef)
            val cpuOptions = buildInterpreterOptions(forceCpu = true)
            interpreter.close()
            interpreter = Interpreter(model, cpuOptions)
        } catch (e: Exception) {
            android.util.Log.e("TfliteDetector", "Failed to recreate CPU interpreter", e)
        }
    }

    private fun getOutputValue(index: Int): Float {
        return if (isOutputQuantized) {
            val raw = outputBuffer.get(index).toInt()
            (raw - outputZeroPoint) * outputScale
        } else {
            outputFloatBuffer.get(index)
        }
    }

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
                    val score = getOutputValue((4 + cls) * totalBoxes + boxIdx)
                    if (score > bestScore) {
                        bestScore = score
                        bestClassId = cls
                    }
                }
            } else {
                val boxOffset = boxIdx * (totalClasses + 4)
                for (cls in activeFilter) {
                    if (cls >= totalClasses) continue
                    val score = getOutputValue(boxOffset + 4 + cls)
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
                cx = getOutputValue(0 * totalBoxes + boxIdx)
                cy = getOutputValue(1 * totalBoxes + boxIdx)
                w  = getOutputValue(2 * totalBoxes + boxIdx)
                h  = getOutputValue(3 * totalBoxes + boxIdx)
            } else {
                val boxOffset = boxIdx * (totalClasses + 4)
                cx = getOutputValue(boxOffset + 0)
                cy = getOutputValue(boxOffset + 1)
                w  = getOutputValue(boxOffset + 2)
                h  = getOutputValue(boxOffset + 3)
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
    }
}
