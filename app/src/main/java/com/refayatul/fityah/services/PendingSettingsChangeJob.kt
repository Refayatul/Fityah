package com.refayatul.fityah.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.refayatul.fityah.data.models.PendingSettingsChange
import com.refayatul.fityah.utils.DataStoreManager

/**
 * Durable deadline runner for deferred settings. UI countdowns only display state; this job
 * applies it even after the app and accessibility service become idle.
 */
class PendingSettingsChangeJob : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            try {
                DataStoreManager(applicationContext).applyDuePendingChanges()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 7713

        fun schedule(context: Context, changes: List<PendingSettingsChange>) {
            val appContext = context.applicationContext
            val scheduler =
                appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)
            val now = System.currentTimeMillis()
            val nextDeadline = changes.map { it.appliesAtMs }.filter { it > now }.minOrNull()
                ?: return
            val delayMs = nextDeadline - now
            scheduler.schedule(
                JobInfo.Builder(
                    JOB_ID,
                    ComponentName(appContext, PendingSettingsChangeJob::class.java)
                )
                    .setMinimumLatency(delayMs)
                    .setOverrideDeadline(delayMs)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
