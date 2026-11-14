package com.example.appblocker.data.model

data class AppUsage(
    val packageName: String,
    val label: String,
    val totalMs: Long
)
