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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.Toast
import com.pintoinsta.app.data.repository.AppRepository
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

        handleIncomingShareIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(incomingIntent: Intent?) {
        if (incomingIntent?.action != Intent.ACTION_SEND) return

        val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrBlank()) {
            val repository = AppRepository.getInstance(this)
            val progress = AlertDialog.Builder(this)
                .setTitle("Importing Pinterest Pin")
                .setMessage("Extracting media and metadata from shared link...")
                .setCancelable(false)
                .create()
            progress.show()

            lifecycleScope.launch {
                val (success, pin) = repository.importPinFromSharedText(sharedText)
                progress.dismiss()

                if (success && pin != null) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("📌 Pin Imported Successfully!")
                        .setMessage("Title: ${pin.title.ifEmpty { "Shared Pin" }}\nMedia: ${pin.mediaType}\n\nWould you like to post this directly to Instagram now or keep it in your Pin Vault?")
                        .setPositiveButton("Post Now") { _, _ ->
                            lifecycleScope.launch {
                                Toast.makeText(this@MainActivity, "Publishing Pin to Instagram...", Toast.LENGTH_SHORT).show()
                                val (_, msg) = repository.postSinglePin(pin)
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("Keep in Vault") { _, _ ->
                            binding.bottomNav.selectedItemId = R.id.nav_pins
                        }
                        .show()
                } else {
                    Toast.makeText(this@MainActivity, "Could not extract Pinterest pin from shared text.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
