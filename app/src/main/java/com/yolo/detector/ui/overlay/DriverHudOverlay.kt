package com.yolo.detector.ui.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.yolo.detector.data.CollisionAlertLevel
import com.yolo.detector.data.DriverScene
import com.yolo.detector.data.SignType
import com.yolo.detector.data.TrafficLightSignal
import com.yolo.detector.data.WarningType

/**
 * Driver-mode top-right status panel.
 *
 * Renders stacked ADAS status panels anchored to the top-right corner:
 *  - **Lead Vehicle / FCW**: Distance in meters + Time-To-Collision (TTC) & danger level.
 *  - **Pedestrian Alert**: Warnings when someone is actively crossing the vehicle path.
 *  - **Active Speed Limit**: Stylized circular regulatory speed limit roundel.
 *  - **Signs**: recognized roadside signs, drawn above the traffic lights.
 *  - **Traffic lights**: one row per detected lane, each with a coloured dot and the current signal.
 */
class DriverHudOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    init {
        setWillNotDraw(false)
    }

    private var scene: DriverScene = DriverScene()

    fun setScene(scene: DriverScene) {
        this.scene = scene
        postInvalidate()
    }

    private val panelPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#99000000")
        alpha = 200
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.DEFAULT
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val roundelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E53935")
        strokeWidth = 6f
    }
    private val roundelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val roundelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private fun signalColor(signal: TrafficLightSignal): Int = when (signal) {
        TrafficLightSignal.RED -> Color.parseColor("#E53935")
        TrafficLightSignal.YELLOW -> Color.parseColor("#FDD835")
        TrafficLightSignal.GREEN -> Color.parseColor("#43A047")
        else -> Color.parseColor("#9E9E9E")
    }

    private fun signalName(signal: TrafficLightSignal): String = when (signal) {
        TrafficLightSignal.RED -> "RED"
        TrafficLightSignal.YELLOW -> "AMBER"
        TrafficLightSignal.GREEN -> "GREEN"
        TrafficLightSignal.OFF -> "OFF"
        TrafficLightSignal.UNKNOWN -> "?"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = context.resources.displayMetrics.density
        val w = width.toFloat()
        if (w <= 0f) return

        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarPx = if (resId > 0) context.resources.getDimensionPixelSize(resId).toFloat() else 24f * density
        val statusBarPad = statusBarPx + 8f * density

        val margin = 12f * density
        val panelW = 240f * density
        val rowH = 44f * density
        val statsBarClearance = 60f * density
        val panelTop = margin + statusBarPad + statsBarClearance

        var y = panelTop

        // ── 1. Forward Collision Warning (FCW) & Lead Vehicle ─────────────────
        val lead = scene.leadVehicle
        if (lead != null) {
            val (fcwColor, fcwText) = when (lead.alertLevel) {
                CollisionAlertLevel.COLLISION_WARNING -> Color.parseColor("#E53935") to "BRAKE! COLLISION"
                CollisionAlertLevel.TAILGATING -> Color.parseColor("#FB8C00") to "TAILGATING"
                CollisionAlertLevel.SAFE -> Color.parseColor("#43A047") to "LEAD CAR"
            }
            canvas.drawRect(w - margin - panelW, y, w - margin, y + rowH, panelPaint)
            dotPaint.color = fcwColor
            val dotR = rowH * 0.30f
            val dotCx = w - margin - panelW + rowH * 0.5f
            val dotCy = y + rowH / 2f
            canvas.drawRect(dotCx - dotR, dotCy - dotR, dotCx + dotR, dotCy + dotR, dotPaint)

            smallTextPaint.textSize = 22f * density
            val ttcStr = if (lead.timeToCollisionSec != null) " | %.1fs".format(lead.timeToCollisionSec) else ""
            val label = "%.0fm%s ($fcwText)".format(lead.distanceMeters, ttcStr)
            canvas.drawText(label, dotCx + dotR + 8f * density, y + rowH / 2f + 7f * density, smallTextPaint)
            y += rowH + 6f * density
        }

        // ── 2. Pedestrian Crossing Warning ────────────────────────────────────
        if (scene.warnings.contains(WarningType.PEDESTRIAN_CROSSING)) {
            canvas.drawRect(w - margin - panelW, y, w - margin, y + rowH, panelPaint)
            dotPaint.color = Color.parseColor("#E53935")
            val dotR = rowH * 0.30f
            val dotCx = w - margin - panelW + rowH * 0.5f
            val dotCy = y + rowH / 2f
            canvas.drawRect(dotCx - dotR, dotCy - dotR, dotCx + dotR, dotCy + dotR, dotPaint)

            smallTextPaint.textSize = 22f * density
            smallTextPaint.color = Color.parseColor("#FF5252")
            canvas.drawText("PEDESTRIAN CROSSING", dotCx + dotR + 8f * density, y + rowH / 2f + 7f * density, smallTextPaint)
            smallTextPaint.color = Color.WHITE
            y += rowH + 6f * density
        }

        // ── 3. Active Speed Limit Roundel ─────────────────────────────────────
        val speedLimit = scene.activeSpeedLimit
        if (speedLimit != null) {
            canvas.drawRect(w - margin - panelW, y, w - margin, y + rowH, panelPaint)
            val roundelR = rowH * 0.36f
            val roundelCx = w - margin - panelW + rowH * 0.5f
            val roundelCy = y + rowH / 2f

            roundelBorderPaint.strokeWidth = 3.5f * density
            canvas.drawCircle(roundelCx, roundelCy, roundelR, roundelFillPaint)
            canvas.drawCircle(roundelCx, roundelCy, roundelR, roundelBorderPaint)

            roundelTextPaint.textSize = 18f * density
            canvas.drawText("$speedLimit", roundelCx, roundelCy + 6f * density, roundelTextPaint)

            smallTextPaint.textSize = 24f * density
            canvas.drawText("SPEED LIMIT $speedLimit", roundelCx + roundelR + 10f * density, y + rowH / 2f + 7f * density, smallTextPaint)
            y += rowH + 6f * density
        }

        // ── 4. Signs panel ───────────────────────────────────────────────────
        for (sign in scene.signs) {
            val color = when (sign.type) {
                SignType.STOP -> Color.parseColor("#E53935")
                SignType.SPEED_LIMIT -> Color.parseColor("#E53935")
                SignType.YIELD -> Color.parseColor("#FB8C00")
                SignType.NO_ENTRY -> Color.parseColor("#D32F2F")
                SignType.PEDESTRIAN_CROSSING -> Color.parseColor("#1976D2")
                SignType.UNKNOWN -> Color.parseColor("#9E9E9E")
            }
            canvas.drawRect(w - margin - panelW, y, w - margin, y + rowH, panelPaint)
            dotPaint.color = color
            val dotR = rowH * 0.32f
            val dotCx = w - margin - panelW + rowH * 0.5f
            val dotCy = y + rowH / 2f
            canvas.drawRect(dotCx - dotR, dotCy - dotR, dotCx + dotR, dotCy + dotR, dotPaint)
            smallTextPaint.textSize = 26f * density
            canvas.drawText(
                sign.label,
                dotCx + dotR + 10f * density,
                y + rowH / 2f + 8f * density,
                smallTextPaint,
            )
            y += rowH + 6f * density
        }

        // ── 5. Traffic-light panel ───────────────────────────────────────────
        val lightRows = scene.trafficLights
        val lightRowH = 40f * density
        val lightPanelH = 40f * density + lightRows.size * lightRowH + 8f * density
        val lightTop = y + 4f * density

        canvas.drawRect(w - margin - panelW, lightTop, w - margin, lightTop + lightPanelH, panelPaint)

        // Header
        smallTextPaint.color = Color.parseColor("#00E676")
        smallTextPaint.textSize = 24f * density
        canvas.drawText("SIGNALS", w - margin - panelW + 12f * density, lightTop + 22f * density, smallTextPaint)
        smallTextPaint.color = Color.WHITE

        var rowY = lightTop + 36f * density
        for (light in lightRows) {
            dotPaint.color = signalColor(light.signal)
            val dotR = 12f * density
            val dotCx = w - margin - panelW + 24f * density
            val dotCy = rowY + lightRowH / 2f
            canvas.drawRect(dotCx - dotR, dotCy - dotR, dotCx + dotR, dotCy + dotR, dotPaint)

            textPaint.textSize = 24f * density
            val label = "L${light.laneIndex + 1}: ${signalName(light.signal)}"
            canvas.drawText(label, w - margin - panelW + 48f * density, rowY + lightRowH / 2f + 7f * density, textPaint)
            rowY += lightRowH
        }
    }
}