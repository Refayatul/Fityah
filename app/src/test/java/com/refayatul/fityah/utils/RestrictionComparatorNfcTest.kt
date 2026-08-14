package com.refayatul.fityah.utils

import com.refayatul.fityah.data.models.AppBlockerWarningScreenConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-device tests for the NFC clause of [RestrictionComparator.warningConfig].
 *
 * `warningConfig` returns true when the new config is the same or stricter (applied immediately)
 * and false when it weakens the block (parked for the settings-change delay / review). The NFC
 * unlock clause mirrors the QR one: a tag is a new way to unlock, so tags may only be kept or
 * removed and a kept tag may not unlock for longer than before.
 */
class RestrictionComparatorNfcTest {

    private val base = AppBlockerWarningScreenConfig()

    @Test
    fun `identical config with nfc disabled is same-or-stricter`() {
        assertTrue(RestrictionComparator.warningConfig(base, base))
    }

    @Test
    fun `enabling nfc unlock is stricter`() {
        val next = base.copy(isNfcUnlockRequirementEnabled = true, nfcKeys = mapOf("A" to 600_000L))
        assertTrue(RestrictionComparator.warningConfig(base, next))
    }

    @Test
    fun `disabling nfc unlock is a weakening`() {
        val old = base.copy(isNfcUnlockRequirementEnabled = true, nfcKeys = mapOf("A" to 600_000L))
        val next = base.copy(isNfcUnlockRequirementEnabled = false, nfcKeys = mapOf())
        assertFalse(RestrictionComparator.warningConfig(old, next))
    }

    @Test
    fun `adding a new nfc tag is a weakening`() {
        val old = base.copy(isNfcUnlockRequirementEnabled = true, nfcKeys = mapOf("A" to 600_000L))
        val next = old.copy(nfcKeys = mapOf("A" to 600_000L, "B" to 600_000L))
        assertFalse(RestrictionComparator.warningConfig(old, next))
    }

    @Test
    fun `removing an nfc tag is same-or-stricter`() {
        val old = base.copy(
            isNfcUnlockRequirementEnabled = true,
            nfcKeys = mapOf("A" to 600_000L, "B" to 600_000L)
        )
        val next = old.copy(nfcKeys = mapOf("A" to 600_000L))
        assertTrue(RestrictionComparator.warningConfig(old, next))
    }

    @Test
    fun `increasing a kept tag's unlock duration is a weakening`() {
        val old = base.copy(isNfcUnlockRequirementEnabled = true, nfcKeys = mapOf("A" to 600_000L))
        val next = old.copy(nfcKeys = mapOf("A" to 1_200_000L))
        assertFalse(RestrictionComparator.warningConfig(old, next))
    }

    @Test
    fun `decreasing a kept tag's unlock duration is stricter`() {
        val old = base.copy(isNfcUnlockRequirementEnabled = true, nfcKeys = mapOf("A" to 1_200_000L))
        val next = old.copy(nfcKeys = mapOf("A" to 600_000L))
        assertTrue(RestrictionComparator.warningConfig(old, next))
    }

    @Test
    fun `switching a kept tag to dynamic timing is a weakening`() {
        val old = base.copy(isNfcUnlockRequirementEnabled = true, nfcKeys = mapOf("A" to 600_000L))
        val next = old.copy(nfcKeys = mapOf("A" to -1L))
        assertFalse(RestrictionComparator.warningConfig(old, next))
    }

    @Test
    fun `dynamic timing kept as dynamic is same-or-stricter`() {
        val old = base.copy(isNfcUnlockRequirementEnabled = true, nfcKeys = mapOf("A" to -1L))
        assertTrue(RestrictionComparator.warningConfig(old, old))
    }

    @Test
    fun `narrowing dynamic to a fixed duration is a weakening`() {
        val old = base.copy(isNfcUnlockRequirementEnabled = true, nfcKeys = mapOf("A" to -1L))
        val next = old.copy(nfcKeys = mapOf("A" to 600_000L))
        assertFalse(RestrictionComparator.warningConfig(old, next))
    }
}
