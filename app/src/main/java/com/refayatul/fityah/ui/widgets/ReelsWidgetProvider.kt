package com.refayatul.fityah.ui.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.runBlocking
import com.refayatul.fityah.R
import com.refayatul.fityah.data.db.AppDatabase
import com.refayatul.fityah.utils.TimeTools

class ReelsWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "ReelsWidgetProvider"
        private const val ACTION_WIDGET_REFRESH = "com.refayatul.fityah.reels.WIDGET_REFRESH"
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
            ?: appWidgetManager.getAppWidgetIds(ComponentName(context, ReelsWidgetProvider::class.java))

        widgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        try {
            val currentDate = TimeTools.getCurrentDate()
            val yesterdayDate = TimeTools.getPreviousDate()

            var reelsCountToday = 0
            var reelsCountYesterday = 0
            runBlocking {
                try {
                    val dao = AppDatabase.getInstance(context).reelStatsDao()
                    reelsCountToday = dao.getCount(currentDate) ?: 0
                    reelsCountYesterday = dao.getCount(yesterdayDate) ?: 0
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading reel stats from DB", e)
                }
            }

            val softGreen = Color.parseColor("#4CAF50")
            val softRed = Color.parseColor("#F44336")

            val views = RemoteViews(context.packageName, R.layout.widget_reels_count).apply {
                setTextViewText(R.id.widget_reels_cout, formatNumber(reelsCountToday.toLong()))

                when {
                    reelsCountYesterday == 0 -> {
                        // No baseline from yesterday — can't compute a meaningful percentage
                        setTextViewText(R.id.widget_reels_cout_percentage, "N/A")
                        setTextColor(R.id.widget_reels_cout_percentage, Color.WHITE)
                    }
                    else -> {
                        val changePercentage =
                            ((reelsCountToday - reelsCountYesterday).toDouble() / reelsCountYesterday) * 100
                        when {
                            changePercentage < 0 -> {
                                setTextViewText(
                                    R.id.widget_reels_cout_percentage,
                                    "-%.1f%%".format(-changePercentage)
                                )
                                setTextColor(R.id.widget_reels_cout_percentage, softGreen)
                            }
                            changePercentage > 0 -> {
                                setTextViewText(
                                    R.id.widget_reels_cout_percentage,
                                    "+%.1f%%".format(changePercentage)
                                )
                                setTextColor(R.id.widget_reels_cout_percentage, softRed)
                            }
                            else -> {
                                setTextViewText(R.id.widget_reels_cout_percentage, "0.0%")
                                setTextColor(R.id.widget_reels_cout_percentage, Color.WHITE)
                            }
                        }
                    }
                }

                val refreshIntent = createRefreshIntent(context, widgetId)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.refresh_stats, pendingIntent)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
            Log.d(TAG, "Widget $widgetId updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget $widgetId", e)
        }
    }

    private fun createRefreshIntent(context: Context, widgetId: Int): Intent {
        return Intent(context, ReelsWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
    }

    private fun formatNumber(number: Long): String {
        val suffixes = arrayOf("", "k", "m", "b", "t")
        var value = number.toDouble()
        var index = 0

        while (value >= 1000 && index < suffixes.size - 1) {
            value /= 1000
            index++
        }

        return if (value % 1.0 == 0.0) {
            "${value.toInt()}${suffixes[index]}"
        } else {
            String.format("%.1f%s", value, suffixes[index])
        }
    }
}
