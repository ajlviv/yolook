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
import com.yolo.detector.data.DetectionView
import com.yolo.detector.data.ViewMode
import com.yolo.detector.databinding.FragmentLiveBinding
import com.yolo.detector.ui.MainViewModel
import com.yolo.detector.ui.ViewModeEffects
import com.yolo.detector.ui.applyEdgeDetection
import com.yolo.detector.ui.applyHeatmap
import com.yolo.detector.ui.applyMatrixEffect
import com.yolo.detector.ui.applyObjectsOnlyMask
import com.yolo.detector.ui.countByClass
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

    // Tracked detection-view (Settings "Detection view").
    private var currentDetectionView: DetectionView = DetectionView.LABELS

    // Heatmap rendering. Baking is slow (per-pixel), so a single serial worker
    // bakes the newest pending frame and drops intermediate ones. This lets the
    // heatmap update at the bake rate regardless of the analysis FPS. All pending
    // frame / worker state lives on the main thread; only the bake itself runs on
    // a background thread, so drop-oldest recycling cannot race the baker.
    private var pendingHeatmapFrame: Bitmap? = null
    private var heatmapWorker: Job? = null

    // Edge view: serial bake worker (same drop-oldest pattern as heatmap).
    // Frames are paired with the latest detections snapshot taken on the main
    // thread, so masking boxes align with the baked frame's inference pass.
    private var pendingEdgeFrame: Bitmap? = null
    private var edgeWorker: Job? = null

    // Matrix view: serial bake worker (same drop-oldest pattern as others).
    private var pendingMatrixFrame: Bitmap? = null
    private var matrixWorker: Job? = null

    // Quality parameters for the bake loops, kept in sync with the persisted
    // Settings. Read on the main thread when each bake is launched, so changes
    // take effect on the next incoming frame.
    private var currentEdgeThreshold: Int = 100
    private var currentEdgeDetail: Int = 3
    private var currentHeatmapDetail: Int = 3
    private var currentMatrixDetail: Int = 8
    private var currentMatrixGamma: Float = 0.74f

    // Objects-only detection view: same worker pattern; reuses the edge
    // worker when Edge Detection view is active, otherwise its own.
    private var pendingObjectsOnlyFrame: Bitmap? = null
    private var objectsOnlyWorker: Job? = null
    private var latestDetections: List<com.yolo.detector.data.Detection> = emptyList()

    // Count toggle: display-only flag. When on, boxes show per-object running
    // numbers inside them and the HUD shows this frame's per-class counts.
    // The view mode is never touched by the toggle.
    private var countingEnabled: Boolean = false

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

        // Sync the overlay before any collector runs: the ViewModel is shared
        // (activity-scoped), so its settings snapshot already holds the
        // persisted values. Without this, fresh fragment views start at
        // LABELS and draw a stale box+label frame until the async
        // settingsFlow emits — visible as a flicker when OBJECTS_ONLY
        // (or any non-default detection view) is persisted.
        binding.overlay.detectionView = viewModel.currentSettingsSnapshot.detectionView
        currentMode = viewModel.currentSettingsSnapshot.viewMode
        currentDetectionView = viewModel.currentSettingsSnapshot.detectionView
        // Pre-seed the bake-quality params from the persisted snapshot so the
        // first bakes (before settingsFlow emits) already use stored values.
        val seed = viewModel.currentSettingsSnapshot
        currentEdgeThreshold = seed.edgeThreshold
        currentEdgeDetail = seed.edgeDetail
        currentHeatmapDetail = seed.heatmapDetail
        currentMatrixDetail = seed.matrixDetail
        currentMatrixGamma = seed.matrixGamma

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

        // Count toggle above the snapshot FAB: 'D' = detect labels, 'C' = count
        // numbers inside boxes with per-frame class counts in the HUD.
        // Display-only switch — the view mode is left untouched.
        binding.fabModeToggle.setOnClickListener {
            countingEnabled = !countingEnabled
            binding.overlay.countLabelsEnabled = countingEnabled
            binding.fabModeToggle.text = if (countingEnabled) "C" else "D"
            // Re-render the HUD immediately so the switch feels instant.
            refreshCountHud()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settingsFlow.collect { settings ->
                        applyViewMode(settings.viewMode)
                        applyDetectionView(settings.detectionView)
                        currentEdgeThreshold = settings.edgeThreshold
                        currentEdgeDetail = settings.edgeDetail
                        currentHeatmapDetail = settings.heatmapDetail
                        currentMatrixDetail = settings.matrixDetail
                        currentMatrixGamma = settings.matrixGamma
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
                            when {
                                currentMode == ViewMode.HEATMAP -> onHeatmapFrame(bitmap)
                                currentMode == ViewMode.EDGE -> onEdgeFrame(bitmap)
                                currentMode == ViewMode.MATRIX -> onMatrixFrame(bitmap)
                                currentDetectionView == DetectionView.OBJECTS_ONLY ->
                                    onObjectsOnlyFrame(bitmap)
                                currentMode == ViewMode.NORMAL -> bitmap.recycle() // camera filtered, UI not yet updated
                                else -> commitFrame(bitmap)
                            }
                        } else if (currentMode == ViewMode.NORMAL &&
                            currentDetectionView != DetectionView.OBJECTS_ONLY) {
                            // Camera switched back to the plain preview with no
                            // masking active: mirror it by clearing the filtered image.
                            clearDisplayedFrame()
                            binding.imageFilterPreview.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.detectionFlow.collect { detections ->
                        binding.overlay.setDetections(detections)
                        latestDetections = detections
                        if (showCountHud()) refreshCountHud()
                    }
                }
                launch {
                    viewModel.statsFlow.collect { stats ->
                        // Latest stats are cached so the count HUD can re-render
                        // on new frames too (per-frame per-class counts).
                        lastStatsFps = stats.fps
                        lastStatsMs = stats.inferenceMs
                        if (showCountHud()) {
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
    /** Maps an edge-detail level (1..3) to a bake downscale: 1 → 4x, 2 → 3x, 3 → 2x. */
    private fun edgeDownscale(level: Int) = when (level) {
        1 -> 4
        2 -> 3
        else -> 2
    }

    /** Maps a heatmap-detail level (1..3) to a bake downscale: 1 → 6x, 2 → 4x, 3 → 2x. */
    private fun heatmapDownscale(level: Int) = when (level) {
        1 -> 6
        2 -> 4
        else -> 2
    }

    /** Maps a matrix-detail level (1..10) to a glyph cell size: higher = finer. */
    private fun matrixCellSize(detail: Int) = (16 - detail).coerceIn(6, 15)

    /** Switches between the raw preview and the filtered frame renderer overlay. */
    private fun applyViewMode(mode: ViewMode) {
        if (mode == currentMode) {
            // Still sync visuals: a fresh fragment view pre-seeds currentMode
            // from the ViewModel snapshot, so without this the B&W/Invert
            // color filter and overlay visibility would never be applied and
            // those modes would look like Normal.
            syncViewModeVisuals()
            return
        }
        android.util.Log.i("ViewMode", "applyViewMode=$mode")
        currentMode = mode

        // Full reset: releases the previous mode's frames and stops any bake.
        // The count toggle is intentionally left alone — it is a Live-tab
        // display switch, independent of the Settings view mode.
        clearDisplayedFrame()

        syncViewModeVisuals()
    }

    /**
     * Applies the ImageView color filter and visibility for [currentMode].
     * Split out so fresh views (which pre-sync [currentMode] in onViewCreated)
     * still get the B&W/Invert GPU filter and the overlay visibility.
     */
    private fun syncViewModeVisuals() {
        binding.imageFilterPreview.colorFilter = when (currentMode) {
            ViewMode.BLACK_AND_WHITE -> ViewModeEffects.blackAndWhiteColorFilter()
            ViewMode.INVERT -> ViewModeEffects.invertColorFilter()
            else -> null
        }
        binding.imageFilterPreview.visibility =
            if (currentMode != ViewMode.NORMAL || currentDetectionView == DetectionView.OBJECTS_ONLY) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    /**
     * Applies the Settings "Detection view". OBJECTS_ONLY additionally forces
     * the filtered-frame path (black outside boxes) even in [ViewMode.NORMAL];
     * the other options only change the overlay labels and HUD.
     *
     * The overlay is synced unconditionally (not only on change) so a fresh
     * fragment view that pre-synced from [MainViewModel.currentSettingsSnapshot]
     * in onViewCreated can never drift from the collector state.
     */
    private fun applyDetectionView(view: DetectionView) {
        binding.overlay.detectionView = view
        if (view == currentDetectionView) return
        android.util.Log.i("ViewMode", "applyDetectionView=$view")
        currentDetectionView = view

        // Boxes-only masking needs its own bake loop outside baked view modes.
        if (view == DetectionView.OBJECTS_ONLY) {
            binding.imageFilterPreview.visibility = View.VISIBLE
        } else {
            objectsOnlyWorker?.cancel()
            objectsOnlyWorker = null
            pendingObjectsOnlyFrame?.recycle()
            pendingObjectsOnlyFrame = null
            if (currentMode == ViewMode.NORMAL) {
                // Back to the raw preview: drop any masked frame.
                clearDisplayedFrame()
                binding.imageFilterPreview.visibility = View.GONE
            }
        }

        if (showCountHud()) refreshCountHud()
    }

    /**
     * Whether the HUD shows per-frame per-class counts: the Live-tab "C"
     * toggle or Settings "Detection view" = "Box with count".
     */
    private fun showCountHud(): Boolean =
        countingEnabled || currentDetectionView == DetectionView.COUNT

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
                        holder[0] = frame.applyHeatmap(downscale = heatmapDownscale(currentHeatmapDetail))
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

    // ── Edge view (full-screen edges) + objects-only masking ────────────────

    /** Latest stats values, reused to re-render the count HUD on new frames. */
    private var lastStatsFps: Float = 0f
    private var lastStatsMs: Long = 0L

    /**
     * Re-renders the HUD from this frame's detections. Main thread. When the
     * count HUD is off this is a no-op (the stats collector draws the
     * default single line itself).
     */
    private fun refreshCountHud() {
        if (!showCountHud()) return
        binding.tvStats.text = formatCountStats(lastStatsFps, lastStatsMs, countByClass(latestDetections))
    }

    /**
     * Receives an edge-view frame. Keeps only the newest frame (drop-oldest),
     * paired with the latest detections snapshot, and hands it to the serial
     * baker. Owns [frame] on entry; the baker recycles it.
     */
    private fun onEdgeFrame(frame: Bitmap) {
        pendingEdgeFrame?.recycle()
        pendingEdgeFrame = frame
        ensureEdgeWorker()
    }

    /** Starts the edge baker if it is not already running. */
    private fun ensureEdgeWorker() {
        if (edgeWorker != null) return
        edgeWorker = viewLifecycleOwner.lifecycleScope.launch {
            runEdgeLoop()
        }
    }

    /**
     * Serial edge loop (runs on main; bakes on a background thread). Renders
     * full-screen edges, or edges masked to detections when "Detection view"
     * is "Only objects". Same main-confined ownership pattern as the heatmap
     * loop, so the drop-oldest recycle cannot race the bake.
     */
    private suspend fun runEdgeLoop() {
        try {
            while (currentMode == ViewMode.EDGE) {
                val frame = pendingEdgeFrame ?: return
                pendingEdgeFrame = null
                // Masked only for "Only objects"; otherwise the whole screen.
                val boxes = if (currentDetectionView == DetectionView.OBJECTS_ONLY) {
                    latestDetections.map { it.bbox }
                } else {
                    null
                }

                val holder = arrayOfNulls<Bitmap>(1)
                try {
                    withContext(Dispatchers.Default) {
                        // Synchronous bake. applyEdgeDetection never recycles the source.
                        holder[0] = frame.applyEdgeDetection(
                            boxes,
                            downscale = edgeDownscale(currentEdgeDetail),
                            threshold = currentEdgeThreshold,
                        )
                    }
                    val baked = holder[0] ?: return
                    if (currentMode != ViewMode.EDGE || !currentCoroutineContext().isActive) {
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
            if (currentCoroutineContext()[Job] == edgeWorker) edgeWorker = null
        }
    }

    /**
     * Receives an objects-only frame (Normal/B&W/Invert view + "Detection
     * view" = "Only objects"). Same drop-oldest ownership as [onEdgeFrame].
     */
    private fun onObjectsOnlyFrame(frame: Bitmap) {
        pendingObjectsOnlyFrame?.recycle()
        pendingObjectsOnlyFrame = frame
        ensureObjectsOnlyWorker()
    }

    /** Starts the objects-only baker if it is not already running. */
    private fun ensureObjectsOnlyWorker() {
        if (objectsOnlyWorker != null) return
        objectsOnlyWorker = viewLifecycleOwner.lifecycleScope.launch {
            runObjectsOnlyLoop()
        }
    }

    /**
     * Serial objects-only loop: blacks out everything outside detection boxes,
     * preserving the active B&W/Invert color filter via the ImageView (the
     * mask runs on the unfiltered frame, the filter is applied by the GPU at
     * draw time). Exits when the detection view or view mode changes.
     */
    private suspend fun runObjectsOnlyLoop() {
        try {
            while (currentDetectionView == DetectionView.OBJECTS_ONLY &&
                currentMode != ViewMode.EDGE && currentMode != ViewMode.HEATMAP &&
                currentMode != ViewMode.MATRIX) {
                val frame = pendingObjectsOnlyFrame ?: return
                pendingObjectsOnlyFrame = null
                val boxes = latestDetections.map { it.bbox }

                val holder = arrayOfNulls<Bitmap>(1)
                try {
                    withContext(Dispatchers.Default) {
                        holder[0] = frame.applyObjectsOnlyMask(boxes)
                    }
                    val baked = holder[0] ?: return
                    if (currentDetectionView != DetectionView.OBJECTS_ONLY ||
                        currentMode == ViewMode.EDGE || currentMode == ViewMode.HEATMAP ||
                        currentMode == ViewMode.MATRIX ||
                        !currentCoroutineContext().isActive) {
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
            if (currentCoroutineContext()[Job] == objectsOnlyWorker) objectsOnlyWorker = null
        }
    }

    // ── Matrix view (digital rain) ─────────────────────────────────────────

    /**
     * Receives a Matrix-view frame. Keeps only the newest frame (drop-oldest)
     * and hands it to the serial baker. Owns [frame] on entry; the baker
     * recycles it.
     */
    private fun onMatrixFrame(frame: Bitmap) {
        pendingMatrixFrame?.recycle()
        pendingMatrixFrame = frame
        ensureMatrixWorker()
    }

    /** Starts the Matrix baker if it is not already running. */
    private fun ensureMatrixWorker() {
        if (matrixWorker != null) return
        matrixWorker = viewLifecycleOwner.lifecycleScope.launch {
            runMatrixLoop()
        }
    }

    /**
     * Serial Matrix loop (runs on main; bakes on a background thread). Renders
     * the frame as a grid of green glyphs, committing the newest bake and
     * looping for any newer frame. Same main-confined ownership pattern as the
     * heatmap loop, so the drop-oldest recycle cannot race the bake.
     */
    private suspend fun runMatrixLoop() {
        try {
            while (currentMode == ViewMode.MATRIX) {
                val frame = pendingMatrixFrame ?: return
                pendingMatrixFrame = null

                val holder = arrayOfNulls<Bitmap>(1)
                try {
                    withContext(Dispatchers.Default) {
                        holder[0] = frame.applyMatrixEffect(
                            cellSize = matrixCellSize(currentMatrixDetail),
                            gamma = currentMatrixGamma,
                        )
                    }
                    val baked = holder[0] ?: return
                    if (currentMode != ViewMode.MATRIX || !currentCoroutineContext().isActive) {
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
            if (currentCoroutineContext()[Job] == matrixWorker) matrixWorker = null
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
        edgeWorker?.cancel()
        edgeWorker = null
        pendingEdgeFrame?.recycle()
        pendingEdgeFrame = null
        matrixWorker?.cancel()
        matrixWorker = null
        pendingMatrixFrame?.recycle()
        pendingMatrixFrame = null
        objectsOnlyWorker?.cancel()
        objectsOnlyWorker = null
        pendingObjectsOnlyFrame?.recycle()
        pendingObjectsOnlyFrame = null
        binding.imageFilterPreview.setImageBitmap(null)
        binding.imageFilterPreview.colorFilter = null
        val old = displayedBitmap
        displayedBitmap = null
        old?.recycle()
    }

    private fun captureAndSaveSnapshot() {
        // Masked when a baked view renders the frame (any view mode, or
        // OBJECTS_ONLY masking over the Normal preview).
        val isFiltered = currentMode != ViewMode.NORMAL ||
                currentDetectionView == DetectionView.OBJECTS_ONLY
        val frame = if (isFiltered) {
            // Filtered view: snapshot the displayed (already-baked) frame.
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
        // Edge are already baked into displayedBitmap at this point.
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
        edgeWorker?.cancel()
        edgeWorker = null
        pendingEdgeFrame?.recycle()
        pendingEdgeFrame = null
        matrixWorker?.cancel()
        matrixWorker = null
        pendingMatrixFrame?.recycle()
        pendingMatrixFrame = null
        objectsOnlyWorker?.cancel()
        objectsOnlyWorker = null
        pendingObjectsOnlyFrame?.recycle()
        pendingObjectsOnlyFrame = null
        displayedBitmap?.recycle()
        displayedBitmap = null
        _binding = null
    }
}