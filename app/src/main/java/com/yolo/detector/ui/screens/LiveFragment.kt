package com.yolo.detector.ui.screens

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yolo.detector.R
import com.yolo.detector.data.CaptureMode
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
import com.yolo.detector.video.FrameVideoRecorder
import com.yolo.detector.video.videoBitrateFor
import com.yolo.detector.video.videoWidthForHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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

    // Capture mode (photo/video) driven by the toggle above the action FAB and
    // persisted in Settings. The action FAB snapshots in PHOTO mode and records
    // a video (start/stop) in VIDEO mode.
    private var currentCaptureMode: CaptureMode = CaptureMode.PHOTO

    // Active video-recording state. Frames are fed to the encoder at the exact
    // point they are displayed (commitFrame for filtered modes; the raw camera
    // frame feed for NORMAL), so the file matches what's on screen.
    private var videoRecorder: FrameVideoRecorder? = null
    private var recordUri: Uri? = null
    private var recordWidth = 0
    private var recordHeight = 0
    private var isRecording = false
    private var recordingStartedAtMs = 0L
    private var recordingTimerJob: Job? = null

    // Finalizes the MP4 after the view may already be gone (navigation away
    // while recording). Own coroutine scope so it survives onDestroyView.
    private val finalizeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        currentCaptureMode = viewModel.currentSettingsSnapshot.captureMode
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

        // Action FAB: snapshot in PHOTO mode; start/stop recording in VIDEO mode.
        binding.fabSnapshot.setOnClickListener {
            when (currentCaptureMode) {
                CaptureMode.PHOTO -> captureAndSaveSnapshot()
                CaptureMode.VIDEO -> if (isRecording) stopVideoRecording() else startVideoRecording()
            }
        }

        // Capture-mode toggle above the action FAB: photo snapshot ↔ video
        // recording. Icons (camera / videocam) replace the old 'D'/'C' letters.
        // The mode itself is persisted through the ViewModel.
        binding.fabModeToggle.setOnClickListener {
            viewModel.setCaptureMode(
                if (currentCaptureMode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO
            )
        }

        // Refresh the toggle/FAB visuals once the persisted capture mode is known.
        applyCaptureMode()

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
                        currentCaptureMode = settings.captureMode
                        applyCaptureMode()
                    }
                }
                launch {
                    viewModel.frameFlow.collect { bitmap ->
                        if (bitmap != null) {
                            // Frame copies are emitted while a filtered mode is
                            // active (or during recording), so the filtered preview
                            // must be on unless we're in a plain NORMAL preview.
                            if (currentMode != ViewMode.NORMAL) {
                                binding.imageFilterPreview.visibility = View.VISIBLE
                            }
                            when {
                                currentMode == ViewMode.HEATMAP -> onHeatmapFrame(bitmap)
                                currentMode == ViewMode.EDGE -> onEdgeFrame(bitmap)
                                currentMode == ViewMode.MATRIX -> onMatrixFrame(bitmap)
                                currentDetectionView == DetectionView.OBJECTS_ONLY ->
                                    onObjectsOnlyFrame(bitmap)
                                currentMode == ViewMode.NORMAL && isRecording -> {
                                    // Raw camera frames at the recording FPS: bake
                                    // the overlay in so the file matches the live view.
                                    recordFrame(bitmap)
                                    bitmap.recycle()
                                }
                                currentMode == ViewMode.NORMAL -> bitmap.recycle() // camera filtered, UI not yet updated
                                else -> commitFrame(bitmap)
                            }
                        } else if (currentMode == ViewMode.NORMAL && !isRecording &&
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
                            // Without an analysing pipeline there are no frames to
                            // record; finalize whatever was captured so far.
                            if (isRecording) stopVideoRecording(showToast = false)
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
     * Whether the HUD shows per-frame per-class counts: Settings "Detection view"
     * = "Box with count" (the Live-tab count toggle was removed when video
     * recording replaced it).
     */
    private fun showCountHud(): Boolean =
        currentDetectionView == DetectionView.COUNT

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
        // Recording feeds the encoder with the exact frame being displayed, so
        // the video matches what's on screen.
        if (isRecording) recordFrame(img)
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
        val paint = viewModePaint()

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

    // ── Video recording ─────────────────────────────────────────────────────

    /**
     * Returns the paint that reproduces the ImageView's GPU color-matrix filter
     * for the current view mode: B&W and Invert bake their filter into frames
     * drawn for snapshots/recording; everything else (baked modes, NORMAL) needs
     * no filter.
     */
    private fun viewModePaint(): Paint? = when (currentMode) {
        ViewMode.BLACK_AND_WHITE -> Paint().apply {
            colorFilter = ViewModeEffects.blackAndWhiteColorFilter()
        }
        ViewMode.INVERT -> Paint().apply {
            colorFilter = ViewModeEffects.invertColorFilter()
        }
        else -> null
    }

    /** Starts recording "what's on screen" to an MP4 in Movies/YOLO. */
    private fun startVideoRecording() {
        if (isRecording || currentCaptureMode != CaptureMode.VIDEO) return
        val snapshot = viewModel.currentSettingsSnapshot
        val fps = snapshot.videoFps.coerceIn(1, 60)
        val targetHeight = snapshot.videoResolution.height

        val overlayW = binding.overlay.width.takeIf { it > 0 } ?: binding.previewView.width
        val overlayH = binding.overlay.height.takeIf { it > 0 } ?: binding.previewView.height
        if (overlayW <= 0 || overlayH <= 0) {
            recordingStartFailed()
            return
        }
        val recW = videoWidthForHeight(targetHeight, overlayW / overlayH.toFloat())
        val recH = targetHeight

        val uri = viewModel.createVideoUri()
        if (uri == null) {
            recordingStartFailed()
            return
        }
        val pfd = runCatching { requireContext().contentResolver.openFileDescriptor(uri, "rw") }
            .getOrNull()
        if (pfd == null) {
            viewModel.commitVideoOutput(uri, false)
            recordingStartFailed()
            return
        }

        val recorder = FrameVideoRecorder(recW, recH, fps, videoBitrateFor(recH, fps), pfd)
        // On failure the recorder already released resources (incl. the fd).
        if (!recorder.start()) {
            viewModel.commitVideoOutput(uri, false)
            recordingStartFailed()
            return
        }

        videoRecorder = recorder
        recordUri = uri
        recordWidth = recW
        recordHeight = recH
        isRecording = true
        viewModel.setRecordingFps(fps)
        binding.root.keepScreenOn = true
        updateFabVisuals()
        startRecordingTimer()
        // Seed the first frame so the file has content even for a quick tap.
        presentCurrentFrameForRecording()
    }

    /** Stops recording and finalizes the MP4 on a detached scope. */
    private fun stopVideoRecording(showToast: Boolean = true) {
        val recorder = videoRecorder ?: return
        val uri = recordUri
        val ctx = context
        videoRecorder = null
        recordUri = null
        isRecording = false
        viewModel.setRecordingFps(0)
        binding.root.keepScreenOn = false
        stopRecordingTimer()
        updateFabVisuals()

        finalizeScope.launch {
            val ok = recorder.stop()
            viewModel.commitVideoOutput(uri, ok)
            if (showToast && ctx != null) {
                val msg = if (ok) R.string.video_recording_saved else R.string.video_recording_failed
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun recordingStartFailed() {
        android.util.Log.w("Recording", "Video recording could not start")
        Toast.makeText(requireContext(), R.string.video_recording_start_failed, Toast.LENGTH_SHORT).show()
    }

    /**
     * Bakes the current frame into the encoder. Draws [source] center-cropped to
     * the recording bounds, applies the current view-mode filter (B&W/Invert),
     * then draws the live overlay on top — byte-for-byte mirroring how
     * [captureAndSaveSnapshot] captures a snapshot.
     *
     * [source] ownership stays with the caller (it must recycle it).
     */
    private fun recordFrame(source: Bitmap) {
        val recorder = videoRecorder ?: return
        if (recordWidth <= 0 || recordHeight <= 0) return
        val filter = viewModePaint()
        val presented = recorder.present { canvas ->
            canvas.drawColor(Color.BLACK)
            val scale = maxOf(
                recordWidth / source.width.toFloat(),
                recordHeight / source.height.toFloat(),
            )
            val dw = source.width * scale
            val dh = source.height * scale
            val left = (recordWidth - dw) / 2f
            val top = (recordHeight - dh) / 2f
            canvas.drawBitmap(source, null, RectF(left, top, left + dw, top + dh), filter)
            binding.overlay.draw(canvas)
        }
        if (!presented) {
            android.util.Log.w("Recording", "Frame dropped (encoder busy or stopping)")
        }
    }

    /** Feeds the encoder one frame right at recording start (before the next analysis frame). */
    private fun presentCurrentFrameForRecording() {
        if (videoRecorder == null) return
        val filtered = currentMode != ViewMode.NORMAL ||
            currentDetectionView == DetectionView.OBJECTS_ONLY
        val source = if (filtered) {
            displayedBitmap?.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            binding.previewView.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        }
        if (source != null) {
            recordFrame(source)
            source.recycle()
        }
    }

    /** Keeps the toggle/FAB icons and descriptions in sync with [currentCaptureMode]. */
    private fun applyCaptureMode() {
        val videoMode = currentCaptureMode == CaptureMode.VIDEO
        binding.fabModeToggle.setIconResource(
            if (videoMode) R.drawable.ic_videocam else R.drawable.ic_photo_camera
        )
        binding.fabModeToggle.contentDescription = getString(
            if (videoMode) R.string.fab_switch_photo else R.string.fab_switch_video
        )
        updateFabVisuals()
    }

    /** Updates the action FAB look for photo / video / recording states. */
    private fun updateFabVisuals() {
        val (icon, iconColor, bgColor) = when {
            isRecording ->
                Triple(R.drawable.ic_stop, Color.WHITE, 0xFFD32F2F.toInt())
            currentCaptureMode == CaptureMode.VIDEO ->
                Triple(R.drawable.ic_record, Color.WHITE, 0xFFD32F2F.toInt())
            else ->
                Triple(R.drawable.ic_photo_camera, Color.BLACK, 0xFF00E676.toInt())
        }
        binding.fabSnapshot.setImageResource(icon)
        binding.fabSnapshot.imageTintList = ColorStateList.valueOf(iconColor)
        binding.fabSnapshot.backgroundTintList = ColorStateList.valueOf(bgColor)
        binding.fabSnapshot.contentDescription = getString(
            when {
                isRecording -> R.string.fab_stop_recording
                currentCaptureMode == CaptureMode.VIDEO -> R.string.fab_record_video
                else -> R.string.fab_snapshot
            }
        )
    }

    private fun startRecordingTimer() {
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        recordingTimerJob?.cancel()
        recordingTimerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val secs = (SystemClock.elapsedRealtime() - recordingStartedAtMs) / 1000
                binding.tvRecording.text = getString(R.string.recording_indicator, formatElapsed(secs))
                binding.tvRecording.visibility = View.VISIBLE
                delay(500)
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        binding.tvRecording.visibility = View.GONE
    }

    private fun formatElapsed(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    override fun onDestroyView() {
        // Finalize any in-progress recording before the camera is torn down.
        stopVideoRecording(showToast = false)
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