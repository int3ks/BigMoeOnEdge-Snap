package com.bigmoe.onedge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bigmoe.onedge.data.local.entity.PerfEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PerfEventDao {
    @Insert
    suspend fun insert(event: PerfEventEntity): Long

    @Query("SELECT * FROM perf_events ORDER BY at ASC, id ASC")
    suspend fun all(): List<PerfEventEntity>

    @Query("SELECT COUNT(*) FROM perf_events WHERE kind = :kind")
    fun countFlow(kind: String): Flow<Int>

    @Query("DELETE FROM perf_events")
    suspend fun clear()
}
