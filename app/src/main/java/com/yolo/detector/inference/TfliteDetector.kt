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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Overlap between neighbouring tiles of SAHI-style sliced inference, as a
 * fraction of the tile size. Keeps objects straddling a tile seam from being
 * split in half (and thus missed).
 */
private const val SLICE_OVERLAP = 0.2f

/** Normalised `[left, top, right, bottom]` of the whole frame — masks from unsliced inference. */
private val FULL_FRAME_RECT = floatArrayOf(0f, 0f, 1f, 1f)

/**
 * On-device detector powered by TensorFlow Lite.
 *
 * Loads the selected static model profile from assets and runs inference with
 * hardware acceleration.
 *
 * Detection profiles emit a single `[1, 4 + classes, boxes]` tensor. Segmentation
 * profiles additionally emit `[1, maskChannels, protoSize, protoSize]` mask
 * prototypes, and their primary output carries `maskChannels` extra trailing
 * channels holding raw per-detection mask coefficients.
 *
 * This class applies class-confidence filtering, class filter masking, and greedy NMS
 * to return a clean `List<Detection>` (trackId = -1; ByteTracker assigns real IDs).
 * For segmentation profiles each surviving detection also gets a
 * [com.yolo.detector.inference.SegmentationMask], computed only after NMS so the
 * prototype matmul runs once per kept object rather than per candidate box.
 *
 * When [InferenceSettings.slicedInference] is enabled, the frame is first tiled into
 * overlapping model-sized crops (SAHI-style, see [SliceGrid]) and each tile is run through
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
    @Volatile override var settings: InferenceSettings,
    override val profile: ModelProfile = ModelAssets.DEFAULT_PROFILE,
) : Detector {

    init {
        profile.validateForCurrentUi()
    }

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val isChannelsFirst: Boolean = profile.outputLayout == ModelLayout.CHANNELS_FIRST
    private val isInputChannelsFirst: Boolean = profile.inputLayout == ModelLayout.NCHW
    private val inputSize: Int = profile.inputSize
    private val numBoxes: Int = profile.numBoxes
    private val numClasses: Int = profile.numClasses
    private val isSegmentation: Boolean = profile.task == ModelTask.SEGMENTATION
    private val primaryChannels: Int = profile.primaryChannels
    private val maskChannels: Int = profile.maskChannels
    private val maskChannelOffset: Int = profile.maskChannelOffset
    private val protoSize: Int = profile.protoSize

    /**
     * Divisor applied to the box channels. Ultralytics' LiteRT export already emits
     * `[0, 1]` coordinates (the graph contains the `_NormalizeCoords` wrapper), so this
     * is 1 for shipped models and `inputSize` only for raw-pixel exports.
     */
    private val boxDivisor: Float =
        if (profile.boxScale == BoxScale.NORMALIZED) 1f else inputSize.toFloat()

    private val outputBuffers: Array<ByteBuffer>
    private val outputFloatBuffers: Array<java.nio.FloatBuffer>

    /**
     * Output buffers keyed by their position in the interpreter's output list, which
     * is the key `runForMultipleInputsOutputs` expects.
     *
     * TFLite 2.16.1 has no `runForMultipleInputsOutputs(Object[], Object[])` overload:
     * `run(Object, Object)` wraps its second argument in a single-element array, so
     * passing an array there makes the interpreter treat the array itself as one
     * output buffer and fail with "cannot resolve DataType of [Ljava.lang.Object;".
     * Preallocated to keep the map off the hot path.
     */
    private val outputTargets: Map<Int, Any>

    /** Primary head output, and the prototype output for segmentation profiles. */
    private val headBuffer: java.nio.FloatBuffer
    private val protoBuffer: java.nio.FloatBuffer?

    // Precomputed float normalization lookup table (0..255 -> 0.0f..1.0f)
    private val normTable = FloatArray(256) { it / 255f }

    // Preallocated buffers to eliminate GC churn and direct ByteBuffer native memory leaks
    private val inputPixelCount: Int = Math.multiplyExact(inputSize, inputSize)
    private val inputFloatCount: Int = Math.multiplyExact(inputPixelCount, 3)
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(Math.multiplyExact(inputFloatCount, Float.SIZE_BYTES))
        .apply { order(ByteOrder.nativeOrder()) }
    private val inputFloatBuffer: java.nio.FloatBuffer = inputBuffer.asFloatBuffer()

    // Reused across every inference call (incl. every slice) to avoid ~1.2M-float allocations.
    private val inputFloatArr = FloatArray(inputFloatCount)
    private val scaledPixels = IntArray(inputPixelCount)

    /** Scratch for one detection's mask coefficients; reused across the frame. */
    private val maskCoefficients = FloatArray(if (isSegmentation) maskChannels else 0)

    /** Reusable model-sized staging bitmap for SAHI-style sliced inference. */
    private var tileBitmap: Bitmap? = null
    private var tileCanvas: Canvas? = null
    private val tilePaint = Paint().apply { isFilterBitmap = true }

    // Tile grid cached per frame size (frames are a constant resolution while bound).
    private val tilesCacheKey = intArrayOf(0, 0)
    private var cachedTiles: List<SliceGrid.Tile> = emptyList()

    /** Which delegates/threads the interpreter was actually built with, for the init log. */
    private var delegateDescription: String = "unknown"

    init {
        val model = loadModelFile(context)
        val options = try {
            buildInterpreterOptions()
        } catch (e: Throwable) {
            closeGpuDelegate()
            throw e
        }
        val createdInterpreter = try {
            Interpreter(model, options)
        } catch (e: Throwable) {
            closeGpuDelegate()
            throw e
        }
        interpreter = createdInterpreter

        try {
            val inputSignature = (0 until createdInterpreter.getInputTensorCount()).map { index ->
                val tensor = createdInterpreter.getInputTensor(index)
                    ?: throw ModelContractException("Input tensor $index is missing")
                TensorSignature(
                    name = tensor.name(),
                    shape = tensor.shape().toList(),
                    dataType = tensorDataType(tensor.dataType()),
                )
            }
            val outputSignature = (0 until createdInterpreter.getOutputTensorCount()).map { index ->
                val tensor = createdInterpreter.getOutputTensor(index)
                    ?: throw ModelContractException("Output tensor $index is missing")
                TensorSignature(
                    name = tensor.name(),
                    shape = tensor.shape().toList(),
                    dataType = tensorDataType(tensor.dataType()),
                )
            }
            profile.validateSignature(ModelSignature(inputSignature, outputSignature))

            outputBuffers = Array(outputSignature.size) { index ->
                val tensor = createdInterpreter.getOutputTensor(index)
                    ?: throw ModelContractException("Output tensor $index is missing")
                ByteBuffer
                    .allocateDirect(Math.multiplyExact(tensor.numElements(), Float.SIZE_BYTES))
                    .apply { order(ByteOrder.nativeOrder()) }
            }
            outputFloatBuffers = Array(outputBuffers.size) { outputBuffers[it].asFloatBuffer() }
            // Keyed positionally, matching the getOutputTensor(index) order the
            // buffers above were sized from.
            outputTargets = HashMap<Int, Any>(outputBuffers.size * 2).apply {
                for (index in outputBuffers.indices) put(index, outputBuffers[index])
            }

            headBuffer = outputFloatBuffers[0]
            protoBuffer = if (isSegmentation) outputFloatBuffers[1] else null

            android.util.Log.i(
                "TfliteDetector",
                "Model initialized. Profile=${profile.id}, task=${profile.task}, " +
                    "InShape=${profile.expectedInputShape}, OutShapes=${profile.expectedOutputShapes}, " +
                "isInputCF=$isInputChannelsFirst, isOutCF=$isChannelsFirst, " +
                "boxes=$numBoxes, classes=$numClasses, maskChannels=$maskChannels, " +
                "outTensors=${outputSignature.map { it.name }}, delegate=$delegateDescription",
            )
        } catch (e: Throwable) {
            runCatching { createdInterpreter.close() }
                .onFailure { closeError -> android.util.Log.w("TfliteDetector", "Error closing interpreter", closeError) }
            closeGpuDelegate()
            throw e
        }
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFd = context.assets.openFd(profile.assetName)
        return assetFd.use { descriptor ->
            descriptor.createInputStream().use { input ->
                input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }
    }

    private fun buildInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()
        if (settings.enableGpuDelegate) {
            try {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate()
                    try {
                        options.addDelegate(delegate)
                        gpuDelegate = delegate
                    } catch (e: Exception) {
                        delegate.close()
                        throw e
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TfliteDetector", "Failed to init GPU delegate, fallback to CPU", e)
                gpuDelegate = null
            }
        }
        val availableCores = Runtime.getRuntime().availableProcessors()
        val threads = availableCores.coerceIn(4, 8)
        options.setNumThreads(threads)
        // Without this the interpreter runs the reference kernels, which is tolerable
        // for yolo11n but leaves a segmentation model an order of magnitude slower than
        // the hardware allows. XNNPACK only covers the ops a delegate did not claim, so
        // it composes with the GPU delegate above.
        options.setUseXNNPACK(true)
        delegateDescription = buildString {
            append(if (gpuDelegate != null) "gpu" else "cpu")
            append("+xnnpack threads=").append(threads)
        }
        return options
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Runs inference on [bitmap] and returns filtered, NMS-deduplicated detections.
     *
     * @param bitmap Source frame. Will be scaled to model-sized internally (or, when
     *        [InferenceSettings.slicedInference] is on, tiled into model-sized crops
     *        that are fused back to frame coordinates).
     * @return List of [Detection] with trackId = -1 (untracked).
     */
    override fun detect(bitmap: Bitmap): List<Detection> {
        val timestampMs = System.currentTimeMillis()
        if (settings.slicedInference) {
            return detectSliced(bitmap, timestampMs)
        }

        val scaled = if (bitmap.width == inputSize && bitmap.height == inputSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        }

        scaled.getPixels(scaledPixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        return runOnInput(timestampMs, FULL_FRAME_RECT)
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
            cachedTiles = SliceGrid.compute(srcW, srcH, inputSize, SLICE_OVERLAP)
            tilesCacheKey[0] = srcW
            tilesCacheKey[1] = srcH
        }

        val canvas = ensureTileCanvas()
        val all = ArrayList<Detection>(32 * cachedTiles.size)
        for (tile in cachedTiles) {
            drawTile(bitmap, canvas, tile)
            // A tile's mask covers the tile's own region of the frame, so it carries
            // that region as its normalised rect rather than the whole frame.
            val tileRect = floatArrayOf(
                tile.left / srcW.toFloat(),
                tile.top / srcH.toFloat(),
                tile.right / srcW.toFloat(),
                tile.bottom / srcH.toFloat(),
            )
            for (detection in runOnInput(timestampMs, tileRect)) {
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
            val bmp = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            tileBitmap = bmp
            Canvas(bmp).also { tileCanvas = it }
        }

    /** Draws [tile] of [source] into the reusable model-sized tile bitmap and reads its pixels. */
    private fun drawTile(source: Bitmap, canvas: Canvas, tile: SliceGrid.Tile) {
        val srcRect = Rect(tile.left, tile.top, tile.right, tile.bottom)
        val dstRect = RectF(0f, 0f, inputSize.toFloat(), inputSize.toFloat())
        canvas.drawBitmap(source, srcRect, dstRect, tilePaint)
        tileBitmap!!.getPixels(scaledPixels, 0, inputSize, 0, 0, inputSize, inputSize)
    }

    /**
     * Converts [scaledPixels] (a model-sized frame in ARGB ints, either the full frame
     * or one slice) into the input buffer, runs the interpreter, and parses the raw
     * output tensor into filtered, NMS-deduplicated detections.
     *
     * @param maskRect Normalised frame rect `[left, top, right, bottom]` that the input
     *        covers, recorded on any mask this pass produces.
     */
    private fun runOnInput(timestampMs: Long, maskRect: FloatArray): List<Detection> {
        inputFloatBuffer.rewind()
        val norm = normTable
        val floatArr = inputFloatArr
        if (isInputChannelsFirst) {
            // NCHW format: RRR... GGG... BBB...
            val planeSize = inputPixelCount
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

        for (buffer in outputBuffers) buffer.rewind()
        val inferStart = System.nanoTime()
        // Must be the multi-I/O entry point: run(Object, Object) wraps its second
        // argument as new Object[]{ outputs }, so passing an Array<Any> there makes
        // TFLite see a single output whose target is an Object[] and fail with
        // "cannot resolve DataType of [Ljava.lang.Object;". Here the array *is* the
        // output list, which is also what makes the second segment prototype tensor
        // reachable. Correct for one output as well as two.
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputTargets)
        for (buffer in outputFloatBuffers) buffer.rewind()
        lastInferenceMs = (System.nanoTime() - inferStart) / 1_000_000L

        val kept = parseCandidates(timestampMs)
            .sortedByDescending { it.detection.confidence }
            .take(settings.maxObjects)

        logFrameDiagnostics()

        return if (isSegmentation) attachMasks(kept, maskRect) else kept.map { it.detection }
    }

    /**
     * Membership test for the active class filter, rebuilt per pass.
     *
     * The candidate loop visits every class so it can report a global maximum, so
     * filter membership has to be an O(1) lookup rather than a set probe per score.
     */
    private val inFilter = BooleanArray(256)

    /**
     * Per-frame candidate accounting is only worth its cost while diagnosing, and
     * must never reach a release build. Keyed off the OS debuggable flag rather than
     * a new `InferenceSettings` field so there is no user-facing switch to get wrong.
     */
    private val diagnosticsEnabled: Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Per-frame counters, reset each pass; summarised by [logFrameDiagnostics]. */
    private var diagGlobalMax = 0f
    private var diagPostBoxMax = 0f
    private var diagTopChannel = -1
    private var diagInFilterMax = 0f
    private var diagAboveThreshold = 0
    private var diagInFilter = 0
    private var diagKept = 0
    private var diagFilterSize = 0

    /**
     * One line per pass describing where candidates were lost, to separate the
     * failure modes that all look like "no detections" on screen.
     *
     * `classMax` is the highest score in the channels the profile treats as class
     * scores, and `postBoxMax`/`topCh` the highest over every channel after the box
     * channels together with the channel it came from. So: a low `classMax` with a
     * high `postBoxMax` at a channel beyond the class range means the head is laid
     * out mask-first and the offsets are wrong, while both being low means the model
     * genuinely saw nothing. `inFilter` is the highest score among the classes the
     * filter allows, so `inFilterMax` at zero with a high `classMax` means the active
     * class filter is what rejected everything.
     */
    private fun logFrameDiagnostics() {
        if (!diagnosticsEnabled) return
        // Built first, then formatted once: applying .format to one literal of a `+`
        // chain binds tighter than the concatenation and silently leaves the other
        // specifiers unformatted.
        val line = "diag profile=%s filter=%d above=%d inFilter=%d kept=%d " +
            "classMax=%.4f postBoxMax=%.4f topCh=%d inFilterMax=%.4f inferMs=%d"
        android.util.Log.i(
            "TfliteDetector",
            line.format(
                java.util.Locale.US,
                profile.id,
                diagFilterSize,
                diagAboveThreshold,
                diagInFilter,
                diagKept,
                diagGlobalMax,
                diagPostBoxMax,
                diagTopChannel,
                diagInFilterMax,
                lastInferenceMs,
            ),
        )
        diagAboveThreshold = 0
        diagInFilter = 0
        diagGlobalMax = 0f
        diagPostBoxMax = 0f
        diagTopChannel = -1
        diagInFilterMax = 0f
    }

    /** Wall time of the most recent [runOnInput] call, for the diagnostic line. */
    private var lastInferenceMs = 0L

    // ── Output parsing ────────────────────────────────────────────────────────

    /**
     * A scored box plus the column it came from in the head output.
     *
     * The column index has to survive filtering and NMS because the mask
     * coefficients for a segmentation detection live in that same column, and the
     * clamped bbox is no longer enough to identify it.
     */
    private class Candidate(val detection: Detection, val boxIndex: Int)

    /**
     * Scans the head output for boxes above the confidence threshold and applies the
     * class filter. No masks and no NMS here — see [runOnInput].
     */
    private fun parseCandidates(timestampMs: Long): List<Candidate> {
        val candidates = ArrayList<Candidate>(64)
        val confThreshold = settings.confidenceThreshold
        val totalBoxes = numBoxes
        val totalClasses = numClasses
        val activeFilter = settings.classFilterFor(totalClasses).toIntArray()
        val rowStride = primaryChannels
        // Channels after the 4 box channels: class scores followed by mask
        // coefficients, per the profile's maskChannelOffset.
        val postBoxChannels = primaryChannels - 4
        // Only the diagnostics need the mask-coefficient channels scanned; for
        // yoloe that is 35 channels instead of 3 across 8400 boxes. Restricted to
        // debug builds so release inference only walks the class channels.
        val scanChannels = if (diagnosticsEnabled) postBoxChannels else totalClasses
        diagFilterSize = activeFilter.size
        java.util.Arrays.fill(inFilter, false)
        for (cls in activeFilter) {
            if (cls in 0 until totalClasses && cls < inFilter.size) inFilter[cls] = true
        }

        for (boxIdx in 0 until totalBoxes) {
            // One pass over every post-box channel. `classMax` covers the channels the
            // profile claims are class scores; `postBoxMax` covers all of them including
            // the mask coefficients, and records which channel it came from. If the class
            // range reads near zero while a later channel is high, the head is laid out
            // mask-first rather than class-first and the offsets are wrong.
            var bestClassId = -1
            var bestScore = confThreshold
            var classMax = 0f
            var postBoxMax = 0f
            var postBoxChannel = -1

            if (isChannelsFirst) {
                for (k in 0 until scanChannels) {
                    val score = headBuffer.get((4 + k) * totalBoxes + boxIdx)
                    if (score > postBoxMax) {
                        postBoxMax = score
                        postBoxChannel = 4 + k
                    }
                    if (k >= totalClasses) continue
                    if (score > classMax) classMax = score
                    if (!inFilter[k]) continue
                    if (score > bestScore) {
                        bestScore = score
                        bestClassId = k
                    }
                }
            } else {
                val boxOffset = boxIdx * rowStride
                for (k in 0 until scanChannels) {
                    val score = headBuffer.get(boxOffset + 4 + k)
                    if (score > postBoxMax) {
                        postBoxMax = score
                        postBoxChannel = 4 + k
                    }
                    if (k >= totalClasses) continue
                    if (score > classMax) classMax = score
                    if (!inFilter[k]) continue
                    if (score > bestScore) {
                        bestScore = score
                        bestClassId = k
                    }
                }
            }

            if (classMax > diagGlobalMax) {
                diagGlobalMax = classMax
                diagTopChannel = postBoxChannel
                diagPostBoxMax = postBoxMax
            }
            if (classMax > confThreshold) diagAboveThreshold++
            if (bestClassId == -1) continue
            diagInFilter++
            if (bestScore > diagInFilterMax) diagInFilterMax = bestScore

            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            if (isChannelsFirst) {
                cx = headBuffer.get(0 * totalBoxes + boxIdx)
                cy = headBuffer.get(1 * totalBoxes + boxIdx)
                w  = headBuffer.get(2 * totalBoxes + boxIdx)
                h  = headBuffer.get(3 * totalBoxes + boxIdx)
            } else {
                val boxOffset = boxIdx * rowStride
                cx = headBuffer.get(boxOffset + 0)
                cy = headBuffer.get(boxOffset + 1)
                w  = headBuffer.get(boxOffset + 2)
                h  = headBuffer.get(boxOffset + 3)
            }

            val halfW = (w / 2f) / boxDivisor
            val halfH = (h / 2f) / boxDivisor
            val normCx = cx / boxDivisor
            val normCy = cy / boxDivisor

            val left   = (normCx - halfW).coerceIn(0f, 1f)
            val top    = (normCy - halfH).coerceIn(0f, 1f)
            val right  = (normCx + halfW).coerceIn(0f, 1f)
            val bottom = (normCy + halfH).coerceIn(0f, 1f)

            candidates.add(
                Candidate(
                    detection = Detection(
                        trackId = -1,
                        classId = bestClassId,
                        confidence = bestScore,
                        bbox = RectF(left, top, right, bottom),
                        timestampMs = timestampMs,
                    ),
                    boxIndex = boxIdx,
                )
            )
        }

        return applyNms(candidates).also { diagKept = it.size }
    }

    /**
     * Builds an instance mask for each surviving detection from the raw mask
     * coefficients in the head output and the prototype output.
     *
     * Runs only on post-NMS, post-cap survivors: the prototype matmul is
     * `maskChannels * protoSize²` per detection, which is wasted work for the hundreds
     * of candidates NMS is about to discard.
     *
     * @param maskRect Normalised frame rect `[left, top, right, bottom]` the mask's
     *        prototype grid covers — the whole frame, or one slice's region.
     */
    private fun attachMasks(candidates: List<Candidate>, maskRect: FloatArray): List<Detection> {
        val protos = protoBuffer ?: return candidates.map { it.detection }
        if (candidates.isEmpty()) return emptyList()

        return candidates.map { candidate ->
            val detection = candidate.detection
            val bbox = detection.bbox
            // Mask coefficients are the trailing channels of the head output, in the
            // column this candidate came from. The bbox was already clamped into the
            // frame, which is the same normalised space the prototypes cover, so the
            // box needs no further scaling.
            for (c in 0 until maskChannels) {
                maskCoefficients[c] = if (isChannelsFirst) {
                    headBuffer.get((maskChannelOffset + c) * numBoxes + candidate.boxIndex)
                } else {
                    headBuffer.get(candidate.boxIndex * primaryChannels + maskChannelOffset + c)
                }
            }
            val mask = SegmentationDecoder.buildMask(
                coefficients = maskCoefficients,
                prototypes = protos,
                channels = maskChannels,
                protoWidth = protoSize,
                protoHeight = protoSize,
                boxLeft = bbox.left,
                boxTop = bbox.top,
                boxRight = bbox.right,
                boxBottom = bbox.bottom,
                rectLeft = maskRect[0],
                rectTop = maskRect[1],
                rectRight = maskRect[2],
                rectBottom = maskRect[3],
            )
            if (mask.isEmpty()) detection else detection.copy(mask = mask)
        }
    }


    // ── NMS ───────────────────────────────────────────────────────────────────

    /**
     * Greedy non-maximum suppression grouped by class.
     *
     * Within each class, suppresses lower-confidence boxes whose IoU with a
     * higher-confidence box exceeds [InferenceSettings.iouThreshold].
     */
    private fun applyNms(candidates: List<Candidate>): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val byClass = candidates.groupBy { it.detection.classId }

        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.detection.confidence }
            val keep = BooleanArray(sorted.size) { true }

            for (i in sorted.indices) {
                if (!keep[i]) continue
                result.add(sorted[i])
                for (j in i + 1 until sorted.size) {
                    if (!keep[j]) continue
                    if (iou(sorted[i].detection.bbox, sorted[j].detection.bbox) > settings.iouThreshold) {
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

    private fun closeGpuDelegate() {
        try {
            gpuDelegate?.close()
        } catch (e: Exception) {
            android.util.Log.w("TfliteDetector", "Error closing GPU delegate", e)
        }
        gpuDelegate = null
    }

    override fun close() {
        try {
            interpreter.close()
        } catch (e: Exception) {
            android.util.Log.w("TfliteDetector", "Error closing interpreter", e)
        }
        closeGpuDelegate()

        tileBitmap?.takeIf { !it.isRecycled }?.recycle()
        tileBitmap = null
        tileCanvas = null
    }
}
