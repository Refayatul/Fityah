package com.refayatul.fityah.ui.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.refayatul.fityah.R
import com.refayatul.fityah.data.sync.SyncGateway
import com.refayatul.fityah.ui.activity.FragmentActivity
import com.refayatul.fityah.ui.fragments.main.usage.AllAppsUsageFragment
import com.refayatul.fityah.utils.DataStoreManager
import com.refayatul.fityah.utils.TimeTools
import com.refayatul.fityah.utils.UsageStatsHelper
import com.refayatul.fityah.utils.getDefaultLauncherPackageName
import java.time.LocalDate

class ScreentimeWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "ScreentimeWidgetProvider"
        private const val ACTION_WIDGET_REFRESH = "com.refayatul.fityah.screentime.WIDGET_REFRESH"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        try {
            appWidgetIds.forEach { widgetId ->
                updateWidget(context, appWidgetManager, widgetId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widgets", e)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        try {
            when (intent.action) {
                ACTION_WIDGET_REFRESH -> handleRefresh(context, intent)
                else -> Log.d(TAG, "Received unhandled action: ${intent.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling widget receive", e)
        }
    }

    private fun handleRefresh(context: Context, intent: Intent) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            ?: appWidgetManager.getAppWidgetIds(ComponentName(context, ScreentimeWidgetProvider::class.java))

        widgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val usageStatsHelper = UsageStatsHelper(context)
        val ignoredPackages = mutableSetOf<String>()
        getDefaultLauncherPackageName(context.packageManager)?.let { ignoredPackages.add(it) }

        val ignoredApps = runBlocking {
            DataStoreManager(context).settings.first().usageTrackerIgnoredApps
        }
        ignoredPackages.addAll(ignoredApps)

        val localList = runBlocking { usageStatsHelper.getForegroundStatsByRelativeDay(0) }.filter {
            it.totalTime >= 1_000 && it.packageName !in ignoredPackages
        }

        // Fold in app + website usage synced from the user's other devices, same
        // as AllAppsUsageViewModel, so the widget total matches the in-app total.
        // Empty on F-Droid (NoopSyncProvider) and whenever nothing has synced yet.
        val today = LocalDate.now().toString()
        val remoteApps = runBlocking {
            runCatching { SyncGateway.provider.remoteAppUsage(today) }.getOrDefault(emptyMap())
        }
        val remoteWebsiteTime = runBlocking {
            runCatching { SyncGateway.provider.remoteWebsiteUsage(today) }.getOrDefault(emptyMap())
        }.values.sum()

        val list = mergeRemoteApps(localList, remoteApps, ignoredPackages).sortedByDescending { it.totalTime }

        val totalScreentime = list.sumOf { it.totalTime } + remoteWebsiteTime

        try {
            val views = RemoteViews(context.packageName, R.layout.widget_app_stats).apply {
                setTextViewText(R.id.screentime_widget, formatTime(totalScreentime))

                setAppUsageText(this, 0, list, R.id.app_1_sm, context)
                setAppUsageText(this, 1, list, R.id.app_2_sm, context)
                setAppUsageText(this, 2, list, R.id.app_3_sm, context)

                val refreshIntent = createRefreshIntent(context, widgetId)
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.refresh_stats_screentime, refreshPendingIntent)

                val openIntent = Intent(context, FragmentActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
                }
                val openPendingIntent = PendingIntent.getActivity(
                    context,
                    widgetId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_bg_app_stats, openPendingIntent)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget $widgetId", e)
        }
    }

    // Combines other devices' per app time into this device's list: matching apps
    // get their time added together, and apps that only ran on another device are
    // appended as their own rows. Mirrors AllAppsUsageViewModel.mergeRemoteApps.
    private fun mergeRemoteApps(
        local: List<AllAppsUsageFragment.Stat>,
        remote: Map<String, Long>,
        ignoredPackages: Set<String>,
    ): List<AllAppsUsageFragment.Stat> {
        if (remote.isEmpty()) return local
        val localByPkg = local.associateBy { it.packageName }
        val merged = ArrayList<AllAppsUsageFragment.Stat>(local.size + remote.size)
        for (st in local) {
            val extra = remote[st.packageName] ?: 0L
            merged.add(
                if (extra > 0L) {
                    AllAppsUsageFragment.Stat(st.packageName, st.totalTime + extra, st.sessions, st.hourlyUsage)
                } else {
                    st
                }
            )
        }
        for ((pkg, ms) in remote) {
            if (pkg !in localByPkg && ms >= 1_000 && pkg !in ignoredPackages) {
                merged.add(AllAppsUsageFragment.Stat(pkg, ms))
            }
        }
        return merged
    }

    private fun setAppUsageText(
        remoteViews: RemoteViews,
        index: Int,
        list: List<AllAppsUsageFragment.Stat>,
        textViewId: Int,
        context: Context
    ) {
        val item = list.getOrNull(index)
        if (item == null) {
            remoteViews.setTextViewText(textViewId, "")
            return
        }
        try {
            val usage = TimeTools.formatTimeForWidget(item.totalTime)
            val appName = context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(item.packageName, 0)
            )
            remoteViews.setTextViewText(textViewId, "$usage : $appName")
        } catch (e: PackageManager.NameNotFoundException) {
            val usage = TimeTools.formatTimeForWidget(item.totalTime)
            remoteViews.setTextViewText(textViewId, "$usage : ${item.packageName}")
        }
    }

    private fun createRefreshIntent(context: Context, widgetId: Int): Intent {
        return Intent(context, ScreentimeWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
    }

    private fun formatTime(timeInMillis: Long): String {
        val hours = timeInMillis / (1000 * 60 * 60)
        val minutes = (timeInMillis % (1000 * 60 * 60)) / (1000 * 60)

        if (hours == 0L && minutes == 0L) return "0m"

        return buildString {
            if (hours > 0) append("${hours}h")
            if (minutes > 0) append(" ${minutes}m")
        }.trim()
    }
}
