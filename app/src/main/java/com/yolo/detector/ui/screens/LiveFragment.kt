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
    private var currentHideCamera: Boolean = false
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

        // This binding is freshly inflated from XML, where the driver-only overlays
        // (driverIcons / driverHud) are declared `visibility="gone"`. The fragment
        // instance is REUSED across navigation (Live -> Settings -> Live), so the
        // cached mode fields still hold the previous values; if we kept them, the
        // settings-flow re-apply in onViewCreated would early-return and leave the
        // new binding's overlays GONE вЂ” hiding the Signals bar and driver icons after
        // returning from Settings. Reset them so applyViewMode() always re-runs fully.
        currentMode = ViewMode.NORMAL
        currentHideCamera = false

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
                        applyViewMode(settings.viewMode, settings.driverModeHideCamera)
                    }
                }
                launch {
                    viewModel.frameFlow.collect { bitmap ->
                        if (currentMode == ViewMode.DRIVER) {
                            // Driver mode shows the HUD + object overlays. With the raw
                            // preview hidden ("hide camera view"), the dimmed analysis
                            // frame is the background so the screen is never black;
                            // otherwise the copies are not displayed and are recycled.
                            if (bitmap != null) {
                                if (currentHideCamera) {
                                    binding.imageFilterPreview.visibility = View.VISIBLE
                                    commitFrame(bitmap)
                                } else {
                                    // Not displayed. Do NOT recycle: the frame is owned by
                                    // the ViewModel's cached flow state and may be replayed
                                    // to a recreated view; GC reclaims the unreferenced one.
                                }
                            } else if (currentHideCamera) {
                                clearDisplayedFrame()
                                binding.imageFilterPreview.visibility = View.GONE
                            }
                            return@collect
                        }
                        if (bitmap != null) {
                            // Frame copies are only emitted while a filtered mode is
                            // active, so the coverage overlay must be on. Keying this
                            // off frame arrival instead of the async settingsFlow
                            // closes the startup/switch gap where the camera is already
                            // filtering but applyViewMode() hasn't run yet вЂ” otherwise
                            // the raw camera feed shows through during that window.
                            binding.imageFilterPreview.visibility = View.VISIBLE
                            when (currentMode) {
                                ViewMode.HEATMAP -> onHeatmapFrame(bitmap)
                                ViewMode.NORMAL -> { /* stale copy; let GC reclaim it */ }
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
                        if (currentMode == ViewMode.DRIVER) {
                            binding.driverIcons.setDetections(detections)
                        } else {
                            binding.overlay.setDetections(detections)
                        }
                    }
                }
                launch {
                    viewModel.driverSceneFlow.collect { scene ->
                        binding.driverHud.setScene(scene)
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
    private fun applyViewMode(mode: ViewMode, hideCamera: Boolean) {
        val modeChanged = mode != currentMode
        val hideChanged = hideCamera != currentHideCamera
        if (!modeChanged && !hideChanged) return
        android.util.Log.i("ViewMode", "applyViewMode=$mode hideCamera=$hideCamera")
        currentMode = mode
        currentHideCamera = hideCamera

        if (modeChanged) {
            // Full reset: releases the previous mode's frames and stops any bake.
            clearDisplayedFrame()
        }

        val isDriver = mode == ViewMode.DRIVER
        val driverHidden = isDriver && hideCamera

        // Filtered renderer is used by the GPU color-matrix filters (B&W / Invert),
        // the heatmap path, and the dimmed driver background when the camera view
        // is hidden — so the driver never faces an empty black screen.
        val useFilterPreview = mode == ViewMode.BLACK_AND_WHITE ||
                mode == ViewMode.INVERT ||
                mode == ViewMode.HEATMAP ||
                driverHidden

        binding.imageFilterPreview.colorFilter = when {
            mode == ViewMode.BLACK_AND_WHITE -> ViewModeEffects.blackAndWhiteColorFilter()
            mode == ViewMode.INVERT -> ViewModeEffects.invertColorFilter()
            driverHidden -> ViewModeEffects.dimColorFilter()
            else -> null
        }
        binding.imageFilterPreview.visibility = if (useFilterPreview) View.VISIBLE else View.GONE

        // Bounding-box overlay only in non-DRIVER modes; driver uses icons instead.
        binding.overlay.visibility = if (isDriver) View.GONE else View.VISIBLE

        // Driver-mode HUD overlays.
        binding.driverIcons.visibility = if (isDriver) View.VISIBLE else View.GONE
        binding.driverHud.visibility = if (isDriver) View.VISIBLE else View.GONE
        // With the camera hidden, render the detected objects as visible boxes too.
        binding.driverIcons.setRenderBoxes(isDriver && hideCamera)

        // Camera preview: in driver hidden mode the opaque filtered view fully covers
        // the preview, so the user never sees it. It must still stay "visible" to the
        // framework though — setting it GONE destroys the PreviewView's surface and the
        // capture session (Preview + ImageAnalysis) then fails to configure with
        // "Unable to configure camera, timeout!", which stops ALL frames: no
        // recognition, no HUD, permanent black screen.
        binding.previewView.visibility = View.VISIBLE
    }

    /**
     * Receives a heatmap-mode frame. Keeps only the newest frame (drop-oldest) and
     * hands it to the serial baker. Owns [frame] on entry; the baker recycles it.
     */
    private fun onHeatmapFrame(frame: Bitmap) {
        // Do NOT recycle the previous pending frame: it is (or was) the flow's
        // cached value and may be replayed to a recreated view. Drop the
        // reference and let GC reclaim it.
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
                    // Drop the source frame without recycling: it is (or was) the
                    // flow's cached value and must remain valid for replay into a
                    // freshly created view. GC reclaims it once unreferenced.
                    frame
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
        // The replaced bitmap was only referenced by the ImageView and us, so
        // recycling it here is safe — the flow has already moved past it.
        val old = displayedBitmap
        displayedBitmap = img
        old?.recycle()
    }

    /** Releases owned frame state and clears the filtered image view. */
    private fun clearDisplayedFrame() {
        heatmapWorker?.cancel()
        heatmapWorker = null
        // pendingHeatmapFrame is flow-owned (cached by the ViewModel's StateFlow) —
        // drop the reference without recycling so a replayed value stays drawable.
        pendingHeatmapFrame = null
        binding.imageFilterPreview.setImageBitmap(null)
        binding.imageFilterPreview.colorFilter = null
        // Do NOT recycle the displayed bitmap: the ViewModel's StateFlow still
        // caches it and replays it to freshly created collectors (e.g. after the
        // Settings round-trip). Recycling here poisoned the replay and produced
        // a permanently black screen. Drop the reference; GC reclaims it.
        displayedBitmap = null
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
        // Flow-owned bitmaps (pendingHeatmapFrame, displayedBitmap) stay unrecycled:
        // the ViewModel's StateFlow still caches them for replay into the next view.
        pendingHeatmapFrame = null
        // displayedBitmap is flow-owned and still cached by the ViewModel's
        // StateFlow — it must stay valid for replay into a recreated view.
        displayedBitmap = null
        _binding = null
    }
}
