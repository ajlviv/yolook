package com.yolo.detector.ui.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.yolo.detector.data.COCO_LABELS
import com.yolo.detector.data.Detection
import com.yolo.detector.data.labelFor

/**
 * Driver-mode object overlay that draws an object marker for each detection.
 *
 * - With the camera preview shown, each detection gets a compact coloured icon above
 *   the object (no bounding box).
 * - With "hide camera view" enabled, it renders each object as a filled, bordered
 *   box with a label band (or traffic-light signal icon) so detections are clearly
 *   visible on the dark background instead of a black screen.
 *
 * Traffic lights render as a dark icon with three stacked dots (state shown in the
 * top-right HUD via [DriverHudOverlay]).
 */
class DriverObjectIconsOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    init {
        setWillNotDraw(false)
    }

    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 230
    }
    /** Opaque-ish box fill so each object stands out clearly on the dark background. */
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 200
    }
    /** Bright border around each object box so outlines pop against black. */
    private val boxBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 255
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#9E9E9E")
    }

    private var detections: List<Detection> = emptyList()

    /** When true (driver + "hide camera view"), render filled object boxes + labels. */
    private var renderBoxes: Boolean = false

    fun setDetections(dets: List<Detection>) {
        detections = dets
        postInvalidate()
    }

    fun setRenderBoxes(render: Boolean) {
        if (render != renderBoxes) {
            renderBoxes = render
            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        for (det in detections) {
            val left = det.bbox.left * w
            val top = det.bbox.top * h
            val right = det.bbox.right * w
            val bottom = det.bbox.bottom * h
            val boxHeight = maxOf(bottom - top, 1f)
            val color = getColorForClass(det.classId)

            if (renderBoxes) {
                // Clamp so we never draw off-canvas.
                val x0 = left.coerceIn(0f, w - 1f)
                val y0 = top.coerceIn(0f, h - 1f)
                val x1 = right.coerceIn(x0 + 1f, w)
                val y1 = bottom.coerceIn(y0 + 1f, h)

                // Filled, bordered box — clearly marks each object on the dark background.
                boxFill.color = color
                canvas.drawRect(x0, y0, x1, y1, boxFill)
                canvas.drawRect(x0, y0, x1, y1, boxBorder)

                if (det.classId == trafficLightClass) {
                    // Traffic light: draw the signal icon at the top-centre of the box.
                    val iconSize = (boxHeight * 0.5f).coerceIn(22f, 48f)
                    val iconLeftX = x0 + (x1 - x0) / 2f - iconSize / 2f
                    drawTrafficLightIcon(canvas, iconLeftX, y0, iconSize)
                } else {
                    // Label band along the top of the box.
                    val bandH = (boxHeight * 0.18f).coerceIn(16f, 28f)
                    labelBg.color = color
                    canvas.drawRect(x0, y0, x1, minOf(y0 + bandH, y1), labelBg)
                    canvas.drawText(labelFor(det.classId).uppercase(), x0 + 6f, y0 + bandH - 4f, labelPaint)
                }
            } else {
                // Default (live preview shown): compact icon above each object.
                val iconSize = (boxHeight * 0.5f).coerceIn(24f, 64f)
                val centerX = (left + right) / 2f
                val iconTop = (top - iconSize).coerceAtLeast(0f)
                val iconLeft = centerX - iconSize / 2f
                val iconRight = centerX + iconSize / 2f

                iconFill.color = color
                canvas.drawRect(iconLeft, iconTop, iconRight, iconTop + iconSize, iconFill)

                if (det.classId == trafficLightClass) {
                    drawTrafficLightIcon(canvas, iconLeft, iconTop, iconSize)
                } else {
                    val label = labelFor(det.classId).uppercase().take(4)
                    val bounds = Rect()
                    labelPaint.getTextBounds(label, 0, label.length, bounds)
                    val textW = bounds.width()
                    val textH = bounds.height()
                    val tx = centerX - textW / 2f
                    val ty = iconTop + (iconSize + textH) / 2f - 2f
                    canvas.drawText(label, tx, ty, labelPaint)
                }
            }
        }

        canvas.restore()
    }
private fun drawTrafficLightIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        val body = Paint().apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#212121")
        }
        canvas.drawRect(left, top, left + size, top + size, body)

        val dotRadius = size * 0.12f
        val cx = left + size / 2f
        val cyTop = top + size * 0.22f
        val spacing = size * 0.3f
        // Red → amber → green, top to bottom (all shown greyed unless in HUD).
        val cols = intArrayOf(
            Color.parseColor("#E53935"),
            Color.parseColor("#FB8C00"),
            Color.parseColor("#43A047"),
        )
        dotPaint.color = cols[0]
        canvas.drawRect(cx - dotRadius, cyTop - dotRadius, cx + dotRadius, cyTop + dotRadius, dotPaint)
        dotPaint.color = cols[1]
        canvas.drawRect(cx - dotRadius, cyTop + spacing - dotRadius, cx + dotRadius, cyTop + spacing + dotRadius, dotPaint)
        dotPaint.color = cols[2]
        canvas.drawRect(cx - dotRadius, cyTop + 2 * spacing - dotRadius, cx + dotRadius, cyTop + 2 * spacing + dotRadius, dotPaint)
    }

    private val trafficLightClass: Int get() = COCO_LABELS.indexOf("traffic light").coerceAtLeast(0)

    private val classColors = mapOf(
        0 to Color.parseColor("#26C6DA"),   // person — cyan
        1 to Color.parseColor("#8D6E63"),   // bicycle
        2 to Color.parseColor("#00E676"),   // car — green
        3 to Color.parseColor("#00BCD4"),   // motorcycle
        5 to Color.parseColor("#F44336"),   // bus — red
        7 to Color.parseColor("#FF9800"),   // truck — orange
        11 to Color.parseColor("#E53935"),  // stop sign — red
    )

    private fun getColorForClass(classId: Int): Int {
        return classColors[classId] ?: run {
            val hue = (classId * 137.5f) % 360f
            Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.95f))
        }
    }
}