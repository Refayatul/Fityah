package com.refayatul.fityah.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WebsiteHourlyUsageCodecTest {
    @Test
    fun encodingTrimsUnusedTrailingBuckets() {
        val buckets = IntArray(24).apply {
            this[0] = 1_000
            this[3] = 42_000
        }

        val encoded = WebsiteHourlyUsageCodec.encode(buckets)

        assertEquals(16, encoded.size)
        assertArrayEquals(buckets, WebsiteHourlyUsageCodec.decode(encoded))
    }

    @Test
    fun emptyUsageHasNoPayload() {
        assertEquals(0, WebsiteHourlyUsageCodec.encode(IntArray(24)).size)
    }
}
