package com.yolo.detector.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayoutMediator
import com.yolo.detector.R
import com.yolo.detector.data.DetectionView
import com.yolo.detector.data.VEHICLE_CLASS_IDS
import com.yolo.detector.data.ViewMode
import com.yolo.detector.databinding.FragmentSettingsTabsBinding
import com.yolo.detector.inference.ModelProfile
import com.yolo.detector.inference.ModelTask
import com.yolo.detector.ui.MainViewModel
import com.yolo.detector.util.snapToStep
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Settings fragment: runtime tuning of detection thresholds, FPS cap, GPU acceleration, and class filters.
 * Settings are grouped into tabs (detection / view / classes / alerts) via a ViewPager2.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsTabsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val classCheckBoxes = mutableMapOf<Int, CheckBox>()

    // Pre-inflated tab pages; the ViewPager2 adapter serves the same instances, so the
    // fragment can wire listeners/observers right after setAdapter().
    private lateinit var detectionPage: View
    private lateinit var viewPage: View
    private lateinit var classesPage: View
    private lateinit var alertsPage: View

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
        _binding = FragmentSettingsTabsBinding.inflate(inflater, container, false)
        detectionPage = inflater.inflate(R.layout.tab_detection, container, false)
        viewPage = inflater.inflate(R.layout.tab_view, container, false)
        classesPage = inflater.inflate(R.layout.tab_classes, container, false)
        alertsPage = inflater.inflate(R.layout.tab_alerts, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        displayAppVersion()
        setupTabs()
        setupModelSpinner()
        setupClassFilterCheckboxes(viewModel.activeLabels)
        setupViewModeSpinner()
        setupDetectionViewSpinner()
        setupRenderDetailSpinners()
        setupListeners()
        setupEmailAlertListeners()
        observeSettings()
        observeEmailAlerts()
    }

    private fun setupTabs() {
        val titles = resources.getStringArray(R.array.settings_tab_titles)
        val pages = listOf(detectionPage, viewPage, classesPage, alertsPage)

        binding.viewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = pages.size

            override fun getItemViewType(position: Int): Int = position

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(pages[viewType]) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }
        // Keep every page alive so per-tab view state (type-in text, scroll) survives switching.
        binding.viewPager.offscreenPageLimit = pages.size

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()
    }

    private fun setupViewModeSpinner() {
        val spViewMode = viewPage.findViewById<Spinner>(R.id.spViewMode)
        val modes = resources.getStringArray(R.array.view_modes).toList()
        spViewMode.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            modes,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spViewMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == currentViewModeOrdinal) return
                currentViewModeOrdinal = position
                viewModel.setViewMode(ViewMode.entries[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupDetectionViewSpinner() {
        val spDetectionView = viewPage.findViewById<Spinner>(R.id.spDetectionView)
        val views = resources.getStringArray(R.array.detection_views).toList()
        spDetectionView.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            views,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spDetectionView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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

        val spEdgeDetail = viewPage.findViewById<Spinner>(R.id.spEdgeDetail)
        val spHeatmapDetail = viewPage.findViewById<Spinner>(R.id.spHeatmapDetail)
        spEdgeDetail.adapter = adapter
        spHeatmapDetail.adapter = adapter

        spEdgeDetail.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == currentEdgeDetailOrdinal) return
                currentEdgeDetailOrdinal = position
                viewModel.setEdgeDetail(position + 1)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spHeatmapDetail.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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

    private fun setupClassFilterCheckboxes(labels: List<String>) {
        val container = classesPage.findViewById<LinearLayout>(R.id.layoutClassFilters)
        container.removeAllViews()
        classCheckBoxes.clear()

        labels.forEachIndexed { index, label ->
            val checkBox = CheckBox(requireContext()).apply {
                text = "$label (id: $index)"
                // Self-contained button drawable: gray outline unchecked, solid
                // green box with a white check when checked. Bypasses the theme's
                // Material checkbox rendering, which tints box and check the same
                // color so the check becomes invisible (seen as a bare green border).
                buttonDrawable = ContextCompat.getDrawable(context, R.drawable.checkbox_class_filter)
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

    /**
     * Populates the model picker from the profiles actually packaged in this build.
     *
     * The list is intentionally explicit: registering a second model must never
     * silently take over the running one, so switching is always a user action.
     */
    private fun setupModelSpinner() {
        val spModel = detectionPage.findViewById<Spinner>(R.id.spModel)
        val tvModelDetail = detectionPage.findViewById<TextView>(R.id.tvModelDetail)

        val profiles = viewModel.availableProfiles.value
        val entries = profiles.map { it.id to describe(it) }
        val ids = profiles.map { it.id }

        modelSpinnerLabels.clear()
        modelSpinnerLabels.addAll(entries.map { it.first })

        spModel.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            entries.map { it.first },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        fun renderDetail(profileId: String?) {
            val profile = profiles.firstOrNull { it.id == profileId } ?: return
            tvModelDetail.text = describe(profile)
        }

        // A freshly adapted Spinner rests at position 0 and fires onItemSelected as
        // soon as it is laid out. Without preselecting the active model first, that
        // callback reports "yolo11n-coco" and the handler below writes it back to
        // storage, silently reverting the selection every time this screen opens.
        val activeIndex = ids.indexOf(viewModel.activeProfile.value.id).coerceAtLeast(0)
        syncingModelSpinner = true
        spModel.setSelection(activeIndex, false)
        syncingModelSpinner = false
        renderDetail(viewModel.activeProfile.value.id)

        spModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = ids.getOrNull(position) ?: return
                renderDetail(selected)
                if (syncingModelSpinner) return
                if (selected == viewModel.currentSettingsSnapshot.modelProfileId) return
                viewModel.setModelProfile(selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * True while the model spinner is being positioned from state rather than by the
     * user, so the resulting [AdapterView.OnItemSelectedListener] callback does not
     * write the value it was just handed back to storage.
     */
    private var syncingModelSpinner = false

    /** Points the model spinner at [profileId] without writing to storage. */
    private fun syncModelSpinner(profileId: String) {
        val spinner = view?.findViewById<android.widget.Spinner>(R.id.spModel) ?: return
        val labels = modelSpinnerLabels
        val index = labels.indexOf(profileId)
        if (index < 0 || spinner.selectedItemPosition == index) return
        syncingModelSpinner = true
        spinner.setSelection(index, false)
        syncingModelSpinner = false
    }

    /** Spinner entry labels, in adapter order; paired with [syncModelSpinner]. */
    private val modelSpinnerLabels = mutableListOf<String>()

    /** Every class ID the active model declares, as the "select all" target. */
    private fun allClassIds(): Set<Int> = (0 until viewModel.activeLabels.size).toSet()

    private fun describe(profile: ModelProfile): String {
        val task = if (profile.task == ModelTask.SEGMENTATION) "segmentation" else "detection"
        val recommended = (profile.recommendedConfidenceThreshold * 100).toInt()
        return "$task · ${profile.numClasses} classes · ${profile.inputSize}px · conf ≥$recommended%"
    }

    private fun setupListeners() {
        val sliderConfidence = detectionPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderConfidence)
        val tvConfValue = detectionPage.findViewById<android.widget.TextView>(R.id.tvConfValue)

        sliderConfidence.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = snapToStep(value, 0.1f, 0.9f, 0.05f)
                // Re-assign the snapped value back onto the slider so the transient
                // off-grid drag value never reaches onDraw (BaseSlider.validateValues).
                sliderConfidence.value = snapped
                tvConfValue.text = "${(snapped * 100).toInt()}%"
                viewModel.setConfidenceThreshold(snapped)
            }
        }

        val sliderIou = detectionPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderIou)
        val tvIouValue = detectionPage.findViewById<android.widget.TextView>(R.id.tvIouValue)
        sliderIou.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = snapToStep(value, 0.1f, 0.9f, 0.05f)
                sliderIou.value = snapped
                tvIouValue.text = "${(snapped * 100).toInt()}%"
                viewModel.setIouThreshold(snapped)
            }
        }

        val sliderMaxObjects = detectionPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMaxObjects)
        val tvMaxObjectsValue = detectionPage.findViewById<android.widget.TextView>(R.id.tvMaxObjectsValue)
        sliderMaxObjects.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val intVal = value.toInt()
                tvMaxObjectsValue.text = "$intVal"
                viewModel.setMaxObjects(intVal)
            }
        }

        val sliderFps = detectionPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderFps)
        val tvFpsValue = detectionPage.findViewById<android.widget.TextView>(R.id.tvFpsValue)
        sliderFps.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val intVal = value.toInt()
                tvFpsValue.text = "$intVal FPS"
                viewModel.setInferenceRateFps(intVal)
            }
        }

        detectionPage.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchGpu)
            .setOnCheckedChangeListener { _, isChecked ->
                viewModel.setGpuEnabled(isChecked)
            }

        detectionPage.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSliced)
            .setOnCheckedChangeListener { _, isChecked ->
                viewModel.setSlicedInference(isChecked)
            }

        classesPage.findViewById<android.widget.Button>(R.id.btnSelectVehiclesOnly).setOnClickListener {
            viewModel.setClassFilter(VEHICLE_CLASS_IDS)
        }

        classesPage.findViewById<android.widget.Button>(R.id.btnSelectAllClasses).setOnClickListener {
            viewModel.setClassFilter(allClassIds())
        }

        val cbAllClasses = classesPage.findViewById<CheckBox>(R.id.cbAllClasses)
        cbAllClasses.setOnCheckedChangeListener { _, checked ->
            if (syncingFromSettings) return@setOnCheckedChangeListener
            viewModel.setClassFilter(if (checked) allClassIds() else emptySet())
        }

        alertsPage.findViewById<android.widget.Button>(R.id.btnResetDefaults).setOnClickListener {
            viewModel.resetSettings()
        }

        val sliderEdgeThreshold = viewPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderEdgeThreshold)
        val tvEdgeThreshold = viewPage.findViewById<android.widget.TextView>(R.id.tvEdgeThreshold)
        sliderEdgeThreshold.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = value.toInt()
                sliderEdgeThreshold.value = snapped.toFloat()
                tvEdgeThreshold.text = "$snapped"
                viewModel.setEdgeThreshold(snapped)
            }
        }

        val sliderMatrixDetail = viewPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMatrixDetail)
        val tvMatrixDetail = viewPage.findViewById<android.widget.TextView>(R.id.tvMatrixDetail)
        sliderMatrixDetail.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = value.toInt().coerceIn(1, 10)
                sliderMatrixDetail.value = snapped.toFloat()
                tvMatrixDetail.text = "$snapped/10"
                viewModel.setMatrixDetail(snapped)
            }
        }

        val sliderMatrixGamma = viewPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMatrixGamma)
        val tvMatrixGamma = viewPage.findViewById<android.widget.TextView>(R.id.tvMatrixGamma)
        sliderMatrixGamma.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val snapped = snapToStep(value, 0.5f, 1f, 0.01f)
                sliderMatrixGamma.value = snapped
                tvMatrixGamma.text = "${(snapped * 100).toInt()}%"
                viewModel.setMatrixGamma(snapped)
            }
        }

        viewPage.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitoring)
            .setOnCheckedChangeListener { _, isChecked ->
                viewModel.setMonitoringMode(isChecked)
            }
    }

    private fun setupEmailAlertListeners() {
        val switchEmail = alertsPage.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchEmail)
        switchEmail.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setEmailEnabled(isChecked)
        }

        // Text fields commit on focus-loss so each keystroke isn't persisted.
        val etRecipient = alertsPage.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRecipient)
        etRecipient.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(view: View, hasFocus: Boolean) {
                if (!hasFocus) viewModel.setEmailRecipient(etRecipient.text?.toString() ?: "")
            }
        }
        val etSenderEmail = alertsPage.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSenderEmail)
        etSenderEmail.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(view: View, hasFocus: Boolean) {
                if (!hasFocus) viewModel.setEmailSender(etSenderEmail.text?.toString() ?: "")
            }
        }
        val etApiKey = alertsPage.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etApiKey)
        etApiKey.onFocusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(view: View, hasFocus: Boolean) {
                if (!hasFocus) {
                    val value = etApiKey.text?.toString() ?: ""
                    if (value.isNotBlank()) viewModel.setEmailApiKey(value)
                    // Never leave the plaintext in the field after committing.
                    etApiKey.setText("")
                }
            }
        }

        val sliderCooldown = alertsPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderCooldown)
        val tvCooldownValue = alertsPage.findViewById<android.widget.TextView>(R.id.tvCooldownValue)
        sliderCooldown.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val secs = value.toInt().coerceIn(30, 120)
                sliderCooldown.value = secs.toFloat()
                tvCooldownValue.text = getString(R.string.email_cooldown_value, secs)
                viewModel.setEmailCooldownSeconds(secs)
            }
        }

        alertsPage.findViewById<android.widget.Button>(R.id.btnTestEmail).setOnClickListener {
            viewModel.sendTestAlertEmail()
        }
    }

    private fun observeEmailAlerts() {
        val switchEmail = alertsPage.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchEmail)
        val sliderCooldown = alertsPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderCooldown)
        val tvCooldownValue = alertsPage.findViewById<android.widget.TextView>(R.id.tvCooldownValue)
        val etRecipient = alertsPage.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRecipient)
        val etSenderEmail = alertsPage.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSenderEmail)
        val tvApiKeyStatus = alertsPage.findViewById<android.widget.TextView>(R.id.tvApiKeyStatus)
        val btnTestEmail = alertsPage.findViewById<android.widget.Button>(R.id.btnTestEmail)
        val tvTestEmailStatus = alertsPage.findViewById<android.widget.TextView>(R.id.tvTestEmailStatus)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.emailSettings.collect { s ->
                        switchEmail.isChecked = s.enabled
                        val secs = (s.cooldownMs / 1000).toInt().coerceIn(30, 120)
                        sliderCooldown.value = secs.toFloat()
                        tvCooldownValue.text = getString(R.string.email_cooldown_value, secs)
                        if (!etRecipient.isFocused) etRecipient.setText(s.recipient)
                        if (!etSenderEmail.isFocused) etSenderEmail.setText(s.senderEmail)
                    }
                }
                launch {
                    viewModel.emailApiKeyPresent.collect { present ->
                        tvApiKeyStatus.text = getString(
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
                        btnTestEmail.isEnabled = !running
                        tvTestEmailStatus.text =
                            if (running) getString(R.string.email_test_sending) else detail.orEmpty()
                    }
                }
            }
        }
    }

    private fun observeSettings() {
        val sliderConfidence = detectionPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderConfidence)
        val tvConfValue = detectionPage.findViewById<android.widget.TextView>(R.id.tvConfValue)
        val sliderIou = detectionPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderIou)
        val tvIouValue = detectionPage.findViewById<android.widget.TextView>(R.id.tvIouValue)
        val sliderMaxObjects = detectionPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMaxObjects)
        val tvMaxObjectsValue = detectionPage.findViewById<android.widget.TextView>(R.id.tvMaxObjectsValue)
        val sliderFps = detectionPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderFps)
        val tvFpsValue = detectionPage.findViewById<android.widget.TextView>(R.id.tvFpsValue)
        val switchGpu = detectionPage.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchGpu)
        val switchSliced = detectionPage.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSliced)

        val cbAllClasses = classesPage.findViewById<CheckBox>(R.id.cbAllClasses)

        val spViewMode = viewPage.findViewById<Spinner>(R.id.spViewMode)
        val spDetectionView = viewPage.findViewById<Spinner>(R.id.spDetectionView)
        val layoutEdgeQuality = viewPage.findViewById<LinearLayout>(R.id.layoutEdgeQuality)
        val layoutHeatmapQuality = viewPage.findViewById<LinearLayout>(R.id.layoutHeatmapQuality)
        val layoutMatrixQuality = viewPage.findViewById<LinearLayout>(R.id.layoutMatrixQuality)
        val sliderEdgeThreshold = viewPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderEdgeThreshold)
        val tvEdgeThreshold = viewPage.findViewById<android.widget.TextView>(R.id.tvEdgeThreshold)
        val spEdgeDetail = viewPage.findViewById<Spinner>(R.id.spEdgeDetail)
        val spHeatmapDetail = viewPage.findViewById<Spinner>(R.id.spHeatmapDetail)
        val sliderMatrixDetail = viewPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMatrixDetail)
        val tvMatrixDetail = viewPage.findViewById<android.widget.TextView>(R.id.tvMatrixDetail)
        val sliderMatrixGamma = viewPage.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMatrixGamma)
        val tvMatrixGamma = viewPage.findViewById<android.widget.TextView>(R.id.tvMatrixGamma)
        val switchMonitoring = viewPage.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitoring)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The class tab is built from the active model's vocabulary, so a model
                // switch has to rebuild the checkboxes before syncing the filter.
                launch {
                    viewModel.activeProfile.collect { profile ->
                        syncModelSpinner(profile.id)
                        setupClassFilterCheckboxes(profile.labels)
                        // COCO-only shortcut: a model with no vehicle classes has nothing
                        // for "Vehicles only" to select.
                        classesPage.findViewById<View>(R.id.btnSelectVehiclesOnly).visibility =
                            if (VEHICLE_CLASS_IDS.any { it < profile.labels.size }) {
                                View.VISIBLE
                            } else {
                                View.GONE
                            }
                    }
                }
                launch {
                    viewModel.settingsFlow.collect { settings ->
                    val snappedConfidence = snapToStep(settings.confidenceThreshold, 0.1f, 0.9f, 0.05f)
                    sliderConfidence.value = snappedConfidence
                    tvConfValue.text = "${(snappedConfidence * 100).toInt()}%"

                    val snappedIou = snapToStep(settings.iouThreshold, 0.1f, 0.9f, 0.05f)
                    sliderIou.value = snappedIou
                    tvIouValue.text = "${(snappedIou * 100).toInt()}%"

                    sliderMaxObjects.value = settings.maxObjects.toFloat()
                    tvMaxObjectsValue.text = "${settings.maxObjects}"

                    sliderFps.value = settings.inferenceRateFps.toFloat()
                    tvFpsValue.text = "${settings.inferenceRateFps} FPS"

                    switchGpu.isChecked = settings.enableGpuDelegate
                    switchSliced.isChecked = settings.slicedInference

                    val numClasses = classCheckBoxes.size
                    val activeFilter = settings.classFilterFor(numClasses)
                    syncingFromSettings = true
                    classCheckBoxes.forEach { (id, checkBox) ->
                        checkBox.isChecked = id in activeFilter
                    }
                    cbAllClasses.isChecked = activeFilter.size == numClasses && numClasses > 0
                    syncingFromSettings = false

                    currentViewModeOrdinal = settings.viewMode.ordinal
                    spViewMode.setSelection(settings.viewMode.ordinal)

                    currentDetectionViewOrdinal = settings.detectionView.ordinal
                    spDetectionView.setSelection(settings.detectionView.ordinal)

                    // Show only the quality controls belonging to the active view mode.
                    layoutEdgeQuality.visibility =
                        if (settings.viewMode == ViewMode.EDGE) View.VISIBLE else View.GONE
                    layoutHeatmapQuality.visibility =
                        if (settings.viewMode == ViewMode.HEATMAP) View.VISIBLE else View.GONE
                    layoutMatrixQuality.visibility =
                        if (settings.viewMode == ViewMode.MATRIX) View.VISIBLE else View.GONE

                    sliderEdgeThreshold.value = settings.edgeThreshold.toFloat()
                    tvEdgeThreshold.text = "${settings.edgeThreshold}"
                    currentEdgeDetailOrdinal = settings.edgeDetail - 1
                    spEdgeDetail.setSelection(settings.edgeDetail - 1)

                    currentHeatmapDetailOrdinal = settings.heatmapDetail - 1
                    spHeatmapDetail.setSelection(settings.heatmapDetail - 1)

                    sliderMatrixDetail.value = settings.matrixDetail.toFloat()
                    tvMatrixDetail.text = "${settings.matrixDetail}/10"

                    val snappedGamma = snapToStep(settings.matrixGamma, 0.5f, 1f, 0.01f)
                    sliderMatrixGamma.value = snappedGamma
                    tvMatrixGamma.text = "${(snappedGamma * 100).toInt()}%"

                    switchMonitoring.isChecked = settings.monitoringMode
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