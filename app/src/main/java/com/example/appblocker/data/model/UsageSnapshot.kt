package com.example.appblocker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_snapshots")
data class UsageSnapshot(
    @PrimaryKey val dayStart: Long,
    val totalMs: Long,
    val topPackage: String?,
    val topPackageMs: Long
)
