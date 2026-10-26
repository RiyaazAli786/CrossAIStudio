package com.pintoinsta.app

import android.app.Application
import com.pintoinsta.app.service.CrossAIPostingService
import com.pintoinsta.app.worker.ScheduleManager

class PinToInstaApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Start persistent background service (runs even when closed from recents)
        CrossAIPostingService.startService(this)

        // Setup recurring background worker for daily posting
        ScheduleManager.setupDailyPostSchedule(this)
    }
}
