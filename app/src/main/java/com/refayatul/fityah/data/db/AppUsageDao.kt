package com.refayatul.fityah.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageDao {

    @Query("SELECT * FROM app_usage_stats WHERE date = :date AND packageName = :packageName")
    suspend fun get(date: String, packageName: String): AppUsageEntity?

    @Query("SELECT * FROM app_usage_stats WHERE date = :date")
    suspend fun getForDate(date: String): List<AppUsageEntity>

    @Query("SELECT * FROM app_usage_stats WHERE date = :date")
    fun observeForDate(date: String): Flow<List<AppUsageEntity>>

    @Query("SELECT * FROM app_usage_stats WHERE date IN (:dates)")
    suspend fun getForDates(dates: List<String>): List<AppUsageEntity>

    @Query("SELECT * FROM app_usage_stats WHERE packageName = :packageName")
    suspend fun getForPackage(packageName: String): List<AppUsageEntity>

    @Upsert
    suspend fun upsert(entity: AppUsageEntity)

    @Query("SELECT MIN(lastUsed) FROM app_usage_stats WHERE lastUsed > 0")
    suspend fun earliestTimestamp(): Long?

    @Query("SELECT DISTINCT date FROM app_usage_stats")
    suspend fun getDistinctDates(): List<String>

    @Query("DELETE FROM app_usage_stats WHERE date IN (:dates)")
    suspend fun deleteByDates(dates: List<String>)
}
