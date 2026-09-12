package com.yolo.detector.ui.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.yolo.detector.data.DriverScene
import com.yolo.detector.data.SignType
import com.yolo.detector.data.TrafficLightSignal

/**
 * Driver-mode top-right status panel.
 *
 * Renders two stacked panels anchored to the top-right corner:
 *  - **Signs** (uppermost): recognized roadside signs, drawn above the traffic lights.
 *  - **Traffic lights**: one row per detected lane, each with a coloured dot and the
 *    current signal (GREEN / AMBER / RED), so the driver can see which lane's light
 *    is lit even when multiple lights are on screen.
 *
 * Only `drawRect` / `drawText` are used (the same primitives as [BoundingBoxOverlay])
 * so this renders reliably on every device.
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

        // Offset the whole panel below the system status bar so the signal dots are
        // never clipped/overlapped by the Android status icons.
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarPx = if (resId > 0) context.resources.getDimensionPixelSize(resId).toFloat() else 24f * density
        val statusBarPad = statusBarPx + 8f * density

        val margin = 12f * density
        val panelW = 240f * density
        val signRowH = 44f * density
        val lightRowH = 40f * density

        // The top FPS/stats bar spans the full width at the top of the screen. Push the
        // whole signals/signs panel down below it so they don't overlap.
        val statsBarClearance = 60f * density
        val panelTop = margin + statusBarPad + statsBarClearance

        // ── Signs panel (drawn ABOVE traffic lights) ─────────────────────────
        var y = panelTop
        for (sign in scene.signs) {
            val color = when (sign.type) {
                SignType.STOP -> Color.parseColor("#E53935")
                SignType.SPEED_LIMIT -> Color.parseColor("#1E88E5")
                SignType.UNKNOWN -> Color.parseColor("#9E9E9E")
            }
            canvas.drawRect(w - margin - panelW, y, w - margin, y + signRowH, panelPaint)
            dotPaint.color = color
            val dotR = signRowH * 0.32f
            val dotCx = w - margin - panelW + signRowH * 0.5f
            val dotCy = y + signRowH / 2f
            canvas.drawRect(dotCx - dotR, dotCy - dotR, dotCx + dotR, dotCy + dotR, dotPaint)
            smallTextPaint.textSize = 26f * density
            canvas.drawText(
                sign.label,
                dotCx + dotR + 10f * density,
                y + signRowH / 2f + 8f * density,
                smallTextPaint,
            )
            y += signRowH + 6f * density
        }

        // ── Traffic-light panel ──────────────────────────────────────────────
        val lightRows = scene.trafficLights
        val lightPanelH = 40f * density + lightRows.size * lightRowH + 8f * density
        val lightTop = if (scene.signs.isNotEmpty()) y + 10f * density else panelTop

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