package com.yolo.detector.ui.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.yolo.detector.data.COCO_LABELS
import com.yolo.detector.data.Detection
import com.yolo.detector.data.DetectionView
import com.yolo.detector.data.VEHICLE_CLASS_IDS
import com.yolo.detector.data.labelFor
import com.yolo.detector.inference.SegmentationMask

/**
 * Transparent overlay [View] that draws YOLO bounding boxes on top of the camera preview.
 *
 * Boxes are drawn in normalised coordinates [0,1] that are scaled to the view's pixel size.
 *
 * Label rendering follows [DetectionView] (see [detectionView]):
 * - [DetectionView.LABELS]     — label above the box: class, track ID, confidence.
 * - [DetectionView.BOX_ONLY]   — box outline only, no labels.
 * - [DetectionView.COUNT]      — per-object running number drawn **inside** the box
 *   ("1", "2", … in frame order); per-class counts are shown in the stats HUD.
 *   Same visuals as the Live-tab count toggle ("C").
 * - [DetectionView.OBJECTS_ONLY] — draws nothing; the baked frame (see
 *   LiveFragment) already blacks out everything outside the boxes, so the
 *   objects show through with no extra box strokes or labels.
 *
 * Vehicle class colours:
 * - Car (2):         Green  #00E676
 * - Motorcycle (3):  Cyan   #00BCD4
 * - Bus (5):         Red    #F44336
 * - Truck (7):       Orange #FF9800
 * - Other:           Grey   #9E9E9E
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    init {
        setWillNotDraw(false)
    }

    private val classColors = mapOf(
        0 to Color.parseColor("#00E676"),   // person — green
        2 to Color.parseColor("#00E676"),   // car — green
        3 to Color.parseColor("#00BCD4"),   // motorcycle — cyan
        5 to Color.parseColor("#F44336"),   // bus — red
        7 to Color.parseColor("#FF9800"),   // truck — orange
    )

    private fun getColorForClass(classId: Int): Int {
        return classColors[classId] ?: run {
            val hue = (classId * 137.5f) % 360f
            Color.HSVToColor(floatArrayOf(hue, 0.9f, 1.0f))
        }
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        alpha = 220
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
    }

    /**
     * Instance masks are drawn as a translucent tint of the class colour. A mask is
     * stored at the model's prototype resolution (160×160 for the shipped YOLOE
     * export) and stretched to its normalised frame rect, so the fill is
     * deliberately soft — the box outline and label stay the precise reference.
     */
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Alpha of the mask tint; low enough that the preview stays readable underneath. */
    private val maskAlpha = 110

    /**
     * ARGB staging row-buffer for [rebuildMaskLayer]. Sized to the largest mask seen;
     * the prototype resolution is fixed per model, so this is allocated once.
     */
    private var maskPixels: IntArray = IntArray(0)

    /**
     * One mask's pixels, paired with the class colour it is tinted in. Built in
     * [setDetections], not in onDraw.
     */
    private var pendingMasks: List<Pair<SegmentationMask, Int>> = emptyList()

    private fun ensureMaskBuffer(count: Int) {
        if (maskPixels.size < count) maskPixels = IntArray(count)
    }

    private val maskDst = RectF()

    /**
     * Mask tints, recorded per frame in [rebuildMaskLayer] and replayed by [onDraw].
     *
     * Mask rasterising allocates a prototype-sized buffer per detection, so doing it
     * inside onDraw would churn the heap (and trip lint) on every frame. Recording
     * happens once per new [detections] snapshot instead, and the bitmaps come from a
     * pool that is reused across frames.
     */
    private data class MaskTint(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private var maskTints: List<MaskTint> = emptyList()

    /** Reusable raster targets, one per concurrently drawn mask. */
    private val maskPool = ArrayList<Bitmap>()

    /**
     * Rasterises `masks` (paired with their class colour) into [maskTints] for the
     * current view geometry, recycling pool bitmaps the frame no longer needs.
     */
    private fun rebuildMaskLayer(
        masks: List<Pair<SegmentationMask, Int>>,
        content: OverlayGeometry.ContentRect?,
    ) {
        if (masks.isEmpty()) {
            maskTints = emptyList()
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val offsetX = content?.left ?: 0f
        val offsetY = content?.top ?: 0f
        val contentW = content?.width ?: w
        val contentH = content?.height ?: h

        // Grow the pool to the number of masks actually on screen.
        while (maskPool.size < masks.size) {
            val first = masks[maskPool.size].first
            maskPool.add(
                Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
            )
        }

        val tints = ArrayList<MaskTint>(masks.size)
        for (i in masks.indices) {
            val (mask, color) = masks[i]
            val bitmap = maskPool[i]
            val bits = mask.rawBits()
            val tint = (color and 0x00FFFFFF) or (maskAlpha shl 24)
            for (p in bits.indices) {
                maskPixels[p] = if (bits[p]) tint else 0
            }
            bitmap.setPixels(maskPixels, 0, mask.width, 0, 0, mask.width, mask.height)

            tints.add(
                MaskTint(
                    bitmap = bitmap,
                    left = offsetX + mask.left * contentW,
                    top = offsetY + mask.top * contentH,
                    right = offsetX + mask.right * contentW,
                    bottom = offsetY + mask.bottom * contentH,
                )
            )
        }
        maskTints = tints
    }

    private var detections: List<Detection> = emptyList()

    /**
     * Label vocabulary of the model that produced [detections]. Class IDs are only
     * meaningful within one model's label space, so the overlay is told which list
     * to index instead of assuming COCO.
     */
    var labels: List<String> = COCO_LABELS
        set(value) {
            if (field != value) {
                field = value
                postInvalidate()
            }
        }

    /**
     * Aspect ratio (width/height) of the frame the detections are normalised to
     * (the rotated analysis bitmap). The overlay uses it to draw boxes inside the
     * centered crop the `fillCenter` preview actually shows. A non-positive value
     * means "unknown" and falls back to mapping across the whole view.
     */
    var frameAspectRatio: Float = -1f
        set(value) {
            if (field != value) {
                field = value
                postInvalidate()
            }
        }

    /**
     * How boxes/labels are rendered. [DetectionView.OBJECTS_ONLY] draws nothing
     * — the frame itself is masked (see LiveFragment), so objects show through
     * with no extra box strokes or labels.
     * Kept in sync with the Settings "Detection view" plus the transient
     * Live-tab count toggle override (see LiveFragment).
     */
    var detectionView: DetectionView = DetectionView.LABELS
        set(value) {
            if (field != value) {
                field = value
                postInvalidate()
            }
        }

    /**
     * Live-tab count toggle override: when true, boxes show per-object running
     * numbers inside them (same as [DetectionView.COUNT]) without changing the
     * persisted Settings value. Display-only switch — no state is kept.
     */
    var countLabelsEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                postInvalidate()
            }
        }

    /** Updates the overlay with a new frame's detections and triggers a redraw. */
    fun setDetections(dets: List<Detection>) {
        detections = dets
        // Collected here so the rasterising can happen in onDraw's size context
        // without re-walking the detections.
        pendingMasks = dets.mapNotNull { det ->
            val mask = det.mask ?: return@mapNotNull null
            if (mask.isEmpty()) null else mask to getColorForClass(det.classId)
        }
        ensureMaskBuffer(pendingMasks.maxOfOrNull { it.first.width * it.first.height } ?: 0)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detectionView == DetectionView.OBJECTS_ONLY) {
            // The baked frame already blacks out everything outside the boxes,
            // so the objects show through with no extra box strokes or labels.
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()

        // PreviewView uses fillCenter: when the view and frame aspects differ, only
        // the centered crop of the frame is visible. Boxes must be mapped inside
        // that window or they drift relative to the objects on screen.
        val content = OverlayGeometry.fillCenterContentRect(w, h, frameAspectRatio)
        val offsetX = content?.left ?: 0f
        val offsetY = content?.top ?: 0f
        val contentW = content?.width ?: w
        val contentH = content?.height ?: h

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        // Masks first, so box outlines and labels stay crisp on top of the tint.
        rebuildMaskLayer(pendingMasks, content)
        for (tint in maskTints) {
            maskDst.set(tint.left, tint.top, tint.right, tint.bottom)
            canvas.drawBitmap(tint.bitmap, null, maskDst, maskPaint)
        }

        for ((index, det) in detections.withIndex()) {
            val color = getColorForClass(det.classId)

            val left   = offsetX + det.bbox.left   * contentW
            val top    = offsetY + det.bbox.top    * contentH
            val right  = offsetX + det.bbox.right  * contentW
            val bottom = offsetY + det.bbox.bottom * contentH

            // ── Bounding box ───────────────────────────────────────────────────
            boxPaint.color = color
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // The Live-tab toggle ("C") overrides the Settings value transiently.
            val showCountNumbers = countLabelsEnabled || detectionView == DetectionView.COUNT
            if (showCountNumbers) {
                // ── Count number inside the box ────────────────────────────────
                drawCountNumber(canvas, index + 1, left, top, right, bottom, color)
                continue
            }

            if (detectionView == DetectionView.BOX_ONLY) {
                // Box outline only — no labels.
                continue
            }

            // ── Label background ───────────────────────────────────────────────
            val label = buildLabel(det)
            val textBounds = Rect()
            labelPaint.getTextBounds(label, 0, label.length, textBounds)

            val labelH = textBounds.height() + 12f
            val labelW = textBounds.width() + 16f
            val labelTop = (top - labelH).coerceAtLeast(0f)

            labelBgPaint.color = color
            canvas.drawRect(left, labelTop, left + labelW, labelTop + labelH, labelBgPaint)

            // ── Label text ─────────────────────────────────────────────────────
            canvas.drawText(label, left + 8f, labelTop + labelH - 6f, labelPaint)
        }

        canvas.restore()
    }

    private fun buildLabel(det: Detection): String {
        val cls = labelFor(det.classId, labels).take(8)
        val id = if (det.trackId >= 0) "#${det.trackId}" else "?"
        val conf = (det.confidence * 100).toInt()
        return "$cls $id ${conf}%"
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (bitmap in maskPool) {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        maskPool.clear()
        maskTints = emptyList()
    }

    /**
     * Draws the per-object running number ("1", "2", …) centred inside the
     * box: a filled circle in the box colour with a contrasting white number.
     * The radius is clamped to the box size so small boxes still fit.
     */
    private fun drawCountNumber(
        canvas: Canvas,
        number: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
    ) {
        val text = countLabelFor(number)
        val textBounds = Rect()
        labelPaint.getTextBounds(text, 0, text.length, textBounds)
        val radius = (maxOf(textBounds.width(), textBounds.height()) / 2f + 14f)
            .coerceAtMost(minOf(right - left, bottom - top) / 2f)
            .coerceAtLeast(1f)
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        labelBgPaint.color = color
        canvas.drawCircle(cx, cy, radius, labelBgPaint)
        // Vertically centre the text on the circle.
        canvas.drawText(text, cx - textBounds.width() / 2f, cy + textBounds.height() / 2f, labelPaint)
    }
}

/**
 * Display text for the per-object running number inside count-mode boxes.
 * Pure helper so the numbering format is unit-testable.
 */
fun countLabelFor(number: Int): String = number.coerceAtLeast(1).toString()

/**
 * Pure geometry for aligning the bounding-box overlay with the cropped camera
 * preview. Kept float-only (no android types) so the math is JVM-unit-testable.
 */
object OverlayGeometry {

    /** Content window, in view pixels, that the cropped frame occupies. */
    data class ContentRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * Computes the content rect for a `scaleType="fillCenter"` view: the camera
     * content is scaled to cover the whole view and, when the view aspect differs
     * from the frame aspect, the overflow is cropped symmetrically.
     *
     * Detections are normalised to the *frame*, so they must be drawn inside this
     * window — otherwise boxes span the full view and drift from the objects.
     *
     * @return null when no crop applies (aspects match, or inputs are unknown).
     */
    fun fillCenterContentRect(viewW: Float, viewH: Float, frameAspect: Float): ContentRect? {
        if (viewW <= 0f || viewH <= 0f || frameAspect <= 0f || !frameAspect.isFinite()) return null
        val viewAspect = viewW / viewH
        if (kotlin.math.abs(viewAspect - frameAspect) < 1e-4f) return null

        val contentW: Float
        val contentH: Float
        if (frameAspect > viewAspect) {
            // Frame is relatively wider: it fills the view height and overflows the sides.
            contentH = viewH
            contentW = viewH * frameAspect
        } else {
            // Frame is relatively taller: it fills the view width and overflows top/bottom.
            contentW = viewW
            contentH = viewW / frameAspect
        }
        val left = (viewW - contentW) / 2f
        val top = (viewH - contentH) / 2f
        return ContentRect(left, top, left + contentW, top + contentH)
    }
}
