package com.example.appblocker.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.appblocker.data.model.UsageSnapshot
import com.example.appblocker.di.AppModule
import java.util.concurrent.TimeUnit

class UsageSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val usage = AppModule.usageRepository
        if (!usage.hasPermission()) return Result.success()

        val dao = AppModule.database.usageSnapshotDao()
        val now = System.currentTimeMillis()
        // Yesterday is stored too, so its total is final once midnight has passed.
        for (offset in 1 downTo 0) {
            val start = usage.dayStart(offset)
            val end = if (offset == 0) now else usage.dayStart(offset - 1)
            val perApp = usage.usageBetween(start, end)
                .filterKeys { it != applicationContext.packageName }
            val top = perApp.maxByOrNull { it.value }
            dao.upsert(
                UsageSnapshot(
                    dayStart = start,
                    totalMs = perApp.values.sum(),
                    topPackage = top?.key,
                    topPackageMs = top?.value ?: 0L
                )
            )
        }
        dao.deleteOlderThan(usage.dayStart(0) - TimeUnit.DAYS.toMillis(90))
        AppModule.settingsRepository.lastUsageSync = System.currentTimeMillis()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "usage_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

}
