package com.refayatul.fityah.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseRestTest {

    private val session = SupabaseRest.Session(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "11111111-2222-3333-4444-555555555555",
        email = null,
        expiresAt = Long.MAX_VALUE,
    )

    @Test
    fun firstPullOverlapsTheSavedTimestampAndHasStableOrdering() {
        val url = SupabaseRest("https://example.supabase.co", "anon")
            .buildPullUrl(session, "2026-08-02T12:34:56.123456Z", limit = 5000)

        assertEquals("eq.${session.userId}", url.queryParameter("user_id"))
        assertEquals("gte.2026-08-02T12:34:56.123456Z", url.queryParameter("updated_at"))
        assertEquals("updated_at.asc,id.asc", url.queryParameter("order"))
        assertEquals("1000", url.queryParameter("limit"))
        assertNull(url.queryParameter("or"))
    }

    @Test
    fun nextPageUsesTimestampAndIdAsACompositeCursor() {
        val position = SupabaseRest.PullPosition(
            updatedAt = "2026-08-02T12:34:56.123456Z",
            id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        )
        val url = SupabaseRest("https://example.supabase.co", "anon")
            .buildPullUrl(session, "1970-01-01T00:00:00Z", position, limit = 0)

        assertNull(url.queryParameter("updated_at"))
        assertEquals(
            "(updated_at.gt.${position.updatedAt},and(updated_at.eq.${position.updatedAt},id.gt.${position.id}))",
            url.queryParameter("or"),
        )
        assertEquals("1", url.queryParameter("limit"))
    }
}
