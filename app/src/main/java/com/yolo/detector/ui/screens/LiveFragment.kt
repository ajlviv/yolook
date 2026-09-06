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
import com.yolo.detector.ui.applyHeatmap
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
                    }
                }
                launch {
                    viewModel.statsFlow.collect { stats ->
                        binding.tvStats.text = "FPS: ${"%.1f".format(stats.fps)}  " +
                                "Latency: ${stats.inferenceMs}ms  " +
                                "Objects: ${stats.objectCount}"
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
        if (mode == currentMode) return
        android.util.Log.i("ViewMode", "applyViewMode=$mode")
        currentMode = mode

        // Full reset: releases the previous mode's frames and stops any bake.
        clearDisplayedFrame()

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
        // them onto the snapshot the same way for a faithful capture. Heatmap is
        // already baked into displayedBitmap at this point.
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
        displayedBitmap?.recycle()
        displayedBitmap = null
        _binding = null
    }
}