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

            Log.d(TAG, "Starting scheduled execution for account: ${account.username}")
            val flowResult = repository.executePublishOrSearchFlow(accountId)
            Log.d(TAG, "Scheduled execution completed: ${flowResult.second}")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "PinterestSearchWorker execution failed", e)
            Result.retry()
        }
    }
}
