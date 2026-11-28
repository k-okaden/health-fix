package jp.healthfix

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = try {
            val settings = HealthFixData.settings(applicationContext)
            if (!settings.autoRun) return Result.success()
            val now = LocalDateTime.now()
            if (!now.toLocalTime().isBefore(settings.end)) {
                val done = HealthFixData.history(applicationContext).any { it.day == now.toLocalDate().toString() }
                if (!done) HealthFixData.writeDay(applicationContext, now.toLocalDate(), settings, "自動")
            }
            Result.success()
        } catch (_: Exception) { Result.failure() }
        AutoSchedule.schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return result
    }
}

object AutoSchedule {
    private const val UNIQUE = "health-fix-daily"
    fun schedule(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val settings = HealthFixData.settings(context)
        val manager = WorkManager.getInstance(context)
        if (!settings.autoRun) { manager.cancelUniqueWork(UNIQUE); return }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(settings.end).plusMinutes(1)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<DailyWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        manager.enqueueUniqueWork(UNIQUE, policy, request)
    }
}
