package com.refayatul.fityah.utils

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.refayatul.fityah.R

object DndHelper {
    private const val PREFS_NAME = "DndPrefs"
    private const val KEY_WAS_TURNED_ON_BY_US = "was_turned_on_by_us"

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasDndAccess(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    /**
     * True when DND access is already granted. Otherwise explains why the permission is needed
     * and, if the user agrees, opens the system screen to grant it. Needs an activity context.
     */
    fun ensureDndAccess(context: Context): Boolean {
        if (hasDndAccess(context)) return true

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dnd_access_dialog_title)
            .setMessage(R.string.dnd_access_dialog_message)
            .setPositiveButton(R.string.proceed) { _, _ ->
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return false
    }

    @Synchronized
    fun applyDndState(context: Context, shouldBeOn: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        
        val currentFilter = nm.currentInterruptionFilter
        val prefs = getPrefs(context)
        val wasTurnedOnByUs = prefs.getBoolean(KEY_WAS_TURNED_ON_BY_US, false)

        if (shouldBeOn) {
            if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                prefs.edit().putBoolean(KEY_WAS_TURNED_ON_BY_US, true).apply()
            }
        } else {
            // Only turn off if we were the ones who turned it on
            if (wasTurnedOnByUs && currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                prefs.edit().putBoolean(KEY_WAS_TURNED_ON_BY_US, false).apply()
            }
        }
    }
}
