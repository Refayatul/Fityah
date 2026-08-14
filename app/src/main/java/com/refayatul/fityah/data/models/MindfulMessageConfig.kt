package com.refayatul.fityah.data.models

data class MindfulMessageConfig(
    val isActive: Boolean = false,
    val selectedApps: List<String> = emptyList(),
    val messages: String =
        "Is this really important? \n App Usage: {app_usage_today} \n Screen Time: {screentime_today} \n Session: {live_session_duration}",
    val textSize: Float = 14f,
    val textOpacity: Int = 100,
    val bgColor: Int = 0x000000,
    val bgOpacity: Int = 37,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.05f
)
