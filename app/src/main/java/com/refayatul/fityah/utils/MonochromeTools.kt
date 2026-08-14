package com.refayatul.fityah.utils

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.refayatul.fityah.BuildConfig

class GrayscaleControl {

    companion object {
        private const val DALTONIZER = "accessibility_display_daltonizer"
        private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        // 0 = full monochrome
        private const val MONOCHROME_MODE = 0

        /**
         * Grants WRITE_SECURE_SETTINGS to the app through Shizuku. Needed only once;
         * after that grayscale toggles work without Shizuku, even across reboots.
         */
        fun grantWriteSecureSettings(context: Context, onDone: (success: Boolean) -> Unit) {
            ShizukuRunner.executeCommand(
                "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
                object : ShizukuRunner.CommandResultListener {
                    override fun onCommandResult(output: String, done: Boolean) {
                        if (done) onDone(true)
                    }

                    override fun onCommandError(error: String) {
                        Log.e("GrayscaleControl", "pm grant failed: $error")
                        onDone(false)
                    }
                }
            )
        }
    }

    private val commandListener = object : ShizukuRunner.CommandResultListener {
        override fun onCommandResult(output: String, done: Boolean) {
            Log.d("monochrome output: ", output)
        }

        override fun onCommandError(error: String) {
            Log.d("monochrome error: ", error)
        }
    }

    /**
     * Enable grayscale mode
     */
    fun enableGrayscale(context: Context) {
        if (writeDaltonizer(context, enable = true)) return
        ShizukuRunner.executeCommand(
            withSelfGrant(
                context,
                "settings put secure $DALTONIZER $MONOCHROME_MODE && " +
                        "settings put secure $DALTONIZER_ENABLED 1"
            ),
            commandListener
        )
    }

    /**
     * Disable grayscale mode
     */
    fun disableGrayscale(context: Context) {
        if (writeDaltonizer(context, enable = false)) return
        ShizukuRunner.executeCommand(
            withSelfGrant(context, "settings put secure $DALTONIZER_ENABLED 0"),
            commandListener
        )
    }

    /**
     * Toggle grayscale mode based on current state
     */
    fun toggleGrayscale(context: Context) {
        if (isGrayscaleEnabled(context)) {
            disableGrayscale(context)
        } else {
            enableGrayscale(context)
        }
    }

    /**
     * Get current grayscale state (reading secure settings needs no permission)
     */
    fun isGrayscaleEnabled(context: Context): Boolean {
        return Settings.Secure.getInt(context.contentResolver, DALTONIZER_ENABLED, 0) == 1
    }

    /**
     * Writes the daltonizer settings directly when the build holds
     * WRITE_SECURE_SETTINGS (full and fdroid, granted once via Shizuku).
     * Returns false when the Shizuku shell fallback should run instead.
     */
    private fun writeDaltonizer(context: Context, enable: Boolean): Boolean {
        if (!BuildConfig.SUPPORTS_WRITE_SECURE_SETTINGS) return false
        if (!PermissionUtils.hasWriteSecureSettings(context)) return false
        return try {
            val resolver = context.contentResolver
            if (enable) {
                Settings.Secure.putInt(resolver, DALTONIZER, MONOCHROME_MODE)
                Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, 1)
            } else {
                Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, 0)
            }
            true
        } catch (e: Exception) {
            Log.e("GrayscaleControl", "Direct secure settings write failed", e)
            false
        }
    }

    /**
     * While we still have to shell out through Shizuku, grant ourselves
     * WRITE_SECURE_SETTINGS in the same command so later toggles no longer need it.
     */
    private fun withSelfGrant(context: Context, command: String): String {
        if (!BuildConfig.SUPPORTS_WRITE_SECURE_SETTINGS) return command
        return "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS; $command"
    }
}
