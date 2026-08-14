package com.refayatul.fityah.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReelStatsDao {

    @Query("SELECT count FROM reel_stats WHERE date = :date")
    suspend fun getCount(date: String): Int?

    @Query("SELECT count FROM reel_stats WHERE date = :date")
    fun getCountFlow(date: String): Flow<Int?>

    @Upsert
    suspend fun upsert(entity: ReelStatsEntity)

    @Query("SELECT * FROM reel_stats ORDER BY date DESC")
    suspend fun getAll(): List<ReelStatsEntity>

    @Query("SELECT DISTINCT date FROM reel_stats")
    suspend fun getDistinctDates(): List<String>

    @Query("DELETE FROM reel_stats WHERE date IN (:dates)")
    suspend fun deleteByDates(dates: List<String>)
}
