package com.refayatul.fityah.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IntentLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(intentLog: IntentLogEntity)

    @Query("SELECT * FROM intent_logs ORDER BY timestamp DESC")
    fun getAllIntentLogs(): Flow<List<IntentLogEntity>>

    @Query("SELECT * FROM intent_logs ORDER BY timestamp DESC")
    suspend fun getAllIntentLogsSync(): List<IntentLogEntity>
    
    @Query("DELETE FROM intent_logs WHERE id = :logId")
    suspend fun delete(logId: Int)
}
