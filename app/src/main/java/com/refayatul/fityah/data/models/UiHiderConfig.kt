package com.refayatul.fityah.data.models

/**
 * A single user-authored UIHider script, bound to one app package. [source] is the raw script
 * text; it is compiled to an AST at runtime by the UiHider blocker.
 */
data class UiHiderScript(
    val id: String = "",
    val packageName: String = "",
    val label: String = "",
    val source: String = "",
    val isEnabled: Boolean = true
)

/**
 * Top-level configuration for the UIHider feature, stored in DataStore as part of Settings.
 * Scripts only run while [isActive] is true and the script's [UiHiderScript.isEnabled] is set.
 *
 * [scripts] holds only user-created scripts. The preset scripts ship in code
 * (DEFAULT_UIHIDER_SCRIPTS) and are never persisted; only the ids the user turned on are
 * stored in [enabledPresetIds], so preset source always comes from the current app version.
 */
data class UiHiderConfig(
    val isActive: Boolean = false,
    val scripts: List<UiHiderScript> = emptyList(),
    val enabledPresetIds: List<String> = emptyList()
)
