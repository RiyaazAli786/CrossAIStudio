package com.pintoinsta.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pintoinsta.app.MainActivity
import com.pintoinsta.app.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_SERVICE_ID = "channel_crossai_service"
        const val CHANNEL_SERVICE_NAME = "CrossAI Background Automation"

        const val CHANNEL_ACTIVITY_ID = "channel_crossai_activity"
        const val CHANNEL_ACTIVITY_NAME = "CrossAI Activity Updates"

        const val SERVICE_NOTIFICATION_ID = 1001

        private var instance: NotificationHelper? = null

        @Synchronized
        fun getInstance(context: Context): NotificationHelper {
            if (instance == null) {
                instance = NotificationHelper(context.applicationContext)
            }
            return instance!!
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Foreground service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                CHANNEL_SERVICE_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CrossAIStudio automation running reliably in background"
                setShowBadge(false)
            }

            // High priority activity notification channel
            val activityChannel = NotificationChannel(
                CHANNEL_ACTIVITY_ID,
                CHANNEL_ACTIVITY_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pushes real-time updates for Pinterest searches and Instagram posts"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(activityChannel)
        }
    }

    private fun getLaunchPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    fun buildForegroundServiceNotification(statusText: String = "Automation active in background"): Notification {
        return NotificationCompat.Builder(context, CHANNEL_SERVICE_ID)
            .setContentTitle("CrossAIStudio")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(getLaunchPendingIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun pushNotification(id: Int, title: String, message: String, isSuccess: Boolean = true) {
        val icon = if (isSuccess) R.drawable.ic_check_circle else R.drawable.ic_error

        val notification = NotificationCompat.Builder(context, CHANNEL_ACTIVITY_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(getLaunchPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Android 13 permission not yet granted
        }
    }

    // --- ACTIVITY NOTIFICATION HELPERS ---

    fun notifySearchStarted(accountName: String, keyword: String) {
        val id = (System.currentTimeMillis() % 100000).toInt()
        pushNotification(
            id = id,
            title = "🔍 Pinterest Search Started",
            message = "Searching for '$keyword' using account @$accountName",
            isSuccess = true
        )
    }

    fun notifySearchCompleted(accountName: String, keyword: String, count: Int) {
        val id = (System.currentTimeMillis() % 100000).toInt()
        pushNotification(
            id = id,
            title = "✅ Pinterest Search Completed",
            message = "Found & saved $count pins for '$keyword' (@$accountName)",
            isSuccess = true
        )
    }

    fun notifyPostStarted(pinId: String, igUsername: String) {
        val id = (System.currentTimeMillis() % 100000).toInt()
        pushNotification(
            id = id,
            title = "📤 Instagram Post Started",
            message = "Preparing to upload Pin $pinId to @$igUsername",
            isSuccess = true
        )
    }

    fun notifyPostSuccess(pinId: String, igUsername: String, caption: String) {
        val id = (System.currentTimeMillis() % 100000).toInt()
        val truncatedCaption = if (caption.length > 50) caption.substring(0, 47) + "..." else caption
        pushNotification(
            id = id,
            title = "🎉 Instagram Post Successful!",
            message = "Published Pin $pinId to @$igUsername with caption: \"$truncatedCaption\"",
            isSuccess = true
        )
    }

    fun notifyPostFailed(pinId: String, igUsername: String, error: String) {
        val id = (System.currentTimeMillis() % 100000).toInt()
        pushNotification(
            id = id,
            title = "❌ Instagram Post Failed",
            message = "Failed posting Pin $pinId to @$igUsername: $error",
            isSuccess = false
        )
    }

    fun notifyStatusAlert(accountName: String, platform: String, status: String) {
        val id = (System.currentTimeMillis() % 100000).toInt()
        val isSuccess = status.equals("Active", ignoreCase = true)
        pushNotification(
            id = id,
            title = if (isSuccess) "✅ $platform Account Active" else "⚠️ $platform Session Alert",
            message = "$platform account '@$accountName' status: $status",
            isSuccess = isSuccess
        )
    }
}
