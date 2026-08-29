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
import kotlinx.coroutines.launch

/**
 * Settings fragment: allows runtime tuning of detection thresholds, FPS cap, GPU acceleration, and class filters.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val classCheckBoxes = mutableMapOf<Int, CheckBox>()

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

        setupClassFilterCheckboxes()
        setupListeners()
        observeSettings()
    }

    private fun setupClassFilterCheckboxes() {
        val container = binding.layoutClassFilters
        container.removeAllViews()

        COCO_LABELS.forEachIndexed { index, label ->
            val checkBox = CheckBox(requireContext()).apply {
                text = "$label (id: $index)"
                setOnCheckedChangeListener { _, _ ->
                    val selectedIds = classCheckBoxes.filter { it.value.isChecked }.keys.toSet()
                    viewModel.setClassFilter(selectedIds)
                }
            }
            classCheckBoxes[index] = checkBox
            container.addView(checkBox)
        }
    }

    private fun setupListeners() {
        binding.sliderConfidence.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvConfValue.text = "${(value * 100).toInt()}%"
                viewModel.setConfidenceThreshold(value)
            }
        }

        binding.sliderIou.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvIouValue.text = "${(value * 100).toInt()}%"
                viewModel.setIouThreshold(value)
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
                    binding.sliderConfidence.value = settings.confidenceThreshold
                    binding.tvConfValue.text = "${(settings.confidenceThreshold * 100).toInt()}%"

                    binding.sliderIou.value = settings.iouThreshold
                    binding.tvIouValue.text = "${(settings.iouThreshold * 100).toInt()}%"

                    binding.sliderMaxObjects.value = settings.maxObjects.toFloat()
                    binding.tvMaxObjectsValue.text = "${settings.maxObjects}"

                    binding.sliderFps.value = settings.inferenceRateFps.toFloat()
                    binding.tvFpsValue.text = "${settings.inferenceRateFps} FPS"

                    binding.switchGpu.isChecked = settings.enableGpuDelegate

                    classCheckBoxes.forEach { (id, checkBox) ->
                        checkBox.isChecked = id in settings.classFilter
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
