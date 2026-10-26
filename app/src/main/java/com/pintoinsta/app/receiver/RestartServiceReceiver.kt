package com.pintoinsta.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pintoinsta.app.service.CrossAIPostingService

class RestartServiceReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RESTART_SERVICE = "com.pintoinsta.app.RESTART_SERVICE"
        private const val TAG = "RestartServiceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "RestartServiceReceiver received: ${intent?.action}. Reviving CrossAIPostingService!")
        CrossAIPostingService.startService(context)
    }
}
