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
 * Each box shows: class label, track ID, and confidence score.
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

    private val classColors = mapOf(
        2 to Color.parseColor("#00E676"),   // car — green
        3 to Color.parseColor("#00BCD4"),   // motorcycle — cyan
        5 to Color.parseColor("#F44336"),   // bus — red
        7 to Color.parseColor("#FF9800"),   // truck — orange
    )
    private val defaultColor = Color.parseColor("#9E9E9E")

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        alpha = 200
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        typeface = Typeface.MONOSPACE
    }

    private var detections: List<Detection> = emptyList()

    /** Updates the overlay with a new frame's detections and triggers a redraw. */
    fun setDetections(dets: List<Detection>) {
        detections = dets
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        for (det in detections) {
            val color = classColors[det.classId] ?: defaultColor

            val left   = det.bbox.left   * w
            val top    = det.bbox.top    * h
            val right  = det.bbox.right  * w
            val bottom = det.bbox.bottom * h

            // ── Bounding box ───────────────────────────────────────────────────
            boxPaint.color = color
            canvas.drawRect(left, top, right, bottom, boxPaint)

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
    }

    private fun buildLabel(det: Detection): String {
        val cls = labelFor(det.classId).take(8)
        val id = if (det.trackId >= 0) "#${det.trackId}" else "?"
        val conf = (det.confidence * 100).toInt()
        return "$cls $id ${conf}%"
    }
}
