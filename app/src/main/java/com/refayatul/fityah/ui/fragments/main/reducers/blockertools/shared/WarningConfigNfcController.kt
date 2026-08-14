package com.refayatul.fityah.ui.fragments.main.reducers.blockertools.shared

import android.nfc.Tag
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.refayatul.fityah.R
import com.refayatul.fityah.databinding.FragmentWarningConfigBinding
import com.refayatul.fityah.nfc.NfcUnlockUtils
import java.util.UUID

internal class WarningConfigNfcController(
    private val fragment: Fragment,
    private val binding: FragmentWarningConfigBinding,
    private val timingDialog: WarningUnlockTimingDialog,
    private val keyListRenderer: WarningUnlockKeyListRenderer
) {
    private val currentKeys = mutableMapOf<String, Long>()
    private var tapDialog: AlertDialog? = null

    fun bind(keys: Map<String, Long>) {
        currentKeys.clear()
        currentKeys.putAll(keys)
        refreshKeyList()
    }

    fun setupListeners() {
        binding.btnWriteNfc.setOnClickListener {
            timingDialog.show { duration ->
                startTap(write = true, duration = duration)
            }
        }
        binding.btnRegisterExistingNfc.setOnClickListener {
            timingDialog.show { duration ->
                startTap(write = false, duration = duration)
            }
        }
    }

    fun keys(): Map<String, Long> = currentKeys

    fun refreshKeyList() {
        keyListRenderer.render(
            binding.nfcListContainer,
            currentKeys,
            "NFC tag",
            ::refreshKeyList
        )
    }

    fun stopTap() {
        fragment.activity?.let { NfcUnlockUtils.disableReader(it) }
        tapDialog?.dismiss()
        tapDialog = null
    }

    private fun startTap(write: Boolean, duration: Long) {
        val activity = fragment.activity ?: return
        if (!NfcUnlockUtils.isNfcReady(activity)) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.nfc_unavailable,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val enabled = NfcUnlockUtils.enableReader(activity) { tag ->
            NfcUnlockUtils.feedback(fragment.requireContext())
            if (write) {
                writeTag(tag, duration)
            } else {
                registerTag(tag, duration)
            }
            stopTap()
        }
        if (!enabled) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.nfc_unavailable,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        tapDialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.nfc_tap_title)
            .setMessage(R.string.nfc_tap_message)
            .setNegativeButton(R.string.cancel) { _, _ -> stopTap() }
            .setOnDismissListener { NfcUnlockUtils.disableReader(activity) }
            .show()
    }

    private fun writeTag(tag: Tag, duration: Long) {
        val key = UUID.randomUUID().toString()
        if (NfcUnlockUtils.writeText(tag, key)) {
            currentKeys[key] = duration
            refreshKeyList()
            Toast.makeText(
                fragment.requireContext(),
                R.string.nfc_write_success,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                fragment.requireContext(),
                R.string.nfc_write_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun registerTag(tag: Tag, duration: Long) {
        val key = NfcUnlockUtils.keysFromTag(tag).firstOrNull()
        when {
            key == null -> Toast.makeText(
                fragment.requireContext(),
                R.string.nfc_write_failed,
                Toast.LENGTH_LONG
            ).show()
            currentKeys.containsKey(key) -> Toast.makeText(
                fragment.requireContext(),
                R.string.nfc_already_registered,
                Toast.LENGTH_SHORT
            ).show()
            else -> {
                currentKeys[key] = duration
                refreshKeyList()
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.nfc_tag_saved,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
