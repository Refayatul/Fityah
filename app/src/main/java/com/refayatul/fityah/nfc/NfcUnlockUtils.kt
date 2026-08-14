package com.refayatul.fityah.nfc

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/**
 * Shared NFC helpers for the unlock-method feature.
 *
 * A tag matches by the text payload Fityah wrote onto it ("write new tag") or by its hardware UID
 * ("register existing tag"). [keysFromTag] returns both candidates, so either registration style
 * unlocks if it hits the stored key map.
 */
object NfcUnlockUtils {

    /** Reader-mode flags covering the common tag technologies. */
    const val READER_FLAGS =
        NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    fun isNfcReady(activity: Activity): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        return adapter.isEnabled
    }

    fun enableReader(activity: Activity, onTag: (Tag) -> Unit): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        val mainHandler = Handler(Looper.getMainLooper())
        return try {
            adapter.enableReaderMode(activity, { tag ->
                mainHandler.post { onTag(tag) }
            }, READER_FLAGS, null)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun disableReader(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
        } catch (e: IllegalStateException) {
            // Activity already torn down (e.g. rapid destroy); nothing to disable.
        }
    }

    fun feedback(context: android.content.Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (e: Exception) {
            // Feedback is best-effort.
        }
    }

    fun uidHex(tag: Tag): String =
        tag.id?.joinToString("") { "%02X".format(it) } ?: ""

    /**
     * Every string a scanned [tag] could be registered under: its UID plus any text/URI NDEF
     * payloads already on it.
     */
    fun keysFromTag(tag: Tag): List<String> {
        val keys = mutableListOf<String>()
        uidHex(tag).takeIf { it.isNotEmpty() }?.let { keys.add(it) }

        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                val message = ndef.ndefMessage ?: ndef.cachedNdefMessage
                message?.records?.forEach { record ->
                    payloadOf(record)?.let { keys.add(it) }
                }
                ndef.close()
            }
        } catch (e: Exception) {
            // Unreadable / non-NDEF tag: UID alone still works.
        }
        return keys
    }

    fun writeText(tag: Tag, text: String): Boolean =
        writeMessage(tag, NdefMessage(arrayOf(NdefRecord.createTextRecord(null, text))))

    fun writeUri(tag: Tag, uri: String): Boolean =
        writeMessage(tag, NdefMessage(arrayOf(NdefRecord.createUri(uri))))

    fun readUri(tag: Tag): android.net.Uri? = try {
        val ndef = Ndef.get(tag)
        var result: android.net.Uri? = null
        if (ndef != null) {
            ndef.connect()
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage
            result = message?.records?.firstNotNullOfOrNull { it.toUri() }
            ndef.close()
        }
        result
    } catch (e: Exception) {
        null
    }

    private fun writeMessage(tag: Tag, message: NdefMessage): Boolean {
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable || ndef.maxSize < message.toByteArray().size) {
                    ndef.close()
                    return false
                }
                ndef.writeNdefMessage(message)
                ndef.close()
                true
            } else {
                val formatable = NdefFormatable.get(tag) ?: return false
                formatable.connect()
                formatable.format(message)
                formatable.close()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun payloadOf(record: NdefRecord): String? = try {
        when {
            record.toUri() != null -> record.toUri().toString()
            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_TEXT) -> decodeText(record)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** Decodes an RTD_TEXT record payload into its text, stripping the language header. */
    private fun decodeText(record: NdefRecord): String? {
        val payload = record.payload
        if (payload.isEmpty()) return null
        val langLength = payload[0].toInt() and 0x3F
        val isUtf16 = (payload[0].toInt() and 0x80) != 0
        val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
        return String(payload, 1 + langLength, payload.size - 1 - langLength, charset)
    }
}
