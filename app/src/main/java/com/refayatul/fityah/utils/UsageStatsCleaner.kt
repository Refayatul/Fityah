package com.refayatul.fityah.utils

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.refayatul.fityah.data.db.AppDatabase

/**
 * Deletes usage stats (app usage, website stats, reel counts) older than four
 * weeks so the database never grows without bound. Runs at most once per day.
 */
object UsageStatsCleaner {
    private const val RETENTION_DAYS = 28L
    private const val PREFS = "usage_cleanup"
    private const val KEY_LAST_RUN_DAY = "last_run_day"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watching = AtomicBoolean(false)

    /** Keeps a long lived process (the accessibility service) cleaning across midnights. */
    fun watch(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            while (true) {
                runCatching { cleanIfDue(app) }
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    fun cleanNowIfDue(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { cleanIfDue(app) } }
    }

    private suspend fun cleanIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_LAST_RUN_DAY, null) == today) return
        clean(context)
        prefs.edit().putString(KEY_LAST_RUN_DAY, today).apply()
    }

    private suspend fun clean(context: Context) {
        val db = AppDatabase.getInstance(context)
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS)
        // Same pattern the trackers write with (TimeTools.getCurrentDate).
        val formatter = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault())

        // A date written under an older device language can't be parsed (or ever
        // queried) anymore, so it counts as stale too.
        fun stale(dates: List<String>): List<String> = dates.filter { date ->
            val parsed = runCatching { LocalDate.parse(date, formatter) }.getOrNull()
            parsed == null || parsed.isBefore(cutoff)
        }

        stale(db.websiteStatsDao().getDistinctDates()).chunked(500).forEach {
            db.websiteStatsDao().deleteByDates(it)
        }
        stale(db.appUsageDao().getDistinctDates()).chunked(500).forEach {
            db.appUsageDao().deleteByDates(it)
        }
        stale(db.reelStatsDao().getDistinctDates()).chunked(500).forEach {
            db.reelStatsDao().deleteByDates(it)
        }
        stale(db.reelUsageStatsDao().getDistinctDates()).chunked(500).forEach {
            db.reelUsageStatsDao().deleteByDates(it)
        }
    }
}
