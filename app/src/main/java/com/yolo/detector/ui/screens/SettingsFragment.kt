package com.yolo.detector.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yolo.detector.data.COCO_LABELS
import com.yolo.detector.data.VEHICLE_CLASS_IDS
import com.yolo.detector.databinding.FragmentSettingsBinding
import com.yolo.detector.ui.MainViewModel
import com.yolo.detector.util.snapToStep
import kotlinx.coroutines.launch

/**
 * Settings fragment: allows runtime tuning of detection thresholds, FPS cap, GPU acceleration, and class filters.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val classCheckBoxes = mutableMapOf<Int, CheckBox>()

    // Guards against the settings-flow re-sync re-triggering the checkbox
    // change listener (which would call setClassFilter → re-emit → flicker loop).
    private var syncingFromSettings = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        displayAppVersion()
        setupClassFilterCheckboxes()
        setupListeners()
        observeSettings()
    }

    private fun displayAppVersion() {
        val versionText = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            "YOLO Detector v${pInfo.versionName} (Build ${pInfo.versionCode})"
        } catch (e: Exception) {
            "YOLO Detector v1.0"
        }
        binding.tvAppVersion.text = versionText
    }

    private fun setupClassFilterCheckboxes() {
        val container = binding.layoutClassFilters
        container.removeAllViews()

        COCO_LABELS.forEachIndexed { index, label ->
            val checkBox = CheckBox(requireContext()).apply {
                text = "$label (id: $index)"
                setOnCheckedChangeListener { _, _ ->
                    if (syncingFromSettings) return@setOnCheckedChangeListener
                    val selectedIds = classCheckBoxes.filter { it.value.isChecked }.keys.toSet()
                    viewModel.setClassFilter(selectedIds)
                }
            }
            classCheckBoxes[index] = checkBox
            container.addView(checkBox)
        }
    }

    private fun setupListeners() {
        binding.sliderConfidence.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = snapToStep(value, 0.1f, 0.9f, 0.05f)
                // Re-assign the snapped value back onto the slider so the transient
                // off-grid drag value never reaches onDraw (BaseSlider.validateValues).
                binding.sliderConfidence.value = snapped
                binding.tvConfValue.text = "${(snapped * 100).toInt()}%"
                viewModel.setConfidenceThreshold(snapped)
            }
        }

        binding.sliderIou.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = snapToStep(value, 0.1f, 0.9f, 0.05f)
                binding.sliderIou.value = snapped
                binding.tvIouValue.text = "${(snapped * 100).toInt()}%"
                viewModel.setIouThreshold(snapped)
            }
        }

        binding.sliderMaxObjects.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val intVal = value.toInt()
                binding.tvMaxObjectsValue.text = "$intVal"
                viewModel.setMaxObjects(intVal)
            }
        }

        binding.sliderFps.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val intVal = value.toInt()
                binding.tvFpsValue.text = "$intVal FPS"
                viewModel.setInferenceRateFps(intVal)
            }
        }

        binding.switchGpu.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGpuEnabled(isChecked)
        }

        binding.btnSelectVehiclesOnly.setOnClickListener {
            viewModel.setClassFilter(VEHICLE_CLASS_IDS)
        }

        binding.btnSelectAllClasses.setOnClickListener {
            viewModel.setClassFilter(COCO_LABELS.indices.toSet())
        }

        binding.btnResetDefaults.setOnClickListener {
            viewModel.resetSettings()
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settingsFlow.collect { settings ->
                    val snappedConfidence = snapToStep(settings.confidenceThreshold, 0.1f, 0.9f, 0.05f)
                    binding.sliderConfidence.value = snappedConfidence
                    binding.tvConfValue.text = "${(snappedConfidence * 100).toInt()}%"

                    val snappedIou = snapToStep(settings.iouThreshold, 0.1f, 0.9f, 0.05f)
                    binding.sliderIou.value = snappedIou
                    binding.tvIouValue.text = "${(snappedIou * 100).toInt()}%"

                    binding.sliderMaxObjects.value = settings.maxObjects.toFloat()
                    binding.tvMaxObjectsValue.text = "${settings.maxObjects}"

                    binding.sliderFps.value = settings.inferenceRateFps.toFloat()
                    binding.tvFpsValue.text = "${settings.inferenceRateFps} FPS"

                    binding.switchGpu.isChecked = settings.enableGpuDelegate

                    syncingFromSettings = true
                    classCheckBoxes.forEach { (id, checkBox) ->
                        checkBox.isChecked = id in settings.classFilter
                    }
                    syncingFromSettings = false
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
