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
import com.pintoinsta.app.ui.viewer.PostViewerActivity
import com.pintoinsta.app.util.InstagramCaptionUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_SERVICE_ID = "channel_crossai_service"
        const val CHANNEL_SERVICE_NAME = "CrossAI Background Automation"

        const val CHANNEL_ACTIVITY_ID = "channel_crossai_activity"
        const val CHANNEL_ACTIVITY_NAME = "CrossAI Activity Updates"

        const val SERVICE_NOTIFICATION_ID = 1001
        const val GROUP_KEY_ACTIVITY = "com.crossai.studio.ACTIVITY_GROUP"

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

    // Dedicated single-thread worker to prevent any thread/UI blocking on bulk notifications
    private val notificationExecutor = Executors.newSingleThreadExecutor()

    // Atomic counter for guaranteed unique, fast notification IDs
    private val notificationIdCounter = AtomicInteger(2000)

    // Rate limiter for vibration/sound to prevent audio/haptic buffer congestion in bulk operations
    @Volatile
    private var lastAudibleAlertTime = 0L

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                CHANNEL_SERVICE_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CrossAIStudio automation running reliably in background"
                setShowBadge(false)
            }

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

    /**
     * Dispatches notification asynchronously to ensure zero UI freezing on bulk calls.
     * Throttles audio/haptics so bulk notifications show instantly without dropping.
     */
    fun pushNotification(
        id: Int = notificationIdCounter.incrementAndGet(),
        title: String,
        message: String,
        isSuccess: Boolean = true,
        actionTitle: String? = null,
        actionIntent: PendingIntent? = null,
        contentIntent: PendingIntent? = null
    ) {
        notificationExecutor.execute {
            try {
                val now = System.currentTimeMillis()
                val shouldAlertAudibly = (now - lastAudibleAlertTime > 1500L)
                if (shouldAlertAudibly) {
                    lastAudibleAlertTime = now
                }

                val builder = NotificationCompat.Builder(context, CHANNEL_ACTIVITY_ID)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(contentIntent ?: getLaunchPendingIntent())
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setGroup(GROUP_KEY_ACTIVITY)
                    .setOnlyAlertOnce(!shouldAlertAudibly)

                if (!shouldAlertAudibly) {
                    builder.setSilent(true)
                }

                if (actionTitle != null && actionIntent != null) {
                    builder.addAction(R.drawable.ic_open_in_new, actionTitle, actionIntent)
                }

                val notification = builder.build()
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (_: SecurityException) {
                // Post notification permission not granted
            } catch (e: Exception) {
                // Ignore other notification errors
            }
        }
    }

    // --- ACTIVITY NOTIFICATION HELPERS ---

    fun notifySearchStarted(accountName: String, keyword: String) {
        pushNotification(
            title = "🔍 Pinterest Search Started",
            message = "Searching for '$keyword' using account @$accountName",
            isSuccess = true
        )
    }

    fun notifySearchCompleted(accountName: String, keyword: String, count: Int) {
        pushNotification(
            title = "✅ Pinterest Search Completed",
            message = "Found & saved $count pins for '$keyword' (@$accountName)",
            isSuccess = true
        )
    }

    fun notifyPostStarted(pinId: String, igUsername: String) {
        pushNotification(
            title = "📤 Instagram Post Started",
            message = "Preparing to upload Pin $pinId to @$igUsername",
            isSuccess = true
        )
    }

    fun notifyPostSuccess(
        pinId: String,
        igUsername: String,
        caption: String,
        postUrl: String? = null,
        instagramAccountId: Long? = null
    ) {
        val truncatedCaption = InstagramCaptionUtils.safeTruncate(caption, 50, appendEllipsis = true)
        val bodyText = if (!postUrl.isNullOrBlank()) {
            "Published Pin $pinId to @$igUsername!\n$postUrl"
        } else {
            "Published Pin $pinId to @$igUsername with caption: \"$truncatedCaption\""
        }

        var viewPostPendingIntent: PendingIntent? = null
        if (!postUrl.isNullOrBlank()) {
            val viewIntent = Intent(context, PostViewerActivity::class.java).apply {
                putExtra(PostViewerActivity.EXTRA_URL, postUrl)
                if (instagramAccountId != null) {
                    putExtra(PostViewerActivity.EXTRA_ACCOUNT_ID, instagramAccountId)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            viewPostPendingIntent = PendingIntent.getActivity(
                context,
                notificationIdCounter.incrementAndGet(),
                viewIntent,
                flags
            )
        }

        pushNotification(
            title = "🎉 Instagram Post Successful!",
            message = bodyText,
            isSuccess = true,
            actionTitle = if (viewPostPendingIntent != null) "View Reel" else null,
            actionIntent = viewPostPendingIntent,
            contentIntent = viewPostPendingIntent
        )
    }

    fun notifyPostFailed(pinId: String, igUsername: String, error: String) {
        pushNotification(
            title = "❌ Instagram Post Failed",
            message = "Failed posting Pin $pinId to @$igUsername: $error",
            isSuccess = false
        )
    }

    fun notifyStatusAlert(accountName: String, platform: String, status: String) {
        val isSuccess = status.equals("Active", ignoreCase = true)
        pushNotification(
            title = if (isSuccess) "✅ $platform Account Active" else "⚠️ $platform Session Alert",
            message = "$platform account '@$accountName' status: $status",
            isSuccess = isSuccess
        )
    }
}
