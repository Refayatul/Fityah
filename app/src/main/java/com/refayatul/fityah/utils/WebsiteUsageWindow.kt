package com.refayatul.fityah.utils

import com.refayatul.fityah.data.db.WebsiteHourlyUsageCodec
import com.refayatul.fityah.data.db.WebsiteStatsEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

object WebsiteUsageWindow {
    fun sum(rows: Collection<WebsiteStatsEntity>, startMs: Long, endMs: Long): Long {
        if (endMs <= startMs) return 0L
        val zone = ZoneId.systemDefault()
        var total = 0.0
        rows.forEach { row ->
            val date = runCatching {
                LocalDate.parse(row.date, TimeTools.dayKeyFormatter())
            }.getOrNull() ?: return@forEach
            val buckets = WebsiteHourlyUsageCodec.decode(row.hourlyUsage)
            if (row.hourlyUsage.isEmpty()) {
                // Pre-migration rows have no buckets. Attribute them only when their last visit
                // falls in this window instead of charging every future linked interval.
                if (row.lastVisited in startMs until endMs) total += row.totalTime
                return@forEach
            }
            for (hour in 0 until 24) {
                if (buckets[hour] == 0) continue
                val bucketStart = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                val bucketEnd = date.atTime(hour, 0).plusHours(1)
                    .atZone(zone).toInstant().toEpochMilli()
                val overlap = (minOf(endMs, bucketEnd) - maxOf(startMs, bucketStart))
                    .coerceAtLeast(0L)
                if (overlap > 0L) {
                    total += buckets[hour] * (overlap.toDouble() / (bucketEnd - bucketStart))
                }
            }
        }
        return total.roundToLong()
    }
}
