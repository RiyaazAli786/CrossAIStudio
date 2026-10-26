package com.pintoinsta.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.service.CrossAIPostingService
import com.pintoinsta.app.worker.ScheduleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "BootReceiver received action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_USER_PRESENT
        ) {
            // Start persistent background service
            CrossAIPostingService.startService(context)

            // Re-schedule all active account jobs
            val repository = AppRepository.getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                val accounts = repository.getAllPinterestAccounts()
                for (account in accounts) {
                    if (account.isAutoPostEnabled) {
                        ScheduleManager.updateAccountSchedule(context, account)
                    }
                }
                ScheduleManager.setupDailyPostSchedule(context)
            }
        }
    }
}
