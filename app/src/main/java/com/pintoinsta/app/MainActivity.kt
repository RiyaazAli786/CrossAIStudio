package com.pintoinsta.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.pintoinsta.app.databinding.ActivityMainBinding
import com.pintoinsta.app.service.CrossAIPostingService
import com.pintoinsta.app.ui.analytics.AnalyticsFragment
import com.pintoinsta.app.ui.instagram.InstagramFragment
import com.pintoinsta.app.ui.pins.PinsLogsFragment
import com.pintoinsta.app.ui.pinterest.PinterestFragment
import com.pintoinsta.app.ui.settings.SettingsFragment
import com.pintoinsta.app.util.ThemeManager
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pinterestFragment = PinterestFragment()
    private val instagramFragment = InstagramFragment()
    private val pinsLogsFragment = PinsLogsFragment()
    private val analyticsFragment = AnalyticsFragment()
    private val settingsFragment = SettingsFragment()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Notification permission result handled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isLight = ThemeManager.getSavedTheme(this) == ThemeManager.THEME_LIGHT
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isLight

        // Prevent top status bar and camera punch-hole/notch from overlapping content
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(statusBarInsets.left, statusBarInsets.top, statusBarInsets.right, 0)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        askNotificationPermission()
        checkBatteryOptimizations()
        CrossAIPostingService.startService(this)

        setupBottomNav()

        if (savedInstanceState == null) {
            switchFragment(pinterestFragment)
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_pinterest -> {
                    switchFragment(pinterestFragment)
                    true
                }
                R.id.nav_instagram -> {
                    switchFragment(instagramFragment)
                    true
                }
                R.id.nav_pins -> {
                    switchFragment(pinsLogsFragment)
                    true
                }
                R.id.nav_analytics -> {
                    switchFragment(analyticsFragment)
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(settingsFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general settings
                }
            }
        }
    }
}
