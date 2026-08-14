package com.refayatul.fityah.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.DragEvent
import com.google.android.material.textfield.TextInputEditText

/**
 * Text input for challenges that must be completed by typing.
 *
 * Paste is blocked from both the regular and selection action menus, and text
 * cannot be dragged into the field.
 */
class NoPasteTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

    override fun onTextContextMenuItem(id: Int): Boolean {
        return when (id) {
            android.R.id.paste,
            android.R.id.pasteAsPlainText -> false
            else -> super.onTextContextMenuItem(id)
        }
    }

    override fun onDragEvent(event: DragEvent): Boolean {
        return if (event.action == DragEvent.ACTION_DROP) false else super.onDragEvent(event)
    }

    init {
        // Removes paste from the floating insertion toolbar on supported Android versions.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            customInsertionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(
                    mode: android.view.ActionMode,
                    menu: android.view.Menu
                ) = false

                override fun onPrepareActionMode(
                    mode: android.view.ActionMode,
                    menu: android.view.Menu
                ) = false

                override fun onActionItemClicked(
                    mode: android.view.ActionMode,
                    item: android.view.MenuItem
                ) = false

                override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
            }
        }

        // Prevents autofill services from filling the typing challenge.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }
}
