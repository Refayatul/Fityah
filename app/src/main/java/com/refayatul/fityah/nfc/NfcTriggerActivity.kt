package com.refayatul.fityah.nfc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.refayatul.fityah.R

/**
 * Invisible activity launched when an NFC tag carrying a `fityah://focus/...` URI is scanned.
 *
 * It parses the tag, applies the focus change via [NfcFocusHandler] and shows a toast immediately.
 */
class NfcTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = extractUri(intent)
        if (!NfcFocusHandler.matches(uri)) {
            toast(getString(R.string.nfc_tag_unrecognized))
            finish()
            return
        }

        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val result = NfcFocusHandler.handle(appContext, uri)
            withContext(Dispatchers.Main) {
                if (result is NfcFocusHandler.Result.Started || result is NfcFocusHandler.Result.Stopped) {
                    NfcUnlockUtils.feedback(this@NfcTriggerActivity)
                }
                messageFor(result)?.let { toast(it) }
                finish()
            }
        }
    }

    /** Pulls the URI from either a plain view intent or an NDEF-discovered NFC intent. */
    private fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        intent.data?.let { return it }

        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        for (raw in rawMessages) {
            val message = raw as? NdefMessage ?: continue
            for (record in message.records) {
                uriFromRecord(record)?.let { return it }
            }
        }
        return null
    }

    private fun uriFromRecord(record: NdefRecord): Uri? = try {
        record.toUri() ?: run {
            if (record.tnf == NdefRecord.TNF_ABSOLUTE_URI) {
                Uri.parse(String(record.type, Charsets.UTF_8))
            } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun messageFor(result: NfcFocusHandler.Result): String? = when (result) {
        is NfcFocusHandler.Result.Started ->
            getString(R.string.nfc_focus_started, result.groupName, result.minutes)
        NfcFocusHandler.Result.Stopped -> getString(R.string.nfc_focus_stopped)
        NfcFocusHandler.Result.NoGroup -> getString(R.string.nfc_focus_no_group)
        NfcFocusHandler.Result.NotExitable -> getString(R.string.nfc_focus_not_exitable)
        NfcFocusHandler.Result.Invalid -> getString(R.string.nfc_tag_unrecognized)
        NfcFocusHandler.Result.Debounced -> null
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
