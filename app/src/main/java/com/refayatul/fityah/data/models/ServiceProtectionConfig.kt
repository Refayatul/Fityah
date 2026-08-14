package com.refayatul.fityah.data.models

/**
 * Settings for the layers that keep the accessibility service alive and bring it back when the
 * system kills it. None of these make the app truly un killable, they only make it very hard to
 * kill and quick to recover.
 */
data class ServiceProtectionConfig(
    /** Master switch. When off, the watchdog and self healing do nothing. */
    val isEnabled: Boolean = false,

    /**
     * When Shizuku is available, let the watchdog silently turn the accessibility service back on
     * if the system or an OEM battery manager switched it off.
     */
    val selfHealWithShizuku: Boolean = true,

    /** Last real time millis the service reported it was alive. */
    val appBlockerLastAliveMs: Long = 0L
)
