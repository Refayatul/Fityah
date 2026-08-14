package com.refayatul.fityah.utils

import com.refayatul.fityah.data.models.AppGroup
import com.refayatul.fityah.data.models.AppTimeConfig
import com.refayatul.fityah.data.models.TimeInterval

data class AppGroupScheduleConflict(
    val group: AppGroup,
    val sharedPackages: Set<String>
)

fun AppTimeConfig.isAllDaySchedule(): Boolean {
    val ranges = weeklyRanges()
    return (0..6).all { day ->
        mergeRanges(ranges[day].orEmpty()) == listOf(MinuteRange(0, MINUTES_PER_DAY))
    }
}

fun AppTimeConfig.hasOverlappingTimeRanges(): Boolean {
    return weeklyRanges().values.any { ranges ->
        val sorted = ranges.sortedBy(MinuteRange::start)
        sorted.zipWithNext().any { (first, second) -> second.start < first.end }
    }
}

fun AppTimeConfig.overlaps(other: AppTimeConfig): Boolean {
    val ownRanges = weeklyRanges()
    val otherRanges = other.weeklyRanges()
    return (0..6).any { day ->
        ownRanges[day].orEmpty().any { own ->
            otherRanges[day].orEmpty().any { candidate ->
                maxOf(own.start, candidate.start) < minOf(own.end, candidate.end)
            }
        }
    }
}

fun AppGroup.scheduleConflictsWith(groups: List<AppGroup>): List<AppGroupScheduleConflict> {
    if (!isActive) return emptyList()
    val schedule = config?.schedule ?: return emptyList()
    val packages = selectedPackages.map(String::trim).filter(String::isNotEmpty).toSet()

    return groups.mapNotNull { other ->
        if (other.id == id || !other.isActive) return@mapNotNull null
        val otherSchedule = other.config?.schedule ?: return@mapNotNull null
        val sharedPackages = packages.intersect(
            other.selectedPackages.map(String::trim).filter(String::isNotEmpty).toSet()
        )
        if (sharedPackages.isEmpty() || !schedule.overlaps(otherSchedule)) {
            null
        } else {
            AppGroupScheduleConflict(other, sharedPackages)
        }
    }
}

private const val MINUTES_PER_DAY = 24 * 60

private data class MinuteRange(
    val start: Int,
    val end: Int
)

private fun AppTimeConfig.weeklyRanges(): Map<Int, List<MinuteRange>> {
    val result = (0..6).associateWith { mutableListOf<MinuteRange>() }

    fun addInterval(day: Int, interval: TimeInterval) {
        val start = (interval.startHour * 60 + interval.startMinute)
            .coerceIn(0, MINUTES_PER_DAY)
        val end = (interval.endHour * 60 + interval.endMinute)
            .coerceIn(0, MINUTES_PER_DAY)
        if (start == end) return

        if (start < end) {
            result.getValue(day).add(MinuteRange(start, end))
        } else {
            result.getValue(day).add(MinuteRange(start, MINUTES_PER_DAY))
            result.getValue((day + 1) % 7).add(MinuteRange(0, end))
        }
    }

    if (isEveryday) {
        (0..6).forEach { day ->
            everydayIntervals.forEach { addInterval(day, it) }
        }
    } else {
        dailyIntervals.forEach { (day, intervals) ->
            if (day in 0..6) {
                intervals.forEach { addInterval(day, it) }
            }
        }
    }

    return result
}

private fun mergeRanges(ranges: List<MinuteRange>): List<MinuteRange> {
    val sorted = ranges.sortedBy(MinuteRange::start)
    if (sorted.isEmpty()) return emptyList()

    val merged = mutableListOf(sorted.first())
    sorted.drop(1).forEach { range ->
        val last = merged.last()
        if (range.start <= last.end) {
            merged[merged.lastIndex] = MinuteRange(last.start, maxOf(last.end, range.end))
        } else {
            merged += range
        }
    }
    return merged
}
