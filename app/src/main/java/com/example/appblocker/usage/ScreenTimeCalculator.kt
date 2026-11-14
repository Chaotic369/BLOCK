package com.example.appblocker.usage

object ScreenTimeCalculator {

    /** Average of daily totals, counting only days that have any usage. Returns 0 if none. */
    fun average(dailyTotals: List<Long>): Long {
        val active = dailyTotals.filter { it > 0 }
        if (active.isEmpty()) return 0L
        return active.sum() / active.size
    }

    fun total(dailyTotals: List<Long>): Long = dailyTotals.sum()
}
