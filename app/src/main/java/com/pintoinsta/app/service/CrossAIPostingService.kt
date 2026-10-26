package com.pintoinsta.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.notification.NotificationHelper
import com.pintoinsta.app.receiver.RestartServiceReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent Background Service that stays alive even when app is cleared from Recent tasks.
 */
class CrossAIPostingService : Service() {

    companion object {
        const val TAG = "CrossAIPostingService"
        const val ACTION_START = "action_start_crossai_service"
        const val ACTION_STOP = "action_stop_crossai_service"

        fun startService(context: Context) {
            val intent = Intent(context, CrossAIPostingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var repository: AppRepository

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper.getInstance(this)
        repository = AppRepository.getInstance(this)

        startForeground(
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            notificationHelper.buildForegroundServiceNotification("CrossAIStudio running in background")
        )

        startBackgroundTicker()
        Log.d(TAG, "CrossAIPostingService created and foreground started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Return START_STICKY so system restarts service if terminated
        return START_STICKY
    }

    /**
     * Requirement 1: Run in background even when closed from recent apps.
     * onTaskRemoved triggers when the user swipes away the app from recent tasks.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "App closed from recent tasks! Scheduling immediate service resurrection...")

        val restartIntent = Intent(applicationContext, RestartServiceReceiver::class.java).apply {
            action = RestartServiceReceiver.ACTION_RESTART_SERVICE
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            9999,
            restartIntent,
            flags
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = SystemClock.elapsedRealtime() + 1000 // Restart in 1 second

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
        }
    }

    private fun startBackgroundTicker() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val accounts = repository.getAllPinterestAccounts()
                    val now = System.currentTimeMillis()

                    for (account in accounts) {
                        if (account.isAutoPostEnabled && account.nextRunTime in 1..now) {
                            Log.d(TAG, "Scheduled run due for account '${account.username}'")
                            repository.executePublishOrSearchFlow(account.id)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background ticker error", e)
                }

                // Sleep for 60 seconds before checking next cycle
                delay(60_000L)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "CrossAIPostingService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
