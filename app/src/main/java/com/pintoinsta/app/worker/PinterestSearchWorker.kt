package com.pintoinsta.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pintoinsta.app.data.repository.AppRepository

class PinterestSearchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "PinterestSearchWorker"
        const val KEY_ACCOUNT_ID = "account_id"
    }

    override suspend fun doWork(): Result {
        val accountId = inputData.getLong(KEY_ACCOUNT_ID, -1L)
        if (accountId == -1L) {
            Log.e(TAG, "Invalid accountId in worker parameters")
            return Result.failure()
        }

        val repository = AppRepository.getInstance(applicationContext)

        return try {
            val account = repository.getPinterestAccountById(accountId)
            if (account == null) {
                Log.e(TAG, "Account $accountId no longer exists")
                return Result.failure()
            }

            Log.d(TAG, "Starting scheduled Pinterest search for account: ${account.username}")
            val searchResult = repository.executePinterestSearch(accountId)
            Log.d(TAG, "Search completed: ${searchResult.second}")

            // If auto-post is enabled and an Instagram account is linked, post the next pin
            if (account.isAutoPostEnabled && account.linkedInstagramAccountId != null) {
                Log.d(TAG, "Auto-post enabled, triggering Instagram post...")
                val postResult = repository.executeInstagramPost(accountId, limit = 1)
                Log.d(TAG, "Instagram post result: ${postResult.second}")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "PinterestSearchWorker execution failed", e)
            Result.retry()
        }
    }
}
