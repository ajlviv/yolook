package com.yolo.detector.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.yolo.detector.R
import com.yolo.detector.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Single-activity host. Manages camera permission and hosts the Navigation component.
 *
 * Navigation graph: Live (default) | History | Settings (bottom nav bar).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        binding.permissionRationale.visibility = if (granted) View.GONE else View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Monitoring mode (Settings → View or the Live quick toggle): keep the screen
        // awake and dim brightness to a minimum so detection can run unattended.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settingsFlow.collect { settings ->
                    applyMonitoringMode(settings.monitoringMode)
                }
            }
        }

        setupNavigation()
        checkCameraPermission()
    }

    /**
     * Applies (or clears) the monitoring-mode window state: FLAG_KEEP_SCREEN_ON plus a
     * near-zero brightness override. The override auto-resets when the activity window
     * is destroyed; clearing it explicitly returns brightness to system control so the
     * user's setting isn't left pinned after the toggle is switched off.
     */
    private fun applyMonitoringMode(enabled: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            MONITORING_BRIGHTNESS
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            binding.permissionRationale.visibility = View.GONE
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    companion object {
        /** Near-zero brightness override used by monitoring mode (0..1, 1 = full). */
        private const val MONITORING_BRIGHTNESS = 0.02f
    }
}
