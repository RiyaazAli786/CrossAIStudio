package com.pintoinsta.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pintoinsta.app.data.repository.AppRepository

/**
 * Requirement 5: Daily worker that downloads each pin and posts to Instagram
 */
class InstagramDailyPostWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "InstagramDailyPostWorker"
        const val WORK_NAME = "instagram_daily_post_work"
    }

    override suspend fun doWork(): Result {
        val repository = AppRepository.getInstance(applicationContext)

        return try {
            val pinterestAccounts = repository.getAllPinterestAccounts()
            for (acc in pinterestAccounts) {
                if (acc.isAutoPostEnabled && acc.linkedInstagramAccountId != null) {
                    Log.d(TAG, "Processing daily post for Pinterest account: ${acc.username}")
                    repository.executeInstagramPost(acc.id, limit = 1)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "InstagramDailyPostWorker failed", e)
            Result.retry()
        }
    }
}
