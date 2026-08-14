package com.refayatul.fityah.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Off-device tests for [NfcFocusHandler.parse], the pure URI decoder behind NFC focus tags.
 * No Android dependencies: parsing runs on java.net.URI so it can be verified on the JVM.
 */
class NfcFocusHandlerTest {

    @Test
    fun `action from first path segment`() {
        val r = NfcFocusHandler.parse("fityah://focus/start")
        assertEquals("start", r?.action)
        assertNull(r?.groupId)
        assertNull(r?.minutes)
    }

    @Test
    fun `full url with group and mins`() {
        val r = NfcFocusHandler.parse("fityah://focus/start?group=abc-123&mins=30")
        assertEquals("start", r?.action)
        assertEquals("abc-123", r?.groupId)
        assertEquals(30, r?.minutes)
    }

    @Test
    fun `stop action`() {
        assertEquals("stop", NfcFocusHandler.parse("fityah://focus/stop")?.action)
    }

    @Test
    fun `missing action defaults to toggle`() {
        assertEquals("toggle", NfcFocusHandler.parse("fityah://focus")?.action)
        assertEquals("toggle", NfcFocusHandler.parse("fityah://focus/")?.action)
    }

    @Test
    fun `action query param used when no path segment`() {
        val r = NfcFocusHandler.parse("fityah://focus?action=stop")
        assertEquals("stop", r?.action)
    }

    @Test
    fun `mins that is not a number is null`() {
        val r = NfcFocusHandler.parse("fityah://focus/start?mins=abc")
        assertEquals("start", r?.action)
        assertNull(r?.minutes)
    }

    @Test
    fun `percent-encoded group is decoded`() {
        val r = NfcFocusHandler.parse("fityah://focus/start?group=a%20b&mins=5")
        assertEquals("a b", r?.groupId)
        assertEquals(5, r?.minutes)
    }

    @Test
    fun `wrong scheme returns null`() {
        assertNull(NfcFocusHandler.parse("http://focus/start"))
    }

    @Test
    fun `wrong host returns null`() {
        assertNull(NfcFocusHandler.parse("fityah://unlock/start"))
    }

    @Test
    fun `null and garbage input return null`() {
        assertNull(NfcFocusHandler.parse(null))
        assertNull(NfcFocusHandler.parse("not a uri"))
        assertNull(NfcFocusHandler.parse(""))
    }
}
