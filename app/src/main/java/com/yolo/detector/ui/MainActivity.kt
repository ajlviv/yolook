package com.yolo.detector.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.yolo.detector.R
import com.yolo.detector.data.ViewMode
import com.yolo.detector.databinding.ActivityMainBinding

/**
 * Single-activity host. Manages camera permission and hosts the Navigation component.
 *
 * Navigation graph: Live (default) | History | Settings (bottom nav bar).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        binding.permissionRationale.visibility = if (granted) View.GONE else View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyDebugIntentExtras()
        setupNavigation()
        checkCameraPermission()
    }

    /**
     * Debug/QA hook: allows driving the driver-mode UI state deterministically from
     * adb without touch injection (some OEM shells block `input tap`):
     * `adb shell am start ... --es yolo_debug_view DRIVER --ez yolo_debug_hide true`.
     * No-ops unless the extras are present.
     */
    private fun applyDebugIntentExtras() {
        val viewName = intent.getStringExtra("yolo_debug_view") ?: return
        val mode = runCatching { ViewMode.valueOf(viewName) }.getOrNull() ?: return
        val viewModel: MainViewModel by viewModels()
        viewModel.setViewMode(mode)
        viewModel.setDriverModeHideCamera(intent.getBooleanExtra("yolo_debug_hide", false))
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
}
