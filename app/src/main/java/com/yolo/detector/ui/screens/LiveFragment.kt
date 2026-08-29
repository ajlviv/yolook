package com.yolo.detector.ui.screens

import android.graphics.Bitmap
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yolo.detector.databinding.FragmentLiveBinding
import com.yolo.detector.ui.MainViewModel
import kotlinx.coroutines.launch

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

        // FAB: capture current overlay and save snapshot
        binding.fabSnapshot.setOnClickListener {
            captureAndSaveSnapshot()
        }

        // Collect detection updates and forward to overlay
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
            }
        }
    }

    private fun captureAndSaveSnapshot() {
        // Capture the entire frame (PreviewView + overlay) as a single bitmap
        val previewBitmap = binding.previewView.bitmap ?: return
        val merged = Bitmap.createBitmap(previewBitmap.width, previewBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(merged)
        canvas.drawBitmap(previewBitmap, 0f, 0f, null)
        binding.overlay.draw(canvas)
        previewBitmap.recycle()
        viewModel.saveSnapshot(merged)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
