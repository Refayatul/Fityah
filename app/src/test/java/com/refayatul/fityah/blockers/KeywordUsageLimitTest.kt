package com.refayatul.fityah.blockers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordUsageLimitTest {

    @Test
    fun zeroLimitBlocksWithoutReadingUsage() {
        var usageRead = false

        val exceeded = isKeywordUsageLimitExceeded(0L) {
            usageRead = true
            0L
        }

        assertTrue(exceeded)
        assertFalse(usageRead)
    }

    @Test
    fun positiveLimitUsesAccumulatedUsage() {
        assertFalse(isKeywordUsageLimitExceeded(5L) { 299_999L })
        assertTrue(isKeywordUsageLimitExceeded(5L) { 300_000L })
    }
}
