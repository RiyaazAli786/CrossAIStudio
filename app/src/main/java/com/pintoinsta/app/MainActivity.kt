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
import android.view.View
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.util.PostingConfigManager
import com.pintoinsta.app.databinding.ActivityMainBinding
import com.pintoinsta.app.service.CrossAIPostingService
import com.pintoinsta.app.update.AppUpdateManager
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

    private var isAuthenticatedThisSession = false

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
        if (PostingConfigManager.isBackgroundRunningEnabled(this)) {
            CrossAIPostingService.startService(this)
        }
        AppUpdateManager.checkOnLaunch(this)

        setupBottomNav()

        if (savedInstanceState == null) {
            switchFragment(pinterestFragment)
        }

        binding.btnUnlockApp.setOnClickListener {
            showBiometricPrompt()
        }

        handleIncomingShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkBiometricSecurity()
    }

    override fun onStop() {
        super.onStop()
        // Lock app when minimized if biometric lock is active
        if (PostingConfigManager.isBiometricLockEnabled(this)) {
            isAuthenticatedThisSession = false
        }
    }

    private fun checkBiometricSecurity() {
        if (!PostingConfigManager.isBiometricLockEnabled(this)) {
            binding.layoutLockOverlay.visibility = View.GONE
            return
        }

        if (!isAuthenticatedThisSession) {
            binding.layoutLockOverlay.visibility = View.VISIBLE
            showBiometricPrompt()
        } else {
            binding.layoutLockOverlay.visibility = View.GONE
        }
    }

    private fun showBiometricPrompt() {
        try {
            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAuthenticatedThisSession = true
                    binding.layoutLockOverlay.visibility = View.GONE
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        // User dismissed lock; remain on lock overlay
                        binding.layoutLockOverlay.visibility = View.VISIBLE
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("CrossAIStudio Locked")
                .setSubtitle("Authenticate with Biometrics or Device PIN")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            // Fallback if device has no biometric or credential set
            binding.layoutLockOverlay.visibility = View.GONE
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

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback ignored
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
        if (incomingIntent == null) return
        val action = incomingIntent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return

        var sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText.isNullOrBlank()) {
            sharedText = incomingIntent.getStringExtra(Intent.EXTRA_SUBJECT)
        }
        if (sharedText.isNullOrBlank() && incomingIntent.data != null) {
            sharedText = incomingIntent.dataString
        }
        if (sharedText.isNullOrBlank() && incomingIntent.clipData != null && incomingIntent.clipData!!.itemCount > 0) {
            val item = incomingIntent.clipData!!.getItemAt(0)
            sharedText = item.text?.toString() ?: item.uri?.toString()
        }

        val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (sharedText.isNullOrBlank() && streamUri == null) {
            return
        }

        val repository = AppRepository.getInstance(this)
        val progress = AlertDialog.Builder(this)
            .setTitle("Importing Shared Content")
            .setMessage("Extracting media and metadata from Pinterest...")
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            try {
                val (success, pin) = if (!sharedText.isNullOrBlank()) {
                    repository.importPinFromSharedText(sharedText)
                } else if (streamUri != null) {
                    repository.importPinFromDirectImageUri(streamUri)
                } else {
                    Pair(false, null)
                }
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
                    val rawDisplay = sharedText ?: streamUri.toString()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Pin Extraction Failed")
                        .setMessage("Could not extract Pinterest pin from shared text:\n\n\"$rawDisplay\"\n\nPlease ensure the link is a valid Pinterest pin or try copying the pin URL directly.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@MainActivity, "Import error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
