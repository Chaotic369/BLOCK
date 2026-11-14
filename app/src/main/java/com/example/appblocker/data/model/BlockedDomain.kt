package com.example.appblocker.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Calendar

@Entity(tableName = "blocked_domains")
data class BlockedDomain(
    @PrimaryKey val domain: String,
    val blockedUntil: Long,
    /** > 0: daily limit. The site may be used this long per day, then it is blocked until [blockedUntil]. */
    @ColumnInfo(defaultValue = "0") val allowanceMs: Long = 0L,
    /** Time spent on the site on the day starting at [usedDay]. */
    @ColumnInfo(defaultValue = "0") val usedMs: Long = 0L,
    /** Local midnight (epoch ms) of the day [usedMs] belongs to. */
    @ColumnInfo(defaultValue = "0") val usedDay: Long = 0L
) {
    /** Time used today; 0 once the stored day has passed. */
    fun usedToday(now: Long): Long = if (usedDay == startOfDay(now)) usedMs else 0L

    /** True while the site must be refused: a plain timed block, or a daily limit that has run out. */
    fun isEnforcing(now: Long): Boolean =
        blockedUntil > now && (allowanceMs <= 0L || usedToday(now) >= allowanceMs)

    companion object {
        fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
