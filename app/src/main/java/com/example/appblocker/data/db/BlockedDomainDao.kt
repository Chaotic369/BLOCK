package com.example.appblocker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.appblocker.data.model.BlockedDomain
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedDomainDao {

    @Query("SELECT * FROM blocked_domains ORDER BY blockedUntil DESC")
    fun observeAll(): Flow<List<BlockedDomain>>

    /** Adds time spent on a site; starts a fresh count when [day] is a new day. */
    @Query(
        "UPDATE blocked_domains SET " +
            "usedMs = CASE WHEN usedDay = :day THEN usedMs + :deltaMs ELSE :deltaMs END, " +
            "usedDay = :day WHERE domain = :domain"
    )
    suspend fun addUsage(domain: String, day: Long, deltaMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(domain: BlockedDomain)

    @Query("DELETE FROM blocked_domains WHERE domain = :domain")
    suspend fun delete(domain: String)

    @Query("DELETE FROM blocked_domains WHERE blockedUntil <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("SELECT COUNT(*) FROM blocked_domains WHERE blockedUntil > :now")
    suspend fun countActive(now: Long): Int

    @Query("SELECT MIN(blockedUntil) FROM blocked_domains WHERE blockedUntil > :now")
    suspend fun nextExpiry(now: Long): Long?

}
