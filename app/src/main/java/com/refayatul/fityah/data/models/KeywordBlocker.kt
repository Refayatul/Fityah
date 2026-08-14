package com.refayatul.fityah.data.models

data class KeywordBlocker(
    val isActive: Boolean = false,
    val keywordGroups: List<KeywordGroup> = emptyList(),
    val blockAllExceptSupported: Boolean = false
)

data class KeywordGroup(
    val id: String = "",
    val name: String = "name",
    val selectedKeywords: List<String> = listOf(),
    val config: ScheduledUsageConfig? = null,
    val isActive: Boolean = false,
    val temporarilyDisabledUntilMs: Long = 0L,
    val warningScreenConfig: AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig(),
    // Kept only so settings written by older versions can be migrated.
    @Deprecated("Use config")
    val blockingType: AppBlockingType = AppBlockingType.Usage,
    @Deprecated("Use config")
    val setting: String = "",
    @Deprecated("Use config")
    val linkedTimeGroupId: String? = null,
)
