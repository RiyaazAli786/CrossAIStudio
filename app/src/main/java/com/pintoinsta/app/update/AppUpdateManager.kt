package com.pintoinsta.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pintoinsta.app.BuildConfig
import com.pintoinsta.app.R
import com.pintoinsta.app.util.PostingConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val forceUpdate: Boolean = false
)

sealed class UpdateCheckResult {
    data class Available(val updateInfo: UpdateInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/rahmatullah-21/CrossAIStudio/releases/latest"
    private const val RAW_VERSION_URL = "https://raw.githubusercontent.com/rahmatullah-21/CrossAIStudio/main/version.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Checks for updates asynchronously against GitHub Releases API with fallback to raw version.json.
     */
    suspend fun checkForUpdates(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            PostingConfigManager.setLastUpdateCheckTime(context, System.currentTimeMillis())

            // 1. Try raw version.json first (cleanest structure)
            var updateInfo = fetchFromRawVersionJson()

            // 2. Fallback to GitHub Releases API
            if (updateInfo == null) {
                updateInfo = fetchFromGitHubReleases()
            }

            if (updateInfo == null) {
                return@withContext UpdateCheckResult.Error("Could not reach update server. Please check your connection.")
            }

            val isNewer = isVersionNewer(
                remoteCode = updateInfo.versionCode,
                remoteName = updateInfo.versionName,
                currentCode = BuildConfig.VERSION_CODE,
                currentName = BuildConfig.VERSION_NAME
            )

            if (isNewer) {
                UpdateCheckResult.Available(updateInfo)
            } else {
                UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            UpdateCheckResult.Error(e.message ?: "Failed to check for updates")
        }
    }

    private fun fetchFromRawVersionJson(): UpdateInfo? {
        try {
            val request = Request.Builder()
                .url(RAW_VERSION_URL)
                .header("Cache-Control", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val vCode = json.optInt("versionCode", 0)
            val vName = json.optString("versionName", "").trim()
            val changelog = json.optString("changelog", "Bug fixes and improvements").trim()
            val downloadUrl = json.optString("downloadUrl", "").trim()
            val force = json.optBoolean("forceUpdate", false)

            if (vName.isNotEmpty() && downloadUrl.isNotEmpty()) {
                return UpdateInfo(
                    versionCode = vCode,
                    versionName = vName,
                    changelog = changelog,
                    downloadUrl = downloadUrl,
                    forceUpdate = force
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "raw version.json fetch attempt: ${e.message}")
        }
        return null
    }

    private fun fetchFromGitHubReleases(): UpdateInfo? {
        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "CrossAIStudio-Android")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").replace("v", "").trim()
            val changelog = json.optString("body", "Bug fixes and performance enhancements").trim()

            // Find APK in assets
            var apkUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            if (apkUrl.isEmpty()) {
                // Default release asset link
                apkUrl = "https://github.com/rahmatullah-21/CrossAIStudio/releases/download/v$tagName/CrossAIStudio-release.apk"
            }

            if (tagName.isNotEmpty()) {
                return UpdateInfo(
                    versionCode = 0,
                    versionName = tagName,
                    changelog = changelog,
                    downloadUrl = apkUrl,
                    forceUpdate = false
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "GitHub Releases API fetch attempt: ${e.message}")
        }
        return null
    }

    private fun isVersionNewer(
        remoteCode: Int,
        remoteName: String,
        currentCode: Int,
        currentName: String
    ): Boolean {
        if (remoteCode > 0 && currentCode > 0) {
            return remoteCode > currentCode
        }

        // Compare semantic version strings e.g. "1.1.0" vs "1.0.0"
        return try {
            val rParts = remoteName.replace(Regex("[^0-9.]"), "").split(".").map { it.toIntOrNull() ?: 0 }
            val cParts = currentName.replace(Regex("[^0-9.]"), "").split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(rParts.size, cParts.size)

            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            false
        } catch (_: Exception) {
            remoteName != currentName
        }
    }

    /**
     * Presents an update confirmation dialog to the user with the changelog.
     */
    fun showUpdateDialog(activity: Activity, updateInfo: UpdateInfo) {
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("🚀 Update Available: v${updateInfo.versionName}")
            .setMessage("A new update of CrossAIStudio is ready!\n\nChangelog:\n${updateInfo.changelog}")
            .setPositiveButton("Update Now") { _, _ ->
                startDownloadAndInstall(activity, updateInfo)
            }

        if (!updateInfo.forceUpdate) {
            builder.setNegativeButton("Later", null)
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    /**
     * Downloads the APK file with real-time progress and launches the system installer.
     */
    fun startDownloadAndInstall(activity: Activity, updateInfo: UpdateInfo) {
        // Verify installation permission on Android 8.0+ (Oreo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Permission Needed")
                    .setMessage("CrossAIStudio needs permission to install app updates directly. Please toggle on 'Allow from this source'.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }

        // Build determinate progress dialog
        val dialogView = LayoutInflater.from(activity).inflate(
            activity.resources.getIdentifier("dialog_update_progress", "layout", activity.packageName).let { id ->
                if (id != 0) id else android.R.layout.simple_list_item_1
            },
            null,
            false
        )

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            setPadding(60, 30, 60, 10)
        }

        val statusText = TextView(activity).apply {
            text = "Downloading update: 0%"
            setPadding(60, 0, 60, 30)
            textSize = 13f
        }

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(progressBar)
            addView(statusText)
        }

        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Downloading CrossAIStudio v${updateInfo.versionName}")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("Cancel", null)
            .create()

        var isCancelled = false
        progressDialog.setOnCancelListener { isCancelled = true }
        progressDialog.show()
        progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            isCancelled = true
            progressDialog.dismiss()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(updateInfo.downloadUrl)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(activity, "Download failed: HTTP ${response.code}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val totalBytes = body.contentLength()

                val destinationFile = File(
                    activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "CrossAIStudio-update-${updateInfo.versionName}.apk"
                )
                if (destinationFile.exists()) destinationFile.delete()

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(destinationFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled) {
                        inputStream.close()
                        outputStream.close()
                        destinationFile.delete()
                        return@launch
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    if (totalBytes > 0) {
                        val percent = ((totalRead * 100) / totalBytes).toInt()
                        withContext(Dispatchers.Main) {
                            progressBar.progress = percent
                            val currentMb = String.format("%.1f", totalRead / (1024.0 * 1024.0))
                            val totalMb = String.format("%.1f", totalBytes / (1024.0 * 1024.0))
                            statusText.text = "$currentMb MB / $totalMb MB ($percent%)"
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    launchPackageInstaller(activity, destinationFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchPackageInstaller(activity: Activity, apkFile: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(activity, "Could not open installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Checks for updates silently on app launch if enabled and throttled (e.g. at most once every 4 hours).
     */
    fun checkOnLaunch(activity: Activity) {
        if (!PostingConfigManager.isAutoCheckUpdatesEnabled(activity)) return

        val lastCheck = PostingConfigManager.getLastUpdateCheckTime(activity)
        val fourHours = 4 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - lastCheck < fourHours) return

        CoroutineScope(Dispatchers.Main).launch {
            when (val result = checkForUpdates(activity)) {
                is UpdateCheckResult.Available -> {
                    showUpdateDialog(activity, result.updateInfo)
                }
                else -> {
                    // Silently ignore UpToDate or Error on automatic launch check
                }
            }
        }
    }
}

