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
    // heatmap update at the bake rate regardless of the analysis FPS, and avoids
    // the old cancel-per-frame behaviour that left the raw preview showing.
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
                        if (bitmap != null && currentMode != ViewMode.NORMAL) {
                            if (currentMode == ViewMode.HEATMAP) {
                                onHeatmapFrame(bitmap)
                            } else {
                                commitFrame(bitmap)
                            }
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
     * hands it to the serial baker; intermediate frames are recycled.
     * Owns [frame] on entry.
     */
    private fun onHeatmapFrame(frame: Bitmap) {
        val old = pendingHeatmapFrame
        pendingHeatmapFrame = frame
        old?.recycle()
        ensureHeatmapWorker()
    }

    /** Starts the baker if it is not already running. */
    private fun ensureHeatmapWorker() {
        if (heatmapWorker != null) return
        heatmapWorker = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            runHeatmapLoop()
        }
    }

    /**
     * Serial heatmap loop: bakes the latest pending frame, commits it on the UI
     * thread, then loops for any newer frame. Exits when out of frames or the mode
     * changes. Cancellations are not possible mid-bake because we never cancel the
     * running job while it is needed — only mode switches tear it down.
     */
    private suspend fun runHeatmapLoop() {
        try {
            while (currentMode == ViewMode.HEATMAP) {
                val frame = pendingHeatmapFrame ?: return
                pendingHeatmapFrame = null

                // Synchronous, non-suspending: cannot be cancelled mid-bake.
                // Takes ownership of `frame` (recycles it internally if it downscaled).
                val baked = frame.applyHeatmap()

                var committed = false
                try {
                    withContext(Dispatchers.Main) {
                        if (!isActive || currentMode != ViewMode.HEATMAP) return@withContext
                        commitFrame(baked)
                        committed = true
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!committed) baked.recycle()
                    throw e
                }
                if (!committed) baked.recycle()
            }
        } finally {
            heatmapWorker = null
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
            displayedBitmap?.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            // Capture the raw preview as a single bitmap.
            binding.previewView.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        } ?: return

        // Merge the bounding-box overlay over the frame.
        val merged = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(merged)
        canvas.drawBitmap(frame, 0f, 0f, null)
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