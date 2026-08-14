package com.refayatul.fityah.data.models

import com.google.gson.Gson

/**
 * Converts groups saved before schedule and usage were part of one config.
 */
@Suppress("DEPRECATION")
fun List<AppGroup>.upgradeLegacyAppGroupConfigs(gson: Gson = Gson()): List<AppGroup> {
    if (all { it.config != null }) return this

    val groupsById = associateBy(AppGroup::id)
    val linkedScheduleIds = filter { it.config == null && it.blockingType == AppBlockingType.Usage }
        .mapNotNull(AppGroup::linkedTimeGroupId)
        .toSet()

    return mapNotNull { group ->
        if (group.config == null &&
            group.blockingType == AppBlockingType.Timed &&
            group.id in linkedScheduleIds
        ) {
            return@mapNotNull null
        }

        val upgradedConfig = group.config ?: when (group.blockingType) {
            AppBlockingType.Usage -> upgradeUsageGroup(group, groupsById, gson)
            AppBlockingType.Timed -> AppGroupConfig(
                schedule = (
                    gson.fromJsonOrNull<AppTimeConfig>(group.setting) ?: AppTimeConfig()
                ).inverseForLegacyTimedGroup(),
                usage = AppUsageConfig(uniformLimit = 0)
            )
            AppBlockingType.OnOpen -> AppGroupConfig(
                schedule = AppTimeConfig.allDay(),
                usage = AppUsageConfig(uniformLimit = 0)
            )
        }

        group.copy(
            config = upgradedConfig,
            warningScreenConfig = group.warningScreenConfig.copy(
                isOnOpenConfig = group.blockingType == AppBlockingType.OnOpen
            ),
            blockingType = AppBlockingType.Usage,
            setting = "",
            linkedTimeGroupId = null
        )
    }
}

@Suppress("DEPRECATION")
private fun upgradeUsageGroup(
    group: AppGroup,
    groupsById: Map<String, AppGroup>,
    gson: Gson
): AppGroupConfig {
    val usage = gson.fromJsonOrNull<AppUsageConfig>(group.setting) ?: AppUsageConfig()
    val linkedSchedule = group.linkedTimeGroupId
        ?.let(groupsById::get)
        ?.takeIf { it.isActive && it.blockingType == AppBlockingType.Timed }
        ?.let { gson.fromJsonOrNull<AppTimeConfig>(it.setting) }
    return AppGroupConfig(
        schedule = linkedSchedule ?: AppTimeConfig.allDay(),
        usage = if (group.linkedTimeGroupId != null && linkedSchedule == null) {
            AppUsageConfig(uniformLimit = 0)
        } else {
            usage
        }
    )
}

internal inline fun <reified T> Gson.fromJsonOrNull(json: String): T? =
    runCatching { fromJson<T>(json, T::class.java) }.getOrNull()

internal fun AppTimeConfig.inverseForLegacyTimedGroup(): AppTimeConfig {
    val allowedMinutes = Array(7) { BooleanArray(24 * 60) }

    fun intervalsFor(day: Int): List<TimeInterval> =
        if (isEveryday) everydayIntervals else dailyIntervals[day].orEmpty()

    for (day in 0..6) {
        intervalsFor(day).forEach { interval ->
            val start = (interval.startHour * 60 + interval.startMinute).coerceIn(0, 1440)
            val end = (interval.endHour * 60 + interval.endMinute).coerceIn(0, 1440)
            if (start <= end) {
                for (minute in start until end) allowedMinutes[day][minute] = true
            } else {
                for (minute in start until 1440) allowedMinutes[day][minute] = true
                val nextDay = (day + 1) % 7
                for (minute in 0 until end) allowedMinutes[nextDay][minute] = true
            }
        }
    }

    val blockedIntervals = allowedMinutes.map(::inverseIntervals)
    val sameEveryDay = blockedIntervals.drop(1).all { it == blockedIntervals.first() }
    return if (sameEveryDay) {
        AppTimeConfig(
            isEveryday = true,
            everydayIntervals = blockedIntervals.first().toMutableList()
        )
    } else {
        AppTimeConfig(
            isEveryday = false,
            everydayIntervals = mutableListOf(),
            dailyIntervals = blockedIntervals
                .mapIndexed { day, intervals -> day to intervals.toMutableList() }
                .filter { it.second.isNotEmpty() }
                .toMap(mutableMapOf())
        )
    }
}

private fun inverseIntervals(allowedMinutes: BooleanArray): List<TimeInterval> {
    val result = mutableListOf<TimeInterval>()
    var start: Int? = null
    for (minute in 0..1440) {
        val blocked = minute < 1440 && !allowedMinutes[minute]
        if (blocked && start == null) start = minute
        if (!blocked && start != null) {
            result += TimeInterval(
                startHour = start / 60,
                startMinute = start % 60,
                endHour = minute / 60,
                endMinute = minute % 60
            )
            start = null
        }
    }
    return result
}
