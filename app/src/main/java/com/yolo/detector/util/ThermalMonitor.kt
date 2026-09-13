package com.yolo.detector.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Monitors device thermal state to dynamically prevent thermal throttling and overheating
 * during continuous driver mode dash-cam operation.
 */
class ThermalMonitor(context: Context) : Closeable {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _thermalStatus = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    val thermalStatus: StateFlow<Int> = _thermalStatus.asStateFlow()

    private var listener: Any? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            registerThermalListener(powerManager)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermalListener(pm: PowerManager) {
        val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            _thermalStatus.value = status
            android.util.Log.i("ThermalMonitor", "Device thermal status updated: $status")
        }
        listener = thermalListener
        try {
            pm.addThermalStatusListener(thermalListener)
            _thermalStatus.value = pm.currentThermalStatus
        } catch (e: Exception) {
            android.util.Log.w("ThermalMonitor", "Unable to register thermal status listener", e)
        }
    }

    /**
     * Checks if current thermal status requires inference frame-rate reduction.
     * Status >= THERMAL_STATUS_MODERATE (2) indicates rising temperatures.
     */
    fun shouldThrottleInference(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val status = _thermalStatus.value
        return status >= PowerManager.THERMAL_STATUS_MODERATE
    }

    /**
     * Returns an adjusted FPS cap based on the current thermal status.
     */
    fun getAdaptiveFpsCap(requestedFps: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return requestedFps
        return when (_thermalStatus.value) {
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> minOf(requestedFps, 5)
            PowerManager.THERMAL_STATUS_MODERATE -> minOf(requestedFps, 8)
            PowerManager.THERMAL_STATUS_LIGHT -> minOf(requestedFps, 12)
            else -> requestedFps
        }
    }

    override fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && listener != null) {
            try {
                powerManager.removeThermalStatusListener(listener as PowerManager.OnThermalStatusChangedListener)
            } catch (_: Exception) {}
            listener = null
        }
    }
}
