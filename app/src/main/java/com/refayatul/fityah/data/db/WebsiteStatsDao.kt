package com.refayatul.fityah.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WebsiteStatsDao {

    @Query("SELECT * FROM website_stats WHERE date = :date")
    suspend fun getStatsForDate(date: String): List<WebsiteStatsEntity>

    @Query("SELECT * FROM website_stats WHERE date = :date")
    fun observeStatsForDate(date: String): Flow<List<WebsiteStatsEntity>>


    @Query("SELECT * FROM website_stats WHERE date = :date AND packageName = :packageName AND urlIdentifier = :urlIdentifier")
    suspend fun getStat(date: String, packageName: String, urlIdentifier: String): WebsiteStatsEntity?

    @Query("SELECT * FROM website_stats WHERE date = :date AND packageName = :packageName")
    suspend fun getStatsForPackage(date: String, packageName: String): List<WebsiteStatsEntity>

    @Query("SELECT * FROM website_stats WHERE date IN (:dates)")
    suspend fun getStatsForDates(dates: List<String>): List<WebsiteStatsEntity>

    @Upsert
    suspend fun upsert(entity: WebsiteStatsEntity)

    // Creates the row only if it does not exist yet, preserving any totalTime
    // already accumulated. Used to make sure a row is present before adding time.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: WebsiteStatsEntity)

    @Query("SELECT hourlyUsage FROM website_stats WHERE date = :date AND packageName = :packageName AND urlIdentifier = :urlIdentifier")
    suspend fun getHourlyUsage(
        date: String,
        packageName: String,
        urlIdentifier: String
    ): ByteArray?

    @Query("UPDATE website_stats SET totalTime = totalTime + :deltaMs, hourlyUsage = :hourlyUsage, lastVisited = :lastVisited WHERE date = :date AND packageName = :packageName AND urlIdentifier = :urlIdentifier")
    suspend fun addTimeAndHourlyUsage(
        date: String,
        packageName: String,
        urlIdentifier: String,
        deltaMs: Long,
        hourlyUsage: ByteArray,
        lastVisited: Long
    )

    @Transaction
    suspend fun addTime(
        date: String,
        packageName: String,
        urlIdentifier: String,
        hour: Int,
        deltaMs: Long,
        lastVisited: Long
    ) {
        val buckets = WebsiteHourlyUsageCodec.decode(getHourlyUsage(date, packageName, urlIdentifier))
        buckets[hour.coerceIn(0, 23)] =
            (buckets[hour.coerceIn(0, 23)].toLong() + deltaMs)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        addTimeAndHourlyUsage(
            date,
            packageName,
            urlIdentifier,
            deltaMs,
            WebsiteHourlyUsageCodec.encode(buckets),
            lastVisited
        )
    }

    @Query("UPDATE website_stats SET lastVisited = :lastVisited WHERE date = :date AND packageName = :packageName AND urlIdentifier = :urlIdentifier")
    suspend fun touch(date: String, packageName: String, urlIdentifier: String, lastVisited: Long)

    @Query("SELECT DISTINCT date FROM website_stats")
    suspend fun getDistinctDates(): List<String>

    @Query("DELETE FROM website_stats WHERE date IN (:dates)")
    suspend fun deleteByDates(dates: List<String>)
}
