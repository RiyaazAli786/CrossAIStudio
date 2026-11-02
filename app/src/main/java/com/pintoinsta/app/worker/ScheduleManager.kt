package com.pintoinsta.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.util.PostingConfigManager
import java.util.concurrent.TimeUnit

object ScheduleManager {

    private fun getWorkTag(accountId: Long) = "pinterest_schedule_work_$accountId"

    fun updateAccountSchedule(context: Context, account: PinterestAccount) {
        val workManager = WorkManager.getInstance(context)
        val tag = getWorkTag(account.id)

        if (!account.isAutoPostEnabled || !PostingConfigManager.isBackgroundRunningEnabled(context)) {
            workManager.cancelUniqueWork(tag)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putLong(PinterestSearchWorker.KEY_ACCOUNT_ID, account.id)
            .build()

        val intervalHours = account.scheduleIntervalHours.coerceAtLeast(1).toLong()

        val workRequest = PeriodicWorkRequestBuilder<PinterestSearchWorker>(
            intervalHours,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniquePeriodicWork(
            tag,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    fun runNow(context: Context, accountId: Long) {
        val workManager = WorkManager.getInstance(context)
        val inputData = Data.Builder()
            .putLong(PinterestSearchWorker.KEY_ACCOUNT_ID, accountId)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWork = OneTimeWorkRequestBuilder<PinterestSearchWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniqueWork(
            "run_now_$accountId",
            ExistingWorkPolicy.REPLACE,
            oneTimeWork
        )
    }

    fun setupDailyPostSchedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!PostingConfigManager.isBackgroundRunningEnabled(context)) {
            workManager.cancelUniqueWork(InstagramDailyPostWorker.WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val dailyRequest = PeriodicWorkRequestBuilder<InstagramDailyPostWorker>(
            24,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            InstagramDailyPostWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            dailyRequest
        )
    }

    fun cancelAllSchedules(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork()
    }

    suspend fun rescheduleAll(context: Context) {
        if (!PostingConfigManager.isBackgroundRunningEnabled(context)) return
        val repository = AppRepository.getInstance(context)
        val accounts = repository.getAllPinterestAccounts()
        for (account in accounts) {
            if (account.isAutoPostEnabled) {
                updateAccountSchedule(context, account)
            }
        }
        setupDailyPostSchedule(context)
    }
}
