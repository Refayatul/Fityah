package com.refayatul.fityah.utils

import com.refayatul.fityah.data.models.AppTimeConfig
import com.refayatul.fityah.data.models.TimeInterval
import java.util.Calendar

data class ActiveTimeGroupWindow(
    val startMs: Long,
    val endMs: Long
)

/**
 * Resolves the currently active allowed interval. Overnight intervals are supported even for
 * imported/legacy settings that have not been split at midnight.
 */
fun AppTimeConfig.activeWindow(nowMs: Long = System.currentTimeMillis()): ActiveTimeGroupWindow? {
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val today = now.get(Calendar.DAY_OF_WEEK) - 1
    val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    fun intervalsFor(day: Int): List<TimeInterval> =
        if (isEveryday) everydayIntervals else dailyIntervals[day] ?: emptyList()

    val previousDay = (today + 6) % 7
    intervalsFor(today).forEach { interval ->
        val start = interval.startHour * 60 + interval.startMinute
        val end = interval.endHour * 60 + interval.endMinute
        val active = if (start <= end) currentMinute in start until end
        else currentMinute >= start
        if (active) {
            val startCalendar = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, interval.startHour.coerceAtMost(23))
                set(Calendar.MINUTE, interval.startMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val endCalendar = (startCalendar.clone() as Calendar).apply {
                if (start > end) add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, interval.endHour.coerceAtMost(23))
                set(Calendar.MINUTE, interval.endMinute)
                if (interval.endHour == 24) {
                    set(Calendar.HOUR_OF_DAY, 0)
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            return ActiveTimeGroupWindow(startCalendar.timeInMillis, endCalendar.timeInMillis)
        }
    }
    intervalsFor(previousDay).forEach { interval ->
        val start = interval.startHour * 60 + interval.startMinute
        val end = interval.endHour * 60 + interval.endMinute
        if (start > end && currentMinute < end) {
            val startCalendar = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, -1)
                set(Calendar.HOUR_OF_DAY, interval.startHour.coerceAtMost(23))
                set(Calendar.MINUTE, interval.startMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val endCalendar = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, interval.endHour)
                set(Calendar.MINUTE, interval.endMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return ActiveTimeGroupWindow(startCalendar.timeInMillis, endCalendar.timeInMillis)
        }
    }
    return null
}

/**
 * Returns the next moment when this schedule becomes active or inactive.
 */
fun AppTimeConfig.nextChangeAfter(nowMs: Long = System.currentTimeMillis()): Long? {
    activeWindow(nowMs)?.let { return it.endMs }

    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val candidates = mutableListOf<Long>()
    for (dayOffset in 0..7) {
        val day = (now.get(Calendar.DAY_OF_WEEK) - 1 + dayOffset) % 7
        val intervals = if (isEveryday) everydayIntervals else dailyIntervals[day].orEmpty()
        intervals.forEach { interval ->
            val start = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, dayOffset)
                set(Calendar.HOUR_OF_DAY, interval.startHour.coerceAtMost(23))
                set(Calendar.MINUTE, interval.startMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (start > nowMs) candidates += start
        }
    }
    return candidates.minOrNull()
}
