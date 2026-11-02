package com.pintoinsta.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pintoinsta.app.service.CrossAIPostingService
import com.pintoinsta.app.util.PostingConfigManager

class RestartServiceReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RESTART_SERVICE = "com.pintoinsta.app.RESTART_SERVICE"
        private const val TAG = "RestartServiceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!PostingConfigManager.isBackgroundRunningEnabled(context)) {
            Log.d(TAG, "Background running disabled by user. Ignoring restart request.")
            return
        }
        Log.d(TAG, "RestartServiceReceiver received: ${intent?.action}. Reviving CrossAIPostingService!")
        CrossAIPostingService.startService(context)
    }
}
