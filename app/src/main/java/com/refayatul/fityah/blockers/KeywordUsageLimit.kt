package com.refayatul.fityah.blockers

internal fun isKeywordUsageLimitExceeded(
    limitMinutes: Long,
    usageMillis: () -> Long
): Boolean {
    if (limitMinutes <= 0L) return true
    return usageMillis() >= limitMinutes * 60_000L
}
