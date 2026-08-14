package com.refayatul.fityah.data.db

/**
 * Stores 24 hourly millisecond totals as trimmed 32-bit values.
 * Rows used only early in the day therefore consume less than the 96-byte maximum.
 */
object WebsiteHourlyUsageCodec {
    private const val HOURS_PER_DAY = 24
    private const val BYTES_PER_BUCKET = 4

    fun decode(value: ByteArray?): IntArray {
        val result = IntArray(HOURS_PER_DAY)
        if (value == null) return result
        val bucketCount = minOf(HOURS_PER_DAY, value.size / BYTES_PER_BUCKET)
        for (hour in 0 until bucketCount) {
            val offset = hour * BYTES_PER_BUCKET
            result[hour] =
                ((value[offset].toInt() and 0xff) shl 24) or
                    ((value[offset + 1].toInt() and 0xff) shl 16) or
                    ((value[offset + 2].toInt() and 0xff) shl 8) or
                    (value[offset + 3].toInt() and 0xff)
        }
        return result
    }

    fun encode(buckets: IntArray): ByteArray {
        var bucketCount = minOf(HOURS_PER_DAY, buckets.size)
        while (bucketCount > 0 && buckets[bucketCount - 1] == 0) bucketCount--
        val result = ByteArray(bucketCount * BYTES_PER_BUCKET)
        for (hour in 0 until bucketCount) {
            val value = buckets[hour].coerceAtLeast(0)
            val offset = hour * BYTES_PER_BUCKET
            result[offset] = (value ushr 24).toByte()
            result[offset + 1] = (value ushr 16).toByte()
            result[offset + 2] = (value ushr 8).toByte()
            result[offset + 3] = value.toByte()
        }
        return result
    }
}
