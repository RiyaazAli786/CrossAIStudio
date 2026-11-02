package com.pintoinsta.app

import android.app.Application
import com.pintoinsta.app.service.CrossAIPostingService
import com.pintoinsta.app.util.PostingConfigManager
import com.pintoinsta.app.util.ThemeManager
import com.pintoinsta.app.worker.ScheduleManager

class PinToInstaApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Apply Light theme by default (or user selected theme)
        ThemeManager.applyTheme(this)

        // Only start background services and workers if enabled by user
        if (PostingConfigManager.isBackgroundRunningEnabled(this)) {
            // Start persistent background service (runs even when closed from recents)
            CrossAIPostingService.startService(this)

            // Setup recurring background worker for daily posting
            ScheduleManager.setupDailyPostSchedule(this)
        }
    }
}
