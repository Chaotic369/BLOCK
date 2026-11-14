package com.example.appblocker.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val label: String,
    val blockedUntil: Long,
    /** > 0: daily limit. The app may be used this long (counted from [sinceMs]); after that it is locked until [blockedUntil]. */
    @ColumnInfo(defaultValue = "0") val allowanceMs: Long = 0L,
    /** When the daily limit was set; usage is counted from here. */
    @ColumnInfo(defaultValue = "0") val sinceMs: Long = 0L
)
