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
import com.refayatul.fityah.utils.DataStoreManager

/** Re-enables app groups after an explicitly chosen temporary break. */
class TemporaryGroupDisableJob : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            try {
                DataStoreManager(applicationContext).restoreDueTemporaryAppGroups()
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
        private const val JOB_ID = 7714

        fun schedule(context: Context, deadlineMs: Long?) {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)
            deadlineMs ?: return
            val delayMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(appContext, TemporaryGroupDisableJob::class.java))
                    .setMinimumLatency(delayMs)
                    .setOverrideDeadline(delayMs)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
