package com.example.appblocker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.appblocker.data.model.BlockedApp
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppDao {

    @Query("SELECT * FROM blocked_apps ORDER BY blockedUntil DESC")
    fun observeAll(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps")
    suspend fun getAll(): List<BlockedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: BlockedApp)

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM blocked_apps WHERE blockedUntil <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("SELECT COUNT(*) FROM blocked_apps WHERE blockedUntil > :now")
    suspend fun countActive(now: Long): Int

    @Query("SELECT MIN(blockedUntil) FROM blocked_apps WHERE blockedUntil > :now")
    suspend fun nextExpiry(now: Long): Long?

}
