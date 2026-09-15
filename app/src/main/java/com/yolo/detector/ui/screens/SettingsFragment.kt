package com.yolo.detector.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.yolo.detector.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yolo.detector.data.COCO_LABELS
import com.yolo.detector.data.DetectionView
import com.yolo.detector.data.VEHICLE_CLASS_IDS
import com.yolo.detector.data.ViewMode
import com.yolo.detector.databinding.FragmentSettingsBinding
import com.yolo.detector.ui.MainViewModel
import com.yolo.detector.util.snapToStep
import kotlinx.coroutines.flow.combine
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

    // Tracks the ordinal currently shown in the view-mode spinner so a programmatic
    // re-sync does not re-trigger setViewMode().
    private var currentViewModeOrdinal = ViewMode.NORMAL.ordinal

    // Same guard for the detection-view spinner (see setDetectionView()).
    private var currentDetectionViewOrdinal = DetectionView.LABELS.ordinal

    // Same guard pattern for the render-detail spinners.
    private var currentEdgeDetailOrdinal = 2   // default detail 3 → index 2
    private var currentHeatmapDetailOrdinal = 2

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
        setupViewModeSpinner()
        setupDetectionViewSpinner()
        setupRenderDetailSpinners()
        setupListeners()
        setupEmailAlertListeners()
        observeSettings()
        observeEmailAlerts()
    }

    private fun setupViewModeSpinner() {
        val modes = resources.getStringArray(R.array.view_modes).toList()
        binding.spViewMode.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            modes,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spViewMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == currentViewModeOrdinal) return
                currentViewModeOrdinal = position
                viewModel.setViewMode(ViewMode.entries[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupDetectionViewSpinner() {
        val views = resources.getStringArray(R.array.detection_views).toList()
        binding.spDetectionView.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            views,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spDetectionView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == currentDetectionViewOrdinal) return
                currentDetectionViewOrdinal = position
                viewModel.setDetectionView(DetectionView.entries[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupRenderDetailSpinners() {
        val levels = resources.getStringArray(R.array.render_detail_levels).toList()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            levels,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spEdgeDetail.adapter = adapter
        binding.spHeatmapDetail.adapter = adapter

        binding.spEdgeDetail.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == currentEdgeDetailOrdinal) return
                currentEdgeDetailOrdinal = position
                viewModel.setEdgeDetail(position + 1)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.spHeatmapDetail.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == currentHeatmapDetailOrdinal) return
                currentHeatmapDetailOrdinal = position
                viewModel.setHeatmapDetail(position + 1)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun displayAppVersion() {
        val appName = getString(R.string.app_name)
        val versionText = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            "$appName v${pInfo.versionName} (Build ${pInfo.versionCode})"
        } catch (e: Exception) {
            "$appName v1.0"
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

        binding.sliderEdgeThreshold.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = value.toInt()
                binding.sliderEdgeThreshold.value = snapped.toFloat()
                binding.tvEdgeThreshold.text = "$snapped"
                viewModel.setEdgeThreshold(snapped)
            }
        }

        binding.sliderMatrixDetail.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = value.toInt().coerceIn(1, 10)
                binding.sliderMatrixDetail.value = snapped.toFloat()
                binding.tvMatrixDetail.text = "$snapped/10"
                viewModel.setMatrixDetail(snapped)
            }
        }

        binding.sliderMatrixGamma.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = snapToStep(value, 0.5f, 1f, 0.01f)
                binding.sliderMatrixGamma.value = snapped
                binding.tvMatrixGamma.text = "${(snapped * 100).toInt()}%"
                viewModel.setMatrixGamma(snapped)
            }
        }
    }

    private fun setupEmailAlertListeners() {
        binding.switchEmail.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setEmailEnabled(isChecked)
        }

        // Text fields commit on focus-loss so each keystroke isn't persisted.
        binding.etRecipient.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(view: View, hasFocus: Boolean) {
                if (!hasFocus) viewModel.setEmailRecipient(binding.etRecipient.text?.toString() ?: "")
            }
        }
        binding.etSenderEmail.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(view: View, hasFocus: Boolean) {
                if (!hasFocus) viewModel.setEmailSender(binding.etSenderEmail.text?.toString() ?: "")
            }
        }
        binding.etApiKey.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(view: View, hasFocus: Boolean) {
                if (!hasFocus) {
                    val value = binding.etApiKey.text?.toString() ?: ""
                    if (value.isNotBlank()) viewModel.setEmailApiKey(value)
                    // Never leave the plaintext in the field after committing.
                    binding.etApiKey.setText("")
                }
            }
        }

        binding.sliderCooldown.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val secs = value.toInt().coerceIn(30, 120)
                binding.sliderCooldown.value = secs.toFloat()
                binding.tvCooldownValue.text = getString(R.string.email_cooldown_value, secs)
                viewModel.setEmailCooldownSeconds(secs)
            }
        }

        binding.btnTestEmail.setOnClickListener {
            viewModel.sendTestAlertEmail()
        }
    }

    private fun observeEmailAlerts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.emailSettings.collect { s ->
                        binding.switchEmail.isChecked = s.enabled
                        val secs = (s.cooldownMs / 1000).toInt().coerceIn(30, 120)
                        binding.sliderCooldown.value = secs.toFloat()
                        binding.tvCooldownValue.text = getString(R.string.email_cooldown_value, secs)
                        if (!binding.etRecipient.isFocused) binding.etRecipient.setText(s.recipient)
                        if (!binding.etSenderEmail.isFocused) binding.etSenderEmail.setText(s.senderEmail)
                    }
                }
                launch {
                    viewModel.emailApiKeyPresent.collect { present ->
                        binding.tvApiKeyStatus.text = getString(
                            if (present) R.string.email_api_key_saved else R.string.email_api_key_missing
                        )
                    }
                }
                launch {
                    combine(
                        viewModel.emailTestRunning,
                        viewModel.emailTestFailure,
                    ) { running, detail ->
                        running to detail
                    }.collect { (running, detail) ->
                        binding.btnTestEmail.isEnabled = !running
                        binding.tvTestEmailStatus.text =
                            if (running) getString(R.string.email_test_sending) else detail.orEmpty()
                    }
                }
            }
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

                    currentViewModeOrdinal = settings.viewMode.ordinal
                    binding.spViewMode.setSelection(settings.viewMode.ordinal)

                    currentDetectionViewOrdinal = settings.detectionView.ordinal
                    binding.spDetectionView.setSelection(settings.detectionView.ordinal)

                    // Show only the quality controls belonging to the active view mode.
                    binding.layoutEdgeQuality.visibility =
                        if (settings.viewMode == ViewMode.EDGE) View.VISIBLE else View.GONE
                    binding.layoutHeatmapQuality.visibility =
                        if (settings.viewMode == ViewMode.HEATMAP) View.VISIBLE else View.GONE
                    binding.layoutMatrixQuality.visibility =
                        if (settings.viewMode == ViewMode.MATRIX) View.VISIBLE else View.GONE

                    binding.sliderEdgeThreshold.value = settings.edgeThreshold.toFloat()
                    binding.tvEdgeThreshold.text = "${settings.edgeThreshold}"
                    currentEdgeDetailOrdinal = settings.edgeDetail - 1
                    binding.spEdgeDetail.setSelection(settings.edgeDetail - 1)

                    currentHeatmapDetailOrdinal = settings.heatmapDetail - 1
                    binding.spHeatmapDetail.setSelection(settings.heatmapDetail - 1)

                    binding.sliderMatrixDetail.value = settings.matrixDetail.toFloat()
                    binding.tvMatrixDetail.text = "${settings.matrixDetail}/10"

                    val snappedGamma = snapToStep(settings.matrixGamma, 0.5f, 1f, 0.01f)
                    binding.sliderMatrixGamma.value = snappedGamma
                    binding.tvMatrixGamma.text = "${(snappedGamma * 100).toInt()}%"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
