package com.refayatul.fityah.utils

import android.view.View
import android.widget.RadioGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.refayatul.fityah.R

object TemporaryDisableDialog {
    const val UNTIL_MANUALLY_ENABLED = -1L

    fun show(fragment: Fragment, title: String, onConfirm: (Long) -> Unit) {
        val content = fragment.layoutInflater.inflate(R.layout.dialog_temporary_disable_group, null)
        val choices = content.findViewById<RadioGroup>(R.id.group_temporary_duration)
        val customLayout = content.findViewById<TextInputLayout>(R.id.layout_custom_minutes)
        val customInput = content.findViewById<TextInputEditText>(R.id.input_custom_minutes)

        choices.setOnCheckedChangeListener { _, checkedId ->
            customLayout.visibility =
                if (checkedId == R.id.duration_custom) View.VISIBLE else View.GONE
            if (checkedId == R.id.duration_custom) customInput.requestFocus()
        }

        val dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.temporary_disable_action, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = when (choices.checkedRadioButtonId) {
                    R.id.duration_5m -> 5L
                    R.id.duration_15m -> 15L
                    R.id.duration_1h -> 60L
                    R.id.duration_until_enabled -> UNTIL_MANUALLY_ENABLED
                    R.id.duration_custom -> customInput.text?.toString()?.toLongOrNull()
                    else -> null
                }
                if (minutes == null ||
                    (minutes != UNTIL_MANUALLY_ENABLED && minutes !in 1L..43_200L)
                ) {
                    customLayout.error = fragment.getString(R.string.temporary_disable_invalid)
                } else {
                    onConfirm(minutes)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
