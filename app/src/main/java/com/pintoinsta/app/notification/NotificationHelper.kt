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
import androidx.core.content.ContextCompat
import com.pintoinsta.app.MainActivity
import com.pintoinsta.app.R
import com.pintoinsta.app.ui.viewer.PostViewerActivity
import com.pintoinsta.app.util.InstagramCaptionUtils
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_SERVICE_ID = "channel_crossai_service"
        const val CHANNEL_SERVICE_NAME = "CrossAI Background Automation"

        const val CHANNEL_ACTIVITY_ID = "channel_crossai_activity"
        const val CHANNEL_ACTIVITY_NAME = "CrossAI Activity Updates"

        const val SERVICE_NOTIFICATION_ID = 1001
        const val SUMMARY_NOTIFICATION_ID = 2000
        const val GROUP_KEY_ACTIVITY = "com.crossai.studio.ACTIVITY_GROUP"

        // Maximum active notifications allowed in the shade to prevent SystemUI freeze / memory exhaustion
        private const val MAX_ACTIVE_NOTIFICATIONS = 5
        private const val MAX_SUMMARY_LINES = 5
        private const val MIN_DISPATCH_INTERVAL_MS = 100L // Throttles Binder IPC to prevent phone freeze

        // Deterministic ID base offsets
        private const val BASE_ID_SEARCH = 2100
        private const val BASE_ID_POST = 2300
        private const val BASE_ID_STATUS = 2500

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

    // Fallback atomic counter for unique notifications
    private val notificationIdCounter = AtomicInteger(3000)

    // Rate limiter for vibration/sound to prevent audio/haptic buffer congestion in bulk operations
    @Volatile
    private var lastAudibleAlertTime = 0L

    // Rate limiter for binder dispatch to prevent flooding system_server
    @Volatile
    private var lastDispatchTime = 0L

    // Sliding window of active notification IDs
    private val activeNotificationIds = ConcurrentLinkedQueue<Int>()

    // Rolling list of recent activity messages for Group Summary InboxStyle
    private val recentActivityLines = Collections.synchronizedList(mutableListOf<String>())

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

            // Using IMPORTANCE_DEFAULT prevents fullscreen heads-up peek banners from spamming and hanging the UI
            val activityChannel = NotificationChannel(
                CHANNEL_ACTIVITY_ID,
                CHANNEL_ACTIVITY_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Pushes updates for Pinterest searches and Instagram posts"
                enableLights(true)
                enableVibration(false) // Disable vibration at channel level so bulk operations never stutter haptic engine
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
            .setSmallIcon(R.drawable.ic_notification_dot)
            .setColor(ContextCompat.getColor(context, R.color.status_green))
            .setContentIntent(getLaunchPendingIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Dispatches notification asynchronously with rate-limiting, group summary management,
     * and a sliding-window pruner to ensure zero UI freezes or system server hangs on bulk operations.
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
                // Throttle rapid-fire Binder IPC calls to prevent UI/System server hanging
                val now = System.currentTimeMillis()
                val elapsedSinceLast = now - lastDispatchTime
                if (elapsedSinceLast < MIN_DISPATCH_INTERVAL_MS) {
                    try {
                        Thread.sleep(MIN_DISPATCH_INTERVAL_MS - elapsedSinceLast)
                    } catch (_: InterruptedException) {}
                }
                lastDispatchTime = System.currentTimeMillis()

                val shouldAlertAudibly = (lastDispatchTime - lastAudibleAlertTime > 2500L)
                if (shouldAlertAudibly) {
                    lastAudibleAlertTime = lastDispatchTime
                }

                // Prune older notifications if exceeding MAX_ACTIVE_NOTIFICATIONS (sliding window)
                if (!activeNotificationIds.contains(id)) {
                    activeNotificationIds.add(id)
                    while (activeNotificationIds.size > MAX_ACTIVE_NOTIFICATIONS) {
                        val oldestId = activeNotificationIds.poll()
                        if (oldestId != null && oldestId != id) {
                            try {
                                notificationManager.cancel(oldestId)
                            } catch (_: Exception) {}
                        }
                    }
                }

                val builder = NotificationCompat.Builder(context, CHANNEL_ACTIVITY_ID)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setSmallIcon(R.drawable.ic_notification_dot)
                    .setColor(ContextCompat.getColor(context, if (isSuccess) R.color.status_green else R.color.status_red))
                    .setContentIntent(contentIntent ?: getLaunchPendingIntent())
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setGroup(GROUP_KEY_ACTIVITY)
                    .setOnlyAlertOnce(true)

                if (!shouldAlertAudibly) {
                    builder.setSilent(true)
                }

                if (actionTitle != null && actionIntent != null) {
                    builder.addAction(R.drawable.ic_open_in_new, actionTitle, actionIntent)
                }

                val notification = builder.build()
                NotificationManagerCompat.from(context).notify(id, notification)

                // Update Group Summary Notification so Android bundles them into a single clean drawer entry
                updateGroupSummary(title, message)
            } catch (_: SecurityException) {
                // Post notification permission not granted
            } catch (e: Exception) {
                // Ignore other notification errors
            }
        }
    }

    /**
     * Posts or updates the Group Summary notification with an InboxStyle list of recent operations.
     * Prevents dozens of independent notification cards from littering and lagging the notification shade.
     */
    private fun updateGroupSummary(latestTitle: String, latestMessage: String) {
        try {
            val line = "$latestTitle: $latestMessage".take(80)
            synchronized(recentActivityLines) {
                recentActivityLines.add(line)
                while (recentActivityLines.size > MAX_SUMMARY_LINES) {
                    recentActivityLines.removeAt(0)
                }
            }

            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle("CrossAI Activity Updates")
                .setSummaryText("${activeNotificationIds.size} updates")

            synchronized(recentActivityLines) {
                for (item in recentActivityLines) {
                    inboxStyle.addLine(item)
                }
            }

            val summaryNotification = NotificationCompat.Builder(context, CHANNEL_ACTIVITY_ID)
                .setContentTitle("CrossAI Activity Updates")
                .setContentText(latestMessage)
                .setSmallIcon(R.drawable.ic_notification_dot)
                .setColor(ContextCompat.getColor(context, R.color.status_green))
                .setStyle(inboxStyle)
                .setGroup(GROUP_KEY_ACTIVITY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(getLaunchPendingIntent())
                .build()

            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, summaryNotification)
        } catch (_: Exception) {}
    }

    /**
     * Clears all activity notifications and resets summary state.
     */
    fun clearAllActivityNotifications() {
        notificationExecutor.execute {
            try {
                for (id in activeNotificationIds) {
                    notificationManager.cancel(id)
                }
                activeNotificationIds.clear()
                notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
                synchronized(recentActivityLines) {
                    recentActivityLines.clear()
                }
            } catch (_: Exception) {}
        }
    }

    // --- ACTIVITY NOTIFICATION HELPERS WITH OPERATION-BASED DEDUPLICATION ---

    fun notifySearchStarted(accountName: String, keyword: String) {
        val id = BASE_ID_SEARCH + ((accountName.hashCode() and 0x7FFFFFFF) % 50)
        pushNotification(
            id = id,
            title = "🔍 Pinterest Search Started",
            message = "Searching for '$keyword' (@$accountName)",
            isSuccess = true
        )
    }

    fun notifySearchCompleted(accountName: String, keyword: String, count: Int) {
        // Replaces the search-started notification for this account instead of spawning an extra one
        val id = BASE_ID_SEARCH + ((accountName.hashCode() and 0x7FFFFFFF) % 50)
        pushNotification(
            id = id,
            title = "✅ Pinterest Search Completed",
            message = "Found & saved $count pins for '$keyword' (@$accountName)",
            isSuccess = true
        )
    }

    fun notifyPostStarted(pinId: String, igUsername: String) {
        val id = BASE_ID_POST + ((pinId.hashCode() and 0x7FFFFFFF) % 50)
        pushNotification(
            id = id,
            title = "📤 Instagram Post Started",
            message = "Uploading Pin $pinId to @$igUsername",
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

        // Replaces the post-started notification for this pin
        val id = BASE_ID_POST + ((pinId.hashCode() and 0x7FFFFFFF) % 50)
        pushNotification(
            id = id,
            title = "🎉 Instagram Post Successful!",
            message = bodyText,
            isSuccess = true,
            actionTitle = if (viewPostPendingIntent != null) "View Reel" else null,
            actionIntent = viewPostPendingIntent,
            contentIntent = viewPostPendingIntent
        )
    }

    fun notifyPostFailed(pinId: String, igUsername: String, error: String) {
        // Replaces the post-started notification for this pin
        val id = BASE_ID_POST + ((pinId.hashCode() and 0x7FFFFFFF) % 50)
        pushNotification(
            id = id,
            title = "❌ Instagram Post Failed",
            message = "Failed posting Pin $pinId to @$igUsername: $error",
            isSuccess = false
        )
    }

    fun notifyStatusAlert(accountName: String, platform: String, status: String) {
        val isSuccess = status.equals("Active", ignoreCase = true)
        // Deterministic ID per platform/account so checking status repeatedly updates in place
        val id = BASE_ID_STATUS + (((platform + accountName).hashCode() and 0x7FFFFFFF) % 50)
        pushNotification(
            id = id,
            title = if (isSuccess) "✅ $platform Account Active" else "⚠️ $platform Session Alert",
            message = "$platform account '@$accountName' status: $status",
            isSuccess = isSuccess
        )
    }
}
