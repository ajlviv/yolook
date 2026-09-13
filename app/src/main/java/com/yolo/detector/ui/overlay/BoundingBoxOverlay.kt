package com.yolo.detector.ui.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.yolo.detector.data.COCO_LABELS
import com.yolo.detector.data.Detection
import com.yolo.detector.data.VEHICLE_CLASS_IDS
import com.yolo.detector.data.labelFor

/**
 * Transparent overlay [View] that draws YOLO bounding boxes on top of the camera preview.
 *
 * Boxes are drawn in normalised coordinates [0,1] that are scaled to the view's pixel size.
 *
 * Two label styles (see [countLabelsEnabled]):
 * - Detect style: label above the box — class label, track ID, confidence.
 * - Count style:  per-object running number drawn **inside** the box
 *   ("1", "2", … in frame order) with per-class counts shown in the stats HUD.
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

    private var detections: List<Detection> = emptyList()

    /**
     * When true, boxes show a per-object running number ("1", "2", …) drawn
     * inside the box instead of the detect-style label above it. This is a
     * pure display switch — no state is kept, and the view mode is untouched.
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
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        for ((index, det) in detections.withIndex()) {
            val color = getColorForClass(det.classId)

            val left   = det.bbox.left   * w
            val top    = det.bbox.top    * h
            val right  = det.bbox.right  * w
            val bottom = det.bbox.bottom * h

            // ── Bounding box ───────────────────────────────────────────────────
            boxPaint.color = color
            canvas.drawRect(left, top, right, bottom, boxPaint)

            if (countLabelsEnabled) {
                // ── Count number inside the box ────────────────────────────────
                drawCountNumber(canvas, index + 1, left, top, right, bottom, color)
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
        val cls = labelFor(det.classId).take(8)
        val id = if (det.trackId >= 0) "#${det.trackId}" else "?"
        val conf = (det.confidence * 100).toInt()
        return "$cls $id ${conf}%"
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
