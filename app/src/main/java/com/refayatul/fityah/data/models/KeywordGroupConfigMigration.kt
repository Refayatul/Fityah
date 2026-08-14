package com.refayatul.fityah.data.models

import com.google.gson.Gson

/**
 * Converts keyword groups saved before schedule and usage were part of one config.
 */
@Suppress("DEPRECATION")
fun KeywordBlocker.upgradeLegacyKeywordGroupConfigs(gson: Gson = Gson()): KeywordBlocker {
    if (keywordGroups.all { it.config != null }) return this

    val groupsById = keywordGroups.associateBy(KeywordGroup::id)
    val linkedScheduleIds = keywordGroups
        .filter { it.config == null && it.blockingType == AppBlockingType.Usage }
        .mapNotNull(KeywordGroup::linkedTimeGroupId)
        .toSet()

    val upgradedGroups = keywordGroups.mapNotNull { group ->
        if (group.config == null &&
            group.blockingType == AppBlockingType.Timed &&
            group.id in linkedScheduleIds
        ) {
            return@mapNotNull null
        }

        val upgradedConfig = group.config ?: when (group.blockingType) {
            AppBlockingType.Usage -> upgradeUsageGroup(group, groupsById, gson)
            AppBlockingType.Timed -> ScheduledUsageConfig(
                schedule = (
                    gson.fromJsonOrNull<AppTimeConfig>(group.setting) ?: AppTimeConfig()
                ).inverseForLegacyTimedGroup(),
                usage = AppUsageConfig(uniformLimit = 0)
            )
            AppBlockingType.OnOpen -> ScheduledUsageConfig(
                schedule = AppTimeConfig.allDay(),
                usage = AppUsageConfig(uniformLimit = 0)
            )
        }

        group.copy(
            config = upgradedConfig,
            warningScreenConfig = group.warningScreenConfig.copy(isOnOpenConfig = false),
            blockingType = AppBlockingType.Usage,
            setting = "",
            linkedTimeGroupId = null
        )
    }
    return copy(keywordGroups = upgradedGroups)
}

@Suppress("DEPRECATION")
private fun upgradeUsageGroup(
    group: KeywordGroup,
    groupsById: Map<String, KeywordGroup>,
    gson: Gson
): ScheduledUsageConfig {
    val usage = gson.fromJsonOrNull<AppUsageConfig>(group.setting) ?: AppUsageConfig()
    val linkedSchedule = group.linkedTimeGroupId
        ?.let(groupsById::get)
        ?.takeIf { it.isActive && it.blockingType == AppBlockingType.Timed }
        ?.let { gson.fromJsonOrNull<AppTimeConfig>(it.setting) }
    return ScheduledUsageConfig(
        schedule = linkedSchedule ?: AppTimeConfig.allDay(),
        usage = if (group.linkedTimeGroupId != null && linkedSchedule == null) {
            AppUsageConfig(uniformLimit = 0)
        } else {
            usage
        }
    )
}
