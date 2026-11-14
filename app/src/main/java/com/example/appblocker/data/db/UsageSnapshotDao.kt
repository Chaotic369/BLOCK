package com.example.appblocker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.appblocker.data.model.UsageSnapshot

@Dao
interface UsageSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: UsageSnapshot)

    @Query("SELECT * FROM usage_snapshots WHERE dayStart >= :since ORDER BY dayStart ASC")
    suspend fun since(since: Long): List<UsageSnapshot>

    @Query("DELETE FROM usage_snapshots WHERE dayStart < :before")
    suspend fun deleteOlderThan(before: Long)
}
