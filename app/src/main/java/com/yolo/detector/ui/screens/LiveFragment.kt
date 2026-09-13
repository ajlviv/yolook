package com.yolo.detector.ui.screens

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yolo.detector.data.ViewMode
import com.yolo.detector.databinding.FragmentLiveBinding
import com.yolo.detector.ui.MainViewModel
import com.yolo.detector.ui.ViewModeEffects
import com.yolo.detector.ui.applyCountEdges
import com.yolo.detector.ui.CountTally
import com.yolo.detector.ui.applyHeatmap
import com.yolo.detector.ui.formatCountStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Live camera fragment: shows the PreviewView, bounding-box overlay, and a stats HUD.
 *
 * The ViewModel is shared with other fragments (activityViewModels) so settings
 * changes in SettingsFragment take effect immediately.
 */
class LiveFragment : Fragment() {

    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    // Tracked view-mode state for frame display.
    private var currentMode: ViewMode = ViewMode.NORMAL
    private var displayedBitmap: Bitmap? = null

    // Heatmap rendering. Baking is slow (per-pixel), so a single serial worker
    // bakes the newest pending frame and drops intermediate ones. This lets the
    // heatmap update at the bake rate regardless of the analysis FPS. All pending
    // frame / worker state lives on the main thread; only the bake itself runs on
    // a background thread, so drop-oldest recycling cannot race the baker.
    private var pendingHeatmapFrame: Bitmap? = null
    private var heatmapWorker: Job? = null

    // Count mode: serial edge-bake worker (same drop-oldest pattern as heatmap).
    // Frames are paired with the latest detections snapshot taken on the main
    // thread, so masking boxes align with the baked frame's inference pass.
    private var pendingCountFrame: Bitmap? = null
    private var countWorker: Job? = null
    private var latestDetections: List<com.yolo.detector.data.Detection> = emptyList()
    private val countTally = CountTally()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind camera to this fragment's lifecycle; preview goes into the PreviewView.
        viewModel.bindCamera(viewLifecycleOwner, binding.previewView)

        // Adjust tvStats margin/padding for system status bar / notch
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.tvStats) { v, insets ->
            val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            lp?.topMargin = statusBars.top + (16 * resources.displayMetrics.density).toInt()
            v.layoutParams = lp
            insets
        }

        // FAB: capture current overlay and save snapshot
        binding.fabSnapshot.setOnClickListener {
            captureAndSaveSnapshot()
        }

        // Mode toggle above the snapshot FAB: 'D' = default detect view,
        // 'C' = count view (objects-only edges + per-class tally HUD).
        binding.fabModeToggle.setOnClickListener {
            if (currentMode == ViewMode.COUNT) {
                viewModel.setViewMode(ViewMode.NORMAL)
            } else {
                viewModel.setViewMode(ViewMode.COUNT)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settingsFlow.collect { settings ->
                        applyViewMode(settings.viewMode)
                    }
                }
                launch {
                    viewModel.frameFlow.collect { bitmap ->
                        if (bitmap != null) {
                            // Frame copies are only emitted while a filtered mode is
                            // active, so the coverage overlay must be on. Keying this
                            // off frame arrival instead of the async settingsFlow
                            // closes the startup/switch gap where the camera is already
                            // filtering but applyViewMode() hasn't run yet — otherwise
                            // the raw camera feed shows through during that window.
                            binding.imageFilterPreview.visibility = View.VISIBLE
                            when (currentMode) {
                                ViewMode.HEATMAP -> onHeatmapFrame(bitmap)
                                ViewMode.COUNT -> onCountFrame(bitmap)
                                ViewMode.NORMAL -> bitmap.recycle() // camera filtered, UI not yet updated
                                else -> commitFrame(bitmap)
                            }
                        } else if (currentMode == ViewMode.NORMAL) {
                            // Camera switched back to the plain preview: mirror it by
                            // clearing the filtered image and hiding the overlay.
                            clearDisplayedFrame()
                            binding.imageFilterPreview.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.detectionFlow.collect { detections ->
                        binding.overlay.setDetections(detections)
                        latestDetections = detections
                        if (currentMode == ViewMode.COUNT) {
                            // Cumulative per-class count from confirmed track IDs.
                            if (countTally.update(detections)) {
                                refreshCountHud()
                            }
                        }
                    }
                }
                launch {
                    viewModel.statsFlow.collect { stats ->
                        if (currentMode == ViewMode.COUNT) {
                            // HUD shows cumulative per-class counts while stats update.
                            lastStatsFps = stats.fps
                            lastStatsMs = stats.inferenceMs
                            refreshCountHud()
                        } else {
                            binding.tvStats.text = "FPS: ${"%.1f".format(stats.fps)}  " +
                                    "Latency: ${stats.inferenceMs}ms  " +
                                    "Objects: ${stats.objectCount}"
                        }
                    }
                }
                launch {
                    viewModel.pipelineError.collect { error ->
                        if (error == null) {
                            binding.tvModelError.visibility = View.GONE
                            binding.fabSnapshot.isEnabled = true
                        } else {
                            binding.tvModelError.visibility = View.VISIBLE
                            binding.tvModelError.text = error
                            binding.fabSnapshot.isEnabled = false
                        }
                    }
                }
                launch {
                    viewModel.cameraError.collect { error ->
                        if (error != null) {
                            binding.tvModelError.visibility = View.VISIBLE
                            binding.tvModelError.text = "Camera error: $error"
                        }
                    }
                }
            }
        }
    }
    /** Switches between the raw preview and the filtered frame renderer overlay. */
    private fun applyViewMode(mode: ViewMode) {
        if (mode == currentMode) {
            // First run: sync the toggle letter with restored persisted mode.
            binding.fabModeToggle.text = if (mode == ViewMode.COUNT) "C" else "D"
            return
        }
        android.util.Log.i("ViewMode", "applyViewMode=$mode")
        currentMode = mode

        // Full reset: releases the previous mode's frames and stops any bake.
        clearDisplayedFrame()

        // Count tally restarts fresh each time count mode is entered.
        if (mode == ViewMode.COUNT) countTally.clear()
        binding.fabModeToggle.text = if (mode == ViewMode.COUNT) "C" else "D"

        binding.imageFilterPreview.colorFilter = when (mode) {
            ViewMode.BLACK_AND_WHITE -> ViewModeEffects.blackAndWhiteColorFilter()
            ViewMode.INVERT -> ViewModeEffects.invertColorFilter()
            else -> null
        }
        binding.imageFilterPreview.visibility = if (mode != ViewMode.NORMAL) View.VISIBLE else View.GONE
    }

    /**
     * Receives a heatmap-mode frame. Keeps only the newest frame (drop-oldest) and
     * hands it to the serial baker. Owns [frame] on entry; the baker recycles it.
     */
    private fun onHeatmapFrame(frame: Bitmap) {
        pendingHeatmapFrame?.recycle()
        pendingHeatmapFrame = frame
        ensureHeatmapWorker()
    }

    /** Starts the baker if it is not already running. */
    private fun ensureHeatmapWorker() {
        if (heatmapWorker != null) return
        heatmapWorker = viewLifecycleOwner.lifecycleScope.launch {
            runHeatmapLoop()
        }
    }

    /**
     * Serial heatmap loop (runs on main; bakes on a background thread). Bakes the
     * latest pending frame and commits it, looping for any newer frame. Exits when
     * out of frames or the mode changes.
     *
     * Keeping the loop, the pending-slot and the worker reference main-confined
     * guarantees the drop-oldest recycle below cannot race the bake that previously
     * consumed a frame.
     */
    private suspend fun runHeatmapLoop() {
        try {
            while (currentMode == ViewMode.HEATMAP) {
                val frame = pendingHeatmapFrame ?: return
                pendingHeatmapFrame = null

                // Transfer the computed result back to the main thread where we can
                // react to cancellation and safely recycle every bitmap we own.
                val holder = arrayOfNulls<Bitmap>(1)
                try {
                    withContext(Dispatchers.Default) {
                        // Synchronous bake. applyHeatmap does NOT recycle the source.
                        holder[0] = frame.applyHeatmap()
                    }
                    val baked = holder[0] ?: return
                    if (currentMode != ViewMode.HEATMAP || !currentCoroutineContext().isActive) {
                        baked.recycle()
                        return
                    }
                    commitFrame(baked)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    holder[0]?.recycle()
                    throw e
                } finally {
                    // We always own the source frame here; recycle it in every path.
                    frame.recycle()
                }
            }
        } finally {
            // Only tear down our own reference so we never clobber a replacement
            // worker started by a later ensureHeatmapWorker() after a mode switch.
            if (currentCoroutineContext()[Job] == heatmapWorker) heatmapWorker = null
        }
    }

    // ── Count mode (objects-only edges + cumulative tally) ───────────────────

    /** Latest stats values, reused to re-render the count HUD on new tracks. */
    private var lastStatsFps: Float = 0f
    private var lastStatsMs: Long = 0L

    /** Re-renders the count-mode HUD from the cumulative tally. Main thread. */
    private fun refreshCountHud() {
        binding.tvStats.text = formatCountStats(lastStatsFps, lastStatsMs, countTally.snapshot())
    }

    /**
     * Receives a count-mode frame. Keeps only the newest frame (drop-oldest),
     * paired with the latest detections snapshot, and hands it to the serial
     * baker. Owns [frame] on entry; the baker recycles it.
     */
    private fun onCountFrame(frame: Bitmap) {
        pendingCountFrame?.recycle()
        pendingCountFrame = frame
        ensureCountWorker()
    }

    /** Starts the count baker if it is not already running. */
    private fun ensureCountWorker() {
        if (countWorker != null) return
        countWorker = viewLifecycleOwner.lifecycleScope.launch {
            runCountLoop()
        }
    }

    /**
     * Serial count loop (runs on main; bakes on a background thread). Bakes
     * the latest pending frame masked to the detections snapshot taken when
     * the frame arrived, then commits it. Same main-confined ownership pattern
     * as the heatmap loop, so the drop-oldest recycle cannot race the bake.
     */
    private suspend fun runCountLoop() {
        try {
            while (currentMode == ViewMode.COUNT) {
                val frame = pendingCountFrame ?: return
                pendingCountFrame = null
                val boxes = latestDetections

                val holder = arrayOfNulls<Bitmap>(1)
                try {
                    withContext(Dispatchers.Default) {
                        // Synchronous bake. applyCountEdges never recycles the source.
                        holder[0] = frame.applyCountEdges(boxes)
                    }
                    val baked = holder[0] ?: return
                    if (currentMode != ViewMode.COUNT || !currentCoroutineContext().isActive) {
                        baked.recycle()
                        return
                    }
                    commitFrame(baked)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    holder[0]?.recycle()
                    throw e
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            if (currentCoroutineContext()[Job] == countWorker) countWorker = null
        }
    }

    /** Takes ownership of [img] and shows it as the current filtered frame. */
    private fun commitFrame(img: Bitmap) {
        android.util.Log.i("ViewMode", "display frame ${img.width}x${img.height}")
        binding.imageFilterPreview.setImageBitmap(img)
        val old = displayedBitmap
        displayedBitmap = img
        old?.recycle()
    }

    /** Releases all owned frame state and clears the filtered image view. */
    private fun clearDisplayedFrame() {
        heatmapWorker?.cancel()
        heatmapWorker = null
        pendingHeatmapFrame?.recycle()
        pendingHeatmapFrame = null
        countWorker?.cancel()
        countWorker = null
        pendingCountFrame?.recycle()
        pendingCountFrame = null
        binding.imageFilterPreview.setImageBitmap(null)
        binding.imageFilterPreview.colorFilter = null
        val old = displayedBitmap
        displayedBitmap = null
        old?.recycle()
    }

    private fun captureAndSaveSnapshot() {
        val isFiltered = currentMode != ViewMode.NORMAL
        val frame = if (isFiltered) {
            // Filtered view: snapshot the displayed (already-heatmapped) frame.
            displayedBitmap?.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            // Capture the raw preview as a single bitmap.
            binding.previewView.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        } ?: return

        // Draw at the on-screen overlay size so detections align with the captured frame.
        val w = binding.overlay.width.takeIf { it > 0 } ?: frame.width
        val h = binding.overlay.height.takeIf { it > 0 } ?: frame.height

        // B&W / Invert are GPU color-matrix filters applied by the ImageView, so bake
        // them onto the snapshot the same way for a faithful capture. Heatmap and
        // Count are already baked into displayedBitmap at this point.
        val paint = when (currentMode) {
            ViewMode.BLACK_AND_WHITE -> android.graphics.Paint().apply {
                colorFilter = ViewModeEffects.blackAndWhiteColorFilter()
            }
            ViewMode.INVERT -> android.graphics.Paint().apply {
                colorFilter = ViewModeEffects.invertColorFilter()
            }
            else -> null
        }

        // Merge the bounding-box overlay over the frame.
        val merged = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(merged)
        if (w == frame.width && h == frame.height) {
            canvas.drawBitmap(frame, 0f, 0f, paint)
        } else {
            // center-crop to the overlay aspect, matching the ImageView's centerCrop.
            val scale = maxOf(w / frame.width.toFloat(), h / frame.height.toFloat())
            val dw = (frame.width * scale).toInt()
            val dh = (frame.height * scale).toInt()
            val left = (w - dw) / 2
            val top = (h - dh) / 2
            canvas.drawBitmap(frame, null, android.graphics.Rect(left, top, left + dw, top + dh), paint)
        }
        binding.overlay.draw(canvas)
        frame.recycle()

        viewModel.saveSnapshot(merged)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        heatmapWorker?.cancel()
        heatmapWorker = null
        pendingHeatmapFrame?.recycle()
        pendingHeatmapFrame = null
        countWorker?.cancel()
        countWorker = null
        pendingCountFrame?.recycle()
        pendingCountFrame = null
        displayedBitmap?.recycle()
        displayedBitmap = null
        _binding = null
    }
}